package com.aiplanner.backend.service;

import com.aiplanner.backend.integration.D2LConnector;
import com.aiplanner.backend.integration.SimpleSyllabusConnector;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PlanningService {

    private final SimpleSyllabusConnector simpleSyllabusConnector;
    private final D2LConnector d2lConnector;

    public PlanningService(
            SimpleSyllabusConnector simpleSyllabusConnector,
            D2LConnector d2lConnector) {
        this.simpleSyllabusConnector = simpleSyllabusConnector;
        this.d2lConnector = d2lConnector;
    }

    public Map<String, Object> generateWeeklyPlan(String courseId) {
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);

        List<Map<String, Object>> tasks = List.of(
                Map.of("day", "Monday", "focus", "Read " + normalizeCourse(courseId) + " syllabus + collect due dates", "minutes", 45),
                Map.of("day", "Tuesday", "focus", normalizeCourse(courseId) + " assignment work block", "minutes", 90),
                Map.of("day", "Wednesday", "focus", normalizeCourse(courseId) + " quiz prep + flashcards", "minutes", 60),
                Map.of("day", "Thursday", "focus", normalizeCourse(courseId) + " project milestone progress", "minutes", 90),
                Map.of("day", "Friday", "focus", "Review " + normalizeCourse(courseId) + " grades + catch-up", "minutes", 45));

        Map<String, Object> calendar = new LinkedHashMap<>();
        calendar.put("inAppCalendar", true);
        calendar.put("googleSyncReady", false);
        calendar.put("outlookSyncReady", false);

        return Map.of(
                "courseId", normalizeCourse(courseId),
                "sources", List.of(
                        simpleSyllabusConnector.sourceName(),
                        d2lConnector.sourceName()),
                "status", "prototype",
                "weekOf", weekStart.toString(),
                "tasks", tasks,
                "calendar", calendar);
    }

    private String normalizeCourse(String courseId) {
        if (courseId == null || courseId.isBlank()) {
            return "GENERAL";
        }
        return courseId.trim().toUpperCase();
    }
}
