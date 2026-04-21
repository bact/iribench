package cc.bact.iribench.ontology;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Constants {
    private Constants() {}

    public static final String SHARED_BASE = "https://spdx.org/rdf/3/terms/";

    public static final Map<String, String> REAL_VERSIONS;
    static {
        REAL_VERSIONS = new LinkedHashMap<>();
        REAL_VERSIONS.put("3.0.1", "https://spdx.github.io/spdx-spec/v3.0.1/rdf/spdx-model.ttl");
        REAL_VERSIONS.put("3.1",   "https://spdx.github.io/spdx-spec/v3.1/rdf/spdx-model.ttl");
    }

    public static final List<String> ALL_SYNTHETIC_VERSIONS =
        Arrays.asList("3.2","3.3","3.4","3.5","3.6","3.7","3.8","3.9");

    public static final int MAX_VERSIONS = 10;

    public static final Path CACHE_DIR =
        Path.of(System.getProperty("user.home"), ".cache", "iribench");

    public static String versionBaseIri(String version) {
        return "https://spdx.org/rdf/" + version + "/terms/";
    }

    public static List<String> versionsForN(int n) {
        if (n < 1 || n > MAX_VERSIONS)
            throw new IllegalArgumentException("n must be 1-" + MAX_VERSIONS + ", got " + n);
        List<String> real = List.copyOf(REAL_VERSIONS.keySet());
        List<String> result = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(n, real.size()); i++) result.add(real.get(i));
        for (int i = 0; i < Math.max(0, n - real.size()); i++) result.add(ALL_SYNTHETIC_VERSIONS.get(i));
        return result;
    }
}
