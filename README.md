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
sameas-bench-java smoke --no-owlrl        # skip reasoning expansion
sameas-bench-java smoke --reasoner custom # use Bare minimum reasoner (custom)
```

Good for development and CI. Completes in seconds.

### `quick` — fast run with real SPDX ontologies

```bash
sameas-bench-java quick                   # 2 versions, 10 packages, 1 repeat, no reasoning
sameas-bench-java quick --versions 3      # 3 versions
sameas-bench-java quick --owlrl           # include reasoning (slow with standard reasoners!)
```

Downloads SPDX 3.0.1 and 3.1 TTLs on first run (cached afterward).

### `run` — full benchmark

```bash
sameas-bench-java run                     # 5 versions, 25 packages, 3 repeats
sameas-bench-java run --versions 3        # fewer versions
sameas-bench-java run --reasoner jena-mini # use OWL Mini (default)
sameas-bench-java run --reasoner custom    # use Bare minimum reasoner (custom) (recommended for performance)
```

#### Reasoner Options (`--reasoner`)

- `jena-mini` (Default): Jena's standard OWL Mini reasoner.
- `jena-micro`: Lightweight OWL reasoner (faster than OWL Mini; but does not support `owl:sameAs`).
- `jena-full`: Full OWL reasoner (slowest, most complete; better support of `owl:oneOf`, `owl:disjointWith`, etc. than OWL Mini).
- `custom`: **Bare minimum reasoner (custom)**. A specialized, high-speed reasoner that only processes `owl:sameAs`, `owl:equivalentClass`, and `owl:equivalentProperty`. Optimized specifically for SPDX 3 identity hubbing. Use this for large-scale benchmarks where identity resolution is the primary requirement.

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
| **SPARQL Query Results** | `direct` vs `union` vs `owlrl` (Selected Reasoner) — wall time and row counts |
| **SHACL Validation** | Per-version shapes (no inference) vs canonical shapes + Selected Reasoner |
| **Summary** | Overhead ratios: union and Selected Reasoner vs shared-namespace baseline |
| **Computing Environment** | CPU, RAM, JVM, OS, total wall time |

---

## Three strategies compared

| Strategy | How it works | Scales with versions |
| -------- | ------------ | -------------------- |
| `direct` | All data uses shared canonical IRI — no equivalences needed | O(1) |
| `union` | Each version has own IRI; query has one UNION branch per version | O(N) |
| `owlrl` | Selected Reasoner hub graph + Backward Chaining (on-the-fly), then canonical query | super-linear |

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

## Reasoner choice

The Java version allows switching between several reasoning profiles via the `--reasoner` flag.

- **Bare minimum reasoner (custom)** (recommended): Implemented via Jena's `GenericRuleReasoner`. This provides the optimal balance for SPDX identity management:
  - **Completeness**: Fully handles `owl:sameAs`, `owl:equivalentClass`, and `owl:equivalentProperty`.
  - **Efficiency**: Ignores 2000+ complex OWL restrictions that cause performance blowout in standard reasoners.
  - **Coverage**: Specifically implements transitivity for `subClassOf`/`subPropertyOf` and RDFS `domain`/`range` inference.
- **Jena standard reasoners**: `jena-mini`, `jena-micro`, and `jena-full` provide standard-compliant OWL 2 RL/Mini/Full profiles. These are useful for verifying the custom reasoner's accuracy but are significantly slower on real-world SPDX ontologies.

---

## Resilience strategy

The benchmark runner is instrumented to catch `OutOfMemoryError` and `QueryCancelledException` (timeouts). If a specific reasoning task exceeds available resources or the 5-minute safety threshold, the system records the error and proceeds to the next scenario.
> [!TIP]
> If you encounter timeouts with `jena-mini` or `jena-full`, try using `--reasoner jena-micro` or `--reasoner custom`.
