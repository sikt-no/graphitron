---
id: R785
title: "Docs-index generator dies on a second stale-stamp run in a reused Maven JVM"
status: In Review
bucket: tooling
priority: 2
theme: tooling
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# Docs-index generator dies on a second stale-stamp run in a reused Maven JVM

The build-time docs-index generator (`graphitron-mcp`'s `build-docs-index` execution, bound to
`process-classes`) fails the whole build with `UnsatisfiedLinkError: Native Library
.../libtokenizers.so already loaded in another classloader` the second time it actually embeds inside
one Maven JVM. The chain: the execution uses `exec:java`, which runs the generator in the Maven JVM
behind a class loader `exec-maven-plugin` builds fresh per execution; the embedder's tokenizer
dependency resolves its native library by `System.load` on a fixed cached path
(`~/.djl.ai/tokenizers/<version>/libtokenizers.so`); and the JVM binds a loaded native library to one
class loader for the life of the process, so the second loader's load is refused. The stamp gate
hides it most of the time, because an unchanged manual skips the embed and therefore never touches
the tokenizer, which is why this reads as intermittent: the failure needs a *stale* stamp on a
*reused* JVM. That combination is not exotic, it is the documented inner loop. CLAUDE.md tells
contributors to prefer `mvnd`, whose daemon deliberately outlives a build, and web sessions warm the
full reactor at session start, so the daemon has already embedded once before the developer types
anything. Editing any page under `docs/manual/` and rebuilding is then enough to fail the build, on a
message that names neither the docs nor the generator and suggests nothing the developer did.

What lands: the docs-index step becomes idempotent, so a contributor can edit a manual page and
rebuild through the inner loop this repo documents without the build dying on a message about a
tokenizer they never invoked. Nothing changes for a consumer of graphitron. The bundle the jar ships
is byte-for-byte the same work as today (634 chunks at dimension 384 over the 79 in-scope manual
pages), the stamp gate still skips the embed when the manual is unchanged, and the runtime that reads
the bundle is untouched.

## Fast-track note

The `Spec → Ready` sign-off was waived by the user, explicitly and in those terms, because the
failure was already hitting developers. Recorded here rather than left to be inferred from the
commit trailers: this plan reached implementation without an independent session reading it, so the
usual "a reviewer agreed the design fits" cannot be assumed of it. `In Review → Done` still needs a
session other than the implementer's, and that reviewer is reading the design for the first time.
The fix shipped to trunk ahead of the guard, in its own commit, so that the unblocking change was
available to pull while the guard was still being written.

## What the reproduction established

Four runs, this container, one module, all of them driving the same generator over the same 79 pages
with the stamp removed so the embed actually happens:

| Driver | Build 1 | Build 2 |
|---|---|---|
| `mvnd` (fresh daemon), in-process `exec:java` | embeds, 634 chunks | **fails**, "already loaded in another classloader" |
| plain `mvn`, in-process `exec:java` | embeds, 634 chunks | embeds, 634 chunks |
| `mvnd` (the daemon that had just failed), forked `exec:exec` | embeds, 634 chunks | embeds, 634 chunks (and a third run too) |

So the trigger is one JVM serving two builds, and the fork is what removes it. Plain `mvn` is immune
because each invocation is a fresh process; `mvnd` is exposed because its daemon deliberately
outlives a build, which is the whole point of the daemon and the reason CLAUDE.md recommends it.

## Parallelism is not the trigger, though it is what put the reporter on `mvnd`

Worth stating explicitly, because `mvnd` also runs modules in parallel and that is the natural first
suspicion. The failing `mvnd` runs above used `-pl graphitron-mcp`: one module, one execution, no
concurrency anywhere in the build. Sequence alone reproduces it. Nor is parallelism sufficient: CI
runs `mvn install -Plocal-db -Pcoverage -T 1C`, module-parallel with tests on, and has never seen
this, because every CI job is a fresh JVM. Parallel but not reused is safe; reused but sequential is
not.

Concurrency cannot even reach the collision today. A second in-JVM load needs a second execution that
loads the tokenizer, and `build-docs-index` is the only one in the reactor: every other
`exec-maven-plugin` execution runs `roadmap-tool`'s `Main` or the model codegen driver, none of which
touch a native library, and `BgeEmbedderOnnxTest` loads the embedder inside a surefire fork rather
than the Maven JVM. Two concurrent loads would collide the same way if that ever changed, which is an
argument for the guard below rather than for a different fix. This is also distinct from R538 (the
ONNX embedder scoring nondeterministically under full-reactor parallel load): that one is numeric
degradation inside a surefire fork and is plausibly contention-driven, this one is a loader binding
that fails identically on an idle machine. Neither blocks the other.

## Design: give the embed its own process

Bind the execution to `exec:exec` with `${java.home}/bin/java` instead of `exec:java`, passing the
classpath through `<classpath/>` and the main class plus its two existing arguments as command-line
arguments.

The reasoning is that the constraint being violated is a property of a *process*: the JVM binds a
loaded native library to one class loader until the process exits, and nothing inside the process can
unbind it. A process boundary is therefore the only place the invariant can be restored, and a fork
costs one JVM start against an embed that takes about 16 seconds and only runs when the manual
changed.

This also extends a shape the tree already relies on rather than inventing one. Every other native
load in this repo already sits behind an isolation boundary of some kind: the ONNX test runs in a
surefire fork, and the tree-sitter path (`BundledLibraryLookup`) extracts to a fresh temp file per
extraction and binds through FFM `SymbolLookup` rather than `System.load`, which is why loader churn
never troubles it. The docs-index step is the one native load with no boundary at all.

Two details the fork settles as a side effect. The execution owns its command line, so
`--enable-native-access=ALL-UNNAMED` is declared where it applies; today the pom comment claims the
surefire `argLine` covers this execution, which was never true, since surefire's `argLine` configures
test forks and this ran in the Maven JVM. And the generator's `main` becomes the only entry point
again, rather than a `main` invoked reflectively in a loader whose lifetime the plugin owns.

## Rejected arms

*Keep it in-process and defeat the loader churn.* Either pin the tokenizer classes into a loader that
outlives the execution, or point each execution at a private `DJL_CACHE_DIR` so `System.load` sees a
path it has not loaded. The first fights a third-party library's static initialiser over which loader
owns its classes, and depends on `exec-maven-plugin` internals we do not control. The second works by
accreting a copy of a multi-megabyte `.so` per build and leaves the invariant intact only for as long
as nobody reuses a cache directory. Both spend more mechanism than the fork to reach a weaker result.

*Tolerate the failure.* Catch `UnsatisfiedLinkError` in the generator and skip the embed. This trades
a loud build failure for a jar shipping a stale bundle, silently, on exactly the build where the docs
changed. The stamp file would then also have to be left un-updated to avoid lying about what is in
the bundle, at which point every subsequent build re-attempts and re-fails. Rejected as a downgrade
from a hard stop to a wrong artifact.

*Serialize or drop `mvnd`.* Recommending plain `mvn` instead would trade a documented performance win
for a bug in one build step, and would leave the failure armed for anyone who ignores the advice.

## The guard

The fix is one word of XML (`java` becoming `exec`), reverting it looks harmless, and the consequence
only appears on a second build in a reused JVM, which is precisely the shape of failure a reviewer
cannot be expected to catch twice. Add a `roadmap-tool` check beside the existing ones, in the shape
`CoverageAgentWiringCheck` established: a `NativeLoadIsolationCheck` invoked as
`check-native-load-isolation <repo-root>`, wired as an `exec:java` execution at `verify` in
`roadmap-tool/pom.xml`, dispatched from `Main` alongside `check-coverage-agent-wiring`, walking the
root pom plus the modules its `<modules>` block declares (`ModuleEnumerationCheck.declaredModules`).

