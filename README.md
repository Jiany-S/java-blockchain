# 🚀 java-blockchain

**A minimal blockchain node built from scratch in Java.**  
Implements the fundamentals of blockchain protocols: transactions, blocks, Proof-of-Work consensus, state management, a mempool, RocksDB persistence, and a minimal RPC API.

> 🎯 Goal: provide a clean, hackable reference implementation for learning and experimentation.

---

## ✨ Key Features

- **Protocol Layer**
  - Transactions with nonce, fee, signature fields
  - Blocks with headers, Merkle root, deterministic serialization
- **Consensus**
  - Proof-of-Work mining (configurable difficulty in “leading zero bits”)
  - Genesis block with custom allocations
- **State Management**
  - Account model: balances + nonces
  - Stateless + stateful transaction validation
- **Mempool**
  - Nonce ordering, fee checks, balance checks
  - Validates via `TxValidator`
- **Storage**
  - In-memory chain store for tests
  - Persistent RocksDB chain store for durability
- **Node Runtime**
  - Produces new blocks, applies state, persists chain
  - Seeds dev accounts (`alice123456`, `bob654321`) for demo transactions
- **RPC API (optional)**
  - `GET /status` → node height & head
  - `GET /balance?addr=...` → balance & nonce
  - `POST /tx` → submit a transaction
- **Developer-friendly**
  - Clear package structure
  - Gradle wrapper with JUnit 5 & SLF4J/Logback
  - Cross-platform (Linux, macOS, Windows)

---

## 🗂 Project Structure

```
app/
  src/
    main/java/io/blockchain/core/
      Main.java                 # Entry point
      protocol/                 # Transactions, Blocks, Merkle, Codecs
      consensus/                # ProofOfWork
      state/                    # StateStore
      mempool/                  # Mempool, TxValidator
      storage/                  # InMemoryChainStore, RocksDBChainStore
      node/                     # Node, BlockProducer, GenesisBuilder
      rpc/                      # Minimal HTTP RPC (optional)
    test/java/                  # JUnit tests
gradlew, gradlew.bat            # Gradle wrapper
build.gradle.kts                # Module build
settings.gradle.kts             # Project settings
```

---

## ⚙️ Requirements

- JDK 21 (tested), works on JDK ≥17  
- Gradle 9 (wrapper included)  
- RocksDB JNI (bundled, no manual install)

---

## 🚦 Quick Start

Clone & run:

```bash
git clone https://github.com/Jiany-S/java-blockchain
cd java-blockchain
./gradlew :app:run --args="./data/chain"
```

Windows (PowerShell):

```powershell
git clone https://github.com/Jiany-S/java-blockchain
cd java-blockchain
.\gradlew :app:run --args=".\data\chain"
```

Output (first run):

```
INFO: Starting height: 0
INFO: Produced block? true (+1) in 42 ms
INFO: Ending height: 1
INFO: Data dir: ./data/chain
INFO: Restart this app and you should see the same or higher height.
```

---

## 🔍 Example Workflow

1. Start node → creates genesis at height 0  
2. Add demo transaction (`alice123456 → bob654321`)  
3. Node mines block 1 with tx  
4. Restart → state is restored from RocksDB, balances preserved  

---

## 📊 Roadmap

- ✅ Transaction + block protocol  
- ✅ Proof-of-Work mining  
- ✅ StateStore + mempool  
- ✅ RocksDB persistence  
- ✅ Demo RPC API  
- ⏳ Block validation (Merkle, timestamp, parent linkage)  
- ⏳ Chain reorg + fork choice (heaviest-tip)  
- ⏳ ECDSA signatures (BouncyCastle)  
- ⏳ P2P gossip (Netty)  
- ⏳ Docker localnet with multiple nodes  

---

## 🧪 Tests

Run unit tests:

```bash
./gradlew :app:test
```

Includes:
- Transaction round-trip (serialize/deserialize)
- Proof-of-Work meetsTarget / mine
- State apply/revert
- Chain store behavior

---

## 🤝 Contributing

Contributions are welcome. Please keep PRs small, add tests, and follow the existing clean coding style.

---

## 📜 License

MIT. See `LICENSE`.

---

## 📎 Direct Download

- **Download ZIP** (no Git needed):  
  👉 [Download Source Code (zip)](https://github.com/Jiany-S/java-blockchain/archive/refs/heads/main.zip)

- Or clone with Git:
  ```bash
  git clone https://github.com/Jiany-S/java-blockchain
  ```

---
