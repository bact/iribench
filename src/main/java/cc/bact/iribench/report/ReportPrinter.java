package cc.bact.iribench.report;

import cc.bact.iribench.benchmark.*;
import cc.bact.iribench.benchmark.ReasonerType;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.*;
import java.util.stream.Collectors;

public class ReportPrinter {

    // ANSI codes
    private static final String BOLD = "\033[1m";
    private static final String CYAN = "\033[36m";
    private static final String YELLOW = "\033[33m";
    private static final String GREEN = "\033[32m";
    private static final String RED = "\033[31m";
    private static final String DIM = "\033[2m";
    private static final String RESET = "\033[0m";

    // -------------------------------------------------------------------
    // Simple ASCII table helper
    // -------------------------------------------------------------------
    static class AsciiTable {
        private final String[] headers;
        private final List<String[]> rows = new ArrayList<>();
        private int[] widths;

        AsciiTable(String... headers) {
            this.headers = headers;
            this.widths = new int[headers.length];
            for (int i = 0; i < headers.length; i++) {
                widths[i] = getMaxLineWidth(headers[i]);
            }
        }

        private int getMaxLineWidth(String cell) {
            int max = 0;
            if (cell == null)
                return 0;
            for (String line : cell.split("\n", -1)) {
                max = Math.max(max, stripAnsi(line).length());
            }
            return max;
        }

        void addRow(String... cells) {
            String[] row = new String[headers.length];
            for (int i = 0; i < headers.length; i++) {
                row[i] = (i < cells.length && cells[i] != null) ? cells[i] : "";
                widths[i] = Math.max(widths[i], getMaxLineWidth(row[i]));
            }
            rows.add(row);
        }

        private String stripAnsi(String s) {
            if (s == null)
                return "";
            return s.replaceAll("\033\\[[\\d;]*m", "");
        }

        private String border() {
            StringBuilder sb = new StringBuilder("+");
            for (int w : widths) {
                for (int i = 0; i < w + 2; i++)
                    sb.append('-');
                sb.append('+');
            }
            return sb.toString();
        }

        void print() {
            String b = border();
            System.out.println(b);
            printMultiLineRow(headers);
            System.out.println(b);
            for (String[] r : rows) {
                printMultiLineRow(r);
            }
            System.out.println(b);
        }

        private void printMultiLineRow(String[] cells) {
            String[][] split = new String[cells.length][];
            int maxLines = 1;
            for (int i = 0; i < cells.length; i++) {
                split[i] = cells[i].split("\n", -1);
                maxLines = Math.max(maxLines, split[i].length);
            }

            for (int line = 0; line < maxLines; line++) {
                StringBuilder sb = new StringBuilder("|");
                for (int i = 0; i < cells.length; i++) {
                    String segment = (line < split[i].length) ? split[i][line] : "";
                    int visLen = stripAnsi(segment).length();
                    int pad = widths[i] - visLen;
                    sb.append(' ').append(segment);
                    for (int p = 0; p < pad; p++)
                        sb.append(' ');
                    sb.append(" |");
                }
                System.out.println(sb);
            }
        }
    }

    // -------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------
    public static void printAll(List<ScenarioResult> results, int pkgPerVersion, int repeats,
            String sharedBase, ReasonerType reasonerType) {
        if (reasonerType == null)
            reasonerType = ReasonerType.MINI;
        printHeader(reasonerType);
        printMethodology(reasonerType);
        printGraphStats(results);
        printEquivBreakdown(results);
        printSparqlResults(results, sharedBase);
        printShaclResults(results);
        printSummary(results);
        printComputingEnv(results);
    }

