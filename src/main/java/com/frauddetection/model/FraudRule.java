package com.frauddetection.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FraudRule {
    private String name;
    private String detail;
    private String threshold;
    private boolean triggered;
    private int score;
}
