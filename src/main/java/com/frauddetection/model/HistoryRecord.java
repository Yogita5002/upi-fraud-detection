package com.frauddetection.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class HistoryRecord {

    private Long id;
    private TransactionDto transaction;
    private ResultDto result;
    private String display;
    private String savedAt;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TransactionDto {
        private String ref;
        private String channel;
        private String payer_vpa;
        private String payer_bank;
        private String payee_vpa;
        private String payee_bank;
        private String mcc;
        private String type;
        private Double amount;
        private String currency;
        private String auth;
        private String timestamp;
        private String location;
        private String device_id;
        private String ip;
        private String devtype;
        private String rooted;
        private String remarks;
        private String screened_at;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ResultDto {
        private int score;
        private String tier;
        private List<FraudRule> rules;
    }
}
