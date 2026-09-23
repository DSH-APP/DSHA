package com.deepseekharness.app.ui;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.deepseekharness.app.R;
/** 内容短时收紧，长时滚动正文并保留底部操作。 */
final class CardSheet {
    static androidx.appcompat.app.AlertDialog create(Context context,CardPage page){
        page.root.setBackgroundResource(R.drawable.bg_card);
        page.scroll.setFillViewport(false);
        page.scroll.setLayoutParams(new LinearLayout.LayoutParams(-1,-2,1));
        FrameLayout container=new FrameLayout(context){
            @Override protected void onMeasure(int widthSpec,int heightSpec){
                int limit=Math.round(getResources().getDisplayMetrics().heightPixels*.86f);
                if(View.MeasureSpec.getMode(heightSpec)!=View.MeasureSpec.UNSPECIFIED)limit=Math.min(limit,View.MeasureSpec.getSize(heightSpec));
                super.onMeasure(widthSpec,View.MeasureSpec.makeMeasureSpec(limit,View.MeasureSpec.AT_MOST));
            }
        };
        container.addView(page.root,new FrameLayout.LayoutParams(-1,-2));
        return new DshaDialogBuilder(context).setView(container).create();
    }
    static void show(androidx.appcompat.app.AlertDialog dialog,Context context){
        dialog.show();if(dialog.getWindow()!=null){dialog.getWindow().setGravity(Gravity.BOTTOM);dialog.getWindow().setLayout(-1,-2);dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
    }
    static void choices(Context context,String title,java.util.function.Supplier<java.util.List<String>> source,java.util.function.IntConsumer selected){
        CardPage page=new CardPage(context,title,"");var dialog=create(context,page);java.util.List<String> values=source.get();
        for(int i=0;i<values.size();i++){final int position=i;page.entry(page.content,values.get(i),"",R.drawable.ic_ui2_box,()->{dialog.dismiss();selected.accept(position);});}
        page.button(page.footer,com.deepseekharness.app.util.UiText.choose("取消","Cancel"),false,dialog::dismiss);show(dialog,context);
    }
    static void show(Context context,String title,String body){
        CardPage page=new CardPage(context,title,"");TextView text=page.text(body,13,R.color.text_secondary);text.setPadding(0,page.dp(14),0,0);text.setTextIsSelectable(true);text.setLineSpacing(page.dp(5),1);page.content.addView(text);
        var dialog=create(context,page);page.button(page.footer,com.deepseekharness.app.util.UiText.choose("关闭","Close"),false,dialog::dismiss);show(dialog,context);
    }
}
