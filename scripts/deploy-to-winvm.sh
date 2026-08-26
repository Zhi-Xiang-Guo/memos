#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <app-name> <port>" >&2
}

if [[ $# -ne 2 ]]; then
  usage
  exit 64
fi

app_name=$1
port=$2

if [[ ! $app_name =~ ^[a-z0-9_-]+$ ]]; then
  echo "Error: app-name may contain only lowercase letters, digits, '_' and '-'." >&2
  exit 64
fi

if [[ ! $port =~ ^[0-9]+$ ]]; then
  echo "Error: port must be an integer from 1024 through 65535." >&2
  exit 64
fi

port_number=$((10#$port))
if ((port_number < 1024 || port_number > 65535)); then
  echo "Error: port must be from 1024 through 65535." >&2
  exit 64
fi

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
project_root=$(cd -- "$script_dir/.." && pwd)

if [[ ! -f "$project_root/Dockerfile" ]]; then
  echo "Error: project root does not contain a Dockerfile: $project_root" >&2
  exit 66
fi

echo "Deploying '$app_name' from '$project_root' to winvm on port $port_number..."

tar \
  --exclude='.git' \
  --exclude='*/.git' \
  --exclude='node_modules' \
  --exclude='*/node_modules' \
  --exclude='.venv' \
  --exclude='*/.venv' \
  --exclude='target' \
  --exclude='*/target' \
  --exclude='build' \
  --exclude='*/build' \
  -C "$project_root" \
  -cf - . \
  | ssh winvm "/opt/deployer/bin/receive-deploy '$app_name' '$port_number'"
