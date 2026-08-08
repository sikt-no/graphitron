---
id: R610
title: "SDL fact keys carry a graph partition dimension"
status: Spec
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-08
last-updated: 2026-08-08
---

# SDL fact keys carry a graph partition dimension

The fact store's SDL families key every row on the type name alone (`graphql_type` is
`PRIMARY KEY (type_name)` and everything downstream inherits that coordinate), which encodes an
assumption the store should not make: that it will only ever hold one GraphQL document universe.
A plausible future direction has one long-lived store serving several Apollo federation
subgraphs at once (shared fact gathering, cross-subgraph composition detections in the LSP, one
`graphitron:dev` process per workspace instead of per subgraph), and under federation that
assumption is false by design: entities are deliberately declared in multiple subgraphs, so
`User` in one subgraph and `User` in another are distinct facts with different fields, keys, and
ownership. Under today's keys, first-wins merge would silently fuse them, turning valid input
into either a fictional merged type or a primary-key violation, and the constraint split
(violations are capture bugs, never author errors) breaks. Directive definitions partition the
same way, since each subgraph carries its own `@link` with possibly aliased imports.

The dimension ships with its first mechanism rather than ahead of one. The persisted store
moves out of the module build directory into the per-user cache location (its own section
below), one store shared by every graphitron module the user builds, so rows from several
graphs coexist the moment a second subgraph module builds against it. Refresh then has to
delete exactly what a run owns and nothing else, and `graph_name` is the ownership boundary
that makes the delete statable. The key shape is still bought while the consumer count is
zero: R589 (`validation-adds-facts`) is in Spec and about to migrate consumers onto these
relations, the store has no persisted state of record (a store that cannot prove itself
current is discarded, never migrated), and changing the model is editing the DDL and
following the compiler. The same change after R589's migration touches every consumer's
queries; today it touches capture, refresh, and the gates.

One fork is dismissed on the record: **one store per graph** costs nothing now and needs no
rekey, but it cannot serve the target case. Shared fact gathering and cross-graph composition
detections need the facts side by side in one queryable store; a per-graph store makes every
cross-graph question an application-level merge, which is the thing the store exists to
avoid. The per-user store below is that single store made concrete: graphs accumulate in it,
one per module build.

## The DDL change

`graph_name VARCHAR NOT NULL` joins every base relation of the `graphql_` and `graphitron_`
families (83 relations today) as the **leading** primary-key column. Leading follows the
`sql_table` precedent, where the namespace dimension (`table_schema`) leads the natural key,
and makes every widened composite FK a prefix-consistent extension rather than a permutation.
The cascade through all 83 relations is forced, not chosen: the store admits no surrogate
keys, so a child key without `graph_name` would not be unique across graphs. One decision,
one blast radius as its consequence.

- Foreign keys between the two families widen automatically, since they reference the
  widened keys. The `graphql_directive_site` union view gains the column in every arm.
- A new `store_graph` relation anchors the partition: `graph_name` as key, `last_captured`
  (the bookkeeping the eviction story below needs), and the graph's **build identity**,
  `base_dir`, `build_file_path` and `build_file_stamp` (the module's pom, content-hashed;
  all three NULL on a programmatic run with no build file). The seven family relations with
  no in-family parent (`graphql_type`, `graphql_root_operation`, `graphql_directive`,
  `graphql_schema_directive`, `graphql_duplicate_declaration`, `graphitron_link`,
  `graphitron_undecoded_argument`) get a direct FK to it; every other relation reaches it
  through its existing parent chain. That set is the FK closure over the DDL rather than a
  hand-kept list: the two position-keyed roots are the ones an eye misses, because nothing
  references them either, so they read as leaves rather than as roots. The reachability gate
  below recomputes the closure, so this enumeration is checked at build time rather than
  trusted. `store_source` gains the matching `last_seen` timestamp.
