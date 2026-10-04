---
name: device-shell
description: 在 DSHA 的 Ubuntu 环境中使用应用管理的设备通道执行明确授权的 Android 操作，核对实际身份和结果。
---

# 手机命令操作

版本从实际界面或发布清单读取，不依赖旧 rc 编号。Ubuntu 终端操作guest，设备操作使用「设备能力授权」中已启用并获授权的 Root、Shizuku 或 ADB 通道，由原生层在发送前选择。不要要求先配 ADB 才能使用已授权 Root/Shizuku，不用裸 adb 绕过入口。

先做只读核验：

```sh
/root/dsh-bin/adb-shell "id"
/root/dsh-bin/adb-shell "getprop ro.product.model"
/root/dsh-bin/adb-shell "getprop ro.build.version.release"
```

按 id 的实际结果记录身份，不能假设总是 uid=2000 或 Android Root。连接未就绪时查看原生通道状态；错误、拒绝或结果未知时不换通道重放。

普通 guest shell 的开放权限不代表可以绕过设备命令执行侧策略和敏感能力授权。不要用文本替换逃避限制。

确需调用本机桥时，凭据从受管私有请求头文件读取，不放 URL、命令参数、剪贴板或公开日志：

```sh
curl --fail --silent --show-error --header '@/root/.dsh/.bridge_headers' \
  --get 'http://127.0.0.1:3090/exec' --data-urlencode 'cmd=id'
```

请求头文件缺失时先修复应用运行状态，不打印 token 排查。桥响应失败、取消和未知结果分别处理，不把空输出当成功。执行前明确目标设备和用户目标，执行后用只读查询核对效果。短信、屏幕等能力采用各自授权和运行代次，不从备份恢复设备许可。

没有 Termux 通道、termux-dialog 或破坏性命令包装承诺。guest 与宿主同 Android UID，proot不是隔离恶意插件的独立安全边界。
