# Contributing to Stream XMPP

Thank you for your interest in contributing.
This document explains how to get involved effectively.

---

## Table of Contents

- [Before You Start](#before-you-start)
- [Types of Contributions](#types-of-contributions)
- [Development Setup](#development-setup)
- [Code Style](#code-style)
- [Commit Messages](#commit-messages)
- [Pull Request Process](#pull-request-process)
- [Reporting Bugs](#reporting-bugs)
- [Suggesting Features](#suggesting-features)
- [Security Vulnerabilities](#security-vulnerabilities)
- [Architecture Decisions](#architecture-decisions)
- [Testing Requirements](#testing-requirements)
- [Documentation](#documentation)
- [Good First Issues](#good-first-issues)

---

## Before You Start

1. Read the [README.md](README.md) fully
2. Read the [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)
3. Check [open issues](https://github.com/yourusername/zeal-xmpp/issues)
   before starting work to avoid duplicating effort
4. For large changes, open a discussion issue first

---

## Types of Contributions

### What We Welcome

```
✅ Bug fixes
✅ Performance improvements
✅ New XEP implementations (see roadmap in README)
✅ Test coverage improvements
✅ Documentation improvements
✅ Security hardening
✅ Client library platform ports (iOS Swift, Kotlin Multiplatform)
✅ Example applications
```

### What We Will Not Accept

```
❌ Introducing external framework dependencies
(no Spring, Netty, Jackson, Gson, OkHttp, etc.)
The zero-dependency principle is core to this project.

❌ Breaking changes to the public API without RFC discussion

❌ Code that reduces test coverage

❌ Features without documentation

❌ Implementations that compromise the E2E encryption model
(server must never see plaintext message content)

❌ Changes that store passwords in any form
(session tokens and Firebase only)
```

---

## Development Setup

```bash
# 1. Fork the repository on GitHub

# 2. Clone your fork
git clone https://github.com/Abdulfattah1001/StreamServer.git
cd zeal-xmpp

# 3. Add upstream remote
git remote add upstream https://github.com/Abdulfattah1001/StreamServer.git

# 4. Set up the database
createdb zealxmpp_test
psql -d zealxmpp_test -f sql/schema.sql

# 5. Set environment variables
export KEYSTORE_PASSWORD=changeit
export KEY_PASSWORD=changeit
export DB_PASSWORD=postgres
export ENV=DEV

# 6. Generate dev certificate
keytool -genkeypair \
  -alias zeal-xmpp \
  -keyalg RSA -keysize 2048 -validity 365 \
  -keystore certs/dev-keystore.p12 \
  -storetype PKCS12 \
  -storepass changeit -keypass changeit \
  -dname "CN=localhost, O=ZealXMPP, C=US"

# 7. Build and run tests
mvn clean test

# 8. Run the server
mvn clean package -DskipTests
java -jar server/target/zeal-xmpp-server.jar config.properties
```

---

## Code Style

We follow standard Java conventions with a few additions.

### General Rules

```java
// ✅ Good - clear intent, proper documentation
/**
 * Validates a session token.
 * Returns null if invalid - never throws for invalid tokens.
 *
 * @param rawToken The raw token presented by the client
 * @return ValidatedToken if valid, null if invalid/revoked
 */
public ValidatedToken validate(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) return null;
    // ...
}

// ❌ Bad - no documentation, unclear intent
public ValidatedToken v(String t) {
    // ...
}
```

```java
// ✅ Good - descriptive variable names
long sessionTimeoutMs = 120_000L;
int maxReconnectAttempts = 10;

// ❌ Bad - single letter variables (except loop counters)
long t = 120000;
int m = 10;
```

```java
// ✅ Good - explicit null checks at public API boundaries
public void bindAuthenticatedSession(String contactId, Session session) {
    Objects.requireNonNull(contactId, "contactId cannot be null");
    Objects.requireNonNull(session, "session cannot be null");
    // ...
}

// ❌ Bad - let NullPointerException happen at random
public void bindAuthenticatedSession(String contactId, Session session) {
    contactToUid.put(contactId, session.getUid()); // NPE if null
}
```

### Thread Safety

```java
// ✅ Good - document thread safety contract
/**
 * Thread safe. Multiple threads may call writeXML() concurrently.
 * Internal write lock ensures XML is never interleaved.
 */
public boolean writeXML(String xml) {
    writeLock.lock();
    try { /* ... */ }
    finally { writeLock.unlock(); }
}

// ✅ Good - use AtomicLong for high-write counters
private final AtomicLong activeConnections = new AtomicLong(0);

// ❌ Bad - unsynchronized shared mutable state
private long activeConnections = 0; // race condition
```

### No External Dependencies

```xml
<!-- ❌ Do NOT add these or any similar external libraries -->
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-core</artifactId>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>

<!-- ✅ Only these external dependencies are allowed -->
<!-- PostgreSQL JDBC driver (necessary for DB connectivity) -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
</dependency>
<!-- JUnit 5 for tests only -->
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

### File Structure

```
Each class must:
  - Have a Javadoc comment explaining its purpose
  - Have a "Thread safety" note if it's used across threads
  - Group methods with // === Section === comments
  - Put constants at the top
  - Put constructors before public methods
  - Put private helpers at the bottom
  - Keep files under 600 lines where possible
```

---

## Commit Messages

We follow [Conventional Commits](https://www.conventionalcommits.org/).

```
Format: <type>(<scope>): <short description>

Types:
  feat     New feature
  fix      Bug fix
  perf     Performance improvement
  refactor Code change that neither fixes nor adds feature
  test     Adding or fixing tests
  docs     Documentation only
  chore    Build, CI, dependency updates

Examples:
  feat(sm): implement session resumption after disconnect
  fix(auth): prevent timing attack in token comparison
  perf(db): batch offline message queries with single SELECT
  docs(readme): add stream management flow diagram
  test(roster): add unit tests for subscription state machine
  refactor(session): extract write lock to StanzaWriter class
  chore(ci): add GitHub Actions workflow for PRs

Rules:
  - Subject line max 72 characters
  - Use imperative mood ("add" not "added" or "adds")
  - No period at end of subject
  - Separate subject from body with blank line
  - Reference issues: "Closes #42" or "Fixes #42"
```

---

## Pull Request Process

### Step-by-Step

```bash
# 1. Create a feature branch from main
git checkout main
git pull upstream main
git checkout -b feat/session-resumption

# 2. Make your changes
# ... write code ...
# ... write tests ...
# ... update docs ...

# 3. Ensure tests pass
mvn clean test

# 4. Ensure code compiles with no warnings
mvn clean compile

# 5. Commit with conventional commit message
git add -A
git commit -m "feat(sm): implement XEP-0198 session resumption

Adds full session resumption support:
- Client sends <resume previd='...' h='N'/> after reconnect
- Server retransmits all unacked stanzas after sequence N
- No re-authentication required
- SM state persisted in sm_sessions table for 5 minutes

Closes #42"

# 6. Push to your fork
git push origin feat/session-resumption

# 7. Open PR on GitHub
# Fill in the PR template
```

### PR Requirements Checklist

```
[ ] Branch is up to date with main
[ ] All existing tests pass (mvn test)
[ ] New tests added for new functionality
[ ] No new external dependencies added
[ ] Javadoc added for new public methods and classes
[ ] CHANGELOG.md updated (if applicable)
[ ] No hardcoded secrets or passwords
[ ] Thread safety documented for shared components
[ ] PR description explains WHY, not just WHAT
```

### PR Template

When opening a PR, your description should include:

```markdown
## What does this PR do?

Brief description of the change.

## Why is this change needed?

Explain the problem being solved.

## How was this tested?

- [ ] Unit tests added
- [ ] Integration tested manually with XMPP client
- [ ] Load tested (if performance-sensitive)

## Breaking changes?

None / Describe breaking changes here.

## Related issues

Closes #XX
```

---

## Reporting Bugs

Open an issue with this template:

```markdown
**Describe the bug**
A clear description of what the bug is.

**To Reproduce**
Steps to reproduce:
1. Configure server with ...
2. Connect client with ...
3. Send message to ...
4. See error

**Expected behavior**
What you expected to happen.

**Actual behavior**
What actually happened.

**Logs**
```
Paste relevant log output here
```

**Environment**
- Java version: 21
- PostgreSQL version: 16
- OS: Ubuntu 22.04
- Server version: 1.0.0
- Client platform: Android 14
```

---

## Suggesting Features

Open an issue with the `enhancement` label:

```markdown
**Feature summary**
One sentence description.

**Problem it solves**
What user problem does this address?

**Proposed solution**
How should it work?

**Alternatives considered**
What other approaches did you consider and why did you reject them?

**XEP/RFC reference**
If applicable: XEP-0280, RFC 6121, etc.

**Are you willing to implement this?**
Yes / No / Maybe
```

---

## Security Vulnerabilities

**Do NOT open a public issue for security vulnerabilities.**

Email: security@omnyrex.com

Include:
- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Suggested fix (if any)

We will respond within 48 hours and coordinate disclosure.

---

## Architecture Decisions

Before making significant architectural changes, open a
**Discussion** issue titled `[ADR] Your proposal title`.

Key principles that MUST be preserved:

```
1. Zero external framework dependencies
   The codebase must remain readable by any Java developer
   without knowledge of any specific framework.

2. Server is a blind router
   The server must never be able to read message content.
   All message routing must work on ciphertext only.

3. Session tokens, not passwords
   Users authenticate with Firebase. The server issues session tokens.
   No password is ever stored or transmitted after initial registration.

4. Stateless handlers
   All stanza handlers (MessageHandler, IQHandler, etc.) must be
   stateless singletons. Per-connection state lives in Session only.

5. Explicit over implicit
   No reflection, no annotations, no "magic".
   Every dependency is wired explicitly in Server.java.
```

---

## Testing Requirements

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=SessionRegistryTest

# Run with coverage report
mvn test jacoco:report
open target/site/jacoco/index.html
```

### Test Coverage Requirements

| Package | Minimum Coverage |
|---------|-----------------|
| `auth/` | 90% |
| `session/` | 85% |
| `roster/` | 85% |
| `xep/sm/` | 90% |
| `crypto/` | 95% |
| `db/` | 70% |
| `api/` | 70% |

### Test Structure

```java
// Test class naming: ClassNameTest
// Test method naming: methodName_scenario_expectedResult
class SessionRegistryTest {

    @Test
    void getByContactId_afterBind_returnsCorrectSession() {
        // Arrange
        SessionRegistry registry = SessionRegistry.getInstance();
        Session session = new Session(mockSocket(), "uid-1");
        registry.register(session);
        session.setContactId("alice@domain.com");

        // Act
        registry.bindAuthenticatedSession("alice@domain.com", session);
        Optional<Session> result = registry.getByContactId("alice@domain.com");

        // Assert
        assertTrue(result.isPresent());
        assertEquals("uid-1", result.get().getUid());
    }

    @Test
    void remove_cleansUpBothIndexes() { /* ... */ }

    @Test
    void bindAuthenticatedSession_evictsGhostSession() { /* ... */ }
}
```

---

## Documentation

When adding a new feature, update:

1. **Javadoc** on all new public classes and methods
2. **README.md** — add to features list if significant
3. **docs/** — add detailed doc for complex features
4. **CHANGELOG.md** — add entry under `[Unreleased]`

Documentation lives in `docs/`:

```
docs/
├── architecture.md        # How the pieces fit together
├── encryption.md          # E2E encryption design
├── stream-management.md   # XEP-0198 implementation details
├── api.md                 # HTTP API reference
├── deployment.md          # Production deployment guide
└── extending.md           # How to add new stanza handlers
```

---

## Good First Issues

Look for issues labeled [`good first issue`](https://github.com/Abdulfattah1001/StreamServer/labels/good%20first%20issue):

| Issue | Difficulty | Skills Needed |
|-------|-----------|---------------|
| Add unit tests for BCrypt implementation | Easy | JUnit 5 |
| Add unit tests for SimpleJson parser | Easy | JUnit 5 |
| Implement vCard / user profile IQ | Medium | XMPP, XML |
| Add metrics HTTP endpoint (/metrics) | Medium | Java HTTP |
| Implement XEP-0085 chat states on server | Easy | XMPP XML |
| Add message delivery timeout detection | Medium | Java concurrency |
| Write deployment guide for Ubuntu 22.04 | Easy | Linux, systemd |

---

Thank you for contributing to Zeal XMPP. Every contribution matters.
```

---

# CODE_OF_CONDUCT.md
```markdown
# Code of Conduct

## Our Pledge

We as contributors and maintainers pledge to make participation
in Zeal XMPP a harassment-free experience for everyone, regardless
of age, body size, disability, ethnicity, sex characteristics,
gender identity and expression, level of experience, education,
socio-economic status, nationality, personal appearance,
race, religion, or sexual identity and orientation.

We pledge to act and interact in ways that contribute to an open,
welcoming, diverse, inclusive, and healthy community.

---

## Our Standards

### Behavior That Contributes to a Positive Environment

```
✅ Using welcoming and inclusive language
✅ Being respectful of differing viewpoints and experiences
✅ Gracefully accepting constructive criticism
✅ Focusing on what is best for the community
✅ Showing empathy towards other community members
✅ Helping newcomers get started
✅ Giving credit where credit is due
✅ Being patient with questions, even repeated ones
```

### Unacceptable Behavior

```
❌ Sexualized language or imagery of any kind
❌ Trolling, insulting or derogatory comments, personal attacks
❌ Public or private harassment
❌ Publishing others' private information without permission
❌ Dismissing or belittling contributions based on experience level
❌ Sustained disruptive behavior in discussions or PRs
❌ Other conduct which could reasonably be considered inappropriate
in a professional setting
```

---

## Technical Disagreements

Technical disagreements are expected and welcome.
Handle them professionally:

```
✅ "I think approach B is better because X, Y, Z"
✅ "Have you considered the performance implications of..."
✅ "RFC 6120 section 4.3 says we should handle this differently"
✅ "Here's a benchmark showing the difference"

❌ "That's a terrible idea"
❌ "Any experienced developer would know..."
❌ "Why would you even suggest that"
```

When technical disagreements cannot be resolved between contributors,
a maintainer will make the final call.
The maintainer's decision on architecture and design is final.

---

## Scope

This Code of Conduct applies within all community spaces:

- GitHub issues, PRs, discussions, and code reviews
- Project Discord/Slack (if applicable)
- Any other forum where you represent the project

---

## Enforcement

### Reporting

Instances of abusive, harassing, or otherwise unacceptable behavior
may be reported to the maintainers at:

**conduct@omnyrex.com**

All complaints will be reviewed and investigated promptly and fairly.
All maintainers are obligated to respect the privacy and security
of the reporter.

### Response Process

```
1. Maintainer receives report
2. Maintainer reviews the incident within 48 hours
3. Maintainer contacts the reported party privately
4. Maintainer determines appropriate response
5. Response is applied
6. Both parties are notified of outcome

### Enforcement Guidelines

**1. Correction**
Community impact: Use of inappropriate language or unprofessional behavior.
Consequence: Private written warning explaining the violation.

**2. Warning**
Community impact: A violation through a single incident or series of actions.
Consequence: Warning with consequences for continued behavior.
No interaction with involved parties for a specified period.

**3. Temporary Ban**
Community impact: Serious violation or sustained inappropriate behavior.
Consequence: Temporary ban from community spaces (2 weeks to 3 months).

**4. Permanent Ban**
Community impact: Pattern of violations, harassment, or serious misconduct.
Consequence: Permanent ban from all community spaces.

---

## Attribution

This Code of Conduct is adapted from the
[Contributor Covenant](https://www.contributor-covenant.org/),
version 2.1.

---

## Questions?

If you have questions about this Code of Conduct, open a
GitHub Discussion or email conduct@veltrion.com.


---