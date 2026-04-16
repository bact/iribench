# sameas-bench-java

Benchmarks the computational cost of versioned IRIs in the SPDX ontology —
the problem where each SPDX release uses its own namespace
(`https://spdx.org/rdf/3.1/terms/` vs `3.0.1/terms/` etc.) rather than a
shared canonical one.

Java port of [sameas-bench](../sameas-bench) (Python/rdflib).
Uses **Apache Jena 4.10.0** (ARQ + `OWL_MEM_RULE_INF` + `jena-shacl`).

See: [spdx-spec #1378](https://github.com/spdx/spdx-spec/issues/1378)

---

## Requirements

| Requirement | Version |
| ----------- | ------- |
| Java | 17+ |
| Maven | 3.8+ (or use included `./mvnw`) |

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
sameas-bench --version   # 1.0
sameas-bench smoke       # runs in-memory smoke test, ~20 s
```

---

## Commands

### `smoke` — fast in-memory test (no download)

```bash
sameas-bench smoke                   # 3 toy versions, 4 packages each
sameas-bench smoke --versions 5      # 5 toy versions
sameas-bench smoke --no-owlrl        # skip OWL-RL expansion
```

Good for development and CI. Completes in seconds.

### `quick` — fast run with real SPDX ontologies

```bash
sameas-bench quick                   # 2 versions, 10 packages, 1 repeat, no OWL-RL
sameas-bench quick --versions 3      # 3 versions
sameas-bench quick --owlrl           # include OWL-RL (slow!)
```

Downloads SPDX 3.0.1 and 3.1 TTLs on first run (cached afterward).

### `run` — full benchmark

```bash
sameas-bench run                     # 7 versions, 50 packages, 3 repeats
sameas-bench run --versions 3        # fewer versions
sameas-bench run --packages 20       # fewer packages (faster)
sameas-bench run --no-owlrl          # skip OWL-RL (much faster)
```

### `list-cache` / `clear-cache`

```bash
sameas-bench list-cache    # show cached ontology TTLs + generated SHACL shapes
sameas-bench clear-cache   # delete cached TTLs (forces re-download on next run)
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
./mvnw package -q                         # builds target/sameas-bench.jar
java -jar target/sameas-bench.jar smoke   # run directly
```

---

## Differences from Python version

| Aspect | Python (rdflib) | Java (Jena) |
| ------ | --------------- | ----------- |
| SPARQL engine | rdflib ARQ (Python) | Jena ARQ (native Java) |
| OWL reasoning | owlrl (pure Python) | `OWL_MEM_RULE_INF` (Jena rule engine) |
| SHACL | pyshacl | jena-shacl |
| Reasoner approach | Pre-materialization (owlrl adds all closure triples) | Materialization by copying `OntModel` to plain `Model` |
| Performance | Baseline | Typically 5–50× faster for large graphs |
