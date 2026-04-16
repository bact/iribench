package cc.bact.sameasbench.benchmark;

import cc.bact.sameasbench.Measurement;
import java.util.ArrayList;
import java.util.List;

public class ScenarioResult {
    public String scenarioName;
    public int versionsCount;
    public long dataTriples;
    public long equivTriples;
    public long totalTriples;
    public Measurement buildMeasurement;
    public EquivStats equivStats;
    public List<QueryResult> queries = new ArrayList<>();
    public List<ShaclResult> shacl   = new ArrayList<>();
}
