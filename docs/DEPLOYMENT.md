# Deployment

## Upgrade procedure

1. Backup the database
2. Deploy the new server version
3. Run database migrations
4. Verify migration success
5. Restart the server
6. Verify server health
7. Verify XMPP connections and messaging


## Database migrations

Before starting a new server version, all required
database migrations must have successfully completed.
If a migration fails, do not start the new application
version until the database issue has been investigated.