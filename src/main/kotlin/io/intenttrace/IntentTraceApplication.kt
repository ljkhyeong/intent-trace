package io.intenttrace

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class IntentTraceApplication

fun main(args: Array<String>) {
	runApplication<IntentTraceApplication>(*args)
}
