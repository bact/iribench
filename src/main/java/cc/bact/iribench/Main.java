package cc.bact.iribench;

import picocli.CommandLine;
import picocli.CommandLine.*;

import cc.bact.iribench.benchmark.BenchmarkRunner;
import cc.bact.iribench.benchmark.ReasonerType;
import cc.bact.iribench.benchmark.ScenarioResult;
import cc.bact.iribench.ontology.*;
import cc.bact.iribench.report.ReportPrinter;
import cc.bact.iribench.toy.ToyOntology;

import org.apache.jena.sys.JenaSystem;

import java.nio.file.*;
import java.util.*;

@Command(
    name = "iribench",
    version = "1.0",
    mixinStandardHelpOptions = true,
    subcommands = {Main.RunCmd.class, Main.QuickCmd.class, Main.SmokeCmd.class,
                   Main.ListCacheCmd.class, Main.ClearCacheCmd.class},
    description = {
        "iribench -- benchmark the computational cost of versioned IRIs in the SPDX ontology.",
        "",
        "Eight benchmark query types:",
        "  - Identity: Find packages, licenses, and dependency chains",
        "  - Hierarchy: subClassOf (1-hop, 2-hop, and deep transitivity)",
        "  - Ontology: Property domain inference and type counting",
        "",
        "Scenarios compared:",
        "  - Shared (Baseline): Canonical namespaces",
        "  - Versioned (N-versions): Per-version namespaces linked via owl:equivalentClass",
        "",
        "SPARQL strategies: direct / union / owlrl",
        "SHACL: per-version shapes / canonical+OWL-RL",
        "",
        "Uses Apache Jena 6.0 (ARQ + OWL reasoner + jena-shacl).",
        "See: https://github.com/spdx/spdx-spec/issues/1378"
    }
)
public class Main implements Runnable {

