package cc.bact.iribench.ontology;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL;

import java.util.Map;

public class EquivGraphBuilder {

    /**
     * Build hub-and-spoke OWL equivalence graph: versioned IRIs -> canonical base.
     * Classes    -> owl:equivalentClass (bidirectional)
     * Properties -> owl:equivalentProperty (bidirectional)
     * Individuals -> owl:sameAs (bidirectional)
     */
    public static Model build(Map<String, OntologyVersion> versions, String canonicalBase) {
        Model g = ModelFactory.createDefaultModel();
        for (OntologyVersion ov : versions.values()) {
            String base = ov.baseIri();
            for (String cls : ov.classes()) {
                String local = cls.substring(base.length());
                Resource versioned = g.createResource(cls);
                Resource canonical = g.createResource(canonicalBase + local);
                g.add(versioned, OWL.equivalentClass, canonical);
                g.add(canonical, OWL.equivalentClass, versioned);
            }
            for (String prop : ov.properties()) {
                String local = prop.substring(base.length());
                Property versioned = g.createProperty(prop);
                Property canonical = g.createProperty(canonicalBase + local);
                g.add(versioned, OWL.equivalentProperty, canonical);
                g.add(canonical, OWL.equivalentProperty, versioned);
            }
            for (String ind : ov.individuals()) {
                String local = ind.substring(base.length());
                Resource versioned = g.createResource(ind);
                Resource canonical = g.createResource(canonicalBase + local);
                g.add(versioned, OWL.sameAs, canonical);
                g.add(canonical, OWL.sameAs, versioned);
            }
        }
        return g;
    }
}
