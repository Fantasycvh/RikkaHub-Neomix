package app.androidrik.rikkahub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.components.haze.LocalBubbleBackdrop
import app.androidrik.rikkahub.ui.components.haze.hazeSourceCompat
import app.androidrik.rikkahub.ui.components.haze.rememberRecoverableHazeState
import app.androidrik.rikkahub.ui.components.message.ChatBubbleSurface
import app.androidrik.rikkahub.ui.theme.ChatMessageLayoutHost
import app.androidrik.rikkahub.ui.theme.builtInChatLayouts
import me.rerere.bettertext.text.BetterText

/** Debug-only, fixed-content visual fixture; no conversations or credentials are displayed. */
@Composable
fun ChatAppearancePreview() {
    val backdrop = rememberRecoverableHazeState()
    MaterialTheme {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().hazeSourceCompat(backdrop).background(Brush.horizontalGradient(listOf(
                Color(0xFFFFE0B2), Color(0xFFE1BEE7), Color(0xFFB3E5FC), Color(0xFFC8E6C9),
            ))).drawBehind {
                var y = 0f
                while (y < size.height) {
                    drawLine(Color.Black.copy(alpha = 0.12f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    y += 20.dp.toPx()
                }
            })
            CompositionLocalProvider(LocalBubbleBackdrop provides backdrop) {
                Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Chat appearance visual fixture", style = MaterialTheme.typography.titleMedium)
                    BetterText(buildAnnotatedString {
                        append("Gradient text preview\n")
                        withStyle(SpanStyle(color = Color(0xFF008800))) { append("Solid span remains green") }
                    }, style = TextStyle(fontSize = 22.sp, brush = Brush.horizontalGradient(listOf(Color.Red, Color.Blue))))
                    val materials = listOf<BubbleMaterial?>(null) + BubbleMaterial.entries
                    materials.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { material ->
                                Column(Modifier.weight(1f)) {
                                    Text(material?.name ?: "NONE", style = MaterialTheme.typography.labelSmall)
                                    ChatBubbleSurface(ChatBubble(visible = material != null,
                                        material = material ?: BubbleMaterial.SOLID,
                                        gradient = AppearanceGradient(listOf("#FF0000", "#0000FF")),
                                        noise = BubbleNoise(42, 0.35f)),
                                        defaultColor = Color(0xFFD1C4E9),
                                        modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                        Text("Bubble text", Modifier.padding(4.dp))
                                    }
                                }
                            }
                        }
                    }
                    builtInChatLayouts.forEach { entry ->
                        ChatMessageLayoutHost(layout = ChatLayout(rendererId = entry.id), isUser = false,
                            header = { Text(entry.name, style = MaterialTheme.typography.labelSmall) },
                            body = { Text("First body section"); Text("Second body section") },
                            footer = { Text("Actions / metadata", style = MaterialTheme.typography.labelSmall) })
                    }
                }
            }
        }
    }
}
