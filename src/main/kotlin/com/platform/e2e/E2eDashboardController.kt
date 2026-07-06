package com.platform.e2e

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/e2e/api")
class E2eDashboardController(
    private val historyTracker: E2eHistoryTracker,
    private val runnerService: E2eRunnerService,
) {

    @GetMapping("/history")
    fun getHistory(): List<CycleResult> {
        return historyTracker.getHistory()
    }

    @GetMapping("/scenarios")
    fun getScenarios(): List<ScenarioMeta> {
        return historyTracker.getScenarios()
    }

    @PostMapping("/scenarios/{key}/run")
    suspend fun runScenario(@PathVariable key: String): ResponseEntity<ManualRunResult> {
        return try {
            ResponseEntity.ok(runnerService.runManual(key))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().build()
        }
    }
}
