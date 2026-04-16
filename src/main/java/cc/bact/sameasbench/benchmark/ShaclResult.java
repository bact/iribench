package cc.bact.sameasbench.benchmark;

import cc.bact.sameasbench.Measurement;

public record ShaclResult(
    String name,
    String inference,
    boolean conforms,
    int violationCount,
    int targetCount,
    Measurement measurement
) {}
