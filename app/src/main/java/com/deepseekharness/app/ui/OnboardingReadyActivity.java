package com.deepseekharness.app.ui;
import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.UiText;
public final class OnboardingReadyActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state){super.onCreate(state);var controller=HarnessController.get(this);
        var ui=new OnboardingUi(this,"7 / 7",controller.isEnvironmentReady()?UiText.choose("你的工作台，准备好了。","Your workspace is ready."):UiText.choose("运行环境需要检查","The runtime needs attention"),UiText.choose("从一段对话开始，也可以随时调整模型和插件。","Start a conversation, and adjust models or plugins whenever you need."));
        ui.row(UiText.choose("运行环境","Runtime"),controller.isEnvironmentReady()?"Ubuntu · READY":UiText.choose("需要检查","Needs attention"));
        ui.row("DSH",com.deepseekharness.app.util.Constants.DSH_VERSION);
        ui.note(UiText.choose("模型凭据由实际模型设置保存。你可以在启动页继续配置。","Credentials are saved by the real model editor. You can continue setup from the launch page."));
        ui.primary(UiText.choose("进入 DSHA","Open DSHA"),()->{startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));finish();});
    }
}