    // -------------------------------------------------------------------
    // 1. Header
    // -------------------------------------------------------------------
    private static void printHeader(ReasonerType reasonerType) {
        String label = (reasonerType != null) ? reasonerType.label() : "Jena OWL Mini";
        System.out.println();
        System.out.println(BOLD + CYAN
                + "========================================================================"
                + RESET);
        System.out.println(
                BOLD + CYAN + "  iribench — SPDX Versioned IRI Overhead Benchmark" + RESET);
        System.out.println(
                BOLD + CYAN + "  Apache Jena 6.0  |  " + label + "  |  jena-shacl" + RESET);
        System.out.println(BOLD + CYAN
                + "========================================================================"
                + RESET);
    }

    // -------------------------------------------------------------------
    // 2. Methodology
    // -------------------------------------------------------------------
    private static void printMethodology(ReasonerType reasonerType) {
        System.out.println();
        System.out.println(BOLD + "Approaches compared:" + RESET);
        System.out.println("  " + GREEN + "direct" + RESET
                + "    All SBOMs use shared canonical IRI https://spdx.org/rdf/3/terms/");
        System.out.println("  " + YELLOW + "union" + RESET
                + "     Each SPDX version has its own IRI prefix; queries use SPARQL UNION");
        System.out.println("  " + CYAN + "owlrl" + RESET + "     " + reasonerType.label()
                + " + backward chaining (on-the-fly), then canonical query");
        System.out.println();
        System.out.println("  " + BOLD + "Example (Counting Software instances):" + RESET);
        System.out.println("  " + GREEN + "direct" + RESET
                + "    SELECT (COUNT(?x) AS ?c) { ?x a spdx:Software }");
        System.out.println("  " + YELLOW + "union" + RESET
                + "     SELECT (COUNT(?x) AS ?c) { { ?x a v301:Software } UNION { ?x a v31:Software } }");
        System.out.println("  " + CYAN + "owlrl" + RESET
                + "     (Backward chain owl:equivalentClass), then SELECT ... { ?x a spdx:Software }");
        System.out.println();
        System.out.println(BOLD + "Limitations:" + RESET);
        System.out.println("  - " + reasonerType.label() + " used for identity linking");
        System.out
                .println("  - Synthetic SBOM data mirrors real SPDX 3.x class/property structure");
        System.out.println("  - Wall time includes JVM overhead; CPU time is per-thread");

        System.out.println();
        System.out.println(BOLD + "Accuracy strategy:" + RESET);
        System.out.println("  - " + BOLD + "Warmup:" + RESET + " Engines primed before timing.");
        System.out.println(
                "  - " + BOLD + "Isolation:" + RESET + " System.gc() before each scenario block.");
        System.out.println("  - " + BOLD + "Protection:" + RESET
                + " Unmeasured discarded first-run per query.");
        System.out.println("  - " + BOLD + "Rows calculation:" + RESET
                + " (Base x V + Inf) where Base=SharedDirect, Inf=Inferred.");
    }

    // -------------------------------------------------------------------
    // 3. Graph stats
    // -------------------------------------------------------------------
    private static void printGraphStats(List<ScenarioResult> results) {
        System.out.println();
        System.out.println(BOLD + "Graph statistics" + RESET);
        AsciiTable t =
                new AsciiTable("Namespace\nscenario", "Vers", "Data\nTriples", "Equiv\nTriples",
                        "Total\nTriples", "Build Time\n(ms)", "Inf Exp\n(ms)", "Build Mem\n(MB)");
        for (ScenarioResult r : results) {
            t.addRow(r.scenarioName, String.valueOf(r.versionsCount),
                    String.format("%,d", r.dataTriples),
                    r.equivTriples > 0 ? String.format("%,d", r.equivTriples) : "-",
                    String.format("%,d", r.totalTriples),
                    r.buildMeasurement != null ? String.format("%.1f", r.buildMeasurement.wallMs)
                            : "-",
                    r.expansionMeasurement != null
                            ? String.format("%.1f", r.expansionMeasurement.wallMs)
                            : "-",
                    r.buildMeasurement != null
                            ? String.format("%.1f", r.buildMeasurement.peakMemoryMb)
                            : "-");
        }
        t.print();
    }

