package io.github.ozkanceng.yansit.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.ozkanceng.yansit.R

/** Uygulamanın istediği çalışma zamanı izinleri ve gerekçeleri. */
object AppPermissions {
    /** Android 17 (API 37) yerel ağ izni; derleme sabitine bağımlı olmamak için adıyla. */
    const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val API_LOCAL_NETWORK = 37

    class Item(val permission: String, @StringRes val title: Int, @StringRes val reason: Int, val required: Boolean)

    fun all(): List<Item> = buildList {
        add(Item(Manifest.permission.CAMERA, R.string.perm_camera, R.string.perm_camera_reason, required = true))
        if (Build.VERSION.SDK_INT >= API_LOCAL_NETWORK) {
            add(Item(ACCESS_LOCAL_NETWORK, R.string.perm_local_network, R.string.perm_local_network_reason, required = true))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Item(Manifest.permission.POST_NOTIFICATIONS, R.string.perm_notifications, R.string.perm_notifications_reason, required = false))
        }
    }

    fun missing(context: Context): List<Item> =
        all().filter { ContextCompat.checkSelfPermission(context, it.permission) != PackageManager.PERMISSION_GRANTED }

    fun requiredGranted(context: Context): Boolean = missing(context).none { it.required }

    /** Hatırlanan tahtaya bağlanmak için yalnız yerel ağ izni gerekir. */
    fun networkGranted(context: Context): Boolean = missing(context).none { it.permission == ACCESS_LOCAL_NETWORK }
}

/** İlk bağlanmadan önce izinlerin neden gerektiğini açıklar. */
@Composable
fun PermissionScreen(
    missing: List<AppPermissions.Item>,
    deniedBefore: Boolean,
    onRequest: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            val width = Modifier.widthIn(max = 560.dp).fillMaxWidth()
            Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.headlineSmall, modifier = width)
            for (item in missing) {
                Column(width, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(item.title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(item.reason), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = onRequest, modifier = width.heightIn(min = 64.dp)) {
                Text(stringResource(R.string.perm_continue), style = MaterialTheme.typography.titleMedium)
            }
            if (deniedBefore) {
                Text(stringResource(R.string.perm_denied_hint), style = MaterialTheme.typography.bodyMedium, modifier = width)
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.perm_open_settings)) }
            }
        }
    }
}
