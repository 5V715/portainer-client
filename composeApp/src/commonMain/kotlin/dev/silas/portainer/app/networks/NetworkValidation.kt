package dev.silas.portainer.app.networks

private fun isIpv4(s: String): Boolean {
    val parts = s.split('.')
    return parts.size == 4 && parts.all { p ->
        p.isNotEmpty() && p.length <= 3 && (p.length == 1 || p[0] != '0') && p.all { it in '0'..'9' } && p.toInt() in 0..255
    }
}

private fun isIpv6(s: String): Boolean {
    if (!s.contains(':') || s.any { it != ':' && it != '.' && it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return false
    val doubles = Regex("::").findAll(s).count()
    if (doubles > 1 || s.contains(":::")) return false
    var text = s
    // An embedded IPv4 tail (::ffff:1.2.3.4) counts as two groups.
    if (text.contains('.')) {
        val tail = text.substringAfterLast(':')
        if (!isIpv4(tail)) return false
        text = text.removeSuffix(tail) + "0:0"
    }
    if (text.contains('.')) return false
    // A single leading/trailing ':' is only valid as half of '::'.
    if ((text.startsWith(":") && !text.startsWith("::")) || (text.endsWith(":") && !text.endsWith("::"))) return false
    val groups = text.split(':')
    val nonEmpty = groups.filter { it.isNotEmpty() }
    if (nonEmpty.any { it.length > 4 }) return false
    val count = nonEmpty.size
    return if (doubles == 1) count < 8 else count == 8 && groups.none { it.isEmpty() }
}

/** `a.b.c.d` or an IPv6 address, no prefix length. */
fun isValidIp(s: String): Boolean = isIpv4(s) || isIpv6(s)

/** IPv4 CIDR (`a.b.c.d/n`, octets 0-255, n 0-32) or IPv6 CIDR (contains ':' and `/n`, n 0-128). */
fun isValidCidr(s: String): Boolean {
    val slash = s.indexOf('/')
    if (slash < 0 || s.indexOf('/', slash + 1) >= 0) return false
    val address = s.substring(0, slash)
    val prefix = s.substring(slash + 1)
    if (prefix.isEmpty() || prefix.length > 3 || !prefix.all { it in '0'..'9' }) return false
    val n = prefix.toInt()
    return if (address.contains(':')) isIpv6(address) && n in 0..128 else isIpv4(address) && n in 0..32
}
