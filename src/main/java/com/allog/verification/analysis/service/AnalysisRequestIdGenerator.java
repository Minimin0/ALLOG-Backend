package com.allog.verification.analysis.service;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AnalysisRequestIdGenerator {

    public UUID generate() {
        return UUID.randomUUID();
    }
}
