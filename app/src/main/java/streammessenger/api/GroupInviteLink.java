package streammessenger.api;

import java.time.Instant;
import java.util.UUID;

public class GroupInviteLink {
    private String id;
    private String groupId;
    private String token;        // The actual link token
    private String createdBy;    // User ID who created it
    private Instant createdAt;
    private Instant expiresAt;   // null = no expiry
    private int maxUses;         // 0 = unlimited
    private int currentUses;
    private boolean active;

    public GroupInviteLink(String groupId, String createdBy) {
        this.id = UUID.randomUUID().toString();
        this.groupId = groupId;
        this.createdBy = createdBy;
        this.token = generateSecureToken();
        this.createdAt = Instant.now();
        this.active = true;
        this.maxUses = 0;
        this.currentUses = 0;
    }

    private String generateSecureToken() {
        return SecureTokenGenerator.generate();
    }

    public boolean isValid() {
        if (!active) return false;
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) return false;
        if (maxUses > 0 && currentUses >= maxUses) return false;
        return true;
    }

    // Getters & setters
    public String getId() { return id; }
    public String getGroupId() { return groupId; }
    public String getToken() { return token; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public int getMaxUses() { return maxUses; }
    public void setMaxUses(int maxUses) { this.maxUses = maxUses; }
    public int getCurrentUses() { return currentUses; }
    public void incrementUses() { this.currentUses++; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public void setToken(String token) { this.token = token; }
}