---
id: R603
title: "A pipeline-output facts family in the model store"
status: Spec
bucket: architecture
priority: 5
theme: classification-model
depends-on: []
created: 2026-08-06
last-updated: 2026-08-08
---

# A pipeline-output facts family in the model store

`graphitron-model-captures-facts` (R595, shipped; see `roadmap/changelog.md`) landed the
store scoped to what a run reads: five families, each named for whose vocabulary a row is
written in, never for its reader or its role. What the SDL declares (`graphql_`), what
graphitron makes of that document (`graphitron_`: decoded directives and macro provenance),
what the consumer's database declares (`sql_`), what the compile classpath declares (`jvm_`),
and the store's own record of what it read and was built from (`store_`).
`validation-adds-facts` (R589) adds the derived stratum: what the generator concluded, as
detections over those inputs; the landed DDL header holds the `intent_` prefix in reserve for
exactly that layer. Neither stratum can hold the third kind of fact the pipeline observes:
what the run *produced*, reported by
oracles that run after capture. The first concrete case is generated-code compile
diagnostics: `Workspace.compileDiagnostics()` carries javac output over emitted sources,
written per dev-loop compile round, and today it lives only in memory beside the store
instead of in it. Candidate siblings in the same family: the emitted-file inventory (which
files a run wrote, from which coordinates), which today exists only as filesystem state.

Why it matters, and the boundary with R569: `mcp-aggregated-diagnostics` (R569) is now the
store's first reader and lands an interim compile *bridge* relation (javac output loaded per
compile round, registered under the agreement driver's bridged arm) as one arm of its
`diagnostic` union view. That bridge is a copy of legacy output with a read-side key; this
item owns the real thing: the family's prefix and doctrine, the writer lifecycle, and the
promotion of compile diagnostics from bridged copy to owned facts. This family is what the
compile arm of R569's view reads once both items have landed; whether the bridge table ever
exists in between is a fork the dovetail section below takes a position on. Either way the
design adopts the key shape R569 fixed rather than superseding it.

Why this is not an R595 amendment: the write cadence is different in kind, not in detail.
R595's capture loads run at startup, and the store now persists between runs on capture's
terms: an H2 file under `<build>/graphitron-model`, a `store_stamp` (DDL hash plus generator
version) that discards and rebuilds on any mismatch so no migration ever exists,
`StoreRefresh` retaining partitions whose source still hashes to what `store_source`
recorded, stamps written only after the capture flush so a killed run leaves nothing claiming
completeness, and readers getting a copy-on-open snapshot. Compile results do not exist at
capture time (in the batch pipeline javac runs in the consumer's build, after the generator
exits; only the dev-loop cadence sees them at all, and only after generation). So the family
needs a third writer with its own lifecycle, and each piece of the landed machinery asks its
own question of that writer: what a compile round's `store_source` entry hashes when the
source is the emitted file set, what vouches for a round's completeness when the stamp that
vouches for capture is already written, and how rows written after open reach readers that
snapshot on open. That is a design of its own, and the relation would sit empty in exactly
the batch runs R595's agreement tests exercise. Per the store's own rule that a relation's DDL
lands with its first consumer (stated on the shipped `jvm_method_parameter` comment, and cheap
by construction now: new DDL is a stamp mismatch and a rebuild, never a migration), this
family lands as its own item. R569's bridge
covers the diagnostics read surface in the meantime; the sections below fix the family's
shape, and its first consumer is R569's `diagnostic` view, whose compile arm this item
re-grounds.

## Design: an output fact is a transcription on a third cadence

The Backlog framing asked where run-output facts could live, since neither the transcription
families nor the reserved derived stratum seemed able to hold them. The landed naming
doctrine dissolves the question. A compile diagnostic is a transcription like every `sql_` or
`jvm_` row: it records what an external oracle, javac, declared, written in that oracle's
vocabulary. What distinguishes it is not the kind of row but when its writer can run: after
generation, and only in the cadences where the oracle runs at all. So there is no `output_`
umbrella prefix; a role name is exactly what the landed naming rule rejects. Each
post-capture oracle gets its own family named for its vocabulary, and the first is `javac_`,
with one relation: `javac_diagnostic`.

