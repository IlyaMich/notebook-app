"""Unit tests for EDT-05 CP4B guarded Gradle version switch; no Android required."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "switch_ink_cp4b.py"
spec = importlib.util.spec_from_file_location("switch_ink_cp4b", SCRIPT)
switch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(switch)


ARTIFACTS = sorted(switch.EXPECTED_ARTIFACTS)


def fake_gradle(version: str = "1.0.0", newline: str = "\n") -> str:
    lines = ["plugins {", "}", "dependencies {", f'    val inkProbeVersion = "{version}"']
    lines.extend(f'    debugImplementation("androidx.ink:{a}:$inkProbeVersion")' for a in ARTIFACTS)
    lines.extend(["    implementation(\"androidx.room:room-runtime:2.8.5\")", "}", ""])
    return newline.join(lines)


class InkVersionSwitcherTests(unittest.TestCase):
    def test_valid_stable_setup(self):
        self.assertEqual("1.0.0", switch.inspect_gradle(fake_gradle())[0])

    def test_valid_preview_setup(self):
        self.assertEqual("1.1.0-alpha10", switch.inspect_gradle(fake_gradle("1.1.0-alpha10"))[0])

    def test_only_version_literal_changes(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp) / "build.gradle.kts"
            original = fake_gradle()
            f.write_bytes(original.encode())
            before, after, changed = switch.switch_version(f, switch.PREVIEW)
            self.assertEqual(("1.0.0", "1.1.0-alpha10", True), (before, after, changed))
            self.assertEqual(original.replace('inkProbeVersion = "1.0.0"', 'inkProbeVersion = "1.1.0-alpha10"'), f.read_text())

    def test_restore_stable(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp) / "build.gradle.kts"
            original = fake_gradle()
            f.write_text(original)
            switch.switch_version(f, switch.PREVIEW)
            switch.switch_version(f, switch.STABLE)
            self.assertEqual(original, f.read_text())

    def test_preview_is_idempotent(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp) / "build.gradle.kts"
            f.write_text(fake_gradle("1.1.0-alpha10"))
            self.assertEqual(("1.1.0-alpha10", "1.1.0-alpha10", False), switch.switch_version(f, switch.PREVIEW))

    def test_preserves_crlf_bytes(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp) / "build.gradle.kts"
            f.write_bytes(fake_gradle(newline="\r\n").encode())
            switch.switch_version(f, switch.PREVIEW)
            self.assertIn(b'inkProbeVersion = "1.1.0-alpha10"\r\n', f.read_bytes())
            self.assertNotIn(b'\n\n', f.read_bytes())

    def test_unknown_version_rejected(self):
        with self.assertRaises(switch.InkConfigurationError):
            switch.inspect_gradle(fake_gradle("9.0.0"))

    def test_missing_artifact_rejected(self):
        source = fake_gradle().replace(f'    debugImplementation("androidx.ink:{ARTIFACTS[0]}:$inkProbeVersion")\n', '')
        with self.assertRaisesRegex(switch.InkConfigurationError, "Missing expected Ink artifacts"):
            switch.inspect_gradle(source)

    def test_release_wiring_rejected(self):
        source = fake_gradle().replace('    debugImplementation("androidx.ink:', '    implementation("androidx.ink:', 1)
        with self.assertRaisesRegex(switch.InkConfigurationError, "debug-only"):
            switch.inspect_gradle(source)

    def test_hardcoded_artifact_rejected(self):
        source = fake_gradle().replace(':$inkProbeVersion")', ':1.0.0")', 1)
        with self.assertRaisesRegex(switch.InkConfigurationError, "does not reference"):
            switch.inspect_gradle(source)

    def test_extra_unrecognized_ink_dependency_rejected(self):
        source = fake_gradle().replace('dependencies {', 'dependencies {\n    debugApi("androidx.ink:ink-bogus:$inkProbeVersion")')
        with self.assertRaisesRegex(switch.InkConfigurationError, "debug-only"):
            switch.inspect_gradle(source)

    def test_unknown_ink_reference_form_rejected(self):
        source = fake_gradle().replace('dependencies {', 'dependencies {\n    add("debugImplementation", "androidx.ink:ink-misc:$inkProbeVersion")')
        with self.assertRaisesRegex(switch.InkConfigurationError, "unrecognized"):
            switch.inspect_gradle(source)

    def test_duplicate_declaration_rejected(self):
        source = fake_gradle().replace('dependencies {', f'dependencies {{\n    debugImplementation("androidx.ink:{ARTIFACTS[0]}:$inkProbeVersion")')
        with self.assertRaisesRegex(switch.InkConfigurationError, "Duplicate"):
            switch.inspect_gradle(source)


if __name__ == "__main__":
    unittest.main()
