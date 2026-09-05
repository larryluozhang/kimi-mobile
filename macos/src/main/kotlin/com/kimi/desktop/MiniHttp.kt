package com.kimi.desktop

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 极简阻塞式 HTTP/1.1 客户端（基于 NIO SocketChannel）。
 *
 * 注意：本机（2014 Mac，OCLP 运行 macOS 15.6）上 JDK 的 java.net.Socket、
 * java.net.http.HttpClient、OkHttp 的读路径全部失灵（TCP 连接成功但永远读不到数据），
 * 唯有 NIO SocketChannel 工作正常（已用 T4/T7/T10/T12 系列探针验证），因此手写。
 * 请求固定带 Connection: close，不做 keep-alive。
 */
object MiniHttp {
    private val watchdog = Executors.newSingleThreadScheduledExecutor()

    data class Response(val code: Int, val headers: Map<String, String>, val body: String)

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 30_000): Response =
        request("GET", url, headers, null, timeoutMs)

    fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Long = 30_000): Response =
        request("POST", url, headers, body, timeoutMs)

    fun request(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Long): Response {
        val uri = URI(url)
        val host = uri.host
        val port = if (uri.port > 0) uri.port else 80
        val path = if (uri.rawQuery != null) "${uri.rawPath ?: "/"}?${uri.rawQuery}" else (uri.rawPath ?: "/")
        AppLog.log("HTTP", "-> $method $url")

        val ch = SocketChannel.open()
        val killer = watchdog.schedule({ runCatching { ch.close() } }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            ch.connect(InetSocketAddress(host, port))
            val sb = StringBuilder()
            sb.append("$method $path HTTP/1.1\r\nHost: $host:$port\r\nConnection: close\r\n")
            for ((k, v) in headers) sb.append("$k: $v\r\n")
            val bodyBytes = body?.toByteArray(StandardCharsets.UTF_8)
            if (bodyBytes != null) {
                sb.append("Content-Type: application/json; charset=utf-8\r\n")
                sb.append("Content-Length: ${bodyBytes.size}\r\n")
            }
            sb.append("\r\n")
            writeAll(ch, sb.toString().toByteArray(StandardCharsets.US_ASCII))
            if (bodyBytes != null) writeAll(ch, bodyBytes)

            val headBytes = readUntil(ch, "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            val head = String(headBytes, StandardCharsets.ISO_8859_1)
            val lines = head.split("\r\n")
            val code = lines[0].split(" ").getOrNull(1)?.toIntOrNull()
                ?: throw IOException("bad status line: ${lines[0]}")
            val hmap = HashMap<String, String>()
            for (i in 1 until lines.size) {
                val idx = lines[i].indexOf(':')
                if (idx > 0) hmap[lines[i].substring(0, idx).trim().lowercase()] = lines[i].substring(idx + 1).trim()
            }
            val bodyStr = when {
                hmap["transfer-encoding"]?.contains("chunked") == true ->
                    String(readChunked(ch), StandardCharsets.UTF_8)
                hmap["content-length"] != null ->
                    String(readN(ch, hmap.getValue("content-length").toInt()), StandardCharsets.UTF_8)
                else -> String(readToEof(ch), StandardCharsets.UTF_8)
            }
            AppLog.log("HTTP", "<- $method $url code=$code body=${bodyStr.length}B")
            return Response(code, hmap, bodyStr)
        } catch (e: Exception) {
            AppLog.error("HTTP", "!! $method $url", e)
            throw if (killer.isDone) IOException("timeout after ${timeoutMs}ms on $url") else e
        } finally {
            killer.cancel(false)
            runCatching { ch.close() }
        }
    }

    internal fun writeAll(ch: SocketChannel, bytes: ByteArray) {
        val buf = ByteBuffer.wrap(bytes)
        while (buf.hasRemaining()) ch.write(buf)
    }

    internal fun readN(ch: SocketChannel, n: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteBuffer.allocate(16384)
        var remaining = n
        while (remaining > 0) {
            buf.clear()
            buf.limit(minOf(buf.capacity(), remaining))
            val r = ch.read(buf)
            if (r < 0) throw IOException("unexpected EOF")
            out.write(buf.array(), 0, r)
            remaining -= r
        }
        return out.toByteArray()
    }

    private fun readToEof(ch: SocketChannel): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteBuffer.allocate(16384)
        while (true) {
            buf.clear()
            val r = ch.read(buf)
            if (r < 0) break
            out.write(buf.array(), 0, r)
        }
        return out.toByteArray()
    }

    /** 读到 delim 为止（返回内容不含 delim） */
    internal fun readUntil(ch: SocketChannel, delim: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val one = ByteBuffer.allocate(1)
        while (true) {
            one.clear()
            val r = ch.read(one)
            if (r < 0) throw IOException("EOF before delimiter")
            if (r == 0) continue
            out.write(one.array(), 0, 1)
            val b = out.toByteArray()
            if (b.size >= delim.size) {
                var match = true
                for (i in delim.indices) {
                    if (b[b.size - delim.size + i] != delim[i]) { match = false; break }
                }
                if (match) return b.copyOfRange(0, b.size - delim.size)
            }
        }
    }

    private fun readChunked(ch: SocketChannel): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = String(readUntil(ch, "\r\n".toByteArray()), StandardCharsets.US_ASCII).trim()
            val size = sizeLine.substringBefore(';').toInt(16)
            if (size == 0) {
                readUntil(ch, "\r\n".toByteArray()) // trailer 结束行（忽略 trailer 头）
                break
            }
            out.write(readN(ch, size))
            readUntil(ch, "\r\n".toByteArray()) // 块尾 CRLF
        }
        return out.toByteArray()
    }
}
