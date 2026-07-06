package com.platform.e2e

import com.platform.notifications.NotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

abstract class E2eRunner(
    val notificationService: NotificationService,
    protected val historyTracker: E2eHistoryTracker
) {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    protected suspend fun runCycle(block: suspend CycleScope.() -> Unit) {
        val traceId: String = UUID.randomUUID().toString().take(8).uppercase()
        val startedAt = Instant.now()
        val cycleResult = historyTracker.startCycle(traceId)

        MDC.put("e2e-trace-id", traceId)
        withContext(MDCContext()) {
            logger.info("════════ E2E Tracing START [{}] ════════", traceId)

            coroutineScope {
                val scope = CycleScope(traceId, cycleResult, this)
                scope.block()
            }

            val elapsedMs: Long = Instant.now().toEpochMilli() - startedAt.toEpochMilli()
            cycleResult.endedAt = Instant.now()
            cycleResult.durationMs = elapsedMs

            if (failures(traceId).isEmpty()) {
                cycleResult.status = CycleStatus.PASS
                logger.info("════════ E2E Tracing PASS [{}] elapsed: {}ms ════════", traceId, elapsedMs)
            } else {
                cycleResult.status = CycleStatus.FAIL
                val failed: CopyOnWriteArrayList<ScenarioFailure> = failures(traceId)
                logger.error("════════ E2E Tracing FAIL [{}] failures: {} elapsed: {}ms ════════", traceId, failed.size, elapsedMs,)

                failed.forEach { f ->
                    notificationService.notify(
                        traceId = traceId,
                        scenario   = f.scenarioName,
                        message    = f.message,
                        cause      = f.cause,
                        severity   = classifySeverity(f),
                    )
                }
            }
        }

        historyTracker.saveHistory()
        MDC.remove("e2e-trace-id")
        failures.remove(traceId)
    }

    // 실패 목록 (traceId 키로 격리)
    private val failures = mutableMapOf<String, CopyOnWriteArrayList<ScenarioFailure>>()
    private fun failures(traceId: String) =
        failures.getOrPut(traceId) { CopyOnWriteArrayList() }

    inner class CycleScope(
        val traceId: String,
        val cycleResult: CycleResult,
        private val coroutineScope: CoroutineScope
    ) {

        /** 순차 실행 — 완료될 때까지 suspend */
        suspend fun runScenario(name: String, block: suspend () -> Unit) =
            executeScenario(name, block)

        /** 순차 실행 — Scenario enum 키 사용 */
        suspend fun runScenario(scenario: Scenario, block: suspend () -> Unit) =
            executeScenario(scenario.meta.name, block)

        /** 병렬 실행 — 즉시 반환하며 백그라운드에서 실행 */
        fun launchScenario(name: String, block: suspend () -> Unit) {
            coroutineScope.launch { executeScenario(name, block) }
        }

        /** 병렬 실행 — Scenario enum 키 사용 */
        fun launchScenario(scenario: Scenario, block: suspend () -> Unit) {
            coroutineScope.launch { executeScenario(scenario.meta.name, block) }
        }

        private suspend fun executeScenario(name: String, block: suspend () -> Unit) {
            val start = Instant.now()
            try {
                logger.info("[{}] Scenario {} START", traceId, name)
                block()
                val end = Instant.now()
                val duration = end.toEpochMilli() - start.toEpochMilli()
                cycleResult.scenarios.add(ScenarioResult(name, ScenarioStatus.PASS, start, end, duration))
                logger.info("[{}] Scenario {} PASS", traceId, name)
            } catch (e: AssertionError) {
                val end = Instant.now()
                val duration = end.toEpochMilli() - start.toEpochMilli()
                logger.error("[{}] Scenario {} ASSERTION FAIL: {}", traceId, name, e.message)
                failures(traceId) += ScenarioFailure(name, e.message ?: "AssertionError", e)
                cycleResult.scenarios.add(ScenarioResult(name, ScenarioStatus.FAIL, start, end, duration, e.message, e.getStackTraceString()))
            } catch (e: Exception) {
                val end = Instant.now()
                val duration = end.toEpochMilli() - start.toEpochMilli()
                logger.error("[{}] Scenario {} UNEXPECTED ERROR: {}", traceId, name, e.message, e)
                failures(traceId) += ScenarioFailure(name, "Unexpected: ${e.message}", e)
                cycleResult.scenarios.add(ScenarioResult(name, ScenarioStatus.FAIL, start, end, duration, "Unexpected: ${e.message}", e.getStackTraceString()))
            }
        }
    }

    private fun classifySeverity(f: ScenarioFailure): NotificationService.Severity {
        val criticalKeywords: List<String> =
            listOf("멱등성 위반", "이중 수령", "이중 지급", "성공 건수가 1이 아님", "재고 초과", "DAST 취약점 감지")

        return NotificationService.Severity.CRITICAL
//        [TODO] 치명적 이슈 키워드 리스트업하고 주석 해제하기
//        return if (criticalKeywords.any { f.message.contains(it) }) NotificationService.Severity.CRITICAL
//        else NotificationService.Severity.WARNING
    }

    data class ScenarioFailure(val scenarioName: String, val message: String, val cause: Throwable?)
}
