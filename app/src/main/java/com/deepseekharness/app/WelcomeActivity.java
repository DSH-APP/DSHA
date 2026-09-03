package com.deepseekharness.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

/** 首次启动引导：3 页说明一体式安装流程，结束后进入主界面 */
public class WelcomeActivity extends AppCompatActivity {

    private final int[] layouts = {
            R.layout.welcome_page1,
            R.layout.welcome_page2,
            R.layout.welcome_page3
    };

    private ViewPager2 pager;
    private Button nextBtn;
    private android.widget.LinearLayout dots;
    /** 指示点尺寸（px，随屏幕密度换算） */
    private int dotSize;
    private int dotActiveWidth;
    private int dotGap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DshaTheme.apply(this);
        DshaBackground.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome);

        pager = findViewById(R.id.welcome_pager);
        nextBtn = findViewById(R.id.welcome_btn);
        dots = findViewById(R.id.welcome_dots);

        float d = getResources().getDisplayMetrics().density;
        dotSize = Math.round(7 * d);
        dotActiveWidth = Math.round(22 * d);
        dotGap = Math.round(6 * d);
        buildDots();

        pager.setAdapter(new PagerAdapter());
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                nextBtn.setText(position == layouts.length - 1 ? "开始使用" : "下一步");
                highlightDot(position);
            }
        });

        nextBtn.setOnClickListener(v -> {
            int cur = pager.getCurrentItem();
            if (cur < layouts.length - 1) {
                pager.setCurrentItem(cur + 1);
            } else {
                finishWelcome();
            }
        });
    }

    /** 按页数生成指示点（宽度在 highlightDot 里切换） */
    private void buildDots() {
        if (dots == null) return;
        dots.removeAllViews();
        for (int i = 0; i < layouts.length; i++) {
            View dot = new View(this);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(dotSize, dotSize);
            if (i > 0) lp.setMarginStart(dotGap);
            dot.setLayoutParams(lp);
            dots.addView(dot);
        }
        highlightDot(0);
    }

    /** 当前页的点拉长成主色胶囊，其余为灰点 */
    private void highlightDot(int position) {
        if (dots == null) return;
        for (int i = 0; i < dots.getChildCount(); i++) {
            View dot = dots.getChildAt(i);
            boolean on = i == position;
            dot.setBackgroundResource(on ? R.drawable.dot_active : R.drawable.dot_inactive);
            android.widget.LinearLayout.LayoutParams lp =
                    (android.widget.LinearLayout.LayoutParams) dot.getLayoutParams();
            lp.width = on ? dotActiveWidth : dotSize;
            dot.setLayoutParams(lp);
        }
    }

    private void finishWelcome() {
        getSharedPreferences("deepseekharness", MODE_PRIVATE)
                .edit().putBoolean("welcomed", true).apply();
        startActivity(new Intent(this, ExtractActivity.class));
        finish();
    }

    private class PagerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(layouts[viewType], parent, false);
            // 最后一页：关于入口（GitHub / QQ 群）
            if (viewType == layouts.length - 1) {
                Button aboutBtn = v.findViewById(R.id.welcome_about);
                if (aboutBtn != null) {
                    aboutBtn.setOnClickListener(btn -> AboutDialog.show(WelcomeActivity.this));
                }
            }
            return new RecyclerView.ViewHolder(v) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) { }

        @Override
        public int getItemCount() { return layouts.length; }

        @Override
        public int getItemViewType(int position) { return position; }
    }
}
