package com.kimi.mobile

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

class ChatActivity : AppCompatActivity(), WsClient.Listener {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_SESSION_TITLE = "session_title"
        private const val REQ_RECORD_AUDIO = 42
        private const val TAG = "Reconcile"
    }

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var sessionId: String
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: MessageAdapter
    private lateinit var tvTitle: TextView
    private lateinit var tvContext: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etInput: EditText
    private lateinit var btnMic: ImageButton
    private lateinit var btnSend: Button

    private var ws: WsClient? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var speechOnnx: SpeechOnnx? = null
    @Volatile private var onnxListening = false
    @Volatile private var onnxInitializing = false

    private val messages: ArrayList<ChatMsg> get() = adapter.items
    /** 当前 turn 的文本帧（frameId -> 已累积文本），保持插入顺序 */
    private val textFrames = LinkedHashMap<String, StringBuilder>()
    private var streamingIndex = -1
    @Volatile private var turnActive = false
    /** 已发送但尚未在服务端历史中确认的乐观回显（id 以 "local-" 开头） */
    private val pendingLocal = ArrayList<ChatMsg>()
    /** 工具活动条目：frameId -> adapter 下标；不进历史，turn 结束后随 loadHistory 清除 */
    private val toolItems = HashMap<String, Int>()

    // ---------- 上下文用量 ----------
    /** 已用/上限 token（-1 表示未知）；WS 快照与 meta.merge 为优先来源，REST 仅兜底 */
    @Volatile private var contextTokens = -1L
    @Volatile private var contextLimit = -1L

    // ---------- 会话模式栏 ----------
    @Volatile private var profile: SessionProfile? = null
    /** 回显 profile 期间抑制控件回调，避免触发多余的 POST */
    private var suppressProfileCallbacks = false
    private lateinit var modeBar: LinearLayout
    private lateinit var modePanel: LinearLayout
    private lateinit var tvModeSummary: TextView
    private lateinit var swPlan: Switch
    private lateinit var swSwarm: Switch
    private lateinit var rgPermission: RadioGroup
    private lateinit var spinnerModel: Spinner
    private lateinit var goalCreate: LinearLayout
    private lateinit var goalActive: LinearLayout
    private lateinit var etGoal: EditText
    private lateinit var tvGoalSummary: TextView

    private val modelPresets = listOf(
        "kimi-code/k3",
        "kimi-code/kimi-for-coding",
        "kimi-code/kimi-for-coding-highspeed",
        "kimi-code/k3-256k"
    )
    private var modelChoices: List<String> = modelPresets

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_SESSION_TITLE) ?: "会话"

        tvTitle = findViewById(R.id.tvChatTitle)
        tvTitle.text = title
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        // 与 /fork 命令同一套代码路径
        findViewById<Button>(R.id.btnFork).setOnClickListener {
            runSessionAction("fork") { data -> openForkedSession(data) }
        }
        tvContext = findViewById(R.id.tvContext)
        tvStatus = findViewById(R.id.tvStatus)
        etInput = findViewById(R.id.etInput)
        btnMic = findViewById(R.id.btnMic)
        btnSend = findViewById(R.id.btnSend)

        recycler = findViewById(R.id.recyclerMessages)
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        adapter = MessageAdapter()
        recycler.adapter = adapter

        btnSend.setOnClickListener { sendCurrentText() }
        setupVoice()
        setupModeBar()
        loadHistory()
        loadProfile()
    }

    override fun onStart() {
        super.onStart()
        val w = WsClient(Prefs.serverUrl(this), Prefs.token(this), sessionId, this)
        ws = w
        w.start()
    }

    override fun onStop() {
        super.onStop()
        ws?.stop()
        ws = null
    }

    // ---------- 审批轮询 ----------

    /** 轮询开关：仅 Activity resumed 时轮询 */
    @Volatile private var approvalsActive = false
    /** 弹窗展示期间不重复触发 */
    private var approvalDialogShowing = false

    private val approvalPoll = object : Runnable {
        override fun run() {
            pollApprovals()
            pollQuestions()
            if (approvalsActive) handler.postDelayed(this, 5000)
        }
    }

    override fun onResume() {
        super.onResume()
        approvalsActive = true
        handler.post(approvalPoll)
        startHistoryPoll() // 前台常驻：busy 15s / 空闲 60s，onPause 停止
    }

    override fun onPause() {
        super.onPause()
        approvalsActive = false
        handler.removeCallbacks(approvalPoll)
        stopHistoryPoll()
    }

    private fun pollApprovals() {
        Thread {
            try {
                val pending = Api.listPendingApprovals(server(), token(), sessionId)
                if (pending.isEmpty()) return@Thread
                handler.post {
                    if (approvalsActive && !approvalDialogShowing) {
                        showNextApproval(ArrayDeque(pending))
                    }
                }
            } catch (e: Exception) {
                // 轮询失败静默忽略，下轮重试
            }
        }.start()
    }

    /** 多条 pending 逐条弹窗处理 */
    private fun showNextApproval(queue: ArrayDeque<ApprovalItem>) {
        val a = queue.removeFirstOrNull() ?: return
        approvalDialogShowing = true
        val detail = a.summary.ifEmpty { a.action }
        AlertDialog.Builder(this)
            .setTitle("工具审批：${a.toolName}")
            .setMessage(if (detail.isEmpty()) "是否允许执行 ${a.toolName}？" else detail)
            .setPositiveButton("批准") { _, _ -> respondApproval(a, "approved", queue) }
            .setNegativeButton("拒绝") { _, _ -> respondApproval(a, "rejected", queue) }
            .setOnDismissListener { approvalDialogShowing = false }
            .show()
    }

    private fun respondApproval(a: ApprovalItem, decision: String, queue: ArrayDeque<ApprovalItem>) {
        Thread {
            try {
                Api.respondApproval(server(), token(), sessionId, a.id, decision)
            } catch (e: Exception) {
                handler.post {
                    Toast.makeText(this, "审批响应失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
        // dismiss 监听器先把标记复位，这里再弹队列中下一条
        handler.post { if (approvalsActive) showNextApproval(queue) }
    }

    // ---------- 问答轮询（pending_interaction="question"） ----------

    /** 弹窗展示期间不重复触发 */
    private var questionDialogShowing = false

    private fun pollQuestions() {
        Thread {
            try {
                val pending = Api.listPendingQuestions(server(), token(), sessionId)
                if (pending.isEmpty()) return@Thread
                handler.post {
                    if (approvalsActive && !questionDialogShowing) {
                        showNextQuestion(ArrayDeque(pending))
                    }
                }
            } catch (e: Exception) {
                // 轮询失败静默忽略，下轮重试
            }
        }.start()
    }

    /** 多条 pending 逐条弹窗处理 */
    private fun showNextQuestion(queue: ArrayDeque<QuestionItem>) {
        val item = queue.removeFirstOrNull() ?: return
        questionDialogShowing = true

        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        /** 每题的控件组：提交时据此组装 answers（entry/选项单选组/"其他"输入框） */
        val forms = ArrayList<Triple<QuestionEntry, RadioGroup, EditText?>>()
        for (q in item.questions) {
            val label = q.question.ifEmpty { q.header }
            if (label.isNotEmpty()) {
                container.addView(TextView(this).apply {
                    text = label
                    textSize = 16f
                })
            }
            val rg = RadioGroup(this)
            for (opt in q.options) {
                rg.addView(RadioButton(this).apply {
                    text = if (opt.description.isEmpty()) opt.label else "${opt.label} — ${opt.description}"
                    tag = opt.id
                })
            }
            var otherEdit: EditText? = null
            if (q.allowOther) {
                rg.addView(RadioButton(this).apply {
                    text = "其他（自定义回答）"
                    tag = "__other__"
                })
                otherEdit = EditText(this).apply { hint = "请输入自定义回答" }
            }
            container.addView(rg)
            if (otherEdit != null) container.addView(otherEdit)
            forms.add(Triple(q, rg, otherEdit))
        }

        val title = item.questions.firstOrNull()?.header?.takeIf { it.isNotEmpty() }
            ?: item.questions.firstOrNull()?.question?.takeIf { it.isNotEmpty() }
        // 内容区限高可滚动：题目/选项多时底部按钮始终可达
        val scroll = ScrollView(this).apply { addView(container) }
        val maxH = (resources.displayMetrics.heightPixels * 0.6).toInt()
        container.post {
            scroll.layoutParams = scroll.layoutParams.apply {
                height = if (container.height > maxH) maxH else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
            scroll.requestLayout()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (title.isNullOrEmpty()) "请回答问题" else title)
            .setView(scroll)
            .setPositiveButton("提交", null)
            .setNegativeButton("跳过") { _, _ ->
                val answers = JSONObject()
                for (f in forms) answers.put(f.first.id, JSONObject().put("kind", "skipped"))
                submitQuestionAnswers(item.questionId, answers, queue)
            }
            .setOnDismissListener { questionDialogShowing = false }
            .create()

        // 手动接管提交按钮：校验未完成时不关闭弹窗
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val answers = JSONObject()
                var valid = true
                for (f in forms) {
                    val rg = f.second
                    val selId = rg.checkedRadioButtonId
                    if (selId < 0) {
                        valid = false
                        break
                    }
                    val sel = rg.findViewById<RadioButton>(selId)
                    if (sel.tag == "__other__") {
                        val text = f.third?.text?.toString()?.trim().orEmpty()
                        if (text.isNullOrEmpty()) {
                            Toast.makeText(this, "请填写“其他”的回答内容", Toast.LENGTH_SHORT).show()
                            valid = false
                            break
                        }
                        answers.put(f.first.id, JSONObject().put("kind", "other").put("text", text))
                    } else {
                        answers.put(f.first.id, JSONObject()
                            .put("kind", "single")
                            .put("option_id", sel.tag.toString()))
                    }
                }
                if (!valid) {
                    Toast.makeText(this, "请完成所有题目后再提交", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                submitQuestionAnswers(item.questionId, answers, queue)
            }
        }

        dialog.show()
    }

    private fun submitQuestionAnswers(questionId: String, answers: JSONObject, queue: ArrayDeque<QuestionItem>) {
        Thread {
            try {
                Api.respondQuestion(server(), token(), sessionId, questionId, answers)
            } catch (e: Exception) {
                handler.post {
                    Toast.makeText(this, "回答提交失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
        // dismiss 监听器先把标记复位，这里再弹队列中下一条
        handler.post { if (approvalsActive) showNextQuestion(queue) }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        speechRecognizer = null
        speechOnnx?.release()
        speechOnnx = null
    }

    private fun server() = Prefs.serverUrl(this)
    private fun token() = Prefs.token(this)

    /** 会话级模式状态（本地持久化为准）；发送时随 prompt 顶层下发 */
    private fun currentModes(): SessionProfile =
        profile ?: SessionProfile(false, false, "manual", "", "", "")

    // ---------- 会话模式栏 ----------

    private fun setupModeBar() {
        modeBar = findViewById(R.id.modeBar)
        modePanel = findViewById(R.id.modePanel)
        tvModeSummary = findViewById(R.id.tvModeSummary)
        swPlan = findViewById(R.id.swPlan)
        swSwarm = findViewById(R.id.swSwarm)
        rgPermission = findViewById(R.id.rgPermission)
        spinnerModel = findViewById(R.id.spinnerModel)
        goalCreate = findViewById(R.id.goalCreate)
        goalActive = findViewById(R.id.goalActive)
        etGoal = findViewById(R.id.etGoal)
        tvGoalSummary = findViewById(R.id.tvGoalSummary)

        tvModeSummary.text = "读取会话模式中…"

        findViewById<Button>(R.id.btnModeToggle).setOnClickListener {
            modePanel.visibility = if (modePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        swPlan.setOnCheckedChangeListener { _, checked ->
            if (!suppressProfileCallbacks) {
                updateModes(JSONObject().put("plan_mode", checked), currentModes().copy(planMode = checked))
            }
        }
        swSwarm.setOnCheckedChangeListener { _, checked ->
            if (!suppressProfileCallbacks) {
                updateModes(JSONObject().put("swarm_mode", checked), currentModes().copy(swarmMode = checked))
            }
        }
        rgPermission.setOnCheckedChangeListener { _, checkedId ->
            if (suppressProfileCallbacks) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.rbAuto -> "auto"
                R.id.rbYolo -> "yolo"
                else -> "manual"
            }
            updateModes(JSONObject().put("permission_mode", mode), currentModes().copy(permissionMode = mode))
        }
        spinnerModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressProfileCallbacks) return
                val chosen = modelChoices.getOrNull(position) ?: return
                if (chosen != profile?.model) {
                    updateModes(JSONObject().put("model", chosen), currentModes().copy(model = chosen))
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnGoalCreate).setOnClickListener {
            val goal = etGoal.text.toString().trim()
            if (goal.isEmpty()) {
                Toast.makeText(this, "请先输入目标", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            etGoal.setText("")
            updateModes(JSONObject().put("goal_objective", goal), currentModes().copy(goalObjective = goal))
        }
        findViewById<Button>(R.id.btnGoalPause).setOnClickListener {
            postGoalControl("pause")
        }
        findViewById<Button>(R.id.btnGoalResume).setOnClickListener {
            postGoalControl("resume")
        }
        findViewById<Button>(R.id.btnGoalCancel).setOnClickListener {
            updateModes(JSONObject().put("goal_control", "cancel"), currentModes().copy(goalObjective = ""))
        }
    }

    /** 目标暂停/恢复不改变本地目标状态，仅下发控制指令 */
    private fun postGoalControl(control: String) {
        Thread {
            try {
                Api.updateProfile(server(), token(), sessionId, JSONObject().put("goal_control", control))
            } catch (e: ApiException) {
                handler.post { handleApiError(e) }
            } catch (e: Exception) {
                handler.post {
                    Toast.makeText(this, "目标控制失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** 进会话回显：本地持久化状态为准；本地无记录才 GET /profile 兜底（并落本地） */
    private fun loadProfile() {
        val local = Prefs.sessionMode(this, sessionId)
        if (local != null) {
            applyProfile(local)
            return
        }
        Thread {
            try {
                val p = Api.getProfile(server(), token(), sessionId)
                handler.post {
                    Prefs.saveSessionMode(this, sessionId, p)
                    applyProfile(p)
                }
            } catch (e: ApiException) {
                handler.post { handleApiError(e) }
            } catch (e: Exception) {
                handler.post {
                    tvModeSummary.text = "模式读取失败"
                    Toast.makeText(this, "读取会话模式失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** 修改即生效：本地乐观更新+持久化，再 POST 补丁；失败回滚到旧状态 */
    private fun updateModes(patch: JSONObject, updated: SessionProfile) {
        val previous = currentModes()
        Prefs.saveSessionMode(this, sessionId, updated)
        applyProfile(updated)
        Thread {
            try {
                Api.updateProfile(server(), token(), sessionId, patch)
            } catch (e: ApiException) {
                handler.post {
                    handleApiError(e)
                    Prefs.saveSessionMode(this, sessionId, previous)
                    applyProfile(previous)
                }
            } catch (e: Exception) {
                handler.post {
                    Toast.makeText(this, "模式设置失败：${e.message}", Toast.LENGTH_LONG).show()
                    Prefs.saveSessionMode(this, sessionId, previous)
                    applyProfile(previous)
                }
            }
        }.start()
    }

    private fun applyProfile(p: SessionProfile) {
        profile = p
        suppressProfileCallbacks = true
        swPlan.isChecked = p.planMode
        swSwarm.isChecked = p.swarmMode
        rgPermission.check(
            when (p.permissionMode) {
                "auto" -> R.id.rbAuto
                "yolo" -> R.id.rbYolo
                else -> R.id.rbManual
            }
        )
        // 模型下拉里保留服务端返回的非预置值
        modelChoices = if (p.model.isNotEmpty() && p.model !in modelPresets) modelPresets + p.model else modelPresets
        spinnerModel.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            modelChoices.map { it.removePrefix("kimi-code/") }
        )
        spinnerModel.setSelection(modelChoices.indexOf(p.model).coerceAtLeast(0))
        // 目标区：有目标显示摘要+控制，无目标显示创建行
        if (p.goalObjective.isEmpty()) {
            goalCreate.visibility = View.VISIBLE
            goalActive.visibility = View.GONE
        } else {
            goalCreate.visibility = View.GONE
            goalActive.visibility = View.VISIBLE
            tvGoalSummary.text = p.goalObjective
        }
        suppressProfileCallbacks = false
        updateModeSummary(p)
    }

    /** 常驻概要条；计划/Swarm/目标激活时高亮提示 */
    private fun updateModeSummary(p: SessionProfile) {
        val parts = ArrayList<String>()
        if (p.planMode) parts.add("计划模式")
        if (p.swarmMode) parts.add("Swarm")
        parts.add(
            when (p.permissionMode) {
                "auto" -> "权限·自动"
                "yolo" -> "权限·YOLO"
                else -> "权限·手动"
            }
        )
        parts.add(p.model.removePrefix("kimi-code/").ifEmpty { "默认模型" })
        if (p.goalObjective.isNotEmpty()) parts.add("目标中")
        tvModeSummary.text = parts.joinToString(" · ")
        val highlight = p.planMode || p.swarmMode || p.goalObjective.isNotEmpty()
        modeBar.setBackgroundColor(getColor(if (highlight) R.color.primary_container else R.color.surface_variant))
        tvModeSummary.setTextColor(getColor(if (highlight) R.color.on_primary_container else R.color.text_secondary))
    }

    // ---------- 历史消息 ----------

    private fun loadHistory() {
        Thread {
            try {
                val history = Api.getMessages(server(), token(), sessionId)
                // 上下文用量兜底（实测该字段可能全 0；WS 快照/meta.merge 为优先来源，非 0 才采纳）
                try {
                    val (used, max) = Api.getSessionUsage(server(), token(), sessionId)
                    if (used > 0) {
                        handler.post {
                            contextTokens = used
                            if (max > 0) contextLimit = max
                            updateContextView()
                        }
                    }
                } catch (e: Exception) {
                    // 旧服务端无此字段/网络抖动：静默忽略
                }
                // 以服务端队列为真相来源：busy 时 queued 的 prompt 不进历史，单独拉取。
                // 拉取失败（旧服务端无此接口/网络抖动）降级为 null → 保留全部未确认回显（旧行为）
                val pq: PromptQueue? = try {
                    Api.listQueuedPrompts(server(), token(), sessionId)
                } catch (e: Exception) {
                    null
                }
                handler.post {
                    Log.d(TAG, "reconcile: history=${history.size} queued=${pq?.queued?.size ?: -1} active=${pq?.active?.text?.take(30)} pendingEcho=${pendingLocal.size}")
                    if (turnActive) {
                        // busy 轮询的 turn 完成兜底检测（v0.37.2 订阅中途加入收不到 turn.upsert）：
                        // active 消失且队列清空 → busy 已结束，落到下面统一刷新
                        if (pq != null && pq.active == null && pq.queued.isEmpty()) {
                            turnActive = false
                            textFrames.clear()
                            streamingIndex = -1
                            hideStatus()
                        } else {
                            // 流式进行中不覆盖，仅同步"执行中"标记，等 turn 结束后统一刷新
                            markActiveEcho(pq?.active?.text)
                            return@post
                        }
                    }
                    // 服务端历史里已出现相同文本的 user 消息 → 回显已确认，从 pendingLocal 移除
                    pendingLocal.removeAll { p ->
                        val confirmed = history.any { it.role == "user" && it.text.trim() == p.text.trim() }
                        if (confirmed) Log.d(TAG, "echo ${p.id} 已被历史确认: ${p.text.take(30)}")
                        confirmed
                    }
                    val queuedMsgs = ArrayList<ChatMsg>()
                    if (pq != null) {
                        // 执行中的 prompt（data.active）：与本地回显匹配 → 标"执行中"（不再是"排队中"）；
                        // 无本地回显（重进会话）→ 由服务端气泡渲染，排在 queued 气泡之前
                        val activePrompt = pq.active
                        var activeMatched = false
                        if (activePrompt != null) {
                            pendingLocal.forEach { p ->
                                if (p.text.trim() == activePrompt.text.trim()) {
                                    activeMatched = true
                                    p.queued = false
                                    p.active = true
                                    p.undelivered = false
                                    Log.d(TAG, "echo ${p.id} 执行中: ${p.text.take(30)}")
                                }
                            }
                            if (!activeMatched) {
                                queuedMsgs.add(ChatMsg("active-0", "user", activePrompt.text, active = true, timeMillis = parseIso(activePrompt.createdAt)))
                            }
                        }
                        // 已在服务端队列里的回显 → 交给服务端队列气泡渲染（同文本去重，只显示一份）
                        pendingLocal.removeAll { p ->
                            val inQueue = pq.queued.any { it.text.trim() == p.text.trim() }
                            if (inQueue) Log.d(TAG, "echo ${p.id} 在服务端队列: ${p.text.take(30)}")
                            inQueue
                        }
                        // 兜底（上游 bug #3127：幻影 busy 下排队 prompt 被服务端静默丢弃）：
                        // 既不在历史也不在服务端队列、且已存在超过 60s → 标记"未送达"，不再显示"排队中"；
                        // POST 在途（<60s）正常保留；active 执行中的消息不标"未送达"。
                        // queued-* 气泡每轮按队列重建，队列与历史都没有即自然消失
                        val now = System.currentTimeMillis()
                        pendingLocal.forEach { p ->
                            if (!p.active && p.timeMillis > 0L && now - p.timeMillis > 60_000) {
                                p.queued = false
                                p.undelivered = true
                                Log.d(TAG, "echo ${p.id} 未送达(>60s 不在历史/队列): ${p.text.take(30)}")
                            }
                        }
                        pq.queued.forEachIndexed { i, q ->
                            queuedMsgs.add(ChatMsg("queued-$i", "user", q.text, queued = true, timeMillis = parseIso(q.createdAt)))
                        }
                    }
                    pendingLocal.forEach { p ->
                        if (!p.active && !p.undelivered) Log.d(TAG, "echo ${p.id} 保留(POST 在途): ${p.text.take(30)}")
                    }
                    // 未确认的回显（POST 在途等）并入列表，防止被 setAll 抹掉；
                    // 按 timeMillis 升序排列（无时间戳的排最后），避免回显/排队/执行中气泡
                    // 无条件落在末尾、早于最新历史条目时顺序错乱
                    val msgs = (history.map { ChatMsg(it.id, it.role, it.text, timeMillis = parseIso(it.createdAt)) } +
                        queuedMsgs + pendingLocal)
                        .sortedWith(compareBy({ it.timeMillis <= 0L }, { it.timeMillis }))
                    toolItems.clear()
                    adapter.setAll(msgs)
                    scrollToBottom()
                }
            } catch (e: ApiException) {
                handler.post { handleApiError(e) }
            } catch (e: Exception) {
                handler.post {
                    Toast.makeText(this, "加载消息失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** turn 进行中（不刷新列表）仅把"执行中"标记同步到匹配的本地回显 */
    private fun markActiveEcho(activeText: String?) {
        if (activeText == null) return
        pendingLocal.forEach { p ->
            if (p.text.trim() == activeText.trim() && !p.active) {
                p.queued = false
                p.active = true
                p.undelivered = false
                val idx = messages.indexOfFirst { it.id == p.id }
                if (idx >= 0) adapter.notifyItemChanged(idx)
            }
        }
    }

    // ---------- 历史轮询 ----------

    /** 前台常驻轮询：busy 时 15s、空闲时 60s，onPause 停止。
     *  busy 轮询：v0.37.2 起 turn 进行中才订阅的客户端收不到 transcript.ops，
     *  靠轮询让进行中的 turn 完成后 15s 内可见回复，不再依赖收不到的 turn.upsert；
     *  空闲轮询：WS 稳定后徽标状态（排队/执行中/未送达）不再刷新会冻结，靠 60s 兜底调和。 */
    private val historyPoll = object : Runnable {
        override fun run() {
            loadHistory()
            handler.postDelayed(this, if (turnActive) 15_000 else 60_000)
        }
    }

    private fun startHistoryPoll() {
        handler.removeCallbacks(historyPoll) // 防止叠加定时器
        handler.postDelayed(historyPoll, if (turnActive) 15_000 else 60_000)
    }

    private fun stopHistoryPoll() {
        handler.removeCallbacks(historyPoll)
    }

    // ---------- 发送 ----------

    private fun sendCurrentText() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return
        etInput.setText("")
        // 斜杠命令拦截：未命中的 / 开头文本按普通 prompt 发送（与官方一致）
        if (text.startsWith("/") && handleSlash(text)) return
        val local = ChatMsg("local-" + System.currentTimeMillis(), "user", text, timeMillis = System.currentTimeMillis())
        pendingLocal.add(local)
        adapter.add(local)
        scrollToBottom()
        showStatus("正在思考…")
        Thread {
            try {
                val status = Api.sendPrompt(server(), token(), sessionId, text, Prefs.model(this@ChatActivity), profile)
                handler.post {
                    if (status == "queued") {
                        showStatus("排队中，等待当前任务完成…")
                        adapter.markQueued(local.id)
                    }
                }
            } catch (e: ApiException) {
                handler.post {
                    removePendingEcho(local)
                    handleApiError(e)
                }
            } catch (e: Exception) {
                handler.post {
                    removePendingEcho(local)
                    hideStatus()
                    Toast.makeText(this, "发送失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** 发送失败撤回乐观回显：从 pendingLocal 与列表中移除，并修正工具条目下标 */
    private fun removePendingEcho(local: ChatMsg) {
        pendingLocal.remove(local)
        val idx = adapter.removeById(local.id)
        if (idx >= 0) {
            toolItems.entries.forEach { if (it.value > idx) it.setValue(it.value - 1) }
        }
    }

    // ---------- 斜杠命令 ----------

    /** 命中内置命令返回 true（已处理）；未命中返回 false，由调用方当普通 prompt 发送 */
    private fun handleSlash(text: String): Boolean {
        // 按首 token 匹配命令（/fork xxx 也算 /fork），与 CLI 行为一致
        when (text.lowercase().substringBefore(' ').substringBefore('\n')) {
            "/compact" -> runSessionAction("compact") {
                Toast.makeText(this, "历史已压缩", Toast.LENGTH_SHORT).show()
                loadHistory()
            }
            "/archive" -> runSessionAction("archive") {
                Toast.makeText(this, "会话已归档", Toast.LENGTH_SHORT).show()
                finish() // 返回会话列表
            }
            "/fork" -> runSessionAction("fork") { data -> openForkedSession(data) }
            "/rename", "/title" -> {
                // 取首个空格后的全部剩余文本作为新标题（允许标题内含空格）
                val newTitle = text.substringAfter(' ', "").trim()
                if (newTitle.isEmpty()) {
                    Toast.makeText(this, "用法：/rename 新标题", Toast.LENGTH_SHORT).show()
                } else {
                    renameSession(newTitle)
                }
            }
            "/abort", "/stop" -> runSessionAction("abort") {
                Toast.makeText(this, "已发送中止指令", Toast.LENGTH_SHORT).show()
            }
            "/new" -> createSessionFromSlash()
            "/help" -> showSlashHelp()
            else -> return false
        }
        return true
    }

    /** 子线程执行会话动作（POST /sessions/{id}:{action}），成功回调在主线程 */
    private fun runSessionAction(action: String, onSuccess: (JSONObject) -> Unit) {
        showStatus("执行 /$action …")
        Thread {
            try {
                val data = Api.sessionAction(server(), token(), sessionId, action)
                handler.post {
                    hideStatus()
                    onSuccess(data)
                }
            } catch (e: ApiException) {
                handler.post {
                    hideStatus()
                    handleApiError(e)
                }
            } catch (e: Exception) {
                handler.post {
                    hideStatus()
                    Toast.makeText(this, "/$action 失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** :fork 返回新会话（data 内含 id，兼容嵌套 session 对象），跳转到新会话 */
    private fun openForkedSession(data: JSONObject) {
        val newId = data.optString("id").ifEmpty {
            data.optJSONObject("session")?.optString("id").orEmpty()
        }
        if (newId.isEmpty()) {
            Toast.makeText(this, "fork 成功但未返回新会话 ID", Toast.LENGTH_LONG).show()
            return
        }
        val newTitle = data.optString("title").ifEmpty {
            data.optJSONObject("session")?.optString("title").orEmpty()
        }.ifEmpty { "Fork 会话" }
        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra(EXTRA_SESSION_ID, newId)
            putExtra(EXTRA_SESSION_TITLE, newTitle)
        })
    }

    /** /rename（/title）：POST /sessions/{id}/profile 顶层 title 字段改名，成功同步顶部标题 */
    private fun renameSession(newTitle: String) {
        showStatus("重命名会话…")
        Thread {
            try {
                Api.renameSession(server(), token(), sessionId, newTitle)
                handler.post {
                    hideStatus()
                    tvTitle.text = newTitle
                    Toast.makeText(this, "已重命名为「$newTitle」", Toast.LENGTH_SHORT).show()
                }
            } catch (e: ApiException) {
                handler.post {
                    hideStatus()
                    handleApiError(e)
                }
            } catch (e: Exception) {
                handler.post {
                    hideStatus()
                    Toast.makeText(this, "重命名失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** /new：复用列表页同款建会话流程（工作区选择策略与 SessionsActivity 一致） */
    private fun createSessionFromSlash() {
        showStatus("创建新会话…")
        Thread {
            try {
                val list = Api.listWorkspaces(server(), token())
                val lastId = Prefs.lastWorkspaceId(this)
                val ws = list.firstOrNull { it.id == lastId }
                    ?: list.maxByOrNull { it.sessionCount }
                if (ws == null) {
                    handler.post {
                        hideStatus()
                        Toast.makeText(this, "没有可用工作区", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                val s = Api.createSession(server(), token(), ws)
                handler.post {
                    hideStatus()
                    startActivity(Intent(this, ChatActivity::class.java).apply {
                        putExtra(EXTRA_SESSION_ID, s.id)
                        putExtra(EXTRA_SESSION_TITLE, s.title)
                    })
                }
            } catch (e: ApiException) {
                handler.post {
                    hideStatus()
                    handleApiError(e)
                }
            } catch (e: Exception) {
                handler.post {
                    hideStatus()
                    Toast.makeText(this, "创建会话失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showSlashHelp() {
        AlertDialog.Builder(this)
            .setTitle("支持的命令")
            .setMessage(
                """
                /compact — 压缩当前会话历史
                /archive — 归档当前会话并返回会话列表
                /fork — 复制当前会话并跳转到新会话
                /rename（或 /title）— 重命名当前会话，用法：/rename 新标题
                /abort（或 /stop）— 中止当前正在执行的任务
                /new — 新建会话
                /help — 显示本帮助

                其他 / 开头的文本按普通消息发送。
                """.trimIndent()
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    // ---------- 语音输入 ----------

    private fun setupVoice() {
        if (!Prefs.voiceEnabled(this)) {
            btnMic.visibility = View.GONE
            return
        }
        val onnxOk = SpeechOnnx.isModelAvailable(this)
        val sysOk = SpeechRecognizer.isRecognitionAvailable(this)
        val usable = when (Prefs.voiceEngine(this)) {
            // onnx 强制模式保留入口：模型未下载时点击提示去设置页下载
            "onnx" -> true
            "system" -> sysOk
            else -> onnxOk || sysOk
        }
        if (!usable) {
            btnMic.visibility = View.GONE
            return
        }
        btnMic.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
            } else {
                toggleListening()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleListening()
            } else {
                Toast.makeText(this, "没有录音权限，无法使用语音输入", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 麦克风按钮入口：离线识别进行中再点一次表示说完了（冲刷出最终结果），否则按引擎设置启动 */
    private fun toggleListening() {
        if (onnxListening) {
            onnxListening = false
            btnMic.alpha = 1f
            speechOnnx?.stop() // 剩余音频冲刷后的尾段结果仍走 onResult 回调
            hideStatus()
            return
        }
        // onnx 强制模式模型未下载：提示去设置页；auto 模式则由 useOnnxEngine 静默回退系统识别
        if (Prefs.voiceEngine(this) == "onnx" && !SpeechOnnx.isModelAvailable(this)) {
            Toast.makeText(this, "离线模型未下载，请先到设置页下载离线模型", Toast.LENGTH_LONG).show()
            return
        }
        if (useOnnxEngine()) startOnnxListening() else startListening()
    }

    /** 引擎决策：onnx 强制离线（模型缺失返回 false）；system 强制系统；auto 离线优先 */
    private fun useOnnxEngine(): Boolean {
        return when (Prefs.voiceEngine(this)) {
            "system" -> false
            "onnx" -> SpeechOnnx.isModelAvailable(this)
            else -> SpeechOnnx.isModelAvailable(this)
        }
    }

    // ---------- 离线识别（sherpa-onnx） ----------

    private fun startOnnxListening() {
        val engine = speechOnnx ?: SpeechOnnx(this).also { speechOnnx = it }
        if (engine.isReady) {
            beginOnnxSession(engine)
            return
        }
        if (onnxInitializing) return
        onnxInitializing = true
        showStatus("正在加载离线语音模型…")
        Thread {
            val ok = engine.init()
            handler.post {
                onnxInitializing = false
                hideStatus()
                if (ok) {
                    beginOnnxSession(engine)
                } else {
                    // 初始化失败回退系统识别（onnx 强制模式除外）
                    speechOnnx?.release()
                    speechOnnx = null
                    if (Prefs.voiceEngine(this) != "onnx" && SpeechRecognizer.isRecognitionAvailable(this)) {
                        Toast.makeText(this, "离线模型不可用，已回退系统识别", Toast.LENGTH_SHORT).show()
                        startListening()
                    } else {
                        Toast.makeText(this, "离线语音模型不可用", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun beginOnnxSession(engine: SpeechOnnx) {
        val ok = engine.start(object : SpeechOnnx.Callback {
            override fun onPartial(text: String) {
                showStatus("识别中：$text")
            }

            override fun onResult(text: String) {
                hideStatus()
                if (text.isNotEmpty()) appendToInput(text)
            }

            override fun onError(msg: String) {
                onnxListening = false
                btnMic.alpha = 1f
                hideStatus()
                Toast.makeText(this@ChatActivity, msg, Toast.LENGTH_SHORT).show()
            }
        })
        if (ok) {
            onnxListening = true
            btnMic.alpha = 0.4f
            Toast.makeText(this, "请说话…（说完再点一次麦克风）", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 系统识别（回退路径） ----------

    private fun startListening() {
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    btnMic.alpha = 0.4f
                    Toast.makeText(this@ChatActivity, "请说话…", Toast.LENGTH_SHORT).show()
                }

                override fun onResults(results: Bundle?) {
                    btnMic.alpha = 1f
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = list?.firstOrNull().orEmpty()
                    if (text.isNotEmpty()) appendToInput(text)
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { btnMic.alpha = 1f }

                override fun onError(error: Int) {
                    btnMic.alpha = 1f
                    val reason = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，请再说一次"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音服务网络错误"
                        SpeechRecognizer.ERROR_AUDIO -> "录音错误"
                        else -> "语音识别失败（$error）"
                    }
                    Toast.makeText(this@ChatActivity, reason, Toast.LENGTH_SHORT).show()
                }
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun appendToInput(text: String) {
        val cur = etInput.text.toString()
        etInput.setText(if (cur.isEmpty()) text else "$cur $text")
        etInput.setSelection(etInput.text.length)
    }

    // ---------- WsClient.Listener（回调在 WS 线程，统一切主线程） ----------

    override fun onOpen() {
        handler.post { loadHistory() }
    }

    override fun onClosed() {
        handler.post { showStatus("连接断开，正在重连…") }
    }

    override fun onAuthError() {
        handler.post {
            Toast.makeText(this, "Token 无效，请重新填写", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onError(message: String) {
        handler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onWorkChanged(busy: Boolean) {
        handler.post {
            if (busy) {
                turnActive = true
                if (textFrames.isEmpty()) showStatus("工作中…")
                startHistoryPoll()
            } else {
                turnActive = false
                hideStatus()
                startHistoryPoll() // 降为空闲 60s 轮询
                loadHistory() // busy 消失最后刷一次（收不到 turn.upsert 时也能看到回复）
            }
        }
    }

    override fun onPhase(kind: String, stream: String) {
        handler.post {
            when (kind) {
                "running" -> {
                    turnActive = true
                    startHistoryPoll()
                    showStatus(if (stream == "thinking") "正在思考…" else "工作中…")
                }
                "tool_call" -> {
                    // reset 快照里的实时阶段（订阅中途加入时唯一能拿到的工作状态）
                    turnActive = true
                    startHistoryPoll()
                    showStatus("工作中…")
                }
                "streaming" -> {
                    turnActive = true
                    startHistoryPoll()
                    if (stream == "thinking") showStatus("正在思考…") else hideStatus()
                }
                "ended", "interrupted" -> {
                    turnActive = false
                    hideStatus()
                    startHistoryPoll() // 降为空闲 60s 轮询
                    loadHistory()
                }
            }
        }
    }

    override fun onFrameUpsert(turnId: String, frameId: String, kind: String, role: String, text: String) {
        handler.post {
            when (kind) {
                "text" -> {
                    // 只渲染 assistant 正文：user 帧（含系统注入的 <system-reminder>/<cron-fire>）一律不进气泡，
                    // 历史消息里的注入块由 Api.getMessages 过滤
                    if (role != "assistant") return@post
                    if (!textFrames.containsKey(frameId)) {
                        textFrames[frameId] = StringBuilder(text)
                        refreshStreamingBubble()
                    }
                }
                // thinking 帧只更新状态条，不当正文渲染
                "thinking" -> showStatus("正在思考…")
            }
        }
    }

    override fun onFrameAppend(frameId: String, offset: Long, text: String) {
        handler.post {
            val sb = textFrames[frameId] ?: return@post
            sb.append(text)
            refreshStreamingBubble()
        }
    }

    override fun onToolFrame(frameId: String, name: String, state: String, summary: String) {
        handler.post {
            if (frameId.isEmpty()) return@post
            // running 用 🔧，done 标记 ✓；临时条目不进历史，turn 结束后随 loadHistory 清除
            val mark = if (state == "done") "✓" else "🔧"
            val text = if (summary.isEmpty()) "$mark $name" else "$mark $name: $summary"
            val idx = toolItems[frameId]
            if (idx != null && idx in messages.indices && messages[idx].id == "tool-$frameId") {
                adapter.updateText(idx, text)
            } else {
                toolItems[frameId] = adapter.add(ChatMsg("tool-$frameId", "tool", text, timeMillis = System.currentTimeMillis()))
                scrollToBottom()
            }
            if (state == "running") showStatus("工作中：$name")
        }
    }

    override fun onTurnState(state: String, error: String?) {
        handler.post {
            when (state) {
                "running" -> {
                    turnActive = true
                    textFrames.clear()
                    streamingIndex = -1
                    startHistoryPoll()
                }
                "completed" -> {
                    turnActive = false
                    textFrames.clear()
                    streamingIndex = -1
                    hideStatus()
                    startHistoryPoll() // 降为空闲 60s 轮询
                    loadHistory()
                }
                "failed", "cancelled" -> {
                    turnActive = false
                    textFrames.clear()
                    streamingIndex = -1
                    hideStatus()
                    startHistoryPoll() // 降为空闲 60s 轮询
                    val msg = if (error.isNullOrEmpty()) "本轮对话失败（$state）" else "出错了：$error"
                    adapter.add(ChatMsg("err-" + System.currentTimeMillis(), "assistant", msg, isError = true, timeMillis = System.currentTimeMillis()))
                    scrollToBottom()
                }
            }
        }
    }

    override fun onTranscriptReset() {
        handler.post {
            textFrames.clear()
            streamingIndex = -1
        }
    }

    override fun onContextUsage(tokens: Long, maxTokens: Long) {
        handler.post {
            if (tokens > 0) contextTokens = tokens
            if (maxTokens > 0) contextLimit = maxTokens
            updateContextView()
        }
    }

    // ---------- 上下文用量显示 ----------

    private fun updateContextView() {
        if (contextTokens < 0) {
            tvContext.visibility = View.GONE
            return
        }
        val limit = if (contextLimit > 0) "/${fmtTokens(contextLimit)}" else ""
        val pct = if (contextLimit > 0) " (${contextTokens * 100 / contextLimit}%)" else ""
        tvContext.text = "上下文 ${fmtTokens(contextTokens)}$limit$pct"
        tvContext.visibility = View.VISIBLE
    }

    /** token 数格式化：≥1000 用 k（23.5k / 1000k），否则原样 */
    private fun fmtTokens(n: Long): String =
        if (n >= 1000) {
            val k = n / 1000.0
            if (k % 1.0 == 0.0) "${k.toLong()}k" else String.format("%.1fk", k)
        } else "$n"

    // ---------- 流式气泡 ----------

    private fun refreshStreamingBubble() {
        val full = textFrames.values.joinToString("") { it.toString() }
        if (streamingIndex < 0) {
            streamingIndex = adapter.add(ChatMsg("stream", "assistant", full, streaming = true, timeMillis = System.currentTimeMillis()))
        } else {
            adapter.updateText(streamingIndex, full)
        }
        scrollToBottom()
    }

    // ---------- UI 辅助 ----------

    /** 解析服务端 ISO 时间戳（带时区偏移或 Z），失败返回 0（不显示时间） */
    private fun parseIso(s: String): Long {
        if (s.isEmpty()) return 0L
        return try {
            java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (e: Exception) {
            try {
                java.time.Instant.parse(s).toEpochMilli()
            } catch (e2: Exception) {
                0L
            }
        }
    }

    private fun showStatus(text: String) {
        tvStatus.text = text
        tvStatus.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        tvStatus.visibility = View.GONE
    }

    private fun scrollToBottom() {
        if (messages.isEmpty()) return
        val last = messages.size - 1
        // post 延迟到新 item 完成布局测量后再滚，避免落点不足导致最后一条半截留在列表下边界外
        recycler.post { recycler.scrollToPosition(last) }
    }

    private fun handleApiError(e: ApiException) {
        if (e.httpCode == 401 || e.httpCode == 403) {
            Toast.makeText(this, "Token 无效，请重新填写", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            Toast.makeText(this, "请求失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
