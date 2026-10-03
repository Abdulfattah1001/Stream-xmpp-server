# DEVELOPMENT

This document provides developer guidelines, environment setup instructions, architectural principles, and testing strategies for the `streammessenger` High-Concurrency Real-Time Instant Messaging Server.

---

## 1. Tech Stack Overview

- **Language & Runtime:** Java 21+
- **HTTP Server:** Built-in JDK `com.sun.net.httpserver.HttpServer` with Virtual Thread Executor (`Executors.newVirtualThreadPerTaskExecutor()`)
- **Core Network Protocols:** Custom TCP XMPP Stream Processor (RFC 6120 / RFC 6121), WebRTC Signaling (RFC 8829 / RFC 8866)
- **Database & Pooling:** PostgreSQL / MySQL with custom lightweight `ConnectionPool`
- **Authentication:** Firebase Auth ID Token Verifier (RS256 with Google x509 public key caching), SHA-256 Session Token Engine
- **Third-Party Integrations:** Cloudinary (Media upload slots), Twilio (Video/Audio WebRTC tokens)

---

## 2. Prerequisites & Environment Setup

### Software Requirements
- JDK 21 or higher
- PostgreSQL 15+ or MySQL 8.0+
- **Build Tool:** Maven 3.9+ or Gradle 8+

### Configuration Setup
Create a `config.properties` file in the project root directory:

```properties
ENV=DEV
port=5222
botApiPort=5224
address=127.0.0.1
domainName=chat.omnyrex.com
fcmProjectId=your-firebase-project-id
devTlsCertPath=keystore.p12
prodTlsCertPath=/etc/ssl/certs/prod_keystore.p12
db.url=jdbc:postgresql://localhost:5432/stream_messenger
db.user=postgres
db.pool.min=5
db.pool.max=20
db.pool.timeout=30000
cloudinary_cloudname=your_cloud_name
cloudinary_api_key=your_api_key
cloudinary_api_secret=your_api_secret
```

### Environment Variables
For security, sensitive keys must never be stored in config.properties. Set the following environment variables:
```bash
export KEYSTORE_PASSWORD="your_keystore_password"
export KEY_PASSWORD="your_key_password"
export DB_PASSWORD="your_database_password"
export BOT_ADMIN_KEY="your_internal_bot_admin_key"
```


### 3. Project Architecture & Directory Structure
```bash
streammessenger/
├── api/          # HTTP Controllers (Auth, Bot, Group, Cloudinary) & Lightweight JSON Parser
├── auth/         # SASL PLAIN, Firebase Token Verifier, Session Token Engine, Access Controller
├── call/         # WebRTC / Twilio Call Signaling Handler (urn:xmpp:call:0)
├── config/       # Server Configuration & TLS/SSL Context Initialization
├── connection/   # Client TCP Socket Connection Lifecycle & TLS Upgrader
├── db/           # Connection Pool, Database Managers, Contact Discovery & Cleanup Tasks
├── exception/    # Custom Domain & Stream Exceptions
├── features/     # Collaborative Notes (OT Engine) & Custom Extensions
├── metrics/      # Server Operations & Network Metrics
├── push/         # FCM / APNs Push Notification Delivery Pipeline
├── session/      # Session Registry, Session State & Connection Binding
├── stanza/       # Stanza Handlers (Presence, Privacy, Roster)
└── stream/       # XML Stream Reader & XMPP Protocol Processor
```


### 4. Key Architectural Patterns & Concurrency Rules
1.  Virtuality & Threads
- Virtual Threads First: Standard IO operations, HTTP handlers, and asynchronous DB updates run on Virtual Threads (Executors.newVirtualThreadPerTaskExecutor()).

- No Shared Unsynchronized State: All session tracking and signaling registries (SessionRegistry, activeCalls, userActiveCalls) use ConcurrentHashMap or atomic flags.

2. Database Connection Management
- Connections are managed via ConnectionPool.

- Always use try-with-resources when borrowing JDBC connections:

```java
try (Connection conn = pool.getConnection();
     PreparedStatement stmt = conn.prepareStatement(sql)) {
    // Perform operations
    conn.commit();
} catch (SQLException e) {
    logger.severe("Database error: " + e.getMessage());
}
```
- When returning connections to the pool, uncommitted transactions are automatically rolled back and autoCommit is reset to true.

## Non-Destructive Offline Messaging
- Offline messages must remain in status = 'pending' during retrieval via fetchEncryptedOfflineMessages().

- Deletion or transition to delivered status occurs only after explicit client delivery receipts 
(markMessageDelivered()).

## 5. Security & Protocol Hardening Checklist
1. XML Stream Safety: Disable DTDs and External Entities on all XMLInputFactory instances to prevent XXE attacks:

```java
factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
```


2. XML Escaping: Pass all user-controlled dynamic text inside custom stanza generators through escapeXml() to prevent stanza injection.

3. Local Port Binding: Bind internal management endpoints (BotApiHandler, GroupController) to loopback (127.0.0.1) when operating behind a reverse proxy (e.g., NGINX).