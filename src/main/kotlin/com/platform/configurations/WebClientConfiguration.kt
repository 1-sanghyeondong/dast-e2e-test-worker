package com.platform.configurations

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.time.Duration
import java.util.concurrent.TimeUnit

@Configuration
class WebClientConfiguration(
    @Value("\${dast.engine.base-url:http://localhost:8080}") private val baseUrl: String,
    @Value("\${dast.engine.connection-timeout-ms:3000}") private val connectTimeout: Int,
    @Value("\${dast.engine.read-timeout-ms:3000}") private val readTimeout: Long,
    @Value("\${dast.engine.write-timeout-ms:3000}") private val writeTimeout: Long,
    @Value("\${dast.engine.max-connections:100}") private val maxConnections: Int,
    @Value("\${dast.engine.pending-acquire-timeout-ms:5000}") private val pendingAcquireTimeout: Long,
    @Value("\${dast.engine.max-idle-time-ms:10000}") private val maxIdleTime: Long
) {
    @Bean
    fun webClient(): WebClient {
        val provider = ConnectionProvider.builder("dast-connection-pool")
            .maxConnections(maxConnections)
            .pendingAcquireTimeout(Duration.ofMillis(pendingAcquireTimeout))
            .maxIdleTime(Duration.ofMillis(maxIdleTime))
            .evictInBackground(Duration.ofSeconds(10))
            .build()

        val httpClient = HttpClient.create(provider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout)
            .responseTimeout(Duration.ofMillis(readTimeout))
            .doOnConnected { conn ->
                conn.addHandlerLast(ReadTimeoutHandler(readTimeout, TimeUnit.MILLISECONDS))
                conn.addHandlerLast(WriteTimeoutHandler(writeTimeout, TimeUnit.MILLISECONDS))
            }

        return WebClient.builder()
            .baseUrl(baseUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}