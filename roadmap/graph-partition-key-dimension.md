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
below), one store shared by every graphitron module of one workspace, so rows from several
graphs coexist the moment a second subgraph module of that workspace builds against it. Refresh
then has to delete exactly what a run owns and nothing else, and `graph_name` is the ownership
boundary
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
avoid. The workspace store below is that single store made concrete: graphs accumulate in it,
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
  description_note)` transcribes the `<schemaInputs>` bindings, and
  `store_graph_schema_extension (graph_name, ordinal, extension)` carries the effective
  schema-file-extension filter, which is a per-run set rather than a per-binding one and so sits
  beside the bindings rather than under them. The recipe is config, an input capture holds
  in hand, not a derivation over captured rows, and it records something the read-set never
  can: how to *find* the graph's schema files, including ones that do not exist yet. The
  `tag` and `description_note` columns are not optional fidelity: those appliers run above
  the capture cut, so replaying a graph's SDL capture without them would mint different rows
  than the graph's own build. The shape is chosen to be absorbable: R612 explores promoting
  resolved configuration to a fact family the scanner itself reads, and these relations are
  its compatible first slice, adoptable without a rekey.
- A recorded pattern is only worth as much as the glob engine that re-expands it, so the
  **dialect is part of the recipe's contract and gets one implementation**, in `graphitron`. The
  patterns are plexus `DirectoryScanner` includes, which is what `SchemaInputExpander.expand`
  feeds `scanner.setIncludes`, and `plexus-utils` is today a dependency of
  `graphitron-maven-plugin` alone, which depends on `graphitron` and not the other way round. So
  a re-expansion primitive in core cannot reach the dialect, a re-expansion primitive in the
  plugin cannot be reached by core's persistence tests or by the LSP, and two implementations
  that must agree and cannot be made to is the one outcome worse than either: a pattern recorded
  faithfully and re-expanded by a second dialect returns a confidently wrong currency verdict,
  which is precisely what this substrate exists to rule out. `plexus-utils` therefore moves to
  `graphitron`, a `SchemaRecipe.expand(baseDir, patterns, extensions)` primitive owns the walk
  and the extension filter, and `SchemaInputExpander` delegates to it, keeping only the
  Maven-shaped work (reading `SchemaInputBinding`, the empty-pattern diagnostics, the
  `MojoExecutionException`). The version moves with it into the root pom's properties, where the
  rest of the pinned versions live and where this one is not today. This reverses a recorded
  decision and is argued like the others:
  `SchemaInputExpander`'s javadoc puts the expansion in the plugin so "rewrite-core stays
  filesystem-agnostic", which was right when core never touched a schema file it had not been
  handed, and is not once core re-hashes files at capture time and re-globs a tree to answer a
  currency question. That javadoc is rewritten with the move.
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

**The default's uniqueness scope is the reactor, so the store's scope is the workspace.** That
sentence is the load-bearing one and the reason the opening section says workspace rather than
user. `${project.artifactId}` is unique within a reactor and nowhere wider: two checkouts of
one repository (a worktree, a release clone) and two unrelated projects each carrying a module
called `api` would, in a store shared across every reactor a user builds, claim the same
`graph_name`. Nothing would violate a constraint, which is what makes it the worse failure:
ownership-scoped refresh clears `graph_name = mine` and rebuilds whole, so the two builds would
thrash each other's partition on every run, silently, with `warm()` true and rows present that
describe the other tree. The cross-graph surface would fare worse still, since `base_dir` and
`build_file_path` are rewritten by whichever ran last, so a sibling reader would re-expand the
recipe's globs over the wrong checkout and return a currency verdict about a tree it has no
interest in. That is this item's own fusion hazard displaced one level up, from subgraphs
inside a workspace to workspaces inside a machine, and the fix is to make the store's sharing
scope equal the name's uniqueness scope rather than to make the name globally unique: a globally
unique default name is a name nobody chose, which is the same objection that sinks the fallback
constant two paragraphs below, and it would be earned here by a mangled artifactId instead of by
a constant.

A residual survives the workspace scoping and is closed by rule rather than by construction,
because a consumer overriding `<graphName>` to the same value on two modules, or a programmatic
caller handing over any string it likes, can still collide inside one workspace. `store_graph`
records `base_dir`, so the run can see it, and the check falls where the store is opened and the
row is readable rather than in the mojo, which never reads the store: **a run whose `graph_name`
is already recorded against a different `base_dir` does not take the name over.** It closes the
shared store, falls back to the module-local in-memory one and leaves the file alone, exactly as
it does for a store it could not open, and logs a warning through the module's slf4j logger
naming both directories and `<graphName>` as the remedy. This is the one cache condition a
consumer can actually fix, which is why it is the only one that says anything at all. It goes to
the log and deliberately not through `BuildWarning`: that surface is sealed around schema-shape
advisories, every arm carries a `SourceLocation`, and it replays into LSP squiggles and the MCP
`diagnostics` tool, none of which a cache-partition collision belongs in. Warmth is the only
thing lost, which is the standing rule for everything about this cache, and the partition stays
truthful for the graph that owns it instead of describing two trees by turns.

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
argument is already the seam, so what the mojos change is the *home* they resolve and pass,
inside `AbstractRewriteMojo.resolveStoreDirectory`. That method is the only store-home resolver
in the tree, and every other opener (the `dev` goal's server, the LSP catalog, MCP) reaches the
store through the `RewriteContext` it built, so there is one place for the rest of this section
to be true of. The `mojo-configuration.adoc` manual page
documents `<storeDirectory>` beside `<graphName>`, and that documentation is build-enforced
rather than promised: `MojoDocCoverageTest` already fails on an editable `@Parameter` with no
row on that page, so both new parameters are covered by a gate that exists.

The move costs one property worth naming rather than leaving to be discovered: `mvn clean`
stops being the store's recovery story, which `resolveStoreDirectory`'s javadoc currently
states in those words and which this item rewrites. The stamped path below replaces most of
it, since the failure that recovery story was written for (a store from an older DDL or an
older generator) now opens a different file instead of needing to be cleaned. The residue is
a store corrupt in a way `store_stamp` still accepts, and its remedy is deleting the cache
directory by hand, or the one workspace segment under it that the workspace scoping above makes
separable. That is a thinner story than `mvn clean`, and it is accepted knowingly: a
named command for it belongs with the eviction surface this item defers, which is the same
surface that has to list and drop partitions anyway.

The default home carries a **workspace segment** under the cache root, which is what makes the
graph-name scoping above structural rather than hopeful: one file per workspace, holding every
graph that workspace's modules capture, and no file holding two workspaces' graphs. The
workspace is the reactor root, resolved by walking `MavenProject.getParent()` while the parent
has a basedir that is an ancestor of the child's and taking the last one, with the module's own
basedir as the fallback when no parent resolves from the reactor. The walk rather than
`MavenSession`'s top-level project or execution root, because those two answer "where was mvn
invoked", so building one module from inside its own directory would resolve a different
workspace than building it from the root and boot cold against a store one directory away; the
parent chain answers "which reactor is this module part of" and gives the same answer either
way. The segment is the root directory's leaf name plus a hash of its absolute normalized path,
so it is filesystem-safe, collision-free and legible in a directory listing when a user goes
looking for what is filling their cache.

Resolving the workspace is the mojo's job and stays in the mojo, which is the layering the
stamp argument below does not disturb: the reactor root is Maven knowledge and
`graphitron-model` has no access to it, while the stamp is `graphitron-model`'s own and no
caller's. So `resolveStoreDirectory` returns `<cache>/graphitron/model/<workspace-segment>/`,
the store appends `<ddl-hash>-<version>/` to whatever home it is handed, and "storeDirectory
means the store's home at every layer that passes it" stays true verbatim. A consumer pinning
`<storeDirectory>` gets no workspace segment and needs none, since the path they chose is
already scoped to whatever they meant it to be scoped to; that is the escape hatch for a
consumer who deliberately wants two checkouts sharing one store, and it is the same parameter
they would reach for to keep them apart.

The directory name carries the store's compatibility stamp: the DDL hash and generator
version that `store_stamp` records move into the path, so a generator upgrade or DDL change
opens a **different file** rather than meeting a shared store other modules' builds are still
warm on, and mid-upgrade reactors (two modules on two graphitron versions) coexist in two
files instead of thrashing one. `store_stamp` stays inside the file as the integrity check for
a hand-moved or hand-corrupted store. The stamp in the path is load-bearing rather than
convenient once the no-discard rule below lands: a shared store is never deleted to make room
for a new schema, so without a per-stamp path a DDL edit would leave every module reading a
file none of them can use and no run will replace, and warm-start would be dead until someone
cleared the cache by hand. Putting the stamp in the path is what makes never discarding safe.

**`GraphitronModelStore` appends that stamp segment, not its callers**, and the placement is
load-bearing rather than tidy. A caller-computed segment would have to be reproduced
byte-identically by every opener in every process (the build mojos, `graphitron:dev`'s reader,
the LSP, MCP), and an opener that computed it even slightly differently would not fail: it
would look in the wrong directory, find nothing, and silently boot cold against a warm store
sitting one directory away. The hash is also not the callers' to compute, since `ddlHash()`
and `generatorVersion()` are private to `GraphitronModelStore` and belong to the module that
owns the DDL; exposing them so that five call sites could each rebuild the same path would be
publishing an invariant instead of enforcing one. So `storeDirectory` means the store's
*home* at every layer that passes it, the store resolves `<home>/<ddl-hash>-<version>/`
itself, and a consumer pinning `<storeDirectory>` gets the stamped subdirectory under their
chosen home too, which is what makes a pinned store survive a generator upgrade for the same
reason the default one does.

The store does have to *report* where it landed, and the distinction between reporting and
publishing is what keeps that from reopening the argument. `GraphitronModelStore` gains an
instance `location()` returning the directory this store actually opened in, empty for the
in-memory shape. That publishes no invariant: it answers "where did you open" after the fact
rather than letting a caller rebuild the path before it, so the failure mode the private hash
prevents (an opener computing the segment slightly differently and booting cold beside a warm
store) is still unreachable. It is also not a convenience. Without it no test can address the
database file at all, since the stamped segment is exactly what a test cannot name, and the
persistence tier's cases plant a corrupt file and assert a file's existence by path today.

Sharing a file between module builds makes concurrent access the norm, not the edge: `mvnd`
builds reactor modules in parallel by default. The store opens in H2's mixed mode
(`AUTO_SERVER`), where the first process holds the file and later processes attach to it
transparently; the shared families (`store_source`, `sql_`, `jvm_`) are written as
idempotent per-source merge transactions so two builds crawling the same new jar
concurrently both land, last writer winning on content the stamps make identical.

One property of mixed mode that the never-fail rule would otherwise be silent about, measured on
the pinned 2.4.240 rather than assumed: an attached second process **survives the first process
closing its connection mid-write**, and both processes' rows end up in the file. That matters
because the fallback this item specifies is an open-time fallback, so a handle that died halfway
through a load because the server-holding build finished first would be a way for cache trouble
to cost correctness after all, and the mechanism does not have that shape. The forked-JVM case
in the verification section is where the property is pinned rather than left as a measurement.

Mixed mode is **not one added URL parameter**, and the two conflicts are stated here because
an implementer would otherwise meet them as a build failure. H2 rejects `AUTO_SERVER=TRUE`
outright in combination with two flags `GraphitronModelStore.fileUrl` builds today, and both
refusals are checked against the pinned 2.4.240 rather than inferred from the manual: with
`DB_CLOSE_ON_EXIT=FALSE`, which `fileUrl` appends unconditionally, and with
`ACCESS_MODE_DATA=r`, which it appends for the read path. Each throws
`Feature not supported`. So the file URL drops `DB_CLOSE_ON_EXIT=FALSE`, which costs nothing
this store relies on: that flag suppresses H2's shutdown hook, and the only store that needs
its database to outlive a handle is the in-memory one, which is held open by
`DB_CLOSE_DELAY=-1` on a different URL and shut down explicitly. A file-backed store is meant
to be left on disk, so letting H2 close it at JVM exit is what it wanted anyway, and the
SHUTDOWN discipline `close()` documents is unchanged because it never applied to the file
case. The read path's flag is settled by deleting the read path, below.

Mixed mode reverses a decision the code already records, so the reversal is argued rather
than assumed. `GraphitronModelStore.openReadOnly` rejected the server approach in as many
words, on the grounds that it turns a build artefact into a running service every reader has
to opt into, and took a copy-to-temp snapshot instead. That reasoning was right for the
store it was written against: one file per module, one writer, and a reader (`graphitron:dev`
beside `mvn install`) that wanted a fixed snapshot anyway. A store shared by every module of a
workspace changes the premise under it. Concurrent writers become the normal case rather than
the collision to survive, and the current answer to a held file, `GraphitronModelStore.openAt`
falling back to the in-memory store, would hand every module but the first in a parallel
reactor build a cold load: the shared store would cost warmth in precisely the configuration
it exists to warm. The "running service" objection also weakens once the host process is a
build the user started rather than a daemon they have to adopt.

Two existing seams move with that reversal, and this item owns both rather than leaving them
to contradict the code. **`openReadOnly` is deleted, not converted to an attach.** Converting
it was the obvious move and it does not work: H2 refuses `AUTO_SERVER=TRUE` together with
`ACCESS_MODE_DATA=r`, so an attaching reader cannot keep the read-only enforcement that is the
only thing the method adds over `openAt`, and an attach that quietly drops the flag would be a
method whose name promises a guarantee its URL no longer carries. Deleting it is the better
answer anyway, on this item's own reasoning: mixed mode makes a copy-to-temp snapshot
redundant, no production caller exists (its only callers are two assertions in
`PersistentStoreTest`, which go with it), and a reader wanting a fixed view now has a
transaction on an attached connection, which is where that requirement belongs once a consumer
actually has it. Keeping a read-only entry point alive so it could be argued about later is
how a store acquires two ways to be opened and one rationale that fits neither. And `openAt`'s
in-use branch becomes unreachable, because the second opener attaches instead of failing: that
branch and its `isAlreadyOpen` helper are deleted, not left as dead code documenting a lock the
store no longer takes.

Deleting that classifier forces the third decision, and it goes the other way from today's:
a shared store is **never discarded**. `openAt` currently deletes an existing file it cannot
open, which is right for a module-local cache, where the blast radius is one module's warmth
and a killed build's half-written file should not outlive it. Under one file shared by every
module the same act destroys every graph's partition, including the sibling SDL rows the
cross-graph read surface reads and the baselines the freshness check treats as authoritative,
on the strength of one local process failing one open. With `isAlreadyOpen` gone there is no
longer anything separating "someone else holds it" from "it is corrupt", so the safe rule is
the unconditional one: any failure to open or attach to the shared store (no resolvable home,
read-only location, H2 server trouble, a file H2 refuses for reasons it will not name) falls
back to the module-local in-memory store and **leaves the file alone**. `discard` is deleted
along with `isAlreadyOpen`, since no failure deletes anything any more and nothing else calls
it, and cache trouble costs warmth, never correctness, and never fails a build. What that gives
up is self-healing: a store corrupt in a way H2 refuses now costs every module *of that
workspace* its warmth until a generator upgrade or a DDL edit moves the path, or someone deletes
the directory. Workspace scoping is what keeps that blast radius from being every project on the
machine, which is the second reason it is worth the segment. That is the residue the
recovery-story paragraph above already accepted, on the same remedy, and the eviction surface
this item defers is where a named command for it
belongs. Tests never touch the real user cache throughout: they inject temp directories
through the same `storeDirectory` seam they use today, which now means a temp *home* under
which the store makes its own stamped subdirectory.

Six comments record the decisions this section supersedes and are rewritten with them, listed
because the comment gate checks that a comment exists and not that it is still true (the
reversals elsewhere in this item name their own). `resolveStoreDirectory` names `mvn clean` as
the recovery story. `GraphitronModelStore`'s class javadoc places `openAt`'s file "under the
build directory" and calls deletability "the only property a `target/` artefact needs", both
false once the store lives with the user. `openAt`'s **own** method javadoc is the most
thoroughly falsified comment in the tree and is easy to miss precisely because the method is
being edited anyway: it promises "discarding it if there is one this build cannot read", walks
through "a database H2 refuses outright, is deleted and rebuilt", and closes on the
`graphitron:dev`-beside-`mvn install` rationale for the in-use branch, which is the branch this
item deletes. The `store_stamp.generator_version` column comment offers "deleting the build
directory" as the remedy, which is now the cache directory. The DDL header's family rule calls
`store_` "the one family whose rows are not a transcription of anything outside", which stops
being true of a family holding a transcription of the pom's `<schemaInputs>`; the header gains
the discriminator instead of losing the rule, since the recipe is configuration the run held in
hand and not a reading of the consumer's schema, database or classpath, which is what the other
four prefixes are named for. And `openReadOnly`'s concurrency rationale goes with the method
rather than being rewritten.

The test tier carries the same hazard in a form the comment gate cannot see at all, because a
test whose name has become false still passes. Three sites, all in `PersistentStoreTest`. Its
class javadoc says an older schema, a half-written file and a file that is not a database "are
all discarded and rebuilt", which is the sentence this section reverses. `aStaleStampRebuilds`
and `anUnreadableFileRebuilds` asserted that discard by name; both keep asserting something
true (a cold store, no rows) while promising something false, so both are renamed to what they
now pin, that the store falls back and the file survives, and both gain the surviving-file
assertion through `location()` that gives the rename its content.

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
  `StoreRefresh.freshSources` starts seeing stamped `SCHEMA_FILE` rows in the `recorded` map it
  builds, and none of them can reach the fresh set, because the only candidates it ever tests
  are the names in `namedSources(extensions)`, the classpath census, which no schema-file path
  is a member of. So the SDL families still clear and rebuild whole within their graph. The
  precise reason matters more than the conclusion here, because `fresh` does more than seed the
  `jvm_` claims: it also gates the `store_source` claims and the `store_source` delete in
  `clear`, so "the fresh set only feeds `jvm_`" is the wrong invariant to lean on when the
  ownership-scoped delete below rewrites that same statement.
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
honest: the store is per-user, per-machine and per-workspace, other people's work arrives as
source changes via git, never as store writes, so parallel module builds meet in the file on a
full reactor
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
  partition dimension. `store_` is exempt from the requirement rather than free of the column,
  which is not the same thing and is worth stating in the gate's own comment: `store_graph` is
  keyed on `graph_name` and `store_graph_schema_input` and `store_graph_schema_extension` lead
  with it, while `store_source` and `store_stamp` carry neither, so the family is the one place
  the question is answered per relation rather than per prefix.
- **Every graph-keyed relation reaches `store_graph` by foreign key**, walked as a closure
  over `INFORMATION_SCHEMA` rather than compared against a list, with `store_graph` itself the
  one excluded row, since the anchor cannot reach itself by a foreign key and an unstated
  exclusion here would read as a gate that passes because it never ran. A `graph_name` column with
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

## User documentation (first-client check)

Two new parameters and a default that writes outside the project tree is a user-visible surface,
so the manual rows are drafted here as the design's first client rather than written after the
code. If the second row cannot be written plainly, the default is wrong.

```asciidoc
| `graphName`
| `String`
| Name this module's graph carries in the fact store. Defaults to `${project.artifactId}`,
  which is unique within a reactor and is the right answer unless the subgraph's name differs
  from the module's. Set it when two modules would otherwise claim one name, or when the graph
  has a published subgraph name of its own.

