package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.phoneuse.PhoneApp

/**
 * 勾选不允许 Phone Use 操作的应用。默认全都不勾，勾上的应用不能被打开、读屏或操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneUseBlockedAppsSheet(
    blocked: Set<String>,
    onToggle: (packageName: String, blocked: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }

    // 一次读出所有带启动图标的应用，包管理器调用不能放在主线程
    val apps by produceState(initialValue = emptyList<PhoneApp>(), context) {
        value = withContext(Dispatchers.IO) {
            val packageManager = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            packageManager.queryIntentActivities(launcher, 0)
                .map { PhoneApp(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
                .filter { it.packageName != context.packageName }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }
    }
    val filtered = remember(apps, query) {
        if (query.isBlank()) {
            apps
        } else {
            apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.assistant_page_local_tools_phone_use_blocked_apps),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.assistant_page_local_tools_phone_use_blocked_apps_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.assistant_page_local_tools_phone_use_blocked_apps_search)) },
            )
            if (filtered.isEmpty()) {
                Text(
                    text = stringResource(R.string.assistant_page_local_tools_phone_use_blocked_apps_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        val checked = app.packageName in blocked
                        ListItem(
                            headlineContent = { Text(app.label) },
                            supportingContent = { Text(app.packageName) },
                            trailingContent = {
                                Switch(
                                    checked = checked,
                                    onCheckedChange = { onToggle(app.packageName, it) },
                                )
                            },
                            modifier = Modifier.clickable { onToggle(app.packageName, !checked) },
                        )
                    }
                }
            }
        }
    }
}
