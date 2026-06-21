package streammessenger.muc.service;


import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import streammessenger.muc.model.GroupRoom;
import streammessenger.muc.model.GroupSettings;
import streammessenger.muc.repository.GroupRepository;

/**
 * In-memory registry of active GroupRoom instances.
 *
 * Lazy-loaded: rooms are loaded into memory on first access.
 * Eviction: rooms with zero occupants are evicted after idle timeout.
 *
 * Thread-safe via ConcurrentHashMap.
 */
public final class GroupRegistry {

    private static final Logger logger =
            Logger.getLogger(GroupRegistry.class.getName());

    private final ConcurrentHashMap<String, GroupRoom> rooms =
            new ConcurrentHashMap<>();

    private final GroupRepository repository;

    public GroupRegistry(GroupRepository repository) {
        this.repository = repository;
    }

    /**
     * Loads a room into memory, or returns existing instance.
     */
    public GroupRoom getOrLoad(String groupId) {
        return rooms.computeIfAbsent(groupId, this::loadFromDb);
    }

    public GroupRoom getIfActive(String groupId) {
        return rooms.get(groupId);
    }

    public void evict(String groupId) {
        rooms.remove(groupId);
    }

    public int activeRoomCount() {
        return rooms.size();
    }

    private GroupRoom loadFromDb(String groupId) {
        GroupRepository.GroupRecord record = repository.getGroup(groupId);
        if (record == null || record.deletedAt() != null) return null;

        GroupSettings settings = repository.getSettings(groupId);

        GroupRoom room = new GroupRoom(
                record.groupId(), record.jid(), record.name(),
                record.description(), record.avatarUrl(),
                record.creatorUserId(), record.visibility(),
                record.maxMembers(), record.memberCount(),
                settings, record.createdAt()
        );

        logger.fine("Group loaded into memory: " + groupId);
        return room;
    }
}