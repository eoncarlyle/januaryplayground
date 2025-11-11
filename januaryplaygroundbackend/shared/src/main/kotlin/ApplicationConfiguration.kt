package com.iainschmitt.januaryplaygroundbackend.shared

import arrow.core.None
import java.util.*
import arrow.core.Option
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

data class ApplicationConfig(
    val database: String = "",
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
    val sslEndpointIdentificationAlgorithm: String = "",
)

data class LedgerKafkaTopics(
    val txLedger: String = ""
) {
    fun toList() = listOf(txLedger)
}

object SimplePropertiesLoader {
    fun loadFromResource(resourcePath: String): Option<Properties> {
        val classLoader = Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()

        return Option.fromNullable(classLoader.getResourceAsStream(resourcePath)).map { stream ->
            val props = Properties()
            props.load(stream)
            props
        }
    }

    fun loadFromFile(filePath: String): Option<Properties> {
        val path = Paths.get(filePath)
        return if (path.exists() && path.isRegularFile()) {
            Option.catch {
                Files.newInputStream(path).use { stream ->
                    Properties().apply { load(stream) }
                }
            }
        } else None
    }


    fun Properties.toKafkaSSLConfig(): ApplicationConfig =
        ApplicationConfig(
            database = getProperty("database") ?: "",
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

    fun Properties.toKafkaTopicsConfig(): LedgerKafkaTopics = LedgerKafkaTopics(
        txLedger = getProperty("kafka.topics.tx-ledger"),
    )

    fun ApplicationConfig.toProperties(): Properties {
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
        val resourceStream = classLoader.getResourceAsStream(path)

        return if (resourceStream != null && (path.endsWith(".jks") || path.endsWith(".p12") || path.endsWith(".pem"))) {
            val tempFile = kotlin.io.path.createTempFile(suffix = path.substringAfterLast("."))
            tempFile.toFile().deleteOnExit()
            Files.copy(resourceStream, tempFile, StandardCopyOption.REPLACE_EXISTING)
            tempFile.toString()
        } else {
            classLoader.getResource(path)?.path ?: path
        }
    }
}