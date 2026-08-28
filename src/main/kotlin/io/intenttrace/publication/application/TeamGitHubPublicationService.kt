package io.intenttrace.publication.application

import io.intenttrace.publication.domain.GitHubPublication
import io.intenttrace.record.application.TeamChangeRecordService
import org.springframework.stereotype.Service

@Service
class TeamGitHubPublicationService(
    private val records: TeamChangeRecordService,
    private val publisher: PublishChangeRecordToGitHub,
) {
    fun publish(command: PublishChangeRecordToGitHubCommand): GitHubPublication {
        records.requireOwnedContributor(command.changeRecordId)
        return publisher.publish(command)
    }
}
