import Foundation
import Shared
#if canImport(llama)
import llama
#endif

/// On-device LLM inference with llama.cpp (MIT) behind the shared `LocalLlmBridge` (BRIEF §3.5, §7.1).
///
/// - GGUF models load with Metal offload of every layer.
/// - Sampling: optional GBNF grammar → top-k 40 → repeat penalty 1.1 → top-p 0.9 → temperature → random draw.
/// - Tokens stream to `onToken` on a private serial queue; `onDone` also fires there.
/// - KV-cache reuse: when a new prompt starts with the tokens already evaluated (a growing conversation),
///   only the new suffix is decoded.
///
/// Cancellation (F-10, tools/models/README.md, DECISIONS D-057/D-073): every `generate` gets an increasing id.
/// `cancel()` and `unload()` stop every generation issued so far, and only those: the id they cancel through is a
/// high-water mark that is never reset, so a late cancel from a timed-out call can't stop the next call, and a new
/// call is never blocked behind a runaway one. The mark is checked inside the queued job, before the prompt and
/// before every token. A stopped generation ends with `onDone(nil, "cancelled…")`, never with its partial text.
///
/// The pinned llama.xcframework (tools/models/fetch_ios_frameworks.sh) ships no iOS Simulator slice, so
/// simulator builds compile without `llama` and report that on-device inference is unavailable.
final class LlamaBridge: NSObject, LocalLlmBridge, @unchecked Sendable {
    /// Mirrors `context != nil` so `isLoaded()` never blocks on (or deadlocks with) the work queue.
    private let loadedFlag = LockedFlag()
    private let queue = DispatchQueue(label: "app.tsumugi.llama", qos: .userInitiated)
    private let lock = NSLock()
    /// Last generation id handed out, and the highest id that has been cancelled (both under [lock]).
    private var issued: UInt64 = 0
    private var cancelledThrough: UInt64 = 0
    private var cancelReason = "cancelled"

    #if canImport(llama)
    private var model: OpaquePointer?
    private var context: OpaquePointer? {
        didSet { let value = context != nil; loadedFlag.withLock { $0 = value } }
    }
    /// Tokens currently held in the KV cache (prompt + generated), for prefix reuse.
    private var cached: [llama_token] = []
    private static let backendInit: Void = { llama_backend_init() }()
    #endif

    func isLoaded() -> Bool {
        #if canImport(llama)
        return loadedFlag.withLock { $0 }
        #else
        return false
        #endif
    }

    func load(modelPath: String, contextSize: Int32, onDone: @escaping (String?) -> Void) {
        #if canImport(llama)
        queue.async { [self] in
            _ = Self.backendInit
            freeAll()
            var mparams = llama_model_default_params()
            mparams.n_gpu_layers = -1 // negative = every layer on the GPU (Metal)
            guard let m = llama_model_load_from_file(modelPath, mparams) else {
                onDone("Couldn't load the model at \(modelPath)")
                return
            }
            var cparams = llama_context_default_params()
            cparams.n_ctx = UInt32(max(512, contextSize))
            cparams.n_batch = UInt32(Self.chunk)
            let threads = Int32(max(1, ProcessInfo.processInfo.activeProcessorCount - 2))
            cparams.n_threads = threads
            cparams.n_threads_batch = threads
            guard let c = llama_init_from_model(m, cparams) else {
                llama_model_free(m)
                onDone("Couldn't create a context (\(contextSize) tokens); try a smaller model")
                return
            }
            model = m
            context = c
            cached = []
            onDone(nil)
        }
        #else
        onDone("On-device inference isn't available in this build (llama.cpp isn't linked for this platform)")
        #endif
    }