    // -------------------------------------------------------------------
    // 4. Equiv breakdown
    // -------------------------------------------------------------------
    private static void printEquivBreakdown(List<ScenarioResult> results) {
        List<ScenarioResult> versioned =
                results.stream().filter(r -> r.equivStats != null).collect(Collectors.toList());
        if (versioned.isEmpty())
            return;

        System.out.println();
        System.out.println(BOLD + "Equivalence graph breakdown" + RESET);
        AsciiTable t =
                new AsciiTable("Namespace\nscenario", "equiv:Class\npairs", "equiv:Prop\npairs",
                        "sameAs\npairs", "Total\nClasses", "Total\nProps", "Total\nIndivs");
        for (ScenarioResult r : versioned) {
            EquivStats e = r.equivStats;
            t.addRow(r.scenarioName, String.valueOf(e.equivClassPairs()),
                    String.valueOf(e.equivPropPairs()), String.valueOf(e.sameAsPairs()),
                    String.valueOf(e.totalClasses()), String.valueOf(e.totalProperties()),
                    String.valueOf(e.totalIndividuals()));
        }
        t.print();
    }

    // -------------------------------------------------------------------
    // 5. SPARQL results
    // -------------------------------------------------------------------
    private static void printSparqlResults(List<ScenarioResult> results, String sharedBase) {
        System.out.println();
        System.out.println(BOLD + "SPARQL query results" + RESET);

        // Collect all distinct query names (preserving order)
        List<String> queryNames = new ArrayList<>();
        for (ScenarioResult r : results) {
            for (QueryResult q : r.queries) {
                if (!queryNames.contains(q.name()))
                    queryNames.add(q.name());
            }
        }

        for (String qname : queryNames) {
            System.out.println();
            System.out.println("  " + BOLD + qname + RESET);

            // Collect all methods for this query across scenarios
            Map<Integer, ScenarioResult> baselines =
                    results.stream().filter(res -> res.scenarioName.endsWith("-shared")).collect(
                            Collectors.toMap(res -> res.versionsCount, res -> res, (a, b) -> a));

            AsciiTable t =
                    new AsciiTable("Namespace\nscenario", "Method", "Wall\nms", "Rows", "Status");
            for (ScenarioResult r : results) {
                if ("1-ver".equals(r.scenarioName))
                    continue;

                ScenarioResult base = baselines.get(r.versionsCount);
                Map<String, Integer> baselineRows = new HashMap<>();
                if (base != null) {
                    for (QueryResult q : base.queries) {
                        baselineRows.put(q.name() + "|" + q.method(), q.resultCount());
                    }
                }

                for (QueryResult q : r.queries) {
                    if (!q.name().equals(qname))
                        continue;

                    String rowStr = formatRowCalculation(q, r, base, baselineRows);

                    String status = "ok";
                    if (q.error() != null) {
                        status = RED + q.error() + RESET;
                    } else if (q.timedOut()) {
                        status = YELLOW + "timeout" + RESET;
                    }

                    t.addRow(r.scenarioName, methodColored(q.method()),
                            (q.error() != null || q.measurement() == null) ? "-"
                                    : String.format("%.1f", q.measurement().wallMs),
                            rowStr, status);
                }
            }
            t.print();
        }
    }

