package com.kimi.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Properties
import java.util.UUID

data class HostProfile(
    val id: String,
    val name: String,
    val url: String,
    val token: String
)

/** 配置持久化：java.util.Properties -> ~/.kimi-mobile/config.properties */
object Prefs {
    private const val KEY_PROFILES = "host_profiles"
    private const val KEY_ACTIVE = "active_profile_id"
    private const val KEY_MODEL = "model"
    private const val KEY_WORKSPACE = "last_workspace_id"

    const val DEFAULT_SERVER = "http://127.0.0.1:58627"
    const val DEFAULT_HOST_NAME = "我的服务器"
    const val DEFAULT_MODEL = "kimi-code/k3"
    const val DEFAULT_WORKSPACE_ROOT = "/tmp/kimi-workspace"

    private val dir = File(System.getProperty("user.home"), ".kimi-mobile")
    private val file = File(dir, "config.properties")

    @Synchronized
    private fun load(): Properties {
        val p = Properties()
        if (file.isFile) file.inputStream().use { p.load(it) }
        return p
    }

    @Synchronized
    private fun save(p: Properties) {
        dir.mkdirs()
        file.outputStream().use { p.store(it, "kimi-mobile desktop") }
    }

    @Synchronized
    private fun ensureSeeded() {
        val p = load()
        if (p.containsKey(KEY_PROFILES)) return
        val arr = JSONArray()
        arr.put(
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("name", DEFAULT_HOST_NAME)
                .put("url", DEFAULT_SERVER)
                .put("token", "")
        )
        p.setProperty(KEY_PROFILES, arr.toString())
        save(p)
    }

    fun profiles(): List<HostProfile> {
        ensureSeeded()
        val raw = load().getProperty(KEY_PROFILES, "[]")
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<HostProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                HostProfile(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    url = o.optString("url"),
                    token = o.optString("token")
                )
            )
        }
        return out
    }

    fun activeProfile(): HostProfile? {
        val list = profiles()
        if (list.isEmpty()) return null
        val activeId = load().getProperty(KEY_ACTIVE)
        return list.firstOrNull { it.id == activeId } ?: list.first()
    }

    fun setActiveProfile(id: String) {
        val p = load()
        p.setProperty(KEY_ACTIVE, id)
        save(p)
    }

    fun upsertProfile(profile: HostProfile) {
        val list = profiles().toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        saveProfiles(list)
        if (activeProfile()?.id == null) setActiveProfile(profile.id)
    }

    fun deleteProfile(id: String) {
        saveProfiles(profiles().filterNot { it.id == id })
        val p = load()
        if (p.getProperty(KEY_ACTIVE) == id) {
            p.remove(KEY_ACTIVE)
            save(p)
        }
    }

    private fun saveProfiles(list: List<HostProfile>) {
        val arr = JSONArray()
        for (pr in list) {
            arr.put(
                JSONObject()
                    .put("id", pr.id)
                    .put("name", pr.name)
                    .put("url", pr.url)
                    .put("token", pr.token)
            )
        }
        val p = load()
        p.setProperty(KEY_PROFILES, arr.toString())
        save(p)
    }

    fun serverUrl(): String =
        activeProfile()?.url?.trim()?.trimEnd('/')?.ifEmpty { DEFAULT_SERVER } ?: DEFAULT_SERVER

    fun token(): String = activeProfile()?.token?.trim() ?: ""

    fun model(): String {
        val m = load().getProperty(KEY_MODEL, DEFAULT_MODEL)?.trim()
        return if (m.isNullOrEmpty()) DEFAULT_MODEL else m
    }

    fun setModel(model: String) {
        val p = load()
        p.setProperty(KEY_MODEL, model.trim())
        save(p)
    }

    fun lastWorkspaceId(): String? = load().getProperty(KEY_WORKSPACE)

    fun setLastWorkspaceId(id: String) {
        val p = load()
        p.setProperty(KEY_WORKSPACE, id)
        save(p)
    }

    // ---- 会话模式（每会话本地持久化；服务端 v0.35.0 的 GET /profile 不回显，官方 Web UI 同样用本地状态） ----
    private const val KEY_SESSION_MODES = "session_modes"

    fun sessionMode(sessionId: String): Api.SessionProfile? {
        val raw = load().getProperty(KEY_SESSION_MODES, "{}") ?: "{}"
        val all = try { JSONObject(raw) } catch (e: Exception) { return null }
        val o = all.optJSONObject(sessionId) ?: return null
        return Api.SessionProfile(
            model = o.optString("model", ""),
            thinking = o.optString("thinking", ""),
            permissionMode = o.optString("permission_mode", "manual"),
            planMode = o.optBoolean("plan_mode", false),
            swarmMode = o.optBoolean("swarm_mode", false),
            goalObjective = o.optString("goal_objective", ""),
            goalControl = ""
        )
    }

    fun saveSessionMode(sessionId: String, p: Api.SessionProfile) {
        val props = load()
        val all = try { JSONObject(props.getProperty(KEY_SESSION_MODES, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        all.put(
            sessionId,
            JSONObject()
                .put("model", p.model)
                .put("thinking", p.thinking)
                .put("permission_mode", p.permissionMode)
                .put("plan_mode", p.planMode)
                .put("swarm_mode", p.swarmMode)
                .put("goal_objective", p.goalObjective)
        )
        props.setProperty(KEY_SESSION_MODES, all.toString())
        save(props)
    }
}
