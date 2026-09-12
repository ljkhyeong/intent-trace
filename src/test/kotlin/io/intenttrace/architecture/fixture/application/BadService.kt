package io.intenttrace.architecture.fixture.application

import io.intenttrace.record.adapter.out.persistence.JdbcChangeRecordRepository

class BadService(val repository: JdbcChangeRecordRepository)
