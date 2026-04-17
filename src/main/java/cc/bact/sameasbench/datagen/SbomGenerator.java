package cc.bact.sameasbench.datagen;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.util.*;

/**
 * Synthetic SBOM generator.
 *
 * <p>Uses {@link DeterministicRng} (xorshift32) so generated graphs are
 * byte-identical to those produced by the Python {@code generate_sbom()}
 * function in {@code data_gen.py} for the same parameters.
 */
public class SbomGenerator {

    private static final List<String> SPDX_LICENSE_IDS = List.of(
        "MIT", "Apache-2.0", "GPL-2.0-only", "GPL-3.0-or-later",
        "BSD-2-Clause", "BSD-3-Clause", "LGPL-2.1-only", "MPL-2.0",
        "ISC", "CC0-1.0", "CDDL-1.0", "EPL-2.0"
    );

    private static final List<String> PACKAGE_NAMES = List.of(
        "openssl", "zlib", "libpng", "libjpeg", "curl", "sqlite", "libxml2",
        "boost", "protobuf", "grpc", "abseil", "googletest", "nlohmann-json",
        "fmt", "spdlog", "catch2", "gflags", "glog", "leveldb", "rocksdb",
        "redis", "memcached", "nginx", "apache-httpd", "haproxy",
        "python", "numpy", "scipy", "pandas", "requests", "flask", "django",
        "fastapi", "sqlalchemy", "pydantic", "click", "rich", "typer"
    );

    // Local paths relative to base IRI — keep in sync with Python data_gen.py
    private static final String CLS_SPDX_DOC     = "Core/SpdxDocument";
    private static final String CLS_RELATIONSHIP  = "Core/Relationship";
    private static final String CLS_CREATION_INFO = "Core/CreationInfo";
    private static final String CLS_TOOL          = "Core/Tool";
    private static final String P_NAME            = "Core/name";
    private static final String P_ELEMENT         = "Core/element";
    private static final String P_ROOT_ELEMENT    = "Core/rootElement";
    private static final String P_CREATION_INFO   = "Core/creationInfo";
    private static final String P_CREATED         = "Core/created";
    private static final String P_CREATED_BY      = "Core/createdBy";
    private static final String P_FROM            = "Core/from";
    private static final String P_TO              = "Core/to";
    private static final String P_REL_TYPE        = "Core/relationshipType";
    private static final String P_SPEC_VERSION    = "Core/specVersion";
    private static final String RT_DESCRIBES      = "Core/RelationshipType/describes";
    private static final String RT_DEPENDS_ON     = "Core/RelationshipType/dependsOn";
    private static final String RT_CONTAINS       = "Core/RelationshipType/contains";
    private static final String RT_HAS_CONC_LIC   = "Core/RelationshipType/hasConcludedLicense";
    private static final String RT_HAS_DECL_LIC   = "Core/RelationshipType/hasDeclaredLicense";
    private static final String CLS_SW_PACKAGE    = "Software/Package";
    private static final String CLS_SW_FILE       = "Software/File";
    private static final String CLS_SW_SNIPPET    = "Software/Snippet";
    private static final String P_PKG_VERSION     = "Software/packageVersion";
    private static final String P_DOWNLOAD_LOC    = "Software/downloadLocation";
    private static final String P_PKG_URL         = "Software/packageUrl";
    private static final String P_PRIMARY_PURPOSE = "Software/primaryPurpose";
    private static final String P_COPYRIGHT_TEXT  = "Software/copyrightText";
    private static final String PURPOSE_LIBRARY   = "Software/SoftwarePurpose/library";
    private static final String P_LIC_TEXT        = "SimpleLicensing/licenseText";
    private static final String CLS_LISTED_LIC    = "ExpandedLicensing/ListedLicense";
    private static final String P_IS_OSI_APPROVED = "ExpandedLicensing/isOsiApproved";

    private static final Set<String> OSI_APPROVED = Set.of("MIT", "Apache-2.0", "GPL-2.0-only");

    // -----------------------------------------------------------------------

    private static Resource term(Model g, String baseIri, String local) {
        return g.createResource(baseIri + local);
    }

    private static Resource inst(Model g, String ns, String local) {
        return g.createResource(ns + local);
    }

    private static Property prop(Model g, String baseIri, String local) {
        return g.createProperty(baseIri + local);
    }

    /** Add a Relationship node and link it to the document element list. */
    private static Resource addRel(
            Model g, String baseIri, String docNs, Resource doc,
            String relId, Resource from, Resource to, String relType, Resource ci) {
        Resource rel = inst(g, docNs, "rel-" + relId);
        g.add(rel, RDF.type,                        term(g, baseIri, CLS_RELATIONSHIP));
        g.add(rel, prop(g, baseIri, P_FROM),        from);
        g.add(rel, prop(g, baseIri, P_TO),          to);
        g.add(rel, prop(g, baseIri, P_REL_TYPE),    term(g, baseIri, relType));
        g.add(rel, prop(g, baseIri, P_CREATION_INFO), ci);
        g.add(doc, prop(g, baseIri, P_ELEMENT),     rel);
        return rel;
    }

