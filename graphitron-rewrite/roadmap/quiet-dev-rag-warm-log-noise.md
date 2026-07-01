---
id: R409
title: "Quiet graphitron:dev RAG-warm log noise (Lucene JUL + DJL SLF4J) and document recommended .mvn/jvm.config"
status: In Review
bucket: cleanup
theme: lsp
depends-on: []
created: 2026-07-01
last-updated: 2026-07-01
---

# Quiet graphitron:dev RAG-warm log noise (Lucene JUL + DJL SLF4J) and document recommended .mvn/jvm.config

Running `mvn graphitron:dev` prints console warnings that make a clean startup
look alarming. They fall into three groups by origin, and only two are
graphitron's to silence:

. *DJL tokenizer (SLF4J).* `[WARNING] maxLength is not explicitly specified,
  use modelMaxLength: 512`, emitted by the DJL HuggingFace tokenizer inside
  langchain4j's bge ONNX model when `DevMojo.bindServer` warms the embedder
  (`BgeEmbedder` constructs `new BgeSmallEnV15QuantizedEmbeddingModel()`,
  R372/R385). The bundled model exposes no public knob for `maxLength`, so this
  is *non-actionable* noise for the user.
. *Lucene vectorization (JUL).* `Java vector incubator module is not readable
  ... pass '--add-modules jdk.incubator.vector'`, from
  `org.apache.lucene.internal.vectorization.VectorizationProvider` when the
  docs-index Lucene store (`LuceneEmbeddingStore`) warms. This one *is*
  actionable: the named JVM flag both silences it and enables the faster Vector
  API path.
. *Maven-runtime JVM warnings.* jansi `System::load` native-access and guava
  `sun.misc.Unsafe` deprecation warnings. These are printed by the JVM for
  Maven's own `lib/` jars, before/independent of any plugin code, so a
  plugin running in-process in Maven's JVM cannot suppress them at runtime.
  The only cures are launch flags (`.mvn/jvm.config` / `MAVEN_OPTS`) or a
  newer Maven.

The heavy RAG dependencies (langchain4j ONNX + Lucene) are dependency-quarantined
in `graphitron-mcp` (R341/R372); `graphitron-maven-plugin` depends on it and
`DevMojo` drives the warms. So groups 1 and 2 are graphitron-emitted and can be
quieted in-process at warm time; group 3 can only be documented.

This is dev-tooling plumbing, not the classifier/generator/model pipeline: no
sealed variant, no classification, no emitted Java. The principles that bear are
the strategic ones, *separate business logic from API code* and *stability
through simplicity*, plus *documentation names only what is real*. The plan below
was reviewed by the `principles-architect` and resolves the three forks the
Backlog statement left open.

## Plan

### D1. Group 1 (DJL tokenizer): mute, best-effort

The `maxLength` warning is non-actionable, no public knob, no perf signal, so
muting it is pure win. Suppress it defensively rather than assuming one logging
provider:

