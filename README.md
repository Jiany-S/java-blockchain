# java-blockchain

A minimal blockchain node written in Java. It implements a simple account-based protocol with Proof-of-Work mining, RocksDB-backed persistence, wallet management, and optional REST/RPC front-ends for interacting with the node.

## Features

- **Protocol & Consensus** - transactions with nonce/fee/signature fields, block headers with Merkle roots, configurable PoW difficulty
- **Mining** - background scheduler attempts a block every second; configurable miner address and block reward; fees accrue to the miner
- **P2P** - Netty-based gossip with automatic ping/pong heartbeats and idle peer eviction
- **State Management** - account balances and nonces, deterministic replay from the persisted chain, demo flow for quick experiments
- **Storage** - RocksDB chain store (with an in-memory implementation for tests)
- **Wallets** - persistent EC key generation, optional passphrase encryption, automatic address derivation
- **APIs** - REST API, RPC endpoints, and Prometheus metrics
- **Tooling** - Gradle build, cross-platform dev scripts, Docker image & docker-compose example

## Requirements

- JDK 21 (Temurin recommended)
- Gradle wrapper included (no local Gradle install necessary)
- RocksDB JNI is pulled automatically by Gradle

## Quick Start (Demo Mode)

```bash
./gradlew :app:run --args="--data-dir=./app/data/chain"
```

The default run executes a short demo: creates two wallets, submits a sample transaction, mines a block, prints metrics, and exits after five seconds. Wallets are persisted under `./app/data/chain/wallets`.

To inspect the available CLI options:

```bash
./gradlew :app:run --args="--help"
```

## CLI Overview

| Option | Description |
| --- | --- |
| `--data-dir=<path>` | Directory for blockchain data (default `./data/chain`) |
| `--reset-chain` | Delete chain data (wallets preserved) before starting |
| `--regen-genesis` | Regenerate `genesis-alloc.json` from known wallets |
| `--keep-alive` | Keep the node running until interrupted |
| `--demo / --no-demo` | Enable (default) or disable the demo transaction flow |
| `--demo-duration-ms=<ms>` | When not in keep-alive mode, how long to wait before shutting down (default 5000) |
| `--enable-api` | Start the REST API (default bind `127.0.0.1:8080`) |
| `--api-bind=<host>` | Bind address for the REST API |
| `--api-port=<port>` | REST API port (default 8080) |
| `--api-token=<token>` | Require Bearer or `X-API-Key` token for the REST API |
| `--enable-rpc` | Start the RPC server (default bind `127.0.0.1:9090`) |
| `--rpc-bind=<host>` | Bind address for the RPC server |
| `--rpc-port=<port>` | RPC port (default 9090) |
| `--rpc-token=<token>` | Require Bearer or `X-API-Key` token for the RPC server |
| `--unsafe-public` | Acknowledge public exposure and keep custom binds without tokens |
| `--no-p2p` | Disable the Netty listener (enabled by default) |
| `--p2p-port=<port>` | P2P port (default 9000) |
| `--miner-address=<addr>` | Address credited with block rewards and transaction fees |
| `--block-reward-minor=<n>` | Base block reward in minor units (default 50) |

Environment overrides:
- `JAVA_CHAIN_DATA_DIR`, `JAVA_CHAIN_API_TOKEN`, `JAVA_CHAIN_RPC_TOKEN`, `JAVA_CHAIN_NODE_ID`
- `JAVA_CHAIN_ENABLE_API`, `JAVA_CHAIN_ENABLE_RPC`, `JAVA_CHAIN_ENABLE_P2P`, `JAVA_CHAIN_P2P_PEERS`
- `JAVA_CHAIN_MINER_ADDRESS`, `JAVA_CHAIN_BLOCK_REWARD_MINOR`, `JAVA_CHAIN_KEEP_ALIVE`, `JAVA_CHAIN_UNSAFE_PUBLIC`

## Mining & Rewards

The keep-alive mode starts a background scheduler that calls `node.tick()` once per second. Each successful block credits the configured miner address with the base block reward plus the transaction fees included in the block.

- Configure the miner address via `--miner-address=<addr>` (or `JAVA_CHAIN_MINER_ADDRESS`). If omitted, the first demo wallet (`alice`) is used.
- Configure the base reward via `--block-reward-minor=<n>` (or `JAVA_CHAIN_BLOCK_REWARD_MINOR`). The default is `50` minor units.
- Rewards are paid into the state store immediately after the block is persisted; if persistence fails, both the state changes and reward are rolled back.

## Fork Handling

Blocks are now accepted even when they do not extend the current head. The chain store persists parent/child relationships so alternative branches can be inspected. Fork choice now tracks cumulative work and picks the heaviest branch; height only breaks ties, leaving room for future fork-choice refinements.

