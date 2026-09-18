package app.tsumugi.ai

/**
 * Keeps prompts inside a model's context window (F-23). There is no tokenizer count on the Kotlin side, so tokens
 * are estimated from characters, deliberately on the high side: Qwen2.5's BPE averages ~1 token per CJK
 * character and ~4 Latin characters per token, so each non-ASCII character counts 1 and every 3 ASCII
 * characters count 1, plus a few tokens of ChatML framing per message.
 */
object ContextWindow {
    /** Room kept free for the JSON contract, chat template and estimate error. */
    const val DEFAULT_MARGIN = 256
    private const val PER_MESSAGE = 4

    fun estimateTokens(text: String): Int {
        var ascii = 0
        var other = 0
        for (c in text) if (c.code < 0x80) ascii++ else other++
        return other + (ascii + 2) / 3
    }

    fun estimateTokens(messages: List<ChatMessage>): Int = messages.sumOf { estimateTokens(it.content) + PER_MESSAGE }

    /** Prompt budget: `n_ctx − maxTokens − margin`, never negative. */
    fun budget(contextSize: Int, maxTokens: Int, margin: Int = DEFAULT_MARGIN): Int =
        (contextSize - maxTokens - margin).coerceAtLeast(0)

    /**
     * The longest suffix of [items] (the most recent turns) whose estimated size, added to [fixedTokens] (system
     * prompt and instructions), fits [budget]. Always drops from the oldest end; returns an empty list if even the
     * last item doesn't fit.
     */
    fun <T> fitLatest(items: List<T>, fixedTokens: Int, budget: Int, size: (T) -> Int): List<T> {
        var used = fixedTokens
        var from = items.size
        while (from > 0) {
            val next = used + size(items[from - 1])
            if (next > budget) break
            used = next
            from--
        }
        return items.subList(from, items.size)
    }
}
