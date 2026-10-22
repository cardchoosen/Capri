"""Test the real channel selector without Android or network access."""
import importlib.util
from pathlib import Path
import sys
import unittest

# The selector intentionally uses the same vendored requirement parser as pip.
try:
    import pip._vendor.packaging
except ImportError:
    raise SystemExit('Run with a development Python that has pip installed')
spec = importlib.util.spec_from_file_location('python_packages', Path(__file__).resolve().parents[1] / 'app/src/main/assets/runtime/python_packages.py')
packages = importlib.util.module_from_spec(spec); spec.loader.exec_module(packages)

class PackageChannelTest(unittest.TestCase):
    manifest = {'packages': {'matplotlib': {'version': '3.9.3'}, 'pillow': {'version': '11.2.1'}, 'numpy': {'version': '2.2.4'}}}
    def test_known_matching_versions_can_be_installed_offline(self):
        for requirements in [['matplotlib'], ['Pillow==11.2.1', 'numpy>=2,<3'], ['matplotlib>=3.8,<3.10']]:
            self.assertEqual('bundled-alpine', packages.selection(requirements, self.manifest))
    def test_user_constraints_and_extras_are_not_silently_relaxed(self):
        for requirements in [['matplotlib>=3.10'], ['requests'], ['pillow[tests]'], ['numpy==1.26']]:
            self.assertEqual('pypi-and-bundled', packages.selection(requirements, self.manifest))
    def test_unsafe_or_unsupported_requirements_are_rejected(self):
        for values in [[], ['pip'], ['x @ https://example.com/file.whl'], ['x; python_version>="3.0"'], ['--upgrade']]:
            with self.assertRaises(ValueError):
                packages.selection(values, self.manifest)

if __name__ == '__main__': unittest.main()
