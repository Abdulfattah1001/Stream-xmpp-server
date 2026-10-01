package streammessenger.profile;

@FunctionalInterface
public interface ProfileMutation {
    Profile apply(Profile current);

    static ProfileMutation displayName(String name) {
        return p -> new Profile(
                p.userId(), p.username(),
                p.version(), name,
                p.avatarUrl(), p.statusText(),
                p.metadata(), p.at()
        );
    }

    static ProfileMutation username(String name) {
        return p -> new Profile(
                p.userId(), name,
                p.version(), p.displayName(),
                p.avatarUrl(), p.statusText(),
                p.metadata(), p.at()
        );
    }

    static ProfileMutation statusText(String status) {
        return p -> new Profile(
                p.userId(), p.username(),
                p.version(), p.displayName(),
                p.avatarUrl(), status,
                p.metadata(), p.at()
        );
    }
}
