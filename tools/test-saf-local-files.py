#!/usr/bin/env python3
"""Real Linux syscall negative controls for SAF container-path anchoring.

Mirrors the production invariant in app/src/main/java/com/deepseekharness/app/saf/SafLocalFiles.java:
a canonical/policy check followed by a path-based open is not stable when an ancestor directory is
replaced by a symlink. This test uses the same syscalls (O_PATH|O_NOFOLLOW directory walk,
/proc/self/fd/<fd>/<name> opens, /proc/self/fd/<fd> readlink) and proves:

1. the attack is real: after swapping an allowed ancestor for a symlink, a plain
   O_NOFOLLOW open still reads the credential file;
2. the opened descriptor's real path (readlink /proc/self/fd) no longer equals the expected path,
   which is what the Java code rejects as PATH_CHANGED;
3.逐级 anchored 遍历在祖先被换成符号链接时直接 ELOOP；
4. 已锚定的目录 fd 在目录被 rename 后仍指向同一目录，发布不会改写到路径重新解析的位置。
"""
import errno
import os
import shutil
import stat
import tempfile
import unittest

O_PATH = 0o10000000
O_CLOEXEC = os.O_CLOEXEC


def _open_dir_anchored(path):
    fd = os.open("/", O_PATH | O_CLOEXEC)
    try:
        for segment in path.strip("/").split("/"):
            next_fd = os.open(
                "/proc/self/fd/%d/%s" % (fd, segment), O_PATH | os.O_NOFOLLOW | O_CLOEXEC
            )
            info = os.fstat(next_fd)
            if not stat.S_ISDIR(info.st_mode):
                os.close(next_fd)
                raise OSError(errno.ENOTDIR, "not a directory", segment)
            os.close(fd)
            fd = next_fd
        return fd
    except BaseException:
        os.close(fd)
        raise


def _readlink_fd(fd):
    return os.readlink("/proc/self/fd/%d" % fd)


@unittest.skipUnless(os.name == "posix" and os.path.isdir("/proc/self/fd"), "Linux /proc required")
class SafLocalFilesSyscallTest(unittest.TestCase):
    def setUp(self):
        self.base = tempfile.mkdtemp(prefix="dsha-saf-anchor-")
        self.allowed = os.path.join(self.base, "allowed")
        self.leaf = os.path.join(self.allowed, "x")
        os.makedirs(self.leaf)
        with open(os.path.join(self.leaf, "config.json"), "w", encoding="utf-8") as stream:
            stream.write("allowed")
        self.credentials = os.path.join(self.base, ".docker")
        os.makedirs(self.credentials)
        with open(os.path.join(self.credentials, "config.json"), "w", encoding="utf-8") as stream:
            stream.write("SECRET")

    def tearDown(self):
        shutil.rmtree(self.base, ignore_errors=True)

    def _swap_ancestor(self):
        shutil.rmtree(self.leaf)
        os.symlink(self.credentials, self.leaf)
        return os.path.join(self.leaf, "config.json")

    def test_plain_nofollow_open_still_follows_swapped_ancestor(self):
        target = self._swap_ancestor()
        fd = os.open(target, os.O_RDONLY | os.O_NOFOLLOW)
        try:
            with os.fdopen(fd, "r", encoding="utf-8") as stream:
                self.assertEqual("SECRET", stream.read())
        finally:
            pass

    def test_real_path_mismatch_is_the_rejection_signal(self):
        target = self._swap_ancestor()
        fd = os.open(target, os.O_RDONLY | os.O_NOFOLLOW)
        try:
            real = _readlink_fd(fd)
        finally:
            os.close(fd)
        expected = os.path.join(self.allowed, "x", "config.json")
        self.assertEqual(os.path.join(self.credentials, "config.json"), real)
        self.assertNotEqual(expected, real)

    def test_anchored_directory_rejects_swapped_ancestor(self):
        self._swap_ancestor()
        # O_PATH|O_NOFOLLOW 在中间分量遇到符号链接时打开的是链接本身；
        # 生产代码随后用 fstat 判定“不是目录”并拒绝（Python 镜像抛 ENOTDIR）。
        with self.assertRaises(OSError) as raised:
            _open_dir_anchored(os.path.join(self.allowed, "x"))
        self.assertEqual(errno.ENOTDIR, raised.exception.errno)

    def test_anchored_fd_keeps_original_directory_after_rename(self):
        fd = _open_dir_anchored(self.leaf)
        moved = os.path.join(self.base, "moved")
        try:
            os.rename(self.allowed, moved)
            # 已打开目录 fd 仍指向原目录（rename 不会让 fd 改为解析新路径）。
            child = os.open(
                "/proc/self/fd/%d/config.json" % fd,
                os.O_RDONLY | os.O_NOFOLLOW,
            )
            try:
                self.assertEqual(b"allowed", os.read(child, 64))
            finally:
                os.close(child)
            self.assertTrue(os.path.isfile(os.path.join(moved, "x", "config.json")))
            with self.assertRaises(FileNotFoundError):
                os.open(os.path.join(self.allowed, "x", "config.json"), os.O_RDONLY)
        finally:
            os.close(fd)

    def test_publish_through_parent_fd_stays_in_anchored_directory(self):
        fd = _open_dir_anchored(self.allowed)
        try:
            os.rename(self.allowed, self.allowed + "-moved")
            temp = "/proc/self/fd/%d/.saf-tmp" % fd
            with open(temp, "w", encoding="utf-8") as stream:
                stream.write("payload")
            os.rename(temp, "/proc/self/fd/%d/config.json" % fd)
            self.assertTrue(os.path.isfile(os.path.join(self.allowed + "-moved", "config.json")))
            self.assertFalse(os.path.isfile(os.path.join(self.allowed, "config.json")))
        finally:
            os.close(fd)


if __name__ == "__main__":
    unittest.main()
