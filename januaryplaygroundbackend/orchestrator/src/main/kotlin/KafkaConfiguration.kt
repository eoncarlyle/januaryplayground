import java.io.FileInputStream
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.javaType

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class PropertyKey(val key: String)

data class KafkaSSLConfig(
    @PropertyKey("bootstrap.servers")
    val bootstrapServers: String = "",

    @PropertyKey("security.protocol")
    val securityProtocol: String = "SSL",

    @PropertyKey("ssl.keystore.type")
    val sslKeystoreType: String = "JKS",

    @PropertyKey("ssl.keystore.location")
    val sslKeystoreLocation: String = "",

    @PropertyKey("ssl.keystore.password")
    val sslKeystorePassword: String = "",

    @PropertyKey("ssl.key.password")
    val sslKeyPassword: String = "",

    @PropertyKey("ssl.truststore.type")
    val sslTruststoreType: String = "JKS",

    @PropertyKey("ssl.truststore.location")
    val sslTruststoreLocation: String = "",

    @PropertyKey("ssl.truststore.password")
    val sslTruststorePassword: String = "",

    @PropertyKey("ssl.protocol")
    val sslProtocol: String = "TLSv1.2",

    @PropertyKey("ssl.enabled.protocols")
    val sslEnabledProtocols: String = "TLSv1.2",

    @PropertyKey("ssl.endpoint.identification.algorithm")
    val sslEndpointIdentificationAlgorithm: String = ""
)

object PropertiesDeserializer {
    inline fun <reified T : Any> deserializeFromResource(resourcePath: String): T {
        return deserializeFromResource(T::class, resourcePath)
    }

    fun <T : Any> deserialize(clazz: KClass<T>, fileName: String): T {
        val props = Properties().apply {
            load(FileInputStream(fileName))
        }
        return deserialize(clazz, props)
    }

    fun <T : Any> deserializeFromResource(clazz: KClass<T>, resourcePath: String): T {
        val props = Properties().apply {
            val classLoader = Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()
            val stream = classLoader.getResourceAsStream(resourcePath)
                ?: throw IllegalArgumentException("Resource not found: $resourcePath")
            load(stream)
        }
        return deserialize(clazz, props)
    }

    private fun <T : Any> deserialize(clazz: KClass<T>, props: Properties): T {
        val constructor = clazz.constructors.first()
        val args = constructor.parameters.map { param ->
            val property = clazz.declaredMemberProperties.find { it.name == param.name }
            val annotation = property?.findAnnotation<PropertyKey>()
            val key = annotation?.key ?: param.name

            val value = props.getProperty(key)

            when (param.type.javaType) {
                String::class.java -> value ?: ""
                Int::class.java -> value?.toIntOrNull() ?: 0
                Long::class.java -> value?.toLongOrNull() ?: 0L
                Boolean::class.java -> value?.toBooleanStrictOrNull() ?: false
                Double::class.java -> value?.toDoubleOrNull() ?: 0.0
                else -> throw IllegalArgumentException("Unsupported type: ${param.type}")
            }
        }.toTypedArray()

        return constructor.call(*args)
    }
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