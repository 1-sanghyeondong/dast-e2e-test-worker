package com.platform.configurations

import com.platform.filters.E2ePlayerIdFilter
import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.time.Duration
import java.util.concurrent.TimeUnit

const val E2E_WEB_CLIENT_BEAN_NAME = "e2eWebClient"

@Configuration
@EnableScheduling
@EnableConfigurationProperties(PlatformProperties::class)
class E2eWebClientConfig(private val props: PlatformProperties) {

    @Bean
    @Qualifier(E2E_WEB_CLIENT_BEAN_NAME)
    fun e2eWebClient(): WebClient {
        val http: PlatformProperties.HttpProperties = props.http
        val provider: ConnectionProvider =
            ConnectionProvider.builder("e2e-pool")
                .maxConnections(http.maxConnections)
                .pendingAcquireTimeout(Duration.ofMillis(http.pendingAcquireTimeoutMs))
                .maxIdleTime(Duration.ofMillis(http.maxIdleTimeMs))
                .evictInBackground(Duration.ofSeconds(30))
                .build()

        val httpClient: HttpClient =
            HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, http.connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(http.readTimeoutMs))
                .doOnConnected { conn ->
                    conn.addHandlerLast(ReadTimeoutHandler(http.readTimeoutMs, TimeUnit.MILLISECONDS))
                    conn.addHandlerLast(WriteTimeoutHandler(http.writeTimeoutMs, TimeUnit.MILLISECONDS))
                }

        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .filter(E2ePlayerIdFilter(props.playerId))
            .build()
    }

    @Bean
    fun e2eWebClientFactory(): E2eWebClientFactory = E2eWebClientFactory(props = props, baseClient = e2eWebClient())
}

class E2eWebClientFactory(
    private val props: PlatformProperties,
    private val baseClient: WebClient,
) {
    fun forTarget(targetName: String): WebClient {
        val target = props.targets.firstOrNull { it.name == targetName && it.enabled }
            ?: throw IllegalArgumentException("$targetName not found or disabled")

        val customReadTimeout = target.readTimeoutMs
        return if (customReadTimeout != null) {
            // 타겟 전용 타임아웃으로 새 커넥터 생성
            val http = props.http
            val httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, http.connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(customReadTimeout))
                .doOnConnected { conn ->
                    conn.addHandlerLast(ReadTimeoutHandler(customReadTimeout, TimeUnit.MILLISECONDS))
                    conn.addHandlerLast(WriteTimeoutHandler(http.writeTimeoutMs, TimeUnit.MILLISECONDS))
                }
            baseClient.mutate()
                .baseUrl(target.baseUrl)
                .clientConnector(ReactorClientHttpConnector(httpClient))
                .build()
        } else {
            baseClient.mutate().baseUrl(target.baseUrl).build()
        }
    }
}
