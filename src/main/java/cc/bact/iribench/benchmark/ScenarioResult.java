package cc.bact.iribench.benchmark;

import cc.bact.iribench.Measurement;
import java.util.ArrayList;
import java.util.List;

public class ScenarioResult {
    public String scenarioName;
    public int versionsCount;
    public long dataTriples;
    public long equivTriples;
    public long totalTriples;
    public Measurement buildMeasurement;
    public Measurement expansionMeasurement;
    public EquivStats equivStats;
    public List<QueryResult> queries = new ArrayList<>();
    public List<ShaclResult> shacl   = new ArrayList<>();
}
