package com.platform.e2e

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class E2eScheduler(private val runnerService: E2eRunnerService) {
    private val logger = LoggerFactory.getLogger(E2eScheduler::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Scheduled(cron = "0 0 9,11,13,15,17 * * MON-THU")
    fun scheduleE2eCoreFlowTwoHourly() {
        scope.launch {
            try {
                runnerService.executeCoreFlowHourly()
            } catch (ex: Exception) {
                logger.error("예상치 못한 오류, 다음 사이클은 정상 실행됩니다 | message: {}", ex.message, ex)
            }
        }
    }

    @Scheduled(cron = "0 0 10 * * MON-THU")
    fun scheduleE2eCoreFlowDaily() {
        scope.launch {
            try {
                runnerService.executeCoreFlowDaily()
            } catch (ex: Exception) {
                logger.error("예상치 못한 오류, 다음 사이클은 정상 실행됩니다 | message: {}", ex.message, ex)
            }
        }
    }

    @Scheduled(cron = "0 0 10 * * MON")
    fun scheduleE2eCoreFlowWeekly() {
        scope.launch {
            try {
                runnerService.executeCoreFlowWeekly()
            } catch (ex: Exception) {
                logger.error("예상치 못한 오류, 다음 사이클은 정상 실행됩니다 | message: {}", ex.message, ex)
            }
        }
    }
}