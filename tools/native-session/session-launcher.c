#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

/*
 * Own the isolated session until Java confirms its whole group exited.
 * The child shell may stop itself after publishing its foreground status;
 * keeping this native leader runnable lets parent death end its initial
 * process group even while that shell is stopped. Java separately verifies
 * the full session and retains a durable record if members escaped the group.
 */
static void parent_gone(int signal_number) {
    (void)signal_number;
    kill(-getpid(), SIGKILL);
    _exit(125);
}

int main(int argc, char **argv) {
    if (argc < 2) { fputs("DSHA_SESSION_USAGE\n", stderr); return 125; }
    int command = 1;
    int handshake = strcmp(argv[1], "--birth-handshake") == 0;
    if (handshake && ++command >= argc) {
        fputs("DSHA_SESSION_USAGE\n", stderr); return 125;
    }
    pid_t original_parent = getppid();
    if (original_parent <= 1) { fputs("DSHA_SESSION_PARENT\n", stderr); return 125; }
    if (setsid() < 0) { perror("DSHA_SESSION_SETSID"); return 125; }

    struct sigaction action = {0};
    action.sa_handler = parent_gone;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGTERM, &action, NULL) < 0
            || prctl(PR_SET_PDEATHSIG, SIGTERM) < 0) {
        perror("DSHA_SESSION_PARENT_WATCH");
        return 125;
    }
    /* Parent may die between getppid and PR_SET_PDEATHSIG. No guest starts then. */
    if (getppid() != original_parent) parent_gone(SIGTERM);

    /* The signed launcher self-reads birth evidence before starting its waiting shell.
     * Java consumes this one line, verifies its parent/session and rereads the birth
     * identity before it persists a record or writes the DSHA_START guest handshake. */
    if (handshake) {
        char stat[8192];
        FILE *identity = fopen("/proc/self/stat", "r");
        if (!identity || !fgets(stat, sizeof(stat), identity)) {
            if (identity) fclose(identity);
            fputs("DSHA_SESSION_STAT_UNAVAILABLE\n", stderr); return 125;
        }
        fclose(identity);
        size_t length = strlen(stat);
        if (length == 0 || stat[length - 1] != '\n') {
            fputs("DSHA_SESSION_STAT_LIMIT\n", stderr); return 125;
        }
        if (printf("DSHA_SESSION_STAT_V1 %s", stat) < 0 || fflush(stdout) != 0) return 125;
    }

    pid_t child = fork();
    if (child < 0) { perror("DSHA_SESSION_FORK"); return 125; }
    if (child == 0) {
        struct sigaction ordinary = {0};
        ordinary.sa_handler = SIG_DFL;
        sigemptyset(&ordinary.sa_mask);
        sigaction(SIGTERM, &ordinary, NULL);
        execvp(argv[command], argv + command);
        int error = errno;
        perror("DSHA_SESSION_EXEC");
        _exit(error == ENOENT ? 127 : 126);
    }

    int status;
    for (;;) {
        if (waitpid(child, &status, 0) == child) break;
        if (errno == EINTR) continue;
        perror("DSHA_SESSION_WAIT");
        break;
    }
    /* An unexpected shell exit must not leave descendants without a leader. */
    kill(-getpid(), SIGKILL);
    _exit(125);
}
