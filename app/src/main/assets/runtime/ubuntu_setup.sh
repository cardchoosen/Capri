#!/bin/sh
set -eu
export DEBIAN_FRONTEND=noninteractive
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
# Guest root is only a PRoot identity. apt must not drop to an inaccessible Android UID.
mkdir -p /etc/apt/apt.conf.d /usr/sbin /var/cache/apt/archives/partial /var/lib/apt/lists/partial
printf 'APT::Sandbox::User "root";\nAcquire::Retries "2";\nAcquire::http::Timeout "30";\nAcquire::https::Timeout "30";\n' > /etc/apt/apt.conf.d/99capri
printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
chmod 755 /usr/sbin/policy-rc.d
# Keep Ubuntu archive signatures and keys; never use trusted=yes or allow-unauthenticated.
cat > /etc/apt/sources.list.d/ubuntu.sources <<'EOF'
Types: deb
URIs: http://ports.ubuntu.com/ubuntu-ports
Suites: noble noble-updates noble-security
Components: main universe
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF
apt-get update -qq
# A killed/interrupted prior attempt may leave unpacked packages; resume without deleting user data.
dpkg --configure -a || apt-get -f install -y --no-install-recommends
apt-get install -y --no-install-recommends python3 python3-venv ca-certificates git curl openssh-client
# All subsequent package requests use HTTPS as well as Ubuntu's package signatures.
sed -i 's|http://ports.ubuntu.com|https://ports.ubuntu.com|' /etc/apt/sources.list.d/ubuntu.sources
python3 -c 'import ssl,venv,sqlite3; print("CAPRI_UBUNTU_BASE_READY")'
