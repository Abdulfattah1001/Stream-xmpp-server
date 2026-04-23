
# Zeal XMPP — Custom XMPP Server & Client Library

<p align="center">
  <img src="docs/assets/logo.png" alt="Zeal XMPP Logo" width="200"/>
</p>

<p align="center">
  <a href="https://github.com/yourusername/zeal-xmpp/actions">
    <img src="https://github.com/yourusername/zeal-xmpp/workflows/CI/badge.svg" alt="CI"/>
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License"/>
  </a>
  <a href="https://github.com/yourusername/zeal-xmpp/releases">
    <img src="https://img.shields.io/github/v/release/yourusername/zeal-xmpp" alt="Release"/>
  </a>
  <a href="https://github.com/yourusername/zeal-xmpp/issues">
    <img src="https://img.shields.io/github/issues/yourusername/zeal-xmpp" alt="Issues"/>
  </a>
</p>

<p align="center">
  A production-grade, custom XMPP server and mobile client library built
  from scratch in pure Java with zero external framework dependencies.
  Designed for full developer control — extend it exactly the way you want.
</p>

---

## Table of Contents

- [Why Zeal XMPP?](#why-zeal-xmpp)
- [Architecture Overview](#architecture-overview)
- [Features](#features)
- [What Is Not Yet Implemented](#what-is-not-yet-implemented)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Environment Variables](#environment-variables)
  - [Database Setup](#database-setup)
  - [TLS Certificate](#tls-certificate)
  - [Running in Development](#running-in-development)
  - [Running in Production](#running-in-production)
- [Configuration Reference](#configuration-reference)
- [Authentication Flow](#authentication-flow)
- [End-to-End Encryption](#end-to-end-encryption)
- [Stream Management](#stream-management)
- [Client Library Usage](#client-library-usage)
- [API Reference](#api-reference)
- [Contributing](#contributing)
- [License](#license)

---

## Why Zeal XMPP?

Most messaging infrastructure forces you to choose between:

| Option | Problem |
|--------|---------|
| Firebase / Pubnub | Vendor lock-in, limited control, recurring cost |
| ejabberd / Openfire | Complex Erlang/Java codebase, hard to customize |
| Roll your own WebSocket | No protocol, no resumption, no federation |

**Zeal XMPP** gives you a clean, readable Java codebase you fully own.
Every feature is either already built or can be added exactly where it belongs.

```
You own the protocol.
You own the encryption.
You own the data.
```

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                          Mobile App                                 │
│                                                                     │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │                    XMPPClient (Java/Kotlin)                  │  │
│   │  ConnectionManager → StreamNegotiator → StanzaDispatcher     │  │
│   │  MessageManager    → RosterManager    → PresenceManager      │  │
│   │  StreamManager (XEP-0198)             → ReconnectManager     │  │
│   └─────────────────────────┬────────────────────────────────────┘  │
└─────────────────────────────│───────────────────────────────────────┘
│  TLS (port 5222)
│  XMPP + Custom Extensions
┌─────────────────────────────▼────────────────────────────────────────┐
│                         Zeal XMPP Server                             │
│                                                                      │
│   ┌────────────┐  ┌──────────────┐  ┌──────────────────────────┐     │
│   │   Server   │  │  Auth HTTP   │  │   Background Tasks       │     │
│   │  (port     │  │  API         │  │   CleanupTask            │     │
│   │   5222)    │  │  (port 8080) │  │   SessionReaper          │     │
│   └─────┬──────┘  └──────┬───────┘  └──────────────────────────┘     │
│         │                │                                           │
│   ┌─────▼────────────────▼────────────────────────────────────────┐  │
│   │              Core Components                                  │  │
│   │  SessionRegistry  VirtualHostManager  StreamManagement        │  │
│   │  MessageHandler   RosterManager       PresenceHandler         │  │
│   │  AuthManager      StatusHandler       SubscriptionHandler     │  │
│   └───────────────────────────┬───────────────────────────────────┘  │
└───────────────────────────────│──────────────────────────────────────┘
│
┌─────────────────▼──────────────────┐
│          PostgreSQL                │
│  users  session_tokens  messages   │
│  roster user_status    user_keys   │
└────────────────────────────────────┘
```

---

## Features

### Server
- [x] Pure Java — no Spring, no Netty, no external frameworks
- [x] XMPP Core (RFC 6120) — STARTTLS, SASL PLAIN, resource binding
- [x] Session Management — idle detection, graceful shutdown, reaper
- [x] Virtual Hosting — multiple domains on one server
- [x] Roster Management — contact list with subscription state machine
- [x] Presence — online/offline/away broadcast
- [x] Offline Messages — 30-day storage with push notification
- [x] Status / Stories — 24-hour expiry, text/image/video
- [x] End-to-End Encryption — AES-256-GCM, server is a blind router
- [x] Stream Management (XEP-0198) — ack, resumption, retransmission
- [x] Session Resumption — reconnect without full re-authentication
- [x] Custom Connection Pool — no HikariCP dependency
- [x] Custom BCrypt — no jBCrypt dependency
- [x] Firebase Token Verification — no Firebase Admin SDK dependency
- [x] Session Tokens — replace passwords, never expire until logout
- [x] Contact Discovery — phone number hashing, privacy preserving
- [x] Background Cleanup — expired status, offline messages, orphaned media
- [x] Rate Limiting — per-IP auth failure tracking and lockout
- [x] Metrics — LongAdder/AtomicLong counters, zero contention
- [x] Graceful Shutdown — drains pool, closes sessions, logs metrics

### Client Library
- [x] Full connection lifecycle (connect, disconnect, reconnect)
- [x] Automatic reconnection with exponential backoff + jitter
- [x] STARTTLS negotiation
- [x] SASL PLAIN with session token
- [x] Resource binding
- [x] Stream Management (XEP-0198) — ack, resume, retransmit
- [x] Session resumption — no full re-auth on reconnect
- [x] Message send/receive (text, image, audio, video, file)
- [x] Chat state notifications (typing indicators)
- [x] Delivery and read receipts
- [x] Roster get/set/push
- [x] Presence send/receive
- [x] Status publish/fetch/delete/view
- [x] Ping keepalive

---

## What Is Not Yet Implemented

These are known gaps. Contributions welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).

| Feature | RFC/XEP | Priority |
|---------|---------|---------|
| Server-to-Server (S2S) federation | RFC 7590 | Medium |
| Multi-User Chat (group messaging) | XEP-0045 | High |
| Message Archive Management | XEP-0313 | Medium |
| Push Notifications (APNs/FCM) | XEP-0357 | High |
| Message Carbons (multi-device) | XEP-0280 | Medium |
| In-Band Registration | XEP-0077 | Low |
| vCard / User Profile | XEP-0054 | Low |
| File Upload (HTTP) | XEP-0363 | High |
| Clustering / multi-node | Custom | Low |
| Admin Web UI | Custom | Low |

---

## Project Structure

```
zeal-xmpp/
│
├── server/                          # XMPP Server
│   └── src/main/java/com/xmpp/
│       ├── Server.java              # Entry point, accept loop
│       ├── Main.java                # main() - wires everything
│       ├── api/
│       │   ├── AuthController.java  # HTTP registration/login API
│       │   └── SimpleJson.java      # Zero-dependency JSON parser
│       ├── auth/
│       │   ├── AuthManager.java     # SASL + rate limiting
│       │   ├── BCrypt.java          # Pure Java BCrypt
│       │   ├── FirebaseTokenVerifier.java
│       │   ├── SASLMechanism.java   # SASL PLAIN decoder
│       │   └── SessionTokenService.java
│       ├── config/
│       │   └── ServerConfig.java    # Immutable config + SSLContext
│       ├── connection/
│       │   ├── ConnectionHandler.java
│       │   └── TLSUpgrader.java
│       ├── crypto/
│       │   └── MessageEncryption.java  # AES-256-GCM, ECDH
│       ├── db/
│       │   ├── CleanupTask.java     # Expired data pruning
│       │   ├── ConnectionPool.java  # Custom JDBC pool
│       │   └── DatabaseManager.java # All SQL operations
│       ├── exception/
│       │   ├── AuthenticationException.java
│       │   ├── StartTLSException.java
│       │   └── StreamException.java
│       ├── metrics/
│       │   └── ServerMetrics.java
│       ├── roster/
│       │   ├── RosterItem.java
│       │   └── RosterManager.java
│       ├── session/
│       │   ├── Session.java
│       │   ├── SessionRegistry.java
│       │   ├── SessionReaper.java
│       │   └── SessionState.java
│       ├── stream/
│       │   ├── XMPPStreamProcessor.java
│       │   └── stanza/
│       │       ├── AuthHandler.java
│       │       ├── IQHandler.java
│       │       ├── MessageHandler.java
│       │       ├── PresenceHandler.java
│       │       ├── ResourceBindHandler.java
│       │       ├── StanzaHandler.java
│       │       ├── StatusHandler.java
│       │       └── SubscriptionHandler.java
│       ├── vhost/
│       │   ├── DomainConfig.java
│       │   └── VirtualHostManager.java
│       └── xep/
│           └── sm/
│               ├── StreamManagementHandler.java
│               ├── StreamManagementState.java
│               └── UnackedStanza.java
│
├── client/                          # Mobile Client Library
│   └── src/main/java/com/xmpp/client/
│       ├── XMPPClient.java
│       ├── XMPPConfig.java
│       ├── connection/
│       │   ├── ConnectionManager.java
│       │   ├── StanzaReader.java
│       │   ├── StanzaWriter.java
│       │   └── StreamNegotiator.java
│       ├── exception/
│       │   ├── AuthenticationException.java
│       │   ├── ConnectionException.java
│       │   └── XMPPException.java
│       ├── listener/
│       │   ├── ConnectionListener.java
│       │   ├── MessageListener.java
│       │   ├── PresenceListener.java
│       │   └── RosterListener.java
│       ├── manager/
│       │   ├── MessageManager.java
│       │   ├── PresenceManager.java
│       │   ├── ReconnectManager.java
│       │   ├── RosterManager.java
│       │   └── StreamManager.java
│       └── stanza/
│           ├── IQStanza.java
│           ├── MessageStanza.java
│           ├── PresenceStanza.java
│           ├── Stanza.java
│           └── StanzaDispatcher.java
│
├── sql/
│   ├── schema.sql                   # Full database schema
│   └── migrations/                  # Future schema migrations
│
├── docs/
│   ├── architecture.md
│   ├── encryption.md
│   ├── stream-management.md
│   └── api.md
│
├── config.properties                # Server configuration
├── docker-compose.yml
├── Dockerfile
├── pom.xml
├── README.md
├── CONTRIBUTING.md
└── CODE_OF_CONDUCT.md
```

---

## Getting Started

### Prerequisites

```bash
# Required
Java 21+
PostgreSQL 16+
Maven 3.9+

# For TLS (development)
keytool  # bundled with JDK

# For TLS (production)
certbot  # Let's Encrypt
```

### Environment Variables

```bash
# TLS keystore passwords
export KEYSTORE_PASSWORD=your_keystore_password
export KEY_PASSWORD=your_key_password

# Database
export DB_PASSWORD=your_postgres_password

# Firebase project ID (for phone auth)
export FIREBASE_PROJECT_ID=your-firebase-project-id

# Never put these in config.properties or source code
```

### Database Setup

```bash
# Create database and user
psql -U postgres <<EOF
CREATE DATABASE zealxmpp;
CREATE USER zealxmpp_user WITH PASSWORD 'your_db_password';
GRANT ALL PRIVILEGES ON DATABASE zealxmpp TO zealxmpp_user;
EOF

# Run schema
psql -U zealxmpp_user -d zealxmpp -f sql/schema.sql

# Verify
psql -U zealxmpp_user -d zealxmpp -c "\dt"
```

### TLS Certificate

```bash
# Development - self-signed
mkdir -p certs
keytool -genkeypair \
  -alias zeal-xmpp \
  -keyalg RSA \
  -keysize 2048 \
  -validity 365 \
  -keystore certs/dev-keystore.p12 \
  -storetype PKCS12 \
  -storepass changeit \
  -keypass changeit \
  -dname "CN=localhost, O=ZealXMPP, C=US"

# Production - Let's Encrypt
sudo certbot certonly --standalone -d yourdomain.com
sudo openssl pkcs12 -export \
  -in /etc/letsencrypt/live/yourdomain.com/fullchain.pem \
  -inkey /etc/letsencrypt/live/yourdomain.com/privkey.pem \
  -out certs/prod-keystore.p12 \
  -name zeal-xmpp \
  -passout env:KEYSTORE_PASSWORD
```

### Running in Development

```bash
# Clone
git clone https://github.com/yourusername/zeal-xmpp.git
cd zeal-xmpp

# Build
mvn clean package -DskipTests

# Set env vars
export KEYSTORE_PASSWORD=changeit
export KEY_PASSWORD=changeit
export DB_PASSWORD=your_postgres_password
export FIREBASE_PROJECT_ID=your-project-id
export ENV=DEV

# Run
java -jar server/target/zeal-xmpp-server.jar config.properties
```

### Running in Production

```bash
# With Docker Compose
cp .env.example .env
# Edit .env with your values

docker-compose up -d

# Check logs
docker-compose logs -f xmpp-server

# With systemd
sudo cp deploy/zeal-xmpp.service /etc/systemd/system/
sudo systemctl enable zeal-xmpp
sudo systemctl start zeal-xmpp
sudo journalctl -u zeal-xmpp -f
```

---

## Configuration Reference

```properties
# config.properties

# Environment: DEV | PROD
ENV=DEV

# Server
port=5222
address=0.0.0.0

# TLS certificate paths (passwords via env vars)
devTlsCertPath=certs/dev-keystore.p12
prodTlsCertPath=certs/prod-keystore.p12

# Thread pool
corePoolSize=10
maxPoolSize=200
notificationCorePool=4
notificationMaxPool=100

# Session timeouts
sessionTimeoutMs=120000        # 2 minutes idle
reaperIntervalSec=30           # Check every 30 seconds

# Database
db.url=jdbc:postgresql://localhost:5432/zealxmpp
db.user=zealxmpp_user
db.pool.min=5
db.pool.max=20
db.pool.timeout=30000

# HTTP Auth API
http.port=8080

# XMPP domain
xmpp.domain=yourdomain.com
```

---

## Authentication Flow

```
SIGN UP                          LOG IN (Returning User)

Client                Server     Client              Server
  │                     │          │                    │
  │── phone number ───▶│          │── session_token ─▶│
  │                     │          │                    │
  │         Firebase OTP│          │   hash token       │
  │         sent to phone          │   lookup in DB     │
  │                     │          │   check revoked?   │
  │── OTP ────────────▶│          │                    │
  │                     │          │◀── ok + jid ──────│
  │         verify OTP  │          │                    │
  │         generate    │          │── XMPP connect ──▶│
  │         user_id     │          │   (session_token   │
  │         generate    │          │    as password)    │
  │         session_    │          │                    │
  │         token       │          │◀── authenticated ─│
  │                     │
  │◀── session_token ──│
  │    + jid            │
  │    (stored forever) │
  │                     │
  │── XMPP connect ───▶│
  │   (session_token    │
  │    as password)     │
  │                     │
  │◀── authenticated ──│
```

Tokens **never expire** unless explicitly revoked via logout.

---

## End-to-End Encryption

The server is a **blind router**. It stores and forwards ciphertext only.

```
Alice                    Server                    Bob
 │                          │                       │
 │  encrypt("Hello")        │                       │
 │  using ECDH shared key   │                       │
 │  → AES-256-GCM           │                       │
 │                          │                       │
 │── ciphertext ──────────▶│── ciphertext ───────▶│
 │                          │                       │
 │                          │  Server sees:         │
 │                          │  from, to, size       │
 │                          │  NOT the content      │
 │                          │                       │
 │                          │              decrypt  │
 │                          │              → "Hello"│
```

See [docs/encryption.md](docs/encryption.md) for full details.

---

## Stream Management

Zeal XMPP implements XEP-0198 Stream Management.
Messages are never lost on disconnect.

```
Normal flow:
  Client sends stanza → server acks → stanza removed from queue

Disconnect and reconnect:
  Client reconnects → sends <resume previd='...' h='N'/>
  Server retransmits everything after N
  Client never re-authenticates — session is resumed transparently

Result:
  App opens  → resumes instantly
  Network blip → retransmits missed stanzas
  Never sees duplicate messages
  Never loses messages
```

See [docs/stream-management.md](docs/stream-management.md) for full details.

---

## Client Library Usage

### Android

```kotlin
val config = XMPPConfig.Builder()
    .host("yourdomain.com")
    .username(userId)           // "u_7f3a9b2c"
    .password(sessionToken)     // "st_abc123..."
    .resource("android")
    .callbackExecutor { runnable ->
        Handler(Looper.getMainLooper()).post(runnable)
    }
    .build()

val client = XMPPClient(config)

client.addConnectionListener(object : ConnectionListener {
    override fun onConnected(fullJid: String) {
        Log.d("XMPP", "Connected as $fullJid")
    }
    override fun onDisconnected(reason: String, willReconnect: Boolean) {}
    override fun onReconnecting(attempt: Int, delayMs: Long) {}
    override fun onAuthenticationFailed(e: AuthenticationException) {}
})

client.addMessageListener(object : MessageListener {
    override fun onMessageReceived(message: MessageStanza) {
        showMessage(message.fromBareJid, message.body)
    }
    override fun onChatStateReceived(fromJid: String, state: String) {}
    override fun onMessageAcknowledged(messageId: String) {}
})

client.connect()

// Send a message
client.messageManager.sendMessage("u_abc@yourdomain.com", "Hello!")
```

---

## API Reference

### POST /auth/register
Registers a new user after Firebase OTP verification.

```json
Request:
{
  "firebase_token": "eyJhbGci...",
  "phone_number":   "+2348012345678",
  "display_name":   "Alice",
  "device_label":   "Pixel 8",
  "platform":       "android",
  "app_version":    "1.0.0"
}

Response 201:
{
  "session_token": "st_7f3a9b2c...",
  "jid":           "u_7f3a9b2c@yourdomain.com",
  "user_id":       "u_7f3a9b2c",
  "display_name":  "Alice"
}
```

### POST /auth/login
Authenticates a returning user with their stored session token.

```json
Request:
{
  "firebase_token": "eyJhbGci...",
  "device_label":   "Pixel 8",
  "push_token":     "fcm_token_here",
  "platform":       "android"
}

Response 200:
{
  "session_token": "st_xyz789...",
  "jid":           "u_7f3a9b2c@yourdomain.com",
  "user_id":       "u_7f3a9b2c"
}
```

### POST /auth/logout
```
Header: Authorization: Bearer st_abc123...

Response 200:
{"status":"logged_out"}
```

### POST /auth/discover
Find which contacts are registered (privacy-preserving).

```json
Request:
{
  "phone_hashes": [
    "sha256_of_E164_number",
    "sha256_of_E164_number"
  ]
}

Response 200:
{
  "matches": [
    {
      "phone_hash":   "sha256_of_E164_number",
      "jid":          "u_abc@yourdomain.com",
      "display_name": "Bob"
    }
  ]
}
```

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

---

## License

MIT License. See [LICENSE](LICENSE).

Built with ❤️ and zero framework dependencies.

---