Cadence and vocabulary are orthogonal axes, and the DDL header edit keeps them apart. The
prefix-picking paragraph gains only the vocabulary sentence: `javac_` is what the JDK
compiler reports about the emitted sources, written in `javax.tools.Diagnostic`'s terms. The
cadence rule is stated on its own axis, as a separate sentence: a family whose writer runs
after capture has its own writer on that writer's cadence, and capture's refresh clears it
like everything else capture does not own. Fusing the two would immediately mis-frame both
neighbours: the emitted-file inventory has no external oracle (it is graphitron's own record
of what a run wrote, so the vocabulary rule points it at the existing families, not at a new
one), and R589's detections also run after capture while being derived rather than
transcribed. The boundary this item does own is one-sided and stays in the `javac_` comments:
an oracle's transcription is never derived, and a detection over store rows must never
acquire an oracle's family, or the detection doctrine leaks. What `intent_` rows will be is
R589's to state; this item does not write that item's design into the shared header.

## The relation

```sql
CREATE TABLE javac_diagnostic (
  file          VARCHAR NOT NULL,
  line_number   BIGINT  NOT NULL,
  column_number BIGINT  NOT NULL,
  ordinal       INT     NOT NULL,
  kind          VARCHAR NOT NULL,
  code          VARCHAR,
  message       VARCHAR NOT NULL,
  PRIMARY KEY (file, line_number, column_number, ordinal)
);
```

Column semantics follow javac's own `Diagnostic` surface, which is what the family name
promises. `kind` is `Diagnostic.Kind.name()` and stays an open column, because a `CHECK`
enumerating an externally owned taxonomy would be a hand-maintained copy of javac's enum (the
reasoning that keeps `variant` open in R569's design; the closed-`CHECK` convention covers
taxonomies the model owns). `code` is `Diagnostic.getCode()`, nullable exactly where javac
returns null; it is the typed dimension a bridged copy of the display list never had, and the
concrete gain of owning the family. `message` is javac's own text: a fact here, unlike the
schema channel's rendered `message` column, because javac authored it; still display
material, never a dimension, never an agreement anchor. The key adopts the (file, position,
ordinal) shape R569 fixed for its bridge table, with position spelled as the two javac
columns and `ordinal` a per-`(file, line_number, column_number)` tie-breaker assigned in
round order, so the key stays natural rather than becoming decoration on a surrogate counter.

Two absences are sentinels in the key, and the reason is stated so it reads as a decision
rather than a drift from R569's NULL-uniform absence discipline: a primary-key column cannot
hold `NULL`, so a position javac did not report transcribes as `NOPOS`'s own value (`-1`, the
value the flattening already carries) and a diagnostic with no source keeps the flattening's
`"(no source)"` placeholder. Readers compare against those values, never `IS NULL`; the
discipline mismatch is confined to the key columns, and `code`, the one nullable column,
follows the sibling item's rule.

One flattening, three sinks. `CompileDiagnostic` changes shape while it is open anyway:
`severity` is renamed `kind` (it has always held `Diagnostic.Kind.name()`; the store should
not transcribe `kind ← severity()`), a `code` component is added (the console and LSP
surfaces ignore it), and `from` normalises `JavaFileObject.getName()` through the single
canonical-URI site (`ValidationReport.canonicalUri`) at the javac boundary. That last move is
R569's own requirement for this channel, hoisted to where it belongs: a `file` dimension
spanning both channels groups two spellings of one path apart unless exactly one site
canonicalises, and doing it in the flattening means console, Workspace, and store agree by
construction instead of each sink normalising or not. The writer then transcribes the round's
`CompileRound.diagnostics()` list verbatim, and the relation's content contract is exactly the
published round: it inherits, and does not fix, the round-scoped-list semantics (a round
covers its recompile set, and publishing it replaces the previous round wholesale on every
surface alike); if that contract ever needs to change, the change is to `CompileRound`,
upstream of all three sinks.

