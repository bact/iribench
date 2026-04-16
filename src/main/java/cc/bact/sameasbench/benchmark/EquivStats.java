package cc.bact.sameasbench.benchmark;

public record EquivStats(
    int equivClassPairs,
    int equivPropPairs,
    int sameAsPairs,
    int totalClasses,
    int totalProperties,
    int totalIndividuals
) {}
