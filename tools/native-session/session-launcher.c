#include <errno.h>
#include <stdio.h>
#include <unistd.h>

/* 冷安装监督器：不依赖 ROM 是否提供 setsid 命令；guest 在 Java 身份握手后才启动。 */
int main(int argc, char **argv) {
    if (argc < 2) { fputs("DSHA_SESSION_USAGE\n", stderr); return 125; }
    if (setsid() < 0) { perror("DSHA_SESSION_SETSID"); return 125; }
    execvp(argv[1], argv + 1);
    int error = errno;
    perror("DSHA_SESSION_EXEC");
    return error == ENOENT ? 127 : 126;
}
