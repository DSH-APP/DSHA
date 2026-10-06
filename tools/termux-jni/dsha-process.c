#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/syscall.h>

#define DSHA_MAX_DIRECTORY_IDENTITIES 128
#define DSHA_MAX_SMALL_FILE_BYTES 32768
#define DSHA_TREE_BATCH_COUNT 32
#define DSHA_TREE_STAT_FIELDS 6
#define DSHA_TREE_PAYLOAD_BYTES (DSHA_TREE_BATCH_COUNT * DSHA_MAX_SMALL_FILE_BYTES)

/* 只接受标准 UTF-8；不让 modified UTF-8、截断或非法码点改变宿主路径。 */
static int valid_utf8(const unsigned char* text, size_t length)
{
    for (size_t at = 0; at < length;) {
        unsigned char first = text[at++];
        if (first < 0x80) continue;
        unsigned int value, minimum;
        size_t extra;
        if (first >= 0xc2 && first <= 0xdf) {
            value = first & 0x1f; minimum = 0x80; extra = 1;
        } else if (first >= 0xe0 && first <= 0xef) {
            value = first & 0x0f; minimum = 0x800; extra = 2;
        } else if (first >= 0xf0 && first <= 0xf4) {
            value = first & 7; minimum = 0x10000; extra = 3;
        } else return 0;
        if (extra > length - at) return 0;
        for (size_t i = 0; i < extra; i++) {
            unsigned char next = text[at++];
            if ((next & 0xc0) != 0x80) return 0;
            value = (value << 6) | (next & 0x3f);
        }
        if (value < minimum || value > 0x10ffff || (value >= 0xd800 && value <= 0xdfff))
            return 0;
    }
    return 1;
}

static int small_file_leaf(JNIEnv* env, jbyteArray leaf, char name[NAME_MAX + 1])
{
    if (!leaf) return -EINVAL;
    jsize length = (*env)->GetArrayLength(env, leaf);
    if (length <= 0 || length > NAME_MAX) return -EINVAL;
    (*env)->GetByteArrayRegion(env, leaf, 0, length, (jbyte*)name);
    if ((*env)->ExceptionCheck(env)) return -EINVAL;
    if (memchr(name, 0, (size_t)length) || memchr(name, '/', (size_t)length)
            || (length == 1 && name[0] == '.')
            || (length == 2 && name[0] == '.' && name[1] == '.')
            || !valid_utf8((const unsigned char*)name, (size_t)length)) return -EINVAL;
    name[length] = 0;
    return 0;
}

static int small_file_parent(int fd, uint64_t device, uint64_t inode)
{
    struct stat parent;
    if (fstat(fd, &parent) != 0) return -errno;
    return S_ISDIR(parent.st_mode) && (uint64_t)parent.st_dev == device
        && (uint64_t)parent.st_ino == inode ? 0 : 3;
}

static int tree_names(JNIEnv* env, jbyteArray packed, jint count,
        char storage[DSHA_TREE_BATCH_COUNT * (NAME_MAX + 1)],
        const char* names[DSHA_TREE_BATCH_COUNT])
{
    if (!packed || count <= 0 || count > DSHA_TREE_BATCH_COUNT) return -EINVAL;
    jsize size = (*env)->GetArrayLength(env, packed);
    if (size < count * 2 || size > DSHA_TREE_BATCH_COUNT * (NAME_MAX + 1)) return -EINVAL;
    (*env)->GetByteArrayRegion(env, packed, 0, size, (jbyte*)storage);
    if ((*env)->ExceptionCheck(env)) return -EINVAL;
    size_t offset = 0;
    for (jint i = 0; i < count; i++) {
        if (offset >= (size_t)size) return -EINVAL;
        const char* name = storage + offset;
        const char* end = memchr(name, 0, (size_t)size - offset);
        if (!end) return -EINVAL;
        size_t length = (size_t)(end - name);
        if (length == 0 || length > NAME_MAX || memchr(name, '/', length)
                || memchr(name, '\\', length) || memchr(name, '\n', length)
                || memchr(name, '\r', length) || !strcmp(name, ".") || !strcmp(name, "..")
                || (length >= 2 && name[1] == ':' && ((name[0] >= 'A' && name[0] <= 'Z')
                    || (name[0] >= 'a' && name[0] <= 'z')))
                || !valid_utf8((const unsigned char*)name, length)) return -EINVAL;
        names[i] = name;
        offset += length + 1;
    }
    return offset == (size_t)size ? 0 : -EINVAL;
}

