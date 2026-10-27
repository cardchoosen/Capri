#!/usr/bin/env python3
"""Fast driver routing regression using real HTTP handler requests, with no sockets or device impersonation."""
import contextlib
import io
from pathlib import Path
import tempfile
from unittest.mock import patch
import gateway_driver


def main():
    token='0123456789abcdef'
    route='/capri-gateway-driver/'+token
    cases=[('GET','/start','192.168.1.8',302),('GET','/','192.168.1.8',302),
           ('GET',route+'/','192.168.1.8',302),('GET',route,'192.168.1.8',200),
           ('GET','/capri-gateway-driver/old','192.168.1.8',404),
           ('GET','/start','192.168.1.9',403),('GET','/_driver_health','192.168.1.3',200),
           ('HEAD','/_driver_health','192.168.1.3',200)]
    responses=[]
    class Connection:
        def __init__(self,method,path):self.request=io.BytesIO(f'{method} {path} HTTP/1.1\r\nHost: 192.168.1.3\r\nConnection: close\r\n\r\n'.encode());self.response=bytearray()
        def makefile(self,*args):return self.request
        def settimeout(self,*args):pass
        def sendall(self,data):self.response.extend(data)
    with tempfile.TemporaryDirectory(prefix='capri-driver-check-') as folder:
        class Server:
            def __init__(self,address,handler):self.handler=handler;self.index=0
            def handle_request(self):
                if self.index==len(cases):Path(folder,'stop').touch();return
                method,path,source,expected=cases[self.index]
                conn=Connection(method,path);self.handler(conn,(source,12345),self)
                responses.append(bytes(conn.response));self.index+=1
            def server_close(self):pass
        with patch.object(gateway_driver,'HTTPServer',Server),patch('sys.argv',['gateway_driver','--token',token,'--output',folder]),contextlib.redirect_stdout(io.StringIO()):
            gateway_driver.main()
    for case,response in zip(cases,responses):
        method,path,source,expected=case
        header,body=response.split(b'\r\n\r\n',1)
        assert int(header.split()[1])==expected,(path,response[:100])
        if expected==302:assert ('Location: '+route).encode() in header
        if expected==404:assert route.encode() in body and b'No driver at this path' not in body
        if method=='HEAD':assert not body
    assert len(responses)==len(cases)
    print(f'{len(cases)} HTTP driver route checks passed (in-process, no live vivo claim)')


if __name__=='__main__':main()