    func generate(
        prompt: String,
        grammar: String?,
        maxTokens: Int32,
        temperature: Double,
        stop: [String],
        onToken: @escaping (String) -> Void,
        onDone: @escaping (String?, String?) -> Void
    ) {
        #if canImport(llama)
        let id = nextGeneration()
        queue.async { [self] in
            if let reason = cancelled(id) { onDone(nil, reason); return }
            guard let model, let context else { onDone(nil, "No model loaded"); return }
            let vocab = llama_model_get_vocab(model)
            let tokens = tokenize(vocab: vocab, text: prompt)
            let nCtx = Int(llama_n_ctx(context))
            guard tokens.count + Int(maxTokens) < nCtx else {
                onDone(nil, "Prompt too long: \(tokens.count) tokens + \(maxTokens) to generate > context \(nCtx)")
                return
            }
            guard evaluate(prompt: tokens, context: context) else {
                onDone(nil, "Decoding the prompt failed")
                return
            }

            let chain = llama_sampler_chain_init(llama_sampler_chain_default_params())
            defer { llama_sampler_free(chain) }
            if let grammar, !grammar.isEmpty {
                guard let g = llama_sampler_init_grammar(vocab, grammar, "root") else {
                    onDone(nil, "Invalid GBNF grammar")
                    return
                }
                llama_sampler_chain_add(chain, g)
            }
            // top-k first so the repeat penalty doesn't scan the whole vocabulary (llama.h advice)
            llama_sampler_chain_add(chain, llama_sampler_init_top_k(40))
            llama_sampler_chain_add(chain, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.1, 0, 0))
            llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.9, 1))
            llama_sampler_chain_add(chain, llama_sampler_init_temp(Float(max(0.0, temperature))))
            llama_sampler_chain_add(chain, llama_sampler_init_dist(UInt32.random(in: 0...UInt32.max)))

            var text = ""
            var pending: [UInt8] = []
            for _ in 0..<Int(maxTokens) {
                // Stopped (cancel, unload, memory warning): report the contract's "cancelled…", never partial text.
                if let reason = cancelled(id) { onDone(nil, reason); return }
                let token = llama_sampler_sample(chain, context, -1) // also accepts the token
                if llama_vocab_is_eog(vocab, token) { break }
                pending += piece(vocab: vocab, token: token)
                if let s = String(bytes: pending, encoding: .utf8) {
                    pending.removeAll()
                    text += s
                    if let cut = stop.compactMap({ text.range(of: $0) }).min(by: { $0.lowerBound < $1.lowerBound }) {
                        text = String(text[..<cut.lowerBound])
                        break
                    }
                    onToken(s)
                } else if pending.count > 8 {
                    let s = String(decoding: pending, as: UTF8.self)
                    pending.removeAll()
                    text += s
                    onToken(s)
                }
                var next = token
                let ok = withUnsafeMutablePointer(to: &next) { llama_decode(context, llama_batch_get_one($0, 1)) == 0 }
                cached.append(token)
                if !ok { onDone(nil, "Decoding failed after \(text.count) characters"); return }
            }
            onDone(text, nil)
        }
        #else
        onDone(nil, "On-device inference isn't available in this build")
        #endif
    }

    func cancel() { cancelIssued(reason: "cancelled") }

    /// Stops any generation (it reports "cancelled: unloaded"), then frees the model after it on the work queue.
    func unload() {
        #if canImport(llama)
        cancelIssued(reason: "cancelled: unloaded")
        queue.async { [self] in freeAll() }
        #endif
    }

    private func nextGeneration() -> UInt64 {
        lock.lock(); defer { lock.unlock() }
        issued += 1
        return issued
    }

    /// Cancels every generation issued so far (queued or running); later ones are unaffected.
    private func cancelIssued(reason: String) {
        lock.lock(); defer { lock.unlock() }
        cancelledThrough = issued
        cancelReason = reason
    }

    /// The contract's "cancelled…" error when generation [id] was stopped, else nil.
    private func cancelled(_ id: UInt64) -> String? {
        lock.lock(); defer { lock.unlock() }
        return id <= cancelledThrough ? cancelReason : nil
    }

    #if canImport(llama)
    private static let chunk = 512

    private func freeAll() {
        if let context { llama_free(context) }
        if let model { llama_model_free(model) }
        context = nil
        model = nil
        cached = []
    }

    private func tokenize(vocab: OpaquePointer?, text: String) -> [llama_token] {
        let utf8 = Array(text.utf8CString)
        let byteCount = Int32(utf8.count - 1)
        var tokens = [llama_token](repeating: 0, count: Int(byteCount) + 8)
        var n = llama_tokenize(vocab, text, byteCount, &tokens, Int32(tokens.count), true, true)
        if n < 0 {
            tokens = [llama_token](repeating: 0, count: Int(-n))
            n = llama_tokenize(vocab, text, byteCount, &tokens, Int32(tokens.count), true, true)
        }
        return Array(tokens.prefix(Int(max(0, n))))
    }

    private func piece(vocab: OpaquePointer?, token: llama_token) -> [UInt8] {
        var buffer = [CChar](repeating: 0, count: 64)
        var n = llama_token_to_piece(vocab, token, &buffer, Int32(buffer.count), 0, false)
        if n < 0 {
            buffer = [CChar](repeating: 0, count: Int(-n))
            n = llama_token_to_piece(vocab, token, &buffer, Int32(buffer.count), 0, false)
        }
        return buffer.prefix(Int(max(0, n))).map { UInt8(bitPattern: $0) }
    }

    /// Decodes [prompt], reusing the KV cache for the longest prefix it shares with what's already cached.
    private func evaluate(prompt: [llama_token], context: OpaquePointer) -> Bool {
        var common = 0
        while common < min(cached.count, prompt.count), cached[common] == prompt[common] { common += 1 }
        if common == prompt.count { common -= 1 } // always decode at least one token to get fresh logits
        let memory = llama_get_memory(context)
        if common <= 0 || !llama_memory_seq_rm(memory, 0, llama_pos(common), -1) {
            llama_memory_clear(memory, true)
            common = 0
        }
        cached = Array(prompt.prefix(common))
        var index = common
        while index < prompt.count {
            var slice = Array(prompt[index..<min(prompt.count, index + Self.chunk)])
            let ok = slice.withUnsafeMutableBufferPointer { buf in
                llama_decode(context, llama_batch_get_one(buf.baseAddress, Int32(buf.count))) == 0
            }
            if !ok {
                llama_memory_clear(memory, true)
                cached = []
                return false
            }
            cached += slice
            index += slice.count
        }
        return true
    }
    #endif
}

/// A Bool behind a lock, readable from any thread (shared by the native bridges).
final class LockedFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false

    func withLock<T>(_ body: (inout Bool) -> T) -> T {
        lock.lock(); defer { lock.unlock() }
        return body(&value)
    }
}
