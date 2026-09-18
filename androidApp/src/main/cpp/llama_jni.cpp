// JNI glue for llama.cpp (MIT), used by app.tsumugi.android.platform.LlamaJni.
// All calls for one session come from a single Kotlin worker thread, so a session needs no locking.
// Cancellation happens in Kotlin: the token sink returns false (LlamaJni tracks a per-generation id, F-10).
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <mutex>
#include <random>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "tsumugi-llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {

constexpr int kChunk = 512;

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    std::vector<llama_token> cached; // tokens held in the KV cache (prompt + generated), for prefix reuse
};

std::once_flag g_backend_once;

std::string to_string(JNIEnv *env, jbyteArray bytes) {
    if (bytes == nullptr) return {};
    const jsize n = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

jstring error(JNIEnv *env, const std::string &message) { return env->NewStringUTF(message.c_str()); }

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text) {
    std::vector<llama_token> tokens(text.size() + 8);
    int n = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(),
                           static_cast<int32_t>(tokens.size()), true, true);
    if (n < 0) {
        tokens.resize(static_cast<size_t>(-n));
        n = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(),
                           static_cast<int32_t>(tokens.size()), true, true);
    }
    tokens.resize(static_cast<size_t>(std::max(0, n)));
    return tokens;
}

std::string piece(const llama_vocab *vocab, llama_token token) {
    char buf[128];
    int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, false);
    if (n >= 0) return std::string(buf, static_cast<size_t>(n));
    std::string big(static_cast<size_t>(-n), '\0');
    n = llama_token_to_piece(vocab, token, big.data(), static_cast<int32_t>(big.size()), 0, false);
    big.resize(static_cast<size_t>(std::max(0, n)));
    return big;
}

/// Length of the longest prefix of [s] that ends on a UTF-8 character boundary.
size_t complete_utf8_prefix(const std::string &s) {
    size_t i = s.size();
    size_t back = 0;
    while (i > 0 && back < 4) {
        const auto c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) != 0x80) { // lead byte (or ASCII)
            size_t need = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
            return (back + 1 >= need) ? s.size() : i - 1;
        }
        --i;
        ++back;
    }
    return s.size();
}

/// A fresh sampler seed per generation (F-29), so the same prompt doesn't give the same reply every session.
/// llama.cpp would also pick one for LLAMA_DEFAULT_SEED, but being explicit keeps parity with iOS and doesn't depend
/// on that convention. Mixes std::random_device with the clock, since random_device may be a PRNG on some libcs.
uint32_t fresh_seed() {
    uint64_t seed = static_cast<uint64_t>(std::chrono::steady_clock::now().time_since_epoch().count());
    try {
        std::random_device rd;
        seed ^= (static_cast<uint64_t>(rd()) << 32) | rd();
    } catch (...) {
        // no entropy source: the clock alone still differs per call
    }
    seed ^= seed >> 33;
    seed *= 0xff51afd7ed558ccdULL; // splitmix-style finalizer spreads the clock bits
    seed ^= seed >> 33;
    auto out = static_cast<uint32_t>(seed);
    return out == LLAMA_DEFAULT_SEED ? out - 1 : out;
}