State the invariant generally rather than naming this one execution, so the next native dependency
inherits the protection: *a module whose dependencies include the ONNX/tokenizer stack must not bind
an in-process `exec:java` execution.* A pom is marked when it names an artifact starting
`langchain4j-embeddings` (the module the dependency quarantine confines to `graphitron-mcp`, which
pulls ONNX Runtime JNI and the DJL tokenizer transitively) or `onnxruntime`, the second listed so a
future direct dependency on the runtime is covered without editing the check. Dependency management
counts as naming it, because an execution the root pom binds is inherited by the marked module, so
the declaring pom is where the fix belongs. In a marked pom, every `exec-maven-plugin` execution
binding `<goal>java</goal>` is a violation; a configuration-only block binds nothing and is not one,
the way the existing check treats a configuration-only failsafe. Comment-stripping first, so
commented-out XML never trips it. The failure message names the module, the execution id, and the
consequence in the terms the developer will meet it ("the second build in a reused JVM fails with
`already loaded in another classloader`"), the same way the argLine violation names its false-0%
consequence.

`NativeLoadIsolationCheckTest` mirrors `CoverageAgentWiringCheckTest`: a forked execution in a
marked module passes; an `exec:java` execution in a marked module fails naming module, id and
consequence; the same `exec:java` execution in an unmarked module passes (the roadmap-tool and
model-codegen executions must stay legal); commented-out XML does not trip; and
`run_againstThisRepository_isClean` pins the reactor's own poms, which is the assertion that fails if
someone reverts the fork.

## Implementation sites

1. `graphitron-mcp/pom.xml`: the `build-docs-index` execution moves to `exec:exec`, and the
   surrounding comment explains why the step forks, since a future reader's first instinct will be to
   simplify it back to `exec:java`.
2. `roadmap-tool/src/main/java/no/sikt/graphitron/roadmap/NativeLoadIsolationCheck.java`, plus the
   `Main` dispatch line and usage line.
3. `roadmap-tool/pom.xml`: the `check-native-load-isolation` execution at `verify`.
4. `roadmap-tool/src/test/java/no/sikt/graphitron/roadmap/NativeLoadIsolationCheckTest.java`.

No `docs/` changes: the failure disappears rather than needing documenting, and the why lives in the
pom comment next to the wiring it explains.

## Verification

- With the stamp removed, three consecutive `mvnd -pl graphitron-mcp exec:exec@build-docs-index` runs
  against one daemon each embed and write 634 chunks at dimension 384. Already observed, including
  against the daemon that had just failed in-process.
- A stamp-current run still prints the skip line, so the fork did not cost the up-to-date check.
- `mvnd -pl graphitron-mcp process-classes -Plocal-db` runs the step in phase, not just as a
  standalone goal invocation.
- Full `mvn install -Plocal-db` green, and the new check green inside it (it runs at `verify`).
  Observed at `646bef5` with the stamp cleared first, so the run exercised the forked embed in phase
  rather than the skip path: 634 chunks at dimension 384 through `exec:3.5.0:exec`, both wiring gates
  reporting clean, no failing suites. An earlier green run straddled a rebase and was re-run rather
  than trusted.
- The guard fails when the execution is reverted to `exec:java`. Verified by flipping the real pom
  back and watching `run_againstThisRepository_isClean` error, then restoring it, so the assertion is
  known to bite rather than merely known to pass.

## Not in scope

- R538's ONNX nondeterminism, which is a different failure with a different mechanism.
- The `graphitron:dev` warm path. It loads the same tokenizer, but through the plugin realm Maven
  caches rather than a per-execution loader, and a failed embedder warm already degrades to
  structured-only by design. If a repeat `graphitron:dev` in one daemon does trip the same binding,
  the observable is a degraded `docs.search` rather than a failed build, and it wants its own item
  with its own reproduction.
- Any change to the bundle format, the chunker, the embedder, or the stamp's hash.
