package com.deepseekharness.app.ui;

import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.TerminalTabs;

/** PTY 和简易终端共用的标签行；新建和关闭始终是独立的 48dp 触摸目标。 */
final class TerminalTabBar {
    interface Actions { void select(long id); void close(long id); }
    static <T> void render(View root,TerminalTabs<T> tabs,Actions actions) {
        LinearLayout row=root.findViewById(R.id.terminal_tabs);row.removeAllViews();
        var current=tabs.current();
        for(var tab:tabs.snapshot()) {
            LinearLayout chip=new LinearLayout(root.getContext());chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackgroundResource(current!=null&&current.id==tab.id?R.drawable.bg_selection:R.drawable.bg_chip);
            TextView name=new TextView(root.getContext());name.setText(com.deepseekharness.app.util.UiText.text("终端 ")+tab.number+(tab.isClosing()?com.deepseekharness.app.util.UiText.text(" · 关闭中…"):""));
            name.setTextColor(root.getContext().getColor(current!=null&&current.id==tab.id?R.color.primary:R.color.text_secondary));
            name.setTextSize(13);name.setIncludeFontPadding(false);name.setGravity(Gravity.CENTER);name.setSingleLine();
            name.setMinWidth(dp(root,72));name.setMinHeight(dp(root,48));name.setPadding(dp(root,12),0,dp(root,8),0);
            name.setContentDescription(com.deepseekharness.app.util.UiText.text("切换到终端 ")+tab.number);name.setOnClickListener(v->actions.select(tab.id));
            chip.addView(name,new LinearLayout.LayoutParams(-2,-2));
            TextView close=new TextView(root.getContext());close.setText(com.deepseekharness.app.util.UiText.text("×"));close.setTextSize(20);close.setGravity(Gravity.CENTER);
            close.setTextColor(root.getContext().getColor(R.color.text_secondary));close.setMinHeight(dp(root,48));
            close.setContentDescription(com.deepseekharness.app.util.UiText.text("关闭终端 ")+tab.number);close.setEnabled(!tab.isClosing());close.setAlpha(tab.isClosing()?0.4f:1f);
            close.setOnClickListener(v->actions.close(tab.id));chip.addView(close,new LinearLayout.LayoutParams(dp(root,48),dp(root,48)));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.setMarginEnd(dp(root,6));row.addView(chip,lp);
            if(current!=null&&current.id==tab.id)chip.post(()->{
                if(root.isAttachedToWindow())((HorizontalScrollView)root.findViewById(R.id.terminal_tabs_scroll)).smoothScrollTo(chip.getLeft(),0);
            });
        }
    }
    private static int dp(View root,int value){return Math.round(value*root.getResources().getDisplayMetrics().density);}
}
