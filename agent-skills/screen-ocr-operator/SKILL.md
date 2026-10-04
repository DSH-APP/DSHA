---
name: screen-ocr-operator
description: 使用 DSHA 已授权的原生读屏与截图工具定位界面，核对目标和坐标后执行用户要求的手机操作。
---

# 读屏与截图操作

使用实际应用所提供的 Android 工具与能力状态。通道可以是 Root、Shizuku 或 ADB，不假设身份为 uid=2000，不把guest root当Android Root。

先核对屏幕、方向、目标应用和授权。优先使用 mcp__dsha-android__android_screenshot 的原生 PNG 结果及当前读屏接口；能否使用取决于实际系统、服务和本次授权，不能从Standard/Low名称推导全设备支持。

根据图片和实际界面树定位后点击、滑动、输入。截图坐标、虚拟屏坐标和主屏必须对应；PiP、键盘、悬浮窗可能遮挡，不能只按旧 XML 坐标。目标不可核验或已改变时停止，不伪报完成。

活动配对或持续无障碍服务期间不使用uiautomator dump，避免抑制其它服务。授权绑定运行代次；停止、断连、重启或手动撤销后重新核对，不继承旧授权。

只读信息走当前受管入口：

```sh
/root/dsh-bin/adb-shell "id"
/root/dsh-bin/adb-shell "getprop ro.product.model"
```

不以裸adb或其它通道重放未知结果。敏感应用和确认窗口采用原生执行点限制，不替用户确认维护写入。

不把截图、密钥、配对码或对话写公开取证目录。临时图只清理本次工具明确创建的文件，不批量删除用户Pictures/Downloads/历史原件。核对结果后报告实际完成情况。
