package com.frauddetection.service;

import com.frauddetection.model.FraudResult;
import com.frauddetection.model.FraudRule;
import com.frauddetection.model.Transaction;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rule engine ported directly from the frontend JS runEngine().
 * All 10 rules, identical thresholds and scoring.
 *
 * In-memory state (velStore, devStore) survives for the JVM lifetime —
 * fine for a hackathon POC; swap for Redis if you want persistence.
 */
@Service
public class FraudEngineService {

    // ── Known trusted merchants (mirrors KNOWN_MERCH in frontend) ────────────
    private static final Set<String> KNOWN_MERCHANTS = Set.of(
        "AMAZON", "FLIPKART", "ZOMATO", "SWIGGY", "NETFLIX",
        "UBER", "OLA", "IRCTC", "MYNTRA", "BIGBASKET",
        "NYKAA", "GPAY", "PHONEPE"
    );

    // ── High-risk MCC codes (mirrors HIGH_RISK_MCC in frontend) ─────────────
    private static final Set<String> HIGH_RISK_MCC = Set.of("4829", "6012", "7995");

    // ── In-memory velocity and device stores ─────────────────────────────────
    // velStore: payerVpa → list of epoch-millis timestamps
    private final Map<String, List<Long>> velStore = new ConcurrentHashMap<>();
    // devStore: set of device IDs seen so far this session
    private final Set<String> devStore = ConcurrentHashMap.newKeySet();

    // ─────────────────────────────────────────────────────────────────────────

    public FraudResult evaluate(Transaction tx) {
        String payerVpa  = nvl(tx.getPayer_vpa());
        String payeeVpa  = nvl(tx.getPayee_vpa());
        double amount    = tx.getAmount() != null ? tx.getAmount() : 0.0;
        String auth      = nvl(tx.getAuth());
        String type      = nvl(tx.getType());
        String mcc       = nvl(tx.getMcc());
        String location  = nvl(tx.getLocation());
        String deviceId  = nvl(tx.getDevice_id(), "X");
        String rooted    = nvl(tx.getRooted());
        String timestamp = nvl(tx.getTimestamp());

        // Parse hour from timestamp
        int hour = parseHour(timestamp);

        // Track velocity before reading it
        trackVel(payerVpa, timestamp);
        long vel = velCount(payerVpa, 5 * 60 * 1000L);

        // Is this device new?
        boolean isNewDev = !devStore.contains(deviceId);
        devStore.add(deviceId);

        List<FraudRule> rules = new ArrayList<>();
        int score = 0;

        // ── R1: Large value transaction ───────────────────────────────────
        boolean r1 = amount >= 10_000;
        int r1s = 0;
        if (r1) {
            if      (amount >= 100_000) r1s = 30;
            else if (amount >= 50_000)  r1s = 22;
            else if (amount >= 25_000)  r1s = 15;
            else                        r1s = 10;
        }
        rules.add(new FraudRule(
            "Large value transaction",
            "Amount ₹" + fmt(amount) + " breaches threshold",
            "≥ ₹10,000",
            r1, r1s
        ));
        score += r1s;

        // ── R2: Off-hours transaction ─────────────────────────────────────
        boolean r2 = hour >= 22 || hour < 6;
        int r2s = r2 ? 20 : 0;
        rules.add(new FraudRule(
            "Off-hours transaction",
            "Initiated at " + String.format("%02d", hour) + ":xx",
            "22:00–05:59 window",
            r2, r2s
        ));
        score += r2s;

        // ── R3: Unverified counterparty ───────────────────────────────────
        boolean r3 = !isKnownMerchant(payeeVpa) && amount > 5_000;
        int r3s = r3 ? 18 : 0;
        rules.add(new FraudRule(
            "Unverified counterparty",
            "Payee not in trusted merchant registry",
            "Unknown payee & amt >₹5,000",
            r3, r3s
        ));
        score += r3s;

        // ── R4: Velocity breach ───────────────────────────────────────────
        boolean r4 = vel >= 3;
        int r4s = r4 ? Math.min(25, (int)(vel * 6)) : 0;
        rules.add(new FraudRule(
            "Velocity breach",
            vel + " transactions from same VPA in 5 min",
            ">3 tx / 5-minute window",
            r4, r4s
        ));
        score += r4s;

        // ── R5: High-risk jurisdiction ────────────────────────────────────
        boolean r5 = location.equals("Unknown") || location.equals("Cross-Border");
        int r5s = r5 ? 14 : 0;
        rules.add(new FraudRule(
            "High-risk jurisdiction",
            "Location: " + location,
            "Unknown or cross-border origin",
            r5, r5s
        ));
        score += r5s;

        // ── R6: High-risk merchant category ──────────────────────────────
        boolean r6 = HIGH_RISK_MCC.contains(mcc);
        int r6s = r6 ? 16 : 0;
        rules.add(new FraudRule(
            "High-risk merchant category",
            "MCC " + (mcc.isEmpty() ? "N/A" : mcc) + " flagged",
            "MCC: 4829, 6012, 7995",
            r6, r6s
        ));
        score += r6s;

        // ── R7: Authentication bypass ─────────────────────────────────────
        boolean r7 = auth.equals("NONE");
        int r7s = r7 ? 20 : 0;
        rules.add(new FraudRule(
            "Authentication bypass",
            "Transaction auth method: " + auth,
            "Non-authenticated transaction",
            r7, r7s
        ));
        score += r7s;

        // ── R8: New device fingerprint ────────────────────────────────────
        boolean r8 = isNewDev && amount > 10_000;
        int r8s = r8 ? 12 : 0;
        rules.add(new FraudRule(
            "New device fingerprint",
            "Unrecognised device ID with high-value tx",
            "First-seen device & amt >₹10,000",
            r8, r8s
        ));
        score += r8s;

        // ── R9: Compromised device ────────────────────────────────────────
        boolean r9 = rooted.equals("Y");
        int r9s = r9 ? 15 : 0;
        rules.add(new FraudRule(
            "Compromised device (rooted)",
            "Device reported as rooted/jailbroken",
            "Rooted or jailbroken = Yes",
            r9, r9s
        ));
        score += r9s;

        // ── R10: Collect request pattern ──────────────────────────────────
        boolean r10 = type.equals("COLLECT");
        int r10s = r10 ? 8 : 0;
        rules.add(new FraudRule(
            "Collect request pattern",
            "Pull-based payment — higher social engineering risk",
            "Transaction type = Collect",
            r10, r10s
        ));
        score += r10s;

        // Cap at 100, assign tier
        score = Math.min(100, score);
        String tier = score < 30 ? "LOW" : score < 60 ? "MEDIUM" : "HIGH";

        return new FraudResult(score, tier, rules);
    }

