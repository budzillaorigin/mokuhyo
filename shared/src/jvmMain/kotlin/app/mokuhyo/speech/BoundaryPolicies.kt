package app.mokuhyo.speech

/** The shipped [BoundaryPolicy] (resource `boundary_policy.json`, shared with tools/voices/boundary.py). */
object BoundaryPolicies {
    val default: BoundaryPolicy by lazy {
        val text = BoundaryPolicies::class.java.getResourceAsStream("/app/mokuhyo/speech/boundary_policy.json")?.use { it.readBytes().decodeToString() }
            ?: error("boundary_policy.json is missing from the build")
        BoundaryPolicy.parse(text)
    }
}
