package com.aiplanner.backend.api;

import com.aiplanner.backend.service.IntegrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/integrations")
public class IntegrationsController {

    private final IntegrationService integrationService;

    public IntegrationsController(IntegrationService integrationService) {
        this.integrationService = integrationService;
    }

    @GetMapping("/import")
    public Map<String, Object> importArtifacts(
            @RequestParam(required = false) String courseCode,
            @RequestParam(required = false) String semester,
            @RequestParam(required = false) String professor) {
        return integrationService.importArtifacts(courseCode, semester, professor);
    }

    @GetMapping("/options")
    public Map<String, Object> options() {
        return integrationService.options();
    }

    @GetMapping("/offering-schedule")
    public Map<String, Object> offeringSchedule(
            @RequestParam @NotBlank String courseCode,
            @RequestParam @NotBlank String semester,
            @RequestParam @NotBlank String professor) {
        return integrationService.getOfferingSchedule(courseCode, semester, professor);
    }

    @PostMapping("/assignments/update")
    public Map<String, Object> updateAssignmentDueDate(@RequestBody @Valid UpdateAssignmentRequest request) {
        return integrationService.updateAssignmentDueDate(
                request.courseCode(),
                request.semester(),
                request.professor(),
                request.assignmentTitle(),
                request.dueDate(),
                request.tentative());
    }

    @GetMapping("/link")
    public Map<String, Object> linkArtifactsToCalendar(
            @RequestParam @NotBlank String courseCode,
            @RequestParam(required = false) String semester,
            @RequestParam(required = false) String professor) {
        return integrationService.linkSyllabusToCalendar(courseCode, semester, professor, true);
    }

    @GetMapping("/calendar")
    public Map<String, Object> calendar(
            @RequestParam @NotBlank String courseCode,
            @RequestParam(required = false) String semester,
            @RequestParam(required = false) String professor) {
        return integrationService.getCalendar(courseCode, semester, professor);
    }

    @PostMapping("/calendar/block")
    public Map<String, Object> addCalendarBlock(@RequestBody @Valid AddCalendarBlockRequest request) {
        return integrationService.addCalendarBlock(
                request.courseCode(),
                request.semester(),
                request.professor(),
                request.title(),
                request.date(),
                request.startTime(),
                request.endTime(),
                request.eventType());
    }

    @PostMapping("/calendar/event/update")
    public Map<String, Object> updateCalendarEvent(@RequestBody @Valid UpdateCalendarEventRequest request) {
        return integrationService.updateCalendarEvent(
                request.courseCode(),
                request.semester(),
                request.professor(),
                request.eventId(),
                request.date(),
                request.startTime(),
                request.endTime());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> handleIntegrationError(IllegalArgumentException ex) {
        return Map.of("error", ex.getMessage());
    }

    public record UpdateAssignmentRequest(
            @NotBlank String courseCode,
            @NotBlank String semester,
            @NotBlank String professor,
            @NotBlank String assignmentTitle,
            @NotBlank String dueDate,
            boolean tentative) {
    }

    public record AddCalendarBlockRequest(
            @NotBlank String courseCode,
            @NotBlank String semester,
            @NotBlank String professor,
            @NotBlank String title,
            @NotBlank String date,
            @NotBlank String startTime,
            @NotBlank String endTime,
            String eventType) {
    }

    public record UpdateCalendarEventRequest(
            @NotBlank String courseCode,
            @NotBlank String semester,
            @NotBlank String professor,
            @NotBlank String eventId,
            @NotBlank String date,
            @NotBlank String startTime,
            @NotBlank String endTime) {
    }
}