    public static void main(String[] args) {
        // Fat JARs break Jena's ServiceLoader init order; force explicit init.
        JenaSystem.init();
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override public void run() {
        new CommandLine(this).usage(System.out);
    }

    // -----------------------------------------------------------------------
    // run command
    // -----------------------------------------------------------------------
    @Command(name = "run", description = "Full benchmark using real SPDX ontologies (downloads on first run).",
             mixinStandardHelpOptions = true)
    static class RunCmd implements Runnable {
        @Option(names = {"-n","--versions"}, defaultValue = "5", showDefaultValue = Help.Visibility.ALWAYS,
                description = "Number of ontology versions (1-10).")
        int versions;
        @Option(names = {"-p","--packages"}, defaultValue = "25", showDefaultValue = Help.Visibility.ALWAYS,
                description = "Packages generated per ontology version.")
        int packages;
        @Option(names = {"-r","--repeats"}, defaultValue = "3", showDefaultValue = Help.Visibility.ALWAYS,
                description = "Times each query is repeated (results averaged).")
        int repeats;
        @Option(names = "--no-owlrl", description = "Skip reasoning expansion queries.")
        boolean skipOwlrl;
        @Option(names = "--reasoner", defaultValue = "jena-mini", 
                description = "Reasoner: jena-full, jena-mini (default), jena-micro, or spdx-custom (recommended).")
        String reasoner = "jena-mini";
        @Option(names = {"-v","--verbose"}, defaultValue = "true", description = "Show per-step progress.")
        boolean verbose = true;

        @Override public void run() {
            execute(versions, packages, repeats, skipOwlrl, reasoner, verbose, false);
        }
    }

    // -----------------------------------------------------------------------
    // quick command
    // -----------------------------------------------------------------------
    @Command(name = "quick", description = "Quick run: fewer packages, 1 repeat, OWL-RL off by default.",
             mixinStandardHelpOptions = true)
    static class QuickCmd implements Runnable {
        @Option(names = {"-n","--versions"}, defaultValue = "2", showDefaultValue = Help.Visibility.ALWAYS)
        int versions;
        @Option(names = {"-p","--packages"}, defaultValue = "10", showDefaultValue = Help.Visibility.ALWAYS)
        int packages;
        @Option(names = {"-r","--repeats"}, defaultValue = "1", showDefaultValue = Help.Visibility.ALWAYS)
        int repeats;
        @Option(names = "--no-owlrl", defaultValue = "true", description = "Skip OWL-RL (default for quick).")
        boolean skipOwlrl;
        @Option(names = "--owlrl", description = "Force reasoning even in quick mode.")
        boolean forceOwlrl;
        @Option(names = "--reasoner", defaultValue = "jena-mini", 
                description = "Reasoner: jena-full, jena-mini, jena-micro, or spdx-custom (recommended).")
        String reasoner = "jena-mini";
        @Option(names = {"-v","--verbose"}, defaultValue = "true") boolean verbose = true;

        @Override public void run() {
            boolean owl = forceOwlrl || !skipOwlrl;
            execute(versions, packages, repeats, !owl, reasoner, verbose, false);
        }
    }

    // -----------------------------------------------------------------------
    // smoke command
    // -----------------------------------------------------------------------
    @Command(name = "smoke", description = {
        "Smoke test using tiny in-memory toy ontology (no download needed).",
        "Verifies all benchmark sections in seconds. Good for CI and development."
    }, mixinStandardHelpOptions = true)
    static class SmokeCmd implements Runnable {
        @Option(names = {"-n","--versions"}, defaultValue = "3", showDefaultValue = Help.Visibility.ALWAYS)
        int versions;
        @Option(names = {"-p","--packages"}, defaultValue = "4", showDefaultValue = Help.Visibility.ALWAYS)
        int packages;
        @Option(names = "--no-owlrl", description = "Skip reasoning expansion queries.")
        boolean skipOwlrl;
        @Option(names = "--reasoner", defaultValue = "jena-mini", 
                description = "Reasoner: jena-full, jena-mini, jena-micro, or spdx-custom (recommended).")
        String reasoner = "jena-mini";

        @Override public void run() {
            execute(versions, packages, 1, skipOwlrl, reasoner, true, true);
        }
    }

    // -----------------------------------------------------------------------
    // list-cache command
    // -----------------------------------------------------------------------
    @Command(name = "list-cache", description = "List cached TTL files.", mixinStandardHelpOptions = true)
    static class ListCacheCmd implements Runnable {
        @Override public void run() {
            Path dir = Constants.CACHE_DIR;
            if (!Files.exists(dir)) { System.out.println("Cache empty: " + dir); return; }
            try {
                List<Path> files = Files.list(dir).filter(p -> p.toString().endsWith(".ttl"))
                    .sorted().toList();
                if (files.isEmpty()) { System.out.println("Cache empty: " + dir); return; }
                System.out.println("\nCached files  (" + dir + ")\n");
                List<Path> onto = files.stream().filter(p -> !p.getFileName().toString().contains("-shapes")).toList();
                List<Path> shapes = files.stream().filter(p -> p.getFileName().toString().contains("-shapes")).toList();
                if (!onto.isEmpty()) {
                    System.out.println("Ontology TTLs:");
                    for (Path f : onto) System.out.printf("  %-40s  %6d KB%n",
                        f.getFileName(), Files.size(f) / 1024);
                }
                if (!shapes.isEmpty()) {
                    System.out.println("\nGenerated SHACL shapes:");
                    for (Path f : shapes) System.out.printf("  %-40s  %6d KB%n",
                        f.getFileName(), Files.size(f) / 1024);
                }
                System.out.println();
            } catch (Exception e) { System.err.println("Error: " + e.getMessage()); }
        }
    }

    // -----------------------------------------------------------------------
    // clear-cache command
    // -----------------------------------------------------------------------
    @Command(name = "clear-cache", description = "Delete all cached TTL files.", mixinStandardHelpOptions = true)
    static class ClearCacheCmd implements Runnable {
        @Override public void run() {
            Path dir = Constants.CACHE_DIR;
            if (!Files.exists(dir)) { System.out.println("Cache already empty."); return; }
            try {
                List<Path> files = Files.list(dir).filter(p -> p.toString().endsWith(".ttl")).toList();
                if (files.isEmpty()) { System.out.println("Cache already empty."); return; }
                for (Path f : files) { Files.delete(f); System.out.println("  Deleted " + f.getFileName()); }
                System.out.println("\nCleared " + files.size() + " cached TTL file(s).");
            } catch (Exception e) { System.err.println("Error: " + e.getMessage()); }
        }
    }

    // -----------------------------------------------------------------------
    // Shared execution logic
    // -----------------------------------------------------------------------
    static void execute(int nVersions, int packages, int repeats,
                        boolean skipOwlrl, String reasonerStr, boolean verbose, boolean useToy) {
        boolean owlEnabled = !skipOwlrl;
        ReasonerType reasonerType = ReasonerType.MINI;
        if (reasonerStr != null) {
            String r = reasonerStr.toLowerCase();
            if (r.equals("jena-full")) reasonerType = ReasonerType.FULL;
            else if (r.equals("jena-mini")) reasonerType = ReasonerType.MINI;
            else if (r.equals("jena-micro")) reasonerType = ReasonerType.MICRO;
            else if (r.equals("spdx-custom")) reasonerType = ReasonerType.SPDX_CUSTOM;
            else {
                System.err.println("Error: Unknown reasoner '" + reasonerStr + "'.");
                System.err.println("Available reasoners: jena-full, jena-mini, jena-micro, spdx-custom");
                System.exit(1);
            }
        }
        if (reasonerType == null) {
            reasonerType = ReasonerType.MINI;
        }

        System.out.printf("\033[1;36miribench\033[0m v1.0  |  Apache Jena 6.0  |  jena-shacl%n");
        System.out.println();

        try {
            Map<String, OntologyVersion> versions;
            String sharedBase;

            if (useToy) {
                System.out.println("\033[1;36mUsing toy ontology (no download) ...\033[0m");
                System.out.printf("  Schema: ~20 terms/version . %d versions generated in memory%n", nVersions);
                versions = ToyOntology.makeVersions(nVersions);
                sharedBase = ToyOntology.TOY_SHARED_BASE;
            } else {
                System.out.println("\033[1;36mLoading SPDX ontologies ...\033[0m");
                System.out.println("  Cache: " + Constants.CACHE_DIR +
                    "  (iribench clear-cache to re-download)");
                List<String> vList = Constants.versionsForN(nVersions);
                versions = OntologyLoader.loadVersions(vList, verbose);
                sharedBase = Constants.SHARED_BASE;
                printVersionTable(versions);
            }

            System.out.println();
            if (!useToy) warnIfHeavy(nVersions, packages, repeats, owlEnabled, reasonerType);
            String rLabel = (reasonerType != null) ? reasonerType.label() : "Unknown";
            System.out.printf("\033[1;36mRunning benchmarks ...\033[0m  " +
                "(%d version(s), %d pkg/ver, %d repeat(s), Reasoner=%s, Enabled=%s)%n",
                nVersions, packages, repeats, rLabel, owlEnabled ? "on" : "off");

            List<ScenarioResult> results =
                BenchmarkRunner.runAll(versions, sharedBase, packages, repeats, verbose, owlEnabled, reasonerType);

            ReportPrinter.printAll(results, packages, repeats, sharedBase, reasonerType);

        } catch (Exception e) {
            System.err.println("\033[1;31mError:\033[0m " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static long estimateOwlRlMs(int n, int pkgs, ReasonerType type) {
        // Baseline: ~0.02s for 2 versions, 10 packages on a typical 2021 laptop (Custom reasoner)
        double nFactor = Math.pow((double) n / 2.0, 1.9);
        double pFactor = Math.pow((double) pkgs / 10.0, 1.3);
        double base = 20.0;
        switch (type) {
            case FULL -> base = 5000.0; // Jena Full OWL is very heavy
            case MINI -> base = 1000.0; // OWL Mini is lighter but still processes schema
            case MICRO -> base = 200.0; // OWL Micro is minimal
            case SPDX_CUSTOM -> base = 20.0;
        }
        return (long) (base * nFactor * pFactor);
    }

    private static String fmtDuration(long ms) {
        if (ms < 60_000L) return (ms / 1000) + " s";
        long m = ms / 60_000L;
        long s = (ms % 60_000L) / 1000;
        return m + "m " + s + "s";
    }

    private static void warnIfHeavy(int n, int pkgs, int repeats, boolean owlEnabled, ReasonerType type) {
        if (!owlEnabled) {
            System.out.printf("  \033[2mEstimated run time: ~10-30 s  (Reasoning skipped — only UNION + SHACL)\033[0m%n");
            return;
        }
        // 8 queries x repeats per scenario; all scenarios run reasoning if enabled
        int owlScenarios = 1;
        if (n >= 2) owlScenarios += 2;
        if (n > 2) owlScenarios += 2;
        
        long owlMs = estimateOwlRlMs(n, pkgs, type) * 8L * repeats * owlScenarios;
        long totalMs = owlMs + 5_000L; // add 5s floor for UNION/SHACL
        long WARN_THRESHOLD_MS = 60_000L;
        String rLabel = (type != null) ? type.label() : "Unknown";
        if (totalMs > WARN_THRESHOLD_MS) {
            System.out.printf(
                "  \033[1;33m⚠ Time warning:\033[0m %s estimated ~%s total. " +
                "Use \033[1m--no-owlrl\033[0m to skip.%n",
                rLabel, fmtDuration(totalMs));
        } else {
            System.out.printf("  \033[2mEstimated run time: ~%s (%s included, on-demand inference)\033[0m%n",
                fmtDuration(totalMs), rLabel);
        }
    }

    static void printVersionTable(Map<String, OntologyVersion> versions) {
        System.out.printf("  Loaded %d ontology versions:%n", versions.size());
        for (Map.Entry<String, OntologyVersion> e : versions.entrySet()) {
            OntologyVersion ov = e.getValue();
            String badge = switch (ov.source()) {
                case "downloaded" -> "\033[32mdownloaded\033[0m";
                case "cached"     -> "\033[2mcached    \033[0m";
                case "simulated"  -> "\033[33msimulated \033[0m";
                default           -> ov.source();
            };
            System.out.printf("    [%s]  v%s: %d cls . %d prop . %d ind%n",
                badge, ov.version(), ov.classes().size(), ov.properties().size(), ov.individuals().size());
        }
    }
}
