package streammessenger.roster;

import java.util.HashMap;
import java.util.Map;

/** A pure function producing the desired next state from the current one (version is assigned by the service). */
@FunctionalInterface
public interface ProfileMutation {
    Profile apply(Profile current);

    static ProfileMutation displayName(String name) {
        return p -> new Profile(p.userId(), p.version(), name, p.avatarHash(), p.statusText(), p.metadata(), p.updatedAt());
    }
    static ProfileMutation avatar(String hash) {
        return p -> new Profile(p.userId(), p.version(), p.displayName(), hash, p.statusText(), p.metadata(), p.updatedAt());
    }
    static ProfileMutation status(String status) {
        return p -> new Profile(p.userId(), p.version(), p.displayName(), p.avatarHash(), status, p.metadata(), p.updatedAt());
    }
    static ProfileMutation metadata(String key, String value) {
        return p -> {
            Map<String, String> m = new HashMap<>(p.metadata());
            if (value == null) m.remove(key); else m.put(key, value);
            return new Profile(p.userId(), p.version(), p.displayName(), p.avatarHash(), p.statusText(), m, p.updatedAt());
        };
    }
    default ProfileMutation andThen(ProfileMutation next) { return p -> next.apply(apply(p)); }
}
