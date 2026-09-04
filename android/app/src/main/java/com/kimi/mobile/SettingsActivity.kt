package com.kimi.mobile

import android.graphics.Typeface
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID

class SettingsActivity : AppCompatActivity() {

    private lateinit var profileList: LinearLayout
    private lateinit var etModel: EditText
    private lateinit var cbVoice: CheckBox
    private lateinit var tvVoiceHint: TextView
    private lateinit var rgVoiceEngine: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        profileList = findViewById(R.id.profileList)
        etModel = findViewById(R.id.etModel)
        cbVoice = findViewById(R.id.cbVoice)
        tvVoiceHint = findViewById(R.id.tvVoiceHint)
        rgVoiceEngine = findViewById(R.id.rgVoiceEngine)

        findViewById<TextView>(R.id.tvVersion).text =
            "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        etModel.setText(Prefs.model(this))
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
            append(if (onnxReady) "离线模型已内置（sherpa-onnx 中英双语）。" else "离线模型未打包，将使用系统识别。")
            if (!sysAvailable) append("本机没有系统语音识别服务（无 GMS 的 ROM 常见）。")
            append("需授予录音权限。")
        }

        findViewById<Button>(R.id.btnAddProfile).setOnClickListener {
            editProfile(null)
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            Prefs.setModel(this, etModel.text.toString())
            Prefs.setVoiceEnabled(this, cbVoice.isChecked)
            Prefs.setVoiceEngine(
                this,
                when (rgVoiceEngine.checkedRadioButtonId) {
                    R.id.rbEngineOnnx -> "onnx"
                    R.id.rbEngineSystem -> "system"
                    else -> "auto"
                }
            )
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }

        renderProfiles()
    }

    private fun renderProfiles() {
        profileList.removeAllViews()
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
