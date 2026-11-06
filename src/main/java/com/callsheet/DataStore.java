package com.callsheet;

import com.callsheet.Models.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stand-in for the database. Every person, film title and credit here is fictional.
 * Dates are seeded relative to "today" so the demo always looks current.
 */
@Component
public class DataStore {

    public final Map<String, User> users = new LinkedHashMap<>();
    public final Map<String, Actor> actors = new LinkedHashMap<>();
    public final Map<String, User> sessions = new ConcurrentHashMap<>();

    private final Map<String, ResumeFile> resumes = new ConcurrentHashMap<>();
    private final List<Block> blocks = new ArrayList<>();
    private final List<Meeting> meetings = new ArrayList<>();
    private int seq = 100;

    /** Actors who start the demo without a resume, so the "no resume yet" state is visible. */
    private static final Set<String> NO_RESUME = Set.of("a3", "a8", "a12", "a16");

    public DataStore() {
        seedPeople();
        seedSchedule();
        seedResumes();
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

    public ResumeFile resume(String actorId) {
        return resumes.get(actorId);
    }

    // ---------- writes ----------

    public synchronized String nextId(String prefix) {
        return prefix + "-" + (seq++);
    }

    public synchronized void save(Meeting m) {
        meetings.add(m);
    }

    public void putResume(String actorId, ResumeFile file) {
        resumes.put(actorId, file);
    }

    public boolean removeResume(String actorId) {
        return resumes.remove(actorId) != null;
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
        seedResumes();
    }

    // ---------- seed data: people ----------

    private void seedPeople() {
        user("m1", "Rajiv Menon", Role.MANAGER, "Menon Talent Management");
        user("m2", "Sunita Rao", Role.MANAGER, "Rao & Co. Artist Management");
        user("m3", "Farah Siddiqui", Role.MANAGER, "Crescent Talent");
        user("m4", "Prakash Subramanian", Role.MANAGER, "Southern Lights Talent");
        user("m5", "Debjani Roy", Role.MANAGER, "East West Artists");
        user("p1", "Neha Kapoor", Role.PRODUCTION, "Lotus Frame Pictures");
        user("p2", "Imran Sheikh", Role.PRODUCTION, "Silverline Studios");
        user("p3", "Anjali Verma", Role.PRODUCTION, "Lotus Frame Pictures");

        // ----- Hindi cinema (Mumbai and Delhi) -----
        actor("a1", "Aarav Khanna", "m1", "Hindi cinema", "Mumbai",
                List.of("Hindi", "English"), List.of("Action", "Thriller"), "28 to 38", "6 ft 0 in",
                "Leads action thrillers and has carried two franchise films. Comfortable with stunt-heavy schedules and long outdoor shoots.",
                List.of("Stunt work", "Krav Maga", "Swimming", "Motorcycle riding"),
                List.of("Acting diploma, Mumbai (2012)", "Action choreography workshop with a stunt co-ordinator (2016)"),
                List.of(c(2019, "Red Corridor", "Lead", "Feature, Hindi"), c(2021, "Seventh Signal", "Lead", "Feature, Hindi"),
                        c(2023, "Night Shift Mumbai", "Inspector Rane", "Web series, Hindi"), c(2025, "Iron Harbour", "Lead", "Feature, Hindi")),
                List.of("Best Action Performance, regional film awards (2023)"));
        actor("a3", "Kabir Malhotra", "m1", "Hindi and Punjabi cinema", "Delhi",
                List.of("Hindi", "Punjabi"), List.of("Comedy", "Family"), "30 to 40", "5 ft 10 in",
                "Ensemble comedies and family entertainers. Strong live-audience and improv background from stand-up and theatre.",
                List.of("Improvisation", "Stand-up", "Mimicry", "Dhol basics"),
                List.of("Improv ensemble, Delhi (2011 to 2015)"),
                List.of(c(2017, "Shaadi Season", "Bunty", "Feature, Hindi"), c(2019, "Pind Wale", "Lead", "Feature, Punjabi"),
                        c(2022, "Office Office Everywhere", "Ensemble", "Web series, Hindi"), c(2024, "Family Package", "Chacha", "Feature, Hindi")),
                List.of());
        actor("a6", "Ishita Desai", "m3", "Hindi and Marathi cinema", "Mumbai",
                List.of("Hindi", "Marathi", "English"), List.of("Romance", "Comedy"), "22 to 30", "5 ft 4 in",
                "Romantic comedies and streaming series. Trained Kathak dancer with playback singing experience.",
                List.of("Kathak", "Playback singing", "Light comedy", "Horse riding basics"),
                List.of("Kathak training, 10 years", "Screen acting workshop, Mumbai (2018)"),
                List.of(c(2020, "Chai at Marine Drive", "Lead", "Feature, Hindi"), c(2022, "Swipe Right Society", "Lead", "Web series, Hindi"),
                        c(2023, "Sunday Special", "Lead", "Feature, Marathi")),
                List.of("Debut of the Year nomination, streaming awards (2021)"));
        actor("a8", "Rohan Bedi", "m3", "Hindi and Marathi cinema", "Pune",
                List.of("Hindi", "Marathi"), List.of("Family", "Action"), "25 to 35", "5 ft 11 in",
                "Dependable supporting and second-lead actor who works well on tight schedules. Played cricket at state level.",
                List.of("Cricket", "Mimicry", "Driving (car and two-wheeler)"),
                List.of("Theatre group, Pune (2013 to 2017)"),
                List.of(c(2019, "Maidan Ka Sher", "Coach's son", "Feature, Hindi"), c(2021, "Last Over", "Best friend", "Feature, Marathi"),
                        c(2023, "Ghar Wapsi", "Younger brother", "Web series, Hindi"), c(2025, "Home Run", "Second lead", "Feature, Hindi")),
                List.of());
        actor("a16", "Sanjana Rawat", "m5", "Hindi cinema and streaming", "Mumbai",
                List.of("Hindi", "English", "Garhwali"), List.of("Drama", "Thriller"), "25 to 33", "5 ft 5 in",
                "Web series and independent film actor who also works in voice-over. Grew up in Uttarakhand and is a trained trekker.",
                List.of("Voice-over", "Mountaineering", "Courtroom and legal dialogue"),
                List.of("Acting course, Mumbai (2017)", "Voice and diction coaching (2019)"),
                List.of(c(2021, "The Hill Station", "Lead", "Web series, Hindi"), c(2022, "Courtroom Nine", "Junior counsel", "Web series, Hindi"),
                        c(2024, "Paper Trail", "Lead", "Feature, Hindi")),
                List.of());

        // ----- Tamil cinema (Chennai) -----
        actor("a2", "Meera Iyer", "m1", "Tamil and Hindi cinema", "Chennai",
                List.of("Tamil", "Hindi", "English"), List.of("Drama", "Romance"), "26 to 34", "5 ft 6 in",
                "Known for layered dramatic roles across Tamil and Hindi cinema. Trained Bharatanatyam dancer and Carnatic vocalist.",
                List.of("Bharatanatyam", "Carnatic vocals", "Period dialogue"),
                List.of("Bharatanatyam since age 7", "Acting workshops, Chennai (2014 to 2016)"),
                List.of(c(2018, "Kaveri Nights", "Lead", "Feature, Tamil"), c(2020, "Thaalam", "Meenakshi", "Feature, Tamil"),
                        c(2022, "Paper Boats", "Lead", "Feature, Hindi"), c(2024, "The Salt Road", "Anandi", "Web series, Tamil")),
                List.of("Best Actress nomination, regional film festival (2021)"));
        actor("a9", "Arjun Venkataraman", "m4", "Tamil cinema", "Chennai",
                List.of("Tamil", "English", "Telugu"), List.of("Action", "Drama"), "30 to 42", "5 ft 11 in",
                "Rooted in rural and small-town stories. Trained in silambam and comfortable with physically demanding roles.",
                List.of("Silambam", "Motorcycle riding", "Rural dialects"),
                List.of("Street theatre troupe, Madurai (2009 to 2013)", "Silambam training, 6 years"),
                List.of(c(2017, "Madurai Express", "Lead", "Feature, Tamil"), c(2019, "Karuppu Naal", "Brother", "Feature, Tamil"),
                        c(2022, "Sea of Salt", "Inspector", "Feature, Tamil"), c(2024, "Thee Nizhal", "Lead", "Feature, Tamil")),
                List.of("Best Supporting Actor, regional awards (2020)"));

        // ----- Telugu cinema (Hyderabad) -----
        actor("a4", "Vikram Shetty", "m2", "Hindi and Telugu cinema", "Bengaluru",
                List.of("Hindi", "Telugu", "Kannada"), List.of("Action", "Period"), "32 to 45", "6 ft 1 in",
                "Period epics and large-scale action. Experienced with horse riding, sword work and heavy costume.",
                List.of("Horse riding", "Swordsmanship", "Kalaripayattu", "Period costume"),
                List.of("Kalaripayattu, 8 years", "Mounted combat workshop (2017)"),
                List.of(c(2018, "The Last Rampart", "Commander", "Feature, Hindi"), c(2020, "Raja Simhan", "Lead", "Feature, Telugu"),
                        c(2023, "Banner of Ashes", "General", "Feature, Hindi"), c(2025, "Fort of Smoke", "Lead", "Web series, Hindi")),
                List.of("Best Villain nomination, South film awards (2021)"));
        actor("a7", "Zoya Qureshi", "m3", "Hindi and Telugu cinema", "Hyderabad",
                List.of("Hindi", "Telugu", "Urdu"), List.of("Period", "Drama"), "28 to 40", "5 ft 7 in",
                "Historical dramas with a focus on classical Urdu diction, costume work and courtly manner.",
                List.of("Urdu diction", "Kathak", "Period costume", "Poetry recitation"),
                List.of("Urdu theatre, Hyderabad (2010 to 2014)", "Kathak training, 7 years"),
                List.of(c(2018, "Nawab Saheb's Daughter", "Lead", "Feature, Hindi"), c(2020, "Chandni Mahal", "Begum", "Feature, Urdu"),
                        c(2022, "The Deccan Letters", "Lead", "Feature, Telugu"), c(2024, "Saffron Court", "Queen mother", "Web series, Hindi")),
                List.of("Best Supporting Actress, regional awards (2019)"));
        actor("a10", "Pranav Rayudu", "m4", "Telugu cinema", "Hyderabad",
                List.of("Telugu", "Hindi", "English"), List.of("Romance", "Action"), "24 to 34", "5 ft 10 in",
                "Young leading man with a strong dance background. Regular in romantic dramas and mass entertainers.",
                List.of("Dance (contemporary and folk)", "Martial arts", "Singing"),
                List.of("Dance academy, Hyderabad (2010 to 2016)", "Screen acting workshop (2017)"),
                List.of(c(2019, "Godavari Days", "Lead", "Feature, Telugu"), c(2021, "Ring Road", "Lead", "Feature, Telugu"),
                        c(2023, "Blue Hour", "Lead", "Feature, Telugu"), c(2025, "Bhagya Nagar Beats", "Lead", "Web series, Telugu")),
                List.of("Youth Icon of the Year, regional awards (2022)"));

        // ----- Malayalam cinema (Kochi) -----
        actor("a5", "Tara Nair", "m2", "Malayalam and Hindi cinema", "Kochi",
                List.of("Malayalam", "Hindi", "English"), List.of("Thriller", "Drama"), "27 to 36", "5 ft 5 in",
                "Psychological thrillers and festival dramas. Also writes and produces short films.",
                List.of("Screenwriting", "Scuba diving", "Short-film producing"),
                List.of("Theatre company, Thrissur (2012 to 2016)"),
                List.of(c(2019, "Monsoon Letters", "Lead", "Feature, Malayalam"), c(2021, "Glass House", "Dr. Anitha", "Feature, Malayalam"),
                        c(2023, "Still Water", "Lead", "Feature, Hindi"), c(2025, "The Quiet Hours", "Lead", "Feature, Malayalam")),
                List.of("Critics' award for Best Actress, regional festival (2021)"));
        actor("a11", "Devika Warrier", "m4", "Malayalam cinema", "Kochi",
                List.of("Malayalam", "English", "Tamil"), List.of("Drama", "Family"), "25 to 35", "5 ft 5 in",
                "Grounded, naturalistic performances in family dramas. Trained Mohiniyattam dancer.",
                List.of("Mohiniyattam", "Classical vocals", "Natural-light performance"),
                List.of("Mohiniyattam, 12 years", "Acting workshop, Kochi (2015)"),
                List.of(c(2018, "Onam Rain", "Lead", "Feature, Malayalam"), c(2020, "Backwater House", "Maya", "Feature, Malayalam"),
                        c(2022, "Salt and Jaggery", "Lead", "Feature, Malayalam"), c(2024, "Night Boat", "Lead", "Feature, Malayalam")),
                List.of("Best New Face, regional awards (2019)"));

        // ----- Kannada cinema (Bengaluru) -----
        actor("a12", "Chinmay Gowda", "m4", "Kannada cinema", "Bengaluru",
                List.of("Kannada", "Hindi", "Tulu"), List.of("Drama", "Thriller"), "27 to 38", "5 ft 9 in",
                "Stage-trained character actor. Equally comfortable as a lead and as a menacing antagonist.",
                List.of("Stage acting", "Voice modulation", "Yakshagana basics"),
                List.of("Repertory theatre, Bengaluru (2010 to 2016)"),
                List.of(c(2018, "Malenaadu", "Lead", "Feature, Kannada"), c(2020, "Hosa Belaku", "Teacher", "Feature, Kannada"),
                        c(2022, "Dharwad Diaries", "Lead", "Feature, Kannada"), c(2024, "Coffee Estate", "Antagonist", "Feature, Kannada")),
                List.of("Best Actor, state theatre festival (2017)"));

        // ----- Bengali cinema (Kolkata) -----
        actor("a13", "Ritwika Chatterjee", "m5", "Bengali cinema", "Kolkata",
                List.of("Bengali", "Hindi", "English"), List.of("Drama", "Romance"), "24 to 34", "5 ft 4 in",
                "Literary adaptations and intimate urban dramas. Trained in Rabindra Sangeet and Bengali theatre.",
                List.of("Rabindra Sangeet", "Bengali stage", "Literary dialogue"),
                List.of("Group theatre, Kolkata (2012 to 2017)"),
                List.of(c(2018, "Hooghly Light", "Lead", "Feature, Bengali"), c(2020, "Rainy Tram", "Lead", "Feature, Bengali"),
                        c(2022, "The Last Adda", "Lead", "Feature, Bengali"), c(2024, "Calcutta Mail", "Lead", "Web series, Hindi")),
                List.of("Critics' Choice, regional film festival (2020)"));

        // ----- Marathi cinema (Mumbai) -----
        actor("a14", "Mugdha Phadke", "m5", "Marathi and Hindi cinema", "Mumbai",
                List.of("Marathi", "Hindi", "English"), List.of("Drama", "Comedy"), "26 to 38", "5 ft 6 in",
                "Marathi stage and screen actor with a strong folk-dance background. Moves easily between comedy and heavy drama.",
                List.of("Lavani", "Marathi theatre", "Comic timing"),
                List.of("Lavani training, 9 years", "Experimental theatre, Mumbai (2012 to 2018)"),
                List.of(c(2018, "Kokan Diary", "Lead", "Feature, Marathi"), c(2020, "Lavani Lane", "Lead", "Feature, Marathi"),
                        c(2022, "Pandharichi Wari", "Mother", "Feature, Marathi"), c(2024, "Platform Nine", "Lead", "Web series, Hindi")),
                List.of("Best Actress, Marathi film awards (2021)"));

        // ----- Punjabi cinema (Chandigarh) -----
        actor("a15", "Harleen Bajwa", "m5", "Punjabi cinema", "Chandigarh",
                List.of("Punjabi", "Hindi", "English"), List.of("Romance", "Comedy"), "23 to 32", "5 ft 6 in",
                "Bright, high-energy lead in Punjabi romances and comedies. Trained in bhangra and giddha.",
                List.of("Bhangra", "Giddha", "Tractor driving", "Singing"),
                List.of("Folk dance troupe, Patiala (2011 to 2016)"),
                List.of(c(2019, "Mitti Di Khushbu", "Lead", "Feature, Punjabi"), c(2021, "Tractor Wali", "Lead", "Feature, Punjabi"),
                        c(2023, "Jugni Express", "Lead", "Feature, Punjabi"), c(2025, "Ludhiana Lights", "Lead", "Web series, Hindi")),
                List.of("Best Debut, Punjabi film awards (2020)"));
    }

    // ---------- seed data: schedule and meetings ----------

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
        block("a9", t.plusDays(3), t.plusDays(25), BlockType.SHOOTING, "Chennai studio block");
        block("a9", t.plusDays(40), t.plusDays(44), BlockType.VACATION, "Temple visit");
        block("a10", t.plusDays(1), t.plusDays(18), BlockType.SHOOTING, "Song schedule");
        block("a10", t.plusDays(30), t.plusDays(50), BlockType.SHOOTING, "Second unit");
        block("a11", t.plusDays(7), t.plusDays(14), BlockType.VACATION, "Family");
        block("a11", t.plusDays(21), t.plusDays(35), BlockType.SHOOTING, "Backwater location");
        block("a12", t.plusDays(10), t.plusDays(20), BlockType.SHOOTING, "Estate schedule");
        block("a13", t, t.plusDays(8), BlockType.SHOOTING, "Kolkata studio");
        block("a13", t.plusDays(26), t.plusDays(32), BlockType.VACATION, "Away");
        block("a14", t.plusDays(12), t.plusDays(27), BlockType.SHOOTING, "Kokan schedule");
        block("a15", t.plusDays(2), t.plusDays(6), BlockType.VACATION, "Away");
        block("a15", t.plusDays(15), t.plusDays(30), BlockType.SHOOTING, "Village set");
        block("a16", t.plusDays(9), t.plusDays(16), BlockType.SHOOTING, "Series block");
        block("a16", t.plusDays(35), t.plusDays(48), BlockType.SHOOTING, "Feature shoot");

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

        List<Block> shared = new ArrayList<>(blocksFor("a6"));
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

    private void seedResumes() {
        resumes.clear();
        for (Actor a : actors.values()) {
            if (NO_RESUME.contains(a.id())) continue;
            User m = users.get(a.managerId());
            resumes.put(a.id(), new ResumeFile(
                    a.name().replace(' ', '_') + "_Acting_Resume.pdf",
                    ResumePdf.build(a, m.name(), m.org()),
                    Instant.now().minus(3, ChronoUnit.DAYS), m.name()));
        }
    }

    // ---------- seed helpers ----------

    private void user(String id, String name, Role role, String org) {
        String email = name.toLowerCase().replace(" ", ".") + "@example.com";
        users.put(id, new User(id, name, role, org, email));
    }

    private void actor(String id, String name, String managerId, String industry, String city,
                       List<String> langs, List<String> genres, String playingAge, String height,
                       String bio, List<String> skills, List<String> training,
                       List<Credit> credits, List<String> awards) {
        actors.put(id, new Actor(id, name, managerId, industry, langs, genres, city, bio,
                playingAge, height, skills, training, credits, awards));
    }

    private static Credit c(int year, String title, String role, String type) {
        return new Credit(year, title, role, type);
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
