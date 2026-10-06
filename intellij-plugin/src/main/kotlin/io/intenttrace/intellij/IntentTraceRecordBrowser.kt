package io.intenttrace.intellij

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.layout.selectedValueMatches
import java.awt.Dimension
import java.net.URI
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.ListSelectionModel

internal object IntentTraceRecordBrowser {
    fun open(project: Project, context: RepositoryFileContext, server: IntentTraceServer, fileOnly: Boolean = false) {
        val query = RecordListQuery(context.repositoryKey, path = context.relativePath.takeIf { fileOnly })
        val page = load(project, server) { token -> IntentTraceApiClient().list(server, token, query) } ?: return
        RecordBrowserDialog(project, context, query, page, server).show()
    }

    fun showRecord(project: Project, id: String, server: IntentTraceServer) {
        // 대체 기록을 포함해 상세 조회마다 서버에서 현재 사용자의 권한을 다시 확인한다.
        val record = load(project, server) { IntentTraceApiClient().record(server, it, id) } ?: return
        RecordHistoryDialog(project, record, server).show()
    }

    /** 창에 토큰을 보관하지 않고 요청마다 [server]의 세션을 modal task 안에서 다시 읽는다. */
    fun <T> load(project: Project, server: IntentTraceServer, request: (String) -> T): T? {
        var result: T? = null
        ProgressManager.getInstance().run(object : Task.Modal(project, "IntentTrace 기록 조회", false) {
            override fun run(indicator: ProgressIndicator) {
                result = request(IntentTraceCredentialStore().require(server))
            }

            override fun onThrowable(error: Throwable) {
                val message = (error as? IntentTraceUserException)?.message
                    ?: "IntentTrace 조회 중 예상하지 못한 오류가 발생했습니다."
                if (!project.isDisposed) Messages.showErrorDialog(project, message, "IntentTrace")
            }
        })
        return result.takeUnless { project.isDisposed }
    }
}

internal open class RecordBrowserDialog(
    private val project: Project,
    private val context: RepositoryFileContext,
    private var query: RecordListQuery,
    private var page: ChangeRecordPage,
    private val server: IntentTraceServer,
    private val loadPage: (RecordListQuery) -> ChangeRecordPage? = { nextQuery ->
        IntentTraceRecordBrowser.load(project, server) { token -> IntentTraceApiClient().list(server, token, nextQuery) }
    },
) : DialogWrapper(project, true) {
    private val filter = JComboBox(RecordFilter.entries.toTypedArray())
    private val fileOnly = JBCheckBox("현재 파일만", query.path != null)
    private val keyword = JBTextField(28).apply {
        emptyText.text = "제목·요청·결정 검색 (최대 ${MAX_KEYWORD_LENGTH}자)"
        addActionListener { search() }
    }
    private var previousQueries = emptyList<RecordListQuery>()
    private val rows = CollectionListModel<ChangeRecordSummary>()
    private val list = JBList(rows)
    private val pageLabel = JLabel()
    private val previous = JButton("이전 페이지")
    private val next = JButton("다음 페이지")
    private val open = JButton("선택 기록 열기")

    init {
        title = "IntentTrace 기록함 · ${context.repositoryKey}"
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = textListCellRenderer { record ->
            "[${IntentTraceTextRenderer.status(record.status)}] ${record.title} · @${record.createdBy.login} · " +
                "${record.targetRevision?.take(12) ?: "커밋 미확인"} · ${record.createdAt}"
        }
        list.addListSelectionListener { open.isEnabled = list.selectedValue != null }
        open.addActionListener { list.selectedValue?.let { IntentTraceRecordBrowser.showRecord(project, it.id, server) } }
        previous.addActionListener {
            previousQueries.lastOrNull()?.let { reload(it, previousQueries.dropLast(1)) }
        }
        next.addActionListener {
            page.nextCursor?.let { reload(query.copy(cursor = it), previousQueries + query) }
        }
        init()
        displayPage()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            cell(filter)
            cell(fileOnly)
        }
        row("검색어") {
            cell(keyword)
            button("조회") { search() }
        }
        row { scrollCell(list).align(Align.FILL) }.resizableRow()
        row { cell(pageLabel) }
        row {
            cell(previous)
            cell(next)
            button("새로고침") { reload(query, previousQueries, list.selectedValue?.id) }
            cell(open)
        }
    }.apply { preferredSize = Dimension(960, 480) }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    private fun search() {
        val selected = filter.selectedItem as RecordFilter
        val text = keyword.text.trim()
        // 서버의 400 응답 대신 입력 위치에서 바로 안내하고 입력값은 고칠 수 있게 남긴다.
        if (text.length > MAX_KEYWORD_LENGTH) {
            Messages.showErrorDialog(project, "검색어는 ${MAX_KEYWORD_LENGTH}자 이하로 입력해 주세요.", "IntentTrace")
            return
        }
        reload(RecordListQuery(
            context.repositoryKey, selected.scope, context.relativePath.takeIf { fileOnly.isSelected }, selected.status,
            keyword = text.takeIf { it.isNotEmpty() },
        ))
    }

    private fun reload(
        nextQuery: RecordListQuery,
        history: List<RecordListQuery> = emptyList(),
        selectedRecordId: String? = null,
    ) {
        val loaded = loadPage(nextQuery) ?: return restoreFilters()
        query = nextQuery
        page = loaded
        previousQueries = history
        displayPage(selectedRecordId)
    }

    private fun restoreFilters() {
        filter.selectedItem = RecordFilter.entries.first { it.scope == query.scope && it.status == query.status }
        fileOnly.isSelected = query.path != null
        keyword.text = query.keyword.orEmpty()
    }

    private fun displayPage(selectedRecordId: String? = null) {
        restoreFilters()
        rows.replaceAll(page.items)
        list.selectedIndex = page.items.indexOfFirst { it.id == selectedRecordId }
        previous.isEnabled = previousQueries.isNotEmpty()
        next.isEnabled = page.nextCursor != null
        open.isEnabled = list.selectedValue != null
        pageLabel.text = "${filter.selectedItem} · ${query.path ?: "저장소 전체"} · " +
            "${previousQueries.size + 1}페이지 · ${page.items.size}건 (생성일 내림차순)"
        list.emptyText.text = "조건에 맞는 기록이 없습니다. 파일 이름 변경 전 이력은 저장소 전체에서 찾아보세요."
    }

    private companion object {
        const val MAX_KEYWORD_LENGTH = 200
    }
}

