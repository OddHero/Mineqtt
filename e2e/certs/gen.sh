#!/bin/sh
# Generates the test certificates for the TLS listeners of e2e/docker-compose.yml:
#   ca.crt / ca.key           test CA; point tls.caFile at ca.crt
#   server.crt / server.key   broker certificate for localhost, 127.0.0.1 and the compose service name mosquitto
#   wrong-ca.crt              an unrelated CA, for the "wrong CA fails with a clear status" check (item 2)
# Run it from anywhere with: sh e2e/certs/gen.sh [--force]. Existing files are kept unless --force is given.
# Everything here is for local testing only and is ignored by git.
set -eu

# Git Bash on Windows would otherwise rewrite the "/CN=..." subjects into file system paths.
export MSYS_NO_PATHCONV=1

cd "$(dirname "$0")"

if [ -f ca.crt ] && [ "${1:-}" != "--force" ]; then
    echo "Certificates already exist in $(pwd); pass --force to regenerate them."
    exit 0
fi

openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 825 \
    -keyout ca.key -out ca.crt -subj "/CN=MineQTT E2E Test CA"

openssl req -newkey rsa:2048 -nodes -sha256 \
    -keyout server.key -out server.csr -subj "/CN=localhost"

cat > server.ext <<'EOF'
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = DNS:localhost, DNS:mosquitto, IP:127.0.0.1
EOF

openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
    -sha256 -days 825 -extfile server.ext -out server.crt

openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 825 \
    -keyout wrong-ca.key -out wrong-ca.crt -subj "/CN=MineQTT E2E Wrong CA"

rm -f server.csr server.ext ca.srl wrong-ca.key
# The broker container runs as its own user and must be able to read the key.
chmod 644 server.key
echo "Wrote ca.crt, server.crt, server.key and wrong-ca.crt to $(pwd)"
