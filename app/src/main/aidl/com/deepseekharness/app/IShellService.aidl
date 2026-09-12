package com.deepseekharness.app;

interface IShellService {
    String exec(String cmd) = 0;
    // Shizuku 约定的销毁事务；升级或解绑时退出旧的特权服务进程。
    void destroy() = 16777114;
}
