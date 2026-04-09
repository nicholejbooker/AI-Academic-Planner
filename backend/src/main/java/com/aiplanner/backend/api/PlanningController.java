package com.aiplanner.backend.api;

import com.aiplanner.backend.service.PlanningService;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/plans")
public class PlanningController {

    private final PlanningService planningService;

    public PlanningController(PlanningService planningService) {
        this.planningService = planningService;
    }

    @GetMapping("/weekly")
    public Map<String, Object> weeklyPlan(@RequestParam(required = false) String courseId) {
        return planningService.generateWeeklyPlan(courseId);
    }
}
