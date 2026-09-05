@file:OptIn(ExperimentalFoundationApi::class)

package com.kimi.desktop

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Segment(val isCode: Boolean, val text: String)

/** 按 ``` 围栏拆分代码块 */
private fun parseSegments(text: String): List<Segment> {
    val out = ArrayList<Segment>()
    val sb = StringBuilder()
    var inCode = false
    fun flush() {
        if (sb.isNotEmpty()) {
            out.add(Segment(inCode, sb.toString().trim('\n')))
            sb.clear()
        }
    }
    for (line in text.lines()) {
        if (line.trimStart().startsWith("```")) {
            flush()
            inCode = !inCode
        } else {
            sb.append(line).append('\n')
        }
    }
    flush()
    return out
}

@Composable
fun MessageBubble(
    role: String,
    text: String,
    streaming: Boolean = false,
    queued: Boolean = false,
    executing: Boolean = false,
    undelivered: Boolean = false,
    onForkFromHere: (() -> Unit)? = null
) {
    val isUser = role == "user"
    val isThinking = role == "thinking"
    val isError = role == "error"
    val clipboard = LocalClipboardManager.current

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        ContextMenuArea(
            items = {
                buildList {
                    add(ContextMenuItem("复制") { clipboard.setText(AnnotatedString(text)) })
                    // 「从这里分叉」：仅 user 气泡（fork 全量克隆 + 新会话 undo 掉该消息之后的内容）
                    if (isUser && onForkFromHere != null) {
                        add(ContextMenuItem("从这里分叉") { onForkFromHere() })
                    }
                }
            }
        ) {
            val shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            )
            Box(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .clip(shape)
                    .then(
                        if (isUser) Modifier.background(Brush.horizontalGradient(listOf(BubbleUserStart, BubbleUserEnd)))
                        else Modifier.background(
                            when {
                                isError -> MaterialTheme.colorScheme.errorContainer
                                isThinking -> MaterialTheme.colorScheme.surfaceVariant
                                else -> MaterialTheme.colorScheme.surface
                            }
                        )
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    if (isUser && undelivered) {
                        // 未送达警示：服务端幻影 busy 丢弃了排队 prompt（上游 MoonshotAI/kimi-code#3127）
                        Text(
                            "⚠ 未送达（服务端已丢弃）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(4.dp))
                    } else if (isUser && executing) {
                        // 服务端执行中标记：prompt 正在被执行（data.active，v0.37.2 起不在 queued[] 里）
                        Text(
                            "执行中",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                        Spacer(Modifier.height(4.dp))
                    } else if (isUser && queued) {
                        // 服务端排队中标记：会话 busy 时消息已入队但未开始执行
                        Text(
                            "排队中",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    if (isThinking) {
                        Text(
                            "思考过程",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    // user 气泡：给文本选择菜单也挂上「从这里分叉」（SelectionContainer 的内建菜单只有复制，
                    // 通过 LocalTextContextMenu 提供自定义菜单才能看到分叉项）
                    if (isUser && onForkFromHere != null) {
                        CompositionLocalProvider(LocalTextContextMenu provides forkTextContextMenu(onForkFromHere)) {
                            SelectionContainer {
                                BubbleText(text = text, streaming = streaming, isUser = isUser, isError = isError, isThinking = isThinking)
                            }
                        }
                    } else {
                        SelectionContainer {
                            BubbleText(text = text, streaming = streaming, isUser = isUser, isError = isError, isThinking = isThinking)
                        }
                    }
                }
            }
        }
    }
}

/** 工具活动条目（流式期间的临时行，turn 结束随 frames 清空而消失） */
@Composable
fun ToolActivityRow(name: String, summary: String, done: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                buildString {
                    append("🔧 ")
                    append(name.ifEmpty { "tool" })
                    if (summary.isNotEmpty()) append(": ").append(summary)
                    if (done) append("  ✓")
                },
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 气泡正文（从 MessageBubble 抽出，便于在两种 SelectionContainer 路径间复用） */
@Composable
private fun BubbleText(text: String, streaming: Boolean, isUser: Boolean, isError: Boolean, isThinking: Boolean) {
    Column {
        for (seg in parseSegments(text)) {
            if (seg.isCode) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CodeBg)
                        .padding(10.dp)
                ) {
                    Text(seg.text, color = CodeText, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            } else {
                Text(
                    seg.text,
                    color = when {
                        isUser -> Color.White
                        isError -> MaterialTheme.colorScheme.onErrorContainer
                        isThinking -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    fontSize = 14.sp
                )
            }
        }
        if (streaming) {
            Text("▍", color = if (isUser) Color.White else MaterialTheme.colorScheme.primary, fontSize = 14.sp)
        }
    }
}

/** user 气泡的文本选择上下文菜单：复制 + 从这里分叉 */
private fun forkTextContextMenu(onFork: () -> Unit): TextContextMenu = object : TextContextMenu {
    @Composable
    override fun Area(
        textManager: TextContextMenu.TextManager,
        state: ContextMenuState,
        content: @Composable () -> Unit
    ) {
        ContextMenuArea(
            items = {
                buildList {
                    textManager.copy?.let { copy ->
                        add(ContextMenuItem("复制") { copy(); state.status = ContextMenuState.Status.Closed })
                    }
                    add(ContextMenuItem("从这里分叉") { onFork(); state.status = ContextMenuState.Status.Closed })
                }
            },
            state = state,
            content = content
        )
    }
}
