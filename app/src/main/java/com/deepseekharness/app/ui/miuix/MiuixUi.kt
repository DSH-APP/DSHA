package com.deepseekharness.app.ui.miuix

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.deepseekharness.app.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** 与 Java 页面共用资源色和 AppCompat 的明暗选择，不另存一份主题偏好。 */
@Composable
internal fun DshaTheme(content: @Composable () -> Unit) {
    val base = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MiuixTheme(colors = base.copy(
        primary = colorResource(R.color.primary),
        onPrimary = colorResource(R.color.accent_on),
        background = colorResource(R.color.card),
        onBackground = colorResource(R.color.text),
        surface = colorResource(R.color.surface),
        onSurface = colorResource(R.color.text),
        surfaceVariant = colorResource(R.color.card),
        surfaceContainer = colorResource(R.color.card),
        onSurfaceContainer = colorResource(R.color.text),
        onSurfaceSecondary = colorResource(R.color.text_secondary),
        onSurfaceVariantSummary = colorResource(R.color.text_secondary),
        outline = colorResource(R.color.line),
    )) {
        CompositionLocalProvider(LocalContentColor provides MiuixTheme.colorScheme.onSurface) {
            content()
        }
    }
}

/** Compose 内容随所属 Activity/Fragment 的视图生命周期销毁，不持有后台业务。 */
abstract class MiuixView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : AbstractComposeView(context, attrs) {
    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }
}

/** 供已有 Java 管理页复用的卡片和入口；原表单及其监听器保留在卡片内部。 */
object MiuixUi {
    @JvmStatic
    fun card(context: Context, content: LinearLayout): View = MiuixPanel(context).apply {
        addView(content, android.widget.FrameLayout.LayoutParams(-1, -2))
    }

    @JvmStatic
    fun entry(context: Context, title: String, summary: String, icon: Int, action: Runnable): View =
        object : MiuixView(context) {
            @Composable override fun Content() {
                DshaTheme { Entry(title, summary, icon, action::run) }
            }
        }
}

@Composable
internal fun Entry(title: String, summary: String, icon: Int, action: () -> Unit) {
    SuperArrow(
        title = title,
        summary = summary,
        startAction = {
            Icon(painterResource(icon), contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 14.dp).size(24.dp))
        },
        onClick = action,
    )
}
