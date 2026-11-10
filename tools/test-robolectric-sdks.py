"""Isolated regressions for tools/prepare-robolectric-sdks.py; no network."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("prs", TOOLS / "prepare-robolectric-sdks.py")
prs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prs)

BODY = b"android-all fixture bytes" * 100


def lock_for(body=BODY, version="15-robolectric-1-i7"):
    return {
        "schema": 1,
        "repository": "https://repo1.maven.org/maven2",
        "group": "org.robolectric",
        "artifact": "android-all-instrumented",
        "jars": [{"version": version, "size": len(body), "sha256": hashlib.sha256(body).hexdigest()}],
    }


class Response(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


class Opener:
    def __init__(self, *bodies):
        self.bodies = list(bodies)
        self.urls = []

    def __call__(self, url, timeout):
        self.urls.append(url)
        body = self.bodies.pop(0)
        if isinstance(body, Exception):
            raise body
        return Response(body)


class PrepareRobolectricSdksTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name) / "deps"

    def tearDown(self):
        self.tmp.cleanup()

    def write_lock(self, lock):
        path = Path(self.tmp.name) / "lock.json"
        path.write_text(json.dumps(lock), encoding="utf-8")
        return path

    def test_real_lock_is_valid_and_unique(self):
        lock = prs.load_lock()
        self.assertEqual(5, len(lock["jars"]))
        self.assertIn("15-robolectric-12650502-i7", [j["version"] for j in lock["jars"]])

    def test_downloads_into_flat_resolver_layout_with_exact_url(self):
        opener = Opener(BODY)
        report = prs.prepare(self.dir, lock=lock_for(), opener=opener, sleep=lambda s: None)
        self.assertEqual([("15-robolectric-1-i7", "downloaded")], report)
        self.assertEqual(BODY, (self.dir / "android-all-instrumented-15-robolectric-1-i7.jar").read_bytes())
        self.assertEqual(
            ["https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/15-robolectric-1-i7/"
             "android-all-instrumented-15-robolectric-1-i7.jar"], opener.urls)

    def test_retries_transient_errors_then_succeeds(self):
        opener = Opener(OSError("reset"), b"truncated", BODY)
        prs.prepare(self.dir, lock=lock_for(), opener=opener, sleep=lambda s: None)
        self.assertEqual(3, len(opener.urls))
        self.assertFalse(list(self.dir.glob("*.part")))

    def test_gives_up_after_attempts_and_leaves_no_jar(self):
        opener = Opener(*[OSError("down")] * 5)
        with self.assertRaisesRegex(RuntimeError, "ROBOLECTRIC_JAR_UNAVAILABLE"):
            prs.prepare(self.dir, lock=lock_for(), opener=opener, sleep=lambda s: None)
        self.assertFalse(any(self.dir.iterdir()))

    def test_oversized_and_wrong_digest_bodies_are_rejected(self):
        opener = Opener(BODY + b"x", b"y" * len(BODY), BODY)
        prs.prepare(self.dir, lock=lock_for(), opener=opener, sleep=lambda s: None)
        self.assertEqual(3, len(opener.urls))

    def test_cached_valid_jar_is_reused_without_network(self):
        self.dir.mkdir()
        (self.dir / "android-all-instrumented-15-robolectric-1-i7.jar").write_bytes(BODY)
        report = prs.prepare(self.dir, lock=lock_for(), opener=Opener(), sleep=lambda s: None)
        self.assertEqual("cached", report[0][1])

    def test_corrupt_cache_is_replaced(self):
        self.dir.mkdir()
        (self.dir / "android-all-instrumented-15-robolectric-1-i7.jar").write_bytes(b"z" * len(BODY))
        prs.prepare(self.dir, lock=lock_for(), opener=Opener(BODY), sleep=lambda s: None)
        self.assertEqual(BODY, (self.dir / "android-all-instrumented-15-robolectric-1-i7.jar").read_bytes())

    def test_symlinked_jar_is_not_trusted(self):
        self.dir.mkdir()
        target = Path(self.tmp.name) / "elsewhere.jar"
        target.write_bytes(BODY)
        (self.dir / "android-all-instrumented-15-robolectric-1-i7.jar").symlink_to(target)
        with self.assertRaisesRegex(RuntimeError, "ROBOLECTRIC_JAR_MISSING"):
            prs.prepare(self.dir, check=True, lock=lock_for())
        prs.prepare(self.dir, lock=lock_for(), opener=Opener(BODY), sleep=lambda s: None)
        self.assertFalse((self.dir / "android-all-instrumented-15-robolectric-1-i7.jar").is_symlink())
        self.assertEqual(BODY, target.read_bytes())

    def test_check_mode_never_downloads(self):
        with self.assertRaisesRegex(RuntimeError, "ROBOLECTRIC_JAR_MISSING"):
            prs.prepare(self.dir, check=True, lock=lock_for())

    def test_lock_rejects_traversal_bad_digest_and_wrong_repository(self):
        for mutate in (
            lambda l: l["jars"][0].update(version="../x"),
            lambda l: l["jars"][0].update(sha256="ABC"),
            lambda l: l["jars"][0].update(size=0),
            lambda l: l.update(repository="https://example.invalid/maven2"),
            lambda l: l.update(artifact="android-all"),
            lambda l: l["jars"].append(dict(l["jars"][0])),
        ):
            lock = lock_for()
            mutate(lock)
            with self.assertRaises(ValueError):
                prs.load_lock(self.write_lock(lock))


if __name__ == "__main__":
    unittest.main()
