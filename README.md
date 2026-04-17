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

To avoid `java.lang.OutOfMemoryError` on large ontologies, set JVM max heap size to 16 GB:

```bash
export JAVA_OPTS="-Xms2g -Xmx16g"
```

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
sameas-bench-java run                     # 3 versions, 10 packages, 3 repeats
sameas-bench-java run --versions 3        # fewer versions
sameas-bench-java run --packages 50       # more packages (slower)
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
| ------- | ---------------- |
| **Graph Statistics** | Triple counts and build time per scenario |
| **Equivalence Breakdown** | `owl:equivalentClass` / `equivalentProperty` / `sameAs` counts |
| **SPARQL Query Results** | `direct` vs `union` vs `owlrl` — wall time and row counts |
| **SHACL Validation** | Per-version shapes (no inference) vs canonical shapes + OWL-RL |
| **Summary** | Overhead ratios: union and OWL-RL vs shared-namespace baseline |
| **Computing Environment** | CPU, RAM, JVM, OS, total wall time |

---

## Three strategies compared

| Strategy | How it works | Scales with versions |
| -------- | ------------ | -------------------- |
| `direct` | All data uses shared canonical IRI — no equivalences needed | O(1) |
| `union` | Each version has own IRI; query has one UNION branch per version | O(N) |
| `owlrl` | `owl:equivalentClass` hub graph + Backward Chaining (on-the-fly inference), then canonical query | super-linear |

---

## Building from source (without install)

```bash
./mvnw package -q                              # builds target/sameas-bench.jar
java -jar target/sameas-bench.jar smoke        # run directly
```

---

## Benchmarking strategy (accuracy & isolation)

To ensure high-fidelity measurements and minimize JVM-induced noise, the benchmark uses several isolation strategies:

1. **Global engine warmup**: Before any measurements begin, the JVM and Jena's query/reasoner engines are primed with a synthetic workload. This ensures that JIT compilation and internal engine caches are steady.
2. **Cold-start protection**: Within each scenario, every distinct SPARQL query and SHACL validation is executed once (unmeasured) before the timing repeats start. This isolates the execution performance from the initial parsing and planning phase.
3. **Preemptive garbage collection**: `System.gc()` is explicitly invoked before each scenario block begins. This clears out models and triples from previous scenarios, ensuring that each benchmark starts with a clean heap and reducing the likelihood of a major GC pause during measurement.
4. **Inference engine reuse**: For reasoning scenarios, the `InfModel` is expanded once outside the measurement loop. This allows us to measure the "hot" performance of the backward-chaining engine rather than the one-time rule setup overhead.

---

## Differences from Python version

| Aspect | Python (rdflib) | Java (Jena 6.0) |
| ------ | --------------- | ----------- |
| SPARQL engine | rdflib ARQ (Python) | Jena ARQ (native Java) |
| OWL reasoning | owlrl (pure Python) | Jena OWL Mini reasoner (built-in rule engine) |
| SHACL | pyshacl | jena-shacl |
| Reasoner approach | Pre-materialization (owlrl adds all closure triples) | Backward Chaining (evaluates OWL-RL rules on-demand during query execution) |
| Performance | Baseline | Typically 5–50× faster for large graphs |

> **Optimization note**: The Java version leverages Jena's **Backward chaining** mechanism for the `owlrl` strategy. Instead of forward materializing the entire equivalence graph (which scales poorly and consumes excessive memory by pre-computing all closure triples), the inference rules are evaluated on-the-fly when evaluating the SPARQL query patterns. This drastically reduces the memory overhead.
>
> **Reasoner choice**: We use the **OWL Mini** reasoner (`ReasonerRegistry.getOWLMiniReasoner()`). This provides the optimal balance for SPDX identity management—it handles `subClassOf`, `equivalentClass`, and robust `sameAs` transitive/symmetric closures while avoiding the exponential search space associated with the "Full" OWL reasoner. `OWL Mini` is preferred over `OWL Micro` here as it more reliably handles identity mapping when individuals are used in the object position of triple patterns (common in SPDX 3 relationship types). This choice specifically addresses `OutOfMemoryError` and infinite recursion issues encountered when applying full OWL rule sets to large, multi-version SPDX datasets.
>
> **Resilience strategy**: The benchmark runner is instrumented to catch `OutOfMemoryError` and `QueryCancelledException` (timeouts). If a specific reasoning task exceeds available resources or the 5-minute safety threshold, the system records the error with heap context and proceeds to the next scenario rather than crashing the entire suite.
