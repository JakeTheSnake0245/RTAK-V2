# RTAK Bridge — Architecture

## Overview

RTAK Bridge is an Android application that connects ATAK (Android Team Awareness Kit) to the Reticulum Network Stack (RNS), enabling off-grid situational awareness over LoRa, HaLow, and other RNS-compatible transports. It runs a local TCP server that ATAK connects to as a TAK Server, and bridges Cursor on Target (CoT) XML messages bidirectionally over Reticulum.

```
┌──────────┐  TCP :8087   ┌────────────────────────────────────────────┐  RNS Links   ┌─────────────┐
│   ATAK   │◄────────────►│              RTAK Bridge                   │◄────────────►│ Remote RTAK │
│  Client  │  CoT XML     │                                            │  Fragmented  │   Bridge    │
└──────────┘              │  ┌──────────┐  ┌──────────┐  ┌──────────┐ │  + Compressed └─────────────┘
                          │  │CotTcpSvr │  │BridgeSvc │  │rtak_brdg │ │
                          │  │  (Java)  │◄►│  (Java)  │◄►│ (Python) │ │
                          │  └──────────┘  └──────────┘  └──────────┘ │
                          └────────────────────────────────────────────┘
```

## Project Structure

```
rtak-bridge/
├── build.gradle                       # Root build (plugins DSL, no repositories)
├── settings.gradle                    # Centralized repositories + Chaquopy maven
├── gradle.properties
├── app/
│   ├── build.gradle                   # Android config, Chaquopy Python 3.13, rnspure
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/rtak/bridge/
│       │   ├── RTAKApplication.java   # Application subclass, notification channel
│       │   ├── RTAKCallback.java      # Java interface for Python→Java callbacks
│       │   ├── ReticulumBridge.java   # Java wrapper around Python rtak_bridge module
│       │   ├── model/
│       │   │   └── BridgeStatus.java  # Observable state model for UI
│       │   ├── service/
│       │   │   ├── CotTcpServer.java  # TCP server for ATAK clients (port 8087)
│       │   │   └── TakBridgeService.java  # Foreground service orchestrating everything
│       │   └── ui/
│       │       ├── MainActivity.java  # Main dashboard UI
│       │       └── SettingsActivity.java
│       ├── python/
│       │   ├── __init__.py
│       │   ├── rtak_bridge.py         # Core RNS bridge: init, links, fragmentation
│       │   └── cot_helper.py          # CoT XML builder/parser utilities
│       └── res/                       # Layouts, drawables, values, menus
```

## Data Flow

### ATAK → Remote Peer

1. ATAK sends CoT XML over TCP to `CotTcpServer` (port 8087)
2. `CotTcpServer` parses `</event>` delimiters, extracts complete CoT events
3. `CotTcpServer.CotListener.onCotFromClient()` fires → `TakBridgeService`
4. Service filters out system events (`t-x-c-t`, `t-x-c-t-r` pings) — these are not forwarded
5. Service calls `ReticulumBridge.broadcastCot(cotXml)` on background executor
6. Java→Python via Chaquopy: `rtak_bridge.send_cot(cot_xml)` is called
7. Python compresses with zlib (level 9), prepends 1-byte flag
8. Compressed data is fragmented into ≤450-byte chunks with 4-byte headers
9. Each fragment sent as `RNS.Packet` over the active `RNS.Link` to each peer

### Remote Peer → ATAK

1. `_on_fragment_received()` callback fires for each incoming RNS packet
2. Fragment header parsed: `(msg_id, index, total)`
3. Single-fragment messages (total=1) delivered immediately
4. Multi-fragment messages buffered in `_reassembly` dict keyed by `(link_hash, msg_id)`
5. Once all fragments arrive, payload is reassembled and decompressed
6. Python→Java callback: `RTAKCallback.onCotReceived(cotXml, senderHash)`
7. `TakBridgeService.onCotReceived()` calls `CotTcpServer.broadcastToClients(cotXml)`
8. TCP server writes CoT XML + newline to all connected ATAK clients

### Heartbeat