static jmethodID tree_cancellation(JNIEnv* env, jobject cancellation)
{
    if (!cancellation) return NULL;
    jclass type = (*env)->GetObjectClass(env, cancellation);
    if (!type) return NULL;
    jmethodID run = (*env)->GetMethodID(env, type, "run", "()V");
    (*env)->DeleteLocalRef(env, type);
    return run;
}

static int tree_cancelled(JNIEnv* env, jobject cancellation, jmethodID run)
{
    (*env)->CallVoidMethod(env, cancellation, run);
    return (*env)->ExceptionCheck(env);
}

static void tree_observed(const struct stat* current, jlong output[DSHA_TREE_STAT_FIELDS])
{
    output[0] = (jlong)current->st_dev;
    output[1] = (jlong)current->st_ino;
    output[2] = current->st_size;
    output[3] = current->st_mtime;
    output[4] = current->st_mode & 0777;
    output[5] = S_ISREG(current->st_mode) ? 1 : S_ISDIR(current->st_mode) ? 2
        : S_ISLNK(current->st_mode) ? 3 : 4;
}

static int tree_same_file(const jlong expected[DSHA_TREE_STAT_FIELDS], const struct stat* actual)
{
    return expected[5] == 1 && S_ISREG(actual->st_mode)
        && (uint64_t)expected[0] == (uint64_t)actual->st_dev
        && (uint64_t)expected[1] == (uint64_t)actual->st_ino
        && expected[2] == actual->st_size && expected[3] == actual->st_mtime
        && expected[4] == (actual->st_mode & 0777);
}

/* 不缓存名称或 tuple；同一已持有父 FD 内按 Java 顺序逐个实际 fstatat。 */
JNIEXPORT jint JNICALL
Java_com_deepseekharness_app_runtime_NativeStorage_statChildrenBatchBytes(
        JNIEnv* env, jclass type __attribute__((unused)), jint parent,
        jlong parent_device, jlong parent_inode, jbyteArray packed, jint count,
        jlongArray output, jobject cancellation)
{
    if (parent < 0 || count <= 0 || count > DSHA_TREE_BATCH_COUNT || !output
            || (*env)->GetArrayLength(env, output) < count * DSHA_TREE_STAT_FIELDS
            || (*env)->GetArrayLength(env, output) > DSHA_TREE_BATCH_COUNT * DSHA_TREE_STAT_FIELDS)
        return -EINVAL;
    char storage[DSHA_TREE_BATCH_COUNT * (NAME_MAX + 1)];
    const char* names[DSHA_TREE_BATCH_COUNT];
    int result = tree_names(env, packed, count, storage, names);
    if (result != 0) return result;
    jmethodID run = tree_cancellation(env, cancellation);
    if (!run || (*env)->ExceptionCheck(env)) return -EINVAL;
    result = small_file_parent(parent, (uint64_t)parent_device, (uint64_t)parent_inode);
    if (result != 0) return result;
    jlong observed[DSHA_TREE_BATCH_COUNT * DSHA_TREE_STAT_FIELDS];
    for (jint i = 0; i < count; i++) {
        if (tree_cancelled(env, cancellation, run)) return -ECANCELED;
        struct stat current;
        jlong* tuple = observed + i * DSHA_TREE_STAT_FIELDS;
        if (fstatat(parent, names[i], &current, AT_SYMLINK_NOFOLLOW) != 0) {
            if (errno != ENOENT) return -errno;
            memset(tuple, 0, sizeof(jlong) * DSHA_TREE_STAT_FIELDS);
        } else tree_observed(&current, tuple);
    }
    result = small_file_parent(parent, (uint64_t)parent_device, (uint64_t)parent_inode);
    if (result != 0) return result;
    (*env)->SetLongArrayRegion(env, output, 0, count * DSHA_TREE_STAT_FIELDS, observed);
    return (*env)->ExceptionCheck(env) ? -EINVAL : 0;
}

