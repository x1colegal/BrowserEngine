package com.x1colegal.browserengine

internal fun httpVersionLabel(value: String): String = when (value.uppercase()) {
    "HTTP/2.0", "HTTP/2", "H2" -> "HTTP/2"
    "HTTP/3.0", "HTTP/3", "H3" -> "HTTP/3"
    else -> value
}

internal fun tlsVersionLabel(value: String): String = when (value.lowercase()) {
    "tlsv1", "tlsv1.0" -> "TLS 1.0"
    "tlsv1.1" -> "TLS 1.1"
    "tlsv1.2" -> "TLS 1.2"
    "tlsv1.3" -> "TLS 1.3"
    else -> value
}

internal fun keyExchangeLabel(value: String): String = when (value.lowercase()) {
    "mlkem768x25519", "x25519mlkem768" -> "X25519MLKEM768"
    "mlkem1024secp384r1", "secp384r1mlkem1024" -> "SecP384r1MLKEM1024"
    "mlkem768secp256r1", "secp256r1mlkem768" -> "SecP256r1MLKEM768"
    "x25519" -> "X25519"
    "x448" -> "X448"
    "secp256r1" -> "P-256"
    "secp384r1" -> "P-384"
    "secp521r1" -> "P-521"
    else -> if (value.startsWith("ffdhe", ignoreCase = true)) value.uppercase() else value
}

internal fun remoteAddressLabel(value: String): String {
    val address = value.trim().removeSurrounding("[", "]")
    val mapped = address.takeIf { it.startsWith("::ffff:", ignoreCase = true) }?.substring(7)
    return when {
        address.isEmpty() -> ""
        mapped != null && '.' in mapped -> "$mapped (IPv4)"
        ':' in address -> "$address (IPv6)"
        '.' in address -> "$address (IPv4)"
        else -> address
    }
}
