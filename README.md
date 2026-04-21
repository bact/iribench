# iribench

Benchmarks the computational cost of versioned IRIs in the SPDX ontology
(`https://spdx.org/rdf/3.0.1/terms/`, `3.1/terms/` etc.) rather than a
shared canonical one (`https://spdx.org/rdf/3/terms/`).

Java port of [sameas-bench](https://github.com/bact/sameas-bench) (Python/RDFLib).
Uses **Apache Jena 6.0** (ARQ + OWL reasoner + `jena-shacl`) as it is much faster than ones in RDFLib.

See: [spdx-spec #1378](https://github.com/spdx/spdx-spec/issues/1378)

---

## Requirements

| Requirement | Version |
| ----------- | ------- |
| Java | 25+ |
| Maven | 3.8+ (or use included `./mvnw`) |
| Apache Jena | 6.0 |

To avoid `java.lang.OutOfMemoryError` on large ontologies, set JVM max heap size to 16 GB:

```bash
export JAVA_OPTS="-Xms2g -Xmx16g"
```

No other setup needed. SPDX ontology TTLs are downloaded on first run and
cached in `~/.cache/iribench/`.

---

## Install

```bash
git clone https://github.com/bact/iribench
cd iribench

./install.sh                     # installs to ~/.local/bin/iribench
# or:
PREFIX=/usr/local ./install.sh   # installs to /usr/local/bin/iribench
```

If `~/.local/bin` is not in `PATH`, add it:

```bash
export PATH="$HOME/.local/bin:$PATH"   # add to ~/.zshrc or ~/.bashrc
```

Verify:

```bash
iribench --version
iribench smoke       # runs in-memory smoke test, ~20 s
```

---

## Commands

### `smoke` — fast in-memory test (no download)

```bash
iribench smoke                   # 3 toy versions, 4 packages each
iribench smoke --versions 5      # 5 toy versions
```

Good for development and CI. Completes in seconds.

### `quick` — fast run with real SPDX ontologies

```bash
iribench quick                   # 2 versions, 10 packages, 1 repeat, no reasoning
iribench quick --versions 3      # 3 versions
iribench quick --owlrl           # include reasoning (slow with standard reasoners!)
```

Downloads SPDX 3.0.1 and 3.1 TTLs on first run (cached afterward).

### `run` — full benchmark

```bash
iribench run                          # 5 versions, 25 packages, 3 repeats
iribench run --versions 3             # fewer versions
iribench run --reasoner jena-mini     # use OWL Mini (default)
iribench run --reasoner spdx-custom   # use SPDX-optimized reasoner (recommended)
iribench quick --no-owlrl             # skip reasoning (use this if reasoners are too slow)
```

#### Reasoner Options (`--reasoner`)

- `jena-mini` (Default): Jena's standard OWL Mini reasoner.
- `jena-micro`: Lightweight OWL reasoner (faster than OWL Mini; but does not support `owl:sameAs`).
- `jena-full`: Full OWL reasoner (slowest, most complete; better support of `owl:oneOf`, `owl:disjointWith`, etc. than OWL Mini).
- `spdx-custom`: **SPDX-optimized**. A specialized, high-speed reasoner that only processes `owl:sameAs`, `owl:equivalentClass`, and `owl:equivalentProperty`. Optimized specifically for SPDX 3 identity hubbing. **Note:** This reasoner is designed solely for identity linking; it does not support other OWL features and may not function correctly if SPDX is integrated with external ontologies. Use this for large-scale benchmarks where identity resolution is the primary requirement.

### `list-cache` / `clear-cache`

```bash
iribench list-cache    # show cached ontology TTLs + generated SHACL shapes
iribench clear-cache   # delete cached TTLs (forces re-download on next run)
```

---

## What the report shows

| Section | What it measures |
| ------- | ---------------- |
| **Graph Statistics** | Triple counts, build time, and **Inference Expansion** time (`Inf Exp`) per scenario |
| **Equivalence Breakdown** | `owl:equivalentClass` / `equivalentProperty` / `sameAs` counts |
| **SPARQL Query Results** | `direct` vs `union` vs `owlrl` (Selected Reasoner) — wall time and row counts |
| **SHACL Validation** | Per-version shapes (no inference) vs canonical shapes + Selected Reasoner |
| **Summary** | Overhead ratios: union and Selected Reasoner vs shared-namespace baseline |
| **Computing Environment** | CPU, RAM, JVM, OS, total wall time |

---

## Three strategies compared

| Strategy | How it works | Effective complexity | Notes |
| -------- | ------------ | ---------- | ----- |
| `direct` | All data uses shared canonical IRI — no equivalences needed | O(log N) | N is size of data |
| `union` | Each version has own IRI; query has one UNION branch per version | O(V ⋅ log N) | V is number of ontology versions |
| `owlrl` | Selected Reasoner hub graph + Backward Chaining (on-the-fly), then canonical query | O(V ⋅ N<sup>k</sup>) | k is depth of schema rule |

Notes:

- *N* effectively becomes *log N* for `direct` and `union` because of indexing. `owlrl` reasoning requires data-driven joins that indexes alone cannot bypass.
- *k* stays at 2 in SPDX 3.0 and 3.1 cases, as they only use `owl:sameAs`, `owl:equivalentClass`, and `owl:equivalentProperty` which are all 2-way joins.

---

## Building from source (without install)

```bash
./mvnw package -q                        # builds target/iribench.jar
java -jar target/iribench.jar smoke      # run directly
```

---

## Benchmarking strategy (accuracy & isolation)

To ensure high-fidelity measurements and minimize JVM-induced noise, the benchmark uses several isolation strategies:

1. **Global engine warmup**: Before any measurements begin, the JVM and Jena's query/reasoner engines are primed with a synthetic workload. This ensures that JIT compilation and internal engine caches are steady.
2. **Cold-start protection**: Within each scenario, every distinct SPARQL query and SHACL validation is executed once (unmeasured) before the timing repeats start. This isolates the execution performance from the initial parsing and planning phase.
3. **Preemptive garbage collection**: `System.gc()` is explicitly invoked before each scenario block begins. This clears out models and triples from previous scenarios, ensuring that each benchmark starts with a clean heap and reducing the likelihood of a major GC pause during measurement.
4. **Inference engine reuse**: For reasoning scenarios, the `InfModel` is expanded once via `inf.prepare()` before any queries are measured. This allows us to measure the one-time **Inference Expansion** cost separately from the "hot" query performance of the engine.
5. **Hard timeout protection**: Every reasoning and SHACL task is protected by a **5-minute (300s) safety timeout**. If a task exceeds this limit, it is aborted and marked as `[TIMEOUT]` or `[SKIPPED]`, ensuring the benchmark continues even if a reasoner enters an infinite loop or triggers a performance blowout.

---

## Reasoner choice

The Java version allows switching between several reasoning profiles via the `--reasoner` flag.

- **SPDX-optimized** (recommended): Implemented via Jena's `GenericRuleReasoner`. This provides the optimal balance for SPDX identity management:
  - **Completeness**: Fully handles `owl:sameAs`, `owl:equivalentClass`, and `owl:equivalentProperty`.
  - **Efficiency**: Ignores 2000+ complex OWL restrictions that cause performance blowout in standard reasoners.
  - **Coverage**: Specifically implements transitivity for `subClassOf`/`subPropertyOf` and RDFS `domain`/`range` inference.
- **Jena standard reasoners**: `jena-mini`, `jena-micro`, and `jena-full` provide standard-compliant OWL 2 RL/Mini/Full profiles. These are useful for verifying the custom reasoner's accuracy but are significantly slower on real-world SPDX ontologies.

---

## Resilience strategy

The benchmark runner is instrumented to catch `OutOfMemoryError` and `QueryCancelledException` (timeouts). If a specific reasoning task exceeds available resources or the 5-minute safety threshold, the system records the error and proceeds to the next scenario.
> [!TIP]
> If you encounter timeouts with `jena-mini` or `jena-full`, try using `--reasoner jena-micro` or `--reasoner spdx-custom`.

---

## Results

Result summary from `iribench % iribench run --reasoner spdx-custom`
on Apple M4 with 32 GB RAM, on Java 25.0.2 (OpenJDK 64-Bit Server VM):

```text
========================================================================
  iribench — SPDX Versioned IRI Overhead Benchmark
  Apache Jena 6.0  |  SPDX-optimized  |  jena-shacl
========================================================================

Approaches compared:
  direct    All SBOMs use shared canonical IRI https://spdx.org/rdf/3/terms/
  union     Each SPDX version has its own IRI prefix; queries use SPARQL UNION
  owlrl     SPDX-optimized + backward chaining (on-the-fly), then canonical query

  Example (Counting Software instances):
  direct    SELECT (COUNT(?x) AS ?c) { ?x a spdx:Software }
  union     SELECT (COUNT(?x) AS ?c) { { ?x a v301:Software } UNION { ?x a v31:Software } }
  owlrl     (Backward chain owl:equivalentClass), then SELECT ... { ?x a spdx:Software }

[...]

Summary — Overhead vs Shared namespace
+---------------+--------------------------------------+--------+------+----------+--------------+
| Namespace     | Query                                | Method | Wall | vs.      | Rows         |
| scenario      |                                      |        | ms   | baseline |              |
+---------------+--------------------------------------+--------+------+----------+--------------+
| Shared (2)    | Find packages + names                | direct | 1.7  | baseline | 26           |
| Shared (2)    | Packages with licenses               | direct | 1.1  | baseline | 25           |
| Shared (2)    | Dependency chain (2-hop)             | direct | 2.1  | baseline | 15           |
| Shared (2)    | Count elements by type               | direct | 1.6  | baseline | 10           |
| Shared (2)    | subClassOf 1-hop: ?x a Core/Artifact | direct | 0.6  | baseline | 2            |
| Shared (2)    | subClassOf 2-hop: ?x a Core/Element  | direct | 0.5  | baseline | 1            |
| Shared (2)    | Element + leaf type (transitivity)   | direct | 0.8  | baseline | 1            |
| Shared (2)    | rdfs:domain: Core/from->Relationship | direct | 0.9  | baseline | 127          |
| Shared (2)    | Find packages + names                | owlrl  | 1.8  | baseline | 26           |
| Shared (2)    | Packages with licenses               | owlrl  | 1.4  | baseline | 25           |
| Shared (2)    | Dependency chain (2-hop)             | owlrl  | 4.4  | baseline | 15           |
| Shared (2)    | Count elements by type               | owlrl  | 2.8  | baseline | 42           |
| Shared (2)    | subClassOf 1-hop: ?x a Core/Artifact | owlrl  | 0.6  | baseline | 83           |
| Shared (2)    | subClassOf 2-hop: ?x a Core/Element  | owlrl  | 0.8  | baseline | 226          |
| Shared (2)    | Element + leaf type (transitivity)   | owlrl  | 4.5  | baseline | 416          |
| Shared (2)    | rdfs:domain: Core/from->Relationship | owlrl  | 1.1  | baseline | 127          |
| Versioned (2) | Find packages + names                | union  | 1.5  | 0.88x    | 52 (26x2)    |
| Versioned (2) | Packages with licenses               | union  | 1.2  | 1.05x    | 50 (25x2)    |
| Versioned (2) | Dependency chain (2-hop)             | union  | 2.4  | 1.12x    | 30 (15x2)    |
| Versioned (2) | Count elements by type               | union  | 1.4  | 0.90x    | 20 (10x2)    |
| Versioned (2) | subClassOf 1-hop: ?x a Core/Artifact | union  | 0.6  | 1.05x    | 4 (2x2)      |
| Versioned (2) | subClassOf 2-hop: ?x a Core/Element  | union  | 0.6  | 1.14x    | 2 (1x2)      |
| Versioned (2) | Element + leaf type (transitivity)   | union  | 0.9  | 1.15x    | 2 (1x2)      |
| Versioned (2) | rdfs:domain: Core/from->Relationship | union  | 1.0  | 1.11x    | 254 (127x2)  |
| Versioned (2) | Find packages + names                | owlrl  | 1.5  | 0.85x    | 52 (26x2)    |
| Versioned (2) | Packages with licenses               | owlrl  | 1.5  | 1.06x    | 50 (25x2)    |
| Versioned (2) | Dependency chain (2-hop)             | owlrl  | 9.2  | 2.09x    | 30 (15x2)    |
| Versioned (2) | Count elements by type               | owlrl  | 7.0  | 2.48x    | 52           |
| Versioned (2) | subClassOf 1-hop: ?x a Core/Artifact | owlrl  | 0.7  | 1.10x    | 166 (83x2)   |
| Versioned (2) | subClassOf 2-hop: ?x a Core/Element  | owlrl  | 0.9  | 1.12x    | 456          |
| Versioned (2) | Element + leaf type (transitivity)   | owlrl  | 6.6  | 1.49x    | 838          |
| Versioned (2) | rdfs:domain: Core/from->Relationship | owlrl  | 1.2  | 1.15x    | 254 (127x2)  |
| Shared (5)    | Find packages + names                | direct | 0.9  | baseline | 26           |
| Shared (5)    | Packages with licenses               | direct | 0.9  | baseline | 25           |
| Shared (5)    | Dependency chain (2-hop)             | direct | 1.1  | baseline | 15           |
| Shared (5)    | Count elements by type               | direct | 0.9  | baseline | 10           |
| Shared (5)    | subClassOf 1-hop: ?x a Core/Artifact | direct | 0.5  | baseline | 2            |
| Shared (5)    | subClassOf 2-hop: ?x a Core/Element  | direct | 0.5  | baseline | 1            |
| Shared (5)    | Element + leaf type (transitivity)   | direct | 0.6  | baseline | 1            |
| Shared (5)    | rdfs:domain: Core/from->Relationship | direct | 0.6  | baseline | 127          |
| Shared (5)    | Find packages + names                | owlrl  | 0.9  | baseline | 26           |
| Shared (5)    | Packages with licenses               | owlrl  | 0.7  | baseline | 25           |
| Shared (5)    | Dependency chain (2-hop)             | owlrl  | 1.8  | baseline | 15           |
| Shared (5)    | Count elements by type               | owlrl  | 1.6  | baseline | 42           |
| Shared (5)    | subClassOf 1-hop: ?x a Core/Artifact | owlrl  | 0.5  | baseline | 83           |
| Shared (5)    | subClassOf 2-hop: ?x a Core/Element  | owlrl  | 0.5  | baseline | 226          |
| Shared (5)    | Element + leaf type (transitivity)   | owlrl  | 1.9  | baseline | 416          |
| Shared (5)    | rdfs:domain: Core/from->Relationship | owlrl  | 0.7  | baseline | 127          |
| Versioned (5) | Find packages + names                | union  | 2.2  | 2.51x    | 130 (26x5)   |
| Versioned (5) | Packages with licenses               | union  | 1.8  | 1.95x    | 125 (25x5)   |
| Versioned (5) | Dependency chain (2-hop)             | union  | 3.6  | 3.31x    | 122          |
| Versioned (5) | Count elements by type               | union  | 2.0  | 2.16x    | 50 (10x5)    |
| Versioned (5) | subClassOf 1-hop: ?x a Core/Artifact | union  | 0.8  | 1.78x    | 10 (2x5)     |
| Versioned (5) | subClassOf 2-hop: ?x a Core/Element  | union  | 0.8  | 1.72x    | 5 (1x5)      |
| Versioned (5) | Element + leaf type (transitivity)   | union  | 1.2  | 1.99x    | 5 (1x5)      |
| Versioned (5) | rdfs:domain: Core/from->Relationship | union  | 1.4  | 2.37x    | 634          |
| Versioned (5) | Find packages + names                | owlrl  | 2.2  | 2.51x    | 130 (26x5)   |
| Versioned (5) | Packages with licenses               | owlrl  | 2.2  | 2.98x    | 125 (25x5)   |
| Versioned (5) | Dependency chain (2-hop)             | owlrl  | 28.9 | 15.88x   | 122          |
| Versioned (5) | Count elements by type               | owlrl  | 10.4 | 6.32x    | 52           |
| Versioned (5) | subClassOf 1-hop: ?x a Core/Artifact | owlrl  | 0.7  | 1.36x    | 415 (83x5)   |
| Versioned (5) | subClassOf 2-hop: ?x a Core/Element  | owlrl  | 1.0  | 1.97x    | 1130 (226x5) |
| Versioned (5) | Element + leaf type (transitivity)   | owlrl  | 11.4 | 6.02x    | 2079         |
| Versioned (5) | rdfs:domain: Core/from->Relationship | owlrl  | 1.7  | 2.35x    | 634          |
+---------------+--------------------------------------+--------+------+----------+--------------+
```
