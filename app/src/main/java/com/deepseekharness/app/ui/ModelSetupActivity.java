package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.DshModelRepository;
import com.deepseekharness.app.util.ModelConfiguration;
import com.deepseekharness.app.util.UiText;
import com.google.gson.*;
import java.util.*;

/** 首次配置与快捷入口共用原生编辑器，读写 DSH 的实际配置。 */
public final class ModelSetupActivity extends AppCompatActivity {
    private DshModelRepository repository;private Draft draft;private CardPage page;
    private LinearLayout body,models;private TextView status;private Button save,fetchModels;
    private EditText route,name,endpoint,key;private DshaSelectView protocol;
    private LinearLayout headersCard;private final List<EditText[]> headerFields=new ArrayList<>();
    private List<String> protocols=List.of();private JsonObject current;private long observedSave;
    public static final class Draft extends ViewModel {
        JsonObject entry,original,value,extra;JsonArray modelList,headers;long revision,generation,handledDiscovery;boolean custom;
        String route="",name="",endpoint="",protocol="",key="";
        boolean shouldShowDiscovery(long serial){return entry!=null&&serial>handledDiscovery;}
        void consumeDiscovery(long serial){handledDiscovery=Math.max(handledDiscovery,serial);}
        void clear(){entry=null;original=null;value=null;extra=null;modelList=null;headers=null;key="";}
        @Override protected void onCleared(){key="";}
    }
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);repository=new ViewModelProvider(this).get(DshModelRepository.class);draft=new ViewModelProvider(this).get(Draft.class);
        observedSave=repository.savedRevision.getValue()==null?0:repository.savedRevision.getValue();current=repository.data.getValue();
        page=new CardPage(this,t("模型配置","Model settings"),t("服务商、连接方式与模型目录。保存后与 Web UI 同步。","Providers, connections and models. Saved settings sync with Web UI."));
        UiNavigation.addHeader(this,page.root,t("模型配置","Model settings"),this::leave);
        status=page.text("",12,R.color.text_secondary);status.setPadding(0,0,0,page.dp(14));page.content.addView(status);
        body=page.column();page.content.addView(body);page.footer.setVisibility(View.GONE);setContentView(page.root);
        repository.message.observe(this,status::setText);repository.busy.observe(this,busy->{if(save!=null)save.setEnabled(!busy);if(fetchModels!=null)fetchModels.setEnabled(!busy);});
        repository.data.observe(this,data->{current=data;if(draft.entry==null)showDirectory();});
        repository.modelDiscovery.observe(this,result->{if(result!=null&&draft.shouldShowDiscovery(result.serial))showDiscoveredModels(result);});
        repository.savedRevision.observe(this,revision->{if(revision>observedSave){observedSave=revision;draft.clear();showDirectory();}});
        getOnBackPressedDispatcher().addCallback(this,new androidx.activity.OnBackPressedCallback(true){public void handleOnBackPressed(){leave();}});
        if(draft.entry!=null&&current!=null)showEditor();else if(current==null){page.button(body,t("重新连接","Reconnect"),false,repository::load);repository.load();}
    }
    private void leave(){
        if(Boolean.TRUE.equals(repository.busy.getValue())&&draft.entry!=null){status.setText(t("正在处理，请等待结果。","Working. Please wait for the result."));return;}
        if(draft.entry!=null){capture();new DshaDialogBuilder(this).setTitle(t("放弃本次编辑？","Discard these edits?"))
                .setNegativeButton(t("继续编辑","Keep editing"),null).setPositiveButton(t("放弃","Discard"),(d,w)->{draft.clear();showDirectory();}).show();return;}
        if(getIntent().getBooleanExtra("first_run",false))startActivity(new Intent(this,OnboardingReadyActivity.class));finish();
    }
    private JsonObject namespace(String name){
        if(current!=null)for(JsonElement item:current.getAsJsonObject("settings").getAsJsonArray("namespaces"))if(name.equals(s(item.getAsJsonObject(),"ns")))return item.getAsJsonObject();
        return new JsonObject();
    }
    private void clearBody(){body.removeAllViews();page.footer.removeAllViews();page.footer.setVisibility(View.GONE);save=null;fetchModels=null;route=null;name=null;endpoint=null;key=null;protocol=null;headerFields.clear();headersCard=null;}
    private LinearLayout card(String title){LinearLayout c=page.column();c.setPadding(page.dp(16),page.dp(14),page.dp(16),page.dp(14));c.setBackgroundResource(R.drawable.bg_card);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=page.dp(12);body.addView(c,p);if(!title.isEmpty()){TextView heading=page.text(title,16,R.color.text);heading.setTypeface(null,android.graphics.Typeface.BOLD);heading.setPadding(0,0,0,page.dp(8));c.addView(heading);}return c;}
    private void showDirectory(){
        if(current==null)return;clearBody();boolean writable=current.getAsJsonObject("settings").get("writable").getAsBoolean();
        LinearLayout configured=card(t("已配置的服务商","Configured providers"));
        for(JsonElement item:current.getAsJsonArray("providers")){
            JsonObject entry=item.getAsJsonObject(),ns=namespace(s(entry,"settingsNs"));JsonArray path=entry.getAsJsonArray("settingsPath");
            JsonElement value=ModelConfiguration.at(ns.get("value"),path);if(path.size()>0&&value.isJsonNull())continue;
            String title=s(entry,"displayName");if(title.isEmpty())title=s(entry,"provider");JsonObject profile=ModelConfiguration.object(value);
            String detail=s(profile,"baseURL");if(detail.isEmpty())detail=t("使用服务商默认地址","Uses provider default endpoint");
            page.entry(configured,title,detail,R.drawable.ic_ui_link,()->{if(writable)open(entry,false);});
        }
        LinearLayout add=card("");
        page.entry(add,t("添加第三方模型","Add third-party models"),t("填写服务商 API 地址、密钥与模型 ID，支持 OpenAI / Anthropic 等协议","Enter an endpoint, API key and model ID. Supports OpenAI, Anthropic and other protocols"),R.drawable.ic_ui_link,()->{if(writable)openCustom();});
        page.entry(add,t("选择内置服务商","Choose a built-in provider"),t("使用 DSH 已支持的服务商配置","Use a provider supported by DSH"),R.drawable.ic_ui2_box,()->{if(writable)chooseProvider();});
        page.button(body,t("重新读取配置","Reload settings"),false,repository::load);
        if(getIntent().getBooleanExtra("first_run",false)){page.footer.setVisibility(View.VISIBLE);page.button(page.footer,t("继续进入 DSHA","Continue to DSHA"),true,this::leave);}
    }
    private void chooseProvider(){
        CardSheet.choices(this,t("添加服务商","Add a provider"),()->{
            List<String> values=new ArrayList<>();values.add(t("自定义提供方","Custom provider"));for(JsonElement item:current.getAsJsonArray("providers"))values.add(s(item.getAsJsonObject(),"displayName"));return values;
        },index->{if(index==0){openCustom();}else open(current.getAsJsonArray("providers").get(index-1).getAsJsonObject(),false);});
    }
    private void openCustom(){
        JsonObject entry=new JsonObject();entry.addProperty("provider","");entry.addProperty("settingsNs","llm-pi-ai");entry.add("settingsPath",ModelConfiguration.path("providers",""));open(entry,true);
        if(draft.entry!=null&&draft.custom){draft.protocol="openai-completions";showEditor();}
    }
    private void open(JsonObject entry,boolean custom){
        JsonObject ns=namespace(s(entry,"settingsNs"));if(!ns.has("revision")){status.setText(t("此服务商组件尚不可用，请检查插件。","Provider component unavailable. Check plugins."));return;}
        draft.entry=entry.deepCopy();draft.custom=custom;draft.revision=ns.get("revision").getAsLong();draft.generation=current.get("generation").getAsLong();draft.extra=null;
        JsonArray path=entry.getAsJsonArray("settingsPath");draft.original=ModelConfiguration.object(ModelConfiguration.at(ns.get("user"),path)).deepCopy();draft.value=ModelConfiguration.object(ModelConfiguration.at(ns.get("value"),path)).deepCopy();
        draft.route=s(entry,"provider");draft.name=s(draft.value,"displayName");draft.endpoint=s(draft.value,"baseURL");draft.protocol=s(draft.value,deepseek()?"protocol":"api");draft.key="";
        draft.headers=ModelConfiguration.headerRows(ModelConfiguration.object(draft.value.get("headers")));
        status.setText(t("编辑完成后，点击下方保存。","Save below when your edits are ready."));
        draft.modelList=draft.value.has("models")?draft.value.getAsJsonArray("models").deepCopy():new JsonArray();showEditor();
    }
    private boolean deepseek(){return "llm-deepseek".equals(s(draft.entry,"settingsNs"));}
    private void showEditor(){
        clearBody();LinearLayout connection=card(draft.custom?t("自定义提供方","Custom provider"):s(draft.entry,"displayName"));
        if(draft.custom)route=field(connection,t("唯一标识（小写英文）","Provider ID (lowercase)"),"my-provider",draft.route,false);
        if(!deepseek())name=field(connection,t("显示名称","Display name"),t("可选","Optional"),draft.name,false);
        endpoint=field(connection,t("API 地址","API endpoint"),deepseek()?t("留空使用 DeepSeek 官方地址","Leave blank for the official DeepSeek endpoint"):"https://api.example.com/v1",draft.endpoint,false);
        JsonArray protocolPath=draft.entry.getAsJsonArray("settingsPath").deepCopy();protocolPath.add(deepseek()?"protocol":"api");
        protocols=new ArrayList<>();protocols.add(t("服务商默认","Provider default"));protocols.addAll(ModelConfiguration.choices(namespace(s(draft.entry,"settingsNs")).getAsJsonObject("schema"),protocolPath));
        if(!draft.protocol.isEmpty()&&!protocols.contains(draft.protocol))protocols.add(draft.protocol);
        label(connection,t("连接协议","Protocol"));protocol=new DshaSelectView(this);protocol.setPrompt(t("连接协议","Protocol"));protocol.setAdapter(new ArrayAdapter<>(this,R.layout.item_data_choice,protocols));protocol.setSelection(Math.max(0,protocols.indexOf(draft.protocol)));connection.addView(protocol,new LinearLayout.LayoutParams(-1,page.dp(50)));
        key=field(connection,"API Key",t("留空保留已有密钥","Leave blank to keep the existing key"),draft.key,true);
        TextView keyHint=page.text(t("密钥不会在此回显。新密钥通过 DSH 凭据服务保存。","Existing keys are never shown here. New keys are saved through DSH credentials."),11,R.color.text_muted);keyHint.setPadding(0,page.dp(8),0,0);connection.addView(keyHint);
        if(!deepseek()){headersCard=card(t("自定义请求头","Custom request headers"));renderHeaders();}
        models=card(t("模型目录","Models"));renderModels();page.button(body,t("高级配置","Advanced settings"),false,this::advanced);
        page.footer.setVisibility(View.VISIBLE);save=page.button(page.footer,t("保存并同步","Save and sync"),true,this::save);save.setEnabled(!Boolean.TRUE.equals(repository.busy.getValue()));
    }
    private void label(LinearLayout parent,String title){TextView view=page.text(title,12,R.color.text_secondary);view.setPadding(0,page.dp(12),0,page.dp(7));parent.addView(view);}
    private EditText field(LinearLayout parent,String title,String hint,String value,boolean secret){label(parent,title);EditText input=new EditText(this);input.setTextSize(14);input.setTextColor(getColor(R.color.text));input.setHintTextColor(getColor(R.color.text_muted));input.setBackgroundResource(R.drawable.bg_input);input.setPadding(page.dp(12),page.dp(12),page.dp(12),page.dp(12));input.setSingleLine(true);input.setMinHeight(page.dp(48));input.setInputType(InputType.TYPE_CLASS_TEXT|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));input.setSaveEnabled(false);if(android.os.Build.VERSION.SDK_INT>=26)input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);input.setHint(hint);input.setText(value);parent.addView(input,new LinearLayout.LayoutParams(-1,-2));return input;}
    private void capture(){if(draft.entry==null||endpoint==null)return;if(route!=null)draft.route=route.getText().toString().trim();if(name!=null)draft.name=name.getText().toString().trim();draft.endpoint=endpoint.getText().toString().trim();draft.protocol=protocol.getSelectedItemPosition()==0?"":protocols.get(protocol.getSelectedItemPosition());draft.key=key.getText().toString().trim();captureHeaders();}
    private void captureHeaders(){
        if(headersCard==null)return;JsonArray rows=new JsonArray();for(EditText[] fields:headerFields){JsonObject row=new JsonObject();row.addProperty("name",fields[0].getText().toString());row.addProperty("value",fields[1].getText().toString());rows.add(row);}draft.headers=rows;
    }
    private void renderHeaders(){
        while(headersCard.getChildCount()>1)headersCard.removeViewAt(1);headerFields.clear();
        for(int i=0;i<draft.headers.size();i++){
            int index=i;JsonObject entry=draft.headers.get(i).getAsJsonObject();
            EditText headerName=field(headersCard,t("名称","Name"),"x-opencode-session",s(entry,"name"),false);
            EditText headerValue=field(headersCard,t("值","Value"),t("服务商要求的值","Value required by the provider"),s(entry,"value"),true);
            headerFields.add(new EditText[]{headerName,headerValue});
            page.button(headersCard,t("移除此请求头","Remove header"),false,()->{captureHeaders();draft.headers.remove(index);renderHeaders();});
        }
        page.button(headersCard,t("添加请求头","Add header"),false,()->{captureHeaders();if(draft.headers.size()>=32)return;draft.headers.add(new JsonObject());renderHeaders();});
        headersCard.addView(page.text(t("仅发送给此服务商；保存后用于模型请求和目录查询。","Sent only to this provider. Save to apply to model requests and model discovery."),11,R.color.text_muted));
    }
    private void renderModels(){
        while(models.getChildCount()>1)models.removeViewAt(1);
        for(int i=0;i<draft.modelList.size();i++){int index=i;JsonObject model=draft.modelList.get(i).getAsJsonObject();page.entry(models,s(model,"id"),s(model,"name").isEmpty()?t("编辑模型与容量","Edit model and capacity"):s(model,"name"),R.drawable.ic_ui2_box,()->editModel(index));}
        if("llm-pi-ai".equals(s(draft.entry,"settingsNs"))){fetchModels=page.button(models,t("获取可用模型","Fetch available models"),false,this::fetchModels);fetchModels.setEnabled(!Boolean.TRUE.equals(repository.busy.getValue()));}
        page.button(models,t("添加模型","Add model"),false,()->editModel(-1));models.addView(page.text(t("不修改目录会保留服务商原有模型和能力。","Unchanged catalogs retain the provider's models and capabilities."),11,R.color.text_muted));
    }
    private void fetchModels(){
        capture();try{
            if(draft.endpoint.isEmpty()&&draft.route.isEmpty())throw new IllegalArgumentException("URL");ModelConfiguration.validateUrl(draft.endpoint,false);
            if(!draft.key.isEmpty()&&!draft.key.matches("[\\x21-\\x7E]+"))throw new IllegalArgumentException("KEY");
            repository.discoverModels(s(draft.entry,"settingsNs"),draft.route,draft.endpoint,draft.protocol,draft.key);
        }catch(RuntimeException invalid){String code=String.valueOf(invalid.getMessage());status.setText(code.equals("KEY")?t("API Key 不应包含空格或换行。","API keys must not contain whitespace."):t("请先填写有效的 HTTP/HTTPS API 地址。","Enter a valid HTTP/HTTPS endpoint first."));}
    }
    private void showDiscoveredModels(DshModelRepository.ModelDiscovery result){
        long serial=result.serial;JsonArray found=result.models;
        if(found==null||found.isEmpty()){new DshaDialogBuilder(this).setTitle(t("未发现模型","No models found")).setMessage(t("服务商没有返回可用模型。现有模型目录保持不变。","The provider returned no available models. The current catalog is unchanged."))
                .setPositiveButton(t("关闭","Close"),(dialog,which)->draft.consumeDiscovery(serial))
                .setOnCancelListener(dialog->{if(!isChangingConfigurations())draft.consumeDiscovery(serial);}).show();return;}
        Set<String> known=new HashSet<>();for(JsonElement item:draft.modelList)if(item.isJsonObject())known.add(s(item.getAsJsonObject(),"id"));
        CharSequence[] labels=new CharSequence[found.size()];boolean[] selected=new boolean[found.size()];
        for(int i=0;i<found.size();i++){JsonObject candidate=found.get(i).getAsJsonObject();String id=s(candidate,"id"),display=s(candidate,"name");boolean exists=known.contains(id);selected[i]=true;labels[i]=id+(display.isEmpty()||display.equals(id)?"":" · "+display)+(exists?t("（已在目录）"," (already in catalog)"):"");}
        new DshaDialogBuilder(this).setTitle(t("选择可用模型","Choose available models"))
                .setMultiChoiceItems(labels,selected,(dialog,which,on)->selected[which]=on)
                .setNegativeButton(t("取消","Cancel"),(dialog,which)->draft.consumeDiscovery(serial))
                .setPositiveButton(t("合并所选模型","Add selected models"),(dialog,which)->{
                    Set<String> picked=new LinkedHashSet<>();for(int i=0;i<found.size();i++)if(selected[i])picked.add(s(found.get(i).getAsJsonObject(),"id"));
                    int before=draft.modelList.size();draft.modelList=ModelConfiguration.mergeDiscoveredModels(draft.modelList,found,picked);renderModels();int added=draft.modelList.size()-before;
                    draft.consumeDiscovery(serial);
                    status.setText(added==0?t("所选模型已在目录中，现有详情保持不变。","The selected models are already in the catalog. Existing details were preserved."):t("已将 ","Added ")+added+t(" 个模型合并到草稿；保存后同步到 Web UI。"," models to the draft. Save to sync them with Web UI."));
                }).setOnCancelListener(dialog->{if(!isChangingConfigurations())draft.consumeDiscovery(serial);}).show();
    }
    private void editModel(int index){
        JsonObject original=index<0?new JsonObject():draft.modelList.get(index).getAsJsonObject().deepCopy();
        CardPage editor=new CardPage(this,t("模型详情","Model details"),"");LinearLayout c=editor.card();
        EditText id=field(c,"模型 ID / Model ID","model-name",s(original,"id"),false),display=field(c,t("显示名称","Display name"),t("可选","Optional"),s(original,"name"),false);
        EditText context=field(c,t("上下文窗口","Context window"),t("留空使用默认值","Leave blank for default"),s(original,"contextWindow"),false),output=field(c,t("最大输出 Token","Maximum output tokens"),t("留空使用默认值","Leave blank for default"),s(original,"maxTokens"),false);
        context.setInputType(InputType.TYPE_CLASS_NUMBER);output.setInputType(InputType.TYPE_CLASS_NUMBER);
        CheckBox vision=new CheckBox(this);vision.setText(t("支持图片输入","Supports image input"));String inputField=deepseek()?"inputModalities":"input";
        vision.setChecked(original.has(inputField)&&original.get(inputField).toString().contains("\"image\""));c.addView(vision);
        TextView error=editor.text("",12,R.color.err);editor.content.addView(error);var dialog=CardSheet.create(this,editor);
        editor.button(editor.footer,t("保存模型","Save model"),true,()->{try{
            JsonObject next=original.deepCopy();next.addProperty("id",id.getText().toString().trim());setOptional(next,"name",display.getText().toString().trim());
            for(Object[] entry:new Object[][]{{"contextWindow",context},{"maxTokens",output}}){String value=((EditText)entry[1]).getText().toString().trim();if(value.isEmpty())next.remove((String)entry[0]);else next.addProperty((String)entry[0],Long.parseLong(value));}
            boolean prior=original.has(inputField)&&original.get(inputField).toString().contains("\"image\"");if(vision.isChecked()!=prior||index<0)next.add(inputField,vision.isChecked()?ModelConfiguration.path("text","image"):ModelConfiguration.path("text"));
            JsonArray checked=draft.modelList.deepCopy();if(index<0)checked.add(next);else checked.set(index,next);ModelConfiguration.validateModels(checked.toString(),true);draft.modelList=checked;renderModels();dialog.dismiss();
        }catch(RuntimeException invalid){error.setText(t("请检查模型 ID、重复项和正整数容量。","Check model ID, duplicates and positive integer capacities."));}});
        if(index>=0)editor.button(editor.footer,t("从目录移除此模型","Remove from catalog"),false,()->{draft.modelList.remove(index);renderModels();dialog.dismiss();});CardSheet.show(dialog,this);
    }
    private void advanced(){
        capture();CardPage editor=new CardPage(this,t("高级配置","Advanced settings"),t("只编辑此服务商的附加字段；模型目录和密钥由主表单管理。","Edit additional provider fields. Models and credentials use the main form."));
        JsonObject extra=draft.extra!=null?draft.extra.deepCopy():draft.original.deepCopy();for(String k:List.of("baseURL","api","protocol","displayName","apiKeyEnv","models","headers"))extra.remove(k);
        EditText json=field(editor.content,"JSON","{}",new GsonBuilder().setPrettyPrinting().create().toJson(extra),false);json.setSingleLine(false);json.setMinLines(6);json.setGravity(Gravity.TOP);json.setTypeface(android.graphics.Typeface.MONOSPACE);
        TextView error=editor.text("",12,R.color.err);editor.content.addView(error);var dialog=CardSheet.create(this,editor);
        editor.button(editor.footer,t("应用到草稿","Apply to draft"),true,()->{try{JsonObject value=JsonParser.parseString(json.getText().toString()).getAsJsonObject();for(String k:List.of("baseURL","api","protocol","displayName","apiKeyEnv","models","headers"))if(value.has(k))throw new IllegalArgumentException();draft.extra=value;dialog.dismiss();}catch(RuntimeException invalid){error.setText(t("请输入 JSON 对象，连接、请求头、模型和密钥请在主表单修改。","Enter a JSON object. Edit connection, headers, models and credentials in the main form."));}});CardSheet.show(dialog,this);
    }
    private void save(){
        capture();try{
            if(draft.custom){ModelConfiguration.validateRoute(draft.route);for(JsonElement entry:current.getAsJsonArray("providers"))if(draft.route.equals(s(entry.getAsJsonObject(),"provider")))throw new IllegalArgumentException("ROUTE_TAKEN");}
            ModelConfiguration.validateUrl(draft.endpoint,draft.custom);ModelConfiguration.validateModels(draft.modelList.toString(),draft.custom);
            if(!draft.key.isEmpty()&&!draft.key.matches("[\\x21-\\x7E]+"))throw new IllegalArgumentException("KEY");if(draft.custom&&draft.protocol.isEmpty())throw new IllegalArgumentException("PROTOCOL");
            JsonObject next=draft.original.deepCopy();if(draft.extra!=null){for(String k:new ArrayList<>(next.keySet()))if(!List.of("baseURL","api","protocol","displayName","apiKeyEnv","models","headers").contains(k))next.remove(k);draft.extra.entrySet().forEach(e->next.add(e.getKey(),e.getValue()));}
            if(!deepseek()){JsonObject headers=ModelConfiguration.headers(draft.headers);if(!headers.equals(ModelConfiguration.object(draft.value.get("headers"))))next.add("headers",headers);}
            updateField(next,"baseURL",draft.endpoint);updateField(next,deepseek()?"protocol":"api",draft.protocol);if(!deepseek())updateField(next,"displayName",draft.name);
            if(draft.custom||!Objects.equals(draft.value.get("models"),draft.modelList)&&(draft.value.has("models")||!draft.modelList.isEmpty()))next.add("models",draft.modelList.deepCopy());
            String ref=s(draft.value,"apiKeyEnv");if(!draft.key.isEmpty()){ref=ModelConfiguration.keyReference(draft.route);next.addProperty("apiKeyEnv",ref);}
            JsonArray path=draft.custom?ModelConfiguration.path("providers",draft.route):draft.entry.getAsJsonArray("settingsPath");JsonArray ops=ModelConfiguration.diff(path,draft.original,next);
            if(draft.custom||ops.isEmpty()&&!deepseek()&&ModelConfiguration.at(namespace(s(draft.entry,"settingsNs")).get("value"),path).isJsonNull()){JsonObject op=new JsonObject();op.addProperty("op","set");op.add("path",path);op.add("value",next);ops=new JsonArray();ops.add(op);}
            repository.save(s(draft.entry,"settingsNs"),ops,draft.revision,draft.generation,ref,draft.key);
        }catch(RuntimeException invalid){String code=String.valueOf(invalid.getMessage());status.setText(code.startsWith("HEADERS")?t("请检查请求头：名称不能重复，值不能包含换行，不可覆盖连接与传输字段。","Check headers: unique names, no line breaks, and no connection or transport fields."):code.startsWith("ROUTE")?t("提供方标识需以小写字母开头，用短横线连接，且不能重复。","Provider IDs must start with a lowercase letter, use hyphens and be unique."):code.equals("URL")?t("请填写有效的 HTTP/HTTPS API 地址。","Enter a valid HTTP/HTTPS endpoint."):code.equals("KEY")?t("API Key 不应包含空格或换行。","API keys must not contain whitespace."):code.equals("PROTOCOL")?t("请选择连接协议。","Select a protocol."):t("请检查模型目录：ID 不能重复，容量必须是正整数。","Check models: unique IDs and positive integer capacities are required."));}
    }
    private void updateField(JsonObject next,String key,String value){if(!value.equals(s(draft.value,key)))setOptional(next,key,value);}
    private static void setOptional(JsonObject object,String key,String value){if(value.isEmpty())object.remove(key);else object.addProperty(key,value);}
    private static String s(JsonObject object,String key){return ModelConfiguration.text(object,key);}
    private static String t(String zh,String en){return UiText.choose(zh,en);}
    @Override protected void onPause(){capture();super.onPause();}
}
