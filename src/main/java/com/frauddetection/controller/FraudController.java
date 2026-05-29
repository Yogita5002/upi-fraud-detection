package com.frauddetection.controller;

import com.frauddetection.model.HistoryRecord;
import com.frauddetection.model.ScreenResponse;
import com.frauddetection.model.Transaction;
import com.frauddetection.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Three endpoints:
 *
 *   POST   /api/v1/screen           — evaluate + persist one transaction
 *   GET    /api/v1/transactions     — history (most-recent first)
 *   DELETE /api/v1/transactions     — clear all history
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class FraudController {

    private final TransactionService service;

    /**
     * Main screening endpoint.
     * Frontend replaces runEngine(tx) with a fetch to this endpoint.
     *
     * Request body  → Transaction (JSON, field names match JS object keys)
     * Response body → ScreenResponse { dbId, score, tier, rules[], ts_display }
     */
    @PostMapping("/screen")
    public ResponseEntity<ScreenResponse> screen(@RequestBody Transaction tx) {
        if (tx.getPayer_vpa() == null || tx.getPayer_vpa().isBlank()
            || tx.getPayee_vpa() == null || tx.getPayee_vpa().isBlank()
            || tx.getAmount() == null || tx.getAmount() <= 0) {
            return ResponseEntity.badRequest().build();
        }
        ScreenResponse result = service.screenTransaction(tx);
        return ResponseEntity.ok(result);
    }

    /**
     * History endpoint — returns all screened transactions for the history drawer.
     * Frontend calls this instead of reading from localStorage.
     *
     * Response body → HistoryRecord[] (matches the shape the JS drawer renders)
     */
    @GetMapping("/transactions")
    public ResponseEntity<List<HistoryRecord>> getHistory() {
        return ResponseEntity.ok(service.getHistory());
    }

    /**
     * Clear all stored history.
     * Called when the user clicks "Clear All" in the history drawer.
     */
    @DeleteMapping("/transactions")
    public ResponseEntity<Map<String, String>> clearHistory() {
        service.clearHistory();
        return ResponseEntity.ok(Map.of("message", "All records cleared"));
    }

    /**
     * Health check — useful to confirm the server is up.
     * GET /api/v1/health → { "status": "UP" }
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
