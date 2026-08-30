package io.intenttrace.intellij

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Action
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListSelectionModel

internal object IntentTraceRecordBrowser {
    fun open(project: Project, context: RepositoryFileContext, fileOnly: Boolean = false) {
        val query = RecordListQuery(context.repositoryKey, path = context.relativePath.takeIf { fileOnly })
        val page = load(project) { server, token -> IntentTraceApiClient().list(server, token, query) } ?: return
        RecordBrowserDialog(project, context, query, page).show()
    }

    fun showRecord(project: Project, id: String) {
        // 대체 기록을 포함해 상세 조회마다 서버에서 현재 사용자의 권한을 다시 확인한다.
        val record = load(project) { server, token -> IntentTraceApiClient().record(server, token, id) } ?: return
        RecordHistoryDialog(project, record).show()
    }

    fun <T> load(project: Project, request: (IntentTraceServer, String) -> T): T? {
        var result: T? = null
        ProgressManager.getInstance().run(object : Task.Modal(project, "IntentTrace 기록 조회", false) {
            override fun run(indicator: ProgressIndicator) {
                val server = IntentTraceServer.current()
                val token = IntentTraceCredentialStore().load(server)
                    ?: throw IntentTraceUsageException("Tools > IntentTrace 세션 연결을 먼저 실행해 주세요.")
                result = request(server, token)
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

private class RecordBrowserDialog(
    private val project: Project,
    private val context: RepositoryFileContext,
    private var query: RecordListQuery,
    private var page: ChangeRecordPage,
) : DialogWrapper(project, true) {
    private val filter = JComboBox(RecordFilter.entries.toTypedArray())
    private val fileOnly = JBCheckBox("현재 파일만", query.path != null)
    private val rows = DefaultListModel<ChangeRecordSummary>()
    private val list = JBList(rows)
    private val pageLabel = JLabel()
    private val previous = JButton("이전 페이지")
    private val next = JButton("다음 페이지")
    private val open = JButton("선택 기록 열기")

    init {
        title = "IntentTrace 기록함 · ${context.repositoryKey}"
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean,
            ): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, selected, focused)
                putClientProperty("html.disable", true)
                val record = value as ChangeRecordSummary
                text = "[${IntentTraceTextRenderer.status(record.status)}] ${record.title} · @${record.createdBy.login} · " +
                    "${record.targetRevision?.take(12) ?: "커밋 미확인"} · ${record.createdAt}"
                return this
            }
        }
        list.addListSelectionListener { open.isEnabled = list.selectedValue != null }
        open.addActionListener { list.selectedValue?.let { IntentTraceRecordBrowser.showRecord(project, it.id) } }
        previous.addActionListener { reload(query.copy(page = query.page - 1)) }
        next.addActionListener { reload(query.copy(page = query.page + 1)) }
        init()
        displayPage()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        border = JBUI.Borders.empty(8)
        add(JPanel(FlowLayout(FlowLayout.LEADING)).apply {
            add(filter)
            add(fileOnly)
            add(JButton("조회").apply {
                addActionListener {
                    val selected = filter.selectedItem as RecordFilter
                    reload(RecordListQuery(context.repositoryKey, selected.scope, context.relativePath.takeIf { fileOnly.isSelected }, selected.status))
                }
            })
        }, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(pageLabel, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEADING)).apply {
                add(previous)
                add(next)
                add(open)
            }, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        preferredSize = Dimension(960, 480)
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    private fun reload(nextQuery: RecordListQuery) {
        val loaded = IntentTraceRecordBrowser.load(project) { server, token ->
            IntentTraceApiClient().list(server, token, nextQuery)
        } ?: return
        query = nextQuery
        page = loaded
        displayPage()
    }

    private fun displayPage() {
        filter.selectedItem = RecordFilter.entries.first { it.scope == query.scope && it.status == query.status }
        fileOnly.isSelected = query.path != null
        rows.clear()
        rows.addAll(page.items)
        previous.isEnabled = page.page > 0
        next.isEnabled = page.hasNext
        open.isEnabled = false
        pageLabel.text = "${query.scope} · ${query.path ?: "저장소 전체"} · ${query.status?.let(IntentTraceTextRenderer::status) ?: "모든 상태"} · " +
            "${page.page + 1}페이지 · ${page.items.size}건 (생성일 내림차순)"
        pageLabel.putClientProperty("html.disable", true)
        list.emptyText.text = "조건에 맞는 기록이 없습니다. 파일 이름 변경 전 이력은 저장소 전체에서 찾아보세요."
    }
}

private enum class RecordFilter(private val label: String, val scope: RecordListScope, val status: String?) {
    TEAM("팀 공개 기록 · 전체", RecordListScope.TEAM, null),
    PUBLISHED("팀 공개 기록 · 공개", RecordListScope.TEAM, "PUBLISHED"),
    SUPERSEDED("팀 공개 기록 · 대체됨", RecordListScope.TEAM, "SUPERSEDED"),
    MY_DRAFTS("내 비공개 기록 · 전체", RecordListScope.MY_DRAFTS, null),
    DRAFT("내 비공개 기록 · 초안", RecordListScope.MY_DRAFTS, "DRAFT"),
    CONFIRMED("내 비공개 기록 · 작성자 확인", RecordListScope.MY_DRAFTS, "AUTHOR_CONFIRMED");

    override fun toString(): String = label
}

private class RecordHistoryDialog(
    private val project: Project,
    private val record: ChangeIntentRecord,
) : DialogWrapper(project, true) {
    init {
        title = "IntentTrace 기록 상세 · 당시 스냅샷 기준"
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        add(JBScrollPane(JBTextArea(IntentTraceTextRenderer.renderHistory(record)).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(12)
            caretPosition = 0
        }), BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEADING)).apply {
            add(JButton("원래 커밋 열기").apply {
                isEnabled = record.targetRevision != null
                addActionListener { browse { GitHubEvidenceLinks.commit(record) } }
            })
            val anchors = JComboBox(record.codeAnchors.map { "${it.relativePath}:${it.startLine}-${it.endLine}" }.toTypedArray())
            anchors.renderer = DefaultListCellRenderer().apply { putClientProperty("html.disable", true) }
            anchors.preferredSize = Dimension(320, anchors.preferredSize.height)
            add(anchors)
            add(JButton("당시 코드 열기").apply {
                isEnabled = record.targetRevision != null && record.codeAnchors.isNotEmpty()
                addActionListener { browse { GitHubEvidenceLinks.code(record, record.codeAnchors[anchors.selectedIndex]) } }
            })
            add(JButton("대체 기록 열기").apply {
                isEnabled = record.supersededBy != null
                addActionListener { record.supersededBy?.let { IntentTraceRecordBrowser.showRecord(project, it) } }
            })
        }, BorderLayout.SOUTH)
        preferredSize = Dimension(960, 560)
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    private fun browse(uri: () -> java.net.URI) {
        try {
            BrowserUtil.browse(uri())
        } catch (error: IntentTraceUserException) {
            Messages.showErrorDialog(project, error.message, "IntentTrace")
        }
    }
}