/* 串行、只读且有界；全部真实字节与 EOF/FD/名称检查通过后才回填 payload。
 * 只临时分配所需的 <=1MiB 字节，回填仅限 used；不保留 native 指针或文件 FD。 */
JNIEXPORT jint JNICALL
Java_com_deepseekharness_app_runtime_NativeStorage_readSmallBatchBytes(
        JNIEnv* env, jclass type __attribute__((unused)), jint parent,
        jlong parent_device, jlong parent_inode, jbyteArray packed, jint count,
        jlongArray expected_array, jbyteArray payload, jintArray offsets_array,
        jobject cancellation)
{
    if (parent < 0 || count <= 0 || count > DSHA_TREE_BATCH_COUNT || !expected_array
            || !payload || !offsets_array
            || (*env)->GetArrayLength(env, expected_array) < count * DSHA_TREE_STAT_FIELDS
            || (*env)->GetArrayLength(env, expected_array) > DSHA_TREE_BATCH_COUNT * DSHA_TREE_STAT_FIELDS
            || (*env)->GetArrayLength(env, offsets_array) < count + 1
            || (*env)->GetArrayLength(env, offsets_array) > DSHA_TREE_BATCH_COUNT + 1)
        return -EINVAL;
    jsize capacity = (*env)->GetArrayLength(env, payload);
    if (capacity < 0 || capacity > DSHA_TREE_PAYLOAD_BYTES) return -EINVAL;
    char storage[DSHA_TREE_BATCH_COUNT * (NAME_MAX + 1)];
    const char* names[DSHA_TREE_BATCH_COUNT];
    int result = tree_names(env, packed, count, storage, names);
    if (result != 0) return result;
    jlong expected[DSHA_TREE_BATCH_COUNT * DSHA_TREE_STAT_FIELDS];
    (*env)->GetLongArrayRegion(env, expected_array, 0, count * DSHA_TREE_STAT_FIELDS, expected);
    if ((*env)->ExceptionCheck(env)) return -EINVAL;
    size_t required = 0;
    for (jint i = 0; i < count; i++) {
        const jlong* tuple = expected + i * DSHA_TREE_STAT_FIELDS;
        if (tuple[5] != 1 || tuple[2] < 0 || tuple[2] > DSHA_MAX_SMALL_FILE_BYTES
                || tuple[4] < 0 || tuple[4] > 0777) return -EINVAL;
        if ((size_t)tuple[2] > (size_t)capacity - required) return -EFBIG;
        required += (size_t)tuple[2];
    }
    jmethodID run = tree_cancellation(env, cancellation);
    if (!run || (*env)->ExceptionCheck(env)) return -EINVAL;
    result = small_file_parent(parent, (uint64_t)parent_device, (uint64_t)parent_inode);
    if (result != 0) return result;
    unsigned char* content = malloc(required ? required : 1);
    if (!content) return -ENOMEM;
    jint offsets[DSHA_TREE_BATCH_COUNT + 1];
    size_t used = 0;
    for (jint i = 0; i < count; i++) {
        if (tree_cancelled(env, cancellation, run)) { result = -ECANCELED; goto done_tree_read; }
        int fd = openat(parent, names[i], O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
        if (fd < 0) { result = -errno; goto done_tree_read; }
        struct stat opened, after, named;
        const jlong* tuple = expected + i * DSHA_TREE_STAT_FIELDS;
        if (fstat(fd, &opened) != 0) { result = -errno; goto close_tree_read; }
        if (!tree_same_file(tuple, &opened)) { result = 2; goto close_tree_read; }
        size_t count_read = 0, wanted = (size_t)tuple[2];
        while (count_read < wanted) {
            ssize_t bytes = read(fd, content + used + count_read, wanted - count_read);
            if (bytes < 0 && errno == EINTR) continue;
            if (bytes < 0) { result = -errno; goto close_tree_read; }
            if (tree_cancelled(env, cancellation, run)) { result = -ECANCELED; goto close_tree_read; }
            if (bytes == 0) { result = 2; goto close_tree_read; }
            count_read += (size_t)bytes;
        }
        unsigned char extra;
        ssize_t eof;
        do { eof = read(fd, &extra, 1); } while (eof < 0 && errno == EINTR);
        if (eof < 0) { result = -errno; goto close_tree_read; }
        if (tree_cancelled(env, cancellation, run)) { result = -ECANCELED; goto close_tree_read; }
        if (eof != 0) { result = 2; goto close_tree_read; }
        if (fstat(fd, &after) != 0) { result = -errno; goto close_tree_read; }
        if (!tree_same_file(tuple, &after)) { result = 2; goto close_tree_read; }
        if (fstatat(parent, names[i], &named, AT_SYMLINK_NOFOLLOW) != 0) {
            result = -errno;
            goto close_tree_read;
        }
        if (!tree_same_file(tuple, &named)) { result = 2; goto close_tree_read; }
        offsets[i] = (jint)used;
        used += wanted;
        result = 0;
close_tree_read:
        if (close(fd) != 0 && result == 0) result = -errno;
        if (result != 0) goto done_tree_read;
    }
    result = small_file_parent(parent, (uint64_t)parent_device, (uint64_t)parent_inode);
    if (result != 0) goto done_tree_read;
    offsets[count] = (jint)used;
    (*env)->SetByteArrayRegion(env, payload, 0, (jsize)used, (jbyte*)content);
    if ((*env)->ExceptionCheck(env)) { result = -EINVAL; goto done_tree_read; }
    (*env)->SetIntArrayRegion(env, offsets_array, 0, count + 1, offsets);
    result = (*env)->ExceptionCheck(env) ? -EINVAL : 0;
done_tree_read:
    free(content);
    return result;
}

/* 已创建的 FD 和出生身份即使写入失败也交还 Java；不按名称删除或暗藏发布。
 * state: FD、出生身份有效、dev、ino、size、mtime秒、权限。 */
JNIEXPORT jint JNICALL
Java_com_deepseekharness_app_runtime_NativeStorage_prepareSmallFileBytes(
        JNIEnv* env, jclass type __attribute__((unused)), jint parent,
        jlong parent_device, jlong parent_inode, jbyteArray leaf, jbyteArray bytes,
        jint count, jint mode, jlongArray state)
{
    if (parent < 0 || !bytes || !state || count < 0 || count > DSHA_MAX_SMALL_FILE_BYTES
            || count > (*env)->GetArrayLength(env, bytes)
            || mode < 0 || (mode & ~0777) != 0
            || (*env)->GetArrayLength(env, state) != 7) return -EINVAL;
    char name[NAME_MAX + 1];
    int result = small_file_leaf(env, leaf, name);
    if (result != 0) return result;
    jbyte content[DSHA_MAX_SMALL_FILE_BYTES];
    (*env)->GetByteArrayRegion(env, bytes, 0, count, content);
    if ((*env)->ExceptionCheck(env)) return -EINVAL;
    jlong* observed = (*env)->GetLongArrayElements(env, state, NULL);
    if (!observed) return -ENOMEM;
    memset(observed, 0, sizeof(jlong) * 7);
    observed[0] = -1;
    result = small_file_parent(parent, (uint64_t)parent_device, (uint64_t)parent_inode);
    if (result != 0) goto done_small;
    int fd = openat(parent, name, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) { result = -errno; goto done_small; }
    observed[0] = fd;
    struct stat born, current;
    if (fstat(fd, &born) != 0) { result = -errno; goto done_small; }
    if (!S_ISREG(born.st_mode)) { result = 1; goto done_small; }
    observed[1] = 1;
    observed[2] = (jlong)born.st_dev;
    observed[3] = (jlong)born.st_ino;
    observed[4] = born.st_size;
    observed[5] = born.st_mtime;
    observed[6] = born.st_mode & 0777;
    size_t offset = 0;
    while (offset < (size_t)count) {
        ssize_t written = write(fd, content + offset, (size_t)count - offset);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) { result = written < 0 ? -errno : -EIO; goto done_small; }
        offset += (size_t)written;
    }
    if (fstat(fd, &current) != 0) { result = -errno; goto done_small; }
    if (!S_ISREG(current.st_mode) || born.st_dev != current.st_dev || born.st_ino != current.st_ino) {
        result = 2;
        goto done_small;
    }
    if (fchmod(fd, (mode_t)mode) != 0) { result = -errno; goto done_small; }
    result = 0;
done_small:
    (*env)->ReleaseLongArrayElements(env, state, observed, 0);
    return result;
}

