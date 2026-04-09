package com.aiplanner.backend.integration;

import java.util.List;
import java.util.Map;

public interface SyllabusConnector {
    String sourceName();
    List<Map<String, Object>> importCourseArtifacts(String userId);
}
