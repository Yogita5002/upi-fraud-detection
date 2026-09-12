package com.frauddetection.service;

import com.frauddetection.model.FraudResult;
import com.frauddetection.model.FraudRule;
import com.frauddetection.model.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the existing FraudEngineService scoring behaviour.
 * Each test uses a fresh engine instance so in-memory velocity/device state
 * cannot leak between cases.
 */
class FraudEngineServiceTest {

    private static final String LARGE_VALUE = "Large value transaction";
    private static final String OFF_HOURS = "Off-hours transaction";
    private static final String AUTH_BYPASS = "Authentication bypass";
    private static final String COMPROMISED_DEVICE = "Compromised device (rooted)";
    private static final String COLLECT_REQUEST = "Collect request pattern";
    private static final String UNVERIFIED_COUNTERPARTY = "Unverified counterparty";
    private static final String HIGH_RISK_JURISDICTION = "High-risk jurisdiction";
    private static final String HIGH_RISK_MCC = "High-risk merchant category";
    private static final String NEW_DEVICE = "New device fingerprint";
    private static final String VELOCITY_BREACH = "Velocity breach";

    private FraudEngineService engine;

    @BeforeEach
    void setUp() {
        engine = new FraudEngineService();
    }

    @Test
    void normalLowRiskTransaction_hasZeroScoreAndLowTier() {
        Transaction tx = lowRiskTransaction("dev-low-risk");

        FraudResult result = engine.evaluate(tx);

        assertEquals(0, result.getScore());
        assertEquals("LOW", result.getTier());
        assertTrue(result.getRules().stream().noneMatch(FraudRule::isTriggered));
    }

    @Test
    void highValueTransaction_triggersLargeValueRule() {
        String deviceId = "dev-large-amount";
        rememberDevice(deviceId);

        Transaction tx = lowRiskTransaction(deviceId);
        tx.setAmount(100_000.0);

        FraudResult result = engine.evaluate(tx);
        FraudRule rule = rule(result, LARGE_VALUE);

        assertTrue(rule.isTriggered());
        assertEquals(30, rule.getScore());
        assertEquals(30, result.getScore());
        assertEquals("MEDIUM", result.getTier());
    }

    @Test
    void offHoursTransaction_triggersOffHoursRule() {
        Transaction tx = lowRiskTransaction("dev-off-hours");
        tx.setTimestamp("2026-05-25T02:15");

        FraudResult result = engine.evaluate(tx);
        FraudRule rule = rule(result, OFF_HOURS);

        assertTrue(rule.isTriggered());
        assertEquals(20, rule.getScore());
        assertEquals(20, result.getScore());
        assertEquals("LOW", result.getTier());
    }

    @Test
    void missingAuthentication_triggersAuthenticationBypassRule() {
        Transaction tx = lowRiskTransaction("dev-auth-none");
        tx.setAuth("NONE");

        FraudResult result = engine.evaluate(tx);
        FraudRule rule = rule(result, AUTH_BYPASS);

        assertTrue(rule.isTriggered());
        assertEquals(20, rule.getScore());
        assertEquals(20, result.getScore());
        assertEquals("LOW", result.getTier());
    }

    @Test
    void rootedDevice_triggersCompromisedDeviceRule() {
        Transaction tx = lowRiskTransaction("dev-rooted");
        tx.setRooted("Y");

        FraudResult result = engine.evaluate(tx);
        FraudRule rule = rule(result, COMPROMISED_DEVICE);

        assertTrue(rule.isTriggered());
        assertEquals(15, rule.getScore());
        assertEquals(15, result.getScore());
        assertEquals("LOW", result.getTier());
    }

    @Test
    void collectTypeTransaction_triggersCollectRequestRule() {
        Transaction tx = lowRiskTransaction("dev-collect");
        tx.setType("COLLECT");

        FraudResult result = engine.evaluate(tx);
        FraudRule rule = rule(result, COLLECT_REQUEST);

        assertTrue(rule.isTriggered());
        assertEquals(8, rule.getScore());
        assertEquals(8, result.getScore());
        assertEquals("LOW", result.getTier());
    }

