package com.deepseekharness.app.ui;

import android.content.Context;
import android.view.*;
import android.widget.*;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;

/** 恢复页的原生布局；静态展示可独立验收，不执行 Web 或修复操作。 */
final class StartupRecoveryLayout {
    final ScrollView root;
    final LinearLayout content,recovery,tools,attempts,snapshots,plugins;
    final View pluginCard;
    final TextView progress;
    private final Context context;
    private static String t(String zh,String en){return UiText.choose(zh,en);}
    StartupRecoveryLayout(Context context,String reason,Runnable back){
        this.context=context;
        root=new ScrollView(context);root.setFillViewport(true);root.setBackgroundColor(context.getColor(R.color.surface));
        content=column();content.setPadding(dp(20),dp(12),dp(20),dp(20));root.addView(content);
        View backRow=action(content,t("返回","Back"),R.drawable.ic_recovery_right,back,false);
        ((ImageView)((LinearLayout)backRow).getChildAt(0)).setRotation(180);backRow.setBackgroundResource(R.drawable.bg_btn);
        LinearLayout hero=new LinearLayout(context);hero.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams heroParams=new LinearLayout.LayoutParams(-1,-2);heroParams.topMargin=dp(16);content.addView(hero,heroParams);
        ImageView emblem=icon(R.drawable.ic_recovery_wrench,R.color.text);emblem.setPadding(dp(10),dp(10),dp(10),dp(10));emblem.setBackgroundResource(R.drawable.bg_recovery_emblem);
        hero.addView(emblem,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout titleBlock=column();LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(0,-2,1);titleParams.leftMargin=dp(14);hero.addView(titleBlock,titleParams);
        TextView title=label(titleBlock,t("启动恢复","Startup recovery"),24,R.color.text);title.setTypeface(null,android.graphics.Typeface.BOLD);
        label(titleBlock,t("进入原因：","Reason: ")+reason,14,R.color.text);
        TextView description=label(content,t("此页面不依赖 Web。可查看最近启动记录、恢复配置快照，或卸载出错的插件。","This page works independently of Web. Review recent starts, restore configuration snapshots, or remove faulty plugins."),14,R.color.text_secondary);
        description.setPadding(0,dp(12),0,dp(4));
        progress=label(content,"",13,R.color.primary);progress.setVisibility(View.GONE);
        recovery=card(t("恢复与修复","Recovery & repair"),t("通过以下操作尝试解决启动问题","Try these actions to resolve startup problems"),0);
        tools=card(t("其他工具","Other tools"),t("更多高级选项，帮助排查和解决问题","More options to diagnose and resolve problems"),0);
        attempts=card(t("最近五次启动","Last five starts"),"",R.drawable.ic_recovery_clock);
        snapshots=card(t("配置快照","Configuration snapshots"),t("保留三次健康启动与三次修复前快照；仅保存配置，不复制会话、工作区或依赖目录。","Keeps three healthy-start and three pre-repair snapshots. Saves configuration only, without sessions, workspaces, or dependency directories."),R.drawable.ic_recovery_database);
        plugins=card(t("插件卸载","Remove plugins"),"",R.drawable.ic_recovery_document);
        pluginCard=(View)plugins.getParent();pluginCard.setVisibility(View.GONE);
    }
    private LinearLayout column(){LinearLayout view=new LinearLayout(context);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private int dp(int n){return Math.round(n*context.getResources().getDisplayMetrics().density);}
    private ImageView icon(int resource,int color){
        androidx.appcompat.widget.AppCompatImageView icon=new androidx.appcompat.widget.AppCompatImageView(context);icon.setImageResource(resource);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(context.getColor(color)));icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return icon;
    }
    private LinearLayout card(String title,String subtitle,int icon){
        LinearLayout card=column();card.setPadding(dp(12),dp(12),dp(12),dp(12));card.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(12);content.addView(card,params);
        LinearLayout heading=new LinearLayout(context);heading.setGravity(Gravity.TOP);card.addView(heading);
        if(icon!=0){ImageView image=icon(icon,R.color.text_muted);LinearLayout.LayoutParams imageParams=new LinearLayout.LayoutParams(dp(24),dp(24));imageParams.rightMargin=dp(12);imageParams.topMargin=dp(2);heading.addView(image,imageParams);}
        LinearLayout copy=column();heading.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        TextView label=label(copy,title,16,R.color.text);label.setTypeface(null,android.graphics.Typeface.BOLD);
        if(!subtitle.isEmpty())label(copy,subtitle,13,R.color.text_muted);
        LinearLayout entries=column();card.addView(entries,new LinearLayout.LayoutParams(-1,-2));return entries;
    }
    TextView label(LinearLayout parent,String value,int size,int color){
        TextView view=new TextView(context);view.setText(value);view.setTextSize(size);view.setTextColor(context.getColor(color));view.setLineSpacing(dp(2),1);
        view.setPadding(0,dp(2),0,dp(2));parent.addView(view,new LinearLayout.LayoutParams(-1,-2));return view;
    }
    View action(LinearLayout parent,String label,int resource,Runnable click,boolean chevron){
        LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(dp(48));row.setPadding(dp(12),dp(10),dp(12),dp(10));
        row.setBackgroundResource(R.drawable.bg_recovery_action);row.setFocusable(true);row.setContentDescription(label);row.setOnClickListener(v->click.run());
        if(resource!=0){ImageView icon=icon(resource,R.color.text);LinearLayout.LayoutParams iconParams=new LinearLayout.LayoutParams(dp(24),dp(24));iconParams.rightMargin=dp(12);row.addView(icon,iconParams);}
        TextView text=new TextView(context);text.setText(label);text.setTextSize(15);text.setIncludeFontPadding(false);text.setGravity(Gravity.CENTER_VERTICAL);
        text.setTextColor(androidx.core.content.ContextCompat.getColorStateList(context,R.color.button_text));row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        if(chevron){ImageView next=icon(R.drawable.ic_recovery_right,R.color.text_muted);LinearLayout.LayoutParams nextParams=new LinearLayout.LayoutParams(dp(18),dp(18));nextParams.leftMargin=dp(8);row.addView(next,nextParams);}
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(parent.getChildCount()==0?8:5);parent.addView(row,params);
        androidx.core.view.ViewCompat.setAccessibilityDelegate(row,new androidx.core.view.AccessibilityDelegateCompat(){
            @Override public void onInitializeAccessibilityNodeInfo(View host,androidx.core.view.accessibility.AccessibilityNodeInfoCompat info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}
        });return row;
    }
    static void enabled(View action,boolean enabled){
        action.setEnabled(enabled);
        if(action instanceof ViewGroup)for(int i=0;i<((ViewGroup)action).getChildCount();i++)((ViewGroup)action).getChildAt(i).setEnabled(enabled);
    }
}