- The graph's **SDL recipe** is remembered beside it, written fresh by every run from its
  resolved configuration: `store_graph_schema_input (graph_name, ordinal, pattern, tag,
  description_note)` transcribes the `<schemaInputs>` bindings, and an ordered child carries
  the effective schema-file-extension filter. The recipe is config, an input capture holds
  in hand, not a derivation over captured rows, and it records something the read-set never
  can: how to *find* the graph's schema files, including ones that do not exist yet. The
  `tag` and `description_note` columns are not optional fidelity: those appliers run above
  the capture cut, so replaying a graph's SDL capture without them would mint different rows
  than the graph's own build. The shape is chosen to be absorbable: R612 explores promoting
  resolved configuration to a fact family the scanner itself reads, and these relations are
  its compatible first slice, adoptable without a rekey.
- The `store_graph` comment owes two discriminators, so the DDL's conventions stay readable
  as consistent. First, why this FK exists while the SDL-to-`store_source` FK was declined:
  the graph is ambient before the walk begins and `NOT NULL` everywhere, while the source
  rows are a summary collected last and nullable at schema-level sites, so the FK doctrine
  admits one and not the other. Second, a recorded exemption: any derivation joining an SDL
  fact to a catalog or classpath fact (`graphitron_service`'s class name against `jvm_class`,
  `graphitron_table`'s table reference against `sql_table`) is underdetermined in a shared
  store until the membership relation says which sources are the joining graph's; such joins
  are deferred with their consumers, and the membership relation lands with them. Stating it
  makes the next such join read the rule instead of inheriting the hazard silently.
- `jvm_` and `sql_` stay graph-free but stop being store-global, because the shared store
  makes their bare natural keys unsound: two modules' classpaths can carry two versions of
  one class name, two modules' catalogs two shapes of one `(schema, table)` coordinate, and
  under merge writes the second build would silently clobber facts owned by a source it
  never crawled. Their keys gain their **source**, not the graph: `jvm_class` keys
  `(source_name, class_name)`, the `sql_` family leads with its generated-package source,
  children widening through the usual FK cascade. The same jar at the same path (the shared
  `~/.m2` case) stays one partition, written once and shared; different versions are
  different sources and coexist; the version-skew collision becomes representable instead
  of a clobber. Which sources make up a graph's classpath stays a membership and derivation
  question for the consumers that need it. `store_source` and `store_stamp` keep their keys.
  One existing comment goes stale on this and the rekey rewrites it: `jvm_class` says a class
  present under more than one entry is captured once, at the entry that comes first in
  classpath order. That stays true of a single run and becomes misleading store-wide, where
  two runs' entries are two partitions that coexist by design. The comment gate checks that a
  comment exists, not that it is still true, so this one is named here to be caught.

## The graph has a configured name

The graph name is consumer configuration, not a generator constant: a `<graphName>` parameter
on `AbstractRewriteMojo`, beside `<schemaInputs>` where the consumer already declares what the
graph is made of, defaulting to `${project.artifactId}`. The artifactId is the module's
identity in the build, unique within a reactor, which is exactly the namespace a future
multi-subgraph workspace would key its graphs by; a consumer whose subgraph name differs from
the module name overrides the parameter. The value threads through `RewriteContext` to
capture like every other mojo parameter, and the user-manual page
`docs/manual/reference/mojo-configuration.adoc` documents it beside the schema inputs.

`RewriteContext` requires the name non-blank at construction, the same non-null contract its
other fields carry; Maven users never see the requirement because the mojo default always
supplies a value. The programmatic construction sites state a name once each, and there are
55 of them across 53 files in five modules (`graphitron`, `graphitron-lsp`,
`graphitron-maven-plugin`, `graphitron-mcp`, `graphitron-sakila-example`): all six
convenience overloads (fourteen-arg down to five-arg) delegate to the canonical constructor,
so a required field reaches every caller of every one of them. That is the item's largest
mechanical edit, and its size is stated here rather than discovered by the implementer. It is
still the right trade. The rejected alternative is a core fallback constant for callers that
configure nothing: a
fallback name is an unowned name, every caller has a natural identity to give, and a
required field is the enforcer the convention would lack. Defaulting the name inside the
test-tier overloads instead would be that same fallback relocated somewhere the gate cannot
see it, so the overloads gain the parameter rather than a value. The edit itself is
compiler-led and each site names the fixture or test it already is.

A run still captures exactly one graph; the store may hold many. Per-run single-graph is
enforced by construction through the graph-scoped sink below, not by a row-count gate, since
a shared store legitimately accumulates one `store_graph` row per module ever built against
it.

## The store lives with the user

The persisted store leaves `target/`. Its default home follows the platform's cache
conventions for per-user tool state: `$XDG_CACHE_HOME/graphitron/model/` (falling back to
`~/.cache/graphitron/model/`) on Linux, `~/Library/Caches/graphitron/model/` on macOS,
`%LOCALAPPDATA%\graphitron\model\` on Windows. It is a cache by nature (rebuildable from
sources, no state of record), which is what makes the cache directory the right convention
rather than the data one. A `<storeDirectory>` mojo parameter with a matching
`graphitron.store.directory` property overrides the default for consumers and CI setups that
want the store module-local or hermetic; `FactCapture.run`'s existing `storeDirectory`
argument is already the seam, so the change is in what the mojos resolve and pass, inside
`AbstractRewriteMojo.resolveStoreDirectory`. The `mojo-configuration.adoc` manual page
documents `<storeDirectory>` beside `<graphName>`.

The move costs one property worth naming rather than leaving to be discovered: `mvn clean`
stops being the store's recovery story, which `resolveStoreDirectory`'s javadoc currently
states in those words and which this item rewrites. The stamped path below replaces most of
it, since the failure that recovery story was written for (a store from an older DDL or an
older generator) now opens a different file instead of needing to be cleaned. The residue is
a store corrupt in a way `store_stamp` still accepts, and its remedy is deleting the cache
directory by hand. That is a thinner story than `mvn clean`, and it is accepted knowingly: a
named command for it belongs with the eviction surface this item defers, which is the same
surface that has to list and drop partitions anyway.

The directory name carries the store's compatibility stamp: the DDL hash and generator
version that `store_stamp` records move into the path, so a generator upgrade or DDL change
opens a **different file** instead of discarding a shared store that other modules' builds
are still warm on, and mid-upgrade reactors (two modules on two graphitron versions) coexist
in two files instead of thrashing one. `store_stamp` stays inside the file as the integrity
check for a hand-moved or hand-corrupted store.

Sharing a file between module builds makes concurrent access the norm, not the edge: `mvnd`
builds reactor modules in parallel by default. The store opens in H2's mixed mode
(`AUTO_SERVER`), where the first process holds the file and later processes attach to it
transparently; the shared families (`store_source`, `sql_`, `jvm_`) are written as
idempotent per-source merge transactions so two builds crawling the same new jar
concurrently both land, last writer winning on content the stamps make identical. Any
failure to open or attach to the shared store (no resolvable home, read-only location, H2
server trouble) falls back to the module-local in-memory store: cache trouble may cost
warmth, never correctness, and never fails a build. Tests never touch the real user cache;
they inject temp directories through the same `storeDirectory` seam they use today.

Mixed mode reverses a decision the code already records, so the reversal is argued rather
than assumed. `GraphitronModelStore.openReadOnly` rejected the server approach in as many
words, on the grounds that it turns a build artefact into a running service every reader has
to opt into, and took a copy-to-temp snapshot instead. That reasoning was right for the
store it was written against: one file per module, one writer, and a reader (`graphitron:dev`
beside `mvn install`) that wanted a fixed snapshot anyway. A per-user store shared by every
module changes the premise under it. Concurrent writers become the normal case rather than
the collision to survive, and the current answer to a held file, `GraphitronModelStore.openAt`
falling back to the in-memory store, would hand every module but the first in a parallel
reactor build a cold load: the shared store would cost warmth in precisely the configuration
it exists to warm. The "running service" objection also weakens once the host process is a
build the user started rather than a daemon they have to adopt.

Two existing seams move with that reversal, and this item owns both rather than leaving them
to contradict the code. `openReadOnly`'s copy becomes an attach, and the concurrency
rationale in its javadoc is rewritten rather than left standing against the shipped
behaviour; its only callers today are in `PersistentStoreTest`, so the change is cheap, and
whether a reader wanting a fixed view holds a transaction or simply re-reads is settled at
implementation, since no consumer has that requirement yet. And `openAt`'s in-use branch
becomes unreachable, because the second opener attaches instead of failing: that branch and
its `isAlreadyOpen` helper are deleted, not left as dead code documenting a lock the store
no longer takes.

## Capture

`FactSink` becomes **graph-scoped** rather than every call site threading a new leading
argument. The sink is constructed with the graph name, stamps the `graph_name` column on
every record it buffers, and namespaces its `claim` keys by its own graph. This is the load-
bearing half of the item: `claim` is a hand-maintained mirror of every natural key, and
widening the DDL keys without widening the claim keys would relocate the fusion this item
exists to prevent one layer up, where a two-graph load would first-wins-drop the second
graph's types before the widened primary keys could ever see them, misfiling them as author
duplicates. Scoping the sink leaves every SDL-family `claim` call site untouched and correct
by construction; a future multi-graph load is a second sink, not two hundred call sites that
each remember a new argument. The source-keyed families' few claim sites change with their
keys, which is the compiler-led part of the rekey.

`StoreRefresh` becomes **ownership-scoped**: a run deletes exactly what it owns and touches
nothing else. Owned means two things. The run's graph: the SDL families clear scoped to
`graph_name = mine` and rebuild whole, because within one graph the parse they rebuild from
is paid for regardless, and other graphs' rows are another run's business. And the run's
crawled sources: for each schema file, classpath entry, and jOOQ package in this run's input
set, the existing stamp logic decides retain-or-rewrite; a source **not** in the input set is
never examined and never deleted, because a jar absent from this module's classpath may be
another graph's live dependency. `store_source` and `store_graph` rows upsert with fresh
`last_seen` / `last_captured` stamps and are never deleted by a run that does not own them.

### Freshness is heterogeneous by design

A user edits one subgraph at a time, but does not *receive* change that way: in a monorepo,
`git pull` lands other people's edits to sibling subgraphs with no local build of those
modules. The shared store's steady state is therefore one hot graph, current to the
keystroke, beside sibling partitions that faithfully describe sources a pull may since have
moved underneath them. A cold partition is not merely old; it can be wrong about the working
tree while remaining true to what it read, and that happens routinely, not exceptionally.
Ownership-scoped refresh accepts this rather than engineering it away (no run pretends to
refresh facts it did not read); what the store owes in exchange is keeping **age and
currency distinguishable, and both visible**. Two different questions, two different
substrates, both shipping here:

- **Age** (when was this read): `store_graph.last_captured` and `store_source.last_seen`
  date every partition.
- **Currency** (is what was read still what would be read now): a cold graph's currency is
  checkable without building its module, and the check has two instruments because
  staleness has two shapes. `<schemaInputs>` binds patterns, not files, so a pull can grow
  or shrink a sibling's match set with no edit to any file the store ever read; a check
  over recorded sources alone is blind to exactly that arrival. So the set question comes
  first: re-run the remembered recipe's globs over `base_dir` and compare the resulting
  file set against `store_source`, which catches added and deleted schema files. Then the
  content question: re-hash the files both sides agree on against `store_source.stamp`,
  which catches edits. A partition is current only when the re-expansion reproduces the
  recorded set and every file in it re-hashes to its stamp; a new match, a lost match, and
  an edit are one verdict. The remembered recipe is itself trusted only while the build
  file it was resolved from still hashes to the recorded build identity, which is where the
  check-and-repair loop below starts. Age cannot answer any of these questions at all,
  since a pull invalidates a partition captured a minute ago as readily as one from last
  month. Staleness detection costs a glob walk and a hash, not a capture. For SDL sources the check needs two
  halves, and only one exists today. The address half is settled: which schema files a
  graph read is derivable from its declaration sites, and the `source_name` those record is
  already resolvable from outside the owning module's build on the Maven path, where
  `SchemaInputExpander` writes it absolute and normalized before it ever reaches the parser.
  The baseline half ships here, and it reverses a recorded decision, so the reversal is
  argued like the mixed-mode one: `SdlFactCapture.captureSources` leaves `SCHEMA_FILE` rows
  unstamped in as many words, on the grounds that graphql-java hands the walk source names
  rather than text, so hashing meant re-reading files capture does not own. That reasoning
  priced the hash against a store nothing read across builds; a currency check run against
  the working tree without building the module is exactly the reader worth one file re-read
  per schema file at capture time. Capture therefore stamps each source name that resolves
  to a regular file when it writes the source summary, and the two comments that record the
  old decision are rewritten with it: the `captureSources` javadoc, and the
  `store_source.stamp` column comment, which justifies the NULL for schema files in as many
  words. One knock-on is inert and stated so it reads as chosen rather than missed:
  `StoreRefresh.freshSources` starts seeing stamped `SCHEMA_FILE` rows, and nothing changes,
  because the fresh set feeds only the `jvm_` claims and the SDL families still clear and
  rebuild whole within their graph.
  The residue is the two source kinds that never resolve to a file, the bundled
  `directives.graphqls` (recorded under its resource name, and shipped inside the generator,
  so never stale against the working tree) and programmatic callers handing a bare name to
  `SchemaInput.plain`; both stay unstamped exactly as the null-while-loading discipline
  already allows. A cross-graph reader skips a source it cannot resolve to a file rather
  than reporting it current, which keeps the unresolvable case honest without blocking the
  check on the Maven path that carries every real consumer. Sibling catalog and classpath
  currency is nobody's question: those families sit outside the cross-graph read surface
  below, and within the owning graph the run's own configuration names its input set.

The rule this binds future consumers to, stated now so it is reviewable now: a cross-graph
reader treats age and currency as inputs, and currency means the full check, never the
re-hash alone. A composition detection over a sibling partition whose recipe re-expansion
or re-hash fails reports against a stated stale baseline or declines, and never presents a
heterogeneous store as uniformly current.

With the build identity and the SDL recipe remembered on `store_graph`, the check-and-repair
loop becomes executable for **every graph the store knows about**, with no build of any
sibling module. For each known graph: re-hash its build file, and on a mismatch treat the
remembered recipe as possibly stale, repaired the next time that module builds (resolving a
pom's config outside Maven is not worth owning); on a match, re-run the recipe's globs over
`base_dir`, which discovers added and deleted schema files, not just edited ones; hash the
resulting file set against `store_source`; and where anything moved, re-capture that graph's
SDL partition by parse alone, replaying the recipe's tags and description notes, because
parse-to-registry is the linear half of the pipeline and capture is infallible by
construction. This item ships the substrate (the columns, the recipe relations, the
schema-file stamps, capture writing them every run); the loop's driver (in the dev goal's watcher, the LSP, or a store
open) is the orchestration item's first move, and it starts with everything it needs.

Collaboration does not flow through the store, which is what keeps the concurrency posture
honest: the store is per-user and per-machine, other people's work arrives as source changes
via git, never as store writes, so parallel module builds meet in the file on a full reactor
build while the everyday case stays a single hot writer.

### The cross-graph read surface is the schema contract

Working on subgraph A, the sibling facts that matter are what B's schema **declares**: its
types, fields, entities and keys, the `graphql_` transcription and the SDL-derived decoded
stratum. B's implementation facts (its catalog, its classpath, what its binding directives
resolve to) are B's private business, encapsulated exactly as federation intends: A consumes
B's contract, never B's internals. The spec fixes that as the store's read discipline:
**cross-graph reads range over the SDL-derived families only**, and the `sql_` and `jvm_`
families are read by their own graph's consumers alone. Two alignments fall out, load-bearing
rather than lucky. The cross-graph read surface coincides with the parse-only re-capture
surface, so the sibling facts a consumer wants are exactly the ones a pull-staleness repair
can refresh without building the sibling. And every family whose key partitions by source
rather than by graph is one no cross-graph consumer reads, so their graph-freeness costs
nothing.

That second alignment runs one way only, and the direction matters, because the obvious
converse is false. Source-keyed implies graph-private, but graph-keyed does not imply
cross-graph-readable: a family can partition by graph and still be its graph's internals.
The post-capture oracle families are the live case (R603's `javac_` is graph-keyed, because
a diagnostic is about one graph's generated output and belongs to that graph's ownership
scope, and graph-private, because a sibling's compile errors are its internals and not its
schema contract). So the read discipline is stated positively and admits no inference from
the key: cross-graph reads range over the SDL-derived families, and every other family,
however keyed, is read by its own graph's consumers alone. A family arriving later joins the
cross-graph surface by being argued onto it, which is the same polarity the schema gate
takes and for the same reason. The rule governs consumers; the store's own maintenance
machinery sits beneath it rather than being bound by it, which the freshness check above
already relies on when it reads every graph's `store_graph` bookkeeping and recipe. That is
the store keeping itself honest, not a consumer reading another graph's facts, and a reader
counts as maintenance exactly when it writes no conclusions anywhere but the `store_`
family.

Retention of unowned rows is justified by ownership, not by a freshness proof: the run that
owns them refreshes them on its own cadence. The residual hazard is the orphan partition, and
it is accepted and instrumented rather than solved here: a renamed graph (an artifactId
change, or `<graphName>` set for the first time) leaves the old graph's rows behind, and a
jar that leaves every classpath keeps its rows, in both cases until eviction reads the
`last_captured` / `last_seen` stamps this item writes. The eviction command itself (an age
policy, a `graphitron:dev` or MCP surface to list and drop partitions) is deferred with its
first consumer; the stamps land now because a schema written without them cannot support
eviction later without another pass over capture.

## Gates

Three new gate queries join `FactSchemaGateTest`, and the first two are written in exemption
polarity, the same polarity `StoreRefresh.wholesale()` already chose:

- **Every base relation leads its primary key with `graph_name` unless its family is
  deliberately graph-free**, with the exempt prefixes (`store_`, `sql_`, `jvm_`) enumerated
  in the gate and justified in a line each. An allow-list over today's two prefixes would
  silently not cover the reserved `intent_` stratum and R589's claim relations, which is
  exactly where the dimension matters most; under exemption polarity a new family is covered
  by default and its exemption has to be argued in. The exemption is not key-freedom: the
  same gate checks that `sql_` and `jvm_` relations lead with `source_name` instead, their
  partition dimension; only the `store_` family itself carries neither.
- **Every graph-keyed relation reaches `store_graph` by foreign key**, walked as a closure
  over `INFORMATION_SCHEMA` rather than compared against a list. A `graph_name` column with
  no FK path to the anchor is a column the database will not defend: it admits rows naming a
  graph that was never captured, and it leaves the delete `StoreRefresh` scopes by graph
  relying on a value nothing constrains. The first gate above checks the column is present
  and leading; this one checks it means something. It also makes the parentless enumeration
  in the DDL section self-enforcing, which is the point of writing it as a closure: that list
  is easy to get wrong by eye (the two position-keyed roots read as leaves, since nothing
  references them either), and a gate that recomputes the closure catches the omission at
  build time instead of leaving an unanchored relation to be found by a consumer later.
- **A run writes only under its own graph**: after capturing into a store pre-seeded with a
  second graph's rows, that graph's partition is byte-identical and no row of the run's
  output carries any other `graph_name`. This is the two-graph fusion test the item's
  motivation promises, and it doubles as the enforcer for per-run single-graph capture now
  that a store legitimately holds many graphs.

Comment coverage extends to the new columns automatically through the existing gate.

## The coordinate vocabulary widens with the keys

Store-wide, "coordinate" now means `(graph_name, ...)`. R589's claim relations, the demand
relation, and the occurrence-path key inherit the dimension by definition rather than by
later amendment; without this sentence the rekey this item buys against resurfaces one
stratum up, at exactly the moment it predicted the change would be expensive. R589's Spec
should say so when it next revises, and its reviewer can hold it to this item.

The post-capture oracle families inherit the dimension on the same reasoning, and R603 is the
first of them. A family whose writer runs after capture still describes one graph's run, so
it partitions by graph exactly as the transcription families do; the cadence its writer runs
on is an orthogonal axis and buys no exemption from the key. R603 asked for this clause
rather than editing this item, which is the right way round, and its reviewer can hold it to
the same rule. Note that inheriting the dimension is not admission to the cross-graph read
surface: R603 is graph-keyed and graph-private, which is the asymmetry the read-discipline
section above states.

## What stays put

The agreement anchors compare one pipeline run against that run's own graph; they gain the
graph name in their join keys and nothing else changes in what they assert. Within one
graph, warm-start retention semantics are unchanged: stamps decide retain-or-rewrite exactly
as shipped. No consumer queries exist yet to update, which is the point of doing this now.

## Deliberately out of scope

- **`store_graph_source` membership for the classpath and catalog.** The Backlog stub
  proposed general membership; the SDL side now lands through the recipe relations above,
  which serve the freshness reader and subsume it (the recipe finds files membership could
  only list). What stays out is the `jvm_`/`sql_` side: which classpath entries and
  generated packages make up a graph. Nothing reads it yet; the candidate readers are
  eviction (which sources does no graph still reference) and per-graph resolution over the
  source-keyed families, while cross-graph composition detections need neither, reading
  sibling SDL rows keyed by graph directly. It lands with the first reader, as an input
  written by capture, not a derivation.
- **Eviction.** Orphaned partitions (renamed graphs, jars no classpath references) accumulate
  until a policy or command drops them; this item writes the `last_captured` / `last_seen`
  stamps eviction needs and defers the eviction surface itself.
- Multi-graph capture orchestration in one process and per-graph classloader scopes; any
  composition-detection stratum; graph-aware resolution across the `jvm_`/`sql_` boundary.
  Each can land later without rekeying anything, which is the test this item's scope was
  cut by.

## Verification

Full `mvn install -Plocal-db` green. The DDL edit follows the compiler through capture and
the tests; the widened gate family and the agreement suite are the honesty check, and the
two-graph test above is the first assertion the multi-graph store has ever had. The
persistence tests (`PersistentStoreTest`, `WarmStartRefreshTest`) grow the ownership cases:
a second graph's partition survives a refresh, an uncrawled source's rows survive a refresh,
concurrent opens through mixed mode land both writers' rows, a schema file's recorded stamp
matches a re-hash until the file is edited and mismatches after, a file added under a
remembered recipe's pattern is discovered by re-expansion with no build of the owning module,
and a graph's build identity and recipe rows are rewritten by its own run and untouched by a
sibling's. Generated output is
untouched.

The reactor takes the per-user default for itself rather than pinning `<storeDirectory>`,
which is a decision and not an omission. Every module build then opens the shared store the
moment the change lands, so a full `-T 1C` reactor build is a real concurrent-writer test of
mixed mode across genuinely different graphs, which is a harder exercise than any fixture can
stage. Two properties keep that from leaking into what the build asserts. CI caches `~/.m2`
and not `~/.cache`, so every CI run starts from a cold store and no result depends on a
previous run's rows; and the store is a cache with no state of record, so a warm run and a
cold run agree by the invariant the agreement anchors already pin. If the shared store ever
does make a reactor build non-reproducible, that is the store failing its own contract and
the anchors are where it should surface, not something pinning the directory should hide.
