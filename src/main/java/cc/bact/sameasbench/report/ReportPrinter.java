package cc.bact.sameasbench.report;

import cc.bact.sameasbench.benchmark.*;
import cc.bact.sameasbench.benchmark.ReasonerType;

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
        if (reasonerType == null) reasonerType = ReasonerType.MINI;
        printHeader(reasonerType);
        printMethodology(reasonerType);
        printGraphStats(results);
        printEquivBreakdown(results);
        printSparqlResults(results);
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
        System.out.println(BOLD + CYAN
                + "  sameas-bench-java — SPDX Versioned IRI Overhead Benchmark" + RESET);
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
        System.out.println("  " + CYAN + "owlrl" + RESET
                + "     " + reasonerType.label() + " + backward chaining (on-the-fly), then canonical query");
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
        System.out.println(BOLD + "Accuracy Strategy:" + RESET);
        System.out.println("  - " + BOLD + "Warmup:" + RESET + " Engines primed before timing.");
        System.out.println(
                "  - " + BOLD + "Isolation:" + RESET + " System.gc() before each scenario block.");
        System.out.println("  - " + BOLD + "Protection:" + RESET
                + " Unmeasured discarded first-run per query.");
    }

    // -------------------------------------------------------------------
    // 3. Graph stats
    // -------------------------------------------------------------------
    private static void printGraphStats(List<ScenarioResult> results) {
        System.out.println();
        System.out.println(BOLD + "Graph Statistics" + RESET);
        AsciiTable t = new AsciiTable("Namespace\nscenario", "Vers", "Data\nTriples",
                "Equiv\nTriples", "Total\nTriples", "Build Time\n(ms)", "Build Mem\n(MB)");
        for (ScenarioResult r : results) {
            t.addRow(r.scenarioName, String.valueOf(r.versionsCount),
                    String.format("%,d", r.dataTriples),
                    r.equivTriples > 0 ? String.format("%,d", r.equivTriples) : "-",
                    String.format("%,d", r.totalTriples),
                    r.buildMeasurement != null ? String.format("%.1f", r.buildMeasurement.wallMs)
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
        System.out.println(BOLD + "Equivalence Graph Breakdown" + RESET);
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
    private static void printSparqlResults(List<ScenarioResult> results) {
        System.out.println();
        System.out.println(BOLD + "SPARQL Query Results" + RESET);

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
                    results.stream().filter(res -> res.scenarioName.startsWith("Shared (")).collect(
                            Collectors.toMap(res -> res.versionsCount, res -> res, (a, b) -> a));

            AsciiTable t =
                    new AsciiTable("Namespace\nscenario", "Method", "Wall\nms", "Rows", "Status");
            for (ScenarioResult r : results) {
                if ("Versioned (1)".equals(r.scenarioName))
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

                    String baseMethod = "union".equals(q.method()) ? "direct" : q.method();
                    Integer baseR = baselineRows.get(q.name() + "|" + baseMethod);
                    String rowStr;
                    if (q.error() != null) {
                        rowStr = "-";
                    } else {
                        rowStr = String.valueOf(q.resultCount());
                        if (baseR != null && baseR > 0 && r != base
                                && q.resultCount() == baseR * r.versionsCount) {
                            rowStr += " " + DIM + "(" + baseR + "x" + r.versionsCount + ")" + RESET;
                        }
                    }

                    String status = "ok";
                    if (q.error() != null) {
                        status = RED + q.error() + RESET;
                    } else if (q.timedOut()) {
                        status = YELLOW + "timeout" + RESET;
                    }

                    t.addRow(r.scenarioName, methodColored(q.method()),
                            (q.error() != null || q.measurement() == null) ? "-" : String.format("%.1f", q.measurement().wallMs),
                            rowStr, status);
                }
            }
            t.print();
        }
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
        System.out.println(BOLD + "SHACL Validation Results" + RESET);

        Map<Integer, ScenarioResult> baselines =
                results.stream().filter(r -> r.scenarioName.startsWith("Shared ("))
                        .collect(Collectors.toMap(r -> r.versionsCount, r -> r, (a, b) -> a));

        AsciiTable t = new AsciiTable("Namespace\nscenario", "Shapes config", "Inference",
                "Conforms?", "Violations", "Targets", "Wall\nms", "Status");
        for (ScenarioResult r : results) {
            if ("Versioned (1)".equals(r.scenarioName))
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
                        (s.error() != null || s.measurement() == null) ? "-" : String.format("%.1f", s.measurement().wallMs),
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
        System.out.println(BOLD + "Summary — Overhead vs Shared Namespace" + RESET);

        // Map shared baselines by version count
        Map<Integer, ScenarioResult> baselines =
                results.stream().filter(r -> r.scenarioName.startsWith("Shared ("))
                        .collect(Collectors.toMap(r -> r.versionsCount, r -> r, (a, b) -> a));

        if (baselines.isEmpty()) {
            System.out.println("  (no shared namespace baseline found)");
            return;
        }

        AsciiTable t = new AsciiTable("Namespace\nscenario", "Query", "Method", "Wall\nms",
                "vs.\nbaseline", "Rows");
        for (ScenarioResult r : results) {
            if ("Versioned (1)".equals(r.scenarioName))
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
                String baseMethod = "union".equals(q.method()) ? "direct" : q.method();
                Double baselineT = baselineTime.get(q.name() + "|" + baseMethod);
                Integer baseR = baselineRows.get(q.name() + "|" + baseMethod);

                String ratio;
                if (q.error() != null) {
                    ratio = RED + "ERROR" + RESET;
                } else if (baselineT != null && baselineT > 0) {
                    double x = q.measurement().wallMs / baselineT;
                    if (r == base && x == 1.0) {
                        ratio = DIM + "baseline" + RESET;
                    } else {
                        ratio = String.format("%.2fx", x);
                        if (x > 3.0)
                            ratio = RED + ratio + RESET;
                        else if (x > 1.5)
                            ratio = YELLOW + ratio + RESET;
                        else
                            ratio = GREEN + ratio + RESET;
                    }
                } else {
                    ratio = "-";
                }

                String rowStr;
                if (q.error() != null) {
                    rowStr = "-";
                } else {
                    rowStr = String.valueOf(q.resultCount());
                    if (baseR != null && baseR > 0 && r != base
                            && q.resultCount() == baseR * r.versionsCount) {
                        rowStr += " " + DIM + "(" + baseR + "x" + r.versionsCount + ")" + RESET;
                    }
                }

                t.addRow(r.scenarioName, q.name(), methodColored(q.method()),
                        (q.error() != null || q.measurement() == null) ? "-" : String.format("%.1f", q.measurement().wallMs),
                        ratio, rowStr);
            }
        }
        t.print();
    }

    // -------------------------------------------------------------------
    // 9. Computing environment
    // -------------------------------------------------------------------
    private static void printComputingEnv(List<ScenarioResult> results) {
        System.out.println();
        System.out.println(BOLD + "Computing Environment" + RESET);

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
