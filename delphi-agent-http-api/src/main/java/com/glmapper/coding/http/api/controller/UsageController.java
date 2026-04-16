package com.glmapper.coding.http.api.controller;

import com.glmapper.coding.core.mongo.UsageMetricsDocument;
import com.glmapper.coding.core.mongo.UsageMetricsRepository;
import com.glmapper.coding.core.tenant.UsageMeteringService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageMetricsRepository usageMetricsRepository;
    private final UsageMeteringService usageMeteringService;

    public UsageController(UsageMetricsRepository usageMetricsRepository,
                           UsageMeteringService usageMeteringService) {
        this.usageMetricsRepository = usageMetricsRepository;
        this.usageMeteringService = usageMeteringService;
    }

    @GetMapping
    public List<UsageMetricsDocument> queryUsage(
            @RequestParam String namespace,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {

        if (from == null) {
            from = LocalDate.now().minusDays(30);
        }
        if (to == null) {
            to = LocalDate.now();
        }
        return usageMetricsRepository.findByNamespaceAndDateBetweenOrderByDateDesc(namespace, from, to);
    }

    @GetMapping("/today")
    public UsageMetricsDocument todayUsage(@RequestParam String namespace) {
        return usageMeteringService.getTodayUsage(namespace);
    }
}
