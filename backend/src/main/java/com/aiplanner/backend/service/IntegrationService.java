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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final String SYLLABUS_LIBRARY_URL = SIMPLE_SYLLABUS_BASE + "/en-US/syllabus-library";
    private static final Duration OPTIONS_CACHE_TTL = Duration.ofMinutes(30);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Pattern MDY_DATE_PATTERN = Pattern.compile("\\b(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?\\b");
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    private static final Pattern COUNTED_ITEM_PATTERN = Pattern.compile(
            "(?i)\\b(\\d{1,2})\\s+(?:regular\\s+)?(quizzes|quiz|assignments|assignment|projects|project|labs|lab|tests|test|papers|paper|exams|exam)\\b");
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
                k -> defaultAssignments(normalizedCourseCode, semester));

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
                k -> defaultAssignments(normalizedCourseCode, semester));
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

    public Map<String, Object> parseAndApplySyllabusText(
            String courseCode,
            String semester,
            String professor,
            String syllabusText,
            String syllabusUrl) {
        String effectiveText = syllabusText;
        if ((effectiveText == null || effectiveText.isBlank()) && syllabusUrl != null && !syllabusUrl.isBlank()) {
            effectiveText = fetchTextFromUrl(syllabusUrl);
        }
        String normalizedCourseCode = normalizeParsedCourseCode(courseCode, syllabusUrl, effectiveText);
        String normalizedSemester = normalizeParsedSemester(semester, syllabusUrl, effectiveText);
        String normalizedProfessor = normalizeParsedProfessor(professor, syllabusUrl, effectiveText);
        String offeringKey = offeringKey(normalizedCourseCode, normalizedSemester, normalizedProfessor);

        List<Map<String, Object>> parsed = parseAssignmentsFromText(
                normalizedCourseCode,
                normalizedSemester,
                effectiveText,
                syllabusUrl);
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException(
                    "No assignments were detected. Paste schedule lines or provide a syllabus URL with visible assignment text.");
        }

        assignmentStore.put(offeringKey, parsed);
        calendarStore.remove(offeringKey);

        return Map.of(
                "courseCode", normalizedCourseCode,
                "semester", normalizedSemester,
                "professor", normalizedProfessor,
                "count", parsed.size(),
                "assignments", parsed,
                "source", "manual-syllabus-text");
    }

    private String normalizeParsedCourseCode(String provided, String syllabusUrl, String text) {
        if (provided != null && !provided.isBlank()) {
            return normalizedCourseCode(provided);
        }
        String inferred = inferCourseCode(syllabusUrl + " " + text);
        return inferred == null ? normalizedCourseCode(null) : inferred;
    }

    private String normalizeParsedSemester(String provided, String syllabusUrl, String text) {
        if (provided != null && !provided.isBlank()) {
            return normalizedSemester(provided);
        }
        String inferred = inferSemester(syllabusUrl + " " + text);
        return inferred == null ? normalizedSemester(null) : inferred;
    }

    private String normalizeParsedProfessor(String provided, String syllabusUrl, String text) {
        if (provided != null && !provided.isBlank()) {
            return normalizeProfessor(provided);
        }
        String inferred = inferProfessor(syllabusUrl + " " + text);
        return inferred == null ? normalizeProfessor(null) : inferred;
    }

    private String inferCourseCode(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        Matcher m = Pattern.compile("\\b([A-Za-z]{3,5})[-\\s](\\d{3,4}[A-Za-z0-9]{0,2})\\b").matcher(source);
        if (m.find()) {
            return (m.group(1) + " " + m.group(2)).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    private String inferSemester(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        String normalized = source.toLowerCase(Locale.ROOT);
        String term = null;
        if (normalized.contains("fall")) {
            term = "Fall";
        } else if (normalized.contains("summer")) {
            term = "Summer";
        } else if (normalized.contains("spring")) {
            term = "Spring";
        } else if (normalized.contains("winter")) {
            term = "Winter";
        }
        if (term == null) {
            return null;
        }
        Matcher yearMatcher = Pattern.compile("\\b(20\\d{2})\\b").matcher(source);
        String year = yearMatcher.find() ? yearMatcher.group(1) : String.valueOf(LocalDate.now().getYear());
        return term + " " + year;
    }

    private String inferProfessor(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        Matcher m = Pattern.compile("(?i)\\b(?:professor|instructor|prof)\\s*[:\\-]?\\s*([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){1,2})\\b")
                .matcher(source);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    private String fetchTextFromUrl(String rawUrl) {
        try {
            String url = rawUrl.trim();
            if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                throw new IllegalArgumentException("Syllabus URL must start with http:// or https://");
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .header("User-Agent", "AI-Academic-Planner/1.0")
                    .header("Accept", "text/html, text/plain")
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("Could not fetch syllabus URL (status " + response.statusCode() + ").");
            }
            return htmlToText(response.body());
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not fetch syllabus URL. Paste syllabus text instead.");
        }
    }

    private String htmlToText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("(?i)</div>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&");
        text = text.replaceAll("[ \\t]+", " ");
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }

    private List<Map<String, Object>> parseAssignmentsFromText(
            String courseCode,
            String semester,
            String syllabusText,
            String syllabusUrl) {
        if (syllabusText == null || syllabusText.isBlank()) {
            return List.of();
        }
        int year = extractYear(semester);
        List<Map<String, Object>> assignments = new ArrayList<>();
        String[] lines = syllabusText.split("\\R");
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.length() < 4) {
                continue;
            }
            String date = extractDateFromLine(line, year);
            if (date == null) {
                continue;
            }
            if (!containsAssessmentKeyword(line)) {
                continue;
            }

            String title = cleanParsedTitle(line, date, courseCode);
            String type = inferAssignmentType(title);
            Map<String, Object> row = buildAssignment(title, date, type, true);
            if (syllabusUrl != null && !syllabusUrl.isBlank()) {
                row.put("sourceUrl", syllabusUrl.trim());
            }
            assignments.add(row);
        }

        Map<String, Map<String, Object>> byTitleDate = new LinkedHashMap<>();
        for (Map<String, Object> item : assignments) {
            String key = item.get("title") + "|" + item.get("dueDate");
            byTitleDate.putIfAbsent(key, item);
        }
        List<Map<String, Object>> unique = new ArrayList<>(byTitleDate.values());

        // If syllabus lists assessments without dates (e.g., "5 regular quizzes"),
        // generate tentative due dates across the term so users can edit later.
        unique.addAll(parseUndatedAssessmentHints(courseCode, semester, syllabusText, syllabusUrl, unique));

        Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
        for (Map<String, Object> item : unique) {
            String key = item.get("title") + "|" + item.get("dueDate");
            deduped.putIfAbsent(key, item);
        }
        unique = new ArrayList<>(deduped.values());
        unique.sort(Comparator.comparing(a -> String.valueOf(a.get("dueDate"))));
        return unique;
    }

    private List<Map<String, Object>> parseUndatedAssessmentHints(
            String courseCode,
            String semester,
            String syllabusText,
            String syllabusUrl,
            List<Map<String, Object>> existing) {
        String[] lines = syllabusText.split("\\R");
        List<String> generatedTitles = new ArrayList<>();
        for (Map<String, Object> item : existing) {
            generatedTitles.add(String.valueOf(item.get("title")).toLowerCase(Locale.ROOT));
        }

        List<String> requestedTitles = new ArrayList<>();
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.length() < 3 || !containsAssessmentKeyword(line) || extractDateFromLine(line, extractYear(semester)) != null) {
                continue;
            }
            Matcher counted = COUNTED_ITEM_PATTERN.matcher(line);
            if (counted.find()) {
                int count = Math.min(12, Math.max(1, Integer.parseInt(counted.group(1))));
                String kind = singularizeKind(counted.group(2));
                for (int i = 1; i <= count; i++) {
                    String title = "Tentative " + capitalize(kind) + " " + i;
                    if (!generatedTitles.contains(title.toLowerCase(Locale.ROOT))) {
                        requestedTitles.add(title);
                        generatedTitles.add(title.toLowerCase(Locale.ROOT));
                    }
                }
                continue;
            }

            String title = cleanParsedTitle(line, "", courseCode);
            if (title.isBlank()) {
                continue;
            }
            if (!title.toLowerCase(Locale.ROOT).startsWith("tentative ")) {
                title = "Tentative " + title;
            }
            if (!generatedTitles.contains(title.toLowerCase(Locale.ROOT))) {
                requestedTitles.add(title);
                generatedTitles.add(title.toLowerCase(Locale.ROOT));
            }
        }

        if (requestedTitles.isEmpty()) {
            return List.of();
        }

        LocalDate start = semesterStartDate(semester);
        int[] offsets = semesterOffsets(semester);
        int spanDays = Math.max(offsets[offsets.length - 1], 56);
        int step = Math.max(7, spanDays / Math.max(1, requestedTitles.size() + 1));

        List<Map<String, Object>> generated = new ArrayList<>();
        for (int i = 0; i < requestedTitles.size(); i++) {
            LocalDate due = start.plusDays(7L + (long) step * i);
            String title = requestedTitles.get(i);
            Map<String, Object> row = buildAssignment(
                    title,
                    due.format(DATE_FORMATTER),
                    inferAssignmentType(title),
                    true);
            if (syllabusUrl != null && !syllabusUrl.isBlank()) {
                row.put("sourceUrl", syllabusUrl.trim());
            }
            generated.add(row);
        }
        return generated;
    }

    private String singularizeKind(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.endsWith("ies")) {
            return lower.substring(0, lower.length() - 3) + "y";
        }
        if (lower.endsWith("s")) {
            return lower.substring(0, lower.length() - 1);
        }
        return lower;
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "Item";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private boolean containsAssessmentKeyword(String line) {
        String normalizedLine = line.toLowerCase(Locale.ROOT);
        return normalizedLine.contains("quiz")
                || normalizedLine.contains("exam")
                || normalizedLine.contains("test")
                || normalizedLine.contains("assignment")
                || normalizedLine.contains("project")
                || normalizedLine.contains("paper")
                || normalizedLine.contains("lab")
                || normalizedLine.contains("discussion")
                || normalizedLine.contains("hw")
                || normalizedLine.contains("homework")
                || normalizedLine.contains("due");
    }

    private String inferAssignmentType(String title) {
        String normalized = title.toLowerCase(Locale.ROOT);
        if (normalized.contains("quiz")) {
            return "quiz";
        }
        if (normalized.contains("exam") || normalized.contains("midterm") || normalized.contains("final")) {
            return "exam";
        }
        if (normalized.contains("project")) {
            return "project";
        }
        if (normalized.contains("lab")) {
            return "lab";
        }
        return "assignment";
    }

    private String cleanParsedTitle(String line, String date, String courseCode) {
        String title = line;
        if (date != null && !date.isBlank()) {
            title = title.replace(date, " ");
        }
        title = title.replaceAll("\\b\\d{1,2}[/-]\\d{1,2}(?:[/-]\\d{2,4})?\\b", " ");
        title = title.replaceAll("\\b\\d{4}-\\d{2}-\\d{2}\\b", " ");
        title = title.replaceAll("(?i)\\bdue\\b", " ");
        title = title.replaceAll("[\\|:;\\-]{2,}", " ");
        title = title.replaceAll("\\s+", " ").trim();
        if (title.isBlank()) {
            return courseCode + " Assignment";
        }
        if (title.length() > 120) {
            return title.substring(0, 120).trim();
        }
        return title;
    }

    private String extractDateFromLine(String line, int defaultYear) {
        Matcher iso = ISO_DATE_PATTERN.matcher(line);
        if (iso.find()) {
            return iso.group(0);
        }
        Matcher mdy = MDY_DATE_PATTERN.matcher(line);
        if (!mdy.find()) {
            return null;
        }
        int month = Integer.parseInt(mdy.group(1));
        int day = Integer.parseInt(mdy.group(2));
        String yearGroup = mdy.group(3);
        int year = defaultYear;
        if (yearGroup != null && !yearGroup.isBlank()) {
            int parsedYear = Integer.parseInt(yearGroup);
            year = parsedYear < 100 ? 2000 + parsedYear : parsedYear;
        }
        try {
            LocalDate parsed = LocalDate.of(year, month, day);
            return parsed.format(DATE_FORMATTER);
        } catch (Exception ignored) {
            return null;
        }
    }

    private int extractYear(String semester) {
        String normalized = normalizedSemester(semester);
        String[] parts = normalized.split(" ");
        for (String part : parts) {
            if (part.matches("\\d{4}")) {
                return Integer.parseInt(part);
            }
        }
        return LocalDate.now().getYear();
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
        return assignmentStore.computeIfAbsent(key, k -> defaultAssignments(courseCode, semester));
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
        // MGA academic calendar defaults:
        // Spring full session starts Jan 12, 2026; summer full session starts May 27, 2026;
        // fall starts Aug 12, 2026. Short sessions are handled when explicitly named.
        if (normalized.contains("spring")) {
            if (normalized.contains("short session ii")) {
                return LocalDate.of(year, 3, 9);
            }
            return LocalDate.of(year, 1, 12);
        }
        if (normalized.contains("summer")) {
            if (normalized.contains("short session i")) {
                return LocalDate.of(year, 5, 20);
            }
            if (normalized.contains("short session ii")) {
                return LocalDate.of(year, 6, 24);
            }
            return LocalDate.of(year, 5, 27);
        }
        if (normalized.contains("winter")) {
            return LocalDate.of(year, 1, 5);
        }
        return LocalDate.of(year, 8, 12);
    }

    private int[] semesterOffsets(String semester) {
        String normalized = normalizedSemester(semester).toLowerCase(Locale.ROOT);
        if (normalized.contains("summer")) {
            // Compressed summer timeline
            return new int[] {7, 17, 31, 45, 60};
        }
        if (normalized.contains("spring") || normalized.contains("winter")) {
            return new int[] {14, 28, 49, 77, 105};
        }
        // Fall/default timeline
        return new int[] {14, 28, 49, 84, 112};
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
            item.put("syllabusUrl", buildSyllabusUrl(normalizedCourseCode, semester, professor));
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
        item.put("syllabusUrl", buildSyllabusUrl(courseCode, semester, professor));
        return item;
    }

    private String buildSyllabusUrl(String courseCode, String semester, String professor) {
        StringBuilder query = new StringBuilder();
        if (courseCode != null && !courseCode.isBlank()) {
            query.append(courseCode.trim());
        }
        if (semester != null && !semester.isBlank()) {
            if (query.length() > 0) {
                query.append(' ');
            }
            query.append(semester.trim());
        }
        if (professor != null && !professor.isBlank() && !professor.equalsIgnoreCase("Staff")) {
            if (query.length() > 0) {
                query.append(' ');
            }
            query.append(professor.trim());
        }
        if (query.length() == 0) {
            return SYLLABUS_LIBRARY_URL;
        }
        return SYLLABUS_LIBRARY_URL + "?search=" + URLEncoder.encode(query.toString(), StandardCharsets.UTF_8);
    }

    private List<Map<String, Object>> defaultAssignments(String courseCode, String semester) {
        LocalDate start = semesterStartDate(semester);
        int[] offsets = semesterOffsets(semester);
        List<Map<String, Object>> assignments = new ArrayList<>();
        assignments.add(buildAssignment(
                "Tentative Assignment 1",
                start.plusDays(offsets[0]).format(DATE_FORMATTER),
                "assignment",
                true));
        assignments.add(buildAssignment(
                "Tentative Quiz 1",
                start.plusDays(offsets[1]).format(DATE_FORMATTER),
                "quiz",
                true));
        assignments.add(buildAssignment(
                "Tentative Midterm",
                start.plusDays(offsets[2]).format(DATE_FORMATTER),
                "exam",
                true));
        assignments.add(buildAssignment(
                courseCode + " Project Draft",
                start.plusDays(offsets[3]).format(DATE_FORMATTER),
                "project",
                true));
        assignments.add(buildAssignment(
                courseCode + " Final Exam",
                start.plusDays(offsets[4]).format(DATE_FORMATTER),
                "exam",
                true));
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
