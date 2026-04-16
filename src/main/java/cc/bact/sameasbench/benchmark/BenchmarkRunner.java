package cc.bact.sameasbench.benchmark;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.reasoner.Reasoner;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.shacl.*;
import org.apache.jena.shacl.validation.ReportEntry;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import cc.bact.sameasbench.Measurement;
import cc.bact.sameasbench.datagen.GeneratorConfig;
import cc.bact.sameasbench.datagen.SbomGenerator;
import cc.bact.sameasbench.ontology.Constants;
import cc.bact.sameasbench.ontology.EquivGraphBuilder;
import cc.bact.sameasbench.ontology.OntologyVersion;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class BenchmarkRunner {

    public static final long OWLRL_TIMEOUT_MS = 120_000L;

    // -------------------------------------------------------------------
    // Internal: run SPARQL, return row count
    // -------------------------------------------------------------------
    private static int runQuery(Model model, String sparql) {
        try (QueryExecution qe = QueryExecution.create().query(sparql).model(model).build()) {
            ResultSet rs = qe.execSelect();
            int count = 0;
            while (rs.hasNext()) { rs.nextSolution(); count++; }
            return count;
        }
    }

    // -------------------------------------------------------------------
    // Internal: materialize OWL-RL closure
    // Returns (materializedModel, timedOut)
    // -------------------------------------------------------------------
    private static ModelAndTimeout expandOwlRl(Model combinedModel) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Model> future = executor.submit(() -> {
                Reasoner reasoner = ReasonerRegistry.getOWLReasoner();
                InfModel inf = ModelFactory.createInfModel(reasoner, combinedModel);
                Model mat = ModelFactory.createDefaultModel();
                mat.add(inf);
                return mat;
            });
            Model mat = future.get(OWLRL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return new ModelAndTimeout(mat, false);
        } catch (TimeoutException e) {
            return new ModelAndTimeout(combinedModel, true);
        } catch (Exception e) {
            throw new RuntimeException("OWL-RL expansion failed: " + e.getMessage(), e);
        }
    }

    record ModelAndTimeout(Model model, boolean timedOut) {}

    // Result carrier for a single query iteration
    private record QueryIterResult(int count, boolean timedOut) {}

    // Result carrier for a single SHACL iteration
    private record ShaclIterResult(boolean conforms, int violations) {}

    // -------------------------------------------------------------------
    // Internal: bench one query
    // -------------------------------------------------------------------
    private static QueryResult benchQuery(
            Model model, String name, String method, String sparql,
            int repeats, boolean expandOwl) throws Exception {
        List<Measurement> measurements = new ArrayList<>();
        int count = 0;
        boolean timedOut = false;
        for (int i = 0; i < repeats; i++) {
            Measurement.MeasuredResult<QueryIterResult> mr = Measurement.measureWithResult(() -> {
                if (expandOwl) {
                    ModelAndTimeout mat = expandOwlRl(model);
                    int c = mat.timedOut() ? 0 : runQuery(mat.model(), sparql);
                    return new QueryIterResult(c, mat.timedOut());
                } else {
                    return new QueryIterResult(runQuery(model, sparql), false);
                }
            });
            measurements.add(mr.measurement());
            count = mr.value().count();
            timedOut = mr.value().timedOut();
            if (timedOut) break;
        }
        return new QueryResult(name, method, count, Measurement.average(measurements), timedOut);
    }

    // -------------------------------------------------------------------
    // Internal: run SHACL
    // -------------------------------------------------------------------
    private static ShaclResult benchShacl(
            Model dataModel, String name, String shapesTtl,
            String inference, int repeats) throws Exception {
        List<Measurement> measurements = new ArrayList<>();
        boolean conforms = false;
        int violations = 0;
        int targetCount = 0;

        // Compute target count (query data model for instances of each sh:targetClass)
        Model shapesModel = ModelFactory.createDefaultModel();
        RDFDataMgr.read(shapesModel, new ByteArrayInputStream(shapesTtl.getBytes(StandardCharsets.UTF_8)), Lang.TURTLE);
        Property targetClassProp = shapesModel.createProperty("http://www.w3.org/ns/shacl#targetClass");
        Set<String> targetClassUris = new HashSet<>();
        shapesModel.listStatements(null, targetClassProp, (RDFNode) null).forEachRemaining(
            s -> { if (s.getObject().isURIResource()) targetClassUris.add(s.getObject().asResource().getURI()); }
        );
        for (String tc : targetClassUris) {
            String q = "SELECT DISTINCT ?x WHERE { ?x a <" + tc + "> }";
            try (QueryExecution qe = QueryExecution.create().query(q).model(dataModel).build()) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) { rs.nextSolution(); targetCount++; }
            }
        }

        Model queryModel = inference.equals("owlrl") ? expandOwlRl(dataModel).model() : dataModel;
        for (int i = 0; i < repeats; i++) {
            Measurement.MeasuredResult<ShaclIterResult> mr = Measurement.measureWithResult(() -> {
                Model sm2 = ModelFactory.createDefaultModel();
                RDFDataMgr.read(sm2, new ByteArrayInputStream(shapesTtl.getBytes(StandardCharsets.UTF_8)), Lang.TURTLE);
                Shapes shapes = Shapes.parse(sm2);
                ValidationReport report = ShaclValidator.get().validate(shapes, queryModel.getGraph());
                int v = 0;
                for (ReportEntry entry : report.getEntries()) {
                    v++;
                }
                return new ShaclIterResult(report.conforms(), v);
            });
            measurements.add(mr.measurement());
            conforms = mr.value().conforms();
            violations = mr.value().violations();
        }

        return new ShaclResult(name, inference, conforms, violations, targetCount, Measurement.average(measurements));
    }

    // -------------------------------------------------------------------
    // Internal: compute equiv stats
    // -------------------------------------------------------------------
    private static EquivStats computeEquivStats(Model equivG, Map<String, OntologyVersion> versions) {
        int eqCls = 0, eqProp = 0, eqInd = 0;
        StmtIterator it;
        it = equivG.listStatements(null, OWL.equivalentClass, (RDFNode) null);
        while (it.hasNext()) { it.next(); eqCls++; }
        eqCls /= 2;
        it = equivG.listStatements(null, OWL.equivalentProperty, (RDFNode) null);
        while (it.hasNext()) { it.next(); eqProp++; }
        eqProp /= 2;
        it = equivG.listStatements(null, OWL.sameAs, (RDFNode) null);
        while (it.hasNext()) { it.next(); eqInd++; }
        eqInd /= 2;
        int totalCls = versions.values().stream().mapToInt(v -> v.classes().size()).sum();
        int totalProp = versions.values().stream().mapToInt(v -> v.properties().size()).sum();
        int totalInd = versions.values().stream().mapToInt(v -> v.individuals().size()).sum();
        return new EquivStats(eqCls, eqProp, eqInd, totalCls, totalProp, totalInd);
    }

    // -------------------------------------------------------------------
    // Warmup — prime Jena SPARQL engine + OWL reasoner + SHACL
    // -------------------------------------------------------------------
    // Warmup using a realistic SBOM graph so JIT compiles the actual query
    // execution paths before the first timed scenario.  A 1-triple graph
    // is insufficient: Jena's query planner and OWL rule engine only reach
    // steady-state performance once they process schema-like triple patterns.
    public static void warmup(String sharedBase, boolean owlEnabled) throws Exception {
        Model wg = SbomGenerator.generate(
            sharedBase,
            "https://example.org/sbom/warmup/",
            new GeneratorConfig(5, 99)
        );
        // 1. SPARQL — all four query shapes used in the benchmark
        for (String sparql : List.of(
                SparqlQueries.findPackagesDirect(sharedBase),
                SparqlQueries.licensesDirect(sharedBase),
                SparqlQueries.depChainDirect(sharedBase),
                SparqlQueries.countByTypeDirect(sharedBase))) {
            try (QueryExecution qe = QueryExecution.create().query(sparql).model(wg).build()) {
                qe.execSelect().forEachRemaining(s -> {});
            }
        }
        // 2. OWL-RL on representative graph
        if (owlEnabled) {
            Reasoner reasoner = ReasonerRegistry.getOWLReasoner();
            InfModel inf = ModelFactory.createInfModel(reasoner, wg);
            Model mat = ModelFactory.createDefaultModel();
            mat.add(inf);
        }
        // 3. SHACL with representative shapes
        String warmupShapes = ShaclShapes.makeShapes(sharedBase);
        Model sm = ModelFactory.createDefaultModel();
        RDFDataMgr.read(sm, new ByteArrayInputStream(warmupShapes.getBytes(StandardCharsets.UTF_8)), Lang.TURTLE);
        Shapes shapes = Shapes.parse(sm);
        ShaclValidator.get().validate(shapes, wg.getGraph());
    }

    // -------------------------------------------------------------------
    // Scenario 1: Shared namespace
    // -------------------------------------------------------------------
    public static ScenarioResult runSharedNamespace(
            Map<String, OntologyVersion> versions,
            String sharedBase,
            int pkgPerVersion,
            int repeats,
            boolean verbose) throws Exception {

        if (verbose) System.out.println("  Building shared-namespace data graph ...");

        Measurement.MeasuredResult<Model> built = Measurement.measureWithResult(() -> {
            Model dm = ModelFactory.createDefaultModel();
            for (int i = 0; i < versions.size(); i++) {
                // Same doc_namespace + same seed for all iterations.
                // Jena Model is a triple set: identical triples deduplicate,
                // so N iterations collapse to M unique triples — correctly
                // modelling a world where one canonical namespace means one
                // unified dataset regardless of how many ontology versions exist.
                dm.add(SbomGenerator.generate(
                    sharedBase,
                    "https://example.org/sbom/shared/",
                    new GeneratorConfig(pkgPerVersion, 0)
                ));
            }
            return dm;
        });
        Model dataModel = built.value();
        Measurement buildM = built.measurement();

        ScenarioResult result = new ScenarioResult();
        result.scenarioName = "Shared (" + versions.size() + ")";
        result.versionsCount = versions.size();
        result.dataTriples = dataModel.size();
        result.equivTriples = 0;
        result.totalTriples = dataModel.size();
        result.buildMeasurement = buildM;

        List<String[]> queries = List.of(
            new String[]{"Find packages + names",    SparqlQueries.findPackagesDirect(sharedBase)},
            new String[]{"Packages with licenses",   SparqlQueries.licensesDirect(sharedBase)},
            new String[]{"Dependency chain (2-hop)", SparqlQueries.depChainDirect(sharedBase)},
            new String[]{"Count elements by type",   SparqlQueries.countByTypeDirect(sharedBase)}
        );
        for (String[] q : queries)
            result.queries.add(benchQuery(dataModel, q[0], "direct", q[1], repeats, false));

        String shapesTtl = ShaclShapes.makeShapes(sharedBase);
        result.shacl.add(benchShacl(dataModel, "Package + Relationship shapes", shapesTtl, "none", repeats));
        return result;
    }

    // -------------------------------------------------------------------
    // Scenarios 2 & 3: Versioned namespaces
    // -------------------------------------------------------------------
    public static ScenarioResult runVersioned(
            Map<String, OntologyVersion> versions,
            String sharedBase,
            int pkgPerVersion,
            int repeats,
            boolean verbose,
            String scenarioName,
            boolean owlEnabled) throws Exception {

        List<String> versionsList = new ArrayList<>(versions.keySet());
        List<String> bases = new ArrayList<>();
        for (String v : versionsList) bases.add(versions.get(v).baseIri());

        if (verbose) System.out.printf("  Building versioned graph (%d versions) ...%n", versionsList.size());

        record DataAndEquiv(Model dataModel, Model equivModel) {}
        Measurement.MeasuredResult<DataAndEquiv> built = Measurement.measureWithResult(() -> {
            Model dm = ModelFactory.createDefaultModel();
            for (int i = 0; i < versionsList.size(); i++) {
                String version = versionsList.get(i);
                String vbase = bases.get(i);
                dm.add(SbomGenerator.generate(
                    vbase,
                    "https://example.org/sbom/" + version.replace(".", "") + "/v" + i + "/",
                    new GeneratorConfig(pkgPerVersion, i)
                ));
            }
            Model em = ModelFactory.createDefaultModel();
            if (versions.size() > 1) {
                em.add(EquivGraphBuilder.build(versions, sharedBase));
            }
            return new DataAndEquiv(dm, em);
        });
        Model dataModel = built.value().dataModel();
        Model equivModel = built.value().equivModel();
        Measurement buildM = built.measurement();

        Model combinedModel = ModelFactory.createDefaultModel();
        combinedModel.add(dataModel);
        combinedModel.add(equivModel);

        ScenarioResult result = new ScenarioResult();
        result.scenarioName = scenarioName;
        result.versionsCount = versionsList.size();
        result.dataTriples = dataModel.size();
        result.equivTriples = equivModel.size();
        result.totalTriples = dataModel.size() + equivModel.size();
        result.buildMeasurement = buildM;
        result.equivStats = computeEquivStats(equivModel, versions);

        // SPARQL - UNION
        List<String[]> unionQueries = List.of(
            new String[]{"Find packages + names",    SparqlQueries.findPackagesUnion(bases)},
            new String[]{"Packages with licenses",   SparqlQueries.licensesUnion(bases)},
            new String[]{"Dependency chain (2-hop)", SparqlQueries.depChainUnion(bases)},
            new String[]{"Count elements by type",   SparqlQueries.countByTypeUnion(bases)}
        );
        for (String[] q : unionQueries)
            result.queries.add(benchQuery(dataModel, q[0], "union", q[1], repeats, false));

        // SPARQL - OWL-RL
        if (owlEnabled) {
            List<String[]> inferQueries = List.of(
                new String[]{"Find packages + names",    SparqlQueries.findPackagesDirect(sharedBase)},
                new String[]{"Packages with licenses",   SparqlQueries.licensesDirect(sharedBase)},
                new String[]{"Dependency chain (2-hop)", SparqlQueries.depChainDirect(sharedBase)},
                new String[]{"Count elements by type",   SparqlQueries.countByTypeDirect(sharedBase)}
            );
            for (String[] q : inferQueries)
                result.queries.add(benchQuery(combinedModel, q[0], "owlrl+query", q[1], repeats, true));
        }

        // Save shapes to cache for SPDX runs
        boolean isSpdx = bases.stream().anyMatch(b -> b.contains("spdx.org"));
        if (isSpdx) ShaclShapes.saveToCache(versionsList, bases);

        // SHACL - per-version shapes
        String versionedShapesTtl = ShaclShapes.makeShapesMulti(bases);
        result.shacl.add(benchShacl(dataModel,
            "Per-version shapes, no inference (shapes target each versioned IRI)",
            versionedShapesTtl, "none", repeats));

        // SHACL - canonical shapes + OWL-RL
        String canonicalShapesTtl = ShaclShapes.makeShapes(sharedBase);
        result.shacl.add(benchShacl(combinedModel,
            "Canonical shapes + OWL-RL inference",
            canonicalShapesTtl, "owlrl", repeats));

        return result;
    }

    // -------------------------------------------------------------------
    // Scenario 4: Reasoner inference tests
    // -------------------------------------------------------------------
    public static List<ScenarioResult> runReasonerTests(
            Map<String, OntologyVersion> versions,
            String sharedBase,
            int pkgPerVersion,
            int repeats,
            boolean verbose,
            boolean owlEnabled) throws Exception {

        if (versions.isEmpty()) return List.of();
        List<Map.Entry<String, OntologyVersion>> entries = new ArrayList<>(versions.entrySet());
        Map<String, OntologyVersion> twoV = new LinkedHashMap<>();
        int n = Math.min(2, entries.size());
        for (int i = 0; i < n; i++) twoV.put(entries.get(i).getKey(), entries.get(i).getValue());
        OntologyVersion firstOv = twoV.values().iterator().next();
        List<String> vList = new ArrayList<>(twoV.keySet());
        List<String> basesVersioned = new ArrayList<>();
        for (OntologyVersion ov : twoV.values()) basesVersioned.add(ov.baseIri());

        if (verbose) System.out.println("  Building reasoner-test graphs ...");

        record DataAndEquiv(Model dataModel, Model equivModel) {}
        Measurement.MeasuredResult<DataAndEquiv> built = Measurement.measureWithResult(() -> {
            Model dg = ModelFactory.createDefaultModel();
            for (int i = 0; i < vList.size(); i++) {
                dg.add(SbomGenerator.generate(
                    basesVersioned.get(i),
                    "https://example.org/sbom/reasoner/" + vList.get(i) + "/",
                    new GeneratorConfig(pkgPerVersion, i + 200)
                ));
            }
            Model eg = ModelFactory.createDefaultModel();
            eg.add(EquivGraphBuilder.build(twoV, sharedBase));
            return new DataAndEquiv(dg, eg);
        });
        Model dataG = built.value().dataModel();
        Model equivG = built.value().equivModel();
        Measurement buildM = built.measurement();
        Model ontoG = firstOv.graph();

        String[] testQueryNames = {
            "subClassOf 2-hop: ?x a Core/Element",
            "subClassOf 1-hop: ?x a Core/Artifact",
            "Element + leaf type (transitivity)",
            "rdfs:domain: Core/from->Relationship"
        };
        String[] testQuerySparqls = {
            SparqlQueries.superclassAll(sharedBase),
            SparqlQueries.subclassTwoHop(sharedBase),
            SparqlQueries.superclassWithType(sharedBase),
            SparqlQueries.domainInference(sharedBase)
        };

        long ontoSize = ontoG.size();
        long equivSize = equivG.size();

        Model dataAndOnto = ModelFactory.createDefaultModel();
        dataAndOnto.add(dataG); dataAndOnto.add(ontoG);
        Model dataAndEquiv = ModelFactory.createDefaultModel();
        dataAndEquiv.add(dataG); dataAndEquiv.add(equivG);
        Model full = ModelFactory.createDefaultModel();
        full.add(dataG); full.add(equivG); full.add(ontoG);

        String[][] scenarioConfigs = {
            {"Reasoner - versioned data only (baseline)", "direct"},
            {"Reasoner - versioned + subClassOf ontology (no equiv)", "owlrl+onto"},
            {"Reasoner - versioned + equiv only (no subClassOf)", "owlrl+equiv"},
            {"Reasoner - versioned + equiv + subClassOf (full chain)", "owlrl+full"}
        };
        Model[] scenarioModels = {dataG, dataAndOnto, dataAndEquiv, full};
        long[] extraTriples = {0L, ontoSize, equivSize, equivSize + ontoSize};

        List<ScenarioResult> results = new ArrayList<>();
        for (int s = 0; s < 4; s++) {
            ScenarioResult sr = new ScenarioResult();
            sr.scenarioName = scenarioConfigs[s][0];
            sr.versionsCount = twoV.size();
            sr.dataTriples = dataG.size();
            sr.equivTriples = extraTriples[s];
            sr.totalTriples = dataG.size() + extraTriples[s];
            sr.buildMeasurement = buildM;
            String method = scenarioConfigs[s][1];
            boolean expand = !method.equals("direct");
            if (owlEnabled) {
                Model model = scenarioModels[s];
                for (int qi = 0; qi < testQueryNames.length; qi++) {
                    sr.queries.add(benchQuery(model, testQueryNames[qi], method, testQuerySparqls[qi], repeats, expand));
                }
            }
            results.add(sr);
        }
        return results;
    }

    // -------------------------------------------------------------------
    // Top-level entry point
    // -------------------------------------------------------------------
    public static List<ScenarioResult> runAll(
            Map<String, OntologyVersion> versions,
            String sharedBase,
            int pkgPerVersion,
            int repeats,
            boolean verbose,
            boolean owlEnabled) throws Exception {

        List<String> allV = new ArrayList<>(versions.keySet());
        List<ScenarioResult> results = new ArrayList<>();

        if (verbose) System.out.println("  Warming up JVM + Jena engines ...");
        warmup(sharedBase, owlEnabled);

        // Scenario 1
        if (verbose) System.out.printf("%n[Scenario 1] Shared namespace (%d versions, canonical IRI)%n", allV.size());
        results.add(runSharedNamespace(versions, sharedBase, pkgPerVersion, repeats, verbose));

        if (allV.size() < 2) {
            if (verbose) System.out.println("  (only 1 version loaded - skipping versioned scenarios)");
            return results;
        }

        // Scenario 1b: Versioned 1-version — equal data volume to Shared Namespace,
        // so readers can compare all three strategies on the same dataset size.
        Map<String, OntologyVersion> oneV = new LinkedHashMap<>();
        oneV.put(allV.get(0), versions.get(allV.get(0)));
        if (verbose) System.out.printf("%n[Scenario 1b] Versioned - 1 version (%s)%n", allV.get(0));
        results.add(runVersioned(oneV, sharedBase, pkgPerVersion, repeats, verbose,
            "Versioned (1)", owlEnabled));

        // Scenario 2: first 2 versions
        Map<String, OntologyVersion> twoV = new LinkedHashMap<>();
        twoV.put(allV.get(0), versions.get(allV.get(0)));
        twoV.put(allV.get(1), versions.get(allV.get(1)));
        if (verbose) System.out.printf("%n[Scenario 2] Versioned - 2 versions (%s, %s)%n", allV.get(0), allV.get(1));
        results.add(runVersioned(twoV, sharedBase, pkgPerVersion, repeats, verbose, "Versioned (2)", owlEnabled));

        // Scenario 3: all versions (only if >2)
        if (allV.size() > 2) {
            if (verbose) System.out.printf("%n[Scenario 3] Versioned - %d versions%n", allV.size());
            results.add(runVersioned(versions, sharedBase, pkgPerVersion, repeats, verbose,
                "Versioned (" + allV.size() + ")", owlEnabled));
        }

        // Scenario 4: reasoner tests
        if (verbose) System.out.println("\n[Scenario 4] Reasoner inference tests (equiv + subClassOf chain)");
        results.addAll(runReasonerTests(versions, sharedBase, pkgPerVersion, repeats, verbose, owlEnabled));

        return results;
    }
}
