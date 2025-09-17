#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
INSTALL_DIR="$ROOT_DIR/app/build/install/app"
APP_BIN="$INSTALL_DIR/bin/app"

if [[ ! -x "$APP_BIN" ]]; then
  (cd "$ROOT_DIR" && ./gradlew :app:installDist)
fi

DATA_DIR=${DATA_DIR:-"$ROOT_DIR/app/data/chain"}
API_BIND=${API_BIND:-0.0.0.0}
API_PORT=${API_PORT:-8080}
RPC_BIND=${RPC_BIND:-0.0.0.0}
RPC_PORT=${RPC_PORT:-9090}
P2P_PORT=${P2P_PORT:-9000}
API_TOKEN=${API_TOKEN:-${JAVA_CHAIN_API_TOKEN:-}}
RPC_TOKEN=${RPC_TOKEN:-${JAVA_CHAIN_RPC_TOKEN:-}}

CLI_ARGS=("--data-dir=$DATA_DIR" "--regen-genesis" "--keep-alive")

if [[ "${RESET_CHAIN:-false}" == "true" ]]; then
  CLI_ARGS+=("--reset-chain")
fi

if [[ "${ENABLE_API:-true}" != "false" ]]; then
  CLI_ARGS+=("--enable-api" "--api-bind=$API_BIND" "--api-port=$API_PORT")
  if [[ -n "$API_TOKEN" ]]; then
    CLI_ARGS+=("--api-token=$API_TOKEN")
  fi
fi

if [[ "${ENABLE_RPC:-true}" != "false" ]]; then
  CLI_ARGS+=("--enable-rpc" "--rpc-bind=$RPC_BIND" "--rpc-port=$RPC_PORT")
  if [[ -n "$RPC_TOKEN" ]]; then
    CLI_ARGS+=("--rpc-token=$RPC_TOKEN")
  fi
fi

if [[ "${ENABLE_P2P:-true}" == "false" ]]; then
  CLI_ARGS+=("--no-p2p")
else
  CLI_ARGS+=("--p2p-port=$P2P_PORT")
fi

if [[ "${RUN_DEMO:-true}" == "false" ]]; then
  CLI_ARGS+=("--no-demo")
fi

CLI_ARGS+=("$@")

mkdir -p "$DATA_DIR"
exec "$APP_BIN" "${CLI_ARGS[@]}"
