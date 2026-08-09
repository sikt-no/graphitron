---
id: R603
title: "A pipeline-output facts family in the model store"
status: Ready
bucket: architecture
priority: 5
theme: classification-model
depends-on: [graph-partition-key-dimension]
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
R595's capture loads run at startup, and the store persists between runs on capture's terms: a
`store_stamp` (DDL hash plus generator version) that discards and rebuilds on any mismatch so
no migration ever exists, a refresh retaining partitions whose source still hashes to what
`store_source` recorded, and stamps written only after the capture flush so a killed run
leaves nothing claiming completeness. Compile results do not exist at capture time (in the
batch pipeline javac runs in the consumer's build, after the generator exits; only the
dev-loop cadence sees them at all, and only after generation). So the family needs a third
writer with its own lifecycle, and each piece of that machinery asks its own question of that
writer: what a compile round's `store_source` entry hashes when the source is the emitted file
set, what vouches for a round's completeness when the stamp that vouches for capture is
already written, and how rows written after a reader opened reach it. That is a design of its
own, and the relation would sit empty in exactly the batch runs R595's agreement tests
exercise. Per the store's own rule that a relation's DDL
lands with its first consumer (stated on the shipped `jvm_method_parameter` comment, and cheap
by construction now: new DDL is a stamp mismatch and a rebuild, never a migration), this
family lands as its own item. R569's bridge
covers the diagnostics read surface in the meantime; the sections below fix the family's
shape, and its first consumer is R569's `diagnostic` view, whose compile arm this item
re-grounds.

## The store this family lands in is the one R610 is building

`graph-partition-key-dimension` (R610, Spec) rebuilds three of the properties the sections
below would otherwise have reasoned from, so this item is specified against R610's store
rather than against R595's, and `depends-on` names it. Three consequences, each carried
through below rather than noted and forgotten.

The store stops being module-local. R610 moves the persisted file out of
`<build>/graphitron-model` into a per-user cache home shared by every graphitron module the
user builds, which turns a per-round `DELETE FROM javac_diagnostic` from a correct statement
into one dev session erasing a sibling module's diagnostics. The relation therefore takes
R610's partition dimension, and every statement this item writes is scoped by it.

Refresh stops clearing by default. R610 replaces the wholesale clear with ownership-scoped
refresh, where a run deletes exactly what it owns and leaves everything else alone. The
retention section below is written against that polarity: this relation is no longer cleared
for free by a mechanism that empties whatever it was not taught about, and it says what it
costs instead.

Concurrency stops being a fallback story. R610 opens the store in H2's mixed mode, so a second
process attaches to the live database instead of falling back or reading a copy. That removes
the awkward half of the writer's delivery argument rather than complicating it: the shared
handle stops being an in-process special case and becomes how every reader sees a round.

R610 is in Spec and moving. What this item leans on is its core (the per-user shared store,
ownership-scoped refresh, mixed mode, and the leading partition column), which has been stable
across its revisions; if any of those changes shape, the sections below are what to re-read.
Nothing here asks R610 to change, with one exception stated at the end.

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
after capture has its own writer on that writer's cadence, and capture clears the run's own
partition of it before regenerating, because the rows describe an emitted tree the run is
about to replace. Fusing the two would immediately mis-frame both
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
  graph_name    VARCHAR NOT NULL,
  file          VARCHAR NOT NULL,
  line_number   BIGINT  NOT NULL,
  column_number BIGINT  NOT NULL,
  ordinal       INT     NOT NULL,
  kind          VARCHAR NOT NULL,
  code          VARCHAR,
  message       VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, file, line_number, column_number, ordinal),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
