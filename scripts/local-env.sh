#!/usr/bin/env bash
# Source this file; changes only this shell, never login/startup settings.
if [[ -d /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if command -v podman >/dev/null 2>&1 && podman machine inspect memos-local >/dev/null 2>&1; then
  memos_socket="$(podman machine inspect memos-local --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
  if [[ -S "$memos_socket" ]]; then
    export DOCKER_HOST="unix://$memos_socket"
    export TESTCONTAINERS_RYUK_DISABLED=true
  fi
  unset memos_socket
fi
