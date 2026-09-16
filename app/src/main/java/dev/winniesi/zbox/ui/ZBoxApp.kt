package dev.winniesi.zbox.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.winniesi.zbox.core.AppLink
import dev.winniesi.zbox.core.RemoteLinkParser
import dev.winniesi.zbox.di.AppContainer
import dev.winniesi.zbox.ui.add.AddDeviceScreen
import dev.winniesi.zbox.ui.devices.DevicesScreen
import dev.winniesi.zbox.ui.remote.RemoteScreen
import dev.winniesi.zbox.ui.scan.ScanScreen
import kotlinx.coroutines.launch

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}

object NavRoutes {
    const val DEVICES = "devices"
    const val ADD = "add?prefill={prefill}"
    const val SCAN = "scan?forDevice={forDevice}"
    const val REMOTE = "remote/{mid}"

    fun add(prefill: String?): String = "add?prefill=" + (prefill?.let(Uri::encode) ?: "")
    fun scan(forDevice: String?): String = "scan?forDevice=${Uri.encode(forDevice ?: "")}"
    fun remote(mid: String): String = "remote/$mid"
}

@Composable
fun ZBoxApp(container: AppContainer) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        container.pendingAppLink.collect { link ->
            when (link) {
                is AppLink.AddDevice -> nav.navigate(NavRoutes.add(link.url))
                is AppLink.OpenDevice -> nav.navigate(NavRoutes.remote(link.mid))
                null -> Unit
            }
            container.pendingAppLink.value = null
        }
    }

    NavHost(navController = nav, startDestination = NavRoutes.DEVICES) {
        composable(NavRoutes.DEVICES) {
            DevicesScreen(
                onOpenDevice = { nav.navigate(NavRoutes.remote(it)) },
                onScan = { nav.navigate(NavRoutes.scan(null)) },
                onPaste = { nav.navigate(NavRoutes.add(null)) },
                onRescanDevice = { nav.navigate(NavRoutes.scan(it)) },
            )
        }

        composable(
            NavRoutes.ADD,
            arguments = listOf(
                navArgument("prefill") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            AddDeviceScreen(
                prefillUrl = entry.arguments?.getString("prefill"),
                onDone = { nav.popBackStack() },
            )
        }

        composable(
            NavRoutes.SCAN,
            arguments = listOf(
                navArgument("forDevice") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = ""
                },
            ),
        ) { entry ->
            val forDevice = entry.arguments?.getString("forDevice").orEmpty().ifEmpty { null }
            ScanScreen(
                forDeviceMid = forDevice,
                onCancel = { nav.popBackStack() },
                onScannedLink = { raw ->
                    handleScanned(
                        container = container,
                        raw = raw,
                        forDeviceMid = forDevice,
                        navigateToAdd = { nav.navigate(NavRoutes.add(raw)) },
                        onUpdated = { nav.popBackStack() },
                        scope = scope,
                    )
                },
            )
        }

        composable(
            NavRoutes.REMOTE,
            arguments = listOf(navArgument("mid") { type = NavType.StringType }),
        ) { entry ->
            RemoteScreen(
                mid = entry.arguments?.getString("mid").orEmpty(),
                onRescan = { nav.navigate(NavRoutes.scan(it)) },
                onBack = { nav.popBackStack() },
            )
        }
    }
}

/** 扫码结果分流：更新已有设备直接落库返回；新增设备先进确认页。 */
private fun handleScanned(
    container: AppContainer,
    raw: String,
    forDeviceMid: String?,
    navigateToAdd: () -> Unit,
    onUpdated: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val parsed = RemoteLinkParser().parse(raw)
    if (parsed !is RemoteLinkParser.Result.Ok) {
        navigateToAdd()
        return
    }
    if (forDeviceMid != null && parsed.link.mid == forDeviceMid) {
        scope.launch {
            container.repository.addOrUpdateFromLink(parsed.link)
            onUpdated()
        }
    } else {
        navigateToAdd()
    }
}
