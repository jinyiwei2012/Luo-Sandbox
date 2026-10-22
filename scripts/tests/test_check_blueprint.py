import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path


CHECKER_PATH = Path(__file__).resolve().parents[1] / "check_blueprint.py"
SPEC = importlib.util.spec_from_file_location("check_blueprint", CHECKER_PATH)
check_blueprint = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(check_blueprint)


class CheckBlueprintTest(unittest.TestCase):
  def setUp(self):
    self.temp_dir = tempfile.TemporaryDirectory()
    self.root = Path(self.temp_dir.name)

  def tearDown(self):
    self.temp_dir.cleanup()

  def init_git_repo(self):
    subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)

  def write(self, relative_path, content):
    path = self.root / relative_path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")

  def validation_errors(self):
    errors = []
    check_blueprint.check_markdown_links(self.root, errors)
    check_blueprint.check_json_files(self.root, errors)
    return errors

  def test_ignored_generated_files_are_not_scanned(self):
    self.init_git_repo()
    self.write(".gitignore", ".gradle-user-home/\n")
    self.write("README.md", "[broken](missing.md)\n")
    self.write(".gradle-user-home/cache.md", "[ignored](missing.md)\n")
    (self.root / ".gradle-user-home" / "named.json").mkdir()

    errors = self.validation_errors()

    self.assertEqual(["README.md: missing link target: missing.md"], errors)

  def test_untracked_source_files_are_scanned(self):
    self.init_git_repo()
    self.write("notes.md", "[broken](missing.md)\n")
    self.write("config.json", "not json\n")

    errors = self.validation_errors()

    self.assertEqual(2, len(errors))
    self.assertTrue(any(error.startswith("notes.md: missing link target") for error in errors))
    self.assertTrue(any(error.startswith("config.json: invalid JSON") for error in errors))

  def test_tracked_file_is_scanned_after_being_ignored(self):
    self.init_git_repo()
    self.write("tracked.md", "[broken](missing.md)\n")
    subprocess.run(["git", "add", "tracked.md"], cwd=self.root, check=True)
    self.write(".gitignore", "tracked.md\n")

    errors = self.validation_errors()

    self.assertEqual(["tracked.md: missing link target: missing.md"], errors)

  def test_non_git_fallback_excludes_generated_roots(self):
    self.write("README.md", "[broken](missing.md)\n")
    self.write("build/cache.md", "[ignored](missing.md)\n")
    self.write("run/cache.json", "not json\n")

    errors = self.validation_errors()

    self.assertEqual(["README.md: missing link target: missing.md"], errors)


if __name__ == "__main__":
  unittest.main()
