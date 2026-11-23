package com.x1colegal.browserengine

internal enum class TlsCipherSuite(
    val suiteName: String,
    val preference: String,
    val enabledByDefault: Boolean = true
) {
    TLS13_AES128("TLS_AES_128_GCM_SHA256", "security.tls13.aes_128_gcm_sha256"),
    TLS13_AES256("TLS_AES_256_GCM_SHA384", "security.tls13.aes_256_gcm_sha384"),
    TLS13_CHACHA("TLS_CHACHA20_POLY1305_SHA256", "security.tls13.chacha20_poly1305_sha256"),
    ECDSA_AES128_GCM("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "security.ssl3.ecdhe_ecdsa_aes_128_gcm_sha256"),
    ECDSA_AES256_GCM("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "security.ssl3.ecdhe_ecdsa_aes_256_gcm_sha384"),
    ECDSA_CHACHA("TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256", "security.ssl3.ecdhe_ecdsa_chacha20_poly1305_sha256"),
    ECDHE_RSA_AES128_GCM("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "security.ssl3.ecdhe_rsa_aes_128_gcm_sha256"),
    ECDHE_RSA_AES256_GCM("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384", "security.ssl3.ecdhe_rsa_aes_256_gcm_sha384"),
    ECDHE_RSA_CHACHA("TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256", "security.ssl3.ecdhe_rsa_chacha20_poly1305_sha256"),
    ECDSA_AES128_CBC("TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA", "security.ssl3.ecdhe_ecdsa_aes_128_sha"),
    ECDSA_AES256_CBC("TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA", "security.ssl3.ecdhe_ecdsa_aes_256_sha"),
    ECDHE_RSA_AES128_CBC("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", "security.ssl3.ecdhe_rsa_aes_128_sha"),
    ECDHE_RSA_AES256_CBC("TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA", "security.ssl3.ecdhe_rsa_aes_256_sha"),
    DHE_RSA_AES128_CBC("TLS_DHE_RSA_WITH_AES_128_CBC_SHA", "security.ssl3.dhe_rsa_aes_128_sha", false),
    DHE_RSA_AES256_CBC("TLS_DHE_RSA_WITH_AES_256_CBC_SHA", "security.ssl3.dhe_rsa_aes_256_sha", false),
    RSA_AES128_GCM("TLS_RSA_WITH_AES_128_GCM_SHA256", "security.ssl3.rsa_aes_128_gcm_sha256"),
    RSA_AES256_GCM("TLS_RSA_WITH_AES_256_GCM_SHA384", "security.ssl3.rsa_aes_256_gcm_sha384"),
    RSA_AES128_CBC("TLS_RSA_WITH_AES_128_CBC_SHA", "security.ssl3.rsa_aes_128_sha"),
    RSA_AES256_CBC("TLS_RSA_WITH_AES_256_CBC_SHA", "security.ssl3.rsa_aes_256_sha");

    val tls13 get() = preference.startsWith("security.tls13.")
}