    @Test
    void multipleTriggeredRules_addTheirScoresTogether() {
        Transaction tx = lowRiskTransaction("dev-multi");
        tx.setTimestamp("2026-05-25T23:00");
        tx.setAuth("NONE");
        tx.setRooted("Y");
        tx.setType("COLLECT");

        FraudResult result = engine.evaluate(tx);

        assertTrue(rule(result, OFF_HOURS).isTriggered());
        assertTrue(rule(result, AUTH_BYPASS).isTriggered());
        assertTrue(rule(result, COMPROMISED_DEVICE).isTriggered());
        assertTrue(rule(result, COLLECT_REQUEST).isTriggered());
        assertEquals(20 + 20 + 15 + 8, result.getScore());
        assertEquals("HIGH", result.getTier());
    }

    @Test
    void scoreBelow30_isClassifiedAsLow() {
        Transaction tx = lowRiskTransaction("dev-tier-low");
        tx.setRooted("Y");

        FraudResult result = engine.evaluate(tx);

        assertEquals(15, result.getScore());
        assertTrue(result.getScore() < 30);
        assertEquals("LOW", result.getTier());
    }

    @Test
    void scoreAt30_isClassifiedAsMedium() {
        String deviceId = "dev-tier-medium";
        rememberDevice(deviceId);

        Transaction tx = lowRiskTransaction(deviceId);
        tx.setAmount(100_000.0);

        FraudResult result = engine.evaluate(tx);

        assertEquals(30, result.getScore());
        assertEquals("MEDIUM", result.getTier());
    }

    @Test
    void scoreAt60_isClassifiedAsHigh() {
        String deviceId = "dev-tier-high";
        rememberDevice(deviceId);

        // 30 (₹1,00,000) + 18 (unknown payee) + 12 is avoided because the device is known.
        // Add off-hours (20) → 68, which is above the HIGH threshold of 60.
        // Use 50,000 (22) + unknown payee (18) + off-hours (20) = 60 exactly.
        Transaction tx = lowRiskTransaction(deviceId);
        tx.setAmount(50_000.0);
        tx.setPayee_vpa("unknown-person@upi");
        tx.setTimestamp("2026-05-25T22:00");

        FraudResult result = engine.evaluate(tx);

        assertEquals(22 + 18 + 20, result.getScore());
        assertEquals(60, result.getScore());
        assertEquals("HIGH", result.getTier());
    }

    @Test
    void fraudScore_isCappedAt100() {
        String deviceId = "dev-cap";
        // Do not pre-register the device: a new high-value device also adds R8 (12).

        Transaction tx = new Transaction();
        tx.setPayer_vpa("user@okhdfc");
        tx.setPayee_vpa("unknown-person@upi");
        tx.setAmount(100_000.0);
        tx.setAuth("NONE");
        tx.setType("COLLECT");
        tx.setMcc("7995");
        tx.setLocation("Unknown");
        tx.setDevice_id(deviceId);
        tx.setRooted("Y");
        tx.setTimestamp("2026-05-25T02:15");

        FraudResult result = engine.evaluate(tx);

        int uncapped =
            30  // large value ≥ ₹1,00,000
            + 20  // off-hours
            + 18  // unverified counterparty
            + 14  // high-risk jurisdiction
            + 16  // high-risk MCC
            + 20  // auth NONE
            + 12  // new device + amount > ₹10,000
            + 15  // rooted
            + 8;  // COLLECT
        assertTrue(uncapped > 100);
        assertTrue(rule(result, LARGE_VALUE).isTriggered());
        assertTrue(rule(result, OFF_HOURS).isTriggered());
        assertTrue(rule(result, UNVERIFIED_COUNTERPARTY).isTriggered());
        assertTrue(rule(result, HIGH_RISK_JURISDICTION).isTriggered());
        assertTrue(rule(result, HIGH_RISK_MCC).isTriggered());
        assertTrue(rule(result, AUTH_BYPASS).isTriggered());
        assertTrue(rule(result, NEW_DEVICE).isTriggered());
        assertTrue(rule(result, COMPROMISED_DEVICE).isTriggered());
        assertTrue(rule(result, COLLECT_REQUEST).isTriggered());
        assertEquals(100, result.getScore());
        assertEquals("HIGH", result.getTier());
    }

