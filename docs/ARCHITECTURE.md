# Stream Messenger — Architecture Overview

## 1. System Context & Network Topology

Stream Messenger is a low-latency, high-concurrency real-time instant messaging backend designed for:

- Low CPU overhead
- Minimal garbage-collection pressure
- High concurrent connection counts
- Rapid horizontal scaling
- Java Virtual Threads on JDK 21+

### Network Topology

```text
                  +-----------------------------+
                  |    Android / Mobile Clients |
                  +--------------+--------------+
                                 |
                     HTTPS / TLS | TCP (5222 / 5224)
                                 v
                  +--------------+--------------+
                  |     NGINX Reverse Proxy     |
                  |  (TLS Termination Edge)     |
                  +--------------+--------------+
                                 |
             +-------------------+-------------------+
             |                                       |
       Loopback HTTP                            Proxied TCP / Stream
          (5223)                                   (5222 / 5224)
             |                                       |
             v                                       v
+-----------------------------+       +-----------------------------+
| Embedded HTTP Controllers   |       | XMPP TCP Connection Pool    |
|                             |       |                             |
| - AuthController            |       | - ConnectionHandler         |
| - BotApiHandler             |       | - XMPPStreamProcessor       |
| - GroupController           |       | - SessionRegistry            |
+--------------+--------------+       +--------------+--------------+
               |                                      |
               +------------------+-------------------+
                                  |
                                  v
                    +-----------------------------+
                    |    Internal Service Layer   |
                    |                             |
                    | - AuthManager               |
                    | - CallSignalingHandler      |
                    | - CollaborativeNoteHandler  |
                    +--------------+--------------+
                                   |
                                   v
                    +-----------------------------+
                    |     JDBC Connection Pool    |
                    |     (Custom Lock-Free Pool) |
                    +--------------+--------------+
                                   |
                                   v
                    +-----------------------------+
                    | PostgreSQL / MySQL Database |
                    +-----------------------------+
```

---

## 2. Architectural Layers

### 2.1 Connection Lifecycle & Networking Layer

This layer manages persistent TCP connections, TLS negotiation, session lifecycle, and online-user routing.

#### `ConnectionHandler`
Responsible for:
- Managing plain TCP or proxied TLS connection lifecycles
- Handling connection establishment and termination
- Negotiating STARTTLS for XMPP connections where applicable
- Binding proxied sessions originating from edge proxies
- Handing established connections to the XMPP stream-processing layer

#### `TLSUpgrader`
Responsible for:
- Wrapping raw TCP sockets in TLS/SSL
- Enforcing TLS 1.2+
- Restricting connections to approved cipher suites
- Managing the transition from plaintext to encrypted communication when STARTTLS is used

#### `Session`
Represents an authenticated or partially authenticated client session. A session maintains:
- Socket/connection state
- Authentication state
- Associated `userId`
- Associated `contactId`, where applicable
- Connection metadata
- Message-routing state

#### `SessionRegistry`
Maintains the currently active socket/session state. Primary responsibilities include:
- Mapping `userId` to active sessions
- Mapping `contactId` to relevant sessions where required
- Supporting concurrent message routing
- Registering and unregistering sessions safely
- Providing fast lookup of online users

---

## 3. HTTP & External API Layer

The HTTP layer exposes authentication, bot integration, group-management, and related REST-style endpoints.

#### `AuthController`
Handles authentication-related HTTP endpoints:
- `POST /auth/register`
- `POST /auth/login`
- `POST /auth/logout`
- `GET /auth/discover`

**Responsibilities include:**
- Account registration
- User authentication
- Token/session revocation
- Contact/user discovery

#### `BotApiHandler`
Provides webhook-based integration for bot accounts and automated messaging.

**Responsibilities include:**
- Bot account integration
- Announcement delivery
- Webhook processing
- HMAC-SHA256 webhook signature verification

#### `GroupController`
Handles group invitation and membership operations.

**Responsibilities include:**
- Group invite-link generation
- Invite-link revocation
- Member admission
- Group membership-related authorization

---

## 4. Feature & Signaling Subsystems

#### `CallSignalingHandler`
Handles signaling for real-time voice/video communication.

**Responsibilities include:**
- WebRTC SDP offer/answer exchange
- ICE candidate pass-through
- Call-session lifecycle management
- Twilio Video room-token generation

Active calls are indexed through:
```java
ConcurrentHashMap<UserId, ActiveCall> userActiveCalls;
```
This provides approximately $\mathcal{O}(1)$ average-time lookup for determining whether a user currently participates in an active call.

#### `CollaborativeNoteHandler`
Provides real-time collaborative document editing.

**Responsibilities include:**
- Operational Transformation (OT)
- Delta tracking
- Section locking
- Incremental document patch generation
- Patch broadcasting to connected collaborators
- Maintaining synchronization between concurrent editors

---

## 5. Persistence & Database Abstraction

#### `ConnectionPool`
A lightweight JDBC connection pool with minimal dependencies.

**Responsibilities include:**
- Maintaining pre-warmed database connections
- Providing connections to application tasks
- Resetting connection state before returning connections to the pool
- Managing auto-commit state
- Evicting idle connections in the background
- Controlling the maximum number of concurrent database connections

The pool is designed to minimize allocation and synchronization overhead.

#### `DatabaseManager`
Provides the primary database-access abstraction.

**Responsibilities include:**
- User-account persistence
- Offline message storage
- Store-and-forward message queues
- User status persistence
- Privacy settings
- Related transactional operations

#### `BlogDatabaseManager`
Provides database access for blog/content-related functionality where applicable.

---

