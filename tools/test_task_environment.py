import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

ASSET = Path(__file__).resolve().parents[1] / 'app/src/main/assets/runtime/task_environment.py'
spec = importlib.util.spec_from_file_location('task_environment', ASSET)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class TaskEnvironmentTest(unittest.TestCase):
    def test_missing_import_and_actual_behavior_are_distinct_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            result = module.inspect({'requirements': {'commands': ['capri_absent_command'], 'imports': ['capri_absent_module']}}, Path(temporary))
            self.assertFalse(result['requirements_satisfied'])
            self.assertEqual(['command:capri_absent_command'], result['missing'])
            self.assertEqual(['import:capri_absent_module'], result['failed'])
            self.assertFalse(module.run([sys.executable, '-c', 'raise SystemExit(7)'], temporary)['passed'])
    def test_project_manifest_and_version_constraints_are_observed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root/'package.json').write_text('{"dependencies":{"example":"1.2.3"},"packageManager":"pnpm@9"}')
            result = module.inspect({'requirements': {'python_packages': ['pip>=9999']}}, root)
            self.assertFalse(result['requirements_satisfied'])
            self.assertEqual('1.2.3',result['project']['package.json']['dependencies']['example'])
            self.assertFalse(result['checks']['python:pip>=9999']['satisfies'])

    def test_satisfied_versions_skip_pip_but_extras_and_missing_versions_do_not(self):
        from unittest.mock import patch
        with patch.object(module.importlib.metadata, 'version', return_value='2.0'):
            self.assertEqual(['example>=3', 'example[extra]>=1'], module.pending_python_packages(['example>=1', 'example>=3', 'example[extra]>=1']))
        with patch.object(module.importlib.metadata, 'version', side_effect=module.importlib.metadata.PackageNotFoundError):
            self.assertEqual(['example'], module.pending_python_packages(['example']))

    def test_process_failures_retain_exit_and_elapsed_time(self):
        with tempfile.TemporaryDirectory() as temporary:
            result = module.run([sys.executable, '-c', 'raise SystemExit(9)'], temporary)
            self.assertEqual(9, result['exit_code']); self.assertFalse(result['passed'])
            self.assertGreaterEqual(result['elapsed_ms'], 0)
