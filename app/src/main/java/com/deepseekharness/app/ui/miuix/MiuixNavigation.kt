package com.deepseekharness.app.ui.miuix

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import androidx.compose.runtime.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import com.deepseekharness.app.R
import java.util.function.IntPredicate
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem

/** 只负责选中态与绘制；页面切换仍同步交给 MainActivity 的 FragmentManager。 */
class MiuixNavigation @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    MiuixView(context, attrs) {
    private var selected by mutableIntStateOf(R.id.nav_launch)
    private var listener: IntPredicate? = null

    fun getSelectedItemId(): Int = selected

    fun setSelectedItemId(id: Int) {
        if (id !in ids) return
        // 先回调再提交选中态，保留原导航对重复点击、外部通知和拒绝导航的语义。
        if (listener?.test(id) != false) selected = id
    }

    fun setOnItemSelectedListener(value: IntPredicate) { listener = value }

    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putParcelable("parent", super.onSaveInstanceState())
        putInt("selected", selected)
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is Bundle) { super.onRestoreInstanceState(state); return }
        super.onRestoreInstanceState(state.getParcelable("parent"))
        selected = state.getInt("selected", R.id.nav_launch).takeIf { it in ids } ?: R.id.nav_launch
    }

    @Composable override fun Content() {
        DshaTheme {
            // ModernAndroidUi 已负责系统栏和键盘避让，组件不能重复加底部留白。
            NavigationBar(modifier = Modifier.selectableGroup(), defaultWindowInsetsPadding = false, showDivider = false) {
                ids.indices.forEach { index ->
                    NavigationBarItem(
                        modifier = Modifier
                            .heightIn(min = (64 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp)
                            .selectable(selected == ids[index], role = Role.Tab,
                                onClick = { setSelectedItemId(ids[index]) }),
                        selected = selected == ids[index],
                        onClick = { setSelectedItemId(ids[index]) },
                        icon = ImageVector.vectorResource(icons[index]),
                        label = stringResource(labels[index]),
                    )
                }
            }
        }
    }

    private companion object {
        val ids = intArrayOf(R.id.nav_launch, R.id.nav_plugins, R.id.nav_settings, R.id.nav_terminal)
        val icons = intArrayOf(R.drawable.ic_launch, R.drawable.ic_plugins, R.drawable.ic_settings, R.drawable.ic_terminal)
        val labels = intArrayOf(R.string.ui_m0005, R.string.ui_m0016, R.string.ui_m0023, R.string.ui_m0021)
    }
}
