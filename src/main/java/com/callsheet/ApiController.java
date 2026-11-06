package com.callsheet;

import com.callsheet.Models.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * REST API. Authorisation rules live here, on the server, so hiding a button in the UI
 * is never the only thing protecting data:
 *  - a manager can only read or change their own actors
 *  - production houses see BUSY / free, never the reason (shooting, vacation, notes)
 *  - only the side a meeting is waiting on can accept, decline or counter it
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private static final String AUTH = "Authorization";
    private static final long MAX_RESUME_BYTES = 2L * 1024 * 1024;
    private final DataStore db;

    public ApiController(DataStore db) {
        this.db = db;
    }

    // ---------- request / response shapes ----------

    public record LoginRequest(String userId) {}
    public record AvailabilityRequest(LocalDate start, LocalDate end, String type, String note) {}
    public record CreateMeeting(String actorId, String projectTitle, String message,
                                LocalDate date, String time, String productionUserId) {}
    public record ResponseRequest(String comment) {}
    public record CounterRequest(LocalDate date, String time, String comment) {}

    public record BlockView(String id, LocalDate start, LocalDate end, String type, String note) {}
    public record ResumeInfo(String filename, long sizeBytes, Instant uploadedAt, String uploadedBy) {}
    public record ActorView(String id, String name, String industry, String city, List<String> languages,
                            List<String> genres, String bio, String playingAge, String height,
                            List<String> skills, List<String> training, List<Credit> credits,
                            List<String> awards, String managerName, ResumeInfo resume,
                            List<BlockView> blocks) {}
    public record MeetingView(String id, String actorId, String actorName, String managerName,
                              String productionUserName, String productionOrg, String projectTitle,
                              String message, String initiatedBy, String awaiting, String status,
                              LocalDate date, String time, boolean dateConflict,
                              boolean canRespond, boolean canCancel,
                              List<Event> history, List<BlockView> sharedAvailability) {}

    // ---------- session ----------

    @GetMapping("/demo-users")
    public Collection<User> demoUsers() {
        return db.users.values();
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest req) {
        User u = req.userId() == null ? null : db.users.get(req.userId());
        if (u == null) throw status(HttpStatus.UNAUTHORIZED, "Unknown demo account");
        String token = UUID.randomUUID().toString();
        db.sessions.put(token, u);
        return Map.of("token", token, "user", u);
    }

    @GetMapping("/production-users")
    public List<User> productionUsers(@RequestHeader(value = AUTH, required = false) String auth) {
        auth(auth);
        return db.users.values().stream().filter(u -> u.role() == Role.PRODUCTION).toList();
    }

    @PostMapping("/demo/reset")
    public Map<String, String> reset() {
        db.reset();
        return Map.of("status", "reset");
    }

    // ---------- actors and availability ----------

    @GetMapping("/actors")
    public List<ActorView> actors(
            @RequestHeader(value = AUTH, required = false) String auth,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "language", required = false) String language,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        User u = auth(auth);
        boolean mask = u.role() == Role.PRODUCTION;
        LocalDate f = from;
        LocalDate t = to != null ? to : from;
        if (f != null && t.isBefore(f)) throw status(HttpStatus.BAD_REQUEST, "The end date is before the start date");

        return db.actors.values().stream()
                .filter(a -> mask || a.managerId().equals(u.id()))
                .filter(a -> q == null || q.isBlank() || matches(a, q.trim().toLowerCase()))
                .filter(a -> language == null || language.isBlank()
                        || a.languages().stream().anyMatch(l -> l.equalsIgnoreCase(language)))
                .filter(a -> f == null || db.isFree(a.id(), f, t))
                .map(a -> actorView(a, mask))
                .toList();
    }

    @GetMapping("/actors/{id}")
    public ActorView actor(@RequestHeader(value = AUTH, required = false) String auth,
                           @PathVariable("id") String id) {
        User u = auth(auth);
        Actor a = actorFor(u, id, false);
        return actorView(a, u.role() == Role.PRODUCTION);
    }

    @PostMapping("/actors/{id}/availability")
    public synchronized ActorView paint(@RequestHeader(value = AUTH, required = false) String auth,
                                        @PathVariable("id") String id,
                                        @RequestBody AvailabilityRequest r) {
        User u = auth(auth);
        Actor a = actorFor(u, id, true);
        if (r.start() == null || r.end() == null) throw status(HttpStatus.BAD_REQUEST, "Choose a start and end date");
        if (r.end().isBefore(r.start())) throw status(HttpStatus.BAD_REQUEST, "The end date is before the start date");
        if (r.end().isAfter(r.start().plusDays(365))) throw status(HttpStatus.BAD_REQUEST, "Ranges are limited to one year");

        String t = r.type() == null ? "" : r.type().toUpperCase();
        BlockType type = switch (t) {
            case "SHOOTING" -> BlockType.SHOOTING;
            case "VACATION" -> BlockType.VACATION;
            case "AVAILABLE" -> null;
            default -> throw status(HttpStatus.BAD_REQUEST, "Status must be shooting, vacation or available");
        };
        String note = r.note() == null ? "" : r.note().trim();
        db.paint(a.id(), r.start(), r.end(), type, note.length() > 120 ? note.substring(0, 120) : note);
        return actorView(a, false);
    }

    @DeleteMapping("/actors/{id}/availability/{blockId}")
    public synchronized ActorView removeBlock(@RequestHeader(value = AUTH, required = false) String auth,
                                              @PathVariable("id") String id,
                                              @PathVariable("blockId") String blockId) {
        User u = auth(auth);
        Actor a = actorFor(u, id, true);
        if (!db.removeBlock(a.id(), blockId)) throw status(HttpStatus.NOT_FOUND, "That entry no longer exists");
        return actorView(a, false);
    }

    // ---------- resumes ----------

    /** Upload or replace the actor's acting resume. Managers only, own actors only, PDF only, 2 MB max. */
    @PostMapping("/actors/{id}/resume")
    public synchronized ActorView uploadResume(@RequestHeader(value = AUTH, required = false) String auth,
                                               @PathVariable("id") String id,
                                               @RequestParam("file") MultipartFile file) throws IOException {
        User u = auth(auth);
        Actor a = actorFor(u, id, true);
        if (file == null || file.isEmpty()) throw status(HttpStatus.BAD_REQUEST, "Choose a PDF file to upload");
        if (file.getSize() > MAX_RESUME_BYTES) throw status(HttpStatus.BAD_REQUEST, "The file is larger than 2 MB");
        byte[] data = file.getBytes();
        boolean pdf = data.length > 5 && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F' && data[4] == '-';
        if (!pdf) throw status(HttpStatus.BAD_REQUEST, "Only PDF files are accepted");
        db.putResume(a.id(), new ResumeFile(cleanName(file.getOriginalFilename()), data, Instant.now(), u.name()));
        return actorView(a, false);
    }

    /** Production houses and the actor's own manager can read the resume. */
    @GetMapping("/actors/{id}/resume")
    public ResponseEntity<byte[]> resume(@RequestHeader(value = AUTH, required = false) String auth,
                                         @PathVariable("id") String id) {
        User u = auth(auth);
        Actor a = actorFor(u, id, false);
        ResumeFile f = db.resume(a.id());
        if (f == null) throw status(HttpStatus.NOT_FOUND, "No resume has been uploaded for this actor yet");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + f.filename() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "no-store")
                .contentType(MediaType.APPLICATION_PDF)
                .body(f.data());
    }

    @DeleteMapping("/actors/{id}/resume")
    public synchronized ActorView removeResume(@RequestHeader(value = AUTH, required = false) String auth,
                                               @PathVariable("id") String id) {
        User u = auth(auth);
        Actor a = actorFor(u, id, true);
        if (!db.removeResume(a.id())) throw status(HttpStatus.NOT_FOUND, "There is no resume to remove");
        return actorView(a, false);
    }

    // ---------- meetings ----------

    @GetMapping("/meetings")
    public List<MeetingView> meetings(@RequestHeader(value = AUTH, required = false) String auth) {
        User u = auth(auth);
        return db.meetings().stream()
                .filter(m -> visible(u, m))
                .sorted(Comparator.comparing(Meeting::lastActivity).reversed())
                .map(m -> view(m, u))
                .toList();
    }

    @PostMapping("/meetings")
    public synchronized MeetingView create(@RequestHeader(value = AUTH, required = false) String auth,
                                           @RequestBody CreateMeeting r) {
        User u = auth(auth);
        if (r.projectTitle() == null || r.projectTitle().isBlank()) throw status(HttpStatus.BAD_REQUEST, "Add a project title");
        if (r.date() == null || r.date().isBefore(LocalDate.now().minusDays(1))) {
            throw status(HttpStatus.BAD_REQUEST, "Pick a date that is today or later");
        }
        String time = normTime(r.time());
        if (r.actorId() == null) throw status(HttpStatus.BAD_REQUEST, "Choose an actor");
        Actor a = actorFor(u, r.actorId(), false);

        String prodId;
        List<Block> snapshot = List.of();
        if (u.role() == Role.PRODUCTION) {
            prodId = u.id();
        } else {
            User p = r.productionUserId() == null ? null : db.users.get(r.productionUserId());
            if (p == null || p.role() != Role.PRODUCTION) {
                throw status(HttpStatus.BAD_REQUEST, "Choose who at the production house should receive this");
            }
            prodId = p.id();
            LocalDate today = LocalDate.now();
            snapshot = db.blocksFor(a.id()).stream()
                    .filter(b -> !b.end().isBefore(today) && !b.start().isAfter(today.plusDays(60)))
                    .toList();
        }

        Meeting m = new Meeting(db.nextId("M"), a.id(), prodId, u.role(), r.projectTitle().trim(),
                r.message() == null ? "" : r.message().trim(), r.date(), time, snapshot);
        m.history.add(ev(u, "Requested a meeting", "", r.date(), time));
        db.save(m);
        return view(m, u);
    }

    @PostMapping("/meetings/{id}/accept")
    public synchronized MeetingView accept(@RequestHeader(value = AUTH, required = false) String auth,
                                           @PathVariable("id") String id,
                                           @RequestBody(required = false) ResponseRequest body) {
        User u = auth(auth);
        Meeting m = respondable(u, id);
        m.status = MeetingStatus.ACCEPTED;
        m.awaiting = null;
        m.history.add(ev(u, "Accepted", comment(body), m.date, m.time));
        return view(m, u);
    }

    @PostMapping("/meetings/{id}/decline")
    public synchronized MeetingView decline(@RequestHeader(value = AUTH, required = false) String auth,
                                            @PathVariable("id") String id,
                                            @RequestBody(required = false) ResponseRequest body) {
        User u = auth(auth);
        Meeting m = respondable(u, id);
        m.status = MeetingStatus.DECLINED;
        m.awaiting = null;
        m.history.add(ev(u, "Declined", comment(body), m.date, m.time));
        return view(m, u);
    }

    @PostMapping("/meetings/{id}/counter")
    public synchronized MeetingView counter(@RequestHeader(value = AUTH, required = false) String auth,
                                            @PathVariable("id") String id,
                                            @RequestBody CounterRequest r) {
        User u = auth(auth);
        Meeting m = respondable(u, id);
        if (r.date() == null || r.date().isBefore(LocalDate.now().minusDays(1))) {
            throw status(HttpStatus.BAD_REQUEST, "Pick a date that is today or later");
        }
        m.date = r.date();
        m.time = normTime(r.time());
        m.status = MeetingStatus.COUNTER_PROPOSED;
        m.awaiting = u.role() == Role.MANAGER ? Role.PRODUCTION : Role.MANAGER;
        m.history.add(ev(u, "Proposed a new date", r.comment() == null ? "" : r.comment().trim(), m.date, m.time));
        return view(m, u);
    }

    @PostMapping("/meetings/{id}/cancel")
    public synchronized MeetingView cancel(@RequestHeader(value = AUTH, required = false) String auth,
                                           @PathVariable("id") String id,
                                           @RequestBody(required = false) ResponseRequest body) {
        User u = auth(auth);
        Meeting m = visibleMeeting(u, id);
        if (m.status == MeetingStatus.DECLINED || m.status == MeetingStatus.CANCELLED) {
            throw status(HttpStatus.CONFLICT, "This meeting is already closed");
        }
        m.status = MeetingStatus.CANCELLED;
        m.awaiting = null;
        m.history.add(ev(u, "Cancelled", comment(body), m.date, m.time));
        return view(m, u);
    }

    /** Calendar invite for a confirmed meeting. Open the file in Outlook, Google Calendar or Apple Calendar. */
    @GetMapping("/meetings/{id}/invite.ics")
    public ResponseEntity<String> invite(@RequestHeader(value = AUTH, required = false) String auth,
                                         @PathVariable("id") String id) {
        User u = auth(auth);
        Meeting m = visibleMeeting(u, id);
        if (m.status != MeetingStatus.ACCEPTED) {
            throw status(HttpStatus.CONFLICT, "An invite is available once the meeting is accepted");
        }
        Actor a = db.actors.get(m.actorId);
        User prod = db.users.get(m.productionUserId);
        LocalTime start = LocalTime.parse(m.time);
        DateTimeFormatter dt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(Instant.now().atZone(ZoneOffset.UTC));
        String desc = "Actor: " + a.name() + "\\nProduction: " + prod.org() + " (" + prod.name() + ")"
                + (m.message.isBlank() ? "" : "\\n\\n" + ics(m.message));
        String body = String.join("\r\n",
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//CallSheet//Demo//EN",
                "BEGIN:VEVENT",
                "UID:" + m.id + "@callsheet.demo",
                "DTSTAMP:" + stamp,
                "DTSTART;TZID=Asia/Kolkata:" + dt.format(m.date.atTime(start)),
                "DTEND;TZID=Asia/Kolkata:" + dt.format(m.date.atTime(start.plusHours(1))),
                "SUMMARY:" + ics("Meeting: " + m.projectTitle + " with " + a.name()),
                "DESCRIPTION:" + desc,
                "END:VEVENT",
                "END:VCALENDAR",
                "");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + m.id + ".ics\"")
                .contentType(MediaType.parseMediaType("text/calendar; charset=utf-8"))
                .body(body);
    }

    // ---------- helpers ----------

    private User auth(String header) {
        if (header == null || !header.startsWith("Bearer ")) throw status(HttpStatus.UNAUTHORIZED, "Please sign in");
        User u = db.sessions.get(header.substring(7));
        if (u == null) throw status(HttpStatus.UNAUTHORIZED, "Your session has ended. Please sign in again");
        return u;
    }

    private Actor actorFor(User u, String id, boolean write) {
        Actor a = db.actors.get(id);
        if (a == null) throw status(HttpStatus.NOT_FOUND, "Actor not found");
        if (u.role() == Role.MANAGER && !a.managerId().equals(u.id())) {
            throw status(HttpStatus.FORBIDDEN, "You can only access actors you manage");
        }
        if (write && u.role() != Role.MANAGER) {
            throw status(HttpStatus.FORBIDDEN, "Only the actor's manager can change availability");
        }
        return a;
    }

    private boolean visible(User u, Meeting m) {
        if (u.role() == Role.MANAGER) {
            return db.actors.get(m.actorId).managerId().equals(u.id());
        }
        return db.users.get(m.productionUserId).org().equals(u.org());
    }

    private Meeting visibleMeeting(User u, String id) {
        Meeting m = db.meeting(id);
        if (m == null || !visible(u, m)) throw status(HttpStatus.NOT_FOUND, "Meeting not found");
        return m;
    }

    private Meeting respondable(User u, String id) {
        Meeting m = visibleMeeting(u, id);
        boolean open = m.status == MeetingStatus.PENDING || m.status == MeetingStatus.COUNTER_PROPOSED;
        if (!open) throw status(HttpStatus.CONFLICT, "This meeting is already closed");
        if (u.role() != m.awaiting) throw status(HttpStatus.FORBIDDEN, "This is waiting on the other side to respond");
        return m;
    }

    private boolean matches(Actor a, String q) {
        return a.name().toLowerCase().contains(q)
                || a.city().toLowerCase().contains(q)
                || a.industry().toLowerCase().contains(q)
                || a.languages().stream().anyMatch(l -> l.toLowerCase().contains(q))
                || a.genres().stream().anyMatch(g -> g.toLowerCase().contains(q));
    }

    private ActorView actorView(Actor a, boolean mask) {
        LocalDate from = LocalDate.now().minusDays(30);
        LocalDate to = LocalDate.now().plusDays(240);
        List<BlockView> blocks = db.blocksFor(a.id()).stream()
                .filter(b -> !b.end().isBefore(from) && !b.start().isAfter(to))
                .map(b -> blockView(b, mask))
                .toList();
        ResumeFile rf = db.resume(a.id());
        ResumeInfo info = rf == null ? null : new ResumeInfo(rf.filename(), rf.data().length, rf.uploadedAt(), rf.uploadedBy());
        return new ActorView(a.id(), a.name(), a.industry(), a.city(), a.languages(), a.genres(), a.bio(),
                a.playingAge(), a.height(), a.skills(), a.training(), a.credits(), a.awards(),
                db.users.get(a.managerId()).name(), info, blocks);
    }

    private BlockView blockView(Block b, boolean mask) {
        return mask ? new BlockView(b.id(), b.start(), b.end(), "BUSY", null)
                    : new BlockView(b.id(), b.start(), b.end(), b.type().name(), b.note());
    }

    private MeetingView view(Meeting m, User viewer) {
        Actor a = db.actors.get(m.actorId);
        User prod = db.users.get(m.productionUserId);
        boolean open = m.status == MeetingStatus.PENDING || m.status == MeetingStatus.COUNTER_PROPOSED;
        boolean mask = viewer.role() == Role.PRODUCTION;
        boolean conflict = open && db.blocksFor(a.id()).stream()
                .anyMatch(b -> !m.date.isBefore(b.start()) && !m.date.isAfter(b.end()));
        return new MeetingView(m.id, a.id(), a.name(), db.users.get(a.managerId()).name(),
                prod.name(), prod.org(), m.projectTitle, m.message, m.initiatedBy.name(),
                m.awaiting == null ? null : m.awaiting.name(), m.status.name(),
                m.date, m.time, conflict,
                open && viewer.role() == m.awaiting,
                open || m.status == MeetingStatus.ACCEPTED,
                m.history,
                m.snapshot.stream().map(b -> blockView(b, mask)).toList());
    }

    private Event ev(User u, String action, String comment, LocalDate date, String time) {
        return new Event(Instant.now(), u.name(), u.role().name(), action, comment, date, time);
    }

    private String comment(ResponseRequest body) {
        return body == null || body.comment() == null ? "" : body.comment().trim();
    }

    private String normTime(String time) {
        try {
            return LocalTime.parse(time == null || time.isBlank() ? "11:00" : time)
                    .format(DateTimeFormatter.ofPattern("HH:mm"));
        } catch (DateTimeParseException e) {
            throw status(HttpStatus.BAD_REQUEST, "Time should look like 14:30");
        }
    }

    private String ics(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r", "").replace("\n", "\\n");
    }

    private String cleanName(String raw) {
        String n = raw == null ? "" : raw;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        n = n.replaceAll("[^A-Za-z0-9 ._()-]", "_").trim();
        if (n.length() > 80) n = n.substring(n.length() - 80);
        if (n.isBlank()) n = "resume.pdf";
        if (!n.toLowerCase().endsWith(".pdf")) n = n + ".pdf";
        return n;
    }

    private ResponseStatusException status(HttpStatus s, String msg) {
        return new ResponseStatusException(s, msg);
    }
}
