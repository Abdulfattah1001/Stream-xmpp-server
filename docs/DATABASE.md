# Database

The XMPP Server uses MySQL for offline message queues storage pending till the
recipient of the message comes online or available

## Schema migrations

Database schema changes are managed using Flyway.

Migrations files are located at 
`src/main/resources/db/migration`

Migrations files follow this naming convention:
V<version>_<description>.sql

Example:
V3_add_delivered_at.sql

## Rules

1. Never manually modify the production schema unless explicitly required
2. Never modify a migration that has already been deployed
3. Create a new migration for every schema change
4. Test migratioins against a database containing previous schema
5. Database migrations must be included in the release containing the application code that requires them


## Example

To add a column:
ALTER TABLE offline_messages ADD COLUMN delivered_at DATETIME NULL;

Create:
V3_add_delivered_at.sql
rather than modifying the existing migration