private enum class RecordFilter(private val label: String, val scope: RecordListScope, val status: String?) {
    TEAM("팀 공개 기록 · 전체", RecordListScope.TEAM, null),
    PUBLISHED("팀 공개 기록 · 공개", RecordListScope.TEAM, "PUBLISHED"),
    SUPERSEDED("팀 공개 기록 · 대체됨", RecordListScope.TEAM, "SUPERSEDED"),
    MINE("내 비공개 기록 · 초안·작성자 확인", RecordListScope.MINE, null),
    DRAFT("내 비공개 기록 · 초안", RecordListScope.MINE, "DRAFT"),
    CONFIRMED("내 비공개 기록 · 작성자 확인", RecordListScope.MINE, "AUTHOR_CONFIRMED"),
    DISCARDED("내 비공개 기록 · 폐기", RecordListScope.MINE, "DISCARDED");

    override fun toString(): String = label
}

internal open class RecordHistoryDialog(
    private val project: Project,
    private val record: ChangeIntentRecord,
    server: IntentTraceServer,
    private val openRecord: (String) -> Unit = { IntentTraceRecordBrowser.showRecord(project, it, server) },
    private val openBrowser: (URI) -> Unit = { BrowserUtil.browse(it) },
) : DialogWrapper(project, true) {
    private val webRecordUri = server.webRecordUri(record.id)

    init {
        title = "IntentTrace 기록 상세 · 당시 스냅샷 기준"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { button("웹에서 기록 열기") { browse { webRecordUri } }.align(AlignX.RIGHT) }
        row { cell(readOnlyTextPane(IntentTraceTextRenderer.renderHistory(record))).align(Align.FILL) }.resizableRow()
        row {
            button("원래 커밋 열기") { browse { GitHubEvidenceLinks.commit(record) } }.enabled(record.targetRevision != null)
            val anchors = comboBox(record.codeAnchors, textListCellRenderer<ChangeCodeAnchor?> { it?.label })
                .columns(COLUMNS_LARGE).component
            button("당시 코드 열기") { anchors.item?.let { anchor -> browse { GitHubEvidenceLinks.code(record, anchor) } } }
                .enabledIf(anchors.selectedValueMatches { it?.let(record::revisionFor) != null })
            button("원본 기록 열기") { record.derivedFromRecordId?.let(openRecord) }.enabled(record.derivedFromRecordId != null)
            button("대체 기록 열기") { record.supersededBy?.let(openRecord) }.enabled(record.supersededBy != null)
        }
    }.apply { preferredSize = Dimension(960, 560) }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    private fun browse(uri: () -> URI) {
        orShowError(project) { openBrowser(uri()) }
    }
}
