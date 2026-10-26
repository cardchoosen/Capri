# Public eMudhra incident certificate fixtures

Source: Mozilla Bug 2083449, original reporter's public attachments 9664066 and 9664067:
https://bugzilla.mozilla.org/show_bug.cgi?id=2083449

Only the 16 public X.509 `.crt.pem` files are included. No private keys, signatures, messages, or executable attachments are included. Certificate DER SHA-256 fingerprints were checked against the original report/comment 2 on 2026-10-26; SPKI SHA-256 values were computed from those certificates. The application ships only the fingerprint rules, not these certificate fixtures. Two public fixtures are also used by the Android instrumentation test.
