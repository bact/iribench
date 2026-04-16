package cc.bact.sameasbench.benchmark;

import cc.bact.sameasbench.ontology.Constants;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public final class ShaclShapes {
    private ShaclShapes() {}

    public static String makeShapes(String base) {
        return "@prefix sh:       <http://www.w3.org/ns/shacl#> .\n"
             + "@prefix xsd:      <http://www.w3.org/2001/XMLSchema#> .\n"
             + "@prefix spdxCore: <" + base + "Core/> .\n"
             + "@prefix spdxSw:   <" + base + "Software/> .\n\n"
             + "<" + base + "shapes/PackageShape>\n"
             + "    a sh:NodeShape ;\n"
             + "    sh:targetClass spdxSw:Package ;\n"
             + "    sh:property [ sh:path spdxCore:name ; sh:minCount 1 ; sh:datatype xsd:string ] ;\n"
             + "    sh:property [ sh:path spdxCore:creationInfo ; sh:minCount 1 ] ;\n"
             + "    sh:property [ sh:path spdxSw:packageVersion ; sh:maxCount 1 ] .\n\n"
             + "<" + base + "shapes/RelationshipShape>\n"
             + "    a sh:NodeShape ;\n"
             + "    sh:targetClass spdxCore:Relationship ;\n"
             + "    sh:property [ sh:path spdxCore:from ; sh:minCount 1 ] ;\n"
             + "    sh:property [ sh:path spdxCore:to ; sh:minCount 1 ] ;\n"
             + "    sh:property [ sh:path spdxCore:relationshipType ; sh:minCount 1 ] .\n";
    }

    public static String makeShapesMulti(List<String> bases) {
        StringBuilder sb = new StringBuilder();
        sb.append("@prefix sh:  <http://www.w3.org/ns/shacl#> .\n");
        sb.append("@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n");
        for (int i = 0; i < bases.size(); i++) {
            sb.append("@prefix v").append(i).append("Core: <").append(bases.get(i)).append("Core/> .\n");
            sb.append("@prefix v").append(i).append("Sw:   <").append(bases.get(i)).append("Software/> .\n");
        }
        sb.append("\n");
        for (int i = 0; i < bases.size(); i++) {
            String base = bases.get(i);
            String tag = "v" + i;
            sb.append("<").append(base).append("shapes/PackageShape>\n")
              .append("    a sh:NodeShape ;\n")
              .append("    sh:targetClass ").append(tag).append("Sw:Package ;\n")
              .append("    sh:property [ sh:path ").append(tag).append("Core:name ; sh:minCount 1 ; sh:datatype xsd:string ] ;\n")
              .append("    sh:property [ sh:path ").append(tag).append("Core:creationInfo ; sh:minCount 1 ] ;\n")
              .append("    sh:property [ sh:path ").append(tag).append("Sw:packageVersion ; sh:maxCount 1 ] .\n\n")
              .append("<").append(base).append("shapes/RelationshipShape>\n")
              .append("    a sh:NodeShape ;\n")
              .append("    sh:targetClass ").append(tag).append("Core:Relationship ;\n")
              .append("    sh:property [ sh:path ").append(tag).append("Core:from ; sh:minCount 1 ] ;\n")
              .append("    sh:property [ sh:path ").append(tag).append("Core:to ; sh:minCount 1 ] ;\n")
              .append("    sh:property [ sh:path ").append(tag).append("Core:relationshipType ; sh:minCount 1 ] .\n\n");
        }
        return sb.toString();
    }

    public static String saveToCache(List<String> versions, List<String> bases) throws IOException {
        Files.createDirectories(Constants.CACHE_DIR);
        String tag = String.join("-", versions);
        java.nio.file.Path path = Constants.CACHE_DIR.resolve("spdx-shapes-" + tag + ".ttl");
        Files.writeString(path, makeShapesMulti(bases));
        return path.toString();
    }
}
