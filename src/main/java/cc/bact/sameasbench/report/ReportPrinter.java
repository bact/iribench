package cc.bact.sameasbench.report;

import cc.bact.sameasbench.benchmark.*;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.*;
import java.util.stream.Collectors;

public class ReportPrinter {

    // ANSI codes
    private static final String BOLD    = "\033[1m";
    private static final String CYAN    = "\033[36m";
    private static final String YELLOW  = "\033[33m";
    private static final String GREEN   = "\033[32m";
    private static final String RED     = "\033[31m";
    private static final String DIM     = "\033[2m";
    private static final String RESET   = "\033[0m";

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
                int maxLine = 0;
                for (String line : headers[i].split("\n", -1)) {
                    maxLine = Math.max(maxLine, line.length());
                }
                widths[i] = maxLine;
            }
        }

        void addRow(String... cells) {
            // Pad to correct number of columns
            String[] row = new String[headers.length];
            for (int i = 0; i < headers.length; i++) {
                row[i] = (i < cells.length && cells[i] != null) ? cells[i] : "";
                widths[i] = Math.max(widths[i], stripAnsi(row[i]).length());
            }
            rows.add(row);
        }

        private String stripAnsi(String s) {
            return s.replaceAll("\033\\[[\\d;]*m", "");
        }

        private String border() {
            StringBuilder sb = new StringBuilder("+");
            for (int w : widths) {
                for (int i = 0; i < w + 2; i++) sb.append('-');
                sb.append('+');
            }
            return sb.toString();
        }

        private String row(String[] cells) {
            StringBuilder sb = new StringBuilder("|");
            for (int i = 0; i < headers.length; i++) {
                String cell = (i < cells.length && cells[i] != null) ? cells[i] : "";
                int visLen = stripAnsi(cell).length();
                int pad = widths[i] - visLen;
                sb.append(' ').append(cell);
                for (int p = 0; p < pad; p++) sb.append(' ');
                sb.append(" |");
            }
            return sb.toString();
        }

        void print() {
            String b = border();
            System.out.println(b);
            // Split headers on \n
            String[][] splitH = new String[headers.length][];
            int maxLines = 1;
            for (int i = 0; i < headers.length; i++) {
                splitH[i] = headers[i].split("\n", -1);
                maxLines = Math.max(maxLines, splitH[i].length);
            }
            for (int line = 0; line < maxLines; line++) {
                StringBuilder sb = new StringBuilder("|");
                for (int i = 0; i < headers.length; i++) {
                    String cell = (line < splitH[i].length) ? splitH[i][line] : "";
                    int pad = widths[i] - cell.length();
                    sb.append(' ').append(cell);
                    for (int p = 0; p < pad; p++) sb.append(' ');
                    sb.append(" |");
                }
                System.out.println(sb);
            }
            System.out.println(b);
            for (String[] r : rows) System.out.println(row(r));
            System.out.println(b);
        }
    }

    // -------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------
    public static void printAll(List<ScenarioResult> results, int pkgPerVersion, int repeats, String sharedBase) {
        List<ScenarioResult> baseResults = results.stream()
            .filter(r -> !isReasoner(r)).collect(Collectors.toList());
        List<ScenarioResult> reasonerResults = results.stream()
            .filter(ReportPrinter::isReasoner).collect(Collectors.toList());

        printHeader();
        printMethodology();
        printGraphStats(baseResults);
        printEquivBreakdown(baseResults);
        printSparqlResults(baseResults);
        printShaclResults(baseResults);
        printSummary(baseResults);
        printReasonerResults(reasonerResults);
        printComputingEnv(results);
    }

    private static boolean isReasoner(ScenarioResult r) {
        return r.scenarioName.startsWith("Reasoner -");
    }

    // -------------------------------------------------------------------
    // 1. Header
    // -------------------------------------------------------------------
    private static void printHeader() {
        System.out.println();
        System.out.println(BOLD + CYAN + "========================================================================" + RESET);
        System.out.println(BOLD + CYAN + "  sameas-bench-java — SPDX Versioned IRI Overhead Benchmark" + RESET);
        System.out.println(BOLD + CYAN + "  Apache Jena 6.0  |  OWL reasoner  |  jena-shacl" + RESET);
        System.out.println(BOLD + CYAN + "========================================================================" + RESET);
    }

    // -------------------------------------------------------------------
    // 2. Methodology
    // -------------------------------------------------------------------
    private static void printMethodology() {
        System.out.println();
        System.out.println(BOLD + "Approaches compared:" + RESET);
        System.out.println("  " + GREEN + "direct" + RESET +
            "        All SBOMs use shared canonical IRI https://spdx.org/rdf/3/terms/");
        System.out.println("  " + YELLOW + "union" + RESET +
            "         Each SPDX version has its own IRI prefix; queries use SPARQL UNION");
        System.out.println("  " + CYAN + "owlrl+query" + RESET +
            "   owl:equivalentClass graph + OWL-RL materialization, then canonical query");
        System.out.println();
        System.out.println(BOLD + "Limitations:" + RESET);
        System.out.println("  - OWL-RL uses Jena's built-in OWL reasoner (not full OWL 2 RL)");
        System.out.println("  - Synthetic SBOM data mirrors real SPDX 3.x class/property structure");
        System.out.println("  - Memory measurement is approximate (JVM GC may affect readings)");
        System.out.println("  - Wall time includes JVM overhead; CPU time is per-thread");
    }

    // -------------------------------------------------------------------
    // 3. Graph stats
    // -------------------------------------------------------------------
    private static void printGraphStats(List<ScenarioResult> results) {
        System.out.println();
        System.out.println(BOLD + "Graph Statistics" + RESET);
        AsciiTable t = new AsciiTable("Namespace\nscenario", "Versions", "Data Triples", "Equiv Triples",
            "Total Triples", "Build Time (ms)", "Build Mem (MB)");
        for (ScenarioResult r : results) {
            t.addRow(
                r.scenarioName,
                String.valueOf(r.versionsCount),
                String.format("%,d", r.dataTriples),
                r.equivTriples > 0 ? String.format("%,d", r.equivTriples) : "-",
                String.format("%,d", r.totalTriples),
                r.buildMeasurement != null ? String.format("%.1f", r.buildMeasurement.wallMs) : "-",
                r.buildMeasurement != null ? String.format("%.1f", r.buildMeasurement.peakMemoryMb) : "-"
            );
        }
        t.print();
    }

    // -------------------------------------------------------------------
    // 4. Equiv breakdown
    // -------------------------------------------------------------------
    private static void printEquivBreakdown(List<ScenarioResult> results) {
        List<ScenarioResult> versioned = results.stream()
            .filter(r -> r.equivStats != null).collect(Collectors.toList());
        if (versioned.isEmpty()) return;

        System.out.println();
        System.out.println(BOLD + "Equivalence Graph Breakdown" + RESET);
        AsciiTable t = new AsciiTable("Namespace\nscenario", "equiv:Class pairs", "equiv:Prop pairs",
            "sameAs pairs", "Total Classes", "Total Props", "Total Individuals");
        for (ScenarioResult r : versioned) {
            EquivStats e = r.equivStats;
            t.addRow(
                r.scenarioName,
                String.valueOf(e.equivClassPairs()),
                String.valueOf(e.equivPropPairs()),
                String.valueOf(e.sameAsPairs()),
                String.valueOf(e.totalClasses()),
                String.valueOf(e.totalProperties()),
                String.valueOf(e.totalIndividuals())
            );
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
                if (!queryNames.contains(q.name())) queryNames.add(q.name());
            }
        }

        for (String qname : queryNames) {
            System.out.println();
            System.out.println("  " + BOLD + qname + RESET);

            // Collect all methods for this query across scenarios
            AsciiTable t = new AsciiTable("Namespace\nscenario", "Method", "Wall ms", "CPU ms", "Rows", "Timed out?");
            for (ScenarioResult r : results) {
                for (QueryResult q : r.queries) {
                    if (!q.name().equals(qname)) continue;
                    String wallStr = String.format("%.1f", q.measurement().wallMs);
                    String cpuStr  = String.format("%.1f", q.measurement().cpuUserMs);
                    String rows    = String.valueOf(q.resultCount());
                    String to      = q.timedOut() ? RED + "YES" + RESET : "no";
                    String method  = methodColored(q.method());
                    t.addRow(r.scenarioName, method, wallStr, cpuStr, rows, to);
                }
            }
            t.print();
        }
    }

    private static String methodColored(String method) {
        return switch (method) {
            case "direct"      -> GREEN  + method + RESET;
            case "union"       -> YELLOW + method + RESET;
            case "owlrl+query" -> CYAN   + method + RESET;
            default            -> DIM    + method + RESET;
        };
    }

    // -------------------------------------------------------------------
    // 6. SHACL results
    // -------------------------------------------------------------------
    private static void printShaclResults(List<ScenarioResult> results) {
        System.out.println();
        System.out.println(BOLD + "SHACL Validation Results" + RESET);
        AsciiTable t = new AsciiTable("Namespace\nscenario", "Shapes config", "Inference",
            "Conforms?", "Violations", "Targets", "Wall ms");
        for (ScenarioResult r : results) {
            for (ShaclResult s : r.shacl) {
                String conforms = s.conforms() ? GREEN + "yes" + RESET : RED + "NO" + RESET;
                t.addRow(
                    r.scenarioName,
                    truncate(s.name(), 45),
                    s.inference(),
                    conforms,
                    String.valueOf(s.violationCount()),
                    String.valueOf(s.targetCount()),
                    String.format("%.1f", s.measurement().wallMs)
                );
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

        // Find shared namespace baseline
        Optional<ScenarioResult> baseOpt = results.stream()
            .filter(r -> r.scenarioName.startsWith("Shared (")).findFirst();
        if (baseOpt.isEmpty()) {
            System.out.println("  (no shared namespace baseline found)");
            return;
        }
        ScenarioResult base = baseOpt.get();

        // For each query in base, build a lookup of direct wall time by query name
        Map<String, Double> baseDirectTime = new HashMap<>();
        for (QueryResult q : base.queries) {
            baseDirectTime.put(q.name(), q.measurement().wallMs);
        }

        AsciiTable t = new AsciiTable("Namespace\nscenario", "Query", "Method",
            "Wall ms", "vs. direct (x)", "Rows");
        for (ScenarioResult r : results) {
            for (QueryResult q : r.queries) {
                Double baseline = baseDirectTime.get(q.name());
                String ratio;
                if (baseline != null && baseline > 0) {
                    double x = q.measurement().wallMs / baseline;
                    ratio = String.format("%.2fx", x);
                    if (x > 3.0) ratio = RED + ratio + RESET;
                    else if (x > 1.5) ratio = YELLOW + ratio + RESET;
                    else ratio = GREEN + ratio + RESET;
                } else {
                    ratio = "-";
                }
                t.addRow(r.scenarioName, q.name(), methodColored(q.method()),
                    String.format("%.1f", q.measurement().wallMs),
                    ratio,
                    String.valueOf(q.resultCount()));
            }
        }
        t.print();
    }

    // -------------------------------------------------------------------
    // 8. Reasoner results
    // -------------------------------------------------------------------
    private static void printReasonerResults(List<ScenarioResult> results) {
        if (results.isEmpty()) return;
        System.out.println();
        System.out.println(BOLD + "Reasoner Inference Tests (equivalentClass x subClassOf chain)" + RESET);

        // Collect all query names
        List<String> queryNames = new ArrayList<>();
        for (ScenarioResult r : results) {
            for (QueryResult q : r.queries) {
                if (!queryNames.contains(q.name())) queryNames.add(q.name());
            }
        }

        if (queryNames.isEmpty()) {
            System.out.println("  (OWL-RL disabled — use --owlrl to enable)");
            return;
        }

        // Table 1: Result counts
        System.out.println();
        System.out.println("  " + BOLD + "Result counts (rows returned)" + RESET);
        String[] colHeaders = new String[results.size() + 1];
        colHeaders[0] = "Query";
        for (int i = 0; i < results.size(); i++)
            colHeaders[i+1] = truncate(results.get(i).scenarioName.replace("Reasoner - ", ""), 30);
        AsciiTable countTable = new AsciiTable(colHeaders);
        for (String qname : queryNames) {
            String[] row = new String[results.size() + 1];
            row[0] = qname;
            for (int i = 0; i < results.size(); i++) {
                ScenarioResult r = results.get(i);
                Optional<QueryResult> qOpt = r.queries.stream()
                    .filter(q -> q.name().equals(qname)).findFirst();
                row[i+1] = qOpt.map(q -> String.valueOf(q.resultCount())).orElse("-");
            }
            countTable.addRow(row);
        }
        countTable.print();

        // Table 2: Wall times
        System.out.println();
        System.out.println("  " + BOLD + "Wall time (ms)" + RESET);
        AsciiTable timeTable = new AsciiTable(colHeaders);
        for (String qname : queryNames) {
            String[] row = new String[results.size() + 1];
            row[0] = qname;
            for (int i = 0; i < results.size(); i++) {
                ScenarioResult r = results.get(i);
                Optional<QueryResult> qOpt = r.queries.stream()
                    .filter(q -> q.name().equals(qname)).findFirst();
                row[i+1] = qOpt.map(q -> {
                    String s = String.format("%.1f", q.measurement().wallMs);
                    if (q.timedOut()) s = RED + "TIMEOUT" + RESET;
                    return s;
                }).orElse("-");
            }
            timeTable.addRow(row);
        }
        timeTable.print();
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
                java.lang.reflect.Method m = osBean.getClass().getMethod("getTotalPhysicalMemorySize");
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
        String jvmName     = System.getProperty("java.vm.name");
        String osName      = System.getProperty("os.name");
        String osVersion   = System.getProperty("os.version");

        // Total wall time across all scenarios
        double totalWallMs = results.stream()
            .flatMap(r -> r.queries.stream())
            .mapToDouble(q -> q.measurement().wallMs)
            .sum();
        // Add build times
        totalWallMs += results.stream()
            .filter(r -> r.buildMeasurement != null)
            .mapToDouble(r -> r.buildMeasurement.wallMs)
            .sum();

        // Peak memory across all
        double peakMemMb = results.stream()
            .flatMap(r -> r.queries.stream())
            .mapToDouble(q -> q.measurement().peakMemoryMb)
            .max().orElse(0);

        System.out.printf("  CPU:          %s%n", cpuBrand);
        System.out.printf("  Logical CPUs: %d%n", cpus);
        System.out.printf("  (Benchmark is single-threaded; logical CPU count shown for reference.)%n");
        if (totalRamBytes > 0)
            System.out.printf("  RAM:          %.1f GB%n", totalRamBytes / (1024.0 * 1024.0 * 1024.0));
        System.out.printf("  JVM:          %s (%s)%n", javaVersion, jvmName);
        System.out.printf("  OS:           %s %s%n", osName, osVersion);
        System.out.printf("  Total wall:   %.1f ms (queries + builds)%n", totalWallMs);
        System.out.printf("  Peak query mem: %.1f MB%n", peakMemMb);
        System.out.println();
    }

    private static String getCpuBrand() {
        // macOS
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sysctl", "-n", "machdep.cpu.brand_string"});
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (!out.isEmpty()) return out;
        } catch (Exception ignored) {}
        // Linux
        try {
            String cpuinfo = java.nio.file.Files.readString(java.nio.file.Path.of("/proc/cpuinfo"));
            for (String line : cpuinfo.split("\n")) {
                if (line.startsWith("model name")) {
                    return line.substring(line.indexOf(':') + 1).trim();
                }
            }
        } catch (Exception ignored) {}
        return System.getProperty("os.arch", "unknown");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }
}