/* 原同步排队之后再完成最终 fstat 与名称核验，主写 FD 仍由 Java PFD 持有。 */
JNIEXPORT jint JNICALL
Java_com_deepseekharness_app_runtime_NativeStorage_finishSmallFileBytes(
        JNIEnv* env, jclass type __attribute__((unused)), jint parent,
        jlong parent_device, jlong parent_inode, jbyteArray leaf, jint fd,
        jlong device, jlong inode, jlongArray output)
{
    if (parent < 0 || fd < 0 || !output || (*env)->GetArrayLength(env, output) != 5)
        return -EINVAL;
    char name[NAME_MAX + 1];
    int result = small_file_leaf(env, leaf, name);
    if (result != 0) return result;
    result = small_file_parent(parent, (uint64_t)parent_device, (uint64_t)parent_inode);
    if (result != 0) return result;
    struct stat current, named;
    if (fstat(fd, &current) != 0) return -errno;
    if (!S_ISREG(current.st_mode) || (uint64_t)current.st_dev != (uint64_t)device
            || (uint64_t)current.st_ino != (uint64_t)inode) return 2;
    if (fstatat(parent, name, &named, AT_SYMLINK_NOFOLLOW) != 0)
        return errno == ENOENT ? 4 : -errno;
    if (!S_ISREG(named.st_mode) || current.st_dev != named.st_dev || current.st_ino != named.st_ino
            || current.st_size != named.st_size || current.st_mtime != named.st_mtime
            || (current.st_mode & 0777) != (named.st_mode & 0777)) return 4;
    jlong observed[5] = { (jlong)current.st_dev, (jlong)current.st_ino, current.st_size,
        current.st_mtime, current.st_mode & 0777 };
    (*env)->SetLongArrayRegion(env, output, 0, 5, observed);
    return (*env)->ExceptionCheck(env) ? -EINVAL : 0;
}