## 6. Concurrency & Performance Design

The system is designed around Java 21 Virtual Threads and concurrent data structures.

### 6.1 Virtual Threads
The application uses:
```java
Executors.newVirtualThreadPerTaskExecutor()
```
for workloads such as:
- HTTP API handlers
- Background tasks
- Asynchronous push notifications
- Blocking JDBC operations
- Other I/O-bound operations

The goal is to avoid tying a small number of platform threads to blocking operations.

### 6.2 Concurrent Lookup Structures
High-frequency lookup structures use concurrent maps rather than linear scans. For example:
```java
ConcurrentHashMap<UserId, ActiveCall> userActiveCalls;
```
This avoids $\mathcal{O}(N)$ scans when determining whether a user has an active call. Similar indexing strategies may be applied to:
- Online sessions
- User-to-session mappings
- Group membership caches
- Pending operations
- Call state
- Other frequently accessed runtime state

### 6.3 Store-and-Forward Offline Messaging
Messages destined for offline users are persisted in the `offline_messages` table. When the recipient authenticates and establishes a session:

1. Pending messages are identified.
2. Messages are retrieved in chronological order.
3. Messages are delivered to the client.
4. Successfully delivered messages are acknowledged.
5. Persisted messages are removed or marked as delivered according to the retention strategy.

The intended delivery order is chronological.

---

## 7. Core Design Principles

1. **Low-latency networking:** Persistent TCP connections are used for real-time messaging.
2. **Efficient concurrency:** Java Virtual Threads handle large numbers of blocking I/O tasks without requiring a large platform-thread pool.
3. **Fast in-memory routing:** `ConcurrentHashMap`-based indexes provide fast access to active sessions and call state.
4. **Durable offline delivery:** Offline messages are persisted rather than held exclusively in memory.
5. **Separation of concerns:** Networking, HTTP APIs, business logic, signaling, and persistence are separated into distinct layers.
6. **Edge TLS termination:** NGINX provides the external TLS termination layer, while internal services can operate over trusted local/proxied connections where appropriate.
7. **Horizontal scalability:** Runtime state and database access are designed with future multi-instance deployment in mind.

---

## 8. Primary Components

| Layer | Component | Primary Responsibility |
| :--- | :--- | :--- |
| **Networking** | `ConnectionHandler` | TCP connection lifecycle |
| **Networking** | `TLSUpgrader` | TLS negotiation/upgrading |
| **Networking** | `Session` | Client session state |
| **Networking** | `SessionRegistry` | Active-session lookup and routing |
| **XMPP** | `XMPPStreamProcessor` | XMPP stream processing |
| **HTTP** | `AuthController` | Authentication APIs |
| **HTTP** | `BotApiHandler` | Bot/webhook APIs |
| **HTTP** | `GroupController` | Group invitation/membership APIs |
| **Services** | `AuthManager` | Authentication/business logic |
| **Services** | `CallSignalingHandler` | WebRTC/Twilio signaling |
| **Services** | `CollaborativeNoteHandler` | Real-time collaborative editing |
| **Persistence** | `ConnectionPool` | JDBC connection management |
| **Persistence** | `DatabaseManager` | Core database operations |
| **Persistence** | `BlogDatabaseManager` | Blog/content persistence |
| **Database** | PostgreSQL / MySQL | Durable application storage |
| **Edge** | NGINX | TLS termination and traffic proxying |

---

## 9. High-Level Request & Message Flows

### 9.1 HTTP API Request

```text
Mobile Client
     |
     | HTTPS
     v
   NGINX
     |
     | HTTP
     v
AuthController / GroupController / BotApiHandler
     |
     v
Internal Service Layer
     |
     v
DatabaseManager
     |
     v
ConnectionPool
     |
     v
PostgreSQL / MySQL
```

### 9.2 Real-Time XMPP Message

```text
Mobile Client
     |
     | TCP / TLS
     v
   NGINX
     |
     | Proxied TCP / Stream
     v
ConnectionHandler
     |
     v
XMPPStreamProcessor
     |
     v
SessionRegistry
     |
     +----------------------+
     |                      |
     | Recipient online     | Recipient offline
     v                      v
Recipient Session       DatabaseManager
     |                      |
     v                      v
Mobile Client          offline_messages
```

### 9.3 Call Signaling

```text
Client A
   |
   | SDP / ICE
   v
CallSignalingHandler
   |
   +--------------------+
   |                    |
   v                    v
SessionRegistry     Twilio API
   |                    |
   v                    v
Client B            Video Room
```

### 9.4 Collaborative Editing

```text
Editor A
   |
   | OT Delta
   v
CollaborativeNoteHandler
   |
   +----------------------+
   |                      |
   v                      v
OT / Lock Management   Persistence
   |
   v
Incremental Patch
   |
   +-----------+-----------+
   |                       |
   v                       v
Editor B                 Editor C
```

---

## 10. Technology Baseline

| Area | Technology |
| :--- | :--- |
| **Runtime** | JDK 21+ |
| **Concurrency** | Java Virtual Threads |
| **Network Edge** | NGINX |
| **Real-Time Protocol** | XMPP over TCP/TLS |
| **HTTP** | Embedded HTTP server/controllers |
| **Database Access** | JDBC |
| **Connection Pool** | Custom implementation |
| **Database** | PostgreSQL / MySQL |
| **Real-Time Calls** | WebRTC / Twilio Video |
| **Collaborative Editing** | Operational Transformation |
| **Runtime Concurrent State** | `ConcurrentHashMap` |
| **Webhook Authentication** | HMAC-SHA256 |
| **External Transport Security** | TLS 1.2+ |