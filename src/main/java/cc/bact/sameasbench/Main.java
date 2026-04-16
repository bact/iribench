package cc.bact.sameasbench;

import picocli.CommandLine;
import picocli.CommandLine.*;

import cc.bact.sameasbench.benchmark.BenchmarkRunner;
import cc.bact.sameasbench.benchmark.ScenarioResult;
import cc.bact.sameasbench.ontology.*;
import cc.bact.sameasbench.report.ReportPrinter;
import cc.bact.sameasbench.toy.ToyOntology;

import java.nio.file.*;
import java.util.*;

@Command(
    name = "sameas-bench",
    version = "1.0",
    mixinStandardHelpOptions = true,
    subcommands = {Main.RunCmd.class, Main.QuickCmd.class, Main.SmokeCmd.class,
                   Main.ListCacheCmd.class, Main.ClearCacheCmd.class},
    description = {
        "sameas-bench-java -- benchmark the computational cost of versioned IRIs in the SPDX ontology.",
        "",
        "Four benchmark sections:",
        "  1. Shared namespace  - canonical https://spdx.org/rdf/3/terms/",
        "  2. Versioned 2-ver   - 3.0.1 + 3.1, own namespaces",
        "  3. Versioned N-ver   - up to 10 versions",
        "  4. Reasoner tests    - owl:equivalentClass vs rdfs:subClassOf chain",
        "",
        "SPARQL strategies: direct / union / owlrl+query",
        "SHACL: per-version shapes / canonical+OWL-RL",
        "",
        "Uses Apache Jena 4.10.0 (ARQ + OWL_MEM_RULE_INF + jena-shacl).",
        "See: https://github.com/spdx/spdx-spec/issues/1378"
    }
)
public class Main implements Runnable {

    public static void main(String[] args) {
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
        @Option(names = {"-n","--versions"}, defaultValue = "7", showDefaultValue = Help.Visibility.ALWAYS,
                description = "Number of ontology versions (1-10). First 2 are real SPDX TTLs.")
        int versions;
        @Option(names = {"-p","--packages"}, defaultValue = "50", showDefaultValue = Help.Visibility.ALWAYS,
                description = "Packages generated per ontology version.")
        int packages;
        @Option(names = {"-r","--repeats"}, defaultValue = "3", showDefaultValue = Help.Visibility.ALWAYS,
                description = "Times each query is repeated (results averaged).")
        int repeats;
        @Option(names = "--no-owlrl", description = "Skip OWL-RL expansion queries.")
        boolean skipOwlrl;
        @Option(names = {"-v","--verbose"}, description = "Show per-step progress.")
        boolean verbose;

        @Override public void run() {
            execute(versions, packages, repeats, skipOwlrl, verbose, false);
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
        @Option(names = "--owlrl", description = "Force OWL-RL even in quick mode.")
        boolean forceOwlrl;
        @Option(names = {"-v","--verbose"}) boolean verbose;

        @Override public void run() {
            boolean owl = forceOwlrl || !skipOwlrl;
            execute(versions, packages, repeats, !owl, verbose, false);
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
        @Option(names = "--no-owlrl") boolean skipOwlrl;

        @Override public void run() {
            execute(versions, packages, 1, skipOwlrl, true, true);
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
                        boolean skipOwlrl, boolean verbose, boolean useToy) {
        boolean owlEnabled = !skipOwlrl;
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
                    "  (sameas-bench clear-cache to re-download)");
                List<String> vList = Constants.versionsForN(nVersions);
                versions = OntologyLoader.loadVersions(vList, verbose);
                sharedBase = Constants.SHARED_BASE;
                printVersionTable(versions);
            }

            System.out.println();
            System.out.printf("\033[1;36mRunning benchmarks ...\033[0m  " +
                "(%d version(s), %d pkg/ver, %d repeat(s), OWL-RL=%s)%n",
                nVersions, packages, repeats, owlEnabled ? "on" : "off");

            List<ScenarioResult> results =
                BenchmarkRunner.runAll(versions, sharedBase, packages, repeats, verbose, owlEnabled);

            ReportPrinter.printAll(results, packages, repeats, sharedBase);

        } catch (Exception e) {
            System.err.println("\033[1;31mError:\033[0m " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
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
