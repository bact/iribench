package cc.bact.sameasbench.benchmark;

import java.util.List;

public final class SparqlQueries {
    private SparqlQueries() {}

    static String prefixesForBase(String base, String tag) {
        return "PREFIX " + tag + "Core:    <" + base + "Core/>\n"
             + "PREFIX " + tag + "Sw:      <" + base + "Software/>\n"
             + "PREFIX " + tag + "SimLic:  <" + base + "SimpleLicensing/>\n"
             + "PREFIX " + tag + "ExpLic:  <" + base + "ExpandedLicensing/>\n"
             + "PREFIX " + tag + "CoreRT:  <" + base + "Core/RelationshipType/>\n";
    }

    static String unionPrefixes(List<String> bases) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bases.size(); i++) {
            String b = bases.get(i);
            String tag = "v" + i;
            sb.append("PREFIX ").append(tag).append("Core:   <").append(b).append("Core/>\n");
            sb.append("PREFIX ").append(tag).append("Sw:     <").append(b).append("Software/>\n");
            sb.append("PREFIX ").append(tag).append("SimLic: <").append(b).append("SimpleLicensing/>\n");
            sb.append("PREFIX ").append(tag).append("ExpLic: <").append(b).append("ExpandedLicensing/>\n");
            sb.append("PREFIX ").append(tag).append("CoreRT: <").append(b).append("Core/RelationshipType/>\n");
        }
        return sb.toString();
    }

    public static String findPackagesDirect(String base) {
        return prefixesForBase(base, "spdx")
            + "SELECT ?pkg ?name ?version ?purl\n"
            + "WHERE {\n"
            + "    ?pkg a spdxSw:Package ;\n"
            + "         spdxCore:name ?name .\n"
            + "    OPTIONAL { ?pkg spdxSw:packageVersion ?version . }\n"
            + "    OPTIONAL { ?pkg spdxSw:packageUrl ?purl . }\n"
            + "}\nORDER BY ?name\n";
    }

    public static String findPackagesUnion(List<String> bases) {
        StringBuilder branches = new StringBuilder();
        for (int i = 0; i < bases.size(); i++) {
            String tag = "v" + i;
            if (i > 0) branches.append("    UNION\n");
            branches.append("    {\n")
                    .append("        ?pkg a ").append(tag).append("Sw:Package ;\n")
                    .append("             ").append(tag).append("Core:name ?name .\n")
                    .append("        OPTIONAL { ?pkg ").append(tag).append("Sw:packageVersion ?version . }\n")
                    .append("        OPTIONAL { ?pkg ").append(tag).append("Sw:packageUrl ?purl . }\n")
                    .append("    }\n");
        }
        return unionPrefixes(bases)
            + "SELECT ?pkg ?name ?version ?purl\nWHERE {\n" + branches + "}\nORDER BY ?name\n";
    }

    public static String licensesDirect(String base) {
        return prefixesForBase(base, "spdx")
            + "SELECT ?pkg ?pkgName ?lic ?licName\n"
            + "WHERE {\n"
            + "    ?rel a spdxCore:Relationship ;\n"
            + "         spdxCore:relationshipType spdxCoreRT:hasConcludedLicense ;\n"
            + "         spdxCore:from ?pkg ;\n"
            + "         spdxCore:to   ?lic .\n"
            + "    ?pkg spdxCore:name ?pkgName .\n"
            + "    ?lic spdxCore:name ?licName .\n"
            + "}\nORDER BY ?pkgName\n";
    }

    public static String licensesUnion(List<String> bases) {
        StringBuilder branches = new StringBuilder();
        for (int i = 0; i < bases.size(); i++) {
            String tag = "v" + i;
            if (i > 0) branches.append("    UNION\n");
            branches.append("    {\n")
                    .append("        ?rel a ").append(tag).append("Core:Relationship ;\n")
                    .append("             ").append(tag).append("Core:relationshipType ").append(tag).append("CoreRT:hasConcludedLicense ;\n")
                    .append("             ").append(tag).append("Core:from ?pkg ;\n")
                    .append("             ").append(tag).append("Core:to   ?lic .\n")
                    .append("        ?pkg ").append(tag).append("Core:name ?pkgName .\n")
                    .append("        ?lic ").append(tag).append("Core:name ?licName .\n")
                    .append("    }\n");
        }
        return unionPrefixes(bases)
            + "SELECT ?pkg ?pkgName ?lic ?licName\nWHERE {\n" + branches + "}\nORDER BY ?pkgName\n";
    }

    public static String depChainDirect(String base) {
        return prefixesForBase(base, "spdx")
            + "SELECT ?from ?mid ?to\n"
            + "WHERE {\n"
            + "    ?r1 a spdxCore:Relationship ;\n"
            + "        spdxCore:relationshipType spdxCoreRT:dependsOn ;\n"
            + "        spdxCore:from ?from ;\n"
            + "        spdxCore:to   ?mid .\n"
            + "    ?r2 a spdxCore:Relationship ;\n"
            + "        spdxCore:relationshipType spdxCoreRT:dependsOn ;\n"
            + "        spdxCore:from ?mid ;\n"
            + "        spdxCore:to   ?to .\n"
            + "}\nLIMIT 200\n";
    }

    public static String depChainUnion(List<String> bases) {
        StringBuilder branches = new StringBuilder();
        for (int i = 0; i < bases.size(); i++) {
            String tag = "v" + i;
            if (i > 0) branches.append("    UNION\n");
            branches.append("    {\n")
                    .append("        ?r1 a ").append(tag).append("Core:Relationship ;\n")
                    .append("            ").append(tag).append("Core:relationshipType ").append(tag).append("CoreRT:dependsOn ;\n")
                    .append("            ").append(tag).append("Core:from ?from ;\n")
                    .append("            ").append(tag).append("Core:to   ?mid .\n")
                    .append("        ?r2 a ").append(tag).append("Core:Relationship ;\n")
                    .append("            ").append(tag).append("Core:relationshipType ").append(tag).append("CoreRT:dependsOn ;\n")
                    .append("            ").append(tag).append("Core:from ?mid ;\n")
                    .append("            ").append(tag).append("Core:to   ?to .\n")
                    .append("    }\n");
        }
        return unionPrefixes(bases)
            + "SELECT ?from ?mid ?to\nWHERE {\n" + branches + "}\nLIMIT 200\n";
    }

    public static String countByTypeDirect(String base) {
        return prefixesForBase(base, "spdx")
            + "SELECT ?type (COUNT(?e) AS ?count)\n"
            + "WHERE {\n"
            + "    ?e a ?type .\n"
            + "    FILTER(STRSTARTS(STR(?type), \"" + base + "\"))\n"
            + "}\nGROUP BY ?type\nORDER BY DESC(?count)\n";
    }

    public static String countByTypeUnion(List<String> bases) {
        StringBuilder filters = new StringBuilder();
        for (int i = 0; i < bases.size(); i++) {
            if (i > 0) filters.append(" || ");
            filters.append("STRSTARTS(STR(?type), \"").append(bases.get(i)).append("\")");
        }
        return "SELECT ?type (COUNT(?e) AS ?count)\n"
            + "WHERE {\n"
            + "    ?e a ?type .\n"
            + "    FILTER(" + filters + ")\n"
            + "}\nGROUP BY ?type\nORDER BY DESC(?count)\n";
    }

    // --- Union variants for Reasoner-style benchmarks ---

    public static String subclassOneHopUnion(List<String> bases) {
        StringBuilder branches = new StringBuilder();
        for (int i = 0; i < bases.size(); i++) {
            String tag = "v" + i;
            if (i > 0) branches.append("    UNION\n");
            branches.append("    { ?x a ").append(tag).append("Core:Artifact . }\n");
        }
        return unionPrefixes(bases) + "SELECT DISTINCT ?x WHERE {\n" + branches + "}\n";
    }

    public static String subclassTwoHopUnion(List<String> bases) {
        StringBuilder branches = new StringBuilder();
        for (int i = 0; i < bases.size(); i++) {
            String tag = "v" + i;
            if (i > 0) branches.append("    UNION\n");
            branches.append("    { ?x a ").append(tag).append("Core:Element . }\n");
        }
        return unionPrefixes(bases) + "SELECT DISTINCT ?x WHERE {\n" + branches + "}\n";
    }

    public static String superclassWithTypeUnion(List<String> bases) {
        StringBuilder branches = new StringBuilder();
        for (int i = 0; i < bases.size(); i++) {
            String tag = "v" + i;
            String base = bases.get(i);
            if (i > 0) branches.append("    UNION\n");
            branches.append("    {\n")
                    .append("        ?x a ").append(tag).append("Core:Element ;\n")
                    .append("           a ?concreteType .\n")
                    .append("        FILTER(?concreteType != ").append(tag).append("Core:Element)\n")
                    .append("        FILTER(STRSTARTS(STR(?concreteType), \"").append(base).append("\"))\n")
                    .append("    }\n");
        }
        return unionPrefixes(bases)
            + "SELECT DISTINCT ?x ?concreteType\nWHERE {\n" + branches + "}\nORDER BY ?concreteType\n";
    }

    public static String domainInferenceUnion(List<String> bases) {
        StringBuilder branches = new StringBuilder();
        for (int i = 0; i < bases.size(); i++) {
            String tag = "v" + i;
            if (i > 0) branches.append("    UNION\n");
            branches.append("    {\n")
                    .append("        ?x ").append(tag).append("Core:from ?target .\n")
                    .append("        ?x a ").append(tag).append("Core:Relationship .\n")
                    .append("    }\n");
        }
        return unionPrefixes(bases) + "SELECT DISTINCT ?x WHERE {\n" + branches + "}\n";
    }

    // --- Reasoner queries (use canonical/shared base) ---

    public static String subclassTwoHop(String base) {
        return prefixesForBase(base, "spdx")
            + "SELECT DISTINCT ?x WHERE { ?x a spdxCore:Element . }\n";
    }

    public static String subclassOneHop(String base) {
        return prefixesForBase(base, "spdx")
            + "SELECT DISTINCT ?x WHERE { ?x a spdxCore:Artifact . }\n";
    }

    public static String superclassWithType(String base) {
        return prefixesForBase(base, "spdx")
            + "SELECT DISTINCT ?x ?concreteType\n"
            + "WHERE {\n"
            + "    ?x a spdxCore:Element ;\n"
            + "       a ?concreteType .\n"
            + "    FILTER(?concreteType != spdxCore:Element)\n"
            + "    FILTER(STRSTARTS(STR(?concreteType), \"" + base + "\"))\n"
            + "}\nORDER BY ?concreteType\n";
    }

    public static String domainInference(String base) {
        return prefixesForBase(base, "spdx")
            + "SELECT DISTINCT ?x\n"
            + "WHERE {\n"
            + "    ?x spdxCore:from ?target .\n"
            + "    ?x a spdxCore:Relationship .\n"
            + "}\n";
    }
}
