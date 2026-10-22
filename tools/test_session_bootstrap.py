import concurrent.futures
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
import venv

ASSET = Path(__file__).resolve().parents[1] / 'app/src/main/assets/runtime/session_bootstrap.py'
spec = importlib.util.spec_from_file_location('session_bootstrap', ASSET)
bootstrap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bootstrap)


class SessionBootstrapTest(unittest.TestCase):
    def test_parallel_first_use_seeds_pip_offline_and_keeps_user_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            (home / 'user.txt').write_text('keep')
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                processes = list(pool.map(lambda _: bootstrap.ensure_environment(home), range(2)))
            self.assertEqual(processes[0], processes[1])
            result = subprocess.check_output([str(processes[0]), '-c', 'import pip,sys; print(sys.prefix); print(pip.__version__)'], text=True)
            self.assertIn(str(home / '.venv'), result)
            self.assertEqual('keep', (home / 'user.txt').read_text())
            self.assertTrue((home / '.capri-environment.json').is_file())

    def test_existing_venv_gets_pip_without_deleting_existing_packages(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            venv.EnvBuilder(with_pip=False, symlinks=True).create(home / '.venv')
            python = home / '.venv/bin/python3'
            library = Path(subprocess.check_output([str(python), '-c', 'import sysconfig; print(sysconfig.get_path("purelib"))'], text=True).strip())
            (library / 'capri_user_marker.py').write_text('VALUE = 42\n')
            bootstrap.ensure_environment(home)
            result = subprocess.check_output([str(python), '-c', 'import pip,capri_user_marker; print(capri_user_marker.VALUE)'], text=True)
            self.assertEqual('42', result.strip())

    def test_interrupted_seed_repairs_only_partial_pip_and_keeps_user_module(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            venv.EnvBuilder(with_pip=False, symlinks=True).create(home / '.venv')
            python = home / '.venv/bin/python3'
            library = Path(subprocess.check_output([str(python), '-c', 'import sysconfig; print(sysconfig.get_path("purelib"))'], text=True).strip())
            (library / 'pip').mkdir()
            (library / 'pip/__init__.py').write_text('# interrupted seed')
            (library / 'capri_user_marker.py').write_text('VALUE = 42\n')
            (home / '.capri-pip-seeding').touch()
            bootstrap.ensure_environment(home)
            result = subprocess.check_output([str(python), '-c', 'import pip._internal.cli.main,capri_user_marker; print(capri_user_marker.VALUE)'], text=True)
            self.assertEqual('42', result.strip())
            self.assertFalse((home / '.capri-pip-seeding').exists())


if __name__ == '__main__':
    unittest.main()
