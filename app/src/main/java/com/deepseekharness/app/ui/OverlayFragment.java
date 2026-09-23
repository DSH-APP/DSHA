package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.OverlayController;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.materialswitch.MaterialSwitch;

/** 独立悬浮条编辑页；所有控件编辑草稿，保存才写入原有偏好键。 */
public final class OverlayFragment extends Fragment {
    private CardPage ui;
    private MaterialSwitch enabled,reasoning,commands,confirmation;
    private SeekBar opacity,font;
    private DshaSelectView lines,hold,background;
    private TextView sample,opacityLabel,permission;
    private boolean livePreview;
    private Button previewAction;
    private final int[] durations={2,5,6,10,15,30,60};
    @Override public View onCreateView(LayoutInflater inflater,ViewGroup container,Bundle saved){
        var prefs=requireContext().getSharedPreferences(Constants.PREFS,0);
        ui=new CardPage(requireContext(),UiText.choose("悬浮条","Floating status"),UiText.choose("在其他应用中，轻量查看任务进展。","Follow progress while using other apps."));
        LinearLayout preview=ui.card();enabled=toggle(preview,UiText.choose("显示悬浮条","Show floating status"),saved==null?prefs.getBoolean(OverlayController.K_ENABLED,false):saved.getBoolean("enabled"));
        permission=ui.text("",11,R.color.text_muted);preview.addView(permission);
        sample=ui.text(UiText.choose("DSHA · 样式预览\n正在整理工作区中的文件…","DSHA · Style preview\nOrganizing files in the workspace…"),13,R.color.text);sample.setPadding(ui.dp(14),ui.dp(14),ui.dp(14),ui.dp(14));LinearLayout.LayoutParams spacing=new LinearLayout.LayoutParams(-1,-2);spacing.topMargin=ui.dp(16);preview.addView(sample,spacing);
        // 预览入口放在首张卡片中，用户不必滚到页面底部才知道怎样验证悬浮窗。
        previewAction=ui.button(preview,UiText.choose("预览悬浮条","Preview floating status"),false,()->{
            livePreview=true;
            paint();
            if(!OverlayController.permitted(requireContext())){
                permission.setText(UiText.choose("需要悬浮窗权限，正在打开系统设置…","Overlay permission is required; opening system settings…"));
                openOverlayPermission();
            } else {
                permission.setText(UiText.choose("系统悬浮条预览已显示，调整下面的选项会立即刷新。","System overlay preview is shown; changes below update it immediately."));
            }
        });
        previewAction.setId(R.id.overlay_preview_button);
        previewAction.setContentDescription(UiText.choose("显示系统悬浮条预览","Show system overlay preview"));
        LinearLayout appearance=ui.card();opacityLabel=ui.text("",13,R.color.text);appearance.addView(opacityLabel);
        opacity=new SeekBar(requireContext());opacity.setMax(80);opacity.setProgress((saved==null?prefs.getInt(OverlayController.K_ALPHA,OverlayController.DEF_ALPHA):saved.getInt("alpha"))-20);appearance.addView(opacity,new LinearLayout.LayoutParams(-1,ui.dp(48)));
        String[] lineLabels=new String[6];for(int i=0;i<6;i++)lineLabels[i]=(i+1)+UiText.choose(" 行"," lines");
        lines=choice(appearance,UiText.choose("最多显示行数","Maximum lines"),lineLabels,(saved==null?prefs.getInt(OverlayController.K_LINES,OverlayController.DEF_LINES):saved.getInt("lines"))-1);
        int holdSeconds=saved==null?prefs.getInt(OverlayController.K_HOLD,OverlayController.DEF_HOLD):saved.getInt("hold");int selected=-1;for(int i=0;i<durations.length;i++)if(holdSeconds==durations[i])selected=i;
        String[] times=new String[durations.length+(selected<0?1:0)];for(int i=0;i<durations.length;i++)times[i]=durations[i]+UiText.choose(" 秒"," seconds");
        if(selected<0){selected=durations.length;times[selected]=holdSeconds+UiText.choose(" 秒（当前）"," seconds (current)");}
        hold=choice(appearance,UiText.choose("停留时间","Display duration"),times,selected);hold.setTag(holdSeconds);
        String[] colors=new String[OverlayController.BG_NAMES.length];for(int i=0;i<colors.length;i++)colors[i]=UiText.text(OverlayController.BG_NAMES[i]);
        background=choice(appearance,UiText.choose("背景样式","Background style"),colors,saved==null?prefs.getInt(OverlayController.K_BG,0):saved.getInt("background"));
        appearance.addView(ui.text(UiText.choose("文字大小","Text size"),13,R.color.text));font=new SeekBar(requireContext());font.setMax(14);font.setProgress((saved==null?prefs.getInt(OverlayController.K_TEXT_SP,OverlayController.DEF_TEXT_SP):saved.getInt("font"))-6);appearance.addView(font,new LinearLayout.LayoutParams(-1,ui.dp(48)));
        LinearLayout behavior=ui.card();reasoning=toggle(behavior,UiText.choose("显示思考过程","Show reasoning"),saved==null?prefs.getBoolean(OverlayController.K_REASONING,false):saved.getBoolean("reasoning"));commands=toggle(behavior,UiText.choose("显示执行命令","Show executed commands"),saved==null?prefs.getBoolean(OverlayController.K_COMMAND,true):saved.getBoolean("commands"));confirmation=toggle(behavior,UiText.choose("在悬浮条上确认命令","Confirm commands in the overlay"),saved==null?prefs.getBoolean(OverlayController.K_CONFIRM,true):saved.getBoolean("confirmation"));
        CompoundButton.OnCheckedChangeListener repaint=(button,checked)->paint();
        enabled.setOnCheckedChangeListener(repaint);reasoning.setOnCheckedChangeListener(repaint);commands.setOnCheckedChangeListener(repaint);confirmation.setOnCheckedChangeListener(repaint);
        ui.button(ui.footer,UiText.choose("保存样式","Save style"),true,this::save);
        SeekBar.OnSeekBarChangeListener changes=new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int value,boolean user){paint();}public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}};
        opacity.setOnSeekBarChangeListener(changes);font.setOnSeekBarChangeListener(changes);
        AdapterView.OnItemSelectedListener selection=new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> parent){}public void onItemSelected(AdapterView<?> parent,View view,int position,long id){paint();}};lines.setOnItemSelectedListener(selection);background.setOnItemSelectedListener(selection);renderPermission();paint();return ui.root;
    }
    private MaterialSwitch toggle(LinearLayout parent,String label,boolean value){MaterialSwitch toggle=new MaterialSwitch(requireContext());toggle.setText(label);toggle.setTextSize(13);toggle.setMinHeight(ui.dp(48));toggle.setChecked(value);parent.addView(toggle,new LinearLayout.LayoutParams(-1,-2));return toggle;}
    private DshaSelectView choice(LinearLayout parent,String label,String[] values,int selected){TextView title=ui.text(label,12,R.color.text_secondary);title.setPadding(0,ui.dp(16),0,ui.dp(8));parent.addView(title);DshaSelectView view=new DshaSelectView(requireContext());view.setPrompt(label);view.setBackgroundResource(R.drawable.bg_input);view.setPadding(ui.dp(10),0,ui.dp(10),0);view.setMinimumHeight(ui.dp(48));view.setAdapter(new ArrayAdapter<>(requireContext(),R.layout.item_data_choice,values));view.setSelection(Math.max(0,Math.min(values.length-1,selected)));parent.addView(view,new LinearLayout.LayoutParams(-1,ui.dp(48)));return view;}
    private int seconds(){return hold.getSelectedItemPosition()<durations.length?durations[hold.getSelectedItemPosition()]:(Integer)hold.getTag();}
    private void paint(){
        if(sample==null||background==null||font==null||lines==null)return;
        int index=Math.max(0,Math.min(OverlayController.BG_PRESETS.length-1,background.getSelectedItemPosition()));
        int count=Math.max(1,lines.getSelectedItemPosition()+1);
        int sp=Math.max(6,font.getProgress()+6);
        int alphaValue=Math.max(20,Math.min(100,opacity.getProgress()+20));
        GradientDrawable shape=new GradientDrawable();shape.setColor(((alphaValue*255/100)<<24)|OverlayController.BG_PRESETS[index]);shape.setCornerRadius(ui.dp(12));
        sample.setBackground(shape);sample.setTextColor(android.graphics.Color.WHITE);sample.setTextSize(sp);sample.setMinLines(1);sample.setMaxLines(count);
        StringBuilder text=new StringBuilder(UiText.choose("DSHA · 样式预览\n","DSHA · Style preview\n"));
        if(reasoning!=null&&reasoning.isChecked())text.append(UiText.choose("正在思考：先检查文件内容。\n","Thinking: checking the files first.\n"));
        if(commands!=null&&commands.isChecked())text.append(UiText.choose("正在执行命令：ls -la\n","Running command: ls -la\n"));
        text.append(UiText.choose("文件已整理完成，可以继续下一步。","Files are ready; continue to the next step."));
        sample.setText(text.toString());
        opacityLabel.setText(UiText.choose("不透明度","Opacity")+"  "+alphaValue+"%  ·  "+count+UiText.choose(" 行"," lines")+"  ·  "+sp+"sp");
        if(livePreview) OverlayController.showStylePreview(requireContext(),index,alphaValue,count,sp,reasoning!=null&&reasoning.isChecked(),commands!=null&&commands.isChecked());
    }
    private void renderPermission(){
        if(permission==null||getContext()==null)return;
        permission.setText(OverlayController.permitted(requireContext())
                ?UiText.choose("悬浮窗权限已开启","Overlay permission is enabled")
                :UiText.choose("需要悬浮窗权限，点击“预览悬浮条”即可授权。","Overlay permission is required; tap “Preview floating status” to grant it."));
    }
    private void openOverlayPermission(){
        try{startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+requireContext().getPackageName())));}
        catch(RuntimeException unavailable){
            try{startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+requireContext().getPackageName())));}
            catch(RuntimeException ignored){Toast.makeText(requireContext(),UiText.choose("请在系统设置中允许 DSHA 显示在其他应用上层。","Allow DSHA to display over other apps in system settings."),Toast.LENGTH_LONG).show();}
        }
    }
    @Override public void onResume(){
        super.onResume();
        renderPermission();
        if(livePreview&&OverlayController.permitted(requireContext()))paint();
    }
    private void save(){
        boolean on=enabled.isChecked();
        requireContext().getSharedPreferences(Constants.PREFS,0).edit().putBoolean(OverlayController.K_ENABLED,on).putInt(OverlayController.K_ALPHA,opacity.getProgress()+20).putInt(OverlayController.K_LINES,lines.getSelectedItemPosition()+1).putInt(OverlayController.K_HOLD,seconds()).putInt(OverlayController.K_BG,background.getSelectedItemPosition()).putInt(OverlayController.K_TEXT_SP,font.getProgress()+6).putBoolean(OverlayController.K_REASONING,reasoning.isChecked()).putBoolean(OverlayController.K_COMMAND,commands.isChecked()).putBoolean(OverlayController.K_CONFIRM,confirmation.isChecked()).apply();
        if(!on)OverlayController.teardown(requireContext().getApplicationContext());else OverlayController.applyStyleNow(requireContext().getApplicationContext());
        livePreview=false;OverlayController.hideStylePreview(requireContext().getApplicationContext());
        Toast.makeText(requireContext(),UiText.choose("悬浮条设置已保存","Overlay settings saved"),Toast.LENGTH_SHORT).show();
        if(on&&!OverlayController.permitted(requireContext()))openOverlayPermission();
    }
    @Override public void onDestroyView(){
        livePreview=false;
        if(getContext()!=null)OverlayController.hideStylePreview(requireContext().getApplicationContext());
        super.onDestroyView();
    }
    @Override public void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);if(opacity==null)return;out.putBoolean("enabled",enabled.isChecked());out.putInt("alpha",opacity.getProgress()+20);out.putInt("lines",lines.getSelectedItemPosition()+1);out.putInt("hold",seconds());out.putInt("background",background.getSelectedItemPosition());out.putInt("font",font.getProgress()+6);out.putBoolean("reasoning",reasoning.isChecked());out.putBoolean("commands",commands.isChecked());out.putBoolean("confirmation",confirmation.isChecked());}
}
