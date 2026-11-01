package app.androidrik.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.androidrik.ai.provider.ProviderManager
import app.androidrik.ai.provider.ProviderQuota
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.rikkahub.utils.SimpleCache
import app.androidrik.rikkahub.utils.toDp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MoneyBag02
import org.koin.compose.koinInject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private val quotaCache = SimpleCache.builder<String, String>()
    .expireAfterWrite(2, TimeUnit.MINUTES)
    .build()

/**
 * 提供商额度展示（对齐上游 #2186 的 `ProviderQuota`）。
 *
 * API Key 类提供商仅在开启自定义额度查询（[ProviderSetting.ApiKey.quotaQuery]）时展示；
 * 账号类提供商（P4 落地）有凭据即展示订阅用量。查询结果按「提供商 id + 查询配置」缓存 2 分钟，
 * 改配置后哈希变化自然失效。
 */
@Composable
fun ProviderQuotaText(
    providerSetting: ProviderSetting,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    val apiKeySetting = providerSetting as? ProviderSetting.ApiKey ?: return
    if (!apiKeySetting.quotaQuery.enabled) return

    val providerManager = koinInject<ProviderManager>()

    val value = produceState(
        initialValue = "~",
        key1 = providerSetting.id,
        key2 = providerSetting.quotaQuery,
    ) {
        val cacheKey = "${providerSetting.id},${providerSetting.quotaQuery.hashCode()}"
        val cached = quotaCache.getIfPresent(cacheKey)
        if (cached != null) {
            value = cached
        } else {
            runCatching {
                providerManager.getProviderByType(providerSetting).getQuota(providerSetting)
            }.onSuccess { quota ->
                val text = quota.toDisplayText()
                if (text != null) {
                    quotaCache.put(cacheKey, text)
                    value = text
                }
            }.onFailure {
                // 错误不缓存，下次进入页面会重试
                value = "Error: ${it.message}"
            }
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = HugeIcons.MoneyBag02,
            contentDescription = null,
            modifier = Modifier.size(style.fontSize.toDp()),
            tint = color.takeOrElse { LocalContentColor.current },
        )
        Text(
            text = value.value,
            style = style,
            maxLines = 1,
            color = color,
        )
    }
}

/** 额度 → 一行展示文本；没有可展示的额度返回 null（不渲染图标行） */
internal fun ProviderQuota?.toDisplayText(): String? = when (this) {
    null -> null
    is ProviderQuota.Balance -> value
    is ProviderQuota.UsageLimits -> buildString {
        append(windows.joinToString(" / ") { window ->
            "${window.usedPercent.roundToInt()}%"
        })
        if (limitReached) append(" (limit)")
    }.ifEmpty { null }
}
