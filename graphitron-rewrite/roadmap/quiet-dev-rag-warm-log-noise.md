---
id: R409
title: "Quiet graphitron:dev RAG-warm log noise (Lucene JUL + DJL SLF4J) and document recommended .mvn/jvm.config"
status: Backlog
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

Scope sketch (decide deliberately at Spec): quiet groups 1 and 2 in code at
warm time (a `graphitron-mcp`-owned helper called by `DevMojo` before the warms
start, so the logger-name knowledge stays with the module owning those deps and
the global-JVM logger mutation is a deliberate, dev-goal-scoped act rather than a
static-init side effect); and document a recommended `.mvn/jvm.config` in
`getting-started.adoc` for group 3 (and the actionable Lucene flag). Open forks
for Spec: whether to mute the *actionable* Lucene hint in code at all or only
document its flag; and whether the SLF4J-provider-specific suppression of group 1
is acceptable coupling given the dev goal always runs under Maven's slf4j
provider.
