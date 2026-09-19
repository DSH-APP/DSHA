package com.deepseekharness.app.ui;
import android.app.Activity;
import android.view.Gravity;
import android.widget.*;
import com.deepseekharness.app.R;
/** 首次引导共用排版。内容可滚动，底部主操作始终可见。 */
final class OnboardingUi {
    final Activity activity;final LinearLayout page,body,footer;
    OnboardingUi(Activity owner,String step,String title,String subtitle){activity=owner;
        page=new LinearLayout(owner);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(owner.getColor(R.color.surface));page.setPadding(dp(24),dp(18),dp(24),dp(18));
        LinearLayout header=new LinearLayout(owner);header.setGravity(Gravity.CENTER_VERTICAL);TextView brand=text("DSHA",18,R.color.text);brand.setTypeface(null,android.graphics.Typeface.BOLD);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));header.addView(text(step,11,R.color.text_muted));page.addView(header);
        ScrollView scroll=new ScrollView(owner);body=new LinearLayout(owner);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(0,dp(24),0,dp(16));scroll.addView(body);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView heading=text(title,27,R.color.text);heading.setTypeface(null,android.graphics.Typeface.BOLD);body.addView(heading);note(subtitle);
        footer=new LinearLayout(owner);footer.setOrientation(LinearLayout.VERTICAL);page.addView(footer);owner.setContentView(page);
    }
    int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    TextView text(String s,int size,int color){TextView t=new TextView(activity);t.setText(s);t.setTextSize(size);t.setTextColor(activity.getColor(color));t.setIncludeFontPadding(false);return t;}
    void note(String s){TextView t=text(s,13,R.color.text_secondary);t.setLineSpacing(dp(5),1);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(14);p.bottomMargin=dp(16);body.addView(t,p);}
    void row(String label,String value){LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(16),dp(14),dp(16),dp(14));card.setBackgroundResource(R.drawable.bg_card);card.addView(text(label,11,R.color.text_muted));TextView content=text(value,16,R.color.text);content.setPadding(0,dp(7),0,0);card.addView(content);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(10);body.addView(card,p);}
    void primary(String title,Runnable action){button(title,action,true);}void secondary(String title,Runnable action){button(title,action,false);}
    void button(String title,Runnable action,boolean primary){var b=new androidx.appcompat.widget.AppCompatButton(activity);b.setText(title);b.setTextSize(14);b.setAllCaps(false);b.setGravity(Gravity.CENTER);b.setMinHeight(dp(48));b.setBackgroundResource(primary?R.drawable.bg_btn_primary:R.drawable.bg_action_plain);b.setTextColor(activity.getColorStateList(primary?R.color.button_primary_text:R.color.primary));b.setOnClickListener(v->action.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);footer.addView(b,p);}
}