That canonicalisation restates the path spelling at both live readers of
`CompileDiagnostic.file()`, and the two are named here rather than discovered later:
`CompileErrorFormatter` renders it into the dev-loop console error block, and
`DiagnosticsTool` emits it as the MCP `diagnostics` tool's `location.uri`, a field already
named for the form it is about to start carrying. Neither is pinned to a real path by a test
(both suites construct the record directly), so nothing fails, but a session does see the
difference and *What stays out* says so rather than claiming no surface moves. Considered and
rejected: leaving `file` raw and adding a canonical accessor the store and the LSP filter
call. That keeps the console short at the cost of putting the choice of spelling back at every
sink, which is the drift this move exists to remove; the store's `file` dimension is the one
that must not fork, so the flattening is where the single spelling belongs. One Backlog item
reaches this: `lsp-compile-diagnostics-publish` (R430) plans to resolve
`CompileDiagnostic.file()` under the generated-sources root, and after this change its input
is already a URI, which shortens that item rather than blocking it. Its body takes the
one-line correction whenever this lands.

The ERROR predicate collapses to one home in the same edit. The javac-`Kind`-to-severity
projection is model-owned (R569 declares the view's `severity` a closed-`CHECK` projection),
and today it is evaluated independently at `CompileRound.errors()` and in
`DiagnosticsTool`'s compile source. After the rename the record carries the fact and a named
projection accessor beside it; both existing predicate sites read the accessor, and the
`diagnostic` view's compile arm carries the same projection as its derived `severity` column
(derived columns live in the view, per R569's stored-facts split). A partition unit test over
`Diagnostic.Kind.values()` pins both renditions total, which is what fails when javac grows a
`Kind`.

## The writer

For this family the store is a delivery channel, not a cache, and that difference governs the
writer's shape. Capture can shrug off a lost write because the run that wrote is the run that
reads; a compile round written into a store the reader never sees is a round the `diagnostic`
view answers wrongly about, where today `Workspace.compileDiagnostics()` cannot lose it. So
the writer does not open its own store per round. A new `CompileFacts` class beside
`CompileRound` in `no.sikt.graphitron.rewrite.compile` takes the dev session's store handle
(a `DSLContext`) and a round, and `DevMojo.reportCompile` is the single call site, beside the
existing two sinks, passing the same handle the `diagnostic` view's in-process readers query,
so a round the writer wrote is a round the tool sees, whatever the file's fate. No session
handle (the batch pipeline, or `-Dgraphitron.dev.compile=false`) means no write. Per round,
one transaction: delete every `javac_diagnostic` row, insert the round's list. Atomicity is
what stands in for a completeness stamp: `store_stamp` vouches for DDL and version, never for
rows, and a single-transaction write means no handle ever observes half a round.

**The session handle is stated here rather than assumed, because nothing establishes one
today.** The store's whole lifetime is currently one call: `FactCapture.run` opens
`GraphitronModelStore.openAt` and closes it inside a try-with-resources, once per generation
pass. `DevMojo` holds no `DSLContext`. R569's read side needs the same handle for the same
reason (its loader writes where its tools read), but its spec places the loader at the
workspace layer and the queries in `graphitron-mcp` without saying the two share one, so the
contract belongs to whichever item lands first and is fixed here so the two cannot land
incompatible halves:

- One `openAt` handle over the directory `AbstractRewriteMojo.resolveStoreDirectory` names,
  acquired by the dev session and closed with the session's other resources in
  `DevMojo.cleanup`. Its `dsl()` is what this writer takes and what every in-process reader of
  the `diagnostic` view queries.
- Not `openReadOnly`. That method's private copy is the *cross-process* reader shape by design
  (its javadoc argues the copy is the whole concurrency story), and an in-process reader on a
  copy would not see the round the writer just wrote, which is the one guarantee this section
  exists to make. Reading the delivery channel through a snapshot is the failure mode to
  design against, not an alternative to it.
- It coexists with capture without a protocol. H2 gives one process one database per file
  however many connections reach for it, which `GraphitronModelStore.close` already relies on
  (it withholds `SHUTDOWN` from a file-backed store for exactly that reason), so capture's
  per-pass `openAt` and close leave the session handle live, and the refresh's clear is
  visible through it. The lifecycle invariant below is therefore a consequence of the shared
  handle rather than a second mechanism. H2's already-open error is a cross-process signal;
  the same-process case is the sharing this relies on.
- One edge, and it is the implementer's to close: `openAt` deletes and rebuilds the files when
  the stamp does not name this DDL and this generator version, which can happen under a live
  handle when a session spans a generator upgrade. Re-acquiring the handle after each
  generation pass costs one `openAt` and removes the edge; taking it once per session is
  cheaper and leaves it. Either is acceptable, and the choice is a note in `CompileFacts`'
  javadoc, not a silent one.

If R569's read side lands first, this item inherits its handle unchanged and adds only the
writer; if this item lands first, the handle above is its to build. The `DevMojoTest`-tier
test in *Tests* is what pins whichever it is.

Note the relocation, stated so the reviewer reads a decision rather than a contradiction of
R569: that item places the compile channel's loader at the workspace layer in
`graphitron-lsp`; this item homes the writer with the round's producer in `graphitron`'s
compile package, because the producer owning its transcription is what makes "one flattening,
three sinks" true, and `graphitron` already depends on `graphitron-model`.

The degradations then follow from R595's bootstrap, and the one behaviour change is stated
rather than absorbed. When the session handle had to fall back to a private in-memory store
(another process held the file at session start), rounds stay fully visible to the in-process
tools through the shared handle; what is lost is the file, so cross-process readers and the
next run see nothing from this session. That is R595's persistence-is-an-optimisation stance,
and it is acceptable here because the in-process path, the only one that exists today, never
degrades. A cross-process reader's view is a copy-on-open snapshot (`openReadOnly`), so its
compile rows are whatever round existed at its copy, and it re-opens to see newer rounds; a
snapshot taken mid-write opens the previous round or boots cold, which `openReadOnly` already
treats as the empty answer. A stamp mismatch (a dev session spanning a generator upgrade)
discards and rebuilds. In the batch pipeline no writer runs at all: javac runs in the
consumer's build after the generator exits, the relation stays empty, and the store claims
nothing it cannot know.

## Refresh and retention: cleared by default, on purpose

The relation gets no `store_source` row and no stamp. A previous round's rows describe an
emitted tree that generation is about to replace, so the correct retention is none, and none
is the store's default: `StoreRefresh.clear` empties every relation it was not explicitly
taught to partition, so the new relation is cleared on every warm capture with zero refresh
code, exactly the "a relation nobody thought about is emptied and rebuilt, never silently
retained" behaviour the refresh was written around. The resulting invariant is the family's
lifecycle in one sentence: after any capture, cold or warm, `javac_diagnostic` is empty; rows
exist only between a compile round and the next generation.

## Agreement registration: an arm of its own

`FactCaptureAgreementTest` enumerates every generated relation and fails on any without a
registered arm, and none of the three landed arms fits: no independent second walk can
re-derive javac's verdict without re-running javac. R569's bridged arm does not fit either if
it lands first, so the count is left unstated: that arm's character is a census against the
list a loader copied from, and this relation has no loader and no list to census against. The
driver gains an `ORACLE` arm for relations a post-capture oracle writer owns, and the arm must
say something about content, or it is a skip list wearing an arm's name. Its anchors are
therefore two, both non-vacuous.
The lifecycle anchor seeds rows into the relation, runs capture (cold and warm), and asserts
empty, so the test distinguishes "cleared" from "never written"; an unseeded emptiness check
would pass identically with the writer deleted. The content anchor drives `CompileFacts` with
a constructed round and asserts the relation's rows equal the round's published list reduced
the same way, ordinal grain included, which is the `EQUALITY` arm's own character (the same
data reduced two ways) applied at the oracle's cadence, and it is what catches a writer bug
(dropped rows, ordinal collisions, a transaction split) that construction alone would let
through. The arm's javadoc names the one thing genuinely unpinned, javac's verdict itself,
rather than gesturing at everything. The arm is a sibling of R569's bridged arm, not a
replacement: the schema channel stays bridged until R589 lands its detection-minted
relation.

## The R569 dovetail is a fork, and this item picks a side

R569's `diagnostic` view is this relation's first consumer either way; what forks is whether
the compile bridge table ever exists. Additive-then-cutover earns its bridge when the old
surface is widely pinned and cannot move in one step, and the compile channel is pinned at
exactly two places: one producer call site (`DevMojo.reportCompile`) and one view arm. R569's
compile bridge would be a new copy built solely so this item can delete it, plus a loader and
a bridged-arm registration with the same lifetime. So the recommendation is the path where it
is never built: R569 is implemented with its compile arm reading `javac_diagnostic` from day
one, this item's relation and writer landing as that arm's substrate, sequenced so the DDL
lands with the view rather than ahead of it, and R569's bridge machinery reduces to the
schema channel alone. The workflow's own words license this: the additive-then-cutover
technique is "a technique, not a prescription", and two pin sites is as narrow as a surface
gets. The fallback stands if scheduling forces it: R569 fully lands first, bridge included,
and this item delivers the promised retirement (the compile bridge table, its loader, and its
bridged registration go; the view's compile arm re-points with its per-arm `source` literal
unchanged, a dropped table and a one-line view edit, never a re-key). Under either order
R569's wire-contract pins, which assert on tool answers and cannot tell which substrate
answered, are the acceptance that the compile arm's substrate is invisible on the wire.
`depends-on` stays empty because neither item unconditionally ships first; the sequencing
contract above is what binds, and whichever item starts implementation first resolves the
fork with the other's spec in hand. The session-handle contract in *The writer* is the piece
both orders share, which is why it is fixed there instead of left to whichever lands first.

## What stays out

The emitted-file inventory stays out, and the deferral is the first-consumer rule applied
honestly: nothing reads an inventory today, so its DDL waits for whichever consumer first
needs one (compile results joined to the files that produced them, or a workspace-inventory
surface). When it comes it is not a new oracle family: the inventory has no external oracle,
it is graphitron's own record of what a run wrote, so the vocabulary rule points it at the
existing families, and the cadence rule this item states covers its post-capture writer
regardless of which prefix it lands under. The same deferral covers every other output
oracle (execution results, test outcomes).

Two surfaces do move, and the first-client check is answered rather than waved past. The
canonicalisation restates the file spelling in the dev-loop console error block and in the
`diagnostics` tool's `location.uri` (the flattening section above owns both). No directive, no
generated-code change, and no new tool, argument, or response field: the `diagnostics` tool's
shape is R569's, and this item changes the value of one field it already has. Nothing in
`docs/` renders either surface, so no user-doc draft is owed; the claim is "no doc changes",
not "nothing a session can see".

## Implementation

- `graphitron-model.sql`: the header's prefix-picking paragraph gains the `javac_`
  vocabulary sentence and its opening count goes from five families to six; the cadence rule
  lands as its own sentence, not fused into prefix-picking; `CREATE TABLE javac_diagnostic`
  plus `COMMENT ON`s per the conventions, the one-sided derivation boundary and the
  key-column sentinel rule stated in them.
- `CompileDiagnostic`: `severity` renamed to `kind`, new nullable `code` component, `from`
  canonicalises the file through the single canonical-URI site, and a named severity
  projection accessor beside the fact. The reshape moves the canonical constructor, so the
  three files that build the record by hand (`DevMojoTest`, `DiagnosticsToolCompileSourceTest`
  and `from` itself) come with it; that is the whole blast radius.
- `CompileRound.errors()` and `DiagnosticsTool`'s compile source: collapse onto the
  projection accessor.
- The dev session's store handle per the contract in *The writer*, if R569's read side has
  not already established one: acquired in `DevMojo`, closed in `cleanup`, its `dsl()` shared
  by this writer and the view's in-process readers. Nothing holds a store open across a
  generation pass today, so this is real work whichever item does it.
- New `CompileFacts` writer as above, taking that handle.
- `DevMojo.reportCompile`: the third sink call, passing the handle the view's readers query.
- `FactCaptureAgreementTest`: the `ORACLE` arm, the registration, the seeded lifecycle
  anchor (cold and warm), and the write-read content anchor.
- R569 dovetail per the fork above: either the compile arm reads this relation from day one,
  or the bridge retirement (dropped table, dropped loader and registration, one-line view
  edit).
- `StoreRefresh`: no change; the wholesale clear picks the relation up derivationally, and
  the seeded lifecycle anchor is what proves it did.

## Tests

- Unit (compile package): writer round-trip through a store handle; a second round replaces
  the first wholesale; a successful round clears a previous failure; ordinal grain on
  repeated identical messages at the same position; `NOPOS` and null-`code` transcription;
  canonical-URI agreement between the flattening and the schema channel's spelling; the
  `Diagnostic.Kind.values()` partition pin on the severity projection.
- Pipeline tier: both `ORACLE` anchors; agreement closure still holds with no skip list.
- Maven-plugin tier: `reportCompile` writes through on the existing `DevMojoTest` seam, and
  the row is readable on the same handle afterwards, which is the delivery guarantee stated
  rather than assumed.
- R569's tool pins run unchanged; no new wire assertions.
