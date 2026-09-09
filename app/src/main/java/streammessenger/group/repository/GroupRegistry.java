package streammessenger.group.repository;

import java.util.concurrent.ConcurrentHashMap;

import streammessenger.group.model.Group;

public class GroupRegistry {
    private final ConcurrentHashMap<String, Group> groups = new ConcurrentHashMap<String, Group>();

    public GroupRegistry() {}


    public void register(Group group) {}


    public Group get(String groupId) {
        return groups.get(groupId);
    }

    public void deleteGroup(String groupId) {}

    public void updateGroup(String groupId) {}
}
