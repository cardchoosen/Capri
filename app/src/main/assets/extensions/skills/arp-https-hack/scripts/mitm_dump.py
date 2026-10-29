"""mitmproxy addon: dump decrypted request/response plaintext to a text file in real time.

The log path is overridable via env var MITM_LOG, default /workspace/mitm_plain.txt.
Usage: mitmdump --mode transparent -s mitm_dump.py
"""
from mitmproxy import http
import time
import os

LOG = os.environ.get("MITM_LOG", "/workspace/mitm_plain.txt")


def log(msg):
    try:
        with open(LOG, "a") as f:
            f.write(f"[{time.strftime('%H:%M:%S')}] {msg}\n")
    except Exception:
        pass


def request(flow):
    req = flow.request
    try:
        log(f"REQ {req.method} {req.pretty_url}")
    except Exception:
        pass


def response(flow):
    req = flow.request
    try:
        ct = flow.response.headers.get("content-type", "") if flow.response else ""
        body = ""
        if flow.response is not None and flow.response.content:
            textual = any(t in ct for t in ("json", "text", "html", "xml",
                                            "javascript", "x-www-form-urlencoded"))
            if textual:
                try:
                    body = flow.response.content.decode("utf-8", "replace")[:2500]
                except Exception:
                    body = f"[{len(flow.response.content)} bytes]"
            else:
                body = f"[{len(flow.response.content)} bytes {ct}]"
        status = flow.response.status_code if flow.response else "?"
        log(f"RESP {req.method} {req.pretty_url} -> {status}\n{body}")
    except Exception:
        pass
