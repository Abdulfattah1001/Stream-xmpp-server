package streammessenger.group.model;


import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;


/**
 * In-memory representation of an active group room.
 * <p>
 * One instance per group. Lives in GroupRegistry while the room is active.
 * Contains:
 *   - Static config (groupId, jid, name, settings)
 *   - Dynamic occupant list (users currently online in the room)
 * <p>
 * Thread safety:
 *   - occupants: ConcurrentHashMap (lock-free reads)
 *   - settings: volatile reference (replaced atomically on update)
 *   - metadata changes: writeLock
 */
public final class GroupRoom {

    private final String groupId;
    private final String jid;
    private volatile String name;
    private volatile String description;
    private volatile String avatarUrl;
    private final String creatorUserId;
    private volatile GroupVisibility visibility;
    private volatile int maxMembers;
    private volatile int memberCount;
    private volatile GroupSettings settings;
    private final Instant createdAt;

    // userId → Occupant
    private final ConcurrentHashMap<String, Occupant> occupants =
            new ConcurrentHashMap<>();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public GroupRoom(String groupId, String jid, String name,
                     String description, String avatarUrl,
                     String creatorUserId, GroupVisibility visibility,
                     int maxMembers, int memberCount,
                     GroupSettings settings, Instant createdAt) {
        this.groupId       = groupId;
        this.jid           = jid;
        this.name          = name;
        this.description   = description;
        this.avatarUrl     = avatarUrl;
        this.creatorUserId = creatorUserId;
        this.visibility    = visibility;
        this.maxMembers    = maxMembers;
        this.memberCount   = memberCount;
        this.settings      = settings;
        this.createdAt     = createdAt;
    }

    // =========================================================================
    // Occupant management
    // =========================================================================

    public boolean addOccupant(Occupant occupant) {
        return occupants.putIfAbsent(occupant.userId(), occupant) == null;
    }

    public Occupant removeOccupant(String userId) {
        return occupants.remove(userId);
    }

    public Occupant getOccupant(String userId) {
        return occupants.get(userId);
    }

    public Collection<Occupant> getOccupants() {
        return occupants.values();
    }

    public int getOccupantCount() {
        return occupants.size();
    }

    public boolean hasOccupant(String userId) {
        return occupants.containsKey(userId);
    }

    // =========================================================================
    // Metadata - guarded by lock
    // =========================================================================

    public void updateMetadata(String name, String description,
                                String avatarUrl) {
        lock.writeLock().lock();
        try {
            if (name != null) this.name = name;
            if (description != null) this.description = description;
            if (avatarUrl != null) this.avatarUrl = avatarUrl;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void incrementMemberCount() {
        lock.writeLock().lock();
        try { memberCount++; }
        finally { lock.writeLock().unlock(); }
    }

    public void decrementMemberCount() {
        lock.writeLock().lock();
        try { memberCount = Math.max(0, memberCount - 1); }
        finally { lock.writeLock().unlock(); }
    }

    public void updateSettings(GroupSettings newSettings) {
        this.settings = newSettings; // volatile - atomic
    }

    // =========================================================================
    // Getters
    // =========================================================================

    private volatile boolean justCreated = false;
    private volatile String subject;

    public void markJustCreated() {
        this.justCreated = true;
    }

    public void clearJustCreated() {
        this.justCreated = false;
    }

    public boolean isJustCreated() {
        return justCreated;
    }

    public String getSubject() { return subject; }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getGroupId() { return groupId; }
    public String getJid() { return jid; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getAvatarUrl() { return avatarUrl; }
    public String getCreatorUserId() { return creatorUserId; }
    public GroupVisibility getVisibility() { return visibility; }
    public int getMaxMembers() { return maxMembers; }
    public int getMemberCount() { return memberCount; }
    public GroupSettings getSettings() { return settings; }
    public Instant getCreatedAt() { return createdAt; }
}