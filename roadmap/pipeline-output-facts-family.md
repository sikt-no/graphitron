---
id: R603
title: "A pipeline-output facts family in the model store"
status: Backlog
bucket: architecture
priority: 5
theme: classification-model
depends-on: [mcp-aggregated-diagnostics]
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
promotion of compile diagnostics from bridged copy to owned facts. When this family lands it
replaces the compile arm of R569's view (a dropped table and a one-line view edit, by that
item's design), and the design below adopts the bridge table's key rather than superseding
it.

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
the batch runs R595's agreement tests exercise. Per the architecture's own rule that a
relation's DDL lands with its first consumer (cheap by construction now: new DDL is a stamp
mismatch and a rebuild, never a migration), this family lands as its own item. R569's bridge
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

The `intent_` boundary stays sharp by the same move. A `javac_` row transcribes an external
tool's independent verdict; an `intent_` row will derive from rows already in the store. An
output fact is never derived, and a detection over store rows must never acquire an oracle's
family, or the detection doctrine leaks. The DDL header's prefix-picking paragraph gains the
new family and one sentence of cadence doctrine: a family whose oracle runs after capture is
written by its own writer on the oracle's cadence, and capture's refresh clears it like
everything else it does not own.

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
promises. `line_number` and `column_number` carry javac's values with `NOPOS` transcribed as
the `-1` the flattening already uses; `kind` is `Diagnostic.Kind.name()` and stays an open
column, because a `CHECK` enumerating an externally owned taxonomy would be a hand-maintained
copy of javac's enum (the reasoning that keeps `variant` open in R569's design; the
closed-`CHECK` convention covers taxonomies the model owns). `code` is
`Diagnostic.getCode()`, nullable exactly where javac returns null; it is the typed dimension
a bridged copy of the display list never had, and the concrete gain of owning the family.
`message` is javac's own text: a fact here, unlike the schema channel's rendered `message`
column, because javac authored it; still display material, never a dimension, never an
agreement anchor. The key adopts the (file, position, ordinal) shape R569 fixed for its
bridge table, with position spelled as the two javac columns and `ordinal` disambiguating
repeated identical messages in round order.

One flattening, three sinks. `CompileDiagnostic` grows a `code` component (read from
`Diagnostic.getCode()` in `CompileDiagnostic.from`; the console and LSP surfaces ignore it),
and the writer transcribes the round's `CompileRound.diagnostics()` list verbatim. The
console renderer, `Workspace.setCompileDiagnostics`, and the store then answer from the same
list by construction, so no census check between them can fail. The relation's content
contract is exactly the published round: it inherits, and does not fix, the round-scoped-list
semantics (a round covers its recompile set, and publishing it replaces the previous round
wholesale on every surface alike); if that contract ever needs to change, the change is to
`CompileRound`, upstream of all three sinks.

## The writer

A new `CompileFacts` class beside `CompileRound` in `no.sikt.graphitron.rewrite.compile`,
with one entry point: write a round into the store directory, or do nothing when the
directory is null. `DevMojo.reportCompile` is the single call site, beside the existing two
sinks; the mojo threads the same path `AbstractRewriteMojo.resolveStoreDirectory` already
computes for generation. Per round, one transaction: delete every `javac_diagnostic` row,
insert the round's list with ordinals assigned in list order. Atomicity is what stands in for
a completeness stamp: `store_stamp` vouches for DDL and version, never for rows, and a
single-transaction write means no store ever holds half a round.

The degradations are inherited from R595's bootstrap rather than designed here.
`GraphitronModelStore.openAt` falls back to a private in-memory store when another process
holds the file, so a `mvn install` beside `graphitron:dev` makes the round's write invisible
instead of corrupting anything; persistence is an optimisation, and the fallback is the same
stance capture takes. A stamp mismatch (a dev session spanning a generator upgrade) discards
and rebuilds; a reader's copy-on-open snapshot taken mid-write opens the previous round or,
failing that, boots cold, which `openReadOnly` already treats as the empty answer. In the
batch pipeline no writer runs at all: javac runs in the consumer's build after the generator
exits, the relation stays empty, and the store claims nothing it cannot know.

