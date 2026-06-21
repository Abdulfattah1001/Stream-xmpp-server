package streammessenger.api;

import java.util.Optional;

public interface GroupInviteLinkRepository {
    void save(GroupInviteLink link);
    Optional<GroupInviteLink> findByToken(String token);
    Optional<GroupInviteLink> findByGroupId(String groupId);
    void update(GroupInviteLink link);
    void delete(String id);
}