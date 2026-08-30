package streammessenger.vhost;

/**
 * Configuration for one virtual hosted domain.
 * <p>
 * A single XMPP server can host multiple domains:
 *   company.com        → corporate users
 *   personal.net       → personal accounts
 *   conference.company → MUC (Multi-User Chat) rooms
 * <p>
 * Each domain has its own:
 *   - TLS certificate (optional, can share the server's)
 *   - Admin contact
 *   - Feature flags (registration open/closed, etc.)
 *   - Description
 */
public final class DomainConfig {

    private final String domain;
    private final boolean registrationOpen;
    private final boolean federationEnabled;
    private final String adminContact;
    private final String description;
    private final boolean isConferenceDomain;

    private DomainConfig(Builder b) {
        this.domain = b.domain;
        this.registrationOpen = b.registrationOpen;
        this.federationEnabled = b.federationEnabled;
        this.adminContact = b.adminContact;
        this.description = b.description;
        this.isConferenceDomain = b.isConferenceDomain;
    }

    public String getDomain() { return domain; }
    public boolean isRegistrationOpen() { return registrationOpen; }
    public boolean isFederationEnabled() { return federationEnabled; }
    public String getAdminContact() { return adminContact; }
    public String getDescription() { return description; }
    public boolean isConferenceDomain() { return isConferenceDomain; }

    @Override
    public String toString() {
        return String.format(
            "DomainConfig{domain=%s, regOpen=%b, federation=%b, conference=%b}",
            domain, registrationOpen, federationEnabled, isConferenceDomain
        );
    }

    public static final class Builder {
        private final String domain;
        private boolean registrationOpen = false;
        private boolean federationEnabled = false;
        private String adminContact;
        private String description;
        private boolean isConferenceDomain = false;

        public Builder(String domain) {
            if (domain == null || domain.isBlank()) {
                throw new IllegalArgumentException("Domain cannot be null/empty");
            }
            this.domain = domain.toLowerCase();
        }

        public Builder registrationOpen(boolean open) {
            this.registrationOpen = open; return this;
        }
        public Builder federationEnabled(boolean enabled) {
            this.federationEnabled = enabled; return this;
        }
        public Builder adminContact(String contact) {
            this.adminContact = contact; return this;
        }
        public Builder description(String desc) {
            this.description = desc; return this;
        }
        public Builder conferenceDomain(boolean conference) {
            this.isConferenceDomain = conference; return this;
        }

        public DomainConfig build() {
            return new DomainConfig(this);
        }
    }
}