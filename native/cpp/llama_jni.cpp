// JNI glue for llama.cpp (MIT), used by app.mokuhyo.ai.jni.LlamaJniBridge (via LlamaNative).
// All calls for one session come from a single Kotlin worker thread, so a session needs no locking. Cancellation:
// the token sink returns false between tokens, and a CancelToken aborts prompt decoding (F-10).
#include <jni.h>

#include <algorithm>
#include <chrono>
#include <random>
#include <string>
#include <vector>

#include "jni_common.h"
#include "llama.h"

using mokuhyo::AbortState;
using mokuhyo::CancelToken;
using mokuhyo::error;
using mokuhyo::to_bytes;
using mokuhyo::to_string;

namespace {

constexpr int kChunk = 512;

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    std::vector<llama_token> cached; // tokens held in the KV cache (prompt + generated), for prefix reuse
    AbortState abort;                // read by the abort callback while llama_decode runs
};

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
    // A chat template that writes the BOS text itself plus add_special gives two BOS tokens: keep one.
    const llama_token bos = llama_vocab_bos(vocab);
    if (bos != LLAMA_TOKEN_NULL && tokens.size() >= 2 && tokens[0] == bos && tokens[1] == bos) {
        tokens.erase(tokens.begin());
    }
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
/// Mixes std::random_device with the clock, since random_device may be a PRNG on some libcs.
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

enum class EvalResult { ok, failed, aborted };

/// Decodes [prompt], reusing the KV cache for the longest prefix it shares with what's cached.
EvalResult evaluate(Session *s, const std::vector<llama_token> &prompt) {
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
        if (s->abort.cancelled()) return EvalResult::aborted;
        std::vector<llama_token> slice(prompt.begin() + static_cast<long>(i),
                                       prompt.begin() + static_cast<long>(std::min(prompt.size(), i + kChunk)));
        const int rc = llama_decode(s->ctx, llama_batch_get_one(slice.data(), static_cast<int32_t>(slice.size())));
        if (rc != 0) {
            llama_memory_clear(mem, true);
            s->cached.clear();
            return rc == 2 || s->abort.cancelled() ? EvalResult::aborted : EvalResult::failed;
        }
        s->cached.insert(s->cached.end(), slice.begin(), slice.end());
    }
    return EvalResult::ok;
}

const char *role_name(jint role) {
    switch (role) {
        case 0: return "system";
        case 1: return "user";
        default: return "assistant";
    }
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_mokuhyo_ai_jni_LlamaNative_nativeNewCancelToken(JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(new CancelToken());
}

/// Cancels every call with id <= [through] (monotonic; never lowers the mark). Safe from any thread.
JNIEXPORT void JNICALL
Java_app_mokuhyo_ai_jni_LlamaNative_nativeCancelThrough(JNIEnv *, jclass, jlong token, jlong through) {
    auto *t = reinterpret_cast<CancelToken *>(token);
    if (t == nullptr) return;
    int64_t cur = t->through.load();
    while (cur < through && !t->through.compare_exchange_weak(cur, through)) {
    }
}

/// Loads a GGUF model. [n_gpu_layers] 0 = CPU only, 999 = everything on the GPU backend. Returns 0 on failure.
JNIEXPORT jlong JNICALL
Java_app_mokuhyo_ai_jni_LlamaNative_nativeLoad(JNIEnv *env, jclass, jbyteArray jpath, jint n_ctx, jint n_threads,
                                               jint n_gpu_layers) {
    const std::string path = to_string(env, jpath);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers;
    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) return 0;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(std::max(512, static_cast<int>(n_ctx)));
    cparams.n_batch = kChunk;
    cparams.n_ubatch = kChunk;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    cparams.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        return 0;
    }
    auto *s = new Session();
    s->model = model;
    s->ctx = ctx;
    llama_set_abort_callback(ctx, mokuhyo::abort_if_cancelled, &s->abort);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_app_mokuhyo_ai_jni_LlamaNative_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return;
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}

JNIEXPORT jint JNICALL
Java_app_mokuhyo_ai_jni_LlamaNative_nativeContextSize(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    return s == nullptr ? 0 : static_cast<jint>(llama_n_ctx(s->ctx));
}

/// Formats a chat with the model's own template (GGUF `tokenizer.chat_template`, matched by llama.cpp against its
/// built-in templates). [roles]: 0 system, 1 user, 2 assistant; [contents]: UTF-8. Returns the prompt as UTF-8,
/// or null when the model has no template or llama.cpp doesn't recognise it (the caller then uses ChatML).
JNIEXPORT jbyteArray JNICALL
Java_app_mokuhyo_ai_jni_LlamaNative_nativeApplyChatTemplate(JNIEnv *env, jclass, jlong handle, jintArray jroles,
                                                            jobjectArray jcontents) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return nullptr;
    const char *tmpl = llama_model_chat_template(s->model, nullptr);
    if (tmpl == nullptr || *tmpl == '\0') return nullptr;

    const jsize n = env->GetArrayLength(jroles);
    std::vector<jint> roles(static_cast<size_t>(n));
    env->GetIntArrayRegion(jroles, 0, n, roles.data());
    std::vector<std::string> contents;
    contents.reserve(static_cast<size_t>(n));
    size_t total = 0;
    for (jsize i = 0; i < n; ++i) {
        auto bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(jcontents, i));
        contents.push_back(to_string(env, bytes));
        env->DeleteLocalRef(bytes);
        total += contents.back().size();
    }
    std::vector<llama_chat_message> chat;
    chat.reserve(contents.size());
    for (size_t i = 0; i < contents.size(); ++i) chat.push_back({role_name(roles[i]), contents[i].c_str()});

    std::vector<char> buf(total * 2 + 1024);
    int32_t len = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buf.data(),
                                            static_cast<int32_t>(buf.size()));
    if (len > static_cast<int32_t>(buf.size())) {
        buf.resize(static_cast<size_t>(len));
        len = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buf.data(),
                                        static_cast<int32_t>(buf.size()));
    }
    if (len < 0) return nullptr;
    return to_bytes(env, std::string(buf.data(), static_cast<size_t>(len)));
}

/// Generates up to [max_tokens] tokens for [jprompt] (UTF-8). Each complete UTF-8 chunk goes to
/// `sink.onPiece(byte[]): boolean`; returning false stops generation (stop string found or cancelled).
/// [token]/[id] abort prompt decoding when the call is cancelled. Returns null on success or an error message
/// ("cancelled" when aborted).
JNIEXPORT jstring JNICALL
Java_app_mokuhyo_ai_jni_LlamaNative_nativeGenerate(JNIEnv *env, jclass, jlong handle, jbyteArray jprompt,
                                                   jbyteArray jgrammar, jint max_tokens, jfloat temperature,
                                                   jobject sink, jlong token, jlong id) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return error(env, "No model loaded");
    jclass sink_class = env->GetObjectClass(sink);
    jmethodID on_piece = env->GetMethodID(sink_class, "onPiece", "([B)Z");
    env->DeleteLocalRef(sink_class);
    if (on_piece == nullptr) return error(env, "sink has no onPiece([B)Z");
    s->abort.token = reinterpret_cast<CancelToken *>(token);
    s->abort.id = id;

    const llama_vocab *vocab = llama_model_get_vocab(s->model);
    const std::vector<llama_token> tokens = tokenize(vocab, to_string(env, jprompt));
    const auto n_ctx = static_cast<size_t>(llama_n_ctx(s->ctx));
    if (tokens.empty()) return error(env, "Empty prompt");
    if (tokens.size() + static_cast<size_t>(std::max(0, static_cast<int>(max_tokens))) >= n_ctx) {
        return error(env, "Prompt too long: " + std::to_string(tokens.size()) + " tokens + " +
                              std::to_string(max_tokens) + " to generate > context " + std::to_string(n_ctx));
    }
    switch (evaluate(s, tokens)) {
        case EvalResult::ok: break;
        case EvalResult::aborted: return error(env, "cancelled");
        case EvalResult::failed: return error(env, "Decoding the prompt failed");
    }

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
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(fresh_seed()));
    }

    jstring result = nullptr;
    std::string pending;
    for (int i = 0; i < max_tokens; ++i) {
        if (s->abort.cancelled()) {
            result = error(env, "cancelled");
            break;
        }
        llama_token tok = llama_sampler_sample(chain, s->ctx, -1); // also accepts the token
        if (llama_vocab_is_eog(vocab, tok)) break;
        pending += piece(vocab, tok);
        const size_t ready = complete_utf8_prefix(pending);
        if (ready > 0) {
            jbyteArray chunk = to_bytes(env, pending.substr(0, ready));
            pending.erase(0, ready);
            const jboolean more = env->CallBooleanMethod(sink, on_piece, chunk);
            env->DeleteLocalRef(chunk);
            if (env->ExceptionCheck()) break; // Kotlin rethrows it when this returns
            if (!more) break;
        }
        const int rc = llama_decode(s->ctx, llama_batch_get_one(&tok, 1));
        if (rc != 0) {
            llama_memory_clear(llama_get_memory(s->ctx), true);
            s->cached.clear();
            result = error(env, rc == 2 || s->abort.cancelled()
                                    ? std::string("cancelled")
                                    : "Decoding failed after " + std::to_string(i) + " tokens");
            break;
        }
        s->cached.push_back(tok);
    }
    llama_sampler_free(chain);
    return result;
}

JNIEXPORT jstring JNICALL
Java_app_mokuhyo_ai_jni_LlamaNative_nativeSystemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}

} // extern "C"
