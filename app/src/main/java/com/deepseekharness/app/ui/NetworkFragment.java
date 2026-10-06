package com.deepseekharness.app.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.ConfigInput;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.UiText;

import java.util.concurrent.atomic.AtomicInteger;

/** 网络设置子页：首选 Web 端口、局域网访问与 DNS 解析策略。 */
public class NetworkFragment extends Fragment {
  private static final String[] DNS_MODES = {"auto", "ipv4", "native"};

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    View v = inflater.inflate(R.layout.fragment_network, container, false);
    Context ctx = requireContext();
    ConfigStore c = new ConfigStore(ctx);
    EditText port = v.findViewById(R.id.net_port);
    CompoundButton lan = v.findViewById(R.id.net_lan);
    Button save = v.findViewById(R.id.net_save);

    port.setText(c.getPort());
    lan.setChecked(c.isLanMode());

    AtomicInteger dnsIndex =
        new AtomicInteger(
            "ipv4".equals(c.getDnsMode()) ? 1 : "native".equals(c.getDnsMode()) ? 2 : 0);
    bindChoice(
        v,
        R.id.net_dns_choice,
        UiText.choose("DNS 解析策略", "DNS policy"),
        new String[] {
          UiText.choose("自动（推荐）", "Automatic (recommended)"), "IPv4", UiText.choose("原生", "Native")
        },
        dnsIndex.get(),
        dnsIndex::set);

    Runnable validateInputs =
        () -> {
          try {
            ConfigInput.port(port.getText().toString());
            save.setEnabled(true);
          } catch (IllegalArgumentException invalid) {
            save.setEnabled(false);
          }
        };
    port.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            validateInputs.run();
          }

          public void afterTextChanged(Editable text) {}
        });
    validateInputs.run();

    save.setOnClickListener(
        x -> {
          final int chosenPort;
          try {
            chosenPort = ConfigInput.port(port.getText().toString());
          } catch (IllegalArgumentException e) {
            port.setError(e.getMessage());
            port.requestFocus();
            return;
          }
          port.setError(null);
          EnvironmentTaskGate.Lease saving = EnvironmentTaskGate.tryAcquire(UiText.text("保存配置"));
          if (saving == null) {
            toast(UiText.format("正在%s，完成后再保存配置", EnvironmentTaskGate.activeKind()));
            return;
          }
          try {
            saving.run(
                () -> {
                  boolean lanOn = lan.isChecked();
                  c.setPort(String.valueOf(chosenPort));
                  c.setLanMode(lanOn);
                  c.setDnsMode(DNS_MODES[dnsIndex.get()]);
                  applyLanMode(c, lanOn);
                  if (lanOn && getActivity() instanceof MainActivity)
                    ((MainActivity) getActivity()).requestLocalNetwork();
                  Context context = getContext();
                  if (context != null)
                    Toast.makeText(
                            context,
                            UiText.choose(
                                "已保存；端口与局域网需重启 Web 生效",
                                "Saved. Restart Web for port and LAN changes."),
                            Toast.LENGTH_LONG)
                        .show();
                  return null;
                });
          } catch (Exception e) {
            toast(UiText.format("配置保存未完成：%s", SensitiveData.redact(String.valueOf(e))));
          } finally {
            saving.close();
          }
        });
    return v;
  }

  /** LAN 开关真正生效：开启时若 dsh 已鉴权则启动 3081 代理，关闭时停掉监听。 */
  private void applyLanMode(ConfigStore c, boolean on) {
    try {
      if (!on) {
        com.deepseekharness.app.LanProxyService.stopLanListener();
        com.deepseekharness.app.HarnessService.refreshPowerMode(requireContext());
        return;
      }
      com.deepseekharness.app.HarnessService.ensureLanForeground(requireContext());
      HarnessController hc = HarnessController.get(requireContext());
      long gen = hc.getWebGeneration();
      if (gen <= 0 || !com.deepseekharness.app.LanProxyService.hasDshAuth(gen)) {
        // dsh 还没起来/还没交换 cookie：等下次进入对话时 HarnessController 自动启动
        return;
      }
      com.deepseekharness.app.LanProxyService.start(
          hc.proot().getRootfsDir().getAbsolutePath(), requireContext(), hc.getWebPort(), gen);
    } catch (Throwable t) {
      android.util.Log.w("DSHA", UiText.format("LAN 开关生效失败: %s", t.getMessage()));
    }
  }

  private void toast(String s) {
    Context context = getContext();
    if (context != null) Toast.makeText(context, s, Toast.LENGTH_SHORT).show();
  }

  private void bindChoice(
      View root,
      int id,
      String prompt,
      String[] labels,
      int selected,
      java.util.function.IntConsumer action) {
    DshaSelectView choice = root.findViewById(id);
    choice.setPrompt(prompt);
    choice.setAdapter(new ArrayAdapter<>(requireContext(), R.layout.item_data_choice, labels));
    choice.setSelection(selected);
    choice.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> parent) {}

          public void onItemSelected(AdapterView<?> parent, View view, int position, long item) {
            action.accept(position);
          }
        });
  }
}
