package io.intenttrace.intellij

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepositoryManager

internal object CurrentLineContextResolver {
    fun resolve(project: Project, editor: Editor, file: VirtualFile): LineLookup {
        val repository = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file)
            ?: throw IntentTraceUsageException("현재 파일이 Git 저장소에 포함되어 있지 않습니다.")
        val changes = ChangeListManager.getInstance(project)
        if (
            FileDocumentManager.getInstance().isFileModified(file) ||
            changes.getStatus(file) != FileStatus.NOT_CHANGED
        ) {
            throw IntentTraceUsageException("현재 파일에 커밋되지 않은 변경이 있습니다. HEAD 기준 줄을 조회하려면 먼저 커밋해 주세요.")
        }
        val revision = repository.currentRevision?.lowercase()
            ?: throw IntentTraceUsageException("현재 Git HEAD commit을 확인할 수 없습니다.")
        val relativePath = VfsUtilCore.getRelativePath(file, repository.root, '/')
            ?: throw IntentTraceUsageException("현재 파일의 저장소 상대 경로를 계산할 수 없습니다.")
        val repositoryKey = repository.remotes
            .sortedBy { if (it.name == "origin") 0 else 1 }
            .asSequence()
            .flatMap { (it.urls + it.pushUrls).asSequence() }
            .mapNotNull(GitHubRemoteParser::repositoryKey)
            .firstOrNull()
            ?: throw IntentTraceUsageException("GitHub origin에서 owner/repository를 확인할 수 없습니다.")
        return LineLookup(
            repositoryKey = repositoryKey,
            revision = revision,
            relativePath = relativePath,
            line = editor.caretModel.logicalPosition.line + 1,
        )
    }
}
