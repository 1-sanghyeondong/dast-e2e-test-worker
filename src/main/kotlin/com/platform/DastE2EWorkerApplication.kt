package com.platform

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DastE2EWorkerApplication

fun main(args: Array<String>) {
    runApplication<DastE2EWorkerApplication>(*args)
}
