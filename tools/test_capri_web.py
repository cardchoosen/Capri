import http.client
import json
import threading
import unittest
from capri_web import server_for


class FakeBridge:
    def __init__(self): self.calls = []
    def call(self, method, args):
        self.calls.append((method, args))
        return {'sessions': [], 'echo': args}


class WebBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.bridge = FakeBridge()
        self.server, self.token = server_for(self.bridge, 0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.origin = f'http://127.0.0.1:{self.server.server_port}'

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join()

    def request(self, path='/api/call', method='POST', **headers):
        conn = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=3)
        defaults = {'Host': self.origin[7:], 'Origin': self.origin,
                    'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json'}
        defaults.update(headers)
        body = json.dumps({'method': 'web_state', 'args': {'chat_id': '手机会话'}}) if method == 'POST' else None
        conn.request(method, path, body=body, headers=defaults)
        result = conn.getresponse(); data=result.read(); status=result.status
        response_headers=dict(result.getheaders());conn.close()
        return status, data, response_headers

    def test_authorized_same_origin_reads_phone_and_does_not_cache(self):
        status, data, headers = self.request()
        self.assertEqual(200, status)
        self.assertEqual('手机会话', json.loads(data)['echo']['chat_id'])
        self.assertEqual('no-store', headers['Cache-Control'])
        self.assertEqual(1, len(self.bridge.calls))

    def test_cross_origin_missing_token_and_dns_rebinding_never_reach_phone(self):
        for headers, status in [({'Origin': 'https://evil.example'}, 403),
                                ({'Authorization': ''}, 401),
                                ({'Host': 'evil.example'}, 403)]:
            self.assertEqual(status, self.request(**headers)[0])
        self.assertEqual([], self.bridge.calls)

    def test_no_file_traversal_and_no_external_scripts(self):
        self.assertEqual(404, self.request('/../capri_web.py', method='GET')[0])
        status, data, headers = self.request('/', method='GET')
        self.assertEqual(200, status)
        self.assertIn(b'/app.js', data)
        self.assertIn("frame-ancestors 'none'", headers['Content-Security-Policy'])
        self.assertNotIn('Access-Control-Allow-Origin', headers)

    def test_non_json_request_is_rejected_before_dispatch(self):
        self.assertEqual(400, self.request(**{'Content-Type': 'text/plain'})[0])
        self.assertEqual([], self.bridge.calls)


class AttachmentEndpointTest(unittest.TestCase):
    setUp = WebBoundaryTest.setUp
    tearDown = WebBoundaryTest.tearDown
    request = WebBoundaryTest.request
    def test_attachment_stream_preserves_bytes_and_releases_lock_between_chunks(self):
        import base64
        content = b'GIF89a' + bytes(range(256)) * 300
        def call(method, args):
            self.bridge.calls.append((method, args))
            offset = args['offset']; data = content[offset:offset + 65536]
            return {'data': base64.b64encode(data).decode(), 'offset': offset, 'next_offset': offset + len(data),
                    'size': len(content), 'mime': 'image/gif', 'name': '动画.gif'}
        self.bridge.call = call
        status, body, headers = self.request('/api/attachment')
        self.assertEqual(200, status)
        self.assertEqual(content, body)
        self.assertEqual('image/gif', headers['Content-Type'])
        self.assertEqual([0, 65536], [args['offset'] for _, args in self.bridge.calls])

    def test_attachment_missing_or_changed_is_not_exposed_as_file(self):
        self.bridge.call = lambda method, args: {'error': '附件已变化'}
        status, body, headers = self.request('/api/attachment')
        self.assertEqual(409, status)
        self.assertIn('附件已变化', body.decode())

    def test_attachment_requires_same_origin_and_token(self):
        self.assertEqual(401, self.request('/api/attachment', Authorization='')[0])
        self.assertEqual(403, self.request('/api/attachment', Origin='https://elsewhere.example')[0])
        self.assertEqual([], self.bridge.calls)


