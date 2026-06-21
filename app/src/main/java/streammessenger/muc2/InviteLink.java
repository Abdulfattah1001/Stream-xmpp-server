package streammessenger.muc;

import java.time.Instant;

/** One invite token + its rules. Token itself is stored HASHED in the repo. */
public final class InviteLink {
    public final String token;        // raw token (the secret in the URL)
    public final String roomJid;
    public final String createdByBare;
    public final Instant expiresAt;   // null = never
    public final int maxUses;         // 0 = unlimited
    public int uses;
    public boolean revoked;

    public InviteLink(String token, String roomJid, String createdByBare,
                      Instant expiresAt, int maxUses) {
        this.token = token; this.roomJid = roomJid; this.createdByBare = createdByBare;
        this.expiresAt = expiresAt; this.maxUses = maxUses;
    }
    public boolean usable() {
        if (revoked) return false;
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) return false;
        if (maxUses > 0 && uses >= maxUses) return false;
        return true;
    }
}