package com.deepseekharness.app.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.deepseekharness.app.R;

/** 原生内页共用卡片、分组和固定页脚，业务事件由页面自己绑定。 */
final class CardPage {
    final Context context;
    final LinearLayout root,content,footer;
    final ScrollView scroll;
    CardPage(Context context,String title,String subtitle){
        this.context=context;root=column();root.setBackgroundColor(context.getColor(R.color.surface));
        scroll=new ScrollView(context);scroll.setFillViewport(true);content=column();content.setPadding(dp(18),dp(16),dp(18),dp(24));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView heading=text(title,24,R.color.text);heading.setTypeface(null,android.graphics.Typeface.BOLD);content.addView(heading);
        if(!subtitle.isEmpty()){TextView sub=text(subtitle,12,R.color.text_secondary);sub.setPadding(0,dp(7),0,dp(18));content.addView(sub);}
        footer=column();footer.setPadding(dp(18),dp(8),dp(18),dp(12));root.addView(footer);
    }
    int dp(int n){return Math.round(n*context.getResources().getDisplayMetrics().density);}
    LinearLayout column(){LinearLayout view=new LinearLayout(context);view.setOrientation(LinearLayout.VERTICAL);return view;}
    TextView text(String value,int size,int color){TextView view=new TextView(context);view.setText(value);view.setTextSize(size);view.setIncludeFontPadding(false);view.setTextColor(context.getColor(color));return view;}
    LinearLayout card(){LinearLayout card=column();card.setBackgroundResource(R.drawable.bg_card);card.setPadding(dp(16),dp(14),dp(16),dp(14));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(14);content.addView(card,p);return card;}
    void label(String title){TextView label=text(title,11,R.color.text_muted);label.setPadding(0,dp(6),0,dp(8));content.addView(label);}
    void kv(LinearLayout card,String label,String value){LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(dp(38));row.addView(text(label,12,R.color.text_secondary),new LinearLayout.LayoutParams(0,-2,1));TextView content=text(value,12,R.color.text);content.setMaxWidth(dp(210));content.setGravity(Gravity.END);row.addView(content);card.addView(row);}
    void entry(LinearLayout card,String title,String subtitle,int icon,Runnable action){
        LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(10),0,dp(10));row.setMinimumHeight(dp(64));row.setBackgroundResource(R.drawable.bg_action_plain);row.setFocusable(true);row.setOnClickListener(v->action.run());
        ImageView mark=new ImageView(context);mark.setImageResource(icon);mark.setImageTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.primary)));mark.setBackgroundResource(R.drawable.bg_logo);mark.setPadding(dp(7),dp(7),dp(7),dp(7));row.addView(mark,new LinearLayout.LayoutParams(dp(32),dp(32)));
        LinearLayout words=column();TextView heading=text(title,13,R.color.text);heading.setTypeface(null,android.graphics.Typeface.BOLD);words.addView(heading);TextView detail=text(subtitle,11,R.color.text_secondary);detail.setPadding(0,dp(5),0,0);words.addView(detail);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.leftMargin=dp(12);row.addView(words,p);
        ImageView arrow=new ImageView(context);arrow.setImageResource(R.drawable.ic_ui2_chevron);row.addView(arrow,new LinearLayout.LayoutParams(dp(16),dp(16)));card.addView(row);
    }
    Button button(LinearLayout parent,String title,boolean primary,Runnable action){var button=new androidx.appcompat.widget.AppCompatButton(context);button.setText(title);button.setTextSize(13);button.setAllCaps(false);button.setMinHeight(dp(48));button.setBackgroundResource(primary?R.drawable.bg_btn_primary:R.drawable.bg_btn);button.setTextColor(context.getColorStateList(primary?R.color.button_primary_text:R.color.button_text));button.setOnClickListener(v->action.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);parent.addView(button,p);return button;}
}
