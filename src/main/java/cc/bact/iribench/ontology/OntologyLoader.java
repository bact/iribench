package cc.bact.iribench.ontology;

import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

public class OntologyLoader {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    public static OntologyVersion loadVersion(String version, boolean verbose) throws Exception {
        Files.createDirectories(Constants.CACHE_DIR);
        Path cache = Constants.CACHE_DIR.resolve("spdx-" + version + ".ttl");
        String baseIri = Constants.versionBaseIri(version);
        String source, provenance;

        if (Constants.REAL_VERSIONS.containsKey(version)) {
            if (Files.exists(cache)) {
                source = "cached";
                provenance = "cache: " + cache.getFileName();
                if (verbose) System.out.printf("  [cached]    SPDX %s  (%s)%n", version, cache.getFileName());
            } else {
                source = "downloaded";
                String url = Constants.REAL_VERSIONS.get(version);
                provenance = url;
                if (verbose) System.out.printf("  [download]  SPDX %s  <- %s%n", version, url);
                String ttl = fetch(url);
                Files.writeString(cache, ttl);
            }
        } else {
            source = "simulated";
            provenance = "patched from SPDX 3.1 (namespace -> " + baseIri + ")";
            if (!Files.exists(cache)) {
                Path base31Cache = Constants.CACHE_DIR.resolve("spdx-3.1.ttl");
                if (!Files.exists(base31Cache)) {
                    if (verbose) System.out.println("  [download]  SPDX 3.1 (needed as simulation base)");
                    Files.writeString(base31Cache, fetch(Constants.REAL_VERSIONS.get("3.1")));
                }
                String ttl31 = Files.readString(base31Cache);
                String patched = ttl31.replace(Constants.versionBaseIri("3.1"), baseIri);
                Files.writeString(cache, patched);
            }
            if (verbose) System.out.printf("  [simulated] SPDX %s  (namespace-patched copy of 3.1)%n", version);
        }

        Model g = ModelFactory.createDefaultModel();
        try (InputStream is = Files.newInputStream(cache)) {
            RDFDataMgr.read(g, is, baseIri, Lang.TURTLE);
        }

        return new OntologyVersion(
            version, baseIri, g,
            extractClasses(g, baseIri),
            extractProperties(g, baseIri),
            extractIndividuals(g, baseIri),
            source, provenance
        );
    }

    public static Map<String, OntologyVersion> loadVersions(List<String> versions, boolean verbose) throws Exception {
        Map<String, OntologyVersion> result = new LinkedHashMap<>();
        for (String v : versions) result.put(v, loadVersion(v, verbose));
        return result;
    }

    private static String fetch(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "text/turtle, */*")
            .timeout(Duration.ofSeconds(60))
            .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new IOException("HTTP " + resp.statusCode() + " fetching " + url);
        return resp.body();
    }

    private static List<String> extractClasses(Model g, String base) {
        List<String> result = new ArrayList<>();
        g.listSubjectsWithProperty(RDF.type, OWL.Class).forEachRemaining(r -> {
            if (r.isURIResource() && r.getURI().startsWith(base)) result.add(r.getURI());
        });
        return result;
    }

    private static List<String> extractProperties(Model g, String base) {
        List<String> result = new ArrayList<>();
        for (Resource pt : List.of(OWL.ObjectProperty, OWL.DatatypeProperty, OWL.AnnotationProperty)) {
            g.listSubjectsWithProperty(RDF.type, pt).forEachRemaining(r -> {
                if (r.isURIResource() && r.getURI().startsWith(base)) result.add(r.getURI());
            });
        }
        return result;
    }

    private static List<String> extractIndividuals(Model g, String base) {
        List<String> result = new ArrayList<>();
        g.listSubjectsWithProperty(RDF.type, OWL2.NamedIndividual).forEachRemaining(r -> {
            if (r.isURIResource() && r.getURI().startsWith(base)) result.add(r.getURI());
        });
        return result;
    }
}
