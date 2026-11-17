#!/usr/bin/env bash
set -euo pipefail
if [[ "$EUID" -ne 0 ]]; then
    echo 'Run: sudo bash install-docker.sh' >&2
    exit 1
fi
if docker compose version >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    echo 'Docker Engine and Compose are already available.'
    exit 0
fi
if [[ "$(uname -m)" != aarch64 ]]; then
    echo 'This installer requires Raspberry Pi OS / Debian / Ubuntu 64-bit (aarch64).' >&2
    exit 1
fi
. /etc/os-release
case "$ID" in
    debian) docker_distro=debian; docker_codename="$VERSION_CODENAME" ;;
    ubuntu) docker_distro=ubuntu; docker_codename="${UBUNTU_CODENAME:-$VERSION_CODENAME}" ;;
    *) echo "Unsupported OS: $ID. Install Docker manually first." >&2; exit 1 ;;
esac
# Use Docker's signed apt repository; preserve existing engines/data.
apt-get update
apt-get install -y ca-certificates curl
install -m 0755 -d /etc/apt/keyrings
curl -fsSL "https://download.docker.com/linux/$docker_distro/gpg" -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/$docker_distro
Suites: $docker_codename
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
docker compose version
