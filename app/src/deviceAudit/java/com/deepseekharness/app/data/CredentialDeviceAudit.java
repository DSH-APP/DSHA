package com.deepseekharness.app.data;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import com.deepseekharness.app.*;
import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.core.*;
import com.deepseekharness.app.ui.ConfigFragment;
import com.deepseekharness.app.util.*;
import java.io.*;
import java.util.*;

/** 同一真实设备 Keystore 的合成凭据往返；异常注入与真实加解密分别记录。 */
public final class CredentialDeviceAudit extends Instrumentation {
    private final List<Map<String,Object>> tests=new ArrayList<>();
    private void check(boolean value,String code)throws IOException{if(!value)throw new IOException(code);}
    private void pass(String name){tests.add(Map.of("name",name,"status","PASS"));Bundle value=new Bundle();value.putString("case",name);value.putString("status","PASS");sendStatus(1,value);}
    private interface Checked {void run()throws Exception;}
    private void ui(Checked action)throws Exception{Throwable[] failure={null};runOnMainSync(()->{try{action.run();}catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new IOException("UI_CHECK",failure[0]);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();SharedPreferences prefs=null;String original=null;boolean had=false;Activity screen=null;
        try{
            Context context=getTargetContext();DeviceAuditSupport.requireIsolated(context);prefs=context.getSharedPreferences(Constants.PREFS,Context.MODE_PRIVATE);
            had=prefs.contains(Constants.KEY_API_KEY);Object old=prefs.getAll().get(Constants.KEY_API_KEY);check(old==null||old instanceof String,"UNEXPECTED_EXISTING_TEST_RECORD");original=(String)old;
            check(prefs.edit().remove(Constants.KEY_API_KEY).commit(),"FIXTURE_SAVE");ConfigStore config=new ConfigStore(context);
            check(config.readApiKey().state==CredentialRead.State.NOT_CONFIGURED&&config.getApiKey().isEmpty(),"NOT_CONFIGURED_STATE");
            String key="owned-credential-fixture-"+UUID.randomUUID();check(config.saveApiKey(key),"REAL_KEYSTORE_ENCRYPT");String encrypted=prefs.getString(Constants.KEY_API_KEY,"");
            check(!encrypted.isEmpty()&&!encrypted.contains(key)&&key.equals(config.getApiKey()),"REAL_KEYSTORE_ROUND_TRIP");
            check(key.equals(new ConfigStore(context).getApiKey()),"REOPEN_EXISTING_CREDENTIAL");pass("real_keystore_round_trip_absence_and_reopened_storage");
            check(prefs.edit().putString(Constants.KEY_API_KEY,"invalid-owned-ciphertext").commit(),"FIXTURE_SAVE");
            check(config.readApiKey().state==CredentialRead.State.NEEDS_ATTENTION,"UNREADABLE_STATE");
            boolean rejected=false;try{config.getApiKey();}catch(CredentialRead.Unavailable expected){rejected=true;}check(rejected,"FAILURE_BECAME_EMPTY_KEY");
            rejected=false;try{HarnessController.get(context).runCoreCommand();}catch(CredentialRead.Unavailable expected){rejected=true;}check(rejected,"COMMAND_USED_EMPTY_CREDENTIAL");
            check(prefs.getString(Constants.KEY_API_KEY,"").equals("invalid-owned-ciphertext"),"UNREADABLE_RECORD_REMOVED");pass("real_unreadable_record_blocks_dependent_command_and_is_retained");
            check(prefs.edit().putString(Constants.KEY_API_KEY,encrypted).commit(),"FIXTURE_SAVE");
            var field=ConfigStore.class.getDeclaredField("vault");field.setAccessible(true);Object realVault=field.get(config);
            try{
                field.set(config,new KeyVault(context){@Override public String encrypt(String value){return "";}});
                check(!config.saveApiKey("owned replacement"),"SAVE_FAILURE_ACCEPTED");check(encrypted.equals(prefs.getString(Constants.KEY_API_KEY,"")),"SAVE_FAILURE_CHANGED_ORIGINAL");
                field.set(config,new KeyVault(context){@Override public CredentialRead read(String value){return CredentialRead.failed(CredentialRead.Reason.TRANSIENT_STORE);}});
                check(config.readApiKey().state==CredentialRead.State.TEMPORARILY_UNAVAILABLE,"TRANSIENT_STATE_LOST");
                rejected=false;try{config.getApiKey();}catch(CredentialRead.Unavailable expected){rejected=true;}check(rejected,"TRANSIENT_BECAME_EMPTY");
            }finally{field.set(config,realVault);}
            check(KeyVault.classify(new android.security.keystore.UserNotAuthenticatedException()).state==CredentialRead.State.TEMPORARILY_UNAVAILABLE,"AUTH_EXCEPTION_CLASSIFICATION");
            check(KeyVault.classify(new android.security.keystore.KeyPermanentlyInvalidatedException()).reason==CredentialRead.Reason.KEY_INVALIDATED,"INVALIDATED_CLASSIFICATION");
            check(KeyVault.classify(new javax.crypto.AEADBadTagException()).reason==CredentialRead.Reason.UNREADABLE,"TAG_ERROR_NOT_PROOF_OF_CORRUPTION");
            pass("injected_save_failure_transient_and_typed_platform_exceptions");
            check(prefs.edit().putString(Constants.KEY_API_KEY,"invalid-owned-ciphertext").commit(),"FIXTURE_SAVE");
            DeviceAuditActivity host=(DeviceAuditActivity)DeviceAuditSupport.open(this,DeviceAuditActivity.class);screen=host;
            ConfigFragment fragment=new ConfigFragment();ui(()->host.getSupportFragmentManager().beginTransaction().replace(android.R.id.content,fragment).commitNow());
            SharedPreferences storage=prefs;
            ui(()->{
                View view=fragment.requireView();check(view.findViewById(R.id.config_credential_state).getVisibility()==View.VISIBLE,"ERROR_UI_MISSING");
                ((Button)view.findViewById(R.id.config_save)).performClick();check(storage.getString(Constants.KEY_API_KEY,"").equals("invalid-owned-ciphertext"),"OTHER_SETTINGS_CLEARED_KEY");
                ((EditText)view.findViewById(R.id.config_api_key)).setText("owned replacement after review");((Button)view.findViewById(R.id.config_save)).performClick();
                check("owned replacement after review".equals(new ConfigStore(context).getApiKey()),"UI_REENTRY_FAILED");
                check(view.findViewById(R.id.config_credential_state).getVisibility()==View.GONE,"ERROR_UI_NOT_CLEARED");
            });pass("real_configuration_ui_preserves_unreadable_key_and_accepts_explicit_reentry");
        }catch(Throwable failure){result.putString("failure",SensitiveData.redact(android.util.Log.getStackTraceString(failure)));}
        finally{
            if(prefs!=null){SharedPreferences.Editor edit=prefs.edit();if(had)edit.putString(Constants.KEY_API_KEY,original);else edit.remove(Constants.KEY_API_KEY);if(!edit.commit())result.putString("cleanupFailure","ORIGINAL_PREF_RESTORE_FAILED");}
            if(screen!=null){Activity activity=screen;runOnMainSync(activity::finish);}
            try{result.putString("report",new String(BackupJson.write(Map.of("status",result.containsKey("failure")||result.containsKey("cleanupFailure")?"FAIL":"PASS","tests",tests,"flavor",BuildConfig.FLAVOR,"limitations","platform invalidation/transient exceptions are injected; no device lock or biometric configuration changed"),65536),java.nio.charset.StandardCharsets.UTF_8));}catch(IOException failure){result.putString("failure",failure.toString());}
            finish(result.containsKey("failure")||result.containsKey("cleanupFailure")?1:0,result);
        }
    }
}
