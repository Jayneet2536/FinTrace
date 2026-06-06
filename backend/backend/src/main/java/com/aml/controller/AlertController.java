package com.aml.controller;

import com.aml.model.AlertPageRequest;
import com.aml.model.AlertPageResponse;
import com.aml.service.InferenceService;
import com.aml.service.TransactionService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class AlertController {

    private final TransactionService transactionService;
    private final InferenceService inferenceService;

    public AlertController(
            TransactionService transactionService,
            InferenceService inferenceService) {
        this.transactionService = transactionService;
        this.inferenceService   = inferenceService;
    }

    // HTTP fallback for recent fraud alerts
    @GetMapping("/alerts")
    public ResponseEntity<AlertPageResponse> getAlerts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(
                transactionService.getRecentAlerts(page, limit)
        );
    }

    // WebSocket request-response for recent fraud alerts
    @MessageMapping("/alerts.snapshot")
    @SendToUser("/queue/alerts.snapshot")
    public AlertPageResponse getAlertsSnapshot(@Payload AlertPageRequest request) {
        int page = request != null && request.getPage() != null ? request.getPage() : 1;
        int limit = request != null && request.getLimit() != null ? request.getLimit() : 20;
        return transactionService.getRecentAlerts(page, limit);
    }

    // Frontend requests report generation on demand for a selected alert
    @PostMapping("/alerts/{transactionId}/report")
    public ResponseEntity<Map<String, Object>> generateReport(
            @PathVariable String transactionId,
            @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(
                    inferenceService.forwardReportRequest(transactionId, body)
            );
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }
}