    // -----------------------------------------------------------------------

    public static Model generate(String baseIri, String docNamespace, GeneratorConfig config) {
        Model g = ModelFactory.createDefaultModel();
        // Deterministic xorshift32 — identical to Python _DeterministicRng(seed)
        DeterministicRng rng = new DeterministicRng(config.seed());

        // ---- CreationInfo --------------------------------------------------
        Resource ci   = inst(g, docNamespace, "creation-info");
        Resource tool = inst(g, docNamespace, "tool-sameas-bench");
        g.add(tool, RDF.type,                        term(g, baseIri, CLS_TOOL));
        g.add(tool, prop(g, baseIri, P_NAME),        g.createLiteral("sameas-bench/1.0"));
        g.add(ci,   RDF.type,                        term(g, baseIri, CLS_CREATION_INFO));
        g.add(ci,   prop(g, baseIri, P_CREATED),     g.createTypedLiteral("2024-01-01T00:00:00Z", XSDDatatype.XSDdateTimeStamp));
        g.add(ci,   prop(g, baseIri, P_CREATED_BY),  tool);

        // ---- SpdxDocument --------------------------------------------------
        Resource doc = inst(g, docNamespace, "document");
        g.add(doc, RDF.type,                          term(g, baseIri, CLS_SPDX_DOC));
        g.add(doc, prop(g, baseIri, P_NAME),          g.createLiteral("example-sbom"));
        g.add(doc, prop(g, baseIri, P_CREATION_INFO), ci);
        g.add(doc, prop(g, baseIri, P_SPEC_VERSION),  g.createLiteral("3.0"));

        // ---- License pool --------------------------------------------------
        // sample(list, k) = Fisher-Yates shuffle copy then take first k
        // Matches Python: rng.sample(_SPDX_LICENSE_IDS, min(6, len(...)))
        List<Resource> licenseIris = new ArrayList<>();
        if (config.includeLicensing()) {
            List<String> selectedLics = rng.sample(new ArrayList<>(SPDX_LICENSE_IDS),
                                                   Math.min(6, SPDX_LICENSE_IDS.size()));
            for (String licId : selectedLics) {
                String safeId = licId.toLowerCase().replace("-", "").replace(".", "");
                Resource lic = inst(g, docNamespace, "license-" + safeId);
                g.add(lic, RDF.type,                          term(g, baseIri, CLS_LISTED_LIC));
                g.add(lic, prop(g, baseIri, P_NAME),          g.createLiteral(licId));
                g.add(lic, prop(g, baseIri, P_LIC_TEXT),      g.createLiteral("License text for " + licId + "."));
                g.add(lic, prop(g, baseIri, P_IS_OSI_APPROVED), g.createTypedLiteral(OSI_APPROVED.contains(licId)));
                g.add(lic, prop(g, baseIri, P_CREATION_INFO), ci);
                g.add(doc, prop(g, baseIri, P_ELEMENT),       lic);
                licenseIris.add(lic);
            }
        }

        // ---- Packages ------------------------------------------------------
        // choices(list, k) = k independent draws with replacement
        // Matches Python: rng.choices(_PACKAGE_NAMES, k=config.num_packages)
        List<String> pkgNames = rng.choices(new ArrayList<>(PACKAGE_NAMES), config.numPackages());
        List<Resource> pkgIris = new ArrayList<>();

        for (int i = 0; i < pkgNames.size(); i++) {
            String pkgName = pkgNames.get(i);
            // nextInt(5)+1 matches Python rng.randint(1,5)  (inclusive both ends)
            // nextInt(10)   matches Python rng.randint(0,9)
            String ver = (rng.nextInt(5) + 1) + "." + rng.nextInt(10) + "." + rng.nextInt(10);
            String purl = "pkg:generic/" + pkgName + "@" + ver;
            Resource pkg = inst(g, docNamespace, String.format("package-%04d", i));
            g.add(pkg, RDF.type,                            term(g, baseIri, CLS_SW_PACKAGE));
            g.add(pkg, prop(g, baseIri, P_NAME),            g.createLiteral(pkgName));
            g.add(pkg, prop(g, baseIri, P_CREATION_INFO),   ci);
            g.add(pkg, prop(g, baseIri, P_PKG_VERSION),     g.createLiteral(ver));
            g.add(pkg, prop(g, baseIri, P_PKG_URL),         g.createLiteral(purl));
            g.add(pkg, prop(g, baseIri, P_DOWNLOAD_LOC),    g.createLiteral("https://example.com/releases/" + pkgName + "/" + ver + ".tar.gz"));
            g.add(pkg, prop(g, baseIri, P_PRIMARY_PURPOSE), term(g, baseIri, PURPOSE_LIBRARY));
            g.add(pkg, prop(g, baseIri, P_COPYRIGHT_TEXT),  g.createLiteral("Copyright 2024 " + pkgName + " authors"));
            g.add(doc, prop(g, baseIri, P_ELEMENT),         pkg);
            pkgIris.add(pkg);
        }
        if (!pkgIris.isEmpty())
            g.add(doc, prop(g, baseIri, P_ROOT_ELEMENT), pkgIris.get(0));

        // ---- Files ---------------------------------------------------------
        List<Resource[]> filePkgPairs = new ArrayList<>();
        for (int i = 0; i < pkgIris.size(); i++) {
            Resource pkg = pkgIris.get(i);
            for (int j = 0; j < config.numFilesPerPackage(); j++) {
                String pkgLocal = pkgIris.get(i).getLocalName(); // e.g. "package-0000"
                Resource f = inst(g, docNamespace, String.format("file-%04d-%02d", i, j));
                g.add(f, RDF.type,                          term(g, baseIri, CLS_SW_FILE));
                g.add(f, prop(g, baseIri, P_NAME),          g.createLiteral("/usr/lib/pkgs/" + pkgLocal + "/file-" + String.format("%02d", j) + ".so"));
                g.add(f, prop(g, baseIri, P_CREATION_INFO), ci);
                g.add(doc, prop(g, baseIri, P_ELEMENT),     f);
                filePkgPairs.add(new Resource[]{pkg, f});
            }
        }

        // ---- Snippets ------------------------------------------------------
        if (config.includeSnippets()) {
            int numSnippets = Math.min(config.numPackages() / 5 + 1, 8);
            for (int i = 0; i < numSnippets; i++) {
                Resource snip = inst(g, docNamespace, String.format("snippet-%04d", i));
                g.add(snip, RDF.type,                          term(g, baseIri, CLS_SW_SNIPPET));
                g.add(snip, prop(g, baseIri, P_NAME),          g.createLiteral("snippet-" + i));
                g.add(snip, prop(g, baseIri, P_CREATION_INFO), ci);
                g.add(doc,  prop(g, baseIri, P_ELEMENT),       snip);
            }
        }

        // ---- Relationships: DESCRIBES doc -> root package ------------------
        if (!pkgIris.isEmpty())
            addRel(g, baseIri, docNamespace, doc, "describes-root", doc, pkgIris.get(0), RT_DESCRIBES, ci);

        // ---- Relationships: DEPENDS_ON chains ------------------------------
        // 1. Guaranteed 2-hop chain for benchmarking if we have enough packages
        if (pkgIris.size() >= 3) {
            addRel(g, baseIri, docNamespace, doc, "dep-fixed-1", pkgIris.get(0), pkgIris.get(1), RT_DEPENDS_ON, ci);
            addRel(g, baseIri, docNamespace, doc, "dep-fixed-2", pkgIris.get(1), pkgIris.get(2), RT_DEPENDS_ON, ci);
        }

        // 2. Random noise
        // Always consume 2 RNG calls per attempt regardless of self-loop skip.
        // Matches Python behaviour exactly.
        int numDeps = Math.min(config.numPackages() * 2, Math.max(pkgIris.size() - 1, 0));
        for (int k = 0; k < numDeps; k++) {
            Resource src = rng.choice(pkgIris);
            Resource tgt = rng.choice(pkgIris);
            if (!src.equals(tgt))
                addRel(g, baseIri, docNamespace, doc, String.format("dep-%04d", k), src, tgt, RT_DEPENDS_ON, ci);
        }

        // ---- Relationships: CONTAINS package -> file -----------------------
        // Sequential index matches Python: enumerate(file_pkg_pairs) with f"cont-{idx:04d}"
        for (int idx = 0; idx < filePkgPairs.size(); idx++) {
            Resource[] pair = filePkgPairs.get(idx);
            addRel(g, baseIri, docNamespace, doc, String.format("cont-%04d", idx), pair[0], pair[1], RT_CONTAINS, ci);
        }

        // ---- Relationships: hasConcludedLicense + hasDeclaredLicense -------
        if (!licenseIris.isEmpty()) {
            for (int i = 0; i < pkgIris.size(); i++) {
                Resource lic = rng.choice(licenseIris);
                addRel(g, baseIri, docNamespace, doc, String.format("conclicn-%04d", i), pkgIris.get(i), lic, RT_HAS_CONC_LIC, ci);
                addRel(g, baseIri, docNamespace, doc, String.format("declicn-%04d",  i), pkgIris.get(i), lic, RT_HAS_DECL_LIC, ci);
            }
        }

        return g;
    }
}
