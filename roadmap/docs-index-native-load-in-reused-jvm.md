---
id: R785
title: "Docs-index generator dies on a second stale-stamp run in a reused Maven JVM"
status: Backlog
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
Confirmed in a web session: `mvnd -pl graphitron-mcp exec:java@build-docs-index` with the stamp
removed failed on the first attempt against the warm daemon, and the same command reproduces it on
every subsequent try.

The `graphitron:dev` warm path loads the same tokenizer through the same embedder, but in the
plugin's cached realm rather than a per-execution loader, and a failed embedder warm degrades to
structured-only by design, so the build-time step is the surface that turns this into a hard stop.
Two directions worth weighing at Spec: fork the generator into its own JVM (`exec:exec` with
`${java.home}/bin/java`, which restores idempotence because a fresh process has a loader that has
not loaded the library, and lets the step declare its own native-access flag instead of relying on a
surefire `argLine` that never applied to it), or keep it in-process and make the load survive loader
churn. The fork also wants a guard so the wiring cannot silently regress to `exec:java`, in the shape
`CoverageAgentWiringCheckTest` already uses for pom assertions.
