package com.callsheet;

import com.callsheet.Models.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stand-in for the database. All names are fictional.
 * Dates are seeded relative to "today" so the demo always looks current.
 */
@Component
public class DataStore {

    public final Map<String, User> users = new LinkedHashMap<>();
    public final Map<String, Actor> actors = new LinkedHashMap<>();
    public final Map<String, User> sessions = new ConcurrentHashMap<>();

    private final List<Block> blocks = new ArrayList<>();
    private final List<Meeting> meetings = new ArrayList<>();
    private int seq = 100;

    public DataStore() {
        seedPeople();
        seedSchedule();
    }

    // ---------- reads ----------

    public synchronized List<Block> blocksFor(String actorId) {
        List<Block> out = new ArrayList<>();
        for (Block b : blocks) {
            if (b.actorId().equals(actorId)) out.add(b);
        }
        out.sort(Comparator.comparing(Block::start));
        return out;
    }

    public synchronized boolean isFree(String actorId, LocalDate from, LocalDate to) {
        for (Block b : blocks) {
            if (b.actorId().equals(actorId) && !b.end().isBefore(from) && !b.start().isAfter(to)) {
                return false;
            }
        }
        return true;
    }

    public synchronized List<Meeting> meetings() {
        return new ArrayList<>(meetings);
    }

    public synchronized Meeting meeting(String id) {
        for (Meeting m : meetings) {
            if (m.id.equals(id)) return m;
        }
        return null;
    }

    // ---------- writes ----------

    public synchronized String nextId(String prefix) {
        return prefix + "-" + (seq++);
    }

    public synchronized void save(Meeting m) {
        meetings.add(m);
    }

    /**
     * Paint a date range. A null type means "available": it clears the range.
     * Existing blocks that overlap are trimmed or split, like painting over a calendar.
     */
    public synchronized void paint(String actorId, LocalDate s, LocalDate e, BlockType type, String note) {
        List<Block> next = new ArrayList<>();
        for (Block b : blocks) {
            boolean other = !b.actorId().equals(actorId);
            if (other || b.end().isBefore(s) || b.start().isAfter(e)) {
                next.add(b);
                continue;
            }
            if (b.start().isBefore(s)) {
                next.add(new Block(nextId("B"), actorId, b.start(), s.minusDays(1), b.type(), b.note()));
            }
            if (b.end().isAfter(e)) {
                next.add(new Block(nextId("B"), actorId, e.plusDays(1), b.end(), b.type(), b.note()));
            }
        }
        if (type != null) {
            next.add(new Block(nextId("B"), actorId, s, e, type, note));
        }
        blocks.clear();
        blocks.addAll(next);
    }

    public synchronized boolean removeBlock(String actorId, String blockId) {
        return blocks.removeIf(b -> b.id().equals(blockId) && b.actorId().equals(actorId));
    }

    public synchronized void reset() {
        blocks.clear();
        meetings.clear();
        seedSchedule();
    }

    // ---------- seed data ----------

    private void seedPeople() {
        user("m1", "Rajiv Menon", Role.MANAGER, "Menon Talent Management");
        user("m2", "Sunita Rao", Role.MANAGER, "Rao & Co. Artist Management");
        user("m3", "Farah Siddiqui", Role.MANAGER, "Crescent Talent");
        user("p1", "Neha Kapoor", Role.PRODUCTION, "Lotus Frame Pictures");
        user("p2", "Imran Sheikh", Role.PRODUCTION, "Silverline Studios");
        user("p3", "Anjali Verma", Role.PRODUCTION, "Lotus Frame Pictures");

        actor("a1", "Aarav Khanna", "m1", "Mumbai", List.of("Hindi", "English"), List.of("Action", "Thriller"),
                "Lead in two action franchises. Comfortable with stunt-heavy schedules and long outdoor shoots.");
        actor("a2", "Meera Iyer", "m1", "Chennai", List.of("Tamil", "Hindi", "English"), List.of("Drama", "Romance"),
                "Known for layered dramatic roles across Tamil and Hindi cinema. Trained in Bharatanatyam.");
        actor("a3", "Kabir Malhotra", "m1", "Delhi", List.of("Hindi", "Punjabi"), List.of("Comedy", "Family"),
                "Ensemble comedies and family entertainers. Strong live-audience and improv background.");
        actor("a4", "Vikram Shetty", "m2", "Bengaluru", List.of("Hindi", "Telugu", "Kannada"), List.of("Action", "Period"),
                "Period epics and large-scale action. Horse riding and sword work.");
        actor("a5", "Tara Nair", "m2", "Kochi", List.of("Malayalam", "Hindi", "English"), List.of("Thriller", "Drama"),
                "Psychological thrillers and festival dramas. Also writes and produces short films.");
        actor("a6", "Ishita Desai", "m3", "Mumbai", List.of("Hindi", "Marathi", "English"), List.of("Romance", "Comedy"),
                "Romantic comedies and streaming series. Playback singing experience.");
        actor("a7", "Zoya Qureshi", "m3", "Hyderabad", List.of("Hindi", "Telugu", "Urdu"), List.of("Period", "Drama"),
                "Historical dramas with a focus on classical Urdu diction and costume work.");
        actor("a8", "Rohan Bedi", "m3", "Pune", List.of("Hindi", "Marathi"), List.of("Family", "Action"),
                "Supporting and second-lead roles. Reliable on tight schedules.");
    }

