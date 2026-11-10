# DSHA 0.2.1-rc.5-20261106.0130-dsh0.2.2-rc.1 / build329

- 新增 GitHub 登录原生页（T71）：显示容器 `hosts.yml` 里的账号、作用域与来源，标明受管 `gh` 是否可用，并用只读 `gh api -i /user` 区分 CA/TLS 失败、token 401、未登录、网络与超时；提供登录、重新授权与退出。页面本身不显示 token。已知缺口：token 仍保存在容器 `hosts.yml`，同 UID 的网页与插件可读，隔离另立 T50，不在本批。
- 修复 `gh` 的 CA 查找（T73）：`RuntimeTools` 改用 canonical 宿主根设置 `DSHA_GH_HOST_ROOT`，`gh` 包装脚本在 `/data/user/0` 与 `/data/data` 两个数据视图查找受管 CA；两处都缺或 `DSHA_GH_CA` 指向缺失文件时明确报错退出，不再静默回退到缺新根证书的旧系统 CA。固定输入不重建。
- 引导第 4 步底栏统一（T72）：欢迎流程完成页改用与运行环境维护页相同的 `ExtractFooter` 紧凑两行布局，更新中显示 x/5，恢复主操作位，就绪后进入主界面。
- 验证范围：宿主测试、contracts 整组、全树格式检查与定向单测（更新页、GitHub 登录、gh 宿主根、引导页）；本机未做真机验收。版本码提升为 329（基于 build328），DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.2-rc.1。
