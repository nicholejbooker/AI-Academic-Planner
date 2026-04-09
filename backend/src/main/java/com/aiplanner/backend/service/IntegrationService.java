package com.aiplanner.backend.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class IntegrationService {

    private static final String SIMPLE_SYLLABUS_BASE = "https://mga.simplesyllabus.com";
    private static final Duration OPTIONS_CACHE_TTL = Duration.ofMinutes(30);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final Map<String, List<Map<String, Object>>> assignmentStore = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> calendarStore = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, String>>> courseCatalog = seedCatalog();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile List<String> cachedCourseCodes = List.of();
    private volatile List<String> cachedSemesters = List.of();
    private volatile Instant optionsCachedAt = Instant.EPOCH;

    public Map<String, Object> importArtifacts() {
        return importArtifacts(null, null, null);
    }

    public Map<String, Object> importArtifacts(
            String courseCode,
            String semester,
            String professor) {
        String normalizedCourseCode = normalizedCourseCode(courseCode);
        List<Map<String, Object>> offerings = filterOfferings(normalizedCourseCode, semester, professor);
        if (offerings.isEmpty()) {
            offerings = dynamicOfferings(normalizedCourseCode, semester, professor);
        }
        return Map.of(
                "courseCode", normalizedCourseCode,
                "semesterQuery", normalizeFilter(semester),
                "professorQuery", normalizeFilter(professor),
                "count", offerings.size(),
                "offerings", offerings);
    }

    public Map<String, Object> getOfferingSchedule(
            String courseCode,
            String semester,
            String professor) {
        String normalizedCourseCode = normalizedCourseCode(courseCode);
        String offeringKey = offeringKey(normalizedCourseCode, semester, professor);
        List<Map<String, Object>> assignments = assignmentStore.computeIfAbsent(
                offeringKey,
                k -> defaultAssignments(normalizedCourseCode));

        return Map.of(
                "courseCode", normalizedCourseCode,
                "semester", normalizedSemester(semester),
                "professor", normalizeProfessor(professor),
                "offeringId", offeringKey,
                "assignments", assignments,
                "count", assignments.size());
    }

    public Map<String, Object> updateAssignmentDueDate(
            String courseCode,
            String semester,
            String professor,
            String assignmentTitle,
            String dueDate,
            boolean tentative) {
        String normalizedCourseCode = normalizedCourseCode(courseCode);
        String offeringKey = offeringKey(normalizedCourseCode, semester, professor);
        List<Map<String, Object>> assignments = assignmentStore.computeIfAbsent(
                offeringKey,
                k -> defaultAssignments(normalizedCourseCode));
        boolean found = false;
        for (int i = 0; i < assignments.size(); i++) {
            Map<String, Object> row = assignments.get(i);
            if (Objects.equals(row.get("title"), assignmentTitle)) {
                Map<String, Object> updated = new LinkedHashMap<>(row);
                updated.put("dueDate", dueDate);
                updated.put("tentative", tentative);
                assignments.set(i, updated);
                found = true;
                break;
            }
        }
        if (!found) {
            Map<String, Object> appended = new LinkedHashMap<>();
            appended.put("title", assignmentTitle);
            appended.put("dueDate", dueDate);
            appended.put("type", "assignment");
            appended.put("tentative", tentative);
            assignments.add(appended);
        }

        // Keep calendar deadline entries in sync with assignment due-date edits.
        String key = offeringKey(normalizedCourseCode, semester, professor);
        List<Map<String, Object>> calendarEvents = calendarStore.get(key);
        if (calendarEvents != null) {
            boolean deadlineFound = false;
            for (int i = 0; i < calendarEvents.size(); i++) {
                Map<String, Object> event = calendarEvents.get(i);
                if (Objects.equals(String.valueOf(event.get("title")), assignmentTitle)
                        && "deadline".equals(String.valueOf(event.get("eventType")))) {
                    Map<String, Object> updatedEvent = new LinkedHashMap<>(event);
                    updatedEvent.put("date", dueDate);
                    updatedEvent.put("tentative", tentative);
                    calendarEvents.set(i, updatedEvent);
                    deadlineFound = true;
                    break;
                }
            }
            if (!deadlineFound) {
                calendarEvents.add(buildCalendarEvent(
                        assignmentTitle,
                        dueDate,
                        "23:00",
                        "23:59",
                        "deadline",
                        tentative,
                        true,
                        "syllabus"));
            }
        }

        return getOfferingSchedule(normalizedCourseCode, semester, professor);
    }

    public Map<String, Object> linkSyllabusToCalendar(
            String courseCode,
            String semester,
            String professor,
            boolean adminApproved) {
        String normalizedCourseCode = normalizedCourseCode(courseCode);
        List<Map<String, Object>> assignments = getScheduleAssignments(normalizedCourseCode, semester, professor);
        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> artifact : assignments) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("title", artifact.get("title"));
            event.put("date", artifact.get("dueDate"));
            event.put("eventType", "deadline");
            event.put("calendar", "in-app");
            event.put("tentative", artifact.get("tentative"));
            events.add(event);
        }

        return Map.of(
                "courseCode", normalizedCourseCode,
                "semester", normalizedSemester(semester),
                "professor", normalizeProfessor(professor),
                "linked", true,
                "adminApproved", adminApproved,
                "eventCount", events.size(),
                "events", events);
    }

    public Map<String, Object> getCalendar(String courseCode, String semester, String professor) {
        String normalizedCourseCode = normalizedCourseCode(courseCode);
        String key = offeringKey(normalizedCourseCode, semester, professor);
        List<Map<String, Object>> events = calendarStore.computeIfAbsent(
                key,
                k -> initializeCalendarEvents(normalizedCourseCode, semester, professor));
        events.sort(Comparator.comparing(e -> String.valueOf(e.getOrDefault("date", "")) + "T"
                + String.valueOf(e.getOrDefault("startTime", ""))));
        return Map.of(
                "courseCode", normalizedCourseCode,
                "semester", normalizedSemester(semester),
                "professor", normalizeProfessor(professor),
                "count", events.size(),
                "events", events);
    }

    public Map<String, Object> addCalendarBlock(
            String courseCode,
            String semester,
            String professor,
            String title,
            String date,
            String startTime,
            String endTime,
            String eventType) {
        String normalizedCourseCode = normalizedCourseCode(courseCode);
        String key = offeringKey(normalizedCourseCode, semester, professor);
        List<Map<String, Object>> events = calendarStore.computeIfAbsent(
                key,
                k -> initializeCalendarEvents(normalizedCourseCode, semester, professor));
        events.add(buildCalendarEvent(
                title,
                date,
                startTime,
                endTime,
                eventType == null || eventType.isBlank() ? "study-block" : eventType,
                false,
                true,
                "manual"));
        return getCalendar(normalizedCourseCode, semester, professor);
    }

    public Map<String, Object> updateCalendarEvent(
            String courseCode,
            String semester,
            String professor,
            String eventId,
            String date,
            String startTime,
            String endTime) {
        String normalizedCourseCode = normalizedCourseCode(courseCode);
        String key = offeringKey(normalizedCourseCode, semester, professor);
        List<Map<String, Object>> events = calendarStore.computeIfAbsent(
                key,
                k -> initializeCalendarEvents(normalizedCourseCode, semester, professor));
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> row = events.get(i);
            if (Objects.equals(String.valueOf(row.get("id")), eventId)) {
                Map<String, Object> updated = new LinkedHashMap<>(row);
                updated.put("date", date);
                updated.put("startTime", startTime);
                updated.put("endTime", endTime);
                events.set(i, updated);
                break;
            }
        }
        return getCalendar(normalizedCourseCode, semester, professor);
    }

    public Map<String, Object> options() {
        List<String> courseCodes = fetchLiveCourseCodes();
        if (courseCodes.isEmpty()) {
            courseCodes = new ArrayList<>(courseCatalog.keySet());
            courseCodes.sort(String::compareTo);
        }

        List<String> semesters = fetchLiveSemesters();
        if (semesters.isEmpty()) {
            semesters = new ArrayList<>();
        }
        List<String> professors = new ArrayList<>();
        for (List<Map<String, String>> offerings : courseCatalog.values()) {
            for (Map<String, String> offering : offerings) {
                String semester = offering.getOrDefault("semester", "");
                String professor = offering.getOrDefault("professor", "");
                if (!semester.isBlank() && !semesters.contains(semester)) {
                    semesters.add(semester);
                }
                if (!professor.isBlank() && !professors.contains(professor)) {
                    professors.add(professor);
                }
            }
        }
        semesters.sort(String::compareTo);
        professors.sort(String::compareTo);
        return Map.of(
                "courseCodes", courseCodes,
                "semesters", semesters,
                "professors", professors);
    }

    private List<String> fetchLiveCourseCodes() {
        if (!cachedCourseCodes.isEmpty() && Instant.now().isBefore(optionsCachedAt.plus(OPTIONS_CACHE_TTL))) {
            return cachedCourseCodes;
        }
        try {
            List<Map<String, Object>> subjectList = fetchAllPagedItems("/api/subject", 30);
            if (subjectList.isEmpty()) {
                return List.of();
            }

            Set<String> courseCodes = new HashSet<>();
            for (Map<String, Object> subjectMap : subjectList) {
                Object subjectNameObj = subjectMap.get("name");
                if (!(subjectNameObj instanceof String subjectName) || subjectName.isBlank()) {
                    continue;
                }
                Map<String, Object> numbersPayload = fetchJson(
                        "/api/course-numbers?subject_name=" + URLEncoder.encode(subjectName, StandardCharsets.UTF_8));
                Object numberItems = numbersPayload.get("items");
                if (!(numberItems instanceof List<?> numbers)) {
                    continue;
                }
                for (Object numberObj : numbers) {
                    if (numberObj instanceof String number && !number.isBlank()) {
                        courseCodes.add(subjectName.toUpperCase(Locale.ROOT) + " " + number.toUpperCase(Locale.ROOT));
                    }
                }
            }
            List<String> sorted = new ArrayList<>(courseCodes);
            sorted.sort(String::compareTo);
            cachedCourseCodes = sorted;
            optionsCachedAt = Instant.now();
            return sorted;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<String> fetchLiveSemesters() {
        if (!cachedSemesters.isEmpty() && Instant.now().isBefore(optionsCachedAt.plus(OPTIONS_CACHE_TTL))) {
            return cachedSemesters;
        }
        try {
            List<Map<String, Object>> terms = fetchAllPagedItems("/api/term", 20);
            if (terms.isEmpty()) {
                return List.of();
            }
            Set<String> names = new HashSet<>();
            for (Map<String, Object> termMap : terms) {
                Object nameObj = termMap.get("name");
                if (nameObj instanceof String name && !name.isBlank()) {
                    names.add(name);
                }
            }
            List<String> sorted = new ArrayList<>(names);
            sorted.sort(String::compareTo);
            cachedSemesters = sorted;
            optionsCachedAt = Instant.now();
            return sorted;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchJson(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SIMPLE_SYLLABUS_BASE + path))
                .GET()
                .header("Accept", "application/json")
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Simple Syllabus request failed: " + response.statusCode());
        }
        return objectMapper.readValue(response.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchAllPagedItems(String endpoint, int maxPages) throws Exception {
        List<Map<String, Object>> all = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        int expectedTotal = Integer.MAX_VALUE;
        int page = 0;
        while (page < maxPages && all.size() < expectedTotal) {
            Map<String, Object> payload = fetchJson(endpoint + "?page=" + page + "&page_size=200");
            Object lengthObj = payload.get("length");
            if (lengthObj instanceof Number len && len.intValue() > 0) {
                expectedTotal = len.intValue();
            }
            Object itemsObj = payload.get("items");
            if (!(itemsObj instanceof List<?> items) || items.isEmpty()) {
                break;
            }
            int before = all.size();
            for (Object obj : items) {
                if (!(obj instanceof Map<?, ?> rawMap)) {
                    continue;
                }
                Map<String, Object> map = (Map<String, Object>) rawMap;
                String id = String.valueOf(map.getOrDefault("entity_id", ""));
                if (!id.isBlank() && !seenIds.add(id)) {
                    continue;
                }
                all.add(map);
            }
            if (all.size() == before) {
                break;
            }
            page += 1;
        }
        return all;
    }

    private List<Map<String, Object>> getScheduleAssignments(
            String courseCode,
            String semester,
            String professor) {
        String key = offeringKey(courseCode, semester, professor);
        return assignmentStore.computeIfAbsent(key, k -> defaultAssignments(courseCode));
    }

    private List<Map<String, Object>> initializeCalendarEvents(
            String courseCode,
            String semester,
            String professor) {
        List<Map<String, Object>> seed = new ArrayList<>();
        seed.addAll(generateClassMeetings(courseCode, semester, professor));
        List<Map<String, Object>> assignments = getScheduleAssignments(courseCode, semester, professor);
        for (Map<String, Object> assignment : assignments) {
            seed.add(buildCalendarEvent(
                    String.valueOf(assignment.get("title")),
                    String.valueOf(assignment.get("dueDate")),
                    "23:00",
                    "23:59",
                    "deadline",
                    Boolean.TRUE.equals(assignment.get("tentative")),
                    true,
                    "syllabus"));
        }
        return seed;
    }

    private List<Map<String, Object>> generateClassMeetings(
            String courseCode,
            String semester,
            String professor) {
        List<Map<String, Object>> events = new ArrayList<>();
        LocalDate startDate = semesterStartDate(semester);
        boolean monWed = Math.abs((courseCode + professor).hashCode()) % 2 == 0;
        int[] offsets = monWed ? new int[] {0, 2} : new int[] {1, 3};
        String startTime = monWed ? "15:00" : "10:00";
        String endTime = monWed ? "16:15" : "11:15";

        for (int week = 0; week < 8; week++) {
            for (int offset : offsets) {
                LocalDate classDate = startDate.plusDays(week * 7L + offset);
                events.add(buildCalendarEvent(
                        courseCode + " class",
                        classDate.format(DATE_FORMATTER),
                        startTime,
                        endTime,
                        "class",
                        false,
                        true,
                        "syllabus"));
            }
        }
        return events;
    }

    private Map<String, Object> buildCalendarEvent(
            String title,
            String date,
            String startTime,
            String endTime,
            String eventType,
            boolean tentative,
            boolean editable,
            String source) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", UUID.randomUUID().toString());
        event.put("title", title);
        event.put("date", date);
        event.put("startTime", startTime);
        event.put("endTime", endTime);
        event.put("eventType", eventType);
        event.put("tentative", tentative);
        event.put("editable", editable);
        event.put("source", source);
        return event;
    }

    private LocalDate semesterStartDate(String semester) {
        String normalized = normalizedSemester(semester).toLowerCase(Locale.ROOT);
        int year = 2026;
        String[] parts = normalized.split(" ");
        for (String part : parts) {
            if (part.matches("\\d{4}")) {
                year = Integer.parseInt(part);
                break;
            }
        }
        if (normalized.contains("spring")) {
            return LocalDate.of(year, 1, 12);
        }
        if (normalized.contains("summer")) {
            return LocalDate.of(year, 5, 15);
        }
        if (normalized.contains("winter")) {
            return LocalDate.of(year, 1, 5);
        }
        return LocalDate.of(year, 8, 18);
    }

    private List<Map<String, Object>> filterOfferings(
            String normalizedCourseCode,
            String semesterFilter,
            String professorFilter) {
        List<Map<String, String>> offerings = courseCatalog.getOrDefault(normalizedCourseCode, List.of());
        String normalizedSemesterFilter = normalizeFilter(semesterFilter).toLowerCase(Locale.ROOT);
        String normalizedProfessorFilter = normalizeFilter(professorFilter).toLowerCase(Locale.ROOT);
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, String> offering : offerings) {
            String semester = offering.getOrDefault("semester", "");
            String professor = offering.getOrDefault("professor", "");
            if (!semester.toLowerCase(Locale.ROOT).contains(normalizedSemesterFilter)) {
                continue;
            }
            if (!professor.toLowerCase(Locale.ROOT).contains(normalizedProfessorFilter)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("courseCode", normalizedCourseCode);
            item.put("semester", semester);
            item.put("professor", professor);
            item.put("section", offering.getOrDefault("section", "A01"));
            item.put("source", offering.getOrDefault("source", "syllabus-catalog"));
            filtered.add(item);
        }
        filtered.sort(Comparator.comparing(x -> String.valueOf(x.get("semester"))));
        Collections.reverse(filtered);
        return filtered;
    }

    private List<Map<String, Object>> dynamicOfferings(
            String normalizedCourseCode,
            String semesterFilter,
            String professorFilter) {
        List<String> liveCourseCodes = fetchLiveCourseCodes();
        if (!liveCourseCodes.contains(normalizedCourseCode)) {
            return List.of();
        }

        String normalizedProfessor = normalizeProfessor(professorFilter);
        List<String> semesters = fetchLiveSemesters();
        List<Map<String, Object>> generated = new ArrayList<>();

        if (semesterFilter != null && !semesterFilter.isBlank()) {
            generated.add(buildOffering(
                    normalizedCourseCode,
                    normalizedSemester(semesterFilter),
                    normalizedProfessor,
                    "A01",
                    "simple-syllabus-live"));
            return generated;
        }

        // If no term filter is provided, show a handful of recent/active terms.
        int maxTerms = Math.min(4, semesters.size());
        for (int i = semesters.size() - 1; i >= 0 && generated.size() < maxTerms; i--) {
            generated.add(buildOffering(
                    normalizedCourseCode,
                    semesters.get(i),
                    normalizedProfessor,
                    "A01",
                    "simple-syllabus-live"));
        }
        if (generated.isEmpty()) {
            generated.add(buildOffering(
                    normalizedCourseCode,
                    "Fall 2026",
                    normalizedProfessor,
                    "A01",
                    "simple-syllabus-live"));
        }
        return generated;
    }

    private Map<String, Object> buildOffering(
            String courseCode,
            String semester,
            String professor,
            String section,
            String source) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("courseCode", courseCode);
        item.put("semester", semester);
        item.put("professor", professor);
        item.put("section", section);
        item.put("source", source);
        return item;
    }

    private List<Map<String, Object>> defaultAssignments(String courseCode) {
        List<Map<String, Object>> assignments = new ArrayList<>();
        assignments.add(buildAssignment("Tentative Assignment 1", "2026-09-20", "assignment", true));
        assignments.add(buildAssignment("Tentative Quiz 1", "2026-10-04", "quiz", true));
        assignments.add(buildAssignment("Tentative Midterm", "2026-10-25", "exam", true));
        assignments.add(buildAssignment(courseCode + " Project Draft", "2026-11-15", "project", true));
        assignments.add(buildAssignment(courseCode + " Final Exam", "2026-12-08", "exam", true));
        return assignments;
    }

    private Map<String, Object> buildAssignment(String title, String dueDate, String type, boolean tentative) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", title);
        row.put("dueDate", dueDate);
        row.put("type", type);
        row.put("tentative", tentative);
        return row;
    }

    private Map<String, List<Map<String, String>>> seedCatalog() {
        Map<String, List<Map<String, String>>> catalog = new HashMap<>();
        catalog.put(
                "ITEC 1001",
                List.of(
                        offering("Fall 2026", "Brooke Ingram", "A01", "simple-syllabus"),
                        offering("Winter 2027", "Priya Shah", "B02", "d2l"),
                        offering("Fall 2025", "Brooke Ingram", "A02", "simple-syllabus")));
        catalog.put(
                "ITEC 2000",
                List.of(
                        offering("Fall 2026", "Daniel Koh", "A01", "d2l"),
                        offering("Winter 2027", "Mina Patel", "B03", "simple-syllabus")));
        catalog.put(
                "GENERAL",
                List.of(
                        offering("Fall 2026", "Staff", "A01", "simple-syllabus")));
        return catalog;
    }

    private Map<String, String> offering(String semester, String professor, String section, String source) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("semester", semester);
        row.put("professor", professor);
        row.put("section", section);
        row.put("source", source);
        return row;
    }

    private String offeringKey(String courseCode, String semester, String professor) {
        return courseCode + "|" + normalizedSemester(semester) + "|" + normalizeProfessor(professor);
    }

    private String normalizedSemester(String semester) {
        String normalized = normalizeFilter(semester);
        return normalized.isEmpty() ? "UNSPECIFIED TERM" : normalized;
    }

    private String normalizeProfessor(String professor) {
        String normalized = normalizeFilter(professor);
        return normalized.isEmpty() ? "STAFF" : normalized;
    }

    private String normalizeFilter(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }

    private String normalizedCourseCode(String courseCode) {
        if (courseCode == null || courseCode.isBlank()) {
            return "GENERAL";
        }
        return courseCode.trim().toUpperCase();
    }
}
