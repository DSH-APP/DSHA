package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.view.WindowManager;
import android.widget.FrameLayout;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;

/** 仅 debug 的空测试宿主，无 MainActivity 启停、插件初始化或用户偏好写入。 */
public final class FragmentSessionTestActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle saved) {
        setTheme(R.style.Theme_DeepseekHarness);
        super.onCreate(saved);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        FrameLayout container = new FrameLayout(this);
        container.setId(R.id.fragment_container);
        setContentView(container);
    }
}
