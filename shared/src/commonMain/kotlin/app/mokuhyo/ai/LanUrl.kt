package app.mokuhyo.ai

/**
 * A user-entered Ollama URL on the local network (BRIEF_PHASE8 N-00b: explicit opt-in, LAN only, per device), so a
 * GPU machine at home can serve the interviewer without exposing anything to the internet. Accepted hosts: localhost,
 * loopback, private IPv4 (10/8, 172.16/12, 192.168/16), link-local (169.254/16, fe80::/10), IPv6 unique-local
 * (fc00::/7) and `.local` / `.lan` / `.home.arpa` names. Everything else — public addresses and internet hostnames —
 * is refused: a cloud model would break rules 2 and 3.
 */
object LanUrl {
    data class Checked(val baseUrl: String?, val problem: String?)

    fun check(input: String): Checked {
        val raw = input.trim().removeSuffix("/").removeSuffix("/v1").removeSuffix("/")
        if (raw.isEmpty()) return Checked(null, "Enter an address such as http://192.168.1.46:11434")
        val m = Regex("""^(https?)://(\[[0-9A-Fa-f:]+]|[^/:\s]+)(?::(\d{1,5}))?$""").matchEntire(raw)
            ?: return Checked(null, "Use the form http://<address>:<port>, e.g. http://192.168.1.46:11434")
        val host = m.groupValues[2].removePrefix("[").removeSuffix("]").lowercase()
        val port = m.groupValues[3].ifEmpty { "11434" }.toInt()
        if (port !in 1..65535) return Checked(null, "Port $port is out of range")
        if (!isLan(host)) return Checked(null, "$host is not on your local network. Only local-network addresses are allowed (rules 2 and 3).")
        val shown = if (':' in host) "[$host]" else host
        return Checked("${m.groupValues[1]}://$shown:$port", null)
    }

    fun isLan(host: String): Boolean {
        if (host == "localhost") return true
        if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return true
        val v4 = host.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: return false }
        if (v4 != null) {
            if (v4.any { it !in 0..255 }) return false
            val (a, b) = v4[0] to v4[1]
            return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
        }
        if (':' in host) {
            val first = host.substringBefore(':').ifEmpty { "0" }.toIntOrNull(16) ?: return false
            return host == "::1" || (first and 0xffc0) == 0xfe80 || (first and 0xfe00) == 0xfc00
        }
        return false
    }
}
