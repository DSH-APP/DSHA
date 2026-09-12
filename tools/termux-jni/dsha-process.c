#include <errno.h>
#include <jni.h>
#include <unistd.h>

/* DSHA 扩展单独编译，保留 Termux 上游源文件及其校验。 */
JNIEXPORT jint JNICALL
Java_com_deepseekharness_app_runtime_NativeProcess_sessionId(
        JNIEnv* env __attribute__((unused)), jclass type __attribute__((unused)), jint pid)
{
    if (pid <= 1) return -EINVAL;
    pid_t session = getsid(pid);
    return session < 0 ? -errno : session;
}