    private static String formatRowCalculation(QueryResult q, ScenarioResult r, ScenarioResult base,
            Map<String, Integer> baselineRows) {
        if (q.error() != null)
            return "-";
        int count = q.resultCount();
        String s = String.valueOf(count);
        if (base == null || count == 0)
            return s;

        int V = r.versionsCount;
        Integer directBase = baselineRows.get(q.name() + "|direct");
        Integer owlrlBase = baselineRows.get(q.name() + "|owlrl");

        if ("owlrl".equals(q.method())) {
            if (r == base) {
                // Baseline: Show (Direct + Inferred)
                if (directBase != null && count > directBase) {
                    return s + " " + DIM + "(" + directBase + "+" + (count - directBase) + ")" + RESET;
                }
            } else if (owlrlBase != null && owlrlBase > 0) {
                // Versioned Pattern 1: Distributed Inference (Expansion multiplies by V)
                if (count == owlrlBase * V) {
                    return s + " " + DIM + "(" + owlrlBase + "x" + V + ")" + RESET;
                }
                // Versioned Pattern 2: Hubbed Inference (Data multiplies, expansion is canonical)
                if (directBase != null) {
                    int inf = owlrlBase - directBase;
                    if (inf > 0 && count == (directBase * V) + inf) {
                        return s + " " + DIM + "(" + directBase + "x" + V + "+" + inf + ")" + RESET;
                    }
                }
            }
        } else if ("union".equals(q.method()) && r != base) {
            if (directBase != null && directBase > 0 && count == directBase * V) {
                return s + " " + DIM + "(" + directBase + "x" + V + ")" + RESET;
            }
        }

        return s;
    }

    private static String methodColored(String method) {
        return switch (method) {
            case "direct" -> GREEN + method + RESET;
            case "union" -> YELLOW + method + RESET;
            case "owlrl" -> CYAN + method + RESET;
            default -> DIM + method + RESET;
        };
    }

    // -------------------------------------------------------------------
    // 6. SHACL results
    // -------------------------------------------------------------------
    private static void printShaclResults(List<ScenarioResult> results) {
        System.out.println();
        System.out.println(BOLD + "SHACL validation results" + RESET);

        Map<Integer, ScenarioResult> baselines =
                results.stream().filter(r -> r.scenarioName.endsWith("-shared"))
                        .collect(Collectors.toMap(r -> r.versionsCount, r -> r, (a, b) -> a));

        AsciiTable t = new AsciiTable("Namespace\nscenario", "Shapes config", "Inference",
                "Conforms?", "Violations", "Targets", "Wall\nms", "Status");
        for (ScenarioResult r : results) {
            if ("1-ver".equals(r.scenarioName))
                continue;

            ScenarioResult base = baselines.get(r.versionsCount);
            Integer baseT =
                    (base != null && !base.shacl.isEmpty()) ? base.shacl.get(0).targetCount()
                            : null;

            for (ShaclResult s : r.shacl) {
                String targetStr = String.valueOf(s.targetCount());
                if (baseT != null && baseT > 0 && r != base
                        && s.targetCount() == baseT * r.versionsCount) {
                    targetStr += " " + DIM + "(" + baseT + "x" + r.versionsCount + ")" + RESET;
                }

                String status = s.error() != null ? RED + s.error() + RESET : "ok";
                t.addRow(r.scenarioName, truncate(s.name(), 45), s.inference(),
                        s.error() != null ? "-"
                                : (s.conforms() ? GREEN + "yes" + RESET : RED + "NO" + RESET),
                        s.error() != null ? "-" : String.valueOf(s.violationCount()), targetStr,
                        (s.error() != null || s.measurement() == null) ? "-"
                                : String.format("%.1f", s.measurement().wallMs),
                        status);
            }
        }
        t.print();
    }

