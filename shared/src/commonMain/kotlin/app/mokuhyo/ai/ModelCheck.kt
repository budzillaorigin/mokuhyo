package app.mokuhyo.ai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * `model_check` (BRIEF_PHASE8 N-00b, Settings → AI → "Test the model"): one tiny structured turn through the same
 * gateway, schema and validation path every feature uses, so a failure here shows the real reason verbatim.
 */
class ModelCheck : PromptTask<String, ModelCheck.Output> {
    @Serializable
    data class Output(val reply: String, val language: String)

    override val name = "model_check"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val maxTokens = 80
    override val temperature = 0.0
    override val schema = JsonSchema.Obj(listOf("reply" to JsonSchema.Str(maxLength = 120), "language" to JsonSchema.Str(maxLength = 30)))

    override fun messages(input: String): List<ChatMessage> = listOf(
        ChatMessage(Role.SYSTEM, "You are checking that a language model works. Answer in JSON only."),
        ChatMessage(Role.USER, "Reply with one short friendly sentence in $input, and name the language in English."),
    )

    override fun validate(input: String, output: Output, context: ValidationContext): List<String> =
        listOfNotNull(if (output.reply.isBlank()) "empty reply" else null)
}