    @Test
    void threeTransactionsFromSameVpaWithinFiveMinutes_triggerVelocityRule() {
        String vpa = "velocity-burst@okhdfc";
        String deviceId = "dev-vel-burst";

        FraudResult first = engine.evaluate(txAt(vpa, deviceId, "2026-05-25T14:00"));
        FraudResult second = engine.evaluate(txAt(vpa, deviceId, "2026-05-25T14:02"));
        FraudResult third = engine.evaluate(txAt(vpa, deviceId, "2026-05-25T14:04"));

        assertFalse(rule(first, VELOCITY_BREACH).isTriggered());
        assertFalse(rule(second, VELOCITY_BREACH).isTriggered());
        assertTrue(rule(third, VELOCITY_BREACH).isTriggered());
        assertEquals(Math.min(25, 3 * 6), rule(third, VELOCITY_BREACH).getScore());
        assertEquals(18, third.getScore());
    }

    @Test
    void transactionsFromSameVpaMoreThanFiveMinutesApart_doNotTriggerVelocityRule() {
        String vpa = "velocity-sparse@okhdfc";
        String deviceId = "dev-vel-sparse";

        FraudResult first = engine.evaluate(txAt(vpa, deviceId, "2026-05-25T14:00"));
        FraudResult second = engine.evaluate(txAt(vpa, deviceId, "2026-05-25T14:06"));
        FraudResult third = engine.evaluate(txAt(vpa, deviceId, "2026-05-25T14:12"));

        assertFalse(rule(first, VELOCITY_BREACH).isTriggered());
        assertFalse(rule(second, VELOCITY_BREACH).isTriggered());
        assertFalse(rule(third, VELOCITY_BREACH).isTriggered());
        assertEquals(0, third.getScore());
    }

    @Test
    void transactionsFromDifferentVpas_doNotCountTowardEachOthersVelocity() {
        String otherVpa = "other-person@okhdfc";
        String targetVpa = "target-person@okhdfc";

        engine.evaluate(txAt(otherVpa, "dev-vel-other-1", "2026-05-25T14:00"));
        engine.evaluate(txAt(otherVpa, "dev-vel-other-2", "2026-05-25T14:02"));
        engine.evaluate(txAt(otherVpa, "dev-vel-other-3", "2026-05-25T14:04"));

        FraudResult target = engine.evaluate(txAt(targetVpa, "dev-vel-target", "2026-05-25T14:04"));

        assertFalse(rule(target, VELOCITY_BREACH).isTriggered());
        assertEquals(0, target.getScore());
    }

    @Test
    void largeValueRule_isNotTriggeredBelowTenThousand() {
        Transaction tx = lowRiskTransaction("dev-below-large");
        tx.setAmount(9_999.0);

        FraudResult result = engine.evaluate(tx);

        assertFalse(rule(result, LARGE_VALUE).isTriggered());
        assertEquals(0, rule(result, LARGE_VALUE).getScore());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * A daytime PAY to a trusted merchant with PIN auth — triggers none of the
     * current rules when amount stays at ₹500.
     */
    private Transaction lowRiskTransaction(String deviceId) {
        return txAt("user@okhdfc", deviceId, "2026-05-25T14:00");
    }

    private Transaction txAt(String payerVpa, String deviceId, String timestamp) {
        Transaction tx = new Transaction();
        tx.setPayer_vpa(payerVpa);
        tx.setPayee_vpa("amazon@upi");
        tx.setAmount(500.0);
        tx.setAuth("PIN");
        tx.setType("PAY");
        tx.setMcc("5411");
        tx.setLocation("Mumbai");
        tx.setDevice_id(deviceId);
        tx.setRooted("N");
        tx.setTimestamp(timestamp);
        return tx;
    }

    /**
     * First-seen devices add the "New device fingerprint" rule when amount &gt; ₹10,000.
     * Evaluating a small transaction first records the device without changing score.
     */
    private void rememberDevice(String deviceId) {
        engine.evaluate(lowRiskTransaction(deviceId));
    }

    private FraudRule rule(FraudResult result, String name) {
        return result.getRules().stream()
            .filter(r -> name.equals(r.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing rule: " + name));
    }
}
