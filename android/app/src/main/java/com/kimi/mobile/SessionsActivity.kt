package com.kimi.mobile

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class SessionsActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var adapter: SessionAdapter
    private lateinit var spinner: Spinner

    private var workspaces = ArrayList<WorkspaceItem>()
    private var allSessions = ArrayList<SessionItem>()
    private var selectedWorkspace: WorkspaceItem? = null
    private var spinnerInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sessions)

        swipeRefresh = findViewById(R.id.swipeRefresh)
        spinner = findViewById(R.id.spinnerWorkspace)
        val recycler = findViewById<RecyclerView>(R.id.recyclerSessions)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = SessionAdapter { session ->
            val intent = Intent(this, ChatActivity::class.java)
            intent.putExtra(ChatActivity.EXTRA_SESSION_ID, session.id)
            intent.putExtra(ChatActivity.EXTRA_SESSION_TITLE, session.title)
            startActivity(intent)
        }
        recycler.adapter = adapter

        swipeRefresh.setOnRefreshListener { loadAll() }
        swipeRefresh.setColorSchemeResources(R.color.primary)

        findViewById<Button>(R.id.btnNewSession).setOnClickListener { createSession() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in workspaces.indices) {
                    selectedWorkspace = workspaces[position]
                    Prefs.setLastWorkspaceId(this@SessionsActivity, workspaces[position].id)
                    if (spinnerInitialized) applyFilter()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** resumed 期间每 30s 自动刷新，避免"运行中"徽标停留旧状态；loadAll 只读，不打断下拉/输入 */
    private val autoRefresh = object : Runnable {
        override fun run() {
            loadAll()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次回到本页都全量刷新（主机可能已在设置中切换）
        spinnerInitialized = false
        loadAll()
        handler.postDelayed(autoRefresh, 30_000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(autoRefresh)
    }

    private fun server() = Prefs.serverUrl(this)
    private fun token() = Prefs.token(this)

    private fun loadAll() {
        swipeRefresh.isRefreshing = true
        Thread {
            try {
                val ws = Api.listWorkspaces(server(), token())
                val sessions = Api.listSessions(server(), token())
                handler.post {
                    workspaces.clear()
                    workspaces.addAll(ws)
                    allSessions.clear()
                    allSessions.addAll(sessions)
                    setupSpinner()
                    applyFilter()
                    swipeRefresh.isRefreshing = false
                }
            } catch (e: ApiException) {
                handler.post {
                    swipeRefresh.isRefreshing = false
                    handleError(e)
                }
            } catch (e: Exception) {
                handler.post {
                    swipeRefresh.isRefreshing = false
                    Toast.makeText(this, "加载失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun setupSpinner() {
        val names = workspaces.map { "${it.name}（${it.root}）" }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        // 默认选中上次用的工作区；否则选中会话数最多的工作区；再否则第一个
        // （DEFAULT_WORKSPACE_ROOT 是 Linux 路径，在 Mac/iOS 主机上匹配不到，不能作兜底）
        val lastId = Prefs.lastWorkspaceId(this)
        var idx = workspaces.indexOfFirst { it.id == lastId }
        if (idx < 0 && workspaces.isNotEmpty()) {
            idx = workspaces.indices.maxByOrNull { workspaces[it].sessionCount } ?: 0
        }
        if (idx < 0) idx = 0
        if (workspaces.isNotEmpty()) {
            spinner.setSelection(idx)
            selectedWorkspace = workspaces[idx]
        }
        spinnerInitialized = true
    }

    private fun applyFilter() {
        val ws = selectedWorkspace
        val filtered = if (ws == null) allSessions else allSessions.filter { it.workspaceId == ws.id }
        adapter.submit(filtered)
        findViewById<View>(R.id.tvEmpty).visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun createSession() {
        val ws = selectedWorkspace
        if (ws == null) {
            Toast.makeText(this, "请先选择工作区", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            try {
                val s = Api.createSession(server(), token(), ws)
                handler.post {
                    val intent = Intent(this, ChatActivity::class.java)
                    intent.putExtra(ChatActivity.EXTRA_SESSION_ID, s.id)
                    intent.putExtra(ChatActivity.EXTRA_SESSION_TITLE, s.title)
                    startActivity(intent)
                }
            } catch (e: ApiException) {
                handler.post { handleError(e) }
            } catch (e: Exception) {
                handler.post {
                    Toast.makeText(this, "创建会话失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun handleError(e: ApiException) {
        if (e.httpCode == 401 || e.httpCode == 403) {
            Toast.makeText(this, "Token 无效，请重新填写", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            Toast.makeText(this, "请求失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
