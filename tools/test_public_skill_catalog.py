import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('public_catalog', Path(__file__).parent / 'public_skills_repository/tools/catalog.py')
catalog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalog)


class CatalogTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.skill = self.root / 'skills/example-skill'
        self.skill.mkdir(parents=True)
        (self.skill / 'SKILL.md').write_text('---\nname: example-skill\ndescription: Read example data and report evidence.\n---\n\nRead the input.\n')

    def test_deterministic_manifest_and_all_resources_are_hashed(self):
        (self.skill / 'script.py').write_text('print("example")\n')
        first = catalog.build(self.root)
        self.assertEqual(first, catalog.build(self.root))
        self.assertEqual(2, len(first['skills'][0]['files']))
        (self.skill / 'script.py').write_text('print("changed")\n')
        self.assertNotEqual(first['skills'][0]['revision'], catalog.build(self.root)['skills'][0]['revision'])

    def test_crlf_skill_metadata_keeps_original_resource_hashes(self):
        file = self.skill / 'SKILL.md'
        raw = file.read_bytes().replace(b'\n', b'\r\n')
        file.write_bytes(raw)
        self.assertEqual(catalog.digest(raw), catalog.build(self.root)['skills'][0]['files'][0]['sha256'])

    def test_manifest_uses_full_relative_path_order(self):
        (self.skill / 'references.md').write_text('Overview')
        (self.skill / 'references').mkdir()
        (self.skill / 'references/a.md').write_text('Details')
        paths = [f['path'] for f in catalog.build(self.root)['skills'][0]['files']]
        self.assertEqual(['SKILL.md', 'references.md', 'references/a.md'], paths)

    def test_empty_catalog_is_valid(self):
        self.assertEqual([], catalog.build(self.root / 'empty')['skills'])

    def test_secrets_hidden_files_and_binary_resources_are_rejected(self):
        for name, data in [('key.txt', b'token=abcdefghijklmnopqrstuvwxyz'), ('.env', b'private'), ('image.png', b'\x00\x01')]:
            file = self.skill / name
            file.write_bytes(data)
            with self.assertRaises(ValueError):
                catalog.build(self.root)
            file.unlink()

    def test_symlinks_and_invalid_frontmatter_are_rejected(self):
        file = self.skill / 'linked.txt'
        file.symlink_to(self.skill / 'SKILL.md')
        with self.assertRaises(ValueError):
            catalog.build(self.root)
        file.unlink()
        (self.skill / 'SKILL.md').write_text('---\nname: wrong\n---\n')
        with self.assertRaises(ValueError):
            catalog.build(self.root)

    def test_file_and_byte_capacity_are_explicit(self):
        (self.skill / 'large.txt').write_bytes(b'x' * 1_000_001)
        with self.assertRaises(ValueError):
            catalog.build(self.root)
