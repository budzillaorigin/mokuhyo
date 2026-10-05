package app.mokuhyo.ai

/**
 * Keeps prompts inside a model's context window (F-23). There is no tokenizer count on the Kotlin side, so tokens
 * are estimated from characters, deliberately on the high side: multilingual BPE vocabularies average ~1 token per CJK
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

    /**
     * [messages] cut to fit `contextSize − maxTokens − margin` (BRIEF_PHASE8 N-00b; it was never applied before, so long
     * conversations overflowed the context). Keeps a leading system message and the last message (the current turn)
     * whole when it can; drops the oldest of the turns in between first; if system + last still don't fit, shortens
     * the longer of them in its middle. Messages are never reordered.
     */
    fun fit(messages: List<ChatMessage>, contextSize: Int, maxTokens: Int, margin: Int = DEFAULT_MARGIN): List<ChatMessage> {
        val budget = budget(contextSize, maxTokens, margin)
        if (estimateTokens(messages) <= budget || messages.size <= 1) return shorten(messages, budget)
        val head = messages.first().takeIf { it.role == Role.SYSTEM }
        val last = messages.last()
        val middle = messages.subList(if (head != null) 1 else 0, messages.size - 1)
        val fixed = listOfNotNull(head, last).sumOf { estimateTokens(it.content) + PER_MESSAGE }
        val kept = fitLatest(middle, fixed, budget) { estimateTokens(it.content) + PER_MESSAGE }
        return shorten(listOfNotNull(head) + kept + last, budget)
    }

    /** Shortens the longest message (keeping its start and end) until the whole list fits [budget]. */
    private fun shorten(messages: List<ChatMessage>, budget: Int): List<ChatMessage> {
        var out = messages
        repeat(8) {
            val over = estimateTokens(out) - budget
            if (over <= 0) return out
            val i = out.indices.maxByOrNull { estimateTokens(out[it].content) } ?: return out
            val c = out[i].content
            val keepChars = (c.length - (over * c.length / estimateTokens(c).coerceAtLeast(1)) - 8).coerceAtLeast(0)
            val cut = if (keepChars <= 0) "" else c.take(keepChars / 2) + " … " + c.takeLast(keepChars / 2)
            out = out.toMutableList().also { l -> l[i] = out[i].copy(content = cut) }
        }
        return out
    }
}
