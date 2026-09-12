#include <jni.h>
#include <sys/types.h>

int dsha_pty_prepare(int pair[2]);
int dsha_pty_parent(JNIEnv* env, int pair[2], pid_t pid);
void dsha_pty_child(int pair[2]);
