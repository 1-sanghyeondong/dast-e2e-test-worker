package com.platform.notifications

import com.platform.configurations.E2E_WEB_CLIENT_BEAN_NAME
import com.platform.configurations.PlatformProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBodilessEntity

/**
 * E2E 실패 알림 서비스
 * 심각도에 따라 다른 액션을 수행
 *
 *  [Severity.WARNING]  → 로그 출력 + 메세지 알림
 *  [Severity.CRITICAL] → WARNING 액션 전부 + JIRA 티켓 생성
 */
@Service
class NotificationService(
    @param:Qualifier(E2E_WEB_CLIENT_BEAN_NAME)
    private val webClient: WebClient,
    private val props: PlatformProperties,
) {
    private val logger = LoggerFactory.getLogger(NotificationService::class.java)

    enum class Severity { WARNING, CRITICAL }

    /**
     * 테스트 실패를 감지하고 심각도에 따라 알림을 발송합니다
     *
     * @param traceId      실패가 발생한 E2E 사이클 ID
     * @param scenario     실패 시나리오 이름 (A/B/C)
     * @param message      실패 메시지
     * @param cause        원인 예외 (nullable)
     * @param severity     알림 심각도
     */
    suspend fun notify(
        traceId: String,
        scenario: String,
        message: String,
        cause: Throwable? = null,
        severity: Severity = Severity.WARNING,
    ) {
        logger.error("{} | scenario: {}, severity: {}, traceId: {}", message, scenario, severity, traceId, cause)

        sendNotification(traceId = traceId, scenario = scenario, message = message, severity = severity)

        when (severity) {
            Severity.WARNING -> {}
            Severity.CRITICAL -> {
                createJiraTicket(traceId = traceId, scenario = scenario, message = message, cause = cause)
            }
        }
    }

    private suspend fun sendNotification(traceId: String, scenario: String, message: String, severity: Severity) {
        val webhookUrl: String = props.alert.webhookUrl
        if (webhookUrl.isBlank()) {
            return
        }

        val payload: Map<String, String> =
            mapOf("text" to "[E2E 테스트 실패] ${severity.name} | traceId: `$traceId`, scenario=`$scenario`\n```$message```")

        try {
            webClient.post()
                .uri(webhookUrl)
                .bodyValue(payload)
                .retrieve()
                .awaitBodilessEntity()
        } catch (ex: Exception) {
            logger.error("메세지 알림 발송 실패 | message: {}, traceId: {}, scenario: {}, severity: {}", message, traceId, scenario, severity, ex)
        }
    }

    suspend fun createJiraTicket(traceId: String, scenario: String, message: String, cause: Throwable?) {
        val jiraBaseUrl: String = props.alert.jiraBaseUrl
        val projectKey: String = props.alert.jiraProjectKey
        val jiraPat: String = props.alert.jiraPat

        if (jiraBaseUrl.isBlank() || jiraPat.isBlank()) {
            return
        }

        try {
            val summary: String = "[E2E 테스트 실패] $scenario 실패"

            // 동일 summary + 미완료 티켓이 이미 있으면 생성 생략
            // JQL `~` 텍스트 검색 시 `[` `]` 는 Jira custom field 문법과 충돌하므로 제거한 문자열로 검색
            val jqlSummary: String = "E2E 테스트 실패 $scenario 실패"
            val jql =
                """project = "$projectKey" AND summary ~ "$jqlSummary" AND statusCategory != Done ORDER BY created DESC"""

            @Suppress("UNCHECKED_CAST")
            val searchResult = webClient.post()
                .uri("$jiraBaseUrl/rest/api/2/search")
                .header("Authorization", "Bearer $jiraPat")
                .header("Content-Type", "application/json")
                .bodyValue(
                    mapOf(
                        "jql" to jql,
                        "maxResults" to 1,
                        "fields" to listOf("summary", "status"),
                    )
                )
                .retrieve()
                .bodyToMono(Map::class.java)
                .awaitSingleOrNull() as? Map<String, Any>

            val total: Int = (searchResult?.get("total") as? Int) ?: 0
            if (total > 0) {
                @Suppress("UNCHECKED_CAST")
                val existingKey = ((searchResult?.get("issues") as? List<Map<String, Any>>)?.firstOrNull())?.get("key")
                logger.info("지라 동일 미완료 티켓 이미 존재로 생성 생략 | key: {}", existingKey)
                return
            }

            // Jira Server REST API v2
            // description: plain string, assignee: name(로그인 ID)
            val description: String = buildString {
                appendLine("*traceId*: $traceId")
                appendLine("*scenario*: $scenario")
                appendLine()
                appendLine("*오류 메시지*")
                appendLine("{code}")
                appendLine(message)
                cause?.let { appendLine(it.stackTraceToString().take(2000)) }
                appendLine("{code}")
            }

            webClient.post()
                .uri("$jiraBaseUrl/rest/api/2/issue")
                .header("Authorization", "Bearer $jiraPat")
                .header("Content-Type", "application/json")
                .bodyValue(mapOf(
                    "fields" to mapOf(
                        "project" to mapOf("key" to projectKey),
                        "summary" to summary,
                        "description" to description,
                        "issuetype" to mapOf("name" to "Bug"),
                        "assignee" to mapOf("name" to "ldap.id"),
                        "labels" to listOf("e2e", "dast", "critical", "auto-generated"),
                        "priority" to mapOf("name" to "Highest"),
                    )
                ))
                .retrieve()
                .awaitBodilessEntity()

        } catch (ex: Exception) {
            logger.error("지라 티켓 생성 실패 | message: {}", ex.message, ex)
        }
    }
}
