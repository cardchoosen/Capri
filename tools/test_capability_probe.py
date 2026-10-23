import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('capability_probe', Path(__file__).resolve().parents[1] / 'app/src/main/assets/runtime/capability_probe.py')
probe = importlib.util.module_from_spec(spec); spec.loader.exec_module(probe)


class CapabilityProbeTest(unittest.TestCase):
    def test_missing_installable_and_broken_transitive_import_differ(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(probe.importlib, 'import_module', side_effect=ModuleNotFoundError("No PIL", name='PIL')):
                self.assertEqual(probe.library_check('pillow', ['gif'], directory, {'pillow': '11'})['state'], 'needs_install')
            with patch.object(probe.importlib, 'import_module', side_effect=ModuleNotFoundError("No _imaging", name='PIL._imaging')):
                self.assertEqual(probe.library_check('pillow', ['gif'], directory, {'pillow': '11'})['state'], 'broken')

    def test_strict_language_never_claims_python_helper_as_native_solution(self):
        with patch.object(probe, 'language_check', return_value={'state': 'ready'}), \
             patch.object(probe, 'native_check', return_value={'state': 'needs_native_backend'}), \
             patch.object(probe, 'library_check') as python_library:
            result = probe.assess({'language': 'cpp', 'outputs': ['gif'], 'allow_helpers': False})
            self.assertEqual(result['readiness'], 'needs_native_backend')
            python_library.assert_not_called()
            self.assertIsNone(result['install_if_needed'])

    def test_absent_library_suggests_install_without_performing_it(self):
        with patch.object(probe, 'language_check', return_value={'state': 'ready'}), \
             patch.object(probe, 'library_check', return_value={'state': 'needs_install', 'package': 'pillow'}):
            result = probe.assess({'language': 'cpp', 'outputs': ['gif'], 'allow_helpers': True})
            self.assertEqual(result['readiness'], 'needs_install')
            self.assertEqual(result['install_if_needed']['packages'], ['pillow'])