    // -------------------------------------------------------------------
    // 7. Summary
    // -------------------------------------------------------------------
    private static void printSummary(List<ScenarioResult> results) {
        System.out.println();
        System.out.println(BOLD + "Summary — Overhead vs Shared namespace" + RESET);

        // Map shared baselines by version count
        Map<Integer, ScenarioResult> baselines =
                results.stream().filter(r -> r.scenarioName.endsWith("-shared"))
                        .collect(Collectors.toMap(r -> r.versionsCount, r -> r, (a, b) -> a));

        if (baselines.isEmpty()) {
            System.out.println("  (no shared namespace baseline found)");
            return;
        }

        AsciiTable t = new AsciiTable("Namespace\nscenario", "Query", "Method", "Wall\nms",
                "vs.\nbaseline", "Rows", "Notes");
        for (ScenarioResult r : results) {
            if ("1-ver".equals(r.scenarioName))
                continue;

            ScenarioResult base = baselines.get(r.versionsCount);
            if (base == null)
                continue;

            // Build baseline lookup for this specific version count
            Map<String, Double> baselineTime = new HashMap<>();
            Map<String, Integer> baselineRows = new HashMap<>();
            for (QueryResult q : base.queries) {
                if (q.measurement() != null) {
                    baselineTime.put(q.name() + "|" + q.method(), q.measurement().wallMs);
                }
                baselineRows.put(q.name() + "|" + q.method(), q.resultCount());
            }

            for (QueryResult q : r.queries) {
                String calcStr = formatRowCalculation(q, r, base, baselineRows);
                String baseMethod = "union".equals(q.method()) ? "direct" : q.method();
                Double baselineT = baselineTime.get(q.name() + "|" + baseMethod);
                Integer directBase = baselineRows.get(q.name() + "|direct");
                Integer owlrlBase = baselineRows.get(q.name() + "|owlrl");

                String ratio;
                if (q.error() != null) {
                    ratio = RED + "ERROR" + RESET;
                } else if (baselineT != null && baselineT > 0) {
                    double v = q.measurement().wallMs / baselineT;
                    if (r == base && Math.abs(v - 1.0) < 0.001) {
                        ratio = DIM + "baseline" + RESET;
                    } else {
                        ratio = String.format("%.2fx", v);
                        if (v > 2.0)
                            ratio = RED + ratio + RESET;
                        else if (v > 1.2)
                            ratio = YELLOW + ratio + RESET;
                        else
                            ratio = GREEN + ratio + RESET;
                    }
                } else {
                    ratio = "-";
                }

                String note = "";
                String baseNote = getInferenceNote(q.name());
                boolean isVersioned = (r != base);
                Integer dBase = directBase; // can be null

                if ("owlrl".equals(q.method()) || !baseNote.equals("hubbing")) {
                    int total = q.resultCount();
                    int expectedData = (dBase == null) ? 0 : (isVersioned ? dBase * r.versionsCount : dBase);
                    int inf = total - expectedData;

                    if (!isVersioned) {
                        // Baseline: "10 + hier 32"
                        if (dBase != null && inf > 0) {
                            note = DIM + dBase + " + " + baseNote + " " + inf + RESET;
                        } else if (dBase != null && inf == 0 && !baseNote.equals("hubbing")) {
                            note = DIM + baseNote + RESET;
                        }
                    } else {
                        // Versioned
                        if (calcStr.contains("x") && dBase != null) {
                            // Exact match: "10x2 + hier 32" or just "10x2"
                            String dataPart = dBase + "x" + r.versionsCount;
                            if (inf > 0) {
                                note = DIM + dataPart + " + " + baseNote + " " + inf + RESET;
                            } else {
                                note = DIM + baseNote + " (" + dataPart + ")" + RESET;
                            }
                        } else if (dBase != null) {
                            // Approx match: "hier ≈(102x2)"
                            Integer targetBase = ("owlrl".equals(q.method()) && owlrlBase != null) ? owlrlBase : dBase;
                            note = DIM + baseNote + " ≈(" + targetBase + "x" + r.versionsCount + ")" + RESET;
                        }
                    }
                } else if (calcStr.contains("(")) {
                    // union/direct patterns with no inference
                    note = DIM + calcStr.substring(calcStr.indexOf("(")) + RESET;
                }

                t.addRow(r.scenarioName, q.name(), methodColored(q.method()),
                        (q.error() != null || q.measurement() == null) ? "-"
                                : String.format("%.1f", q.measurement().wallMs),
                        ratio, String.valueOf(q.resultCount()), note);
            }
        }
        t.print();
    }

