package app.androidrik.rikkahub.ui.components.ui.permission

import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.androidrik.rikkahub.R
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Network

@Composable
fun rememberLocalNetworkPermissionState(): PermissionState? =
    if (Build.VERSION.SDK_INT >= 37) rememberPermissionState(PermissionLocalNetwork) else null

/** 由设置页按授权状态决定是否添加列表项，避免隐藏后留下空白间距。 */
@Composable
fun LocalNetworkPermissionCard(
    permissionState: PermissionState,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = { permissionState.requestPermissions() },
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        ListItem(
            leadingContent = { Icon(Lucide.Network, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.permission_local_network)) },
            supportingContent = {
                Text(stringResource(R.string.permission_local_network_desc))
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        )
    }
}
