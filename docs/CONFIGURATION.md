# Server Configuration Guide

## Configuration Sources
The server configuration is loaded from a properties file (typically `config.properties`) via `ServerConfig.load()`. Sensitive credentials (database passwords, keystore passwords) **must** be supplied via environment variables.

## Required Environment Variables

| Variable Name | Description | Example / Required Format |
|---|---|---|
| `DB_PASSWORD` | Password for PostgreSQL/MySQL JDBC user | `s3cur3_p@ssw0rd` |
| `KEYSTORE_PASSWORD` | Password for TLS PKCS12 Keystore (DEV mode) | `keystore_secret` |
| `KEY_PASSWORD` | Private key password within Keystore (DEV mode) | `key_secret` |
| `BOT_ADMIN_KEY` | Secret token to authenticate admin bot registration | `admin_secret_key_123` |

---

## Property Keys (`config.properties`)

### General & Network Settings
```properties
# Runtime environment: DEV or PROD
ENV=DEV

# XMPP Socket Listener Port
port=5222

# Bot API HTTP Port
botApiPort=5224

# Server Host Bounding Address
address=0.0.0.0

# Canonical XMPP Domain Name
domainName=chat.omnyrex.com

# TLS and CERTIFICATE
# Development Certificate Path (PKCS12)
devTlsCertPath=certs/dev_keystore.p12

# Production Certificate Path (PKCS12 - used if direct TLS is enabled)
prodTlsCertPath=certs/prod_keystore.p12


# Database connection pool
# JDBC URL
db.url=jdbc:postgresql://127.0.0.1:5432/streammessenger

# Database User
db.user=im_user

# Minimum Idle Connections
db.pool.min=5

# Maximum Active Connections
db.pool.max=20

# Connection Checkout Timeout (ms)
db.pool.timeout=30000

# Session and reaper policy
# Idle Session Timeout (ms)
sessionTimeoutMs=120000

# Background Session Reaper Interval (seconds)
reaperIntervalSec=30


#Push Notification
# Firebase Cloud Messaging
fcmProjectId=stream-6fa32
fcmServiceAccountJson=config/fcm_service_account.json

# Apple Push Notification Service
apnsBundleId=com.omnyrex.streammessenger

# Cloudinary Storage
cloudinary_cloudname=your_cloud_name
cloudinary_api_key=1234567890
cloudinary_api_secret=abcdefghijklmnopqrstuvwxyz
```


```bash
Deployment Modes
1. Nginx Reverse Proxy Setup (Recommended for PROD)
Set ENV=PROD.

Configure Nginx to handle public TLS termination on ports 443 / 5222.

Forward plaintext traffic locally to internal ports (127.0.0.1:5222 and 127.0.0.1:5224).

Pass X-Forwarded-For and X-Forwarded-Proto headers.

2. Direct TLS Listener Setup (DEV / Standalone)
Set ENV=DEV.

Supply KEYSTORE_PASSWORD and KEY_PASSWORD as environment variables.

Place a valid PKCS12 keystore file at devTlsCertPath.

The server will handle in-band STARTTLS directly on port 5222.
```