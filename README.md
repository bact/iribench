# sameas-bench-java

Benchmarks the computational cost of versioned IRIs in the SPDX ontology —
the problem where each SPDX release uses its own namespace
(`https://spdx.org/rdf/3.1/terms/` vs `3.0.1/terms/` etc.) rather than a
shared canonical one.

Java port of [sameas-bench](https://github.com/bact/sameas-bench) (Python/rdflib).
Uses **Apache Jena 6.0** (ARQ + OWL reasoner + `jena-shacl`).

See: [spdx-spec #1378](https://github.com/spdx/spdx-spec/issues/1378)

---

## Requirements

| Requirement | Version |
| ----------- | ------- |
| Java | 25+ |
| Maven | 3.8+ (or use included `./mvnw`) |
| Apache Jena | 6.0 |

No other setup needed. SPDX ontology TTLs are downloaded on first run and
cached in `~/.cache/sameas-bench/`.

---

## Install

```bash
git clone https://github.com/bact/sameas-bench
cd sameas-bench/sameas-bench-java

./install.sh                     # installs to ~/.local/bin/sameas-bench
# or:
PREFIX=/usr/local ./install.sh   # installs to /usr/local/bin/sameas-bench
```

If `~/.local/bin` is not in `PATH`, add it:

```bash
export PATH="$HOME/.local/bin:$PATH"   # add to ~/.zshrc or ~/.bashrc
```

Verify:

```bash
sameas-bench-java --version   # 1.0
sameas-bench-java smoke       # runs in-memory smoke test, ~20 s
```

---

## Commands

### `smoke` — fast in-memory test (no download)

```bash
sameas-bench-java smoke                   # 3 toy versions, 4 packages each
sameas-bench-java smoke --versions 5      # 5 toy versions
sameas-bench-java smoke --no-owlrl        # skip OWL-RL expansion
```

Good for development and CI. Completes in seconds.

### `quick` — fast run with real SPDX ontologies

```bash
sameas-bench-java quick                   # 2 versions, 10 packages, 1 repeat, no OWL-RL
sameas-bench-java quick --versions 3      # 3 versions
sameas-bench-java quick --owlrl           # include OWL-RL (slow!)
```

Downloads SPDX 3.0.1 and 3.1 TTLs on first run (cached afterward).

### `run` — full benchmark

```bash
sameas-bench-java run                     # 7 versions, 50 packages, 3 repeats
sameas-bench-java run --versions 3        # fewer versions
sameas-bench-java run --packages 20       # fewer packages (faster)
sameas-bench-java run --no-owlrl          # skip OWL-RL (much faster)
```

### `list-cache` / `clear-cache`

```bash
sameas-bench-java list-cache    # show cached ontology TTLs + generated SHACL shapes
sameas-bench-java clear-cache   # delete cached TTLs (forces re-download on next run)
```

---

## What the report shows

| Section | What it measures |
|---------|-----------------|
| **Graph Statistics** | Triple counts and build time per scenario |
| **Equivalence Breakdown** | `owl:equivalentClass` / `equivalentProperty` / `sameAs` counts |
| **SPARQL Query Results** | `direct` vs `union` vs `owlrl+query` — wall time and row counts |
| **SHACL Validation** | Per-version shapes (no inference) vs canonical shapes + OWL-RL |
| **Summary** | Overhead ratios: union and OWL-RL vs shared-namespace baseline |
| **Reasoner Tests** | Four graph configs isolating `equivalentClass` × `subClassOf` effects |
| **Computing Environment** | CPU, RAM, JVM, OS, total wall time |

---

## Three strategies compared

| Strategy | How it works | Scales with versions |
| -------- | ------------ | -------------------- |
| `direct` | All data uses shared canonical IRI — no equivalences needed | O(1) |
| `union` | Each version has own IRI; query has one UNION branch per version | O(N) |
| `owlrl+query` | `owl:equivalentClass` hub graph + Jena OWL-RL materialization, then canonical query | super-linear |

---

## Reasoner test — key result

Four graph configurations isolate the effect of each OWL mechanism:

```text
A. versioned data only            →  0 results
B. + rdfs:subClassOf ontology     →  0 results  (versioned types not mapped)
C. + owl:equivalentClass only     →  0 results  (no superclass chain)
D. + both (equiv + subClassOf)    →  ✓ results  (full chain fires)
```

`SELECT ?x WHERE { ?x a canonical:Element }` returns non-zero rows **only** when
both `owl:equivalentClass` and `rdfs:subClassOf` axioms are present.

---

## Building from source (without install)

```bash
./mvnw package -q                              # builds target/sameas-bench.jar
java -jar target/sameas-bench.jar smoke        # run directly
```

---

## Differences from Python version

| Aspect | Python (rdflib) | Java (Jena 6.0) |
| ------ | --------------- | ----------- |
| SPARQL engine | rdflib ARQ (Python) | Jena ARQ (native Java) |
| OWL reasoning | owlrl (pure Python) | Jena OWL reasoner (built-in rule engine) |
| SHACL | pyshacl | jena-shacl |
| Reasoner approach | Pre-materialization (owlrl adds all closure triples) | Materialization by copying `InfModel` to plain `Model` |
| Performance | Baseline | Typically 5–50× faster for large graphs |