/* 仅在 Java adoptFd/结果分配失败时关闭刚由 prepare 交回的本次 FD；不删除名称。 */
JNIEXPORT jint JNICALL
Java_com_deepseekharness_app_runtime_NativeStorage_closeSmallFileDescriptor(
        JNIEnv* env __attribute__((unused)), jclass type __attribute__((unused)), jint fd)
{
    if (fd < 0) return -EINVAL;
    return close(fd) == 0 ? 0 : -errno;
}

/* 每次按原顺序核对 / 到根的每一个名称；不缓存检查结果、指针或描述符。
 * 0 为一致，正数为首个不一致序号加一，负数为失败 errno。 */
JNIEXPORT jint JNICALL
Java_com_deepseekharness_app_runtime_NativeStorage_verifyDirectoryIdentities(
        JNIEnv* env, jclass type __attribute__((unused)), jbyteArray paths,
        jlongArray identities, jlongArray root_stat)
{
    if (!paths || !identities || !root_stat) return -EINVAL;
    jsize identity_count = (*env)->GetArrayLength(env, identities);
    if (identity_count <= 0 || identity_count % 2 != 0
            || identity_count > DSHA_MAX_DIRECTORY_IDENTITIES * 2
            || (*env)->GetArrayLength(env, root_stat) != 3) return -EINVAL;
    jsize count = identity_count / 2;
    jsize byte_count = (*env)->GetArrayLength(env, paths);
    if (byte_count < count * 2 || byte_count > count * PATH_MAX) return -EINVAL;
    jlong expected[DSHA_MAX_DIRECTORY_IDENTITIES * 2];
    (*env)->GetLongArrayRegion(env, identities, 0, identity_count, expected);
    if ((*env)->ExceptionCheck(env)) return -EINVAL;
    jbyte* bytes = (*env)->GetByteArrayElements(env, paths, NULL);
    if (!bytes) return -ENOMEM;
    jint result = -EINVAL;
    const char* names[DSHA_MAX_DIRECTORY_IDENTITIES];
    size_t lengths[DSHA_MAX_DIRECTORY_IDENTITIES];
    size_t offset = 0;
    for (jsize i = 0; i < count; i++) {
        if (offset >= (size_t)byte_count) goto done;
        const char* name = (const char*)bytes + offset;
        const char* end = memchr(name, 0, (size_t)byte_count - offset);
        if (!end) goto done;
        size_t length = (size_t)(end - name);
        if (length == 0 || length >= PATH_MAX || name[0] != '/'
                || !valid_utf8((const unsigned char*)name, length)) goto done;
        if (i == 0) {
            if (length != 1) goto done;
        } else {
            size_t prefix = i == 1 ? 1 : lengths[i - 1] + 1;
            if (length <= prefix || memcmp(name, names[i - 1], lengths[i - 1]) != 0
                    || (i > 1 && name[lengths[i - 1]] != '/')) goto done;
            const char* part = name + prefix;
            size_t part_length = length - prefix;
            if (memchr(part, '/', part_length)
                    || (part_length == 1 && part[0] == '.')
                    || (part_length == 2 && part[0] == '.' && part[1] == '.')) goto done;
        }
        names[i] = name;
        lengths[i] = length;
        offset += length + 1;
    }
    if (offset != (size_t)byte_count) goto done;
    struct stat current;
    for (jsize i = 0; i < count; i++) {
        if (lstat(names[i], &current) != 0) {
            int error = errno;
            result = error == ENOENT ? i + 1 : -(error ? error : EIO);
            goto done;
        }
        if (!S_ISDIR(current.st_mode) || (uint64_t)current.st_dev != (uint64_t)expected[i * 2]
                || (uint64_t)current.st_ino != (uint64_t)expected[i * 2 + 1]) {
            result = i + 1;
            goto done;
        }
    }
    jlong observed[3] = { current.st_size, current.st_mtime, current.st_mode & 0777 };
    (*env)->SetLongArrayRegion(env, root_stat, 0, 3, observed);
    result = (*env)->ExceptionCheck(env) ? -EINVAL : 0;
done:
    (*env)->ReleaseByteArrayElements(env, paths, bytes, JNI_ABORT);
    return result;
}

