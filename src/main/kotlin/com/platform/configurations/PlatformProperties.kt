package com.platform.configurations

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * E2E 워커 전역 설정 프로퍼티
 * application.yml 의 `e2e.*` 키에 바인딩
 */
@ConfigurationProperties(prefix = "e2e")
data class PlatformProperties(

    /** 테스트 데이터 격리용 전용 PlayerId */
    val playerId: String,

    val http: HttpProperties = HttpProperties(),

    /** 타겟 서버 목록 */
    val targets: List<TargetProperties> = emptyList(),

    /** 알림 설정 */
    val alert: AlertProperties,
) {

    data class HttpProperties(
        val connectTimeoutMs: Int = 3_000,
        val readTimeoutMs: Long = 5_000,
        val writeTimeoutMs: Long = 5_000,
        val maxConnections: Int = 100,
        val pendingAcquireTimeoutMs: Long = 5_000,
        val maxIdleTimeMs: Long = 10_000,
    )

    data class TargetProperties(
        val name: String = "",
        val baseUrl: String = "",
        val enabled: Boolean = true,
        /** null 이면 전역 http.read-timeout-ms 사용 */
        val readTimeoutMs: Long? = null,
    )

    data class AlertProperties(
        val webhookUrl: String,
        val jiraProjectKey: String,
        val jiraBaseUrl: String,
        val jiraPat: String
    )
}
