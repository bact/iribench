package cc.bact.iribench.benchmark;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.reasoner.Reasoner;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.Lang;
import org.apache.jena.shacl.*;
import org.apache.jena.vocabulary.OWL;
import cc.bact.iribench.Measurement;
import cc.bact.iribench.datagen.SbomGenerator;
import cc.bact.iribench.datagen.GeneratorConfig;
import cc.bact.iribench.ontology.OntologyVersion;
import cc.bact.iribench.ontology.EquivGraphBuilder;

import org.apache.jena.reasoner.rulesys.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class BenchmarkRunner {

    // Using a 5-minute safety timeout to prevent reasoner blowout on complex ontologies

    // -------------------------------------------------------------------
    // Internal: run SPARQL, return row count
    // -------------------------------------------------------------------
    private static final java.util.concurrent.ExecutorService BENCH_EXECUTOR =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "Bench-Worker");
                t.setDaemon(true);
                return t;
            });

    /**
     * Runs a task with a hard 300s (5-minute) timeout. Returns the result or throws
     * QueryCancelledException on timeout.
     */
    private static <T> T runWithTimeout(java.util.concurrent.Callable<T> task, Runnable onCancel)
            throws Exception {
        java.util.concurrent.CompletableFuture<T> future =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return task.call();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, BENCH_EXECUTOR);

        try {
            return future.get(300, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            if (onCancel != null)
                onCancel.run();
            future.cancel(true);
            throw new org.apache.jena.query.QueryCancelledException();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException && cause.getCause() instanceof Exception) {
                throw (Exception) cause.getCause();
            }
            if (cause instanceof Exception)
                throw (Exception) cause;
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            if (onCancel != null)
                onCancel.run();
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new org.apache.jena.query.QueryCancelledException();
        }
    }

    private static int runQuery(Model model, String sparql) {
        try (QueryExecution qe = QueryExecutionFactory.create(sparql, model)) {
            return runWithTimeout(() -> {
                ResultSet rs = qe.execSelect();
                int count = 0;
                while (rs.hasNext()) {
                    rs.nextSolution();
                    count++;
                }
                return count;
            }, () -> qe.abort());
        } catch (org.apache.jena.query.QueryCancelledException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof RuntimeException re
                    && re.getCause() instanceof org.apache.jena.query.QueryCancelledException) {
                throw (org.apache.jena.query.QueryCancelledException) re.getCause();
            }
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------
    // Internal: configure OWL reasoner
    // Returns (infModel, timedOut=false)
    // -------------------------------------------------------------------
    private static InfModel expandOwlRl(Model combinedModel, ReasonerType type) {
        Reasoner reasoner;
        if (type == null)
            type = ReasonerType.MINI;
        switch (type) {
            case FULL -> reasoner = ReasonerRegistry.getOWLReasoner();
            case MINI -> reasoner = ReasonerRegistry.getOWLMiniReasoner();
            case MICRO -> reasoner = ReasonerRegistry.getOWLMicroReasoner();
            case SPDX_CUSTOM -> reasoner = getBareMinimumReasoner();
            default -> reasoner = getBareMinimumReasoner();
        }
        return ModelFactory.createInfModel(reasoner, combinedModel);
    }

    private static Measurement prepareInference(InfModel inf, boolean verbose) {
        if (verbose) {
            System.out.print("    Inference expansion ... ");
            System.out.flush();
        }
        try {
            Measurement m = Measurement.measure(() -> {
                runWithTimeout(() -> {
                    inf.prepare();
                    return null;
                }, null);
            });
            if (verbose)
                System.out.printf("[%.1f ms]\n", m.wallMs);
            return m;
        } catch (org.apache.jena.query.QueryCancelledException e) {
            if (verbose)
                System.out.println("[TIMEOUT]");
            return new Measurement(300000, 0);
        } catch (Exception e) {
            if (verbose)
                System.out.println("[ERROR]");
            return new Measurement(0, 0);
        }
    }

    /**
     * Bare-minimum rule-based reasoner for SPDX identity hubbing. WARNING: This is NOT a complete
     * OWL reasoner. It only supports: - owl:equivalentClass / owl:equivalentProperty - owl:sameAs -
     * rdfs:subClassOf / rdfs:subPropertyOf (transitive) - rdfs:domain / rdfs:range It will NOT work
     * for complex OWL features (oneOf, unionOf, etc.) and is intended solely for benchmarking
     * identity resolution.
     */
    private static Reasoner getBareMinimumReasoner() {
        String rdf = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
        String rdfs = "http://www.w3.org/2000/01/rdf-schema#";
        String owl = "http://www.w3.org/2002/07/owl#";

        String rules = "[equivClass: (?a <" + owl + "equivalentClass> ?b) -> (?a <" + rdfs
                + "subClassOf> ?b), (?b <" + rdfs + "subClassOf> ?a)]\n" + "[equivProp:  (?a <"
                + owl + "equivalentProperty> ?b) -> (?a <" + rdfs + "subPropertyOf> ?b), (?b <"
                + rdfs + "subPropertyOf> ?a)]\n" + "[sameAsSym:  (?a <" + owl
                + "sameAs> ?b) -> (?b <" + owl + "sameAs> ?a)]\n" + "[sameAsTrans: (?a <" + owl
                + "sameAs> ?b), (?b <" + owl + "sameAs> ?c) -> (?a <" + owl + "sameAs> ?c)]\n"
                + "[subClassTrans: (?a <" + rdfs + "subClassOf> ?b), (?b <" + rdfs
                + "subClassOf> ?c) -> (?a <" + rdfs + "subClassOf> ?c)]\n" + "[subPropTrans:  (?a <"
                + rdfs + "subPropertyOf> ?b), (?b <" + rdfs + "subPropertyOf> ?c) -> (?a <" + rdfs
                + "subPropertyOf> ?c)]\n" + "[typeTrans:  (?x <" + rdf + "type> ?a), (?a <" + rdfs
                + "subClassOf> ?b) -> (?x <" + rdf + "type> ?b)]\n"
                + "[propTrans:  (?s ?p ?o), (?p <" + rdfs + "subPropertyOf> ?q) -> (?s ?q ?o)]\n"
                + "[domain:     (?p <" + rdfs + "domain> ?c), (?s ?p ?o) -> (?s <" + rdf
                + "type> ?c)]\n" + "[range:      (?p <" + rdfs + "range> ?c), (?s ?p ?o) -> (?o <"
                + rdf + "type> ?c)]\n" + "[sameAsSubj: (?s ?p ?o), (?s <" + owl
                + "sameAs> ?s1) -> (?s1 ?p ?o)]\n" + "[sameAsObj:  (?s ?p ?o), (?o <" + owl
                + "sameAs> ?o1) -> (?s ?p ?o1)]";
        return new GenericRuleReasoner(Rule.parseRules(rules));
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
            int repeats, ReasonerType reasonerType, boolean verbose) {
        try {
            Model targetModel = model;
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
        } catch (org.apache.jena.query.QueryCancelledException te) {
            if (verbose) {
                System.out.print(" [TIMEOUT] ");
                System.out.println(
                        "\033[2m(Suggestion: Try a lighter reasoner like OWL Micro if this continues)\033[0m");
                System.out.flush();
            }
            return new QueryResult(name, method, 0, new Measurement(300000, 0), true, null);
        } catch (Throwable t) {
            if (verbose) {
                System.out.print(
                        " [ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage() + "] ");
                System.out.flush();
            }
            if (t instanceof OutOfMemoryError) {
                System.gc(); // Try to recover
                return new QueryResult(name, method, 0, new Measurement(0, 0), false, "OOM");
            }
            return new QueryResult(name, method, 0, new Measurement(), false,
                    t.getClass().getSimpleName());
        }
    }

    // -------------------------------------------------------------------
    // Internal: run SHACL
    // -------------------------------------------------------------------
    private static ShaclResult benchShacl(Model queryModel, String name, String shapesTtl,
            int repeats, boolean verbose) {
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

            try {
                for (String tc : targetClassUris) {
                    String q = "SELECT DISTINCT ?x WHERE { ?x a <" + tc + "> }";
                    targetCount += runQuery(queryModel, q);
                }

                // Parse shapes once outside the measurement loop
                Model sm = ModelFactory.createDefaultModel();
                RDFDataMgr.read(sm,
                        new ByteArrayInputStream(shapesTtl.getBytes(StandardCharsets.UTF_8)),
                        Lang.TURTLE);
                Shapes shapes = Shapes.parse(sm);

                // Cold start protection: run validation once and discard
                final Model finalQModel = queryModel;
                runWithTimeout(() -> {
                    ShaclValidator.get().validate(shapes, finalQModel.getGraph());
                    return null;
                }, null);

                for (int i = 0; i < repeats; i++) {
                    Measurement.MeasuredResult<ShaclIterResult> mr =
                            Measurement.measureWithResult(() -> {
                                return runWithTimeout(() -> {
                                    ValidationReport report = ShaclValidator.get().validate(shapes,
                                            finalQModel.getGraph());
                                    int v = report.getEntries().size();
                                    return new ShaclIterResult(report.conforms(), v);
                                }, null);
                            });
                    if (verbose) {
                        System.out.print(".");
                        System.out.flush();
                    }
                    measurements.add(mr.measurement());
                    conforms = mr.value().conforms();
                    violations = mr.value().violations();
                }

                return new ShaclResult(name, "shacl", conforms, violations, targetCount,
                        Measurement.average(measurements), null);
            } catch (Throwable t) {
                if (verbose) {
                    System.out.print(" [ERROR: " + t.getClass().getSimpleName() + "] ");
                    System.out.flush();
                }
                if (t instanceof OutOfMemoryError) {
                    System.gc();
                    return new ShaclResult(name, "shacl", false, 0, 0, new Measurement(0, 0),
                            "OOM: " + getHeapConfig());
                }
                return new ShaclResult(name, "shacl", false, 0, 0, new Measurement(0, 0),
                        t.toString());
            }
        } catch (Throwable t) {
            if (verbose) {
                System.out.print(" [ERROR: " + t.getClass().getSimpleName() + "] ");
                System.out.flush();
            }
            if (t instanceof OutOfMemoryError) {
                System.gc();
                return new ShaclResult(name, "shacl", false, 0, 0, new Measurement(0, 0),
                        "OOM: " + getHeapConfig());
            }
            return new ShaclResult(name, "shacl", false, 0, 0, new Measurement(0, 0), t.toString());
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
    public static void warmup(String sharedBase, boolean owlEnabled, ReasonerType reasonerType)
            throws Exception {
        Model wg = SbomGenerator.generate(sharedBase, "https://example.org/sbom/warmup/",
                new GeneratorConfig(5, 99));
        // 1. SPARQL — all query shapes used in the benchmark
        List<String> warmupQueries =
                new ArrayList<>(List.of(SparqlQueries.findPackagesDirect(sharedBase),
                        SparqlQueries.licensesDirect(sharedBase),
                        SparqlQueries.depChainDirect(sharedBase),
                        SparqlQueries.countByTypeDirect(sharedBase)));
        if (owlEnabled) {
            warmupQueries.addAll(List.of(SparqlQueries.subclassOneHop(sharedBase),
                    SparqlQueries.subclassTwoHop(sharedBase),
                    SparqlQueries.superclassWithType(sharedBase),
                    SparqlQueries.domainInference(sharedBase)));
        }

        for (String sparql : warmupQueries) {
            try (QueryExecution qe = QueryExecution.model(wg).query(sparql).build()) {
                qe.execSelect().forEachRemaining(s -> {
                });
            }
        }
        // 2. OWL-RL on representative graph
        if (owlEnabled) {
            InfModel inf = expandOwlRl(wg, reasonerType);
            // Run expansion twice during warmup to ensure JIT is fully primed
            inf.prepare();
            inf.rebind();
            inf.prepare();
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
            String sharedBase, int pkgPerVersion, int repeats, boolean verbose, boolean owlEnabled,
            ReasonerType reasonerType) throws Exception {

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
        result.scenarioName = versions.size() + "-shared";
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
                        SparqlQueries.subclassOneHop(sharedBase)},
                new String[] {"subClassOf 2-hop: ?x a Core/Element",
                        SparqlQueries.subclassTwoHop(sharedBase)},
                new String[] {"Element + leaf type (transitivity)",
                        SparqlQueries.superclassWithType(sharedBase)},
                new String[] {"rdfs:domain: Core/from->Relationship",
                        SparqlQueries.domainInference(sharedBase)});

        int totalTasks = queries.size() + (owlEnabled ? queries.size() : 0) + 1;
        int currentTask = 1;

        InfModel inferenceModel = owlEnabled ? expandOwlRl(combinedModel, reasonerType) : null;
        boolean owlTimedOut = false;
        if (owlEnabled) {
            result.expansionMeasurement = prepareInference(inferenceModel, verbose);
            owlTimedOut = result.expansionMeasurement.wallMs >= 300000;
        }

        for (String[] q : queries) {
            if (verbose) {
                System.out.printf("    [%d/%d] %s ", currentTask++, totalTasks, q[0]);
                System.out.flush();
            }
            QueryResult qr =
                    benchQuery(dataModel, q[0], "direct", q[1], repeats, reasonerType, verbose);
            result.queries.add(qr);
            if (verbose)
                System.out.printf(" [%.1f ms]\n", qr.measurement().wallMs);
        }

        if (owlEnabled) {
            for (String[] q : queries) {
                if (verbose) {
                    System.out.printf("    [%d/%d] %s (%s) ", currentTask++, totalTasks, q[0],
                            reasonerType.label());
                    System.out.flush();
                }
                QueryResult qr;
                if (owlTimedOut) {
                    qr = new QueryResult(q[0], "owlrl", 0, new Measurement(300000, 0), true, null);
                    if (verbose)
                        System.out.print(" [SKIPPED due to expansion timeout] ");
                } else {
                    qr = benchQuery(inferenceModel, q[0], "owlrl", q[1], repeats, reasonerType,
                            verbose);
                }
                result.queries.add(qr);
                if (verbose)
                    System.out.printf(" [%.1f ms]\n", qr.measurement().wallMs);
            }
        }

        String shapesTtl = ShaclShapes.makeShapes(sharedBase);
        if (verbose) {
            System.out.printf("    [%d/%d] SHACL (Package + Relationship shapes) ", currentTask++,
                    totalTasks);
            System.out.flush();
        }
        ShaclResult sr =
                benchShacl(dataModel, "Package + Relationship shapes", shapesTtl, repeats, verbose);
        result.shacl.add(sr);
        if (verbose)
            System.out.printf(" [%.1f ms]\n", sr.measurement().wallMs);
        return result;
    }

    // -------------------------------------------------------------------
    // Scenarios 2 & 3: Versioned namespaces
    // -------------------------------------------------------------------
    public static ScenarioResult runVersioned(Map<String, OntologyVersion> versions,
            String sharedBase, int pkgPerVersion, int repeats, boolean verbose, String scenarioName,
            boolean owlEnabled, ReasonerType reasonerType) throws Exception {

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

        // SPARQL - UNION (Manual multi-versioning)
        List<String[]> unionQueries = List.of(
                new String[] {"Find packages + names", SparqlQueries.findPackagesUnion(bases)},
                new String[] {"Packages with licenses", SparqlQueries.licensesUnion(bases)},
                new String[] {"Dependency chain (2-hop)", SparqlQueries.depChainUnion(bases)},
                new String[] {"Count elements by type", SparqlQueries.countByTypeUnion(bases)},
                new String[] {"subClassOf 1-hop: ?x a Core/Artifact",
                        SparqlQueries.subclassOneHopUnion(bases)},
                new String[] {"subClassOf 2-hop: ?x a Core/Element",
                        SparqlQueries.subclassTwoHopUnion(bases)},
                new String[] {"Element + leaf type (transitivity)",
                        SparqlQueries.superclassWithTypeUnion(bases)},
                new String[] {"rdfs:domain: Core/from->Relationship",
                        SparqlQueries.domainInferenceUnion(bases)});

        // SPARQL - CANONICAL (Reasoner-based multi-versioning)
        List<String[]> canonicalQueries = List.of(
                new String[] {"Find packages + names",
                        SparqlQueries.findPackagesDirect(sharedBase)},
                new String[] {"Packages with licenses", SparqlQueries.licensesDirect(sharedBase)},
                new String[] {"Dependency chain (2-hop)", SparqlQueries.depChainDirect(sharedBase)},
                new String[] {"Count elements by type",
                        SparqlQueries.countByTypeDirect(sharedBase)},
                new String[] {"subClassOf 1-hop: ?x a Core/Artifact",
                        SparqlQueries.subclassOneHop(sharedBase)},
                new String[] {"subClassOf 2-hop: ?x a Core/Element",
                        SparqlQueries.subclassTwoHop(sharedBase)},
                new String[] {"Element + leaf type (transitivity)",
                        SparqlQueries.superclassWithType(sharedBase)},
                new String[] {"rdfs:domain: Core/from->Relationship",
                        SparqlQueries.domainInference(sharedBase)});

        int totalTasks = unionQueries.size() + (owlEnabled ? canonicalQueries.size() : 0) + 2;
        int currentTask = 1;

        InfModel inferenceModel = owlEnabled ? expandOwlRl(combinedModel, reasonerType) : null;
        boolean owlTimedOut = false;
        if (owlEnabled) {
            result.expansionMeasurement = prepareInference(inferenceModel, verbose);
            owlTimedOut = result.expansionMeasurement.wallMs >= 300000;
        }

        for (String[] q : unionQueries) {
            if (verbose) {
                System.out.printf("    [%d/%d] %s (UNION) ", currentTask++, totalTasks, q[0]);
                System.out.flush();
            }
            QueryResult qr =
                    benchQuery(dataModel, q[0], "union", q[1], repeats, reasonerType, verbose);
            result.queries.add(qr);
            if (verbose)
                System.out.printf(" [%.1f ms]\n", qr.measurement().wallMs);
        }

        if (owlEnabled) {
            for (String[] q : canonicalQueries) {
                if (verbose) {
                    System.out.printf("    [%d/%d] %s (%s) ", currentTask++, totalTasks, q[0],
                            reasonerType.label());
                    System.out.flush();
                }
                QueryResult qr;
                if (owlTimedOut) {
                    qr = new QueryResult(q[0], "owlrl", 0, new Measurement(300000, 0), true, null);
                    if (verbose)
                        System.out.print(" [SKIPPED due to expansion timeout] ");
                } else {
                    qr = benchQuery(inferenceModel, q[0], "owlrl", q[1], repeats, reasonerType,
                            verbose);
                }
                result.queries.add(qr);
                if (verbose)
                    System.out.printf(" [%.1f ms]\n", qr.measurement().wallMs);
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
        ShaclResult sr1 = benchShacl(dataModel,
                "Per-version shapes, no inference (shapes target each versioned IRI)",
                versionedShapesTtl, repeats, verbose);
        result.shacl.add(sr1);
        if (verbose)
            System.out.printf(" [%.1f ms]\n", sr1.measurement().wallMs);

        if (owlEnabled) {
            // SHACL - canonical shapes + OWL-RL
            String canonicalShapesTtl = ShaclShapes.makeShapes(sharedBase);
            if (verbose) {
                System.out.printf("    [%d/%d] SHACL (Canonical shapes + %s) ", currentTask++,
                        totalTasks, reasonerType.label());
                System.out.flush();
            }

            ShaclResult sr2;
            if (owlTimedOut) {
                sr2 = new ShaclResult("Canonical shapes + OWL-RL", "shacl", false, 0, 0,
                        new Measurement(300000, 0), "SKIPPED due to expansion timeout");
                if (verbose)
                    System.out.print(" [SKIPPED due to expansion timeout] ");
            } else {
                sr2 = benchShacl(inferenceModel, "Canonical shapes + OWL-RL", canonicalShapesTtl,
                        repeats, verbose);
            }
            result.shacl.add(sr2);
            if (verbose)
                System.out.printf(" [%.1f ms]\n", sr2.measurement().wallMs);
        }

        return result;
    }

    // -------------------------------------------------------------------
    // Top-level entry point
    // -------------------------------------------------------------------
    public static List<ScenarioResult> runAll(Map<String, OntologyVersion> versions,
            String sharedBase, int pkgPerVersion, int repeats, boolean verbose, boolean owlEnabled,
            ReasonerType reasonerType) throws Exception {

        List<String> allV = new ArrayList<>(versions.keySet());
        List<ScenarioResult> results = new ArrayList<>();

        if (verbose)
            System.out.println("  Cleaning heap and warming up JVM + Jena engines ...");
        warmup(sharedBase, owlEnabled, reasonerType);

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
        results.add(runVersioned(oneV, sharedBase, pkgPerVersion, repeats, verbose, "1-ver",
                owlEnabled, reasonerType));

        if (allV.size() < 2)
            return results;

        // Scenarios 2 & 3: Shared (2) vs Versioned (2)
        Map<String, OntologyVersion> twoV = new LinkedHashMap<>();
        twoV.put(allV.get(0), versions.get(allV.get(0)));
        twoV.put(allV.get(1), versions.get(allV.get(1)));

        if (verbose)
            System.out.printf("%n[Scenario %d/%d] Shared namespace (2 versions, canonical IRI)%n",
                    currentScenario++, totalScenarios);
        results.add(runSharedNamespace(twoV, sharedBase, pkgPerVersion, repeats, verbose,
                owlEnabled, reasonerType));

        if (verbose)
            System.out.printf("%n[Scenario %d/%d] Versioned - 2 versions%n", currentScenario++,
                    totalScenarios);
        results.add(runVersioned(twoV, sharedBase, pkgPerVersion, repeats, verbose, "2-ver",
                owlEnabled, reasonerType));

        if (allV.size() <= 2)
            return results;

        // Scenarios 4 & 5: Shared (N) and Versioned (N)
        if (verbose)
            System.out.printf("%n[Scenario %d/%d] Shared namespace (%d versions, canonical IRI)%n",
                    currentScenario++, totalScenarios, allV.size());
        results.add(runSharedNamespace(versions, sharedBase, pkgPerVersion, repeats, verbose,
                owlEnabled, reasonerType));

        if (verbose)
            System.out.printf("%n[Scenario %d/%d] Versioned - %d versions%n", currentScenario++,
                    totalScenarios, allV.size());
        results.add(runVersioned(versions, sharedBase, pkgPerVersion, repeats, verbose,
                allV.size() + "-ver", owlEnabled, reasonerType));

        return results;
    }
}
