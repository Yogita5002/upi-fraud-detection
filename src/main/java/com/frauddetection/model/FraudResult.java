package com.frauddetection.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class FraudResult {
    private int score;
    private String tier;
    private List<FraudRule> rules;
}
