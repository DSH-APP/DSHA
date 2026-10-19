package com.deepseekharness.app.ui.miuix

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.Card

/** XML 表单的 Miuix 卡片。原控件仍在 View 树中，查找、输入恢复及监听器立即可用。 */
class MiuixPanel @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    private val surface = object : MiuixView(context) {
        @Composable override fun Content() {
            DshaTheme { Card(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) } }
        }
    }

    init {
        surface.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 背景不参与 wrap_content 高度计算，否则滚动页面首卡会撑满整屏。
        val params = surface.layoutParams
        params.width = 0
        params.height = 0
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        params.width = LayoutParams.MATCH_PARENT
        params.height = LayoutParams.MATCH_PARENT
        surface.measure(MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY))
    }
}
