#include "dsha-pty.h"
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* 子进程自读身份，不依赖发布版 fork/exec 窗口内父进程对 /proc 的读取权限。
 * 父进程接收并登记成功前不得 exec，因此握手失败时还没有任何 guest。 */
static int readable(int fd)
{
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return 0;
    long long end = now.tv_sec * 1000LL + now.tv_nsec / 1000000 + 3000;
    for (;;) {
        if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return 0;
        long long left = end - (now.tv_sec * 1000LL + now.tv_nsec / 1000000);
        if (left <= 0) return 0;
        struct pollfd item = { .fd = fd, .events = POLLIN };
        int result = poll(&item, 1, (int) left);
        if (result < 0 && errno == EINTR) continue;
        return result > 0 && (item.revents & POLLIN);
    }
}

int dsha_pty_prepare(int pair[2])
{
    return socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, pair);
}

void dsha_pty_child(int pair[2])
{
    close(pair[0]);
    char stat[2048];
    int fd = open("/proc/self/stat", O_RDONLY | O_CLOEXEC);
    if (fd < 0) _exit(125);
    ssize_t size;
    do { size = read(fd, stat, sizeof(stat) - 1); } while (size < 0 && errno == EINTR);
    close(fd);
    if (size <= 0 || stat[size - 1] != '\n') _exit(125);
    ssize_t sent;
    do { sent = send(pair[1], stat, (size_t) size, MSG_NOSIGNAL); } while (sent < 0 && errno == EINTR);
    char accepted = 0;
    if (sent != size || !readable(pair[1]) || recv(pair[1], &accepted, 1, 0) != 1 || accepted != 1)
        _exit(125);
    close(pair[1]);
}

int dsha_pty_parent(JNIEnv* env, int pair[2], pid_t pid)
{
    close(pair[1]);
    char stat[2048];
    ssize_t size = readable(pair[0]) ? recv(pair[0], stat, sizeof(stat) - 1, 0) : -1;
    int accepted = 0;
    if (size > 0 && stat[size - 1] == '\n') {
        stat[size] = 0;
        jclass type = (*env)->FindClass(env, "com/deepseekharness/app/runtime/NativeProcess");
        jmethodID record = type ? (*env)->GetStaticMethodID(env, type, "recordPtyIdentity", "(ILjava/lang/String;)Z") : NULL;
        jstring value = record ? (*env)->NewStringUTF(env, stat) : NULL;
        if (value) {
            accepted = (*env)->CallStaticBooleanMethod(env, type, record, (jint) pid, value);
            (*env)->DeleteLocalRef(env, value);
        }
        if (type) (*env)->DeleteLocalRef(env, type);
    }
    char ack = 1;
    int success = accepted && !(*env)->ExceptionCheck(env) && send(pair[0], &ack, 1, MSG_NOSIGNAL) == 1;
    close(pair[0]);
    if (!success) {
        /* 仅限当前 fork 返回且尚未交给 Termux waitFor 的子进程；此时 PID 不会被回收复用。 */
        kill(pid, SIGKILL);
        while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) { }
    }
    return success;
}
