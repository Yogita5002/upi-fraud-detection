package com.frauddetection.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.model.*;
import com.frauddetection.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final FraudEngineService engine;
    private final TransactionRepository repo;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter TS_DISPLAY =
        DateTimeFormatter.ofPattern("hh:mm:ss a").withZone(ZoneId.of("Asia/Kolkata"));

    /**
     * Core flow:
     *  1. Run fraud rules
     *  2. Persist transaction + result to H2
     *  3. Return ScreenResponse (result + DB id + display timestamp)
     */
    public ScreenResponse screenTransaction(Transaction tx) {
        // 1. Evaluate
        FraudResult result = engine.evaluate(tx);

        // 2. Persist
        tx.setRiskScore(result.getScore());
        tx.setRiskTier(result.getTier());
        tx.setRulesJson(toJson(result.getRules()));
        tx.setSavedAt(Instant.now());

        Transaction saved = repo.save(tx);
        log.info("Saved transaction {} → tier={} score={}", tx.getRef(), result.getTier(), result.getScore());

        // 3. Build response
        String display = TS_DISPLAY.format(saved.getSavedAt());

        return new ScreenResponse(
            saved.getId(),
            result.getScore(),
            result.getTier(),
            result.getRules(),
            display
        );
    }

    /**
     * All stored transactions, most-recent first,
     * mapped into the shape the frontend history drawer expects.
     */
    public List<HistoryRecord> getHistory() {
        return repo.findAllByOrderBySavedAtDesc()
            .stream()
            .map(this::toHistoryRecord)
            .collect(Collectors.toList());
    }

    public void clearHistory() {
        repo.deleteAll();
        log.info("All transaction history cleared");
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private HistoryRecord toHistoryRecord(Transaction t) {
        HistoryRecord.TransactionDto txDto = new HistoryRecord.TransactionDto(
            t.getRef(), t.getChannel(),
            t.getPayer_vpa(), t.getPayer_bank(),
            t.getPayee_vpa(), t.getPayee_bank(),
            t.getMcc(), t.getType(),
            t.getAmount(), t.getCurrency(),
            t.getAuth(), t.getTimestamp(),
            t.getLocation(), t.getDevice_id(),
            t.getIp(), t.getDevtype(),
            t.getRooted(), t.getRemarks(),
            t.getScreened_at()
        );

        List<FraudRule> rules = fromJson(t.getRulesJson());

        HistoryRecord.ResultDto resultDto = new HistoryRecord.ResultDto(
            t.getRiskScore(), t.getRiskTier(), rules
        );

        String display  = TS_DISPLAY.format(t.getSavedAt());
        String savedAt  = t.getSavedAt().toString();

        return new HistoryRecord(t.getId(), txDto, resultDto, display, savedAt);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    private List<FraudRule> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, FraudRule.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
