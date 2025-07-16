import java.util.*

data class KafkaSSLConfig(
    val bootstrapServers: String = "",
    val securityProtocol: String = "SSL",
    val sslKeystoreType: String = "JKS",
    val sslKeystoreLocation: String = "",
    val sslKeystorePassword: String = "",
    val sslKeyPassword: String = "",
    val sslTruststoreType: String = "JKS",
    val sslTruststoreLocation: String = "",
    val sslTruststorePassword: String = "",
    val sslProtocol: String = "TLSv1.2",
    val sslEnabledProtocols: String = "TLSv1.2",
    val sslEndpointIdentificationAlgorithm: String = ""
)

object SimplePropertiesLoader {
    fun loadFromResource(resourcePath: String): Properties {
        val props = Properties()
        val classLoader = Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()
        val stream = classLoader.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")
        props.load(stream)
        return props
    }
}

fun Properties.toKafkaSSLConfig(): KafkaSSLConfig {
    return KafkaSSLConfig(
        bootstrapServers = getProperty("bootstrap.servers") ?: "",
        securityProtocol = getProperty("security.protocol") ?: "SSL",
        sslKeystoreType = getProperty("ssl.keystore.type") ?: "JKS",
        sslKeystoreLocation = getProperty("ssl.keystore.location") ?: "",
        sslKeystorePassword = getProperty("ssl.keystore.password") ?: "",
        sslKeyPassword = getProperty("ssl.key.password") ?: "",
        sslTruststoreType = getProperty("ssl.truststore.type") ?: "JKS",
        sslTruststoreLocation = getProperty("ssl.truststore.location") ?: "",
        sslTruststorePassword = getProperty("ssl.truststore.password") ?: "",
        sslProtocol = getProperty("ssl.protocol") ?: "TLSv1.2",
        sslEnabledProtocols = getProperty("ssl.enabled.protocols") ?: "TLSv1.2",
        sslEndpointIdentificationAlgorithm = getProperty("ssl.endpoint.identification.algorithm") ?: ""
    )
}

fun KafkaSSLConfig.toProperties(): Properties {
    return Properties().apply {
        put("bootstrap.servers", bootstrapServers)
        put("security.protocol", securityProtocol)
        put("ssl.keystore.type", sslKeystoreType)
        put("ssl.keystore.location", resolveResourcePath(sslKeystoreLocation))
        put("ssl.keystore.password", sslKeystorePassword)
        put("ssl.key.password", sslKeyPassword)
        put("ssl.truststore.type", sslTruststoreType)
        put("ssl.truststore.location", resolveResourcePath(sslTruststoreLocation))
        put("ssl.truststore.password", sslTruststorePassword)
        put("ssl.protocol", sslProtocol)
        put("ssl.enabled.protocols", sslEnabledProtocols)
        put("ssl.endpoint.identification.algorithm", sslEndpointIdentificationAlgorithm)
    }
}

private fun resolveResourcePath(path: String): String {
    if (path.startsWith("/") || path.contains(":")) {
        return path
    }

    val classLoader = Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()
    val resourceUrl = classLoader.getResource(path)

    return resourceUrl?.path ?: path
}
