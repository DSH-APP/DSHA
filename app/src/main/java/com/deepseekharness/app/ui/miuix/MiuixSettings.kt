package com.deepseekharness.app.ui.miuix

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseekharness.app.R
import com.deepseekharness.app.util.UiText
import java.util.function.Consumer
import java.util.function.IntConsumer
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 设置页面只呈现选项；导航、持久化和服务更新由原 Java 页面处理。 */
class MiuixSettings(
    context: Context,
    private val version: String,
    val language: String,
    eco: Boolean,
    private val open: IntConsumer,
    private val changeEco: Consumer<Boolean>,
) : MiuixView(context) {
    init { id = R.id.settings_page }

    private var ecoEnabled by mutableStateOf(eco)

    @Composable override fun Content() {
        DshaTheme {
            Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(stringResource(R.string.ui2_settings), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.ui2_settings_hint),
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                    color = MiuixTheme.colorScheme.onSurfaceSecondary)
                SmallTitle(stringResource(R.string.ui2_workspace))
                Card(Modifier.fillMaxWidth()) {
                    Entry(stringResource(R.string.ui2_install_environment), UiText.text("安装与修复运行环境"),
                        R.drawable.ic_ui2_box) { open.accept(R.id.settings_install) }
                    Entry(stringResource(R.string.ui2_runtime_config), UiText.text("接口、显示与运行"),
                        R.drawable.ic_settings) { open.accept(R.id.settings_config) }
                    Entry(UiText.text("数据与备份"), UiText.text("备份恢复 · 文件共享"),
                        R.drawable.ic_ui2_folder) { open.accept(R.id.settings_data) }
                    Entry(UiText.text("设备能力授权"), UiText.text("Root · Shizuku · ADB · 权限"),
                        R.drawable.ic_ui_shield) { open.accept(R.id.settings_device) }
                }
                SmallTitle(stringResource(R.string.ui2_experience))
                Card(Modifier.fillMaxWidth()) {
                    SuperSwitch(
                        checked = ecoEnabled,
                        onCheckedChange = { value -> changeEco.accept(value); ecoEnabled = value },
                        title = UiText.text("省电模式"),
                        summary = UiText.text(if (ecoEnabled) "熄屏空闲 1 分钟后减少保活；有任务时继续运行。"
                            else "持续保持运行，适合长时间任务。"),
                    )
                    Entry(stringResource(R.string.ui_language_title), language,
                        R.drawable.ic_ui_language) { open.accept(R.id.settings_appearance) }
                    Entry(stringResource(R.string.ui2_overlay), stringResource(R.string.ui2_overlay_sub),
                        R.drawable.ic_ui_chat) { open.accept(R.id.settings_overlay) }
                }
                SmallTitle(stringResource(R.string.ui2_support))
                Card(Modifier.fillMaxWidth()) {
                    Entry(stringResource(R.string.ui2_updates), UiText.text("稳定版与预览版更新"),
                        R.drawable.ic_recovery_refresh) { open.accept(R.id.settings_update) }
                    Entry(stringResource(R.string.ui2_diagnostics), stringResource(R.string.ui2_diagnostics_sub),
                        R.drawable.ic_ui_shield) { open.accept(R.id.settings_selftest) }
                    Entry(stringResource(R.string.ui2_about), stringResource(R.string.ui2_about_sub),
                        R.drawable.ic_about) { open.accept(R.id.settings_about) }
                }
                Text(UiText.format("DSHA v%s · MIT 许可", version),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    fontSize = 12.sp, textAlign = TextAlign.Center,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
        }
    }
}
