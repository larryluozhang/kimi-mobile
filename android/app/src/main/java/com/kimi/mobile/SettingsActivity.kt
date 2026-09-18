package com.kimi.mobile

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.UUID

class SettingsActivity : AppCompatActivity() {

    private lateinit var profileList: LinearLayout
    private lateinit var spinnerModel: Spinner
    private lateinit var cbVoice: CheckBox
    private lateinit var tvVoiceHint: TextView
    private lateinit var rgVoiceEngine: RadioGroup
    private lateinit var etVoiceModelUrl: EditText
    private lateinit var btnDownloadModel: Button
    private lateinit var tvModelDownload: TextView
    private lateinit var etVoiceEngineUrl: EditText
    private lateinit var btnDownloadEngine: Button
    private lateinit var tvEngineDownload: TextView
    private lateinit var tvUpdateDownload: TextView

    @Volatile private var modelDownloading = false
    @Volatile private var engineDownloading = false

    /** 全局模型 Spinner 选项值（完整 model id）与展示名（服务端 display_name；预置去 provider 前缀） */
    private var modelChoices: List<String> = Api.MODEL_PRESETS
    private var modelLabels: List<String> = Api.MODEL_PRESETS.map { it.removePrefix("kimi-code/") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        profileList = findViewById(R.id.profileList)
        spinnerModel = findViewById(R.id.spinnerModel)
        cbVoice = findViewById(R.id.cbVoice)
        tvVoiceHint = findViewById(R.id.tvVoiceHint)
        rgVoiceEngine = findViewById(R.id.rgVoiceEngine)
        etVoiceModelUrl = findViewById(R.id.etVoiceModelUrl)
        btnDownloadModel = findViewById(R.id.btnDownloadModel)
        tvModelDownload = findViewById(R.id.tvModelDownload)
        etVoiceEngineUrl = findViewById(R.id.etVoiceEngineUrl)
        btnDownloadEngine = findViewById(R.id.btnDownloadEngine)
        tvEngineDownload = findViewById(R.id.tvEngineDownload)
        tvUpdateDownload = findViewById(R.id.tvUpdateDownload)

        findViewById<TextView>(R.id.tvVersion).text =
            "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener { checkUpdate() }

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        refreshModelChoices()
        loadModels()
        cbVoice.isChecked = Prefs.voiceEnabled(this)

        val onnxReady = SpeechOnnx.isModelAvailable(this)
        val sysAvailable = SpeechRecognizer.isRecognitionAvailable(this)
        rgVoiceEngine.check(
            when (Prefs.voiceEngine(this)) {
                "onnx" -> R.id.rbEngineOnnx
                "system" -> R.id.rbEngineSystem
                else -> R.id.rbEngineAuto
            }
        )
        tvVoiceHint.text = buildString {
            append(if (onnxReady) "离线模型已下载（sherpa-onnx 中英双语）。" else "离线模型未下载，可点击下方按钮下载；未下载时将使用系统识别。")
            if (!sysAvailable) append("本机没有系统语音识别服务（无 GMS 的 ROM 常见）。")
            append("需授予录音权限。")
        }

        etVoiceModelUrl.setText(Prefs.voiceModelUrl(this))
        tvModelDownload.text = if (onnxReady) "模型已就位" else ""
        btnDownloadModel.setOnClickListener { startModelDownload() }

        etVoiceEngineUrl.setText(Prefs.voiceEngineUrl(this))
        tvEngineDownload.text = if (NativeEngine.isEngineAvailable(this)) "引擎已就位" else ""
        btnDownloadEngine.setOnClickListener { startEngineDownload() }

        findViewById<Button>(R.id.btnAddProfile).setOnClickListener {
            editProfile(null)
        }

        findViewById<Button>(R.id.btnImportProfile).setOnClickListener {
            showImportDialog()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            Prefs.setModel(
                this,
                modelChoices.getOrNull(spinnerModel.selectedItemPosition) ?: Prefs.DEFAULT_MODEL
            )
            Prefs.setVoiceEnabled(this, cbVoice.isChecked)
            Prefs.setVoiceEngine(
                this,
                when (rgVoiceEngine.checkedRadioButtonId) {
                    R.id.rbEngineOnnx -> "onnx"
                    R.id.rbEngineSystem -> "system"
                    else -> "auto"
                }
            )
            Prefs.setVoiceModelUrl(this, etVoiceModelUrl.text.toString())
            Prefs.setVoiceEngineUrl(this, etVoiceEngineUrl.text.toString())
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }

        renderProfiles()

        // 从 kimi-mobile://connect deeplink 启动时直接弹出导入对话框（预填链接）
        intent?.data?.let { uri ->
            if (uri.scheme == "kimi-mobile" && uri.host == "connect") {
                showImportDialog(uri.toString())
            }
        }
    }

