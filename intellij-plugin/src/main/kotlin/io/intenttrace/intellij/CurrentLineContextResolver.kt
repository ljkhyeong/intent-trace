package io.intenttrace.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangeListManagerEx
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepositoryManager
import git4idea.repo.GitRepository
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal data class RepositoryFileContext(val repositoryKey: String, val relativePath: String)

internal data class RepositoryRevision(val repositoryKey: String, val revision: String?)

/** 조회 직전에 디스크와 `.git`에서 다시 읽은 HEAD와 현재 파일의 변경 여부다. */
internal data class FreshLineState(val revision: String?, val fileChanged: Boolean)

internal object CurrentLineContextResolver {
    fun history(project: Project, file: VirtualFile): RepositoryFileContext = fileContext(gitRepository(project, file), file)

    /** 연결 진단은 파일 변경과 관계없이 저장소와 현재 HEAD만 사용한다. HEAD가 없으면 커밋 읽기를 확인하지 않는다. */
    fun repository(project: Project, file: VirtualFile): RepositoryRevision {
        val repository = gitRepository(project, file)
        return RepositoryRevision(repositoryKey(repository), repository.currentRevision?.lowercase())
    }

    /**
     * 터미널에서 checkout·commit·편집한 직후에는 IDE의 HEAD와 변경 목록이 늦게 갱신될 수 있다.
     * 백그라운드에서 파일과 Git 상태를 다시 읽는다. 쓰기 작업이 끝나기를 기다려야 하므로 읽기 잠금 없이 호출한다.
     */
    fun refreshState(project: Project, file: VirtualFile): FreshLineState {
        VfsUtil.markDirtyAndRefresh(false, false, false, file)
        val repository = gitRepository(project, file)
        repository.update()
        VcsDirtyScopeManager.getInstance(project).fileDirty(file)
        try {
            ChangeListManagerEx.getInstanceEx(project).promiseWaitForUpdate().blockingGet(STATUS_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            throw IntentTraceUsageException("IDE의 변경 목록 갱신이 끝나지 않았습니다. 잠시 후 다시 조회해 주세요.")
        } catch (_: ExecutionException) {
            throw IntentTraceUsageException("IDE의 변경 목록 갱신이 끝나지 않았습니다. 잠시 후 다시 조회해 주세요.")
        }
        val changed = ReadAction.compute<Boolean, RuntimeException> {
            FileDocumentManager.getInstance().isFileModified(file) ||
                ChangeListManager.getInstance(project).getStatus(file) != FileStatus.NOT_CHANGED
        }
        return FreshLineState(repository.currentRevision?.lowercase(), changed)
    }

    /** 편집기에서 계산한 조회 조건이 다시 읽은 상태와 다르면 잘못된 줄로 조회하지 않는다. */
    fun requireUnchanged(lookup: LineLookup, state: FreshLineState) {
        if (state.fileChanged) {
            throw IntentTraceUsageException("현재 파일에 커밋되지 않은 변경이 있습니다. HEAD 기준 줄을 조회하려면 먼저 커밋해 주세요.")
        }
        if (state.revision != lookup.revision) {
            throw IntentTraceUsageException("조회를 시작한 뒤 Git HEAD가 바뀌었습니다. 편집기에 새 커밋이 반영된 뒤 다시 조회해 주세요.")
        }
    }

    fun resolve(project: Project, editor: Editor, file: VirtualFile): LineLookup {
        val repository = gitRepository(project, file)
        val changes = ChangeListManager.getInstance(project)
        if (
            FileDocumentManager.getInstance().isFileModified(file) ||
            changes.getStatus(file) != FileStatus.NOT_CHANGED
        ) {
            throw IntentTraceUsageException("현재 파일에 커밋되지 않은 변경이 있습니다. HEAD 기준 줄을 조회하려면 먼저 커밋해 주세요.")
        }
        val revision = repository.currentRevision?.lowercase()
            ?: throw IntentTraceUsageException("현재 Git HEAD 커밋을 확인할 수 없습니다.")
        val context = fileContext(repository, file)
        return LineLookup(
            repositoryKey = context.repositoryKey,
            revision = revision,
            relativePath = context.relativePath,
            line = editor.caretModel.logicalPosition.line + 1,
        )
    }

    private fun gitRepository(project: Project, file: VirtualFile): GitRepository =
        GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file)
            ?: throw IntentTraceUsageException("현재 파일이 Git 저장소에 포함되어 있지 않습니다.")

    private fun fileContext(repository: GitRepository, file: VirtualFile): RepositoryFileContext {
        val relativePath = VfsUtilCore.getRelativePath(file, repository.root, '/')
            ?: throw IntentTraceUsageException("현재 파일의 저장소 상대 경로를 계산할 수 없습니다.")
        return RepositoryFileContext(repositoryKey(repository), relativePath)
    }

    private fun repositoryKey(repository: GitRepository): String = repository.remotes
        .sortedBy { if (it.name == "origin") 0 else 1 }
        .asSequence()
        .flatMap { (it.urls + it.pushUrls).asSequence() }
        .mapNotNull(GitHubRemoteParser::repositoryKey)
        .firstOrNull()
        ?: throw IntentTraceUsageException("GitHub origin에서 owner/repository를 확인할 수 없습니다.")

    private const val STATUS_WAIT_SECONDS = 10
}