    private void seedSchedule() {
        LocalDate t = LocalDate.now();
        block("a1", t.plusDays(2), t.plusDays(16), BlockType.SHOOTING, "Studio schedule, Mumbai");
        block("a1", t.plusDays(30), t.plusDays(36), BlockType.VACATION, "Family trip");
        block("a2", t.plusDays(5), t.plusDays(6), BlockType.VACATION, "Personal");
        block("a2", t.plusDays(8), t.plusDays(22), BlockType.SHOOTING, "Outdoor schedule, Ooty");
        block("a2", t.plusDays(40), t.plusDays(55), BlockType.SHOOTING, "Second schedule");
        block("a3", t.plusDays(1), t.plusDays(9), BlockType.VACATION, "Away");
        block("a3", t.plusDays(20), t.plusDays(45), BlockType.SHOOTING, "Series shoot");
        block("a4", t, t.plusDays(12), BlockType.SHOOTING, "Fort set, Hyderabad");
        block("a4", t.plusDays(25), t.plusDays(33), BlockType.SHOOTING, "Night schedule");
        block("a5", t.plusDays(10), t.plusDays(17), BlockType.VACATION, "Film festival and rest");
        block("a5", t.plusDays(30), t.plusDays(60), BlockType.SHOOTING, "Feature in Kerala");
        block("a6", t.plusDays(4), t.plusDays(10), BlockType.SHOOTING, "Ad campaign");
        block("a6", t.plusDays(22), t.plusDays(28), BlockType.VACATION, "Away");
        block("a7", t.plusDays(14), t.plusDays(40), BlockType.SHOOTING, "Period drama");
        block("a8", t.plusDays(6), t.plusDays(13), BlockType.VACATION, "Away");
        block("a8", t.plusDays(18), t.plusDays(24), BlockType.SHOOTING, "Two-week shoot");

        // Meetings in different states, so every screen has something to show.
        Meeting m1 = meeting("p1", "a1", Role.PRODUCTION, "Monsoon Express",
                "We would like to discuss the lead role in our monsoon thriller. Shoot starts in January.",
                t.plusDays(18), "11:00");
        event(m1, "p1", "Requested a meeting", "", m1.date, m1.time, 26);

        Meeting m2 = meeting("p2", "a4", Role.PRODUCTION, "Dhoop Ghadi",
                "A period drama with a large ensemble. Keen to talk about the role of the commander.",
                t.plusDays(7), "15:00");
        event(m2, "p2", "Requested a meeting", "", m2.date, m2.time, 50);
        m2.date = t.plusDays(14);
        m2.time = "16:30";
        m2.status = MeetingStatus.COUNTER_PROPOSED;
        m2.awaiting = Role.PRODUCTION;
        event(m2, "m2", "Proposed a new date", "Vikram is on set until the 12th. Could we do the 14th?", m2.date, m2.time, 30);

        Meeting m3 = meeting("p1", "a2", Role.PRODUCTION, "Saath Phere 2",
                "Script reading for the second lead.", t.plusDays(3), "10:30");
        event(m3, "p1", "Requested a meeting", "", m3.date, m3.time, 80);
        m3.status = MeetingStatus.ACCEPTED;
        m3.awaiting = null;
        event(m3, "m1", "Accepted", "See you then.", m3.date, m3.time, 70);

        List<Block> shared = new ArrayList<>();
        for (Block b : blocksFor("a6")) shared.add(b);
        Meeting m4 = new Meeting(nextId("M"), "a6", "p2", Role.MANAGER, "February schedule opening",
                "Ishita has a gap in February and is looking at new scripts. Her availability is attached.",
                t.plusDays(9), "12:00", shared);
        meetings.add(m4);
        event(m4, "m3", "Requested a meeting", "", m4.date, m4.time, 5);

        Meeting m5 = meeting("p2", "a8", Role.PRODUCTION, "Gully Cricket",
                "Supporting role in a family sports film.", t.plusDays(2), "17:00");
        event(m5, "p2", "Requested a meeting", "", m5.date, m5.time, 120);
        m5.status = MeetingStatus.DECLINED;
        m5.awaiting = null;
        event(m5, "m3", "Declined", "Rohan is committed to another project through March.", m5.date, m5.time, 100);
    }

    // ---------- seed helpers ----------

    private void user(String id, String name, Role role, String org) {
        String email = name.toLowerCase().replace(" ", ".") + "@example.com";
        users.put(id, new User(id, name, role, org, email));
    }

    private void actor(String id, String name, String managerId, String city,
                       List<String> langs, List<String> genres, String bio) {
        actors.put(id, new Actor(id, name, managerId, langs, genres, city, bio));
    }

    private void block(String actorId, LocalDate s, LocalDate e, BlockType type, String note) {
        blocks.add(new Block(nextId("B"), actorId, s, e, type, note));
    }

    private Meeting meeting(String prodId, String actorId, Role by, String title, String msg,
                            LocalDate date, String time) {
        Meeting m = new Meeting(nextId("M"), actorId, prodId, by, title, msg, date, time, List.of());
        meetings.add(m);
        return m;
    }

    private void event(Meeting m, String userId, String action, String comment,
                       LocalDate date, String time, int hoursAgo) {
        User u = users.get(userId);
        m.history.add(new Event(Instant.now().minus(hoursAgo, ChronoUnit.HOURS),
                u.name(), u.role().name(), action, comment, date, time));
    }
}
