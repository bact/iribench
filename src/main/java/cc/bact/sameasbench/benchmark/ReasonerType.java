package cc.bact.sameasbench.benchmark;

public enum ReasonerType {
    FULL("Jena full OWL"),
    MINI("Jena OWL Mini"),
    MICRO("Jena OWL Micro"),
    SPDX_CUSTOM("SPDX-optimized");

    private final String label;
    ReasonerType(String label) { this.label = label; }
    public String label() { return label; }
}
