package app.androidrik.rikkahub.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.model.ChatLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ChatLayoutContext(val isUser: Boolean, val layout: ChatLayout, val exporting: Boolean)
enum class ChatLayoutSection { ALL, HEADER, BODY, FOOTER }

/** Host-owned content: a layout arranges slots, without rebuilding message or interaction logic. */
data class ChatLayoutSlots(
    val header: @Composable () -> Unit,
    val body: @Composable () -> Unit,
    val footer: @Composable () -> Unit,
)

interface ChatLayoutRenderer {
    val id: String
    val name: String
    @Composable fun Render(context: ChatLayoutContext, slots: ChatLayoutSlots)
}

data class ChatLayoutEntry(val id: String, val name: String, val renderer: ChatLayoutRenderer)

class ChatLayoutRegistry {
    private val contributions = linkedMapOf<String, List<ChatLayoutEntry>>()
    private val state = MutableStateFlow(builtInChatLayouts)
    val layouts = state.asStateFlow()

    @Synchronized
    fun register(owner: String, renderers: List<ChatLayoutRenderer>): AutoCloseable {
        require(owner.isNotBlank() && ':' !in owner)
        require(renderers.all { it.id.isNotBlank() && ':' !in it.id && it.name.isNotBlank() })
        require(renderers.map { it.id }.distinct().size == renderers.size)
        val owned = renderers.map { ChatLayoutEntry("$owner:${it.id}", it.name, it) }
        contributions[owner] = owned
        publish()
        return AutoCloseable {
            synchronized(this) {
                if (contributions[owner] === owned) {
                    contributions.remove(owner)
                    publish()
                }
            }
        }
    }

    private fun publish() { state.value = builtInChatLayouts + contributions.values.flatten() }
    companion object { val shared = ChatLayoutRegistry() }
}

fun resolveChatLayout(id: String, entries: List<ChatLayoutEntry>): ChatLayoutRenderer =
    (entries.firstOrNull { it.id == id } ?: builtInChatLayouts.first()).renderer

@Composable
fun ChatMessageLayoutHost(
    layout: ChatLayout,
    isUser: Boolean,
    modifier: Modifier = Modifier,
    exporting: Boolean = false,
    leading: @Composable () -> Unit = {},
    header: @Composable () -> Unit = {},
    body: @Composable () -> Unit,
    footer: @Composable () -> Unit = {},
    section: ChatLayoutSection = ChatLayoutSection.ALL,
) {
    val alignment = if (isUser && layout.userAlignEnd) Alignment.End else Alignment.Start
    if (section != ChatLayoutSection.ALL) {
        // Only the standard static projection requests sections. Other renderers keep their slots.
        Column(modifier.fillMaxWidth(), horizontalAlignment = alignment,
            verticalArrangement = Arrangement.spacedBy(layout.messageGapDp.dp)) {
            when (section) {
                ChatLayoutSection.HEADER -> { leading(); if (layout.showHeader) header() }
                ChatLayoutSection.BODY -> Column(Modifier.fillMaxWidth(layout.contentWidthFraction),
                    horizontalAlignment = alignment, verticalArrangement = Arrangement.spacedBy(layout.messageGapDp.dp)) { body() }
                ChatLayoutSection.FOOTER -> Column(Modifier.fillMaxWidth(layout.contentWidthFraction),
                    horizontalAlignment = alignment, verticalArrangement = Arrangement.spacedBy(layout.messageGapDp.dp)) { footer() }
                ChatLayoutSection.ALL -> Unit
            }
        }
        return
    }
    val entries by ChatLayoutRegistry.shared.layouts.collectAsState()
    val renderer = resolveChatLayout(layout.rendererId, entries)
    val slots = ChatLayoutSlots(
        header = { if (layout.showHeader) header() },
        body = {
            Column(Modifier.fillMaxWidth(layout.contentWidthFraction), horizontalAlignment = alignment,
                verticalArrangement = Arrangement.spacedBy(layout.messageGapDp.dp)) { body() }
        },
        footer = {
            Column(Modifier.fillMaxWidth(layout.contentWidthFraction), horizontalAlignment = alignment,
                verticalArrangement = Arrangement.spacedBy(layout.messageGapDp.dp)) { footer() }
        },
    )
    Column(modifier.fillMaxWidth(), horizontalAlignment = alignment,
        verticalArrangement = Arrangement.spacedBy(layout.messageGapDp.dp)) {
        leading()
        renderer.Render(ChatLayoutContext(isUser, layout, exporting), slots)
    }
}

private object StandardChatLayout : ChatLayoutRenderer {
    override val id = "standard"
    override val name = "标准"
    @Composable override fun Render(context: ChatLayoutContext, slots: ChatLayoutSlots) {
        Column(Modifier.fillMaxWidth(),
            horizontalAlignment = if (context.isUser && context.layout.userAlignEnd) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(context.layout.messageGapDp.dp)) {
            slots.header(); slots.body(); slots.footer()
        }
    }
}

private object SidebarChatLayout : ChatLayoutRenderer {
    override val id = "sidebar"
    override val name = "侧栏"
    @Composable override fun Render(context: ChatLayoutContext, slots: ChatLayoutSlots) {
        if (!context.layout.showHeader) {
            StandardChatLayout.Render(context, slots)
            return
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(112.dp)) { slots.header() }
            Column(Modifier.weight(1f),
                horizontalAlignment = if (context.isUser && context.layout.userAlignEnd) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(context.layout.messageGapDp.dp)) {
                slots.body(); slots.footer()
            }
        }
    }
}

private object CardChatLayout : ChatLayoutRenderer {
    override val id = "card"
    override val name = "卡片"
    @Composable override fun Render(context: ChatLayoutContext, slots: ChatLayoutSlots) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(12.dp)) { StandardChatLayout.Render(context, slots) }
        }
    }
}

val builtInChatLayouts: List<ChatLayoutEntry> = listOf(StandardChatLayout, SidebarChatLayout, CardChatLayout)
    .map { ChatLayoutEntry("builtin.${it.id}", it.name, it) }
