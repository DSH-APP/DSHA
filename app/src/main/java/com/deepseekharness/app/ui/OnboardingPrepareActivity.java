package com.deepseekharness.app.ui;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import android.view.Gravity;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.*;
import com.deepseekharness.app.util.UiText;

/** 安装前只读检查；不伪造就绪状态、不绕过实际维护入口。 */
public final class OnboardingPrepareActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state){super.onCreate(state);
        var ui=new OnboardingUi(this,"4 / 7",UiText.choose("为第一次启动做准备","Prepare your first launch"),UiText.choose("内置 Ubuntu 与运行组件将部署到应用私有目录。","Ubuntu and runtime components live in the app’s private storage."));
        HarnessController controller=HarnessController.get(this);
        ui.row(UiText.choose("设备架构","Architecture"),android.os.Build.SUPPORTED_ABIS.length>0?android.os.Build.SUPPORTED_ABIS[0]:"unknown");
        ui.row(UiText.choose("系统与版本","System"),"Android "+android.os.Build.VERSION.RELEASE+" · "+(com.deepseekharness.app.BuildConfig.LOW_ANDROID?UiText.choose("兼容版","Compatibility"):UiText.choose("标准版","Standard")));
        ui.row(UiText.choose("可用空间","Free space"),android.text.format.Formatter.formatFileSize(this,getFilesDir().getUsableSpace()));
        ui.row(UiText.choose("安装方式","Installation"),UiText.choose("随包离线部署","Bundled offline runtime"));
        ui.note(UiText.choose("环境准备会进行真实检查。现有环境和个人数据会按原有维护规则处理；设备控制权限可以稍后单独设置。","Setup performs real checks and preserves existing data through the maintenance workflow. Device permissions remain optional."));
        ui.primary(controller.isEnvironmentReady()?UiText.choose("继续模型配置","Continue to model setup"):UiText.choose("安装内置环境","Install bundled runtime"),()->{
            new ConfigStore(this).setWelcomed(true);
            startActivity(new Intent(this,controller.isEnvironmentReady()?ModelSetupActivity.class:ExtractActivity.class).putExtra("first_setup",true).putExtra("first_run",true));finish();
        });
        ui.secondary(UiText.choose("返回介绍","Back to introduction"),this::finish);
    }
}
