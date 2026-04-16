package cc.bact.sameasbench.datagen;

public record GeneratorConfig(
    int numPackages,
    int numFilesPerPackage,
    boolean includeLicensing,
    boolean includeSnippets,
    int seed
) {
    public GeneratorConfig() { this(50, 2, true, true, 42); }
    public GeneratorConfig(int numPackages, int seed) { this(numPackages, 2, true, true, seed); }
}
