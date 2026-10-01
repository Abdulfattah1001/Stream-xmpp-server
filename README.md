# 📨 Custom XMPP Protocols for An Instant Messaging App

This project provides a set of **custom XMPP (Extensible Messaging and Presence Protocol) extensions** tailored for a modern, feature-rich instant messaging application, with user data managed by a **self-hosted MySQL database**. The system enhances standard XMPP functionality to support reactions, typing indicators, threaded messaging, media metadata, and more.

---

## 📌 Table of Contents

- [Features](#features)
- [Architecture Overview](#architecture-overview)
- [Custom XMPP Protocols](#custom-xmpp-protocols)
- [Database Schema](#database-schema)
- [Installation & Setup](#installation--setup)
- [Usage](#usage)
- [Development](#development)
- [License](#license)
- [Contact](#contact)

---

## ✅ Features

- 💬 Message Delivery Receipts
- ✍️ Real-time Typing Indicators
- ❤️ Emoji Reactions to Messages
- 🧵 Threaded Replies and Message Context
- 📷 Media Metadata Support
- 👥 Self-managed MySQL User Auth & Registration
- 🔐 Future-ready for End-to-End Encryption (E2EE)
- 🧩 Built for XMPP servers (e.g., **ejabberd**, **Prosody**)

---

## 🏗 Architecture Overview

                +-----------------------+
                |   Mobile/Web App      |
                +----------+------------+
                            |            
                     [ Custom XMPP ]    
                            |            
                +----------v------------+
                |   XMPP Server Core    |
                | (with custom stanzas) |
                +----------+------------+
                            |            
                    [ MySQL Backend ]  
                            |            
                +----------v------------+
                |     User DB (MySQL)   |
                |   - Auth              |
                |   - Presence          |
                |   - Contact Lists     |
                +-----------------------+


---

## 📜 Custom XMPP Protocols

| Namespace                        | Purpose                        |
|----------------------------------|--------------------------------|
| `urn:xmpp:custom:reactions`      | Emoji reactions to messages    |
| `urn:xmpp:custom:typing`         | Typing indicators              |
| `urn:xmpp:custom:threads`        | Message threading              |
| `urn:xmpp:custom:media`          | Media metadata                 |
| `urn:xmpp:custom:register`       | User registration integration  |

**Sample stanza: Reaction**

```xml
<reactions xmlns='urn:xmpp:custom:reactions'>
  <reaction emoji="🔥" id="msg1234" />
</reactions>
```
**Sample stanza: Typing Status**

```xml
<composing xmlns='urn:xmpp:custom:typing' status="typing" />
```

## 🗃 Database Schema
The system uses a MySQL database to manage users and messaging metadata.

### users table

```sql
CREATE TABLE users (
    uid VARCHAR(50) NOT NULL PRIMARY KEY,
    contactId VARCHAR(50) NOT NULL UNIQUE,
    displayName VARCHAR(255) NULL,
    email VARCHAR(100) DEFAULT NULL,
    avatarUrl VARCHAR(512) DEFAULT NULL,
    status VARCHAR(125) DEFAULT NULL,
    fcmToken VARCHAR(256) DEFAULT NULL,
    isAnonymous BOOLEAN DEFAULT FALSE,
    identityKey TEXT,
    deviceId VARCHAR(100) DEFAULT NULL,
    createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updatedAt TIMESTAMP  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX uidIdx(uid),
    INDEX contactIdx(contactId),
    INDEX emailIdx(email)
);
```

### messages table
```sql
CREATE TABLE messages (
  id VARCHAR(50) NOT NULL PRIMARY KEY,
  sender_id VARCHAR(20),
  receiver_id VARCHAR(20),
  body TEXT,
  timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### Offline messages
```sql
CREATE TABLE offline_messages (
    messageId VARCHAR(255) PRIMARY KEY NOT NULL,
    receipientId VARCHAR(255) NOT NULL,
    senderId VARCHAR(255) NOT NULL,
    messageType ENUM('CHAT', 'MEDIA', 'CALL') DEFAULT 'CHAT',
    encryptedPayload TEXT NULL,
    mediaUrl VARCHAR(512) DEFAULT NULL,
    timestamp VARCHAR(50) NOT NULL,
    expiresAt TIMESTAMP DEFAULT NULL,
    delivered BOOLEAN DEFAULT FALSE,
    createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX recipientIdx(receipientId), 
    INDEX deliveredIdx(delivered));

```

### rosters tables
```sql
CREATE TABLE rosters (
    uid VARCHAR(50) NOT NULL,
    contactId VARCHAR(50) NOT NULL,
    subscription ENUM('NONE', 'FROM', 'TO', 'BOTH') DEFAULT 'NONE', 
    ask ENUM('SUBSCRIBE', 'UNSUBSCRIBE') DEFAULT NULL,
    displayName VARCHAR(255) DEFAULT NULL,
    blocked BOOLEAN DEFAULT FALSE,
    muted BOOLEAN DEFAULT FALSE,
    createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (uid, contactId),
    updatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP);
```
Full schema definitions are located in the /sql directory.


## 🔐 User Registration (Secure Backend API)
User registration is managed outside of XMPP via a secure HTTPS endpoint provided by the backend server. This helps keep authentication and user provisioning secure, audited, and independent of the XMPP layer.

### 🧩 Endpoint Specification
- Method: POST

- URL: https://streamsync.org/api/register

- Content-Type: application/json

- Authentication: None (public for registration; rate-limited)

**Request Body:**

```json
{
  "username": "alice",
  "password": "SuperSecurePass123"
}
```
You can later expand this to include email verification, CAPTCHA, etc.

### ✅ Sample Responses
**Success**

```json
{
  "status": "success",
  "message": "User registered successfully"
}
```
**Failure**

```json
{
  "status": "error",
  "message": "Username already exists"
}
```

## ⚙️ Installation & Setup
### Prerequisites

- MySQL (8.0+)

- Java 21+ backend (optional for API wrappers)

**1. Clone the repository**
```bash
git clone https://github.com/Abdulfattah1001/StreamServer.git
cd custom-xmpp-im
```

**2. Set up MySQL**
```bash
mysql -u root -p < sql/init.sql
Update credentials in .env or config file.
```

**3. Configure XMPP Server**
- Enable custom stanzas support

- Map authentication layer to MySQL

- Register new namespaces for the extensions

See [docs/xmpp-server-setup.md](docs/xmpp-server-setup.md) for step-by-step instructions.

## 🛠 Usage
### Registering a New User (via stanza)
```xml
<register xmlns='urn:xmpp:custom:register'>
  <username>alice</username>
  <password>securepass</password>
</register>
```
### Sending a Message with Threading
```xml
<message from='alice@example.com' to='bob@example.com' type='chat'>
  <body>Replying to your earlier message!</body>
  <thread xmlns='urn:xmpp:custom:threads' parent='msgid4567' />
</message>
```

## 🧪 Development
Run Tests
```bash
npm test  # or pytest
```
Validate XML Protocols
```bash
xmllint --schema schemas/reactions.xsd examples/reactions.xml
```
## Database Access
Configure .env or config/db.json for local database connection:

```ini
DB_HOST=localhost
DB_USER=root
DB_PASS=yourpassword
DB_NAME=xmpp_chat
```
## 📁 Project Structure

```bash
├─src/
│ └─main/
│    ├─java/
│      │  └─org/
│      │     └─stream/
│      │         └─xmpp/
│      │            ├─server/   # XMPP stanza handlers, components
│      │            ├─protocol/  # Custom protocol logic
│      │            └─database/  # MySQL DB access, models
│      └─resources/
│         ├─config/             # Application config files
│         ├─schemas/            # XML schema definitions
│         └─sql/                # SQL schema and seed data
│
├─src/
│ └─test/
│    └─java/
│       └─org/
│         └─stream/
│            └─xmpp/
│               └─tests/      # JUnit tests
│
├─examples/                  # Sample XML stanzas
├─docs/                      # Protocol specs, architecture diagrams
├─build.gradle                    # Maven build file
└─README.md                  # You're here!
```

## 📄 License
This project is licensed under the MIT License. See LICENSE for details.

## 📬 Contact
- Maintainer: dev@example.com

- Issues / Feedback: GitHub Issues

## 🤝 Contributing
Pull requests are welcome! Please read the CONTRIBUTING.md for details.


```sql
CREATE TABLE offline_messages (
  messageId CHAR(36) NOT NULL PRIMARY KEY,
  receipientId CHAR(36) NOT NULL,
  senderId CHAR(36) NOT NULL,
  messageType ENUM('CHAT', 'MEDIA', 'CALL', 'STICKER') DEFAULT 'CHAT',
  textContent TEXT,
  parentId CHAR(36) DEFAULT NULL,
  
  -- Media-related
  mediaUrl VARCHAR(512) DEFAULT NULL,
  thumbnailUrl VARCHAR(512) DEFAULT NULL,
  mime VARCHAR(125) DEFAULT NULL,
  mediaSize BIGINT UNSIGNED DEFAULT NULL,
  mediaDuration INT UNSIGNED DEFAULT NULL,
  caption TEXT DEFAULT NULL,

  -- Sticker-related
  stickerId VARCHAR(64) DEFAULT NULL,
  stickerPackId VARCHAR(64) DEFAULT NULL,
  delivered TINYINT(1) NULL DEFAULT 0,
  timestamp VARCHAR(125) NULL,
  expiresAt TIMESTAMP NULL DEFAULT NULL,
  createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

  INDEX idx_recipient_delivered (receipientId, delivered),
  INDEX idx_expiresAt (expiresAt),
  INDEX idx_messageType (messageType)
);
```