package com.deepseekharness.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.*;
import androidx.appcompat.widget.AppCompatSpinner;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;

/** 保留 Spinner 适配器与选择回调，只把系统下拉菜单换为可触达的底部选择面板。 */
public final class DshaSelectView extends AppCompatSpinner {
    public DshaSelectView(Context context){super(context);skin();}
    public DshaSelectView(Context context,AttributeSet attrs){super(context,attrs);skin();}
    public DshaSelectView(Context context,AttributeSet attrs,int style){super(context,attrs,style);skin();}
    private void skin(){setBackgroundResource(R.drawable.bg_input);setPadding(dp(8),0,dp(8),0);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    // Spinner 的父实现会打开第二个系统菜单；这里提供等价的无障碍点击事件。
    @android.annotation.SuppressLint("MissingSuperCall")
    @Override public boolean performClick(){
        if(!isEnabled()||getAdapter()==null)return false;
        sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
        BottomSheetDialog dialog=new BottomSheetDialog(getContext());
        LinearLayout root=new LinearLayout(getContext());root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(16),dp(18),dp(24));root.setBackgroundResource(R.drawable.bg_card);
        TextView title=new TextView(getContext());title.setText(getPrompt()!=null?getPrompt():UiText.choose("请选择","Choose an option"));title.setTextSize(18);title.setTypeface(null,android.graphics.Typeface.BOLD);title.setTextColor(getContext().getColor(R.color.text));title.setPadding(dp(8),0,dp(8),dp(18));root.addView(title);
        ScrollView scroll=new ScrollView(getContext());LinearLayout options=new LinearLayout(getContext());options.setOrientation(LinearLayout.VERTICAL);scroll.addView(options);
        int count=getAdapter().getCount();root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1f));
        for(int i=0;i<count;i++){
            final int position=i;boolean selected=i==getSelectedItemPosition();
            TextView item=new TextView(getContext());item.setText(String.valueOf(getAdapter().getItem(i))+(selected?"    ✓":""));item.setTextSize(14);item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(dp(16),dp(14),dp(16),dp(14));item.setMinHeight(dp(52));item.setTextColor(getContext().getColor(selected?R.color.primary:R.color.text));item.setBackgroundResource(selected?R.drawable.bg_logo:R.drawable.bg_action_plain);item.setFocusable(true);item.setSelected(selected);
            if(getAdapter() instanceof ListAdapter)item.setEnabled(((ListAdapter)getAdapter()).isEnabled(i));
            item.setOnClickListener(v->{setSelection(position);dialog.dismiss();sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED);});
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(6);options.addView(item,params);
        }
        dialog.setContentView(root);dialog.show();
        android.graphics.Rect visible=new android.graphics.Rect();getWindowVisibleDisplayFrame(visible);
        int available=visible.height()>0?visible.height():getResources().getDisplayMetrics().heightPixels;
        android.view.ViewGroup.LayoutParams layout=root.getLayoutParams();
        layout.height=Math.min(Math.round(dp(Math.max(1,count)*58+90)*getResources().getConfiguration().fontScale),Math.max(dp(80),available-dp(24)));
        root.setLayoutParams(layout);
        dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        int selected=getSelectedItemPosition();
        if(selected>=0&&selected<options.getChildCount())options.getChildAt(selected).requestFocus();
        dialog.setOnDismissListener(d->requestFocus());return true;
    }
}
