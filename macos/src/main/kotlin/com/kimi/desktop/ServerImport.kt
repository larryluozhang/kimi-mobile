package com.kimi.desktop

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class ImportedServer(val name: String, val url: String, val token: String)

class ImportParseException(message: String) : Exception(message)

/** 解析服务器安装器输出的 deeplink（kimi-mobile://connect?...）或 JSON 卡片，校验后返回可建档的主机信息 */
fun parseServerImport(raw: String): ImportedServer {
    val input = raw.trim()
    if (input.isEmpty()) throw ImportParseException("内容为空，请粘贴 kimi-mobile://connect 链接或 JSON 卡片")
    return if (input.startsWith("{")) parseJsonCard(input) else parseDeeplink(input)
}

private const val DEEPLINK_PREFIX = "kimi-mobile://connect"

private fun parseDeeplink(input: String): ImportedServer {
    if (!input.startsWith(DEEPLINK_PREFIX)) {
        throw ImportParseException("无法识别的格式：既不是 kimi-mobile://connect 链接，也不是 JSON 卡片")
    }
    val query = input.substringAfter('?', "")
    if (query.isEmpty()) throw ImportParseException("链接缺少查询参数（需要 name/url/token）")
    val params = HashMap<String, String>()
    for (pair in query.split('&')) {
        if (pair.isEmpty()) continue
        val key = pair.substringBefore('=')
        val value = pair.substringAfter('=', "")
        params[key] = URLDecoder.decode(value, StandardCharsets.UTF_8)
    }
    // v 为版本号，可选，当前仅 v=1
    return validated(
        name = params["name"].orEmpty(),
        url = params["url"].orEmpty(),
        token = params["token"].orEmpty()
    )
}

private fun parseJsonCard(input: String): ImportedServer {
    val o = try {
        JSONObject(input)
    } catch (e: Exception) {
        throw ImportParseException("JSON 解析失败：${e.message ?: e.javaClass.simpleName}")
    }
    return validated(
        name = o.optString("name"),
        url = o.optString("url"),
        token = o.optString("token")
    )
}

private fun validated(name: String, url: String, token: String): ImportedServer {
    val u = url.trim()
    val t = token.trim()
    if (!u.startsWith("http://") && !u.startsWith("https://")) {
        throw ImportParseException("URL 无效：必须以 http:// 或 https:// 开头")
    }
    val host = try {
        URI(u).host ?: throw ImportParseException("URL 无效：缺少主机名")
    } catch (e: ImportParseException) {
        throw e
    } catch (e: Exception) {
        throw ImportParseException("URL 无效：${e.message ?: e.javaClass.simpleName}")
    }
    if (t.isEmpty()) throw ImportParseException("Token 为空")
    val n = name.trim().ifEmpty { host }
    return ImportedServer(n, u, t)
}
