package com.callsheet;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Domain types. In the real build these become JPA entities backed by PostgreSQL. */
public final class Models {
    private Models() {}

    public enum Role { MANAGER, PRODUCTION }

    public enum BlockType { SHOOTING, VACATION }

    public enum MeetingStatus { PENDING, ACCEPTED, DECLINED, COUNTER_PROPOSED, CANCELLED }

    public record User(String id, String name, Role role, String org, String email) {}

    public record Actor(String id, String name, String managerId, List<String> languages,
                        List<String> genres, String city, String bio) {}

    /** A busy period. Anything not covered by a block counts as available. */
    public record Block(String id, String actorId, LocalDate start, LocalDate end,
                        BlockType type, String note) {}

    public record Event(Instant at, String by, String role, String action, String comment,
                        LocalDate date, String time) {}

    public static class Meeting {
        public final String id;
        public final String actorId;
        public final String productionUserId;
        public final Role initiatedBy;
        public final String projectTitle;
        public final String message;
        public final List<Block> snapshot;
        public final List<Event> history = new ArrayList<>();
        public Role awaiting;
        public MeetingStatus status;
        public LocalDate date;
        public String time;

        public Meeting(String id, String actorId, String productionUserId, Role initiatedBy,
                       String projectTitle, String message, LocalDate date, String time,
                       List<Block> snapshot) {
            this.id = id;
            this.actorId = actorId;
            this.productionUserId = productionUserId;
            this.initiatedBy = initiatedBy;
            this.projectTitle = projectTitle;
            this.message = message;
            this.date = date;
            this.time = time;
            this.snapshot = snapshot;
            this.status = MeetingStatus.PENDING;
            this.awaiting = initiatedBy == Role.MANAGER ? Role.PRODUCTION : Role.MANAGER;
        }

        public Instant lastActivity() {
            return history.get(history.size() - 1).at();
        }
    }
}
