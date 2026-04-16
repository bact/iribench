package cc.bact.sameasbench.benchmark;

import cc.bact.sameasbench.Measurement;

public record QueryResult(
    String name,
    String method,
    int resultCount,
    Measurement measurement,
    boolean timedOut
) {}
