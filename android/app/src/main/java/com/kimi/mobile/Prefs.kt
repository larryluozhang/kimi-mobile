package com.kimi.mobile

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HostProfile(
    val id: String,
    val name: String,
    val url: String,
    val token: String
)

object Prefs {
    private const val NAME = "kimi_mobile_prefs"
    private const val KEY_PROFILES = "host_profiles"
    private const val KEY_ACTIVE = "active_profile_id"
    private const val KEY_VOICE = "voice_enabled"
    private const val KEY_VOICE_ENGINE = "voice_engine"
    private const val KEY_VOICE_MODEL_URL = "voice_model_url"
    private const val KEY_MODEL = "model"
    private const val KEY_WORKSPACE = "last_workspace_id"
    // 旧版单主机字段（用于迁移）
    private const val KEY_SERVER = "server_url"
    private const val KEY_TOKEN = "token"

    const val DEFAULT_SERVER = "http://127.0.0.1:58627"
    const val DEFAULT_HOST_NAME = "我的服务器"
    const val DEFAULT_MODEL = "kimi-code/k3"
    const val DEFAULT_WORKSPACE_ROOT = "/tmp/kimi-workspace"
    const val DEFAULT_VOICE_MODEL_URL =
        "https://github.com/larryluozhang/kimi-mobile/releases/download/v0.6.1-models/model-zipformer-bilingual.zip"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 首次运行播种预置档案；旧版字段迁移为档案 */
    private fun ensureSeeded(ctx: Context) {
        val sp = sp(ctx)
        if (sp.contains(KEY_PROFILES)) return
        val arr = JSONArray()
        val legacyServer = sp.getString(KEY_SERVER, "")?.trim().orEmpty()
        val legacyToken = sp.getString(KEY_TOKEN, "")?.trim().orEmpty()
        arr.put(
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("name", DEFAULT_HOST_NAME)
                .put("url", legacyServer.ifEmpty { DEFAULT_SERVER })
                .put("token", legacyToken)
        )
        sp.edit()
            .putString(KEY_PROFILES, arr.toString())
            .remove(KEY_SERVER)
            .remove(KEY_TOKEN)
            .apply()
    }

    fun profiles(ctx: Context): List<HostProfile> {
        ensureSeeded(ctx)
        val raw = sp(ctx).getString(KEY_PROFILES, "[]") ?: "[]"
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

    fun activeProfile(ctx: Context): HostProfile? {
        val list = profiles(ctx)
        if (list.isEmpty()) return null
        val activeId = sp(ctx).getString(KEY_ACTIVE, null)
        return list.firstOrNull { it.id == activeId } ?: list.first()
    }

    fun setActiveProfile(ctx: Context, id: String) {
        sp(ctx).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun upsertProfile(ctx: Context, profile: HostProfile) {
        val list = profiles(ctx).toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        saveProfiles(ctx, list)
        if (activeProfile(ctx)?.id == null) setActiveProfile(ctx, profile.id)
    }

    fun deleteProfile(ctx: Context, id: String) {
        val list = profiles(ctx).filterNot { it.id == id }
        saveProfiles(ctx, list)
        if (sp(ctx).getString(KEY_ACTIVE, null) == id) {
            sp(ctx).edit().remove(KEY_ACTIVE).apply()
        }
    }

    private fun saveProfiles(ctx: Context, list: List<HostProfile>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("url", p.url)
                    .put("token", p.token)
            )
        }
        sp(ctx).edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    fun serverUrl(ctx: Context): String =
        activeProfile(ctx)?.url?.trim()?.trimEnd('/')?.ifEmpty { DEFAULT_SERVER } ?: DEFAULT_SERVER

    fun token(ctx: Context): String = activeProfile(ctx)?.token?.trim() ?: ""

    fun voiceEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_VOICE, true)

    fun setVoiceEnabled(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_VOICE, enabled).apply()
    }

    /** 语音引擎：auto（离线优先，缺失回退系统）/ onnx（仅离线）/ system（仅系统） */
    fun voiceEngine(ctx: Context): String =
        sp(ctx).getString(KEY_VOICE_ENGINE, "auto") ?: "auto"

    fun setVoiceEngine(ctx: Context, engine: String) {
        sp(ctx).edit().putString(KEY_VOICE_ENGINE, engine).apply()
    }

    /** 离线语音模型下载地址（可在设置页修改） */
    fun voiceModelUrl(ctx: Context): String =
        sp(ctx).getString(KEY_VOICE_MODEL_URL, DEFAULT_VOICE_MODEL_URL)?.trim()
            ?.ifEmpty { DEFAULT_VOICE_MODEL_URL } ?: DEFAULT_VOICE_MODEL_URL

    fun setVoiceModelUrl(ctx: Context, url: String) {
        sp(ctx).edit().putString(KEY_VOICE_MODEL_URL, url.trim()).apply()
    }

    fun model(ctx: Context): String =
        sp(ctx).getString(KEY_MODEL, DEFAULT_MODEL)?.trim()
            ?.ifEmpty { DEFAULT_MODEL } ?: DEFAULT_MODEL

    fun setModel(ctx: Context, model: String) {
        sp(ctx).edit().putString(KEY_MODEL, model.trim()).apply()
    }

    fun lastWorkspaceId(ctx: Context): String? = sp(ctx).getString(KEY_WORKSPACE, null)

    fun setLastWorkspaceId(ctx: Context, id: String) {
        sp(ctx).edit().putString(KEY_WORKSPACE, id).apply()
    }

    // ---- 会话模式（每会话本地持久化 JSON：sessionId -> 模式集合）----
    // v0.35.0 服务端 GET /profile 不回显 agent_config（硬编码 model:""），
    // 与官方 Web UI（localStorage）和 macOS 版一致：本地状态为准。
    private const val KEY_SESSION_MODES = "session_modes"

    fun sessionMode(ctx: Context, sessionId: String): SessionProfile? {
        val raw = sp(ctx).getString(KEY_SESSION_MODES, "{}") ?: "{}"
        val all = try { JSONObject(raw) } catch (e: Exception) { return null }
        val o = all.optJSONObject(sessionId) ?: return null
        return SessionProfile(
            planMode = o.optBoolean("plan_mode", false),
            swarmMode = o.optBoolean("swarm_mode", false),
            permissionMode = o.optString("permission_mode", "manual"),
            model = o.optString("model", ""),
            thinking = o.optString("thinking", ""),
            goalObjective = o.optString("goal_objective", "")
        )
    }

    fun saveSessionMode(ctx: Context, sessionId: String, p: SessionProfile) {
        val raw = sp(ctx).getString(KEY_SESSION_MODES, "{}") ?: "{}"
        val all = try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
        all.put(
            sessionId,
            JSONObject()
                .put("plan_mode", p.planMode)
                .put("swarm_mode", p.swarmMode)
                .put("permission_mode", p.permissionMode)
                .put("model", p.model)
                .put("thinking", p.thinking)
                .put("goal_objective", p.goalObjective)
        )
        sp(ctx).edit().putString(KEY_SESSION_MODES, all.toString()).apply()
    }
}
