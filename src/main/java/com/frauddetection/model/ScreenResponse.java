package com.frauddetection.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ScreenResponse {
    private Long dbId;
    private int score;
    private String tier;
    private List<FraudRule> rules;
    private String ts_display;
}
