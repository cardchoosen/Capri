"""Small fixed HTTP/1.1 fixtures. Synthetic data only; no file serving or external redirects."""
import gzip

PAYLOAD = bytes(range(256)) * 4


def protocol(handler, path, prefix, *, head=False):
    route = prefix + '/protocol/'
    if not path.startswith(route):
        return False
    case = path.removeprefix(route)
    if case == 'chunked' and not head:
        handler.send_response(200)
        handler.send_header('Content-Type', 'application/octet-stream')
        handler.send_header('Transfer-Encoding', 'chunked')
        handler.send_header('Connection', 'close')
        handler.end_headers()
        for data in (PAYLOAD[:300], PAYLOAD[300:700], PAYLOAD[700:]):
            handler.wfile.write(f'{len(data):x}\r\n'.encode() + data + b'\r\n')
        handler.wfile.write(b'0\r\n\r\n')
        return True
    status, data, headers = 200, b'{"fixture":true}', {'Content-Type': 'application/json'}
    if case == 'binary':
        data, headers = PAYLOAD, {'Content-Type': 'application/octet-stream'}
    elif case == 'gzip':
        data, headers = gzip.compress(PAYLOAD, mtime=0), {'Content-Type': 'application/octet-stream', 'Content-Encoding': 'gzip'}
    elif case == 'redirect':
        status, data, headers = 302, b'', {'Location': route + 'json'}
    elif case == 'status':
        status, data = 503, b'fixture unavailable'
    elif case not in ('json', 'head'):
        handler.reply(404, b'Unknown protocol fixture.', head=head)
        return True
    handler.send_response(status)
    for key, value in headers.items():
        handler.send_header(key, value)
    handler.send_header('Content-Length', str(len(data)))
    handler.send_header('Connection', 'close')
    handler.send_header('Cache-Control', 'no-store')
    handler.send_header('X-Capri-Fixture', 'protocol')
    handler.end_headers()
    if not head:
        handler.wfile.write(data)
    return True
