package com.aiplanner.backend.api;

import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    @GetMapping("/today")
    public Map<String, Object> today() {
        return Map.of(
                "message", "Focus on the nearest due item first, then complete one 60-minute deep work block.",
                "priority", "Complete Assignment 2 draft",
                "fallback", "If AI is disabled, use risk score by due date proximity and current grade.");
    }
}
