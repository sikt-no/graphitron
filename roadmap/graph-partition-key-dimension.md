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
- A new `store_graph (graph_name, last_captured)` relation anchors the partition, the
  capture timestamp being the bookkeeping the eviction story below needs. The family
  relations with no in-family parent (`graphql_type`, `graphql_directive_definition`,
  `graphql_schema_directive`, `graphql_duplicate_declaration`, `graphitron_link`) get a
  direct FK to it; every other relation reaches it through its existing parent chain.
  `store_source` gains the matching `last_seen` timestamp.
- The `store_graph` comment owes two discriminators, so the DDL's conventions stay readable
  as consistent. First, why this FK exists while the SDL-to-`store_source` FK was declined:
  the graph is ambient before the walk begins and `NOT NULL` everywhere, while the source
  rows are a summary collected last and nullable at schema-level sites, so the FK doctrine
  admits one and not the other. Second, a recorded exemption: any derivation joining an SDL
  fact to a catalog or classpath fact (`graphitron_service`'s class name against `jvm_class`,
  `graphitron_table`'s table reference against `sql_table`) is graph-blind and correct only
  while exactly one graph exists; the multi-graph orchestration item closes it. Stating it
  makes the next such join read the rule instead of inheriting the hazard silently.
- `jvm_`, `sql_`, `store_source`, and `store_stamp` keep their keys untouched. Per-graph
  classpath scope is a membership and derivation question, not capture, and version skew
  between two graphs' classpaths is that future item's business.

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
supplies a value, and the handful of programmatic construction sites (fixture codegen, test
helpers) state a name once each. The rejected alternative is a core fallback constant for
callers that configure nothing: a fallback name is an unowned name, every caller has a
natural identity to give, and a required field is the enforcer the convention would lack.

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
argument is already the seam, so the change is in what the mojos resolve and pass. The
`mojo-configuration.adoc` manual page documents `<storeDirectory>` beside `<graphName>`.

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

## Capture

`FactSink` becomes **graph-scoped** rather than every call site threading a new leading
argument. The sink is constructed with the graph name, stamps the `graph_name` column on
every record it buffers, and namespaces its `claim` keys by its own graph. This is the load-
bearing half of the item: `claim` is a hand-maintained mirror of every natural key, and
widening the DDL keys without widening the claim keys would relocate the fusion this item
exists to prevent one layer up, where a two-graph load would first-wins-drop the second
graph's types before the widened primary keys could ever see them, misfiling them as author
duplicates. Scoping the sink leaves every existing `claim` call site untouched and correct by
construction; a future multi-graph load is a second sink, not two hundred call sites that
each remember a new argument.

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
- **Currency** (is what was read still what is on disk): `store_source.stamp` is a content
  hash, so a cold graph's currency is checkable without building its module: re-hash its
  recorded sources and compare. Age cannot answer this question at all, since a pull
  invalidates a partition captured a minute ago as readily as one from last month.
  Staleness detection costs a hash, not a capture. For SDL sources the check is fully
  supported today: which schema files a graph read is derivable from its declaration
  sites, and the `source_name` those record must be resolvable from outside the owning
  module's build (absolute or anchored to a stated root, settled at implementation; today
  it is whatever path the parser was handed). Catalog and classpath currency need the
  deferred membership relation, recorded below as one of its readers.

The rule this binds future consumers to, stated now so it is reviewable now: a cross-graph
reader treats age and currency as inputs. A composition detection over a sibling partition
whose sources fail the re-hash reports against a stated stale baseline or declines, and
never presents a heterogeneous store as uniformly current. The natural repair is also worth
recording as a pointer for the orchestration item: a stale sibling's SDL partition can be
re-captured by a parse alone, no build of its module, because parse-to-registry is the
linear half of the pipeline and capture is infallible by construction.

Collaboration does not flow through the store, which is what keeps the concurrency posture
honest: the store is per-user and per-machine, other people's work arrives as source changes
via git, never as store writes, so parallel module builds meet in the file on a full reactor
build while the everyday case stays a single hot writer.

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

Two new gate queries join `FactSchemaGateTest`, and the first is written in exemption
polarity, the same polarity `StoreRefresh.wholesale()` already chose:

- **Every base relation leads its primary key with `graph_name` unless its family is
  deliberately graph-blind**, with the exempt prefixes (`store_`, `sql_`, `jvm_`) enumerated
  in the gate and justified in a line each. An allow-list over today's two prefixes would
  silently not cover the reserved `intent_` stratum and R589's claim relations, which is
  exactly where the dimension matters most; under exemption polarity a new family is covered
  by default and its exemption has to be argued in.
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

## What stays put

The agreement anchors compare one pipeline run against that run's own graph; they gain the
graph name in their join keys and nothing else changes in what they assert. Within one
graph, warm-start retention semantics are unchanged: stamps decide retain-or-rewrite exactly
as shipped. No consumer queries exist yet to update, which is the point of doing this now.

## Deliberately out of scope

- **`store_graph_source` membership.** The Backlog stub proposed it and the shared store
  sharpens the question: in a multi-graph store, which sources a graph reads is no longer
  derivable from the graph-blind `jvm_`/`sql_` rows. It still stays out, because nothing
  reads it yet: each run knows its own input set from configuration, and the three candidate
  readers (eviction, cross-graph composition detections, and currency checks on a sibling's
  catalog and classpath sources, per the freshness section) are all deferred. It lands with
  the first of them, as an input written by capture, not a derivation.
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
and concurrent opens through mixed mode land both writers' rows. Generated output is
untouched; the reactor's own builds exercise the per-user default the moment the change
lands, since every module build now opens the shared store.