* Set the slf4j-simple level property for the DJL tokenizer logger to `error`
  *and* raise that logger's `java.util.logging` level to `SEVERE`. The dev goal
  always runs under Maven's slf4j-simple provider today, but a Maven binding swap
  (Maven 4 is a moving target in group 3's story) or a change in DJL's lazy
  logger-init timing would silently turn a single-provider approach into a no-op.
  Belt-and-suspenders makes a wrong provider a silent no-op, not a wrong-op.
* The confirmed logger name is `org.apache.lucene.internal.vectorization.VectorizationProvider`
  for group 2 (from the user's paste); the DJL logger name (believed
  `ai.djl.huggingface.tokenizers.HuggingFaceTokenizer`) must be *confirmed
  against the `1.16.3-beta26` jar* during implementation before it is hard-coded.
* Javadoc describes what is *attempted* ("best-effort quieting of the bge
  tokenizer logger"), never asserts the warning is gone. Per *documentation names
  only what is real*: an unpinnable runtime invariant must not be stated as fact.
  A provider swap then degrades to "noise returns" (understandable), not "the
  comment lies."

### D2. Group 2 (Lucene vectorization): demote, do not swallow

This warning is *actionable and directional*: `--add-modules jdk.incubator.vector`
both silences it and enables the faster Vector API path. Muting it in code would
hide a fixable, perf-relevant condition and leave the developer believing they
are on the fast path, an inversion of "surface fixable conditions." So:

* Raise the Lucene `VectorizationProvider` JUL logger to `SEVERE` to drop its
  multi-line library-internal warning, and in its place emit a single
  graphitron-owned dev-log line naming the flag, *only when the incubator module
  is actually absent*. Gate on
  `ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent()`: if the
  user already passed the flag, the module is present, the Lucene warning never
  fires, and we stay silent; if absent, one concise line carries the actionable
  fix instead of Lucene's stack noise.
* This keeps the signal (net: one graphitron line vs. a multi-line Lucene dump)
  and still points performance-conscious users at the doc in D4.

### D3. Placement: a `graphitron-mcp` helper, dev-goal-scoped

The logger names are facts about the *RAG dependency set*, which R341/R372
dependency-quarantine in `graphitron-mcp` precisely so the plugin's compile
surface never learns them. `BgeEmbedder`'s own javadoc names this seam ("the same
separation-of-business-logic-from-API axis the graphitron-lsp / graphitron-mcp
split already serves"). So the suppression lives in a small `graphitron-mcp`
helper (working name `RagLogQuieting`), and `DevMojo` calls it; putting the logger
names in `DevMojo` would leak dependency knowledge back across the quarantine
seam.

Invariant to record and protect: this is *process-global* mutation (a JUL level,
a JVM system property), so it is scoped to the dev goal alone. It is **not**
shared with `GenerateMojo` / `ValidateMojo`, **not** triggered by the
`GraphitronMcpServer` constructor (which the generate/validate paths could
reach), and **not** invoked from tests. Named here so a later reader does not
"helpfully" hoist the call into `GraphitronMcpServer`.

### D4. Group 3 (Maven-runtime JVM warnings): document only

Add a short "Quieting startup warnings" note to the dev-loop section of
`getting-started.adoc` recommending a `.mvn/jvm.config` in the consumer project:

----
--add-modules jdk.incubator.vector
--enable-native-access=ALL-UNNAMED
--sun-misc-unsafe-memory-access=allow
----

Explain that graphitron silences the DJL noise itself (D1) and points at the
Vector API flag (D2), while the jansi native-access and guava `Unsafe` warnings
come from Maven's own runtime and can only be cleared by these flags or a newer
Maven, since a plugin running in Maven's JVM cannot un-print a JVM warning
emitted before/independent of its code.

### Ordering and lifecycle

`DevMojo.bindServer` (`DevMojo.java:241-248`) must call the helper *before*
`embedderWarm.start()` / `docsWarm.start()`: the warms load the noisy classes on
`graphitron-warm-*` daemon threads (`AsyncWarm.start()`), and thread-start is a
happens-before edge, so establishing the suppression on the dev thread first
guarantees it is visible when those threads touch the loggers. State the reason
in-code so the call is not later reordered after `start()`. The helper is
idempotent / safe to call once, matching the existing "call once per harness"
warm lifecycle.

## Testing

Log-noise does not warrant a pipeline/execution-tier test; the realistic pin is
that behaviour matches the best-effort prose. A light unit test on the helper:

* After invocation, the Lucene `VectorizationProvider` JUL logger level is
  `SEVERE` and the DJL slf4j-simple property is `error` (and its JUL level
  `SEVERE`).
* Calling twice is a no-op (idempotent), no throw.
* The incubator-hint decision is a pure function of a boolean
  (module-present → no line; absent → the flag-naming line); test the
  string/branch given the boolean rather than the ambient module state.

## Out of scope

* Forking a child JVM for the dev goal to inject launch flags (would not help the
  jansi warning, which is the parent Maven JVM, and would re-architect the
  in-process LSP+watch+MCP loop for two cosmetic warnings).
* Any change to the RAG warm behaviour, the embedder/store, or the MCP tools.
* Suppressing group 3 in code (established impossible in-process).
