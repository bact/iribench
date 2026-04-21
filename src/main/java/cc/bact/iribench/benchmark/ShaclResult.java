package cc.bact.iribench.benchmark;

import cc.bact.iribench.Measurement;

public record ShaclResult(
    String name,
    String inference,
    boolean conforms,
    int violationCount,
    int targetCount,
    Measurement measurement,
    String error
) {}
