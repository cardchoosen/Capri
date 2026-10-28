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
        for method in ('send', 'web_delete', 'web_create', 'web_stop', 'web_select'):
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

if __name__ == '__main__': unittest.main()