`TakBridgeService` sends a `t-x-c-t` ping CoT to all connected ATAK clients every 30 seconds to prevent ATAK's "Data reception timeout" disconnect. These pings are local only — they are filtered out before RNS forwarding.

## Fragmentation Protocol

RNS has a 500-byte MTU for `RNS.Packet`. CoT XML messages typically range from 500–1200 bytes raw. Even with zlib compression, many exceed the MTU. Rather than using `RNS.Resource` (which adds significant complexity), we use a simple custom fragmentation scheme over Links.

### Wire Format

Each RNS packet carries one fragment with a 4-byte header:

```
Byte 0-1:  msg_id  (uint16, big-endian) — correlates fragments of one message
Byte 2:    index   (uint8)              — fragment number, 0-based
Byte 3:    total   (uint8)              — total fragment count
Byte 4+:   payload (up to 450 bytes)    — compressed CoT chunk
```

### Constants

| Constant        | Value | Notes                                    |
|-----------------|-------|------------------------------------------|
| `_HEADER_SIZE`  | 4     | `struct.calcsize(">HBB")`                |
| `_CHUNK_SIZE`   | 450   | Max payload per fragment (454 total < 500 MTU) |
| `_FRAG_TIMEOUT` | 15s   | Incomplete message discard timeout       |
| Max fragments   | 255   | Limited by uint8 `total` field           |
| Max message     | ~112KB| 255 × 450 bytes (far exceeds any CoT)   |

### Message IDs

Each peer has its own sequential counter (`_send_counters` dict, keyed by `link_hash_hex`) that wraps from 0 to 65535 (`& 0xFFFF`). Counter is protected by `_send_counters_lock`. Counter state is cleaned up when a link closes or on shutdown.

### Reassembly

Incoming fragments are buffered in `_reassembly` dict keyed by `(sender_link_hash, msg_id)`. Per-peer keying ensures two peers sending the same `msg_id` concurrently never collide. A background thread runs every 5 seconds to purge stale incomplete messages older than `_FRAG_TIMEOUT`.

### Compression

Before fragmentation, CoT XML is compressed:

```
0x01 + zlib_compressed_data   — if compression helps
0x00 + raw_utf8_data          — if compression doesn't reduce size
```

The receiver checks the first byte to determine how to decode. Legacy packets (no flag byte, raw XML) are handled as a fallback.

## Peer Discovery and Link Management

### Announce Flow

1. On startup, the bridge sends an RNS announce with app data `"RTAK Bridge"`
2. `RTAKAnnounceHandler` listens for announces matching aspect `rtak.cot.server`
3. Self-announces are filtered by comparing `destination_hash.hex()` against `_own_hash`
4. New peers (first time seeing a dest hash) are tracked in `_known_peers` dict and trigger auto-connect in a background thread
5. Re-announces from already-known peers update metadata but do not create duplicate connections

### Link Lifecycle

- **Outbound**: `connect_to_peer()` → path request → `RNS.Link(remote_dest)` → `_on_outbound_link_established` callback → stored in `_links`
- **Inbound**: remote peer connects → `_on_link_established` callback → stored in `_links`
- **Closed**: `_on_link_closed` callback → removed from `_links`, per-peer send counter cleaned up
- All links have `_on_fragment_received` set as their packet callback

### Why Links (Not Direct Packets)

- Direct `RNS.Packet` to a `SINGLE` destination can route over hops but still limited to 500-byte MTU with no built-in fragmentation
- Links provide end-to-end encrypted channels with automatic path management
- Our custom fragmentation rides on top of Link packets
- UDP broadcast on shared interfaces is single-hop only — won't cross RNS transport nodes

## Java ↔ Python Bridge (Chaquopy)

### Java → Python

`ReticulumBridge.java` wraps all Python calls via `PyObject`:

| Java Method         | Python Function        | Purpose                    |
|---------------------|------------------------|----------------------------|
| `init(ctx, cb)`     | `rtak_bridge.init()`   | Start RNS, returns dest hash |
| `broadcastCot(xml)` | `rtak_bridge.send_cot()` | Send to all peers          |
| `announce(data)`    | `rtak_bridge.announce()` | Send RNS announce          |
| `connectToPeer(h)`  | `rtak_bridge.connect_to_peer()` | Manual peer connect  |
| `buildPing()`       | `cot_helper.build_ping()` | Generate keepalive CoT   |
| `shutdown()`        | `rtak_bridge.shutdown()` | Teardown                   |

