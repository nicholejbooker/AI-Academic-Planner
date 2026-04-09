package com.aiplanner.backend.integration;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SimpleSyllabusConnector implements SyllabusConnector {

    @Override
    public String sourceName() {
        return "simple-syllabus";
    }

    @Override
    public List<Map<String, Object>> importCourseArtifacts(String userId) {
        return List.of(
                Map.of(
                        "title", "Sample Syllabus Task",
                        "dueDate", "2026-04-20",
                        "type", "assignment",
                        "source", sourceName()));
    }
}