class PersistentTransportTest(unittest.TestCase):
    def bridge(self):
        from capri_web import Bridge
        return Bridge('unused-adb', 'test-device')

    def test_multiple_reads_reuse_one_phone_connection(self):
        import io
        from unittest.mock import Mock
        bridge = self.bridge()
        connection = Mock()
        def connect():
            bridge.connection = connection
            bridge.reader = io.BytesIO(b'{"revision":1}\n{"revision":2}\n')
            bridge.phone_token = 'phone-only-token'
        bridge._connect = Mock(side_effect=connect)
        self.assertEqual(1, bridge.call('web_state', {})['revision'])
        self.assertEqual(2, bridge.call('web_state', {})['revision'])
        self.assertEqual(1, bridge._connect.call_count)
        self.assertEqual(2, connection.sendall.call_count)
        bridge.close()

    def test_transport_failure_never_replays_mutations(self):
        from unittest.mock import Mock
        for method in ('send', 'web_delete', 'web_create', 'web_stop', 'web_select', 'web_runtime', 'web_revise'):
            with self.subTest(method=method):
                bridge = self.bridge()
                connection = Mock()
                connection.sendall.side_effect = BrokenPipeError('disconnected')
                bridge.connection = connection
                bridge._connect = Mock()
                with self.assertRaises(RuntimeError):
                    bridge.call(method, {'chat_id':'chat','epoch':'epoch','request_id':'request','text':'hello'})
                self.assertEqual(1, connection.sendall.call_count)
                bridge._connect.assert_not_called()

    def test_read_can_reconnect_once_without_replaying_mutations(self):
        import io
        from unittest.mock import Mock
        for method in ('web_state', 'web_catalog'):
            with self.subTest(method=method):
                bridge = self.bridge()
                old = Mock(); old.sendall.side_effect = BrokenPipeError('disconnected')
                new = Mock(); bridge.connection = old
                def connect():
                    bridge.connection = new
                    bridge.reader = io.BytesIO(b'{"revision":3}\n')
                    bridge.phone_token = 'new-token'
                bridge._connect = Mock(side_effect=connect)
                self.assertEqual(3, bridge.call(method, {})['revision'])
                self.assertEqual(1, old.sendall.call_count)
                self.assertEqual(1, new.sendall.call_count)
                bridge.close()

