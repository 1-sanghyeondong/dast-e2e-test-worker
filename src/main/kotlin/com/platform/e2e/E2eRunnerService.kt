package com.platform.e2e

import com.platform.notifications.NotificationService
import com.platform.e2e.scenarios.NewApiDastScenario
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class E2eRunnerService(
    private val newApiDastScenario: NewApiDastScenario,
    notificationService: NotificationService,
    historyTracker: E2eHistoryTracker,
) : E2eRunner(notificationService, historyTracker) {

    /** 매 2시간 실행 시나리오 */
    suspend fun executeCoreFlowHourly() = runCycle {
    }

    /** 매일 실행 시나리오 — 상품 등록·배포·통합상품 연동 플로우 */
    suspend fun executeCoreFlowDaily() = runCycle {
    }

    /** 매주 실행 시나리오 — DAST 취약점 점검 */
    suspend fun executeCoreFlowWeekly() = runCycle {
        val results = newApiDastScenario.runAll()
        results.forEach { result ->
            runScenario(result.name) {
                check(result.passed) { result.detail }
            }
        }
    }

    /** 단건 수동 실행 — dashboard 버튼용 */
    suspend fun runManual(scenarioKey: String): ManualRunResult {
        val scenario = try {
            Scenario.valueOf(scenarioKey)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Unknown scenario key: $scenarioKey")
        }

        val start = Instant.now()
        return try {
            when (scenario) {
                else -> {
                    // TODO
                }
            }
            val ms = Instant.now().toEpochMilli() - start.toEpochMilli()
            ManualRunResult("PASS", null, ms)
        } catch (e: Exception) {
            val ms = Instant.now().toEpochMilli() - start.toEpochMilli()
            ManualRunResult("FAIL", e.message, ms)
        }
    }
}
