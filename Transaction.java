package com.frauddetection.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Mirrors the transaction object the frontend sends.
 * Field names match the JS object keys exactly so Jackson deserialises
 * them without any custom mapping.
 */
@Entity
@Table(name = "transactions")
@Data
@NoArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Identifiers ─────────────────────────────────────
    private String ref;
    private String channel;

    // ── Parties ─────────────────────────────────────────
    private String payer_vpa;
    private String payer_bank;
    private String payee_vpa;
    private String payee_bank;
    private String mcc;

    @Column(name = "tx_type")   // 'type' is a reserved word in some DBs
    private String type;

    // ── Transaction details ──────────────────────────────
    private Double amount;
    private String currency;
    private String auth;
    private String timestamp;   // kept as String — frontend sends ISO-8601 string
    private String location;

    // ── Device & network ────────────────────────────────
    private String device_id;
    private String ip;
    private String devtype;
    private String rooted;

    @Column(length = 1000)
    private String remarks;

    private String screened_at;

    // ── Fraud result (stored alongside the transaction) ──
    private Integer riskScore;
    private String riskTier;            // LOW | MEDIUM | HIGH

    @Column(length = 4000)
    private String rulesJson;           // serialised rules array

    // ── Metadata ─────────────────────────────────────────
    private Instant savedAt = Instant.now();
}
