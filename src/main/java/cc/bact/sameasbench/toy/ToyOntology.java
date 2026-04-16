package cc.bact.sameasbench.toy;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.*;
import org.apache.jena.vocabulary.OWL2;
import cc.bact.sameasbench.ontology.OntologyVersion;

import java.util.*;

public class ToyOntology {

    public static final String TOY_SHARED_BASE = "https://example.org/onto/shared/terms/";

    private static final List<String> CLASSES = List.of(
        "Core/Element", "Core/Artifact", "Core/Document", "Core/Relationship",
        "Core/CreationInfo", "Core/SpdxDocument", "Software/Package", "Software/File",
        "Licensing/License"
    );
    private static final List<String> PROPERTIES = List.of(
        "Core/name", "Core/element", "Core/from", "Core/to", "Core/relationshipType",
        "Core/creationInfo", "Core/created", "Software/packageVersion",
        "Software/downloadLocation", "SimpleLicensing/licenseText", "ExpandedLicensing/licenseId"
    );
    private static final List<String> INDIVIDUALS = List.of(
        "Core/RelationshipType/contains", "Core/RelationshipType/dependsOn",
        "Core/RelationshipType/hasConcludedLicense",
        "Software/PurposeType/library", "Software/PurposeType/application"
    );

    public static OntologyVersion makeVersion(String versionTag) {
        String baseIri = "https://example.org/onto/" + versionTag + "/terms/";
        Model g = buildOntologyGraph(baseIri);
        List<String> clsUris  = CLASSES.stream().map(c -> baseIri + c).toList();
        List<String> propUris = PROPERTIES.stream().map(p -> baseIri + p).toList();
        List<String> indUris  = INDIVIDUALS.stream().map(i -> baseIri + i).toList();
        return new OntologyVersion(versionTag, baseIri, g, clsUris, propUris, indUris, "toy",
            "in-memory toy ontology (base: " + baseIri + ")");
    }

    public static Map<String, OntologyVersion> makeVersions(int n) {
        Map<String, OntologyVersion> result = new LinkedHashMap<>();
        for (int i = 1; i <= n; i++) {
            String tag = "v" + i;
            result.put(tag, makeVersion(tag));
        }
        return result;
    }

    private static Resource term(Model g, String baseIri, String local) {
        return g.createResource(baseIri + local);
    }

    private static Model buildOntologyGraph(String baseIri) {
        Model g = ModelFactory.createDefaultModel();

        for (String cls : CLASSES) {
            g.add(term(g, baseIri, cls), RDF.type, OWL.Class);
            g.add(term(g, baseIri, cls), RDFS.label, g.createLiteral(cls.substring(cls.lastIndexOf('/') + 1)));
        }
        // first 3 = object properties, rest = datatype
        for (int i = 0; i < PROPERTIES.size(); i++) {
            Resource pt = i < 3 ? OWL.ObjectProperty : OWL.DatatypeProperty;
            g.add(term(g, baseIri, PROPERTIES.get(i)), RDF.type, pt);
        }
        for (String ind : INDIVIDUALS) {
            String parent = ind.substring(0, ind.lastIndexOf('/'));
            g.add(term(g, baseIri, ind), RDF.type, OWL2.NamedIndividual);
            g.add(term(g, baseIri, ind), RDF.type, term(g, baseIri, parent));
        }

        // Class hierarchy
        g.add(term(g, baseIri, "Core/Artifact"),     RDFS.subClassOf, term(g, baseIri, "Core/Element"));
        g.add(term(g, baseIri, "Core/Document"),     RDFS.subClassOf, term(g, baseIri, "Core/Element"));
        g.add(term(g, baseIri, "Core/Relationship"), RDFS.subClassOf, term(g, baseIri, "Core/Element"));
        g.add(term(g, baseIri, "Core/CreationInfo"), RDFS.subClassOf, term(g, baseIri, "Core/Element"));
        g.add(term(g, baseIri, "Core/SpdxDocument"), RDFS.subClassOf, term(g, baseIri, "Core/Document"));
        g.add(term(g, baseIri, "Licensing/License"), RDFS.subClassOf, term(g, baseIri, "Core/Element"));
        g.add(term(g, baseIri, "Software/Package"),  RDFS.subClassOf, term(g, baseIri, "Core/Artifact"));
        g.add(term(g, baseIri, "Software/File"),     RDFS.subClassOf, term(g, baseIri, "Core/Artifact"));

        // Domain
        g.add(term(g, baseIri, "Core/from"), RDFS.domain, term(g, baseIri, "Core/Relationship"));
        g.add(term(g, baseIri, "Core/to"),   RDFS.domain, term(g, baseIri, "Core/Relationship"));

        return g;
    }
}
