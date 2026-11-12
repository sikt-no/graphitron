# The clean build, re-measured at trunk `7737fe1f`, 2026-11-12

A working document, not a roadmap item; it lives in `audits/` so the roadmap-tool ignores it. It
follows the `build-profile` skill's method. It is a second reference point for R1131's plan, taken
because 117 commits landed after the 2026-11-11 workstation baseline (`8d34bcd6a3`). It is a
different machine from that baseline, so it compares with `2026-11-02-where-the-build-spends-its-cpu.md`,
which ran on the same one, and not with the workstation figures.

## The run

* **Command:** one sequential `mvn clean install -Plocal-db` into an empty `-Dmaven.repo.local`, shared `~/.m2` as tail. `BUILD SUCCESS`, every test passing, **36:45 wall** (2,207 s), 17:38:18 to 18:15:07 UTC.
* **Machine:** the agent sandbox, 4 cores, 15 GB, no other build running. Steal was 264 s, kept out of every CPU figure below.
* **Recording:**
  * JFR in every JVM the build started, through `JDK_JAVA_OPTIONS` with a `%p` filename. This is the route the item's B6 settled on, and it recorded the LSP, MCP and plugin forks that the workstation run missed.
  * Stack depth 256, `settings=default`.
  * `/proc/stat` every 2 s, `ps` every 5 s.
* **One run.** There is no repeat, so a difference under about 5% between this and any other single run is inside the noise this box has shown.

## Wall and CPU

**6,745 CPU-seconds over 2,207 s: 3.06 of 4 cores busy, 76.4%.** Seconds at each level of busy cores,
in 2-second samples: under 1 core 22, 1 to 2 cores 490, 2 to 3 cores 225, 3 to 4 cores 1,470.

| Module | wall s | CPU-s | avg cores | 11-02 wall | 11-02 CPU-s |
|---|---:|---:|---:|---:|---:|
| `graphitron-sakila-db` | 19 | 35 | 1.8 | 32 | 70 |
| `graphitron-model` | 345 | 1,171 | 3.4 | 467 | 1,312 |
| **`graphitron`** | **1,167** | **3,554** | 3.1 | 1,037 | 3,494 |
| `graphitron-mcp` | 80 | 255 | 3.2 | 72 | 213 |
| `graphitron-lsp` | 168 | 590 | 3.5 | 184 | 678 |
| `graphitron-maven-plugin` | 166 | 453 | 2.7 | 207 | 512 |
| `graphitron-sakila-example` | 190 | 509 | 2.7 | 264 | 706 |
| `roadmap-tool` / `docs` | 35 / 29 | 61 / 56 | | 55 / 46 | 102 / 92 |
| **total** | **2,207** | **6,745** | 3.06 | 2,372 | 7,192 |

Largest goals:

| goal | wall s | CPU-s | avg cores |
|---|---:|---:|---:|
| `graphitron` surefire | 832 | 3,074 | 3.7 |
| `graphitron` exec `generate-test-schemas` | 297 | 406 | **1.4** |
| `graphitron-model` surefire | 291 | 1,075 | 3.7 |
| `graphitron-lsp` surefire | 165 | 586 | 3.5 |
| `graphitron-sakila-example` surefire | 101 | 289 | 2.9 |
| `graphitron-maven-plugin` invoker | 99 | 239 | 2.4 |
| `graphitron-maven-plugin` surefire | 57 | 188 | 3.3 |
| `graphitron-mcp` surefire | 51 | 156 | 3.0 |

## What moved, and what did not

* **CPU is 6% under 11-02 and wall 7% under.** Both are single runs; read them as "a little lower", not as a saving.
* **`graphitron` did not move: 3,494 to 3,554 CPU-s, and 1,037 to 1,167 s wall.** It is 53% of the CPU and 53% of the wall clock. The modules that fell are the small ones, the model by 11% and sakila-example by 28%.
* **The inline-capture ceiling has not moved either.** `graphitron`'s `InlineCaptureGateTest` ceiling reads 294 at `8d34bcd6a3` and at this commit. Its sites are what the population migration (A2) is meant to convert, and the figure the item quotes for them (1,256) counts something else, so the two are not compared here.
* **The reactor is still sequential.** Module windows are disjoint and sum to the wall clock.

## Where the CPU goes inside the two big forks

Java-thread execution samples from each fork's recording; stacks over 256 frames are truncated, so
the H2 shares are lower bounds. GC and native threads are not in a sample count, so these are shares
of Java CPU.

| fork | samples | in H2 statement preparation | elsewhere in H2 | outside H2 |
|---|---:|---:|---:|---:|
| `graphitron` surefire | 68,231 | 72.9% | 15.1% | 12.0% |
| `graphitron-model` surefire | 27,858 | 53.4% | 33.8% | 12.9% |

"Preparation" matches the parser, `prepareLocal`, `Query.prepare`, `Select.init/optimize`, view
inlining and the join optimizer, so it includes planning. The 11-11 audit reported planning 65.8%
and parsing 9.1% in the same fork, which this agrees with.

By the nearest test frame in each stack, in `graphitron`'s fork:

* `TestSchemaHelper`, the inline capture helper, holds **26.4%** of samples, 75% of it in preparation. At 3,074 CPU-s of surefire that is on the order of 800 CPU-s.
* The next classes are 5.1% (`WarmStartRefreshTest`), 3.7% (`PersistentStoreTest`) and under 3% each, 85 to 90% in preparation.
* **The tail is flat.** 9 of 165 classes carry half the samples and 32 carry 80%.

In the model's fork 25.7% of samples have no test class in the stack (fixtures and population
building), and the largest class is `GraphCountGrowthGateTest` at 7.6%, 82% of it preparing.

## What this says about the plan

* **The CPU bound is the number to beat.** 6,745 CPU-s is 1,686 s at perfect scaling on 4 cores, and 422 s on 16 threads. A five-minute build needs about 22 busy cores at today's work, so no parallelism step alone reaches five minutes; it needs the work to fall, which is Track A.
* **Track B is worth at most 520 s on this box**, the 24% the box leaves idle (2,207 s against the 1,686 s floor). The idle is concentrated in `generate-test-schemas` (297 s on 1.4 cores), the invoker, and the module tails.
* **Track A is where the CPU is.** Three quarters of `graphitron`'s Java CPU is H2 preparing statements, a quarter of that through one helper. The share is where 11-11 put it (72.9% against 65.8% plus 9.1% parsing), and `graphitron`'s CPU is flat against 11-02, so the 117 commits since the baseline did not lower it.

## Not done

* No interleaved pairs: this is a whole-build reference point, and a step's saving still needs the paired method.
* No statement tally this time; `2026-11-02-where-the-build-spends-its-cpu.md` has the one that names the statements.
* The per-goal CPU is the `/proc/stat` delta over the goal's window, so it includes any process the build ran at the same time.