    // ── Velocity helpers ──────────────────────────────────────────────────────

    private void trackVel(String vpa, String timestamp) {
        long ts = parseMillis(timestamp);
        velStore.computeIfAbsent(vpa, k -> new ArrayList<>()).add(ts);
    }

    private long velCount(String vpa, long windowMs) {
        long now = Instant.now().toEpochMilli();
        List<Long> times = velStore.getOrDefault(vpa, List.of());
        return times.stream().filter(t -> now - t < windowMs).count();
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private boolean isKnownMerchant(String vpa) {
        String upper = vpa.toUpperCase();
        return KNOWN_MERCHANTS.stream().anyMatch(upper::contains);
    }

    private int parseHour(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return 0;
        try {
            // Handles "2026-05-25T02:15" and full ISO-8601
            LocalDateTime dt = LocalDateTime.parse(timestamp.length() > 16
                ? timestamp.substring(0, 16) : timestamp,
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
            return dt.getHour();
        } catch (Exception e) {
            return 0;
        }
    }

    private long parseMillis(String timestamp) {
        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (Exception e) {
            try {
                LocalDateTime dt = LocalDateTime.parse(
                    timestamp.length() > 16 ? timestamp.substring(0, 16) : timestamp,
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
                return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (Exception ex) {
                return Instant.now().toEpochMilli();
            }
        }
    }

    private String fmt(double amount) {
        // Simple Indian number formatting (no commas for POC)
        return String.format("%.0f", amount);
    }

    private String nvl(String val) {
        return val != null ? val.trim() : "";
    }

    private String nvl(String val, String fallback) {
        return (val != null && !val.isBlank()) ? val.trim() : fallback;
    }
}
