package dev.winniesi.zbox

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.winniesi.zbox.core.AppLinks
import dev.winniesi.zbox.core.RemoteLink
import dev.winniesi.zbox.di.AppContainer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** App 启动、深链路由、远程页导航的端到端 UI 测试（模拟器运行）。 */
@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val container: AppContainer
        get() = composeRule.activity.application.let { it as ZBoxApplication }.container

    private val fakeLink = RemoteLink(
        scheme = "https",
        host = "zcode.z.ai",
        path = "/remote/v4",
        sid = "sid-e2e",
        hash = "hash-e2e",
        t = System.currentTimeMillis(),
        mid = "e2emid0001",
        name = "E2E设备",
        appVersion = "3.11.2",
    )

    @Before
    fun resetDevices() {
        composeRule.activityRule.scenario.onActivity { }
        runBlocking {
            container.repository.refresh()
            container.repository.devices.value?.forEach { container.repository.remove(it.mid) }
        }
    }

    @After
    fun cleanup() {
        runBlocking { container.repository.remove(fakeLink.mid) }
    }

    @Test
    fun emptyStateIsShownWhenNoDevices() {
        composeRule.onNodeWithText("还没有设备").assertIsDisplayed()
        composeRule.onNodeWithText("ZBox 设备").assertIsDisplayed()
    }

    @Test
    fun addDeepLinkShowsParsedPreview() {
        val url = fakeLink.toUrlString()
        composeRule.activityRule.scenario.onActivity { activity ->
            (activity as MainActivity).consumeDeepLink(AppLinks.buildAddUrl(url))
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("链接解析结果").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("链接解析结果").assertIsDisplayed()
        composeRule.onNodeWithText("zcode.z.ai").assertIsDisplayed()
        composeRule.onNodeWithText("保存设备").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun openDeepLinkNavigatesToRemoteScreen() {
        runBlocking { container.repository.addOrUpdateFromLink(fakeLink) }
        composeRule.activityRule.scenario.onActivity { activity ->
            (activity as MainActivity).consumeDeepLink(AppLinks.buildOpenUrl(fakeLink.mid))
        }
        // 远程页是独立 Activity（原生 View 层级），验证其进入 Resumed 状态
        composeRule.waitUntil(10_000) { remoteResumed() }
        assertTrue(remoteResumed())
    }

    private fun remoteResumed(): Boolean {
        val found = java.util.concurrent.atomic.AtomicBoolean(false)
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            found.set(
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .any { it is RemoteActivity },
            )
        }
        return found.get()
    }
}
