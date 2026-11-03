import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

_spec = importlib.util.spec_from_file_location('rea_runtime', Path(__file__).parents[1] / 'app/src/main/assets/rea/runtime.py')
rea = importlib.util.module_from_spec(_spec); _spec.loader.exec_module(rea)


class ReaRuntimeTest(unittest.TestCase):
    def test_valid_cached_dependency_requires_matching_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory); url = 'https://example.invalid/pinned'
            target = cache / hashlib.sha256(url.encode()).hexdigest(); target.write_bytes(b'verified')
            spec = {'url': url, 'sha256': hashlib.sha256(b'verified').hexdigest()}
            with patch.object(rea, 'ROOT', cache), patch.object(rea.urllib.request, 'urlopen', side_effect=AssertionError('No network expected')):
                self.assertEqual(target, rea.fetch(spec, cache))
            target.write_bytes(b'changed')
            with patch.object(rea, 'ROOT', cache), patch.object(rea.urllib.request, 'urlopen', side_effect=RuntimeError('network unavailable')):
                with self.assertRaises(RuntimeError): rea.fetch(spec, cache)

    def test_bad_download_is_not_accepted_and_partial_is_removed(self):
        class Response(io.BytesIO): url = 'https://example.invalid/pinned'
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            with patch.object(rea, 'ROOT', cache), patch.object(rea.urllib.request, 'urlopen', return_value=Response(b'wrong')):
                with self.assertRaisesRegex(ValueError, 'integrity'): rea.fetch({'url': Response.url, 'sha256': '0' * 64}, cache)
            self.assertEqual([], [p for p in cache.iterdir() if p.name != "progress.json"])

    def test_archive_cannot_escape_or_create_devices(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); archive=root/'bad.tar'
            with tarfile.open(archive, 'w') as tar:
                member=tarfile.TarInfo('../escape');member.size=1;tar.addfile(member,io.BytesIO(b'x'))
            if hasattr(tarfile, 'data_filter'):
                with self.assertRaises(tarfile.FilterError): rea.extract(archive,root/'destination')
            else:
                with self.assertRaisesRegex(RuntimeError, 'safe tar'): rea.extract(archive,root/'destination')
            self.assertFalse((root/'escape').exists())

    def test_profiles_are_scoped_and_do_not_enable_network_capture_or_adb(self):
        for profile in rea.PROFILES:
            env=rea.environment(profile,Path('/managed'))
            self.assertNotIn('adb',env['REA_MCP_TOOL_FAMILIES'].split(','))
            self.assertNotIn('pcap',env['REA_MCP_TOOL_FAMILIES'].split(','))
            self.assertEqual('768',env['REA_JADX_HEAP_MIB'])
        with self.assertRaises(ValueError): rea.environment('unknown')
        self.assertIn('android',rea.PROFILES['apk'])
        self.assertEqual('frida,application,session',rea.PROFILES['runtime'])

    def test_proxy_filters_catalog_and_denies_unadvertised_tools(self):
        value={'jsonrpc':'2.0','id':1,'result':{'tools':[{'name':'inspect_android_package','inputSchema':{'type':'object'}},{'name':'load_frida_script'}]}}
        filtered,forward=rea.filter_message(value,'server',{'inspect_android_package'})
        self.assertTrue(forward);self.assertEqual(['inspect_android_package'],[x['name'] for x in filtered['result']['tools']])
        refused,forward=rea.filter_message({'jsonrpc':'2.0','id':2,'method':'tools/call','params':{'name':'load_frida_script'}},'client',{'inspect_android_package'})
        self.assertFalse(forward);self.assertEqual(-32601,refused['error']['code']);self.assertEqual(2,refused['id'])
        request={'jsonrpc':'2.0','id':3,'method':'tools/call','params':{'name':'inspect_android_package','arguments':{'path':'/workspace/fixture.apk'}}}
        forwarded,forward=rea.filter_message(request,'client',{'inspect_android_package'})
        self.assertTrue(forward);self.assertEqual(request,forwarded)

    def test_large_evidence_is_retained_without_claiming_success(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);reply={'jsonrpc':'2.0','id':7,'result':{'content':[{'type':'text','text':'evidence'*1000}],'isError':True}}
            delivered=json.loads(rea.bounded_response(reply,root,limit=1000));saved=json.loads(delivered['result']['content'][0]['text'])
            original=(root/saved['result_file']).read_bytes()
            self.assertTrue(saved['source_is_error']);self.assertTrue(delivered['result']['isError']);self.assertEqual(reply,json.loads(original))
            self.assertEqual(hashlib.sha256(original).hexdigest(),saved['sha256'])

    def test_retention_refuses_symlink_escape_before_creating_directories(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);workspace=root/'workspace';outside=root/'outside';workspace.mkdir();outside.mkdir()
            (workspace/'.capri').symlink_to(outside,target_is_directory=True)
            reply={'jsonrpc':'2.0','id':7,'result':{'content':[{'type':'text','text':'evidence'*1000}]}}
            with self.assertRaisesRegex(RuntimeError,'escapes'):rea.bounded_response(reply,workspace,limit=1000)
            self.assertEqual([],list(outside.iterdir()))

    def test_marker_alone_never_proves_runtime_is_ready(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'installed-apk.json').write_text(json.dumps({'source':{}}))
            state=rea.status('apk',root)
            self.assertFalse(state['installed']);self.assertEqual('unverified',state['validation'])

if __name__ == '__main__': unittest.main()