/// Decodes [prompt], reusing the KV cache for the longest prefix it shares with what's cached.
bool evaluate(Session *s, const std::vector<llama_token> &prompt) {
    size_t common = 0;
    while (common < s->cached.size() && common < prompt.size() && s->cached[common] == prompt[common]) ++common;
    if (common == prompt.size() && common > 0) --common; // always decode at least one token for fresh logits
    llama_memory_t mem = llama_get_memory(s->ctx);
    if (common == 0 || !llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(common), -1)) {
        llama_memory_clear(mem, true);
        common = 0;
    }
    s->cached.assign(prompt.begin(), prompt.begin() + static_cast<long>(common));
    for (size_t i = common; i < prompt.size(); i += kChunk) {
        std::vector<llama_token> slice(prompt.begin() + static_cast<long>(i),
                                       prompt.begin() + static_cast<long>(std::min(prompt.size(), i + kChunk)));
        if (llama_decode(s->ctx, llama_batch_get_one(slice.data(), static_cast<int32_t>(slice.size()))) != 0) {
            llama_memory_clear(mem, true);
            s->cached.clear();
            return false;
        }
        s->cached.insert(s->cached.end(), slice.begin(), slice.end());
    }
    return true;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_tsumugi_android_platform_LlamaNative_nativeLoad(JNIEnv *env, jclass, jstring jpath, jint n_ctx, jint n_threads) {
    std::call_once(g_backend_once, [] { llama_backend_init(); });
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU backend only on Android
    llama_model *model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (model == nullptr) return 0;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(std::max(512, static_cast<int>(n_ctx)));
    cparams.n_batch = kChunk;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        return 0;
    }
    auto *s = new Session();
    s->model = model;
    s->ctx = ctx;
    LOGI("loaded model, n_ctx=%u threads=%d", llama_n_ctx(ctx), n_threads);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_app_tsumugi_android_platform_LlamaNative_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return;
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}

/// Generates up to [max_tokens] tokens for [jprompt] (UTF-8 bytes). Each complete UTF-8 chunk is passed to
/// `sink.onPiece(byte[]): boolean`; returning false stops generation (stop string found). Returns null on
/// success or an error message.
JNIEXPORT jstring JNICALL
Java_app_tsumugi_android_platform_LlamaNative_nativeGenerate(JNIEnv *env, jclass, jlong handle, jbyteArray jprompt,
                                                             jbyteArray jgrammar, jint max_tokens, jfloat temperature,
                                                             jobject sink) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return error(env, "No model loaded");
    jmethodID on_piece = env->GetMethodID(env->GetObjectClass(sink), "onPiece", "([B)Z");
    if (on_piece == nullptr) return error(env, "sink has no onPiece([B)Z");

    const llama_vocab *vocab = llama_model_get_vocab(s->model);
    const std::vector<llama_token> tokens = tokenize(vocab, to_string(env, jprompt));
    const auto n_ctx = static_cast<size_t>(llama_n_ctx(s->ctx));
    if (tokens.size() + static_cast<size_t>(max_tokens) >= n_ctx) {
        return error(env, "Prompt too long: " + std::to_string(tokens.size()) + " tokens + " +
                              std::to_string(max_tokens) + " to generate > context " + std::to_string(n_ctx));
    }
    if (!evaluate(s, tokens)) return error(env, "Decoding the prompt failed");

    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    const std::string grammar = to_string(env, jgrammar);
    if (!grammar.empty()) {
        llama_sampler *g = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (g == nullptr) {
            llama_sampler_free(chain);
            return error(env, "Invalid GBNF grammar");
        }
        llama_sampler_chain_add(chain, g);
    }
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(std::max(0.0f, temperature)));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(fresh_seed()));

    jstring result = nullptr;
    std::string pending;
    for (int i = 0; i < max_tokens; ++i) {
        llama_token token = llama_sampler_sample(chain, s->ctx, -1); // also accepts the token
        if (llama_vocab_is_eog(vocab, token)) break;
        pending += piece(vocab, token);
        const size_t ready = complete_utf8_prefix(pending);
        if (ready > 0) {
            jbyteArray chunk = to_bytes(env, pending.substr(0, ready));
            pending.erase(0, ready);
            const jboolean more = env->CallBooleanMethod(sink, on_piece, chunk);
            env->DeleteLocalRef(chunk);
            if (env->ExceptionCheck()) break;
            if (!more) break;
        }
        if (llama_decode(s->ctx, llama_batch_get_one(&token, 1)) != 0) {
            result = error(env, "Decoding failed after " + std::to_string(i) + " tokens");
            break;
        }
        s->cached.push_back(token);
    }
    llama_sampler_free(chain);
    return result;
}

} // extern "C"