/* DSHA 扩展单独编译，保留 Termux 上游源文件及其校验。 */
JNIEXPORT jint JNICALL
Java_com_deepseekharness_app_runtime_NativeProcess_sessionId(
        JNIEnv* env __attribute__((unused)), jclass type __attribute__((unused)), jint pid)
{
    if (pid <= 1) return -EINVAL;
    pid_t session = getsid(pid);
    return session < 0 ? -errno : session;
}

/* 每组保留同一写描述符的 dup，旧内核也逐个 fsync 检查延迟写错误。 */
JNIEXPORT jint JNICALL
Java_com_deepseekharness_app_runtime_NativeStorage_flushFiles(
        JNIEnv* env, jclass type __attribute__((unused)), jintArray descriptors)
{
    if (!descriptors) return -EINVAL;
    jsize count = (*env)->GetArrayLength(env, descriptors);
    if (count <= 0 || count > 128) return -EINVAL;
    jint fds[128];
    (*env)->GetIntArrayRegion(env, descriptors, 0, count, fds);
    if ((*env)->ExceptionCheck(env)) return -EINVAL;
    struct stat first;
    for (jsize i = 0; i < count; i++) {
        struct stat current;
        if (fstat(fds[i], &current) != 0) return -errno;
        if (!S_ISREG(current.st_mode) && !S_ISDIR(current.st_mode)) return -EINVAL;
        if (i == 0) first = current;
        else if (first.st_dev != current.st_dev) return -EXDEV;
    }
    int result;
    do { result = (int)syscall(__NR_syncfs, fds[0]); } while (result < 0 && errno == EINTR);
    if (result < 0) return -errno;
    for (jsize i = 0; i < count; i++) {
        do { result = fsync(fds[i]); } while (result < 0 && errno == EINTR);
        if (result < 0) return -errno;
    }
    return 0;
}
