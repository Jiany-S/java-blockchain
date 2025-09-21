#!/usr/bin/env bash
set -euo pipefail

DATA_DIR=${DATA_DIR:-/data/chain}
API_BIND=${API_BIND:-0.0.0.0}
API_PORT=${API_PORT:-8080}
RPC_BIND=${RPC_BIND:-0.0.0.0}
RPC_PORT=${RPC_PORT:-9090}
P2P_PORT=${P2P_PORT:-9000}
API_TOKEN=${API_TOKEN:-${JAVA_CHAIN_API_TOKEN:-}}
RPC_TOKEN=${RPC_TOKEN:-${JAVA_CHAIN_RPC_TOKEN:-}}
NODE_ID=${NODE_ID:-${JAVA_CHAIN_NODE_ID:-}}
P2P_PEERS=${P2P_PEERS:-${JAVA_CHAIN_P2P_PEERS:-}}
ENABLE_API=${ENABLE_API:-true}
ENABLE_RPC=${ENABLE_RPC:-true}
ENABLE_P2P=${ENABLE_P2P:-true}
RUN_DEMO=${RUN_DEMO:-false}
RESET_CHAIN=${RESET_CHAIN:-false}

CLI_ARGS=("--data-dir=$DATA_DIR" "--keep-alive")

if [[ "$RESET_CHAIN" == "true" ]]; then
  CLI_ARGS+=("--reset-chain")
fi

if [[ "$ENABLE_API" != "false" ]]; then
  CLI_ARGS+=("--enable-api" "--api-bind=$API_BIND" "--api-port=$API_PORT")
  if [[ -n "$API_TOKEN" ]]; then
    CLI_ARGS+=("--api-token=$API_TOKEN")
  fi
fi

if [[ "$ENABLE_RPC" != "false" ]]; then
  CLI_ARGS+=("--enable-rpc" "--rpc-bind=$RPC_BIND" "--rpc-port=$RPC_PORT")
  if [[ -n "$RPC_TOKEN" ]]; then
    CLI_ARGS+=("--rpc-token=$RPC_TOKEN")
  fi
fi

if [[ "$ENABLE_P2P" == "false" ]]; then
  CLI_ARGS+=("--no-p2p")
else
  CLI_ARGS+=("--p2p-port=$P2P_PORT")
fi

if [[ -n "$NODE_ID" ]]; then
  CLI_ARGS+=("--node-id=$NODE_ID")
fi
if [[ -n "$P2P_PEERS" ]]; then
  IFS="," read -ra __peers <<< "$P2P_PEERS"
  for peer in "${__peers[@]}"; do
    if [[ -n "$peer" ]]; then
      CLI_ARGS+=("--p2p-peer=${peer// /}")
    fi
  done
fi

if [[ "$RUN_DEMO" != "true" ]]; then
  CLI_ARGS+=("--no-demo")
fi

CLI_ARGS+=("$@")

mkdir -p "$DATA_DIR"
exec /opt/app/bin/app "${CLI_ARGS[@]}"
