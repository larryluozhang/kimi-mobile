package com.kimi.mobile

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 启动门控：探测 Tailscale 内网服务器 /api/v1/healthz（3s 超时）。
 * 不通则展示引导并每 5s 重试；通了进入会话列表（无 token 先跳设置页）。
 */
class GateActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var checking = false

    private lateinit var tvStatus: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnOpenTailscale: Button
    private lateinit var btnGateSettings: Button
    private lateinit var progress: ProgressBar

    private val retryRunnable = object : Runnable {
        override fun run() {
            checkServer()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gate)
        tvStatus = findViewById(R.id.gateStatus)
        tvHint = findViewById(R.id.gateHint)
        btnOpenTailscale = findViewById(R.id.btnOpenTailscale)
        btnGateSettings = findViewById(R.id.btnGateSettings)
        progress = findViewById(R.id.gateProgress)

        // 门控页常驻"服务器设置"入口：连不上时也能改主机/token
        btnGateSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnOpenTailscale.setOnClickListener {
            val intent = packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
            if (intent != null) {
                startActivity(intent)
            } else {
                // 找不到 Tailscale（或未安装）：退而打开系统 VPN 设置页
                Toast.makeText(this, "未检测到 Tailscale，已打开 VPN 设置", Toast.LENGTH_LONG).show()
                try {
                    startActivity(android.content.Intent("android.settings.VPN_SETTINGS"))
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(retryRunnable)
        handler.post(retryRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(retryRunnable)
    }

    private fun checkServer() {
        if (checking) return
        checking = true
        val server = Prefs.serverUrl(this)
        Thread {
            val code = Api.healthz(server)
            handler.post {
                checking = false
                if (code == 200) {
                    handler.removeCallbacks(retryRunnable)
                    proceed()
                } else {
                    tvStatus.text = "无法连接服务器\n$server"
                    tvHint.visibility = View.VISIBLE
                    btnOpenTailscale.visibility = View.VISIBLE
                    progress.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun proceed() {
        if (Prefs.token(this).isEmpty()) {
            Toast.makeText(this, "首次使用请先在设置中填写 API Token", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            startActivity(Intent(this, SessionsActivity::class.java))
        }
        finish()
    }
}
