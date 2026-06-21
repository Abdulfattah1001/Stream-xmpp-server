package streammessenger.muc.model;

public record GroupSystemEvent(
        GroupEventType type,

        String actorId,

        String subjectId,

        Instant timestamp
) {}