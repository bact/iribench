package cc.bact.iribench.benchmark;

import cc.bact.iribench.Measurement;

public record QueryResult(
    String name,
    String method,
    int resultCount,
    Measurement measurement,
    boolean timedOut,
    String error
) {}