    private static String getInferenceNote(String qname) {
        return switch (qname) {
            case "Count elements by type", "subClassOf 1-hop: ?x a Core/Artifact",
                    "subClassOf 2-hop: ?x a Core/Element" ->
                "hier";
            case "Element + leaf type (transitivity)" -> "hier + trans";
            case "rdfs:domain: Core/from->Relationship" -> "domain";
            case "Dependency chain (2-hop)" -> "trans";
            default -> "hubbing";
        };
    }

    // -------------------------------------------------------------------
    // 9. Computing environment
    // -------------------------------------------------------------------
    private static void printComputingEnv(List<ScenarioResult> results) {
        System.out.println();
        System.out.println(BOLD + "Computing environment" + RESET);

        String cpuBrand = getCpuBrand();
        int cpus = Runtime.getRuntime().availableProcessors();

        long totalRamBytes = 0;
        try {
            OperatingSystemMXBean osBean =
                    (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            // Use reflection to call getTotalPhysicalMemorySize if available
            try {
                java.lang.reflect.Method m =
                        osBean.getClass().getMethod("getTotalPhysicalMemorySize");
                m.setAccessible(true);
                totalRamBytes = (long) m.invoke(osBean);
            } catch (Exception ex) {
                // fallback: com.sun.management.OperatingSystemMXBean
                if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
                    totalRamBytes = sunOs.getTotalPhysicalMemorySize();
                }
            }
        } catch (Exception e) {
            // ignore
        }

        String javaVersion = System.getProperty("java.version");
        String jvmName = System.getProperty("java.vm.name");
        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");

        // Total wall time across all scenarios
        double totalWallMs = results.stream().flatMap(r -> r.queries.stream())
                .mapToDouble(q -> q.measurement().wallMs).sum();
        // Add build times
        totalWallMs += results.stream().filter(r -> r.buildMeasurement != null)
                .mapToDouble(r -> r.buildMeasurement.wallMs).sum();

        // Peak memory across all
        double peakMemMb = results.stream().flatMap(r -> r.queries.stream())
                .mapToDouble(q -> q.measurement().peakMemoryMb).max().orElse(0);

        System.out.printf("  CPU:          %s%n", cpuBrand);
        System.out.printf("  Logical CPUs: %d%n", cpus);
        System.out.printf(
                "  (Benchmark is single-threaded; logical CPU count shown for reference.)%n");
        if (totalRamBytes > 0)
            System.out.printf("  RAM:          %.1f GB%n",
                    totalRamBytes / (1024.0 * 1024.0 * 1024.0));
        System.out.printf("  JVM:          %s (%s)%n", javaVersion, jvmName);
        System.out.printf("  OS:           %s %s%n", osName, osVersion);
        System.out.printf("  Total wall:   %.1f ms (queries + builds)%n", totalWallMs);
        System.out.printf("  Peak query mem: %.1f MB%n", peakMemMb);
        System.out.println();
    }

    private static String getCpuBrand() {
        // macOS
        try {
            Process p = Runtime.getRuntime()
                    .exec(new String[] {"sysctl", "-n", "machdep.cpu.brand_string"});
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (!out.isEmpty())
                return out;
        } catch (Exception ignored) {
        }
        // Linux
        try {
            String cpuinfo = java.nio.file.Files.readString(java.nio.file.Path.of("/proc/cpuinfo"));
            for (String line : cpuinfo.split("\n")) {
                if (line.startsWith("model name")) {
                    return line.substring(line.indexOf(':') + 1).trim();
                }
            }
        } catch (Exception ignored) {
        }
        return System.getProperty("os.arch", "unknown");
    }

    private static String truncate(String s, int max) {
        if (s == null)
            return "";
        if (s.length() <= max)
            return s;
        return s.substring(0, max - 3) + "...";
    }
}
