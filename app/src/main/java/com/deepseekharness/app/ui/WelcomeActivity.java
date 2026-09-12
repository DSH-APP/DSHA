package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;

import java.util.Arrays;
import java.util.List;

/**
 * 欢迎引导（3 页）：第 3 页点「开始」进入解压/主界面。
 */
public class WelcomeActivity extends AppCompatActivity {

    private final int[] pages = { R.layout.welcome_page1, R.layout.welcome_page2, R.layout.welcome_page3 };
    private final TextView[] dots = new TextView[3];

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome);

        ViewPager2 pager = findViewById(R.id.welcome_pager);
        Button btn = findViewById(R.id.welcome_btn);
        LinearLayout dotsBox = findViewById(R.id.welcome_dots);

        pager.setAdapter(new PageAdapter());
        pager.setUserInputEnabled(true);

        for (int i = 0; i < 3; i++) {
            TextView d = new TextView(this);
            d.setText(com.deepseekharness.app.util.UiText.text("●"));
            d.setTextColor(getColor(R.color.text_muted));
            d.setTextSize(10);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(6, 0, 6, 0);
            d.setLayoutParams(lp);
            dotsBox.addView(d);
            dots[i] = d;
        }
        dots[0].setTextColor(getColor(R.color.primary));

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                for (int i = 0; i < 3; i++) {
                    dots[i].setTextColor(getColor(i == position ? R.color.primary : R.color.text_muted));
                }
                btn.setText(position == 2 ? com.deepseekharness.app.util.UiText.text("开始") : com.deepseekharness.app.util.UiText.text("下一步"));
            }
        });

        btn.setOnClickListener(v -> {
            int cur = pager.getCurrentItem();
            if (cur < 2) {
                pager.setCurrentItem(cur + 1);
            } else {
                new ConfigStore(this).setWelcomed(true);
                HarnessController c = new HarnessController(this);
                startActivity(new Intent(this,
                        c.isEnvironmentReady() ? MainActivity.class : ExtractActivity.class));
                finish();
            }
        });
    }

    private class PageAdapter extends RecyclerView.Adapter<PageAdapter.Holder> {
        private final List<Integer> layouts = Arrays.asList(
                R.layout.welcome_page1, R.layout.welcome_page2, R.layout.welcome_page3);

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(layouts.get(viewType), parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
        }

        @Override
        public int getItemViewType(int position) {
            return position;
        }

        @Override
        public int getItemCount() {
            return layouts.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            Holder(@NonNull View itemView) {
                super(itemView);
            }
        }
    }
}