## Refresh and retention: cleared by default, on purpose

The relation gets no `store_source` row and no stamp. A previous round's rows describe an
emitted tree that generation is about to replace, so the correct retention is none, and none
is the store's default: `StoreRefresh.clear` empties every relation it was not explicitly
taught to partition, so the new relation is cleared on every warm capture with zero refresh
code, exactly the "a relation nobody thought about is emptied and rebuilt, never silently
retained" behaviour the refresh was written around. The resulting invariant is the family's
lifecycle in one sentence: after any capture, cold or warm, `javac_diagnostic` is empty; rows
exist only between a compile round and the next generation.

## Agreement registration: a fourth arm

`FactCaptureAgreementTest` enumerates every generated relation and fails on any without a
registered arm, and none of the three landed arms fits: no independent second walk can
re-derive javac's verdict without re-running javac. The driver gains an `ORACLE` arm for
relations a post-capture oracle writer owns. Its pipeline-tier anchor is the lifecycle
invariant above, asserted on both cold and warm captures; content correctness is carried by
construction (one flattening, three sinks) plus the writer's own unit tests, and the arm's
javadoc says exactly that, the way R569's bridged arm names its own caveat. The arm is a
sibling of that bridged arm, not a replacement: the schema channel stays bridged until R589
lands its detection-minted relation.

## What this item retires from R569

R569's design already promised the retirement shape: each bridge arm's replacement is a
dropped table and a one-line view edit, never a re-key. Delivering it here means the compile
bridge table, its bridged-arm registration, and its per-round loader all go; the `diagnostic`
view's compile arm re-points at `javac_diagnostic` with its per-arm `source` literal
unchanged; and R569's wire-contract pins, which assert on tool answers and cannot tell which
substrate answered, are the acceptance that the swap is invisible. The `depends-on` on
`mcp-aggregated-diagnostics` records the sequencing: the view is this relation's first
consumer, so the DDL lands with it, never ahead of it. If R569's implementation is still in
flight when this item is picked up, the cheaper path is to coordinate so the compile bridge
is never built; that is a session-level scheduling call, not a design fork.

## What stays out

The emitted-file inventory stays out, and the deferral is the first-consumer rule applied
honestly: nothing reads an inventory today, so its DDL waits for whichever consumer first
needs one (compile results joined to the files that produced them, or a workspace-inventory
surface). The cadence doctrine this item writes gives it a home when it comes; its family and
prefix are deliberately left open. The same deferral covers every other output oracle
(execution results, test outcomes). No MCP tool, no directive, no generated-code change, and
no user-visible surface: the user-doc first-client check does not apply.

## Implementation

- `graphitron-model.sql`: the header's prefix-picking paragraph gains `javac_` and the
  cadence sentence; `CREATE TABLE javac_diagnostic` plus `COMMENT ON`s per the conventions.
- `CompileDiagnostic`: new `code` component, read in `from`; existing surfaces ignore it.
- New `CompileFacts` writer as above.
- `DevMojo.reportCompile`: the third sink call; thread the store directory to it.
- `FactCaptureAgreementTest`: the `ORACLE` arm, the registration, and the
  cleared-after-capture anchor (cold and warm).
- R569 retirement: drop the compile bridge table, its loader, and its bridged registration;
  the one-line `diagnostic` view edit.
- `StoreRefresh`: no change; the wholesale clear picks the relation up derivationally, and a
  test asserts it did.

## Tests

- Unit (compile package): writer round-trip against a temp-directory store; a second round
  replaces the first wholesale; a successful round clears a previous failure; ordinal
  assignment on repeated identical messages; `NOPOS` and null-`code` transcription.
- Pipeline tier: the `ORACLE` arm's anchor; agreement closure still holds with no skip list.
- Maven-plugin tier: `reportCompile` writes through, on the existing `DevMojoTest` seam.
- R569's tool pins run unchanged; no new wire assertions.
