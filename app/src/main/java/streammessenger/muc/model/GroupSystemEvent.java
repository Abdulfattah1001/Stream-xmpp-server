package streammessenger.muc.model;

import java.time.Instant;

public record GroupSystemEvent(
        GroupEventType type,

        String actorId,

        String subjectId,

        Instant timestamp
) {}