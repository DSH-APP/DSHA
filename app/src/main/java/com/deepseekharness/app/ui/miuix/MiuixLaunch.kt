package com.deepseekharness.app.ui.miuix

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.deepseekharness.app.R
import com.deepseekharness.app.ui.LogScrollView
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 启动页的可观察显示状态；由 LaunchFragment 更新，不启动或停止任何后台任务。 */
class MiuixLaunch(context: Context) : MiuixView(context) {
    open class Label(private val context: Context, value: String = "") {
        var text by mutableStateOf(value)
        var visibility by mutableIntStateOf(View.VISIBLE)
        fun setText(resource: Int) { text = context.getString(resource) }
    }

    class Action(private val owner: View, resource: Int) : Label(owner.context, owner.context.getString(resource)) {
        var enabled by mutableStateOf(true)
        var contentDescription by mutableStateOf("")
        private var listener: View.OnClickListener? = null
        fun setOnClickListener(value: View.OnClickListener?) { listener = value }
        fun click() { if (enabled && visibility == View.VISIBLE) listener?.onClick(owner) }
    }

    @JvmField val status = Label(context)
    @JvmField val runState = Label(context, context.getString(R.string.ui2_stopped))
    @JvmField val subtitle = Label(context, context.getString(R.string.ui2_launch_hint))
    @JvmField val port = Label(context, "—")
    @JvmField val environment = Label(context, "—")
    @JvmField val busy = Label(context).apply { visibility = View.GONE }
    @JvmField val start = Action(this, R.string.ui2_start)
    @JvmField val restart = Action(this, R.string.ui2_restart)
    @JvmField val stop = Action(this, R.string.ui2_stop)
    @JvmField val safe = Action(this, R.string.ui_m0007)
    @JvmField val recovery = Action(this, R.string.ui_m0011)
    @JvmField val models = Action(this, R.string.ui2_models)
    @JvmField val address = Action(this, R.string.ui2_address)
    @JvmField val downloadLogs = Action(this, R.string.ui_m0046)

    // 日志保留原生可选中文本和“仅在底部时跟随”的滚动容器。
    @JvmField val logScroll = LogScrollView(context, null).apply {
        id = R.id.launch_log_scroll
        setBackgroundResource(R.drawable.bg_ui2_console)
        isFillViewport = true
    }
    @JvmField val logText = TextView(context).apply {
        id = R.id.launch_log
        textSize = 12f
        setTextColor(context.getColor(R.color.ui2_log_text))
        typeface = android.graphics.Typeface.MONOSPACE
        setTextIsSelectable(true)
        val inset = (12 * resources.displayMetrics.density).toInt()
        setPadding(inset, inset, inset, inset)
    }
    @JvmField val lanAddress = TextView(context).apply {
        id = R.id.lan_addr
        textSize = 12f
        setTextColor(context.getColor(R.color.primary))
        setTextIsSelectable(true)
        visibility = View.GONE
    }

    init {
        id = R.id.launch_panel
        logScroll.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(lanAddress)
            addView(logText)
        })
    }

    @Composable override fun Content() {
        DshaTheme {
            Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState()).padding(20.dp)) {
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
                    Text(runState.text, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Text(subtitle.text, modifier = Modifier.padding(top = 8.dp),
                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    if (status.visibility == View.VISIBLE && status.text.isNotEmpty()) {
                        Text(status.text, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp),
                            color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    }
                    if (busy.visibility == View.VISIBLE) {
                        InfiniteProgressIndicator(Modifier.padding(top = 12.dp).size(24.dp))
                    }
                    ActionButton(start, Modifier.fillMaxWidth().padding(top = 20.dp), primary = true)
                    ActionRow(restart, stop)
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(Modifier.weight(1f), insideMargin = PaddingValues(16.dp)) {
                        Text(port.text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.ui2_port), fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    }
                    Card(Modifier.weight(1f), insideMargin = PaddingValues(16.dp)) {
                        Text(environment.text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.ui2_environment), fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    }
                }
                SmallTitle(stringResource(R.string.ui2_shortcuts))
                Card(Modifier.fillMaxWidth()) {
                    Entry(models.text, stringResource(R.string.ui2_models_sub), R.drawable.ic_settings, models::click)
                    Entry(address.text, stringResource(R.string.ui2_address_sub), R.drawable.ic_ui_link, address::click)
                }
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.ui2_logs), modifier = Modifier.weight(1f).padding(top = 12.dp),
                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    ActionButton(downloadLogs)
                }
                AndroidView(factory = { logScroll }, modifier = Modifier.fillMaxWidth().height(180.dp).padding(top = 8.dp))
                ActionRow(safe, recovery)
            }
        }
    }

    @Composable private fun ActionRow(left: Action, right: Action) {
        // 大字体时改为上下排列，避免两列按钮文字挤压和操作目标过窄。
        val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        if (scale > 1.3f) {
            ActionButton(left, Modifier.fillMaxWidth().padding(top = 10.dp))
            ActionButton(right, Modifier.fillMaxWidth().padding(top = 10.dp))
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(left, Modifier.weight(1f))
                ActionButton(right, Modifier.weight(1f))
            }
        }
    }

    @Composable private fun ActionButton(action: Action, modifier: Modifier = Modifier, primary: Boolean = false) {
        if (action.visibility != View.VISIBLE) return
        Button(
            onClick = action::click,
            enabled = action.enabled,
            minHeight = 48.dp,
            modifier = modifier.semantics {
                if (action.contentDescription.isNotEmpty()) contentDescription = action.contentDescription
            },
            colors = if (primary) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
        ) { Text(action.text, textAlign = TextAlign.Center) }
    }
}
