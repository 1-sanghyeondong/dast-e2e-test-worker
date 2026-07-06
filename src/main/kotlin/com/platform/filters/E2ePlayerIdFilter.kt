package com.platform.filters

import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.ExchangeFunction
import reactor.core.publisher.Mono

private const val HEADER_KEY_E2E_PLAYER_ID = "X-E2E-Player-Id"
private const val HEADER_KEY_E2E_TEST_RUN = "X-Test-Run"

/**
 * E2E 테스트 데이터 격리 필터
 * 모든 WebClient 요청에 E2E 전용 헤더를 자동으로 주입
 */
class E2ePlayerIdFilter(private val playerId: String) : ExchangeFilterFunction {
    override fun filter(request: ClientRequest, next: ExchangeFunction): Mono<ClientResponse> {
        val modified: ClientRequest = ClientRequest.from(request)
            .header(HEADER_KEY_E2E_PLAYER_ID, playerId)
            .header(HEADER_KEY_E2E_TEST_RUN, "true")
            .build()

        return next.exchange(modified)
    }

    companion object {
        fun of(playerId: String): E2ePlayerIdFilter = E2ePlayerIdFilter(playerId)
    }
}