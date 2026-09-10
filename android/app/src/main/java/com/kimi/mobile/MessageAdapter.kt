package com.kimi.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMsg(
    val id: String,
    val role: String, // "user" / "assistant" / "tool"
    var text: String,
    var streaming: Boolean = false,
    var isError: Boolean = false,
    var queued: Boolean = false, // 服务端排队中（busy 时 queued 的 prompt 暂不进历史）
    var active: Boolean = false, // 执行中：data.active 的 prompt（v0.37.2，正在执行而非排队）
    var undelivered: Boolean = false, // 未送达：既不在历史也不在服务端队列且超 60s，视为已被服务端丢弃
    var timeMillis: Long = 0L,
    var imageFileId: String = "" // 图片块 file_id（""=无图）；二进制经 Api.fetchFile 拉回显示
)

class MessageAdapter : RecyclerView.Adapter<MessageAdapter.VH>() {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_ASSISTANT = 1
        private const val TYPE_TOOL = 2
        private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())
    }

    val items = ArrayList<ChatMsg>()

    /** 图片块加载所需的服务端地址/令牌（ChatActivity 注入；为空则图片不加载，只显示占位） */
    var serverUrl: String = ""
    var authToken: String = ""

    /** file_id -> Bitmap 简易内存缓存（主线程读写，解码在子线程） */
    private val imageCache = HashMap<String, Bitmap>()

    /** user 气泡长按菜单「从这里分叉」回调（由 ChatActivity 注入，fork+undo 流程） */
    var onForkFrom: ((ChatMsg) -> Unit)? = null

    fun setAll(list: List<ChatMsg>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun add(msg: ChatMsg): Int {
        items.add(msg)
        notifyItemInserted(items.size - 1)
        return items.size - 1
    }

    fun updateText(index: Int, text: String) {
        if (index in items.indices) {
            items[index].text = text
            notifyItemChanged(index)
        }
    }

    /** 按 id 移除条目（如发送失败撤回乐观回显），返回被移除的下标，未找到返回 -1 */
    fun removeById(id: String): Int {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items.removeAt(idx)
            notifyItemRemoved(idx)
        }
        return idx
    }

    /** POST 返回 queued 时给本地回显打“排队中”标记 */
    fun markQueued(id: String) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items[idx].queued = true
            notifyItemChanged(idx)
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.tvMessage)
        val time: TextView = view.findViewById(R.id.tvTime)
        // tool 布局无图片视图，可能为 null
        val image: ImageView? = view.findViewById(R.id.ivImage)
    }

    override fun getItemViewType(position: Int): Int =
        when (items[position].role) {
            "user" -> TYPE_USER
            "tool" -> TYPE_TOOL
            else -> TYPE_ASSISTANT
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = when (viewType) {
            TYPE_USER -> R.layout.item_message_user
            TYPE_TOOL -> R.layout.item_message_tool
            else -> R.layout.item_message_assistant
        }
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val ctx = holder.itemView.context
        val display = if (m.streaming && m.text.isEmpty()) "…" else m.text
        holder.text.text = decorateCode(ctx, display)
        // 纯图片消息（text 为空）隐藏文本气泡，避免空气泡占位
        holder.text.visibility = if (display.isEmpty() && m.imageFileId.isNotEmpty()) View.GONE else View.VISIBLE
        bindImage(holder, m)
        val color = when {
            m.isError || m.undelivered -> ctx.getColor(R.color.error)
            m.role == "user" -> ctx.getColor(R.color.on_primary)
            m.role == "tool" -> ctx.getColor(R.color.text_secondary)
            else -> ctx.getColor(R.color.text_primary)
        }
        holder.text.setTextColor(color)
        if (m.undelivered && m.role == "user") {
            holder.time.text = if (m.timeMillis > 0L)
                "未送达（服务端已丢弃）· " + TIME_FORMAT.format(Date(m.timeMillis))
            else
                "未送达（服务端已丢弃）"
            holder.time.visibility = View.VISIBLE
        } else if (m.active && m.role == "user") {
            // 执行中的小字标记（复用时间戳位置，样式随现有）
            holder.time.text = if (m.timeMillis > 0L)
                "执行中 · " + TIME_FORMAT.format(Date(m.timeMillis))
            else
                "执行中"
            holder.time.visibility = View.VISIBLE
        } else if (m.queued && m.role == "user") {
            // 排队中的小字标记（复用时间戳位置，样式随现有）
            holder.time.text = if (m.timeMillis > 0L)
                "排队中 · " + TIME_FORMAT.format(Date(m.timeMillis))
            else
                "排队中"
            holder.time.visibility = View.VISIBLE
        } else if (m.timeMillis > 0L) {
            holder.time.text = TIME_FORMAT.format(Date(m.timeMillis))
            holder.time.visibility = View.VISIBLE
        } else {
            holder.time.visibility = View.GONE
        }
        val longClick = View.OnLongClickListener { anchor ->
            if (m.role == "user" && onForkFrom != null) {
                // user 气泡：弹菜单（复制 / 从这里分叉）
                PopupMenu(ctx, anchor).apply {
                    menu.add(0, 1, 0, "复制")
                    menu.add(0, 2, 1, "从这里分叉")
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> { copyText(ctx, m.text); true }
                            2 -> { onForkFrom?.invoke(m); true }
                            else -> false
                        }
                    }
                    show()
                }
            } else {
                copyText(ctx, m.text)
            }
            true
        }
        holder.text.setOnLongClickListener(longClick)
        // 纯图片消息文本气泡已隐藏，长按入口挂到图片上
        holder.image?.setOnLongClickListener(longClick)
    }

    /** 图片块绑定：缓存命中直接显示；否则子线程 GET /api/v1/files/{id} 拉回解码，tag 防回收错位 */
    private fun bindImage(holder: VH, m: ChatMsg) {
        val iv = holder.image ?: return
        if (m.imageFileId.isEmpty()) {
            iv.tag = null
            iv.setImageDrawable(null)
            iv.visibility = View.GONE
            return
        }
        iv.visibility = View.VISIBLE
        iv.tag = m.imageFileId
        val cached = imageCache[m.imageFileId]
        if (cached != null) {
            iv.setImageBitmap(cached)
            return
        }
        iv.setImageDrawable(null)
        val fid = m.imageFileId
        val activity = holder.itemView.context as? android.app.Activity
        Thread {
            val bmp = loadImage(fid)
            if (bmp != null) {
                val post: () -> Unit = {
                    imageCache[fid] = bmp
                    if (iv.tag == fid) iv.setImageBitmap(bmp)
                }
                if (activity != null) activity.runOnUiThread(post) else iv.post(post)
            }
        }.start()
    }

    /** 子线程拉取并解码图片（降采样到长边 ~1280px 以内），失败返回 null（气泡仅留空图位） */
    private fun loadImage(fileId: String): Bitmap? {
        if (serverUrl.isEmpty() || authToken.isEmpty()) return null
        return try {
            val bytes = Api.fetchFile(serverUrl, authToken, fileId)
            if (bytes.isEmpty()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxDim / (sample * 2) > 1280) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            null
        }
    }

    private fun copyText(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("message", text))
        Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * 渲染 Markdown 代码段：``` 代码块 ``` 与 `行内代码`，
     * 等宽字体 + 深色底 + 稍小字号，剥离围栏符号。
     */
    private fun decorateCode(ctx: Context, raw: String): CharSequence {
        if (!raw.contains('`')) return raw
        val codeBg = ctx.getColor(R.color.code_bg)
        val codeFg = ctx.getColor(R.color.code_text)
        val out = SpannableStringBuilder()

        fun appendCode(seg: String) {
            if (seg.isEmpty()) return
            val start = out.length
            out.append(seg)
            out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(BackgroundColorSpan(codeBg), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(codeFg), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(0.88f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        fun appendPlain(seg: String) {
            // 处理行内 `code`
            var j = 0
            while (j < seg.length) {
                val t1 = seg.indexOf('`', j)
                if (t1 < 0) {
                    out.append(seg.substring(j))
                    return
                }
                val t2 = seg.indexOf('`', t1 + 1)
                if (t2 < 0) {
                    out.append(seg.substring(j))
                    return
                }
                out.append(seg.substring(j, t1))
                appendCode(seg.substring(t1 + 1, t2))
                j = t2 + 1
            }
        }

        var i = 0
        while (i < raw.length) {
            val fence = raw.indexOf("```", i)
            if (fence < 0) {
                appendPlain(raw.substring(i))
                break
            }
            appendPlain(raw.substring(i, fence))
            val end = raw.indexOf("```", fence + 3)
            if (end < 0) {
                appendPlain(raw.substring(fence))
                break
            }
            var code = raw.substring(fence + 3, end)
            // 去掉围栏后的语言标记行（如 kotlin、bash）
            val nl = code.indexOf('\n')
            if (nl > 0 && !code.substring(0, nl).contains(' ')) {
                code = code.substring(nl + 1)
            }
            code = code.trim('\n')
            if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
            appendCode(code)
            out.append('\n')
            i = end + 3
        }
        return out
    }
}
