package io.intenttrace

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class IntentTraceApplication

fun main(args: Array<String>) {
	runApplication<IntentTraceApplication>(*args)
}