- `ChainStore#getChildren(parentHash)` returns the child hashes for a block.
- The RocksDB-backed store maintains dedicated column families for child lookups and cumulative work metrics.

## Validation

Consensus validation enforces several invariants before a block is persisted:

- The parent hash must already exist in the local store (genesis links to the all-zero hash).
- Heights increase monotonically (`height = parentHeight + 1`).
- Timestamps never move backwards relative to the parent and may be at most 60s in the future compared to the local clock.
- Merkle roots must match the transactions supplied in the block body.
- Proof-of-Work targets are honoured for every header, and cumulative work is tracked per block to drive fork choice.

## P2P Heartbeats

The Netty peer-to-peer server periodically sends ping messages (default every 10s) and drops peers that remain idle for longer than the configured timeout (default 30s). This keeps gossip meshes healthy even when remote nodes vanish silently.

## API & RPC Validation

The REST and RPC submit/send endpoints now perform strict request validation. Malformed bodies return descriptive `400` errors instead of silently queuing invalid transactions, and authenticated tests exercise both the success and failure paths. Provide a valid Bearer or `X-API-Key` token to access protected endpoints.

## Security

Both REST and RPC services support token-based authentication. Set a token via CLI (`--api-token`, `--rpc-token`) or environment variables (`JAVA_CHAIN_API_TOKEN`, `JAVA_CHAIN_RPC_TOKEN`). Requests must include either:

- `Authorization: Bearer <token>` or
- `X-API-Key: <token>`

If you enable the API or RPC services without supplying a token, the node prints a bright security warning and automatically forces the bind address to `127.0.0.1`. Pass `--unsafe-public` (or set `JAVA_CHAIN_UNSAFE_PUBLIC=true`) only when you explicitly want to expose the services on a public interface.

In production deployments always provide a token (and keep the services behind a private interface or reverse proxy).

The node logs periodic `announce` messages over P2P containing the current chain height so peers can detect lagging branches.

## Continuous Integration & Releases

- **build.yml** runs on every push and pull request to `main` across `{ubuntu, windows, macos}` by `{Java 17, Java 21}`. It caches Gradle, executes `:app:test jacocoTestReport`, and uploads both JUnit and JaCoCo reports. Mark each generated status check (e.g. `build (ubuntu-latest, Java 21)`) as required in your branch protection for `main`.
- **release.yml** builds the shaded JAR (`java-blockchain-all.jar`) whenever a tag matching `v*` is pushed, uploads it as a workflow artifact, and attaches it to the GitHub release automatically.
- **docker-build-push.yml** builds `docker/Dockerfile` and publishes `ghcr.io/jiany-s/java-blockchain:<tag>` (and `:latest`) for tagged releases. Ensure the repository has Packages permissions enabled for the default `GITHUB_TOKEN`.

## REST & RPC APIs

- REST base endpoints: `/balance`, `/submit`, `/chain`, `/wallets`, `/wallets/send`
- RPC endpoints: `/status`, `/balance`, `/tx`, `/wallet/list`, `/wallet/create`, `/wallet/send`
- OpenAPI descriptions: `/openapi.json` on both servers

Example curl with auth:

```bash
curl -H "Authorization: Bearer $API_TOKEN" http://localhost:8080/balance?address=<hex-address>
```

## Metrics

Prometheus-compatible metrics are available at `/metrics`. The `http.server.requests` series records request latency, and mining statistics are exposed via `blocks.mined` and `block.mining.time`.

## Docker

Build and run using Docker Compose:

```bash
cd docker
docker compose up --build
```

Environment variables in `docker-compose.yml` control ports and tokens (set `API_TOKEN`/`RPC_TOKEN` before exposing the services). Data is stored under `app/data` on the host.

## Developer Scripts

- `scripts/run-dev.sh` (macOS/Linux)
- `scripts/run-dev.ps1` (Windows PowerShell)

Both scripts build the distribution if necessary and run the node with sensible defaults (`--keep-alive`, REST/RPC enabled, binds on `0.0.0.0`). Override behaviour with environment variables such as `API_TOKEN`, `RPC_TOKEN`, `RUN_DEMO=false`, etc.

## Testing

```bash
./gradlew :app:test
```

Unit tests cover transaction serialization, PoW target checks, state replay, and chain store behaviour.

## Contributing

Issues and pull requests are welcome. Please keep patches focused, include tests where possible, and run `./gradlew :app:compileJava :app:test` before submitting.

## License

MIT License. See [LICENSE](LICENSE).

## Author

Jiany Samara (Jiany-S).
