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
import cc.bact.sameasbench.Measurement;
import cc.bact.sameasbench.datagen.GeneratorConfig;
import cc.bact.sameasbench.datagen.SbomGenerator;
import cc.bact.sameasbench.ontology.EquivGraphBuilder;
import cc.bact.sameasbench.ontology.OntologyVersion;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class BenchmarkRunner {

    // No longer using fixed timeout since backward chaining handles inference on demand

    // -------------------------------------------------------------------
    // Internal: run SPARQL, return row count
    // -------------------------------------------------------------------
    private static int runQuery(Model model, String sparql) {
        try (QueryExecution qe = QueryExecution.create().query(sparql).model(model).build()) {
            ResultSet rs = qe.execSelect();
            int count = 0;
            while (rs.hasNext()) {
                rs.nextSolution();
                count++;
            }
            return count;
        }
    }

    // -------------------------------------------------------------------
    // Internal: configure OWL-RL backward chaining
    // Returns (infModel, timedOut=false)
    // -------------------------------------------------------------------
    private static ModelAndTimeout expandOwlRl(Model combinedModel) {
        Reasoner reasoner = ReasonerRegistry.getOWLReasoner();
        InfModel inf = ModelFactory.createInfModel(reasoner, combinedModel);
        return new ModelAndTimeout(inf, false);
    }

    record ModelAndTimeout(Model model, boolean timedOut) {
    }



    // Result carrier for a single SHACL iteration
    private record ShaclIterResult(boolean conforms, int violations) {
    }

    private static String getHeapConfig() {
        long max = Runtime.getRuntime().maxMemory();
        return (max / (1024 * 1024)) + " MiB";
    }

    // -------------------------------------------------------------------
    // Internal: bench one query
    // -------------------------------------------------------------------
    private static QueryResult benchQuery(Model model, String name, String method, String sparql,
            int repeats, boolean expandOwl, boolean verbose) {
        Model targetModel = null;
        try {
            targetModel = model;
            if (expandOwl) {
                targetModel = expandOwlRl(model).model();
            }

            // Cold start protection: run the query once and discard the result.
            // This primes the SPARQL engine and the reasoner for this specific query.
            runQuery(targetModel, sparql);

            List<Measurement> measurements = new ArrayList<>();
            int count = 0;
            for (int i = 0; i < repeats; i++) {
                final Model finalModel = targetModel;
                Measurement.MeasuredResult<Integer> mr =
                        Measurement.measureWithResult(() -> runQuery(finalModel, sparql));
                if (verbose) {
                    System.out.print(".");
                    System.out.flush();
                }
                measurements.add(mr.measurement());
                count = mr.value();
            }
            return new QueryResult(name, method, count, Measurement.average(measurements), false,
                    null);
        } catch (Throwable t) {
            if (verbose) {
                System.out.print(" [ERROR: " + t.getClass().getSimpleName() + "] ");
                System.out.flush();
            }
            if (t instanceof OutOfMemoryError) {
                System.gc(); // Try to recover
                return new QueryResult(name, method, 0, new Measurement(0, 0), false,
                        "OOM: " + getHeapConfig());
            }
            return new QueryResult(name, method, 0, new Measurement(0, 0), false, t.toString());
        } finally {
            if (expandOwl && targetModel != null && targetModel != model) {
                targetModel.close();
            }
        }
    }

    // -------------------------------------------------------------------
    // Internal: run SHACL
    // -------------------------------------------------------------------
    private static ShaclResult benchShacl(Model dataModel, String name, String shapesTtl,
            String inference, int repeats, boolean verbose) {
        try {
            List<Measurement> measurements = new ArrayList<>();
            boolean conforms = false;
            int violations = 0;
            int targetCount = 0;

            // Compute target count (query data model for instances of each sh:targetClass)
            Model shapesModel = ModelFactory.createDefaultModel();
            RDFDataMgr.read(shapesModel,
                    new ByteArrayInputStream(shapesTtl.getBytes(StandardCharsets.UTF_8)),
                    Lang.TURTLE);
            Property targetClassProp =
                    shapesModel.createProperty("http://www.w3.org/ns/shacl#targetClass");
            Set<String> targetClassUris = new HashSet<>();
            shapesModel.listStatements(null, targetClassProp, (RDFNode) null)
                    .forEachRemaining(s -> {
                        if (s.getObject().isURIResource())
                            targetClassUris.add(s.getObject().asResource().getURI());
                    });
            for (String tc : targetClassUris) {
                String q = "SELECT DISTINCT ?x WHERE { ?x a <" + tc + "> }";
                try (QueryExecution qe =
                        QueryExecution.create().query(q).model(dataModel).build()) {
                    ResultSet rs = qe.execSelect();
                    while (rs.hasNext()) {
                        rs.nextSolution();
                        targetCount++;
                    }
                }
            }

            Model queryModel = null;
            try {
                queryModel = inference.equals("owlrl") ? expandOwlRl(dataModel).model() : dataModel;

                // Parse shapes once outside the measurement loop
                Model sm = ModelFactory.createDefaultModel();
                RDFDataMgr.read(sm,
                        new ByteArrayInputStream(shapesTtl.getBytes(StandardCharsets.UTF_8)),
                        Lang.TURTLE);
                Shapes shapes = Shapes.parse(sm);

                // Cold start protection: run validation once and discard
                ShaclValidator.get().validate(shapes, queryModel.getGraph());

                for (int i = 0; i < repeats; i++) {
                    Measurement.MeasuredResult<ShaclIterResult> mr =
                            Measurement.measureWithResult(() -> {
                                ValidationReport report = ShaclValidator.get().validate(shapes,
                                        queryModel.getGraph());
                                int v = report.getEntries().size();
                                return new ShaclIterResult(report.conforms(), v);
                            });
                    if (verbose) {
                        System.out.print(".");
                        System.out.flush();
                    }
                    measurements.add(mr.measurement());
                    conforms = mr.value().conforms();
                    violations = mr.value().violations();
                }

                return new ShaclResult(name, inference, conforms, violations, targetCount,
                        Measurement.average(measurements), null);
            } catch (Throwable t) {
                if (verbose) {
                    System.out.print(" [ERROR: " + t.getClass().getSimpleName() + "] ");
                    System.out.flush();
                }
                if (t instanceof OutOfMemoryError) {
                    System.gc();
                    return new ShaclResult(name, inference, false, 0, 0, new Measurement(0, 0),
                            "OOM: " + getHeapConfig());
                }
                return new ShaclResult(name, inference, false, 0, 0, new Measurement(0, 0),
                        t.toString());
            } finally {
                // Close the inference model if one was created
                if (inference.equals("owlrl") && queryModel != null && queryModel != dataModel) {
                    queryModel.close();
                }
            }
        } catch (Throwable t) {
            if (verbose) {
                System.out.print(" [ERROR: " + t.getClass().getSimpleName() + "] ");
                System.out.flush();
            }
            if (t instanceof OutOfMemoryError) {
                System.gc();
                return new ShaclResult(name, inference, false, 0, 0, new Measurement(0, 0),
                        "OOM: " + getHeapConfig());
            }
            return new ShaclResult(name, inference, false, 0, 0, new Measurement(0, 0),
                    t.toString());
        }
    }

    // -------------------------------------------------------------------
    // Internal: compute equiv stats
    // -------------------------------------------------------------------
    private static EquivStats computeEquivStats(Model equivG,
            Map<String, OntologyVersion> versions) {
        int eqCls = 0, eqProp = 0, eqInd = 0;
        StmtIterator it;
        it = equivG.listStatements(null, OWL.equivalentClass, (RDFNode) null);
        while (it.hasNext()) {
            it.next();
            eqCls++;
        }
        eqCls /= 2;
        it = equivG.listStatements(null, OWL.equivalentProperty, (RDFNode) null);
        while (it.hasNext()) {
            it.next();
            eqProp++;
        }
        eqProp /= 2;
        it = equivG.listStatements(null, OWL.sameAs, (RDFNode) null);
        while (it.hasNext()) {
            it.next();
            eqInd++;
        }
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
    // execution paths before the first timed scenario. A 1-triple graph
    // is insufficient: Jena's query planner and OWL rule engine only reach
    // steady-state performance once they process schema-like triple patterns.
    public static void warmup(String sharedBase, boolean owlEnabled) throws Exception {
        Model wg = SbomGenerator.generate(sharedBase, "https://example.org/sbom/warmup/",
                new GeneratorConfig(5, 99));
        // 1. SPARQL — all query shapes used in the benchmark
        List<String> warmupQueries =
                new ArrayList<>(List.of(SparqlQueries.findPackagesDirect(sharedBase),
                        SparqlQueries.licensesDirect(sharedBase),
                        SparqlQueries.depChainDirect(sharedBase),
                        SparqlQueries.countByTypeDirect(sharedBase)));
        if (owlEnabled) {
            warmupQueries.addAll(List.of(SparqlQueries.subclassTwoHop(sharedBase),
                    SparqlQueries.superclassAll(sharedBase),
                    SparqlQueries.superclassWithType(sharedBase),
                    SparqlQueries.domainInference(sharedBase)));
        }

        for (String sparql : warmupQueries) {
            try (QueryExecution qe = QueryExecution.create().query(sparql).model(wg).build()) {
                qe.execSelect().forEachRemaining(s -> {
                });
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
        RDFDataMgr.read(sm, new ByteArrayInputStream(warmupShapes.getBytes(StandardCharsets.UTF_8)),
                Lang.TURTLE);
        Shapes shapes = Shapes.parse(sm);
        ShaclValidator.get().validate(shapes, wg.getGraph());
    }

    // -------------------------------------------------------------------
    // Scenario 1: Shared namespace
    // -------------------------------------------------------------------
    private static Model makeCanonicalOntology(OntologyVersion ov, String canonicalBase) {
        Model canonicalModel = ModelFactory.createDefaultModel();
        String oldBase = ov.baseIri();
        StmtIterator iter = ov.graph().listStatements();
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            Resource s = stmt.getSubject();
            Property p = stmt.getPredicate();
            RDFNode o = stmt.getObject();

            Resource newS = s.isURIResource() && s.getURI().startsWith(oldBase) ? canonicalModel
                    .createResource(canonicalBase + s.getURI().substring(oldBase.length())) : s;
            Property newP =
                    p.getURI().startsWith(oldBase)
                            ? canonicalModel.createProperty(
                                    canonicalBase + p.getURI().substring(oldBase.length()))
                            : p;
            RDFNode newO = o;
            if (o.isURIResource() && o.asResource().getURI().startsWith(oldBase)) {
                newO = canonicalModel.createResource(
                        canonicalBase + o.asResource().getURI().substring(oldBase.length()));
            }
            canonicalModel.add(newS, newP, newO);
        }
        return canonicalModel;
    }

    public static ScenarioResult runSharedNamespace(Map<String, OntologyVersion> versions,
            String sharedBase, int pkgPerVersion, int repeats, boolean verbose, boolean owlEnabled)
            throws Exception {

        if (verbose)
            System.out.println("  Building shared-namespace data graph ...");

        Measurement.MeasuredResult<Model> built = Measurement.measureWithResult(() -> {
            Model dm = ModelFactory.createDefaultModel();
            for (int i = 0; i < versions.size(); i++) {
                // Same doc_namespace + same seed for all iterations.
                // Jena Model is a triple set: identical triples deduplicate,
                // so N iterations collapse to M unique triples — correctly
                // modelling a world where one canonical namespace means one
                // unified dataset regardless of how many ontology versions exist.
                dm.add(SbomGenerator.generate(sharedBase, "https://example.org/sbom/shared/",
                        new GeneratorConfig(pkgPerVersion, 0)));
            }
            return dm;
        });
        Model dataModel = built.value();
        Measurement buildM = built.measurement();

        OntologyVersion firstOv = versions.values().iterator().next();
        Model canonicalOntoG = makeCanonicalOntology(firstOv, sharedBase);

        Model combinedModel = ModelFactory.createDefaultModel();
        combinedModel.add(dataModel);
        combinedModel.add(canonicalOntoG);

        ScenarioResult result = new ScenarioResult();
        result.scenarioName = "Shared (" + versions.size() + ")";
        result.versionsCount = versions.size();
        result.dataTriples = dataModel.size();
        result.equivTriples = 0;
        result.totalTriples = dataModel.size();
        result.buildMeasurement = buildM;

        List<String[]> queries = List.of(
                new String[] {"Find packages + names",
                        SparqlQueries.findPackagesDirect(sharedBase)},
                new String[] {"Packages with licenses", SparqlQueries.licensesDirect(sharedBase)},
                new String[] {"Dependency chain (2-hop)", SparqlQueries.depChainDirect(sharedBase)},
                new String[] {"Count elements by type",
                        SparqlQueries.countByTypeDirect(sharedBase)},
                new String[] {"subClassOf 1-hop: ?x a Core/Artifact",
                        SparqlQueries.subclassTwoHop(sharedBase)},
                new String[] {"subClassOf 2-hop: ?x a Core/Element",
                        SparqlQueries.superclassAll(sharedBase)},
                new String[] {"Element + leaf type (transitivity)",
                        SparqlQueries.superclassWithType(sharedBase)},
                new String[] {"rdfs:domain: Core/from->Relationship",
                        SparqlQueries.domainInference(sharedBase)});

        int totalTasks = queries.size() + (owlEnabled ? queries.size() : 0) + 1;
        int currentTask = 1;

        for (String[] q : queries) {
            if (verbose) {
                System.out.printf("    [%d/%d] %s ", currentTask++, totalTasks, q[0]);
                System.out.flush();
            }
            result.queries
                    .add(benchQuery(dataModel, q[0], "direct", q[1], repeats, false, verbose));
            if (verbose)
                System.out.println();
        }

        if (owlEnabled) {
            for (String[] q : queries) {
                if (verbose) {
                    System.out.printf("    [%d/%d] %s (OWL-RL) ", currentTask++, totalTasks, q[0]);
                    System.out.flush();
                }
                result.queries.add(benchQuery(combinedModel, q[0], "owlrl+query", q[1], repeats,
                        true, verbose));
                if (verbose)
                    System.out.println();
            }
        }

        String shapesTtl = ShaclShapes.makeShapes(sharedBase);
        if (verbose) {
            System.out.printf("    [%d/%d] SHACL (Package + Relationship shapes) ", currentTask++,
                    totalTasks);
            System.out.flush();
        }
        result.shacl.add(benchShacl(dataModel, "Package + Relationship shapes", shapesTtl, "none",
                repeats, verbose));
        if (verbose)
            System.out.println();
        return result;
    }

    // -------------------------------------------------------------------
    // Scenarios 2 & 3: Versioned namespaces
    // -------------------------------------------------------------------
    public static ScenarioResult runVersioned(Map<String, OntologyVersion> versions,
            String sharedBase, int pkgPerVersion, int repeats, boolean verbose, String scenarioName,
            boolean owlEnabled) throws Exception {

        List<String> versionsList = new ArrayList<>(versions.keySet());
        List<String> bases = new ArrayList<>();
        for (String v : versionsList)
            bases.add(versions.get(v).baseIri());

        if (verbose)
            System.out.printf("  Building versioned graph (%d versions) ...%n",
                    versionsList.size());

        record DataAndEquiv(Model dataModel, Model equivModel) {
        }
        Measurement.MeasuredResult<DataAndEquiv> built = Measurement.measureWithResult(() -> {
            Model dm = ModelFactory.createDefaultModel();
            for (int i = 0; i < versionsList.size(); i++) {
                String version = versionsList.get(i);
                String vbase = bases.get(i);
                dm.add(SbomGenerator.generate(vbase,
                        "https://example.org/sbom/" + version.replace(".", "") + "/v" + i + "/",
                        new GeneratorConfig(pkgPerVersion, i)));
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
        for (OntologyVersion ov : versions.values()) {
            combinedModel.add(ov.graph());
        }

        ScenarioResult result = new ScenarioResult();
        result.scenarioName = scenarioName;
        result.versionsCount = versionsList.size();
        result.dataTriples = dataModel.size();
        result.equivTriples = equivModel.size();
        result.totalTriples = dataModel.size() + equivModel.size();
        result.buildMeasurement = buildM;
        result.equivStats = computeEquivStats(equivModel, versions);

        if (versionsList.size() == 1) {
            if (verbose) {
                System.out.println(
                        "    (Skipping query benchmark for 1-version scenario to save resources)");
            }
            return result;
        }

        // SPARQL - UNION
        List<String[]> unionQueries = List.of(
                new String[] {"Find packages + names", SparqlQueries.findPackagesUnion(bases)},
                new String[] {"Packages with licenses", SparqlQueries.licensesUnion(bases)},
                new String[] {"Dependency chain (2-hop)", SparqlQueries.depChainUnion(bases)},
                new String[] {"Count elements by type", SparqlQueries.countByTypeUnion(bases)},
                new String[] {"subClassOf 1-hop: ?x a Core/Artifact",
                        SparqlQueries.subclassTwoHop(sharedBase)},
                new String[] {"subClassOf 2-hop: ?x a Core/Element",
                        SparqlQueries.superclassAll(sharedBase)},
                new String[] {"Element + leaf type (transitivity)",
                        SparqlQueries.superclassWithType(sharedBase)},
                new String[] {"rdfs:domain: Core/from->Relationship",
                        SparqlQueries.domainInference(sharedBase)});

        int totalTasks = unionQueries.size() + (owlEnabled ? unionQueries.size() : 0) + 2;
        int currentTask = 1;

        for (String[] q : unionQueries) {
            if (verbose) {
                System.out.printf("    [%d/%d] %s (UNION) ", currentTask++, totalTasks, q[0]);
                System.out.flush();
            }
            result.queries.add(benchQuery(dataModel, q[0], "union", q[1], repeats, false, verbose));
            if (verbose)
                System.out.println();
        }

        // SPARQL - OWL-RL
        if (owlEnabled) {
            List<String[]> inferQueries = List.of(
                    new String[] {"Find packages + names",
                            SparqlQueries.findPackagesDirect(sharedBase)},
                    new String[] {"Packages with licenses",
                            SparqlQueries.licensesDirect(sharedBase)},
                    new String[] {"Dependency chain (2-hop)",
                            SparqlQueries.depChainDirect(sharedBase)},
                    new String[] {"Count elements by type",
                            SparqlQueries.countByTypeDirect(sharedBase)},
                    new String[] {"subClassOf 1-hop: ?x a Core/Artifact",
                            SparqlQueries.subclassTwoHop(sharedBase)},
                    new String[] {"subClassOf 2-hop: ?x a Core/Element",
                            SparqlQueries.superclassAll(sharedBase)},
                    new String[] {"Element + leaf type (transitivity)",
                            SparqlQueries.superclassWithType(sharedBase)},
                    new String[] {"rdfs:domain: Core/from->Relationship",
                            SparqlQueries.domainInference(sharedBase)});
            for (String[] q : inferQueries) {
                if (verbose) {
                    System.out.printf("    [%d/%d] %s (OWL-RL) ", currentTask++, totalTasks, q[0]);
                    System.out.flush();
                }
                result.queries.add(benchQuery(combinedModel, q[0], "owlrl+query", q[1], repeats,
                        true, verbose));
                if (verbose)
                    System.out.println();
            }
        }

        // Save shapes to cache for SPDX runs
        boolean isSpdx = bases.stream().anyMatch(b -> b.contains("spdx.org"));
        if (isSpdx)
            ShaclShapes.saveToCache(versionsList, bases);

        // SHACL - per-version shapes
        String versionedShapesTtl = ShaclShapes.makeShapesMulti(bases);
        if (verbose) {
            System.out.printf("    [%d/%d] SHACL (Per-version shapes) ", currentTask++, totalTasks);
            System.out.flush();
        }
        result.shacl.add(benchShacl(dataModel,
                "Per-version shapes, no inference (shapes target each versioned IRI)",
                versionedShapesTtl, "none", repeats, verbose));
        if (verbose)
            System.out.println();

        // SHACL - canonical shapes + OWL-RL
        String canonicalShapesTtl = ShaclShapes.makeShapes(sharedBase);
        if (verbose) {
            System.out.printf("    [%d/%d] SHACL (Canonical shapes + OWL-RL) ", currentTask++,
                    totalTasks);
            System.out.flush();
        }
        result.shacl.add(benchShacl(combinedModel, "Canonical shapes + OWL-RL inference",
                canonicalShapesTtl, "owlrl", repeats, verbose));
        if (verbose)
            System.out.println();

        return result;
    }

    // -------------------------------------------------------------------
    // Top-level entry point
    // -------------------------------------------------------------------
    public static List<ScenarioResult> runAll(Map<String, OntologyVersion> versions,
            String sharedBase, int pkgPerVersion, int repeats, boolean verbose, boolean owlEnabled)
            throws Exception {

        List<String> allV = new ArrayList<>(versions.keySet());
        List<ScenarioResult> results = new ArrayList<>();

        if (verbose)
            System.out.println("  Cleaning heap and warming up JVM + Jena engines ...");
        System.gc();
        warmup(sharedBase, owlEnabled);

        int totalScenarios = 1;
        if (allV.size() >= 2)
            totalScenarios += 2;
        if (allV.size() > 2)
            totalScenarios += 2;
        int currentScenario = 1;

        // Scenario 1: Versioned (1)
        Map<String, OntologyVersion> oneV = new LinkedHashMap<>();
        oneV.put(allV.get(0), versions.get(allV.get(0)));
        if (verbose)
            System.out.printf("%n[Scenario %d/%d] Versioned - 1 version (%s)%n", currentScenario++,
                    totalScenarios, allV.get(0));
        System.gc();
        results.add(runVersioned(oneV, sharedBase, pkgPerVersion, repeats, verbose, "Versioned (1)",
                owlEnabled));

        if (allV.size() < 2)
            return results;

        // Scenarios 2 & 3: Shared (2) vs Versioned (2)
        Map<String, OntologyVersion> twoV = new LinkedHashMap<>();
        twoV.put(allV.get(0), versions.get(allV.get(0)));
        twoV.put(allV.get(1), versions.get(allV.get(1)));

        if (verbose)
            System.out.printf("%n[Scenario %d/%d] Shared namespace (2 versions, canonical IRI)%n",
                    currentScenario++, totalScenarios);
        System.gc();
        results.add(
                runSharedNamespace(twoV, sharedBase, pkgPerVersion, repeats, verbose, owlEnabled));

        if (verbose)
            System.out.printf("%n[Scenario %d/%d] Versioned - 2 versions%n", currentScenario++,
                    totalScenarios);
        System.gc();
        results.add(runVersioned(twoV, sharedBase, pkgPerVersion, repeats, verbose, "Versioned (2)",
                owlEnabled));

        if (allV.size() <= 2)
            return results;

        // Scenarios 4 & 5: Shared (N) and Versioned (N)
        if (verbose)
            System.out.printf("%n[Scenario %d/%d] Shared namespace (%d versions, canonical IRI)%n",
                    currentScenario++, totalScenarios, allV.size());
        System.gc();
        results.add(runSharedNamespace(versions, sharedBase, pkgPerVersion, repeats, verbose,
                owlEnabled));

        if (verbose)
            System.out.printf("%n[Scenario %d/%d] Versioned - %d versions%n", currentScenario++,
                    totalScenarios, allV.size());
        System.gc();
        results.add(runVersioned(versions, sharedBase, pkgPerVersion, repeats, verbose,
                "Versioned (" + allV.size() + ")", owlEnabled));

        return results;
    }
}
