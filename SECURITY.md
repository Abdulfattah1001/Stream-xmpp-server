# Security Policy & Architecture Guidelines

## Overview
This document outlines the security controls, authentication mechanisms, thread-safety guarantees, and vulnerability reporting procedures for the Stream Messenger Server infrastructure.

## Core Security Controls

### 1. Transport Security & Network Boundary
* **TLS Termination & Transport Enforcements:**
    * Client connections via Nginx edge proxies must enforce standard TLS 1.2 or TLS 1.3 protocols (`TLSv1.2`, `TLSv1.3`).
    * Legacy protocol suites (`SSLv3`, `TLSv1.0`, `TLSv1.1`) and insecure cipher suites (`NULL`, `anon`, `EXPORT`, `DES`, `RC4`, `3DES`) are stripped at the socket/upgrader level.
    * Local administrative endpoints (e.g., Bot API, Health, Group links) must bind to internal loopback interfaces (`127.0.0.1`) when positioned behind Nginx edge termination to prevent unauthenticated public ingress.

### 2. Authentication & Session Management
* **SASL PLAIN Authentication:**
    * In-band SASL PLAIN negotiations are strictly forbidden over unencrypted plain-text TCP connections. TLS negotiation (`STARTTLS`) or proxy-level TLS termination (`X-Forwarded-Proto`) must be verified prior to credential exchange.
* **Token Storage Security:**
    * Plaintext session tokens (`st_...`) are never persisted in storage.
    * All tokens are hashed using SHA-256 (`DatabaseManager.hashToken()`) prior to persistence in the database (`session_tokens` table).
    * Session token revocation occurs instantly upon explicit single-device or all-device logout requests.
* **Firebase Token Verification:**
    * Standard Firebase ID tokens (JWTs) are validated via standard RS256 signature checks using Google's published public X.509 keys (`https://www.googleapis.com/robot/v1/metadata/x509/...`).
    * Token claims (`iss`, `aud`, `sub`, `exp`, `iat`) are parsed strictly using JSON parsers (`SimpleJson`) to prevent string-indexing injection bypasses.

### 3. Data Protection & Cryptography
* **End-to-End Encrypted (E2EE) Stanzas:**
    * Instant messaging stanzas are delivered and queued as encrypted payload blobs (AES-256-GCM ciphertext + IV).
    * The IM server acts purely as an encrypted routing transit node and cannot decrypt end-to-end user payload content.
* **Database Field Protection:**
    * User phone numbers are stored as salted SHA-256 hashes (`phone_number_hash`) for contact discovery, ensuring user address books cannot be reverse-engineered from database dumps.

### 4. Input Validation & XML/JSON Hardening
* **XML External Entity (XXE) Prevention:**
    * All `XMLInputFactory` parser instances explicitly disable external entity processing, DTD parsing, and entity references:
      ```java
      factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
      factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
      factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
      ```
* **Stanza & Injection Escaping:**
    * Dynamic XML stanza constructors wrap attributes and text nodes with XML entity replacement (`&amp;`, `&lt;`, `&gt;`, `&quot;`, `&apos;`).
    * JSON payloads omit trailing commas to maintain strict RFC 8259 parser compatibility.

### 5. Reporting Vulnerabilities
If you discover a security vulnerability within this repository:

1. Please **Do Not** report security vulnerabilities through public Github issues.
2. Email the system security team at `security@omnyrex.com` with reproducible steps or proof-of-concept payloads.
3. Potential impact
4. Suggested mitigations, if known.
5. Allow up to 48 hours for an initial triage response before disclosing details publicly.