### Python → Java

Python calls methods on the `RTAKCallback` interface object passed during init:

| Callback              | Trigger                              |
|-----------------------|--------------------------------------|
| `onCotReceived`       | Complete CoT message reassembled     |
| `onPeerConnected`     | Link established (inbound or outbound) |
| `onPeerDisconnected`  | Link closed                          |
| `onPeerAnnounced`     | Remote RTAK node discovered          |
| `onStatusChanged`     | Bridge state transition              |

## Android Platform Constraints

### Signal Handling

Reticulum's `__init__` calls `signal.signal(SIGINT, ...)`. On Android/Chaquopy, Python runs on a Java worker thread where `signal.signal()` raises `ValueError`. Workaround: monkey-patch `signal.signal` to a no-op before importing RNS, restore after init.

### AutoInterface

RNS `AutoInterface` calls `socket.if_nametoindex()` which is unavailable in Android's Python build. The default config uses `UDPInterface` instead. A migration routine detects and replaces `AutoInterface` in existing configs.

### Cryptography

The `cryptography` C-extension library has no Chaquopy wheels for this project's pinned Python version. `rnspure` includes pure-Python fallbacks for X25519, Ed25519, AES-128, and HKDF, so the `cryptography` package is intentionally excluded from pip dependencies.

### Python Version

The project is pinned to Python 3.13 and relies on Chaquopy 16's compatible tooling.

### ABIs

Only 64-bit ABIs are included: `arm64-v8a` (physical devices) and `x86_64` (emulator). This reduces APK size. Chaquopy auto-detects a matching Python 3.13 interpreter on the build machine.

## Build Configuration

- **Gradle**: Modern `plugins {}` DSL, repositories centralized in `settings.gradle`
- **Chaquopy**: Version 16.0.0, Python 3.13, pip dependencies `rnspure==1.1.3` and `usbserial4a==0.4.0`
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34
- **Java**: 17 source/target compatibility

## Known Issues and Future Work

- **LoRa transport untested**: UDPInterface over WiFi is the only tested path. RNodeInterface config is present but commented out.
- **No message acknowledgement**: The fragmentation protocol has no ACK/retransmit. Lost fragments result in a dropped message after 15s timeout. This is acceptable for SA/PLI updates (which repeat) but not for chat messages.
- **No CoT deduplication**: If both peers auto-connect simultaneously, two links may form (one inbound, one outbound). CoT could be delivered twice. ATAK handles this gracefully via UID-based dedup, but it wastes bandwidth.
- **Single TAK server port**: Fixed at 8087, not configurable at runtime.
- **buildPython auto-detection**: Requires a Python 3.13 command to be available to Chaquopy on the build machine.
- **No TLS on TAK TCP**: The local ATAK↔Bridge TCP connection is plaintext. Acceptable for localhost/LAN but should be documented.
- **Peer reconnection**: If a link closes, the peer is not automatically reconnected until a new announce is received. A periodic reconnect timer would improve resilience.
- **RNS ↔ ATAK CoT delivery not yet functional**: Peers discover each other and establish links, but CoT messages from ATAK are not yet appearing on remote ATAK clients. Root cause under investigation — likely related to fragmentation/reassembly, link state timing, or callback delivery.
- **TCP client sockets not closed properly**: When ATAK disconnects or the bridge stops, TCP sockets in `CotTcpServer` are not always cleaned up. This can leave stale entries in the `clients` map and potentially leak file descriptors across reconnections.
- **Bridge restart within same app session fails**: Stopping and re-starting the bridge without killing the app causes an error — likely due to Reticulum or Chaquopy Python state not being fully reset on `shutdown()`. The RNS singleton, identity, and destination objects persist in the Python interpreter across start/stop cycles. Current workaround: force-stop the app before restarting the bridge.