```

`graph_name` leads the key, which is R610's convention and, here, the difference between a
correct relation and a broken one: in a per-user store shared by every module the user builds,
a graph-free `javac_diagnostic` would let one dev session's round delete another module's
diagnostics and let the `diagnostic` view answer about the wrong module. The FK to
`store_graph` is structural in the sense R610's comment requires of one: the graph is ambient
before the round exists and `NOT NULL` on every row, so the writer cannot mint a row without
one. A run captures one graph and a dev session compiles one graph's emitted sources, so a
round's rows are single-valued in this column by construction, exactly as capture's are.

It also means this family passes R610's new schema gate without an exemption, which is the
outcome that gate's polarity was chosen to produce. R610 writes it in exemption polarity
(every base relation leads its key with `graph_name` unless its family is argued in as
graph-free, with `store_`, `sql_` and `jvm_` enumerated) precisely so a family arriving later
is covered by default and has to make its case. `javac_` is such a family, it arrives after
that list is written, and it has no case to make: it partitions by graph, so it satisfies the
rule rather than joining the exemptions.

Where this family sits in R610's read discipline is worth stating, because R610's two
categories do not obviously cover it. It is graph-keyed, like the SDL families, and it is
graph-private, like the source-keyed ones: a sibling subgraph's compile errors are its
internals, not its schema contract, so cross-graph reads do not range over `javac_`. That
lands it in the same place R610 puts `sql_` and `jvm_` for read purposes while keeping the
graph as its partition dimension rather than the source, because a diagnostic is about one
graph's generated output and belongs to that graph's ownership scope.

Column semantics follow javac's own `Diagnostic` surface, which is what the family name
promises. `kind` is `Diagnostic.Kind.name()` and stays an open column, because a `CHECK`
enumerating an externally owned taxonomy would be a hand-maintained copy of javac's enum (the
reasoning that keeps `variant` open in R569's design; the closed-`CHECK` convention covers
taxonomies the model owns). `code` is `Diagnostic.getCode()`, nullable exactly where javac
returns null; it is the typed dimension a bridged copy of the display list never had, and the
concrete gain of owning the family. `message` is javac's own text: a fact here, unlike the
schema channel's rendered `message` column, because javac authored it; still display
material, never a dimension, never an agreement anchor. Below the partition column the key
adopts the (file, position, ordinal) shape R569 fixed for its bridge table, with position
spelled as the two javac columns and `ordinal` a per-`(graph_name, file, line_number,
column_number)` tie-breaker assigned in round order, so the key stays natural rather than
becoming decoration on a surrogate counter. Leading with the partition and extending the
natural key beneath it is R610's prefix-consistent shape, so R569's key is adopted rather than
permuted and its retirement path stays a re-point rather than a re-key.

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
one transaction: delete this graph's `javac_diagnostic` rows, insert the round's list under
the same graph name. The scope is not a refinement of an unscoped statement, it is the
statement: in R610's shared store an unscoped delete is one module erasing another's, so the
writer takes the session's graph name alongside its handle and no statement it issues omits
the predicate. Atomicity is what stands in for a completeness stamp: `store_stamp` vouches for
DDL and version, never for rows, and a single-transaction write means no handle ever observes
half a round.

**The session handle is stated here rather than assumed, because nothing establishes one
today.** The store's whole lifetime is currently one call: `FactCapture.run` opens
`GraphitronModelStore.openAt` and closes it inside a try-with-resources, once per generation
pass. `DevMojo` holds no `DSLContext`. R569's read side needs the same handle for the same
reason (its loader writes where its tools read), but its spec places the loader at the
workspace layer and the queries in `graphitron-mcp` without saying the two share one, so the
contract belongs to whichever item lands first and is fixed here so the two cannot land
incompatible halves:

- One handle over the store the run's mojo resolved, acquired by the dev session and closed
  with the session's other resources in `DevMojo.cleanup`. Its `dsl()` is what this writer
  takes and what every in-process reader of the `diagnostic` view queries. It carries the
  session's graph name with it, since every statement either issues is scoped by that.
  `FactCapture.run`'s `storeDirectory` argument stays the seam that names the file, which is
  the same seam R610 re-points at the per-user home, so this item states a lifetime and
  inherits a location rather than deciding one.
- A live handle, not a copy. A reader on a private snapshot (the shape the store's deleted
  read-only path used to hand back) cannot see the round the writer just wrote, which is the
  one guarantee this section exists to make. Under R610 that is no longer an in-process-only
  concern: mixed mode lets a separate process attach to the live database, so the delivery
  guarantee extends to cross-process readers instead of stopping at the JVM boundary, and a
  caller that genuinely wants a fixed view of a run takes a transaction on an attached
  connection.
- It coexists with capture without a protocol. H2 gives one process one database per file
  however many connections reach for it, which `GraphitronModelStore.close` already relies on
  (it withholds `SHUTDOWN` from a file-backed store for exactly that reason), so capture's
  per-pass open and close leave the session handle live and its ownership-scoped delete is
  visible through it. The lifecycle invariant below is therefore a consequence of the shared
  handle rather than a second mechanism.
- The edge an earlier draft left open closes in R610 rather than here, and it is worth saying
  so: a stamp mismatch used to delete and rebuild the file under a live handle, which a dev
  session spanning a generator upgrade could hit. R610 moves the DDL hash and generator
  version into the store's directory name, so an upgrade opens a different file and leaves the
  running session's alone. Nothing in this item has to defend against it.

If R569's read side lands first, this item inherits its handle unchanged and adds only the
writer; if this item lands first, the handle above is its to build. The `DevMojoTest`-tier
test in *Tests* is what pins whichever it is.

Note the relocation, stated so the reviewer reads a decision rather than a contradiction of
R569: that item places the compile channel's loader at the workspace layer in
`graphitron-lsp`; this item homes the writer with the round's producer in `graphitron`'s
compile package, because the producer owning its transcription is what makes "one flattening,
three sinks" true, and `graphitron` already depends on `graphitron-model`.

The degradations follow from R610's store rather than from R595's, and they get shorter for
it. The everyday case is a reader attaching to the live database through mixed mode, so it
sees each round as it lands and the delivery guarantee holds across processes, not just
within the dev JVM. The fallback R610 keeps is the module-local in-memory store, taken when
the shared store cannot be opened or attached to at all (no resolvable home, a read-only
location, H2 server trouble); there the writer and the in-process tools still share one
handle, so rounds stay fully visible where they are read today, and what is lost is the file,
which no reader outside the session was going to consult in that state anyway. That is
R595's persistence-is-an-optimisation stance surviving intact under a store that is now
explicitly a per-user cache: cache trouble costs warmth, never correctness. In the batch
pipeline no writer runs at all: javac runs in the consumer's build after the generator exits,
the graph's partition stays empty, and the store claims nothing it cannot know.

## Refresh and retention: owned by the graph, cleared with it

The relation gets no `store_source` row and no stamp. A previous round's rows describe an
emitted tree that generation is about to replace, so within the run's own graph the correct
retention is none, and a sibling graph's rows are none of the run's business. Those are the
two halves of R610's ownership rule, and this relation lands squarely inside it: the run owns
its graph's partition and clears it whole, the way the SDL families do, and touches no other
graph's rows.

An earlier draft of this item got that for free, and it is worth recording why it no longer
does, since the change is a policy reversal and not a detail. R595's refresh was
exemption-polarity: `StoreRefresh.clear` emptied every relation it had not been explicitly
taught to partition, so a new relation was cleared with zero refresh code, and "a relation
nobody thought about is emptied and rebuilt, never silently retained" was the safety property.
R610 replaces that with ownership-scoped deletion, where a run deletes exactly what it owns.
Under the new polarity the default flips: a relation nobody taught the refresh about is
*retained*, not emptied. So this item now owes the refresh one explicit scoped delete, and the
cost is stated rather than hidden: one statement, `graph_name = mine`, in the same place the
SDL families' scoped clear lives. Zero-code retention was a property of a mechanism that is
going away, and claiming it against R610's refresh would leave a relation that quietly
accumulates every module's stale rounds forever.

The invariant is the family's lifecycle in one sentence, now graph-scoped: after a capture of
graph G, cold or warm, G's `javac_diagnostic` partition is empty and every other graph's is
untouched; G's rows exist only between one of its compile rounds and its next generation.

## Agreement registration: an arm of its own

`FactCaptureAgreementTest` enumerates every generated relation and fails on any without a
registered arm, and none of the three landed arms fits: no independent second walk can
re-derive javac's verdict without re-running javac. R569's bridged arm does not fit either if
it lands first, so the count is left unstated: that arm's character is a census against the
list a loader copied from, and this relation has no loader and no list to census against. The
driver gains an `ORACLE` arm for relations a post-capture oracle writer owns, and the arm must
say something about content, or it is a skip list wearing an arm's name. Its anchors are
therefore two, both non-vacuous, and R610's ownership rule makes the first of them sharper
than it was. The lifecycle anchor seeds rows into the relation under *two* graph names, the
run's and a sibling's, runs capture (cold and warm), and asserts the run's partition empty and
the sibling's byte-identical. Seeding is what distinguishes "cleared" from "never written",
since an unseeded emptiness check would pass identically with the writer deleted; the second
graph is what distinguishes "cleared what it owns" from "cleared everything", which under a
shared store is the difference between a correct refresh and one that eats a sibling module's
diagnostics. It is the same shape as R610's own two-graph gate, applied to this family at its
own cadence. The content anchor drives `CompileFacts` with
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
`depends-on` names R610 and not R569, and the asymmetry is the point: R610 has to ship first,
because this relation's key and its writer's delete predicate are R610's dimension, while
neither this item nor R569 unconditionally precedes the other. The sequencing contract above
is what binds that pair, and whichever of them starts implementation first resolves the fork
with the other's spec in hand. The session-handle contract in *The writer* is the piece both
orders share, which is why it is fixed there instead of left to whichever lands first.

The view inherits the dimension with the relation. R569's compile arm selects from a
graph-keyed base relation, so it either filters to the reading session's graph or carries
`graph_name` through as a view column for a consumer that has more than one graph in view.
That is the arm's decision to make when it lands, not this item's, and it is noted here only
so it is not discovered at implementation: it does not change the arm's `source` literal, its
derived `severity` projection, or its wire shape, so R569's pins are as substrate-blind as
before.

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

## The one thing this item asks of R610

R610's *coordinate vocabulary widens with the keys* binds R589 by name: its claim relations,
demand relation and occurrence-path key inherit the dimension by definition, and R610 asks
R589's spec to say so at its next revision. This family is the same case and is not named
there, because it did not exist when that section was written. The ask is one clause: the
post-capture oracle families inherit the dimension too, on the same reasoning, so that R610's
reviewer holds this item to it the way they will hold R589. Everything else here is written
to R610 as it stands, and this item claims no right to edit another item's spec; the note is
for whichever session next revises R610.

## Implementation

- `graphitron-model.sql`: the header's prefix-picking paragraph gains the `javac_`
  vocabulary sentence and its opening count rises by one family; the cadence rule lands as
  its own sentence, not fused into prefix-picking; `CREATE TABLE javac_diagnostic` with
  `graph_name` leading the key and the FK to `store_graph`, plus `COMMENT ON`s per the
  conventions, the one-sided derivation boundary, the key-column sentinel rule, and the
  graph-keyed-but-graph-private read note stated in them. R610 edits the same header
  paragraph, so whichever lands second rebases its sentence onto the other's; the count is
  written as a rise rather than a literal so the two edits do not fight over a number.
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
- New `CompileFacts` writer as above, taking that handle and the session's graph name; every
  statement it issues carries the graph predicate.
- `DevMojo.reportCompile`: the third sink call, passing the handle the view's readers query.
- `StoreRefresh`: one scoped delete of the run's own graph's `javac_diagnostic` rows, beside
  the SDL families' scoped clear. This is the bullet R610 turns from "no change" into work:
  under ownership-scoped refresh an untaught relation is retained, not emptied, so the
  relation that used to be cleared derivationally now has to be named. The two-graph lifecycle
  anchor is what proves it clears the right rows and only those.
- `FactCaptureAgreementTest`: the `ORACLE` arm, the registration, the two-graph lifecycle
  anchor (cold and warm), and the write-read content anchor.
- R569 dovetail per the fork above: either the compile arm reads this relation from day one,
  or the bridge retirement (dropped table, dropped loader and registration, one-line view
  edit); either way the arm decides how it handles the graph column.

## Tests

- Unit (compile package): writer round-trip through a store handle; a second round replaces
  the first wholesale within its graph; a round leaves a second graph's seeded rows
  byte-identical, which is the writer-side half of the two-graph property the agreement arm
  pins on the refresh side; a successful round clears a previous failure; ordinal grain on
  repeated identical messages at the same position; `NOPOS` and null-`code` transcription;
  canonical-URI agreement between the flattening and the schema channel's spelling; the
  `Diagnostic.Kind.values()` partition pin on the severity projection.
- Pipeline tier: both `ORACLE` anchors; agreement closure still holds with no skip list; the
  relation satisfies R610's leading-`graph_name` gate without joining its exemption list.
- Maven-plugin tier: `reportCompile` writes through on the existing `DevMojoTest` seam, and
  the row is readable on the same handle afterwards, which is the delivery guarantee stated
  rather than assumed.
- R569's tool pins run unchanged; no new wire assertions.