class RecoveryTest(unittest.TestCase):
    def test_selected_phone_is_pinned_and_unauthorized_devices_are_not_started(self):
        from capri_web import DeviceTarget, BridgeError
        from unittest.mock import patch, Mock
        target = DeviceTarget()
        def devices(value): return Mock(stdout='List of devices attached\n' + value)
        with patch('capri_web.subprocess.run', return_value=devices('phone-a device usb:1-1 model:test\n')):
            self.assertEqual('phone-a', target.resolve('adb'))
        with patch('capri_web.subprocess.run', return_value=devices('phone-b device usb:1-2 model:test\n')):
            with self.assertRaises(BridgeError) as error: target.resolve('adb')
            self.assertEqual('device_offline', error.exception.code)
        with patch('capri_web.subprocess.run', return_value=devices('phone-a unauthorized usb:1-1\n')):
            with self.assertRaises(BridgeError) as error: target.resolve('adb')
            self.assertEqual('device_unauthorized', error.exception.code)
        with patch('capri_web.subprocess.run', return_value=devices('a device usb:1-1\nb device usb:1-2\n')):
            with self.assertRaises(BridgeError) as error: DeviceTarget().resolve('adb')
            self.assertEqual('device_ambiguous', error.exception.code)

    def test_app_is_started_only_when_provider_reports_not_ready(self):
        import io
        from unittest.mock import Mock, patch
        from capri_web import Bridge
        for ready in (False, True, "provider_missing"):
            bridge=Bridge('adb','phone-a');bridge.target.resolve=Mock()
            info={'socket':'ai.capri.app.web.12345','token':'phone-token'}
            from capri_web import BridgeError
            sequence=[BridgeError('bridge_unavailable','missing'),info] if ready=='provider_missing' else [info] if ready else [{'error':'not_ready: open Capri first'},info]
            bridge._content=Mock(side_effect=sequence)
            connection=Mock();connection.makefile.return_value=io.BytesIO()
            with patch('capri_web.subprocess.run',return_value=Mock(stdout='12345\n')) as command, patch('capri_web.socket.create_connection',return_value=connection), patch('capri_web.time.sleep'):
                bridge._connect()
                starts=[c for c in command.call_args_list if 'start' in c.args[0]]
                self.assertEqual(0 if ready is True else 1,len(starts))
                self.assertEqual(1 if ready is True else 2,bridge._content.call_count)
                bridge.close()

    def test_expired_idle_socket_is_replaced_before_sending_a_mutation(self):
        import io
        from unittest.mock import Mock, patch
        from capri_web import Bridge
        bridge=Bridge('adb','phone');old=Mock();bridge.connection=old;bridge.last_used=1
        new=Mock()
        def connect():
            bridge.connection=new;bridge.reader=io.BytesIO(b'{"accepted":true}\n');bridge.phone_token='new'
        bridge._connect=Mock(side_effect=connect)
        with patch('capri_web.time.monotonic',return_value=12):
            self.assertTrue(bridge.call('send',{'chat_id':'chat','epoch':'e','request_id':'id'})['accepted'])
        old.sendall.assert_not_called();new.sendall.assert_called_once();bridge.close()

    def test_state_lane_is_independent_of_bulk_lane_and_shares_device_identity(self):
        from capri_web import BridgePool
        from unittest.mock import Mock
        bridge=BridgePool('adb','phone')
        bridge.state.call=Mock(return_value={'revision':1});bridge.work.call=Mock(return_value={'ok':True})
        self.assertIs(bridge.state.target,bridge.work.target)
        bridge.call('web_state',{});bridge.call('web_runtime',{})
        bridge.state.call.assert_called_once_with('web_state',{})
        bridge.work.call.assert_called_once_with('web_runtime',{})

    def test_device_tracker_parses_real_length_frames_including_disconnect(self):
        import io
        from capri_web import tracker_frame
        raw=b'phone device usb:1-1\n'
        stream=io.BytesIO(f'{len(raw):04x}'.encode()+raw+b'0000')
        self.assertEqual(raw.decode(),tracker_frame(stream))
        self.assertEqual('',tracker_frame(stream))
        with self.assertRaises(EOFError):tracker_frame(stream)
        with self.assertRaises(ValueError):tracker_frame(io.BytesIO(b'NOPE'))

    def test_device_event_wakes_phone_and_opens_browser_once_ready(self):
        import io
        from unittest.mock import Mock, patch
        from capri_web import DeviceMonitor, BridgeError
        bridge=Mock();bridge.state.adb_path='adb'
        bridge.call.side_effect=[BridgeError('app_not_ready','starting'),{'epoch':'new'}]
        raw=b'phone device usb:1-1\n'
        process=Mock();process.stdout=io.BytesIO(f'{len(raw):04x}'.encode()+raw);process.poll.return_value=0
        monitor=DeviceMonitor(bridge)
        opened=[]
        def connected(): opened.append(True);monitor.stopped.set()
        monitor.connected=connected
        monitor.stopped.wait=lambda timeout: monitor.stopped.is_set()
        with patch('capri_web.subprocess.Popen',return_value=process): monitor.run()
        self.assertEqual([True],opened)
        self.assertEqual(2,bridge.call.call_count)
        for call in bridge.call.call_args_list:self.assertEqual(('web_state',{}),call.args)

    def test_access_token_survives_service_restart_and_is_owner_only(self):
        import tempfile,os
        from pathlib import Path
        from capri_web import access_token
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'access.token'
            first=access_token(path);self.assertEqual(first,access_token(path))
            self.assertEqual(0o600,path.stat().st_mode & 0o777)
            link=Path(temp)/'link';link.symlink_to(path)
            with self.assertRaises(ValueError):access_token(link)

    def test_service_update_waits_until_launchd_unregisters_the_old_job(self):
        from capri_web_service import unload
        from unittest.mock import Mock, patch
        with patch('capri_web_service.launchctl',side_effect=[Mock(returncode=0),Mock(returncode=0),Mock(returncode=113)]) as control, patch('capri_web_service.time.sleep') as pause:
            unload('gui/501/ai.capri.web')
            self.assertEqual(['bootout','print','print'],[call.args[0] for call in control.call_args_list])
            pause.assert_called_once_with(.2)

    def test_autostart_uses_loopback_server_without_sending_tasks(self):
        from capri_web_service import configuration
        config=configuration('/usr/bin/adb','phone-a')
        self.assertTrue(config['RunAtLoad']);self.assertTrue(config['KeepAlive'])
        self.assertIn('--open-browser',config['ProgramArguments'])
        self.assertNotIn('send',config['ProgramArguments'])
        self.assertEqual(['--serial','phone-a'],config['ProgramArguments'][-2:])


class RecoveryEndpointTest(unittest.TestCase):
    setUp = WebBoundaryTest.setUp
    tearDown = WebBoundaryTest.tearDown
    request = WebBoundaryTest.request
    def test_connection_errors_have_actionable_codes(self):
        from capri_web import BridgeError
        def offline(*_):raise BridgeError('device_offline','Selected phone offline.')
        self.bridge.call=offline
        status,body,_=self.request()
        self.assertEqual(503,status)
        self.assertEqual('device_offline',json.loads(body)['code'])


if __name__ == '__main__': unittest.main()
