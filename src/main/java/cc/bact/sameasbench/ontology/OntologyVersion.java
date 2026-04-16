package cc.bact.sameasbench.ontology;

import org.apache.jena.rdf.model.Model;
import java.util.List;

public record OntologyVersion(
    String version,
    String baseIri,
    Model graph,
    List<String> classes,      // URIs
    List<String> properties,   // URIs
    List<String> individuals,  // URIs
    String source,             // "downloaded" | "cached" | "simulated" | "toy"
    String provenance
) {}