| `storeDirectory`
| `File`
| Where the fact store is kept between runs, so a build starts from the previous run's rows
  instead of re-reading every jar on the classpath. Defaults to a per-user cache location
  (`$XDG_CACHE_HOME/graphitron/model/` or `~/.cache/graphitron/model/` on Linux,
  `~/Library/Caches/` on macOS, `%LOCALAPPDATA%` on Windows), with one store per reactor shared
  by that reactor's modules. Set it, or the `graphitron.store.directory` property, to keep the
  store inside the build instead: a hermetic CI job that wants nothing written outside the
  workspace, or a container that discards `$HOME`. The store holds no state of record and is
  rebuilt from your sources whenever it cannot be read, so deleting it is always safe and never
  loses anything; it is a cache, not build output, which is why `mvn clean` no longer removes it.
```

The last sentence is the one that had to be writable. It is, and it says the thing a consumer
actually needs to know: `mvn clean` stopped being the reset button, and the reason is that the
file is no longer build output.

## Verification

Full `mvn install -Plocal-db` green. The DDL edit follows the compiler through capture and
the tests; the widened gate family and the agreement anchors (`FactCaptureAgreementTest`) are
the honesty check, and the two-graph test above is the first assertion the multi-graph store
has ever had. The persistence tests (`PersistentStoreTest`, `WarmStartRefreshTest`) grow the
ownership cases: a second graph's partition survives a refresh, an uncrawled source's rows
survive a refresh, a schema file's recorded stamp matches a re-hash until the file is edited
and mismatches after, a file added under a remembered recipe's pattern is discovered by
re-expansion with no build of the owning module, a run meeting its own `graph_name` recorded
against a different `base_dir` writes nothing and leaves the recorded partition byte-identical,
and a graph's build identity and recipe rows are rewritten by its own run and untouched by a
sibling's. The re-expansion case is implementable there only because `SchemaRecipe.expand` lands
in `graphitron` rather than beside the mojo; a recipe relation whose expansion lived in the
plugin would have this assertion in a module that cannot see the store. Generated output is
untouched.

Two existing cases change what they pin rather than growing, and both go through `location()`,
which is the whole of that accessor's justification. `aStaleStampRebuilds` and
`anUnreadableFileRebuilds` address `store.mv.db` by literal path today and cannot once the store
names its own stamped subdirectory, so each opens the store, asks it where it landed, closes,
plants its damage there, and reopens: the store boots cold **and the damaged file is still on
disk afterwards**, which is the never-discard rule's only observable consequence and was
untestable before.

**Mixed mode needs a second process to be tested at all**, and saying so is what keeps the
suite from growing an anchor that passes before the change. Two handles opened in one JVM
already share one database with no `AUTO_SERVER` anywhere, which is exactly what
`aSecondOpenerLeavesTheStoreIntact` pins today; an in-process "concurrent opens land both
writers' rows" would therefore be green on trunk and would assert nothing about the mechanism
this item adopts. The property that is actually new is cross-process: a second *process*
attaches and writes instead of being handed the in-memory fallback. So `PersistentStoreTest`
gains a case that forks a JVM on the surefire classpath, has it open the same store directory
while the test holds it, and asserts both processes' rows are in the file afterwards.
`PersistentStoreTest`'s javadoc declined that machinery in as many words, as "a lot of
machinery for one branch", and this item reverses that judgement rather than ignoring it: the
branch it was weighed against is the branch being deleted here, and mixed mode is load-bearing
for the shared store rather than one arm of a fallback. The javadoc is rewritten with the case.

The reactor takes the default home for itself rather than pinning `<storeDirectory>`, which is a
decision and not an omission, and it is a sharper exercise under workspace scoping than it would
be under a machine-wide store: every graph in the file belongs to this reactor, so a
cross-partition effect cannot be blamed on somebody else's project. Every module build opens the
shared store the moment the change lands, so a full `-T 1C` reactor build is a real
concurrent-writer test of
mixed mode across genuinely different graphs, which is a harder exercise than any fixture can
stage. It exercises rather than asserts, which is the division of labour with the forked case
above: that one pins the property, this one runs it at a scale no fixture reaches. Two
properties keep that from leaking into what the build asserts. CI caches `~/.m2`
and not `~/.cache`, so every CI run starts from a cold store and no result depends on a
previous run's rows; and the store is a cache with no state of record, so a warm run and a
cold run agree by the invariant the agreement anchors already pin. If the shared store ever
does make a reactor build non-reproducible, that is the store failing its own contract and
the anchors are where it should surface, not something pinning the directory should hide.


## Retired vocabulary

Terms the Done-gate retirement sweep greps for. Each names something this item removes outright,
so a surviving mention is prose describing a mechanism that no longer exists.

- `openReadOnly`, and the read-only-snapshot mechanism it names: "snapshot", "copy-to-temp",
  "private copy", and the `ACCESS_MODE_DATA=r` read path. Deleted, not converted; mixed mode
  makes a snapshot redundant and H2 refuses the flag combination outright.
- `isAlreadyOpen`, and the in-use classifier's vocabulary: `DATABASE_ALREADY_OPEN_1`, "another
  process holds the database", and the `Attempt.inUse` distinction. A second opener attaches, so
  the branch and the taxonomy behind it both go.
- `discard`, and the discard-and-rebuild story around it: "discarded and rebuilt", "deleted and
  rebuilt", "is always correct and never loses anything" said of deleting the build directory.
  A shared store is never discarded.
- `mvn clean` as the store's recovery story, and "the build directory" as the place the store
  lives: "under the build directory", "a `target/` artefact", "deleting the build directory".
  The store lives in the user's cache.
- "rewrite-core stays filesystem-agnostic", as a claim about where schema-file expansion may
  live. Core re-hashes and re-globs now, and `SchemaRecipe` is where the dialect lives.