    /** 下载离线模型：地址先落 Prefs，子线程下载+解压，进度显示在按钮旁 */
    private fun startModelDownload() {
        if (modelDownloading) return
        val url = etVoiceModelUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "请先填写下载地址", Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.setVoiceModelUrl(this, url)
        modelDownloading = true
        btnDownloadModel.isEnabled = false
        tvModelDownload.text = "准备下载…"
        Thread {
            ModelDownloader.download(this, url, object : ModelDownloader.Callback {
                override fun onProgress(downloaded: Long, total: Long) {
                    val dl = downloaded / 1024.0 / 1024.0
                    val text = if (total > 0) {
                        "%.1f MB / %.1f MB".format(dl, total / 1024.0 / 1024.0)
                    } else {
                        "%.1f MB".format(dl)
                    }
                    runOnUiThread { tvModelDownload.text = text }
                }

                override fun onDone() {
                    runOnUiThread {
                        modelDownloading = false
                        btnDownloadModel.isEnabled = true
                        tvModelDownload.text = "模型已就位"
                        tvVoiceHint.text = buildString {
                            append("离线模型已下载（sherpa-onnx 中英双语）。")
                            if (!SpeechRecognizer.isRecognitionAvailable(this@SettingsActivity)) {
                                append("本机没有系统语音识别服务（无 GMS 的 ROM 常见）。")
                            }
                            append("需授予录音权限。")
                        }
                        Toast.makeText(this@SettingsActivity, "离线模型下载完成", Toast.LENGTH_LONG).show()
                    }
                }

                override fun onError(msg: String) {
                    runOnUiThread {
                        modelDownloading = false
                        btnDownloadModel.isEnabled = true
                        tvModelDownload.text = msg
                        Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
            })
        }.start()
    }

    /** 下载离线引擎：地址先落 Prefs，子线程下载+解压，进度显示在按钮旁 */
    private fun startEngineDownload() {
        if (engineDownloading) return
        val url = etVoiceEngineUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "请先填写下载地址", Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.setVoiceEngineUrl(this, url)
        engineDownloading = true
        btnDownloadEngine.isEnabled = false
        tvEngineDownload.text = "准备下载…"
        Thread {
            ModelDownloader.downloadEngine(this, url, object : ModelDownloader.Callback {
                override fun onProgress(downloaded: Long, total: Long) {
                    val dl = downloaded / 1024.0 / 1024.0
                    val text = if (total > 0) {
                        "%.1f MB / %.1f MB".format(dl, total / 1024.0 / 1024.0)
                    } else {
                        "%.1f MB".format(dl)
                    }
                    runOnUiThread { tvEngineDownload.text = text }
                }

                override fun onDone() {
                    runOnUiThread {
                        engineDownloading = false
                        btnDownloadEngine.isEnabled = true
                        tvEngineDownload.text = "引擎已就位"
                        Toast.makeText(this@SettingsActivity, "离线引擎下载完成", Toast.LENGTH_LONG).show()
                    }
                }

                override fun onError(msg: String) {
                    runOnUiThread {
                        engineDownloading = false
                        btnDownloadEngine.isEnabled = true
                        tvEngineDownload.text = msg
                        Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
            })
        }.start()
    }

    /** 手动检查更新：结果 Toast/弹窗提示；有新版复用启动自动检查的更新对话框 */
    private fun checkUpdate() {
        tvUpdateDownload.text = "正在检查更新…"
        AppUpdater.checkLatest(this) { result ->
            when (result) {
                is AppUpdater.CheckResult.Available -> {
                    tvUpdateDownload.text = ""
                    AppUpdater.showUpdateDialog(this, result.info)
                }
                AppUpdater.CheckResult.UpToDate -> {
                    tvUpdateDownload.text = ""
                    Toast.makeText(this, "已是最新版本", Toast.LENGTH_SHORT).show()
                }
                is AppUpdater.CheckResult.Error -> {
                    tvUpdateDownload.text = ""
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 重建全局模型下拉：当前已存模型不在列表时追加保留（服务端已删/自定义值） */
    private fun refreshModelChoices() {
        val current = Prefs.model(this)
        if (current.isNotEmpty() && current !in modelChoices) {
            modelChoices = modelChoices + current
            modelLabels = modelLabels + current
        }
        spinnerModel.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            modelLabels
        )
        spinnerModel.setSelection(modelChoices.indexOf(current).coerceAtLeast(0))
    }

    /** 拉取服务端模型列表填充全局模型下拉（display_name 展示，model id 为值）；失败/为空静默回退预置 */
    private fun loadModels() {
        Thread {
            try {
                val models = Api.listModels(Prefs.serverUrl(this), Prefs.token(this))
                if (models.isEmpty()) return@Thread
                runOnUiThread {
                    modelChoices = models.map { it.model }
                    modelLabels = models.map { it.displayName }
                    refreshModelChoices()
                }
            } catch (e: Exception) {
                // 旧服务端无此接口/网络抖动：静默回退预置
            }
        }.start()
    }

    private fun renderProfiles() {        profileList.removeAllViews()
        val active = Prefs.activeProfile(this)
        for (p in Prefs.profiles(this)) {
            profileList.addView(profileRow(p, active?.id == p.id))
        }
    }

    private fun profileRow(p: HostProfile, isActive: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        val radio = RadioButton(this).apply {
            isChecked = isActive
            setOnClickListener {
                Prefs.setActiveProfile(this@SettingsActivity, p.id)
                renderProfiles()
                Toast.makeText(this@SettingsActivity, "已切换到「${p.name}」", Toast.LENGTH_SHORT).show()
            }
        }
        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "${p.name}\n${p.url}${if (p.token.isEmpty()) "（未填 Token）" else ""}"
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, if (isActive) Typeface.BOLD else Typeface.NORMAL)
            setOnClickListener { radio.performClick() }
        }
        val btnEdit = Button(this).apply {
            text = "编辑"
            textSize = 12f
            setOnClickListener { editProfile(p) }
        }
        val btnDelete = Button(this).apply {
            text = "删除"
            textSize = 12f
            setOnClickListener { confirmDelete(p) }
        }
        row.addView(radio)
        row.addView(label)
        row.addView(btnEdit)
        row.addView(btnDelete)
        return row
    }

    private fun editProfile(existing: HostProfile?) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val etName = EditText(this).apply { hint = "名称（如：我的服务器）" }
        val etUrl = EditText(this).apply { hint = "服务器地址（http://…）" }
        val etToken = EditText(this).apply { hint = "API Token" }
        container.addView(etName)
        container.addView(etUrl)
        container.addView(etToken)

        existing?.let {
            etName.setText(it.name)
            etUrl.setText(it.url)
            etToken.setText(it.token)
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新增主机" else "编辑主机")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim()
                val url = etUrl.text.toString().trim().trimEnd('/')
                val token = etToken.text.toString().trim()
                if (name.isEmpty() || url.isEmpty()) {
                    Toast.makeText(this, "名称和地址不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val profile = HostProfile(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = name,
                    url = url,
                    token = token
                )
                Prefs.upsertProfile(this, profile)
                if (existing == null && Prefs.profiles(this).size == 1) {
                    Prefs.setActiveProfile(this, profile.id)
                }
                renderProfiles()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showImportDialog(prefill: String = "") {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val etImport = EditText(this).apply {
            hint = "粘贴 kimi-mobile://connect 链接或 JSON 卡片"
            minLines = 4
            gravity = Gravity.TOP
            setText(prefill)
            if (prefill.isNotEmpty()) setSelection(prefill.length)
        }
        container.addView(etImport)

        AlertDialog.Builder(this)
            .setTitle("导入服务器")
            .setView(container)
            .setPositiveButton("导入") { _, _ -> importProfile(etImport.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 解析 deeplink / JSON 卡片并落为档案；同 url 已存在时仅更新 token，否则新建并置为当前主机 */
    private fun importProfile(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "请先粘贴链接或 JSON 卡片", Toast.LENGTH_SHORT).show()
            return
        }
        var name = ""
        var url = ""
        var token = ""
        when {
            text.startsWith("{") -> {
                val o = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    Toast.makeText(this, "JSON 解析失败：${e.message}", Toast.LENGTH_LONG).show()
                    return
                }
                name = o.optString("name").trim()
                url = o.optString("url").trim()
                token = o.optString("token").trim()
            }
            text.startsWith("kimi-mobile://connect") -> {
                val uri = Uri.parse(text)
                name = uri.getQueryParameter("name")?.trim().orEmpty()
                url = uri.getQueryParameter("url")?.trim().orEmpty()
                token = uri.getQueryParameter("token")?.trim().orEmpty()
            }
            else -> {
                Toast.makeText(this, "无法识别：需以 kimi-mobile://connect 开头，或为 JSON 卡片", Toast.LENGTH_LONG).show()
                return
            }
        }
        url = url.trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, "url 无效：需以 http:// 或 https:// 开头", Toast.LENGTH_LONG).show()
            return
        }
        if (token.isEmpty()) {
            Toast.makeText(this, "缺少 token", Toast.LENGTH_LONG).show()
            return
        }
        if (name.isEmpty()) {
            name = Uri.parse(url).host?.takeIf { it.isNotEmpty() } ?: url
        }

        val existing = Prefs.profiles(this).firstOrNull { it.url.trimEnd('/') == url }
        val profile = if (existing != null) {
            existing.copy(token = token)
        } else {
            HostProfile(
                id = "import-${System.currentTimeMillis()}",
                name = name,
                url = url,
                token = token
            )
        }
        Prefs.upsertProfile(this, profile)
        Prefs.setActiveProfile(this, profile.id)
        renderProfiles()
        Toast.makeText(this, "已导入：${profile.name}", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(p: HostProfile) {
        AlertDialog.Builder(this)
            .setTitle("删除主机")
            .setMessage("确定删除「${p.name}」吗？")
            .setPositiveButton("删除") { _, _ ->
                Prefs.deleteProfile(this, p.id)
                renderProfiles()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
