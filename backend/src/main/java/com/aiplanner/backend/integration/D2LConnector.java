package com.aiplanner.backend.integration;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class D2LConnector implements SyllabusConnector {

    @Override
    public String sourceName() {
        return "d2l";
    }

    @Override
    public List<Map<String, Object>> importCourseArtifacts(String userId) {
        return List.of(
                Map.of(
                        "title", "Sample D2L Quiz",
                        "dueDate", "2026-04-22",
                        "type", "exam",
                        "source", sourceName(),
                        "note", "Replace with OAuth API import or browser session workflow"));
    }
}
