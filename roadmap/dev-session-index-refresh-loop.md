---
id: R916
title: "The dev session index and refresh loop re-reads only what changed"
status: Ready
bucket: dx
priority: 2
theme: tooling
depends-on: []
created: 2026-09-03
last-updated: 2026-09-04
---
# The dev session index and refresh loop re-reads only what changed

## Goal

A dev session's round costs work proportional to what the developer changed. Today every round, on
either the schema cadence or the classpath cadence, re-reads and re-parses the whole compile
classpath before it does anything else. When this lands, a `.graphqls` save re-parses nothing, a
one-file recompile re-parses one file, and the per-round cost of the census tracks the edit rather
than the size of the workspace.

## What a round costs today

Both watch handlers run the same whole-workspace pass. `DevMojo.regeneratePass` (a schema save) and
`DevMojo.rebuildCatalog` (a `.class` change) each construct a generator and run
`GraphQLRewriteGenerator.runPipeline`, which opens with two reads that a schema save cannot have
invalidated. Both are hoisted so a pass pays for each exactly once, but a pass happens per round.

They are not the same kind of read, and the difference is what this plan turns on. The class census,
from `CatalogBuilder.buildExternalReferences`, is a genuine whole-classpath read: `ClasspathScanner`
opens every classfile in every non-`TRANSITIVE` entry, `Files.readAllBytes` per file for a directory
root and every `ZipEntry` for a jar. The jOOQ catalog is not. It is a single
`Class.forName(jooqPackage + ".DefaultCatalog", true, codegenLoader)`, and its cost is the static
initializer cascade that one load triggers: `DefaultCatalog.<clinit>` constructs the schema, whose
`<clinit>` constructs every generated table class in turn. That cost scales with the number of
generated tables, not with the size of the classpath, and `AbstractRewriteMojo.withCodegenScope`
pays it fresh every round because it builds a new `URLClassLoader` per round and closes it at the
end.

The store's existing retention does not reach that scan.
`StoreRefresh.prepare` computes the sources whose content hash still matches
`store_source.stamp` and pre-claims their classes so their rows are not re-inserted, which saves the
writes and not the reads: capture walks exactly as it would cold, and the rows it would have
re-inserted are dropped as duplicates. The knowledge also arrives too late to help, being computed
inside capture, after the scan has already run.

Measured on this repo:

| Measurement | Result |
|---|---|
| Census over a 25-entry, 28.2 MB classpath | 5,032 classes, 53,279 methods |
| Census, first round (cold JIT) | 1,348 ms |
| Census, rounds 2 to 5, nothing changed between them | 383 to 476 ms |
| Catalog load, first round (cold JIT) | 324 ms |
| Catalog load, rounds 2 to 6, fresh loader each round | 36 to 46 ms, 69 tables |
| Stat-only walk of 1,421 reactor `.class` files | 2.9 ms |
| Read-all walk of the same files | 12.0 ms, before any parsing |
| Content hash of the same 28.2 MB jar set | 55.2 ms |

The census figure is an upper bound for a classpath that size: the harness presented every entry as
project-origin, while a real scan skips `TRANSITIVE` entries before opening them. The catalog rows
come from a harness that rebuilds and closes a `URLClassLoader` per round, as `withCodegenScope`
does, over this repo's `no.sikt.graphitron.rewrite.test.jooq` fixture; a consumer with more generated
tables pays proportionally more, and no consumer-scale measurement was taken.

Two things follow. The census is where the round's time goes, at roughly ten times the catalog load
even on a fixture whose table count is modest, which is why the plan below addresses the census and
leaves the catalog alone. And the last three rows are what the census half trades on: verifying a jar
by hashing it costs about an eighth of parsing it, and verifying a directory by stat costs about a
quarter of merely reading it, before any parsing at all.

## The three populations

The classpath is not one thing. Its parts differ in how much they cost to read and in how often they
change, and the two run opposite to each other.

| Population | Share of bytes | Changes when | Detector |
|---|---|---|---|
| Release jars | most | never within a session | content hash |
| Snapshot jars | some | a sibling project is installed from another checkout | content hash |
| Reactor `target/classes` | little | every compile, so every `.java` save under a live dev loop | per-file size and modification time |

The design already half encodes this. `DevMojo.resolveClasspathRoots` filters the watch set to
directories, so jars are deliberately not watched at all, and `ClasspathSources` leaves a directory
root unstamped because it "changes on every compile, so hashing it would buy an invalidation that
always fires while paying for a full walk to decide that". What follows is that the population
treated as frozen is the one being re-parsed every round, and the population that genuinely churns is
cheap.

For a directory, modification time is not one detector among several, it is the only one that saves
anything: a hash requires the read the round is trying to avoid, while a stat does not. Per-file
grain is also what keeps the invalidation honest, firing for the files the compiler rewrote instead
of for the directory as a whole.

## Plan

### 1. Hold the census across rounds, in memory

The dev session is a long-lived JVM, so the census does not need re-deriving from anywhere. Keep it,
and invalidate it per entry: hash a jar to confirm it is unchanged, stat a directory's files to find
the ones the compiler rewrote, re-parse only those, and drop the entries whose files are gone.

The census only. The jOOQ catalog keeps being loaded fresh every round, and that is a decision rather
than an omission: it cannot be held across rounds as the code stands, and it is not where the time
goes. `JooqCatalog` is loader-bound by construction. It takes the `codegenLoader`, keeps it as a
field, and resolves `Catalog` and every `Table` handle through it, with `keysClass`, `tablesClass`
and `findRecordClass` calling `Class.forName` against it on demand; `allTableEntries` states the rule
for callers, that they "must consume them within the same build pass and never retain them past the
codegen loader's lifetime". That lifetime is one round, because `withCodegenScope` opens the
`URLClassLoader` in a try-with-resources and closes it deliberately, "to release JAR file
descriptors, which matters for the dev-mode loop that rebuilds the loader on every regeneration
cycle". A retained catalog would therefore be reaching into a closed loader on the first `.graphqls`
save, and would fail rather than degrade.

Making the catalog reusable is a real piece of work with two possible shapes, and this item takes
neither. Scoping the loader to the session and invalidating it on the classpath signal would trade
the file descriptors back and raise a class-identity question the census does not have. Turning what
the pipeline needs into loader-free data, the shape `columnFactsOf` already uses for values
documented as "resolved-immutable, safe to retain past the codegen loader's lifetime", is the
tree's own answer to this problem and is larger than the rest of phase one put together. What rules
both out for now is the measurement rather than the difficulty: the catalog load is 36 to 46 ms
against the census's 383 to 476 ms, so removing it entirely would buy about a tenth of what phase one
buys, at several times the cost and risk. If a consumer with many more generated tables shifts that
ratio, the loader-free-data shape is the arm to reach for, and it belongs in its own item.

This is where the round's cost goes, and it needs no schema change. `store_source`'s own rationale
sets the terms: the `(path, size, last-modified)` triple is "a heuristic, tolerable while a wrong
answer dies with the JVM and not tolerable once it survives a build". An in-process census is that
case, so modification time is the right detector here and a content hash remains the right one for
anything persisted.

The substantive argument matters more than the quote, because a dev session can outlive many builds.
The failure the triple admits is a file whose size and modification time are unchanged while its
content differs, which is what a restored cache or a normalising image layer produces. A compiler
writing into `target/classes` does not: it rewrites the file, and the timestamp moves. The cases
that do move class output underneath a session, a `mvn clean` or a rebuild after a branch switch,
change the timestamp too, which is the safe direction and costs a re-parse rather than a wrong
answer. Jars keep the content hash for exactly the reason the rationale gives, and the measurement
above shows that costs little.

The census is safe to hold for the reason the catalog is not. `CompletionData.ExternalReference` and
its children are strings, booleans and lists throughout, with no `Class` or loader reference
anywhere in them, so a census outliving the loader that produced it carries none of the
class-identity hazard, and the loader keeps being rebuilt per round without the census caring.

It lives on `DevMojo`, as a field, handed to the generator per round as a constructor port. That is
where `sessionCapture` already lives, with javadoc describing it as "the caller's to open, share
between passes and close", so the shape is established rather than invented here. `RunContext` is
not an option: it is documented as per-invocation and "never held in a static or `ThreadLocal`".

### 2. Give the persisted classpath facts per-file grain

A cold `graphitron:generate` has no in-memory census to keep, and its equivalent saving is to read
unchanged sources from the store rather than parse them. That needs the `jvm_` family to retain at
file grain instead of wholesale by classpath entry.

The store already has the shape. `java_file` is keyed on the file path, carries the stamp its rows
were read at, and is described as "the family's partition dimension and the grain its refresh runs
at"; `JavaSourceFacts` walks, rewrites what changed, and prunes the files the walk did not see
because they were deleted or renamed, scoped to the roots it covered. A relation for classfiles modelled on it, `jvm_file` say, would give
directory sources the same retention; no such relation exists today. Keying on the file rather than the class name is
what keeps it correct across the scan's nested-class exclusion, since a dropped `Foo$Bar.class` has
no `jvm_class` row to key on.

Whether this phase is worth building is an open question below, not a settled part of the plan.

### 3. Report the round's cost

Log per round what was re-parsed and what was reused, by population. Without it, a regression in the
invalidation is invisible: the loop still produces correct output, just slowly, which is the failure
mode this item exists to remove.

## Delivered

Phases one and three shipped. Phase two stays out of scope, returning through Spec if it is ever
measured to be worth building.

`ClasspathCensus` is the held census: one instance per session, keyed per classpath entry, handed to
each round's generator as a constructor port beside `CapturePort`. Jars are verified by content hash
through `ClasspathSources.hash`, the same function the store's persisted stamps use; directories are
verified per file by size and modification time, and only the files whose stamp moved are re-parsed.
An entry that leaves the classpath leaves the cache, and a directory's entry is rebuilt from each
walk rather than merged into the previous one.

`ClasspathScanner` was refactored so the cold and incremental paths cannot diverge: `scanEntry`
reads one entry without deduplicating, `readClassFile` reads one classfile, and `compose` does the
FQN deduplication that `scan` used to do inline. `scan` is now `scanEntry` over the entries plus
`compose`, so both callers share one parse and one deduplication rather than keeping two in step by
hand.

`DevMojo` holds the session census in `sessionCensus` and routes all three generator construction
sites through one `generatorFor(ctx)`, because a site that constructed a generator directly would
quietly get a census of its own and re-parse the whole classpath. Phase three is
`ClasspathCensus.Round`, reported by the census and logged by `DevMojo` once per round: the count
that matters is what was *not* re-read, since a regression here produces correct output slowly and
is otherwise invisible.

Measured on this repo, 13 entries and 3,421 classes, against the cold scan the loop paid before:

[cols="1,1"]
|===
| Round | Cost

| Cold scan, steady state over five rounds
| 279 to 359 ms, every class file re-parsed every round

| Held census, rounds two to five, nothing changed
| 60 to 86 ms, nothing re-read

| Held census, one class file touched
| 63 ms, 1 of 2,717 class files re-parsed, 0 of 11 jars
|===

That is the Verification's pass: a round that changes nothing re-parses nothing, and a one-file
recompile re-parses one file. What remains per round is the jar hashing and the directory stat walk,
which is the floor this design chose. The jOOQ catalog's 36 to 46 ms is untouched and still paid, as
the plan says it is.

Seven cases in `ClasspathCensusTest` pin it, each pairing a claim about the work with an equality
check against a freshly scanned census, because the risk here is a stale census rather than a slow
one. Four mutations were run against them: a directory that ignores the file stamp and a jar reused
regardless of its hash are both caught, as is a jar never reused. The fourth, merging a directory's
new file map into the old instead of rebuilding it, is not caught, and that is correct rather than a
gap: the census is accumulated from the walk rather than read back out of the cache, so a merge
costs cache growth and a wider blind spot on a deleted-then-recreated file, not a wrong answer. The
comment there says so instead of claiming the correctness it does not carry.

## What this does not address

Phase one needs a JVM that survives between rounds, which is `graphitron:dev`. A
`graphitron:generate` under `quarkus:dev` runs per start in a fresh JVM, with no previous round to
reuse, and gets nothing from it. That path is phase two's, and phase two is the conditional part of
this plan. So the item as scoped improves the editing loop and leaves the start-up cost that issue
544 measures where it is; anyone reading this expecting the reported minute to go away should read
that expectation against phase two's open question rather than against phase one.

## What Ready covers

Ready authorises phases one and three: hold the census across rounds with per-population
invalidation, and report per round what was reused and what was re-read. They stand together because
phase three is what keeps phase one's invalidation honest, and neither touches the schema.

The jOOQ catalog is explicitly not authorised. It keeps loading fresh every round, for the reasons
phase one gives, and the round after this lands still pays the 36 to 46 ms that load costs.

Phase two returns through Spec once phase one has shipped and been measured. Whether reading a
census out of the store beats parsing it is unanswered, phase one changes what a cold run would even
need from the store, and the relation it would add is a schema change that should not be authorised
on an unmeasured premise.

## Verification

Round-over-round timing in a live dev session: a first round, then a `.graphqls` save, then a
one-file recompile. The pass is a schema save that re-parses no classfiles and a one-file recompile
that re-parses one, with the census identical to what a cold round produces. That last part is the
one that matters, since the risk here is a stale census rather than a slow one, and it wants an
equality check against a freshly scanned census rather than a timing assertion alone.

## Open questions

* Whether reading a census from the store beats parsing the jars, which decides whether phase two is
  worth building. Reading rows back is not obviously cheaper than parsing classfiles, and R914
  measured 193,863 rows across 151 tables in a single whole store, so the `jvm_` share alone is
  substantial. Measure before committing to the phase.
* What a restored build cache does to modification times under `target/classes`. In-process use is
  covered by the carve-out above, but the answer decides whether phase two's persisted grain can
  ever read a stat rather than a hash.
* Whether the two processes in one checkout that R914 describes can share a warm census, or whether
  each keeps its own.

## Self-review

Not a gate review: the reviewer rule requires a different party, and this records what the author
checked before asking for one.

Verified against the tree: `DevMojo.regeneratePass` and `DevMojo.rebuildCatalog` both reach
`GraphQLRewriteGenerator.runPipeline`; the two whole-classpath reads open that method;
`ClasspathScanner.scanDirectory` reads every classfile with `Files.readAllBytes` and the scan skips
`TRANSITIVE` entries before opening them; `DevMojo.resolveClasspathRoots` keeps only directories;
`StoreRefresh.prepare` computes fresh sources and pre-claims their classes; `java_file` and
`JavaSourceFacts` carry the per-file grain and the prune this plan borrows.

Two claims were checked because the plan fails without them. A `.graphqls` save does not invalidate
the census: the incremental compile writes to `target/graphitron-classes`, which
`resolveCompileClasspath` does not include, so no byte the census reads changes. And holding the
census across rounds carries no class-identity hazard: `CompletionData.ExternalReference` and its
children are strings, booleans and lists, with no `Class` or loader reference anywhere in them.

Three findings, all folded in above. The jar half of phase one rested on a javadoc assertion that
hashing is cheaper than parsing, which is now measured at 55.2 ms against 383 to 476 ms. The item
did not say what Ready authorised, which is the shape a reviewer withheld on in R914. And the item
did not say that phase one leaves issue 544's reported start-up cost untouched, which is the
scoping a consumer would most want stated.

## Relationship to R914

R914 bounds the fact store: it stops the file growing without limit and stops a large store stalling
the run that opens it. This item is about the loop that fills the store, and the two meet at phase
two, which would put more load on exactly the store read path R914 found stalling. Phase one is
independent of both.

## Reviewer findings

### Round 1, Spec -> Ready declined

**Question 2, architecture fit. Phase one instructs the implementer to hold the jOOQ catalog across
rounds while rebuilding the codegen loader per round, and the tree says those two cannot both
happen.** Phase one is what Ready authorises, so this is not a detail the implementer can defer.

`JooqCatalog` is loader-bound by construction and says so. Its constructor takes the
`codegenLoader`, keeps it as a field, and resolves `Catalog` and every `Table` handle through it;
`allTableEntries` states the rule for callers: "callers must consume them within the same build pass
and never retain them past the codegen loader's lifetime". `keysClass`, `tablesClass` and
`findRecordClass` call `Class.forName(..., codegenLoader)` on demand, so a retained catalog keeps
calling a loader it was told not to outlive.

The lifetime is shorter than "per round" suggests. `AbstractRewriteMojo.withCodegenScope` opens the
`URLClassLoader` in a try-with-resources and closes it at the end of the round, deliberately: "the
loader closed to release JAR file descriptors, which matters for the dev-mode loop that rebuilds the
loader on every regeneration cycle". A catalog held into round two therefore holds handles from a
closed loader, and its next reflective resolve fails rather than degrades. This is a concrete
failure on the first `.graphqls` save, not a hazard to keep an eye on.

So the sentence "The jOOQ catalog load takes the same treatment on the same signal" has no reading
that survives the sentence three paragraphs later, "The loader keeps being rebuilt per round". The
plan forecloses the one option that would make the catalog half work, which is scoping the loader to
the session on the same invalidation signal, and it does so without noting that it is a choice.

The item is also internally split on whether the catalog is in scope at all. Plan section one
includes it. "What Ready covers" restates phase one as "hold the census across rounds with
per-population invalidation" and does not mention it. Open question two asks "whether the jOOQ
catalog can be invalidated on the same signal as the census, or whether its reflective load has to
follow the classloader's lifecycle instead", which reads as unsettled. Three sections, three
positions.

What would satisfy this finding: settle the fork in the plan body, and make the three sections say
the same thing. Any of the arms is a legitimate answer, and each is a different piece of work worth
naming as such:

* Drop the catalog from phase one and reload it per round. Then say what share of the round that
  leaves standing, because "What a round costs today" names two whole-classpath reads and the
  measurement table times only one of them. Today's numbers cannot tell a reader whether a save
  that re-parses no classfiles still pays most of its 383 to 476 ms.
* Scope the loader to the session and invalidate it on the classpath signal. That contradicts the
  current text and brings its own class-identity question, which the item would then have to argue
  rather than set aside.
* Turn what the pipeline needs from the catalog into loader-free data, the way `columnFactsOf`
  already produces values documented as "resolved-immutable, safe to retain past the codegen
  loader's lifetime". That is the shape the tree already uses for this exact problem, and it is a
  larger change than the rest of phase one put together.

Question 1 is otherwise answered. What changes for a consumer is legible from the goal alone: a
developer in `graphitron:dev` stops paying a whole-classpath re-parse on every save, a `.graphqls`
save re-parses no classfiles, a one-file recompile re-parses one, and the round's log says what was
reused. The item is also unusually clear about what it does not do, naming the `quarkus:dev`
start-up cost as untouched by the authorised phases.

Everything else checked out against the tree. `DevMojo.regeneratePass` and `DevMojo.rebuildCatalog`
both reach `GraphQLRewriteGenerator.runPipeline`, whose first two statements are the catalog load
and `CatalogBuilder.buildExternalReferences` under a comment naming them the two hoisted
whole-classpath reads. `ClasspathScanner.scan` skips `TRANSITIVE` entries before opening anything
and `scanDirectory` reads every classfile with `Files.readAllBytes`. `DevMojo.resolveClasspathRoots`
keeps only directories. `StoreRefresh.prepare` takes the already-scanned census as a parameter and
pre-claims the classes of hash-fresh sources, so it saves writes and not reads, and it cannot inform
a scan that has already run. Both quoted rationales are verbatim: `ClasspathSources` on the
unstamped directory root and on the triple being "tolerable while a wrong answer dies with the JVM",
and `java_file.file` on being "the family's partition dimension and the grain its refresh runs at".
`JavaSourceFacts` walks, rewrites by content hash, and prunes unseen files scoped to the roots it
covered. No `jvm_file` relation exists. `CompletionData.ExternalReference` is strings, booleans and
lists throughout, so the census carries no class identity. The incremental compile writes to
`target/graphitron-classes`, which `resolveCompileClasspath` does not assemble, so the claim that a
schema save cannot invalidate the census holds. R914's step four is where the shared-warm-census
open question points, as described.

Two non-blocking notes, neither bearing on the gate.

The plan does not say where the retained census lives, and `RunContext` is documented as
per-invocation and "never held in a static or ThreadLocal", so it cannot be the home. This did not
count against the gate because the tree already has the shape: `sessionCapture` is a `DevMojo` field
handed to the generator per round as a constructor port, whose javadoc describes it as "the caller's
to open, share between passes and close". An implementer has a precedent to copy.

"The `jvm_` tree runs to roughly 180,000 rows for a mid-size consumer" carries no source. R914
measures 193,863 rows across 151 tables for a whole store, which makes the figure plausible without
establishing it. It sits in an open question, and it argues against building the phase it belongs
to, so nothing rests on it.

### Round 2 (2026-09-04, In Review -> Ready, reviewer session 01HHPvdRQsaZKB66My2htu3W)

**Question 3, is this the change the spec approved. Phase three is wired into the schema cadence
only, so the cadence that actually re-reads is the one that says nothing about what it re-read.**

`ClasspathCensus.lastRound()` has exactly one caller: `DevMojo.runGeneratorPass`, which logs the
round after a `regenerate` pass. The classpath cadence does not go through it. `rebuildCatalog`
calls `generatorFor(ctx).buildOutput()` directly and logs "catalog refreshed (n tables, m scalars)"
and nothing else; `buildOutputQuietly`, the `skipInitial` startup arm, logs nothing either. So a
`.graphqls` save reports "nothing re-read", which is the round with nothing to report, and a
`.class` change reports no census line at all, which is the round where the invalidation does its
work and is the only round that can regress into re-parsing a population nothing touched.

The `LOGGER.debug` line in `GraphQLRewriteGenerator.runPipeline` is not that report. It fires on
every pass, including the classpath cadence, but at `DEBUG`, which a dev session does not show. A
developer watching `graphitron:dev` sees the census line on the rounds that reuse everything and
never on the rounds that re-read.

This bears on the gate rather than on taste because phase three is half of what Ready authorised,
and "What Ready covers" says why the two stand together: phase three "is what keeps phase one's
invalidation honest". Section 3 states the failure mode it exists to remove, a loop that "still
produces correct output, just slowly". The cadence left silent is precisely where that failure
would appear. The `Delivered` section records it as done, "reported by the census and logged by
`DevMojo` once per round", so approving as it stands would land a record in `Done` that overstates
what shipped.

**Question 4, what demonstrates completeness. Phase three has no test, and the comment that
advertises one names a test that does not exist.**

`DevMojo.sessionCensus` carries "Package-private so DevMojoTest can assert a round's reuse". No such
assertion exists: nothing under `graphitron-maven-plugin/src/test/` names `sessionCensus` or
`ClasspathCensus`. The idiom is load-bearing elsewhere in the same field block, `incrementalCompiler`
saying "Package-private so DevMojoTest can assert the opt-out leaves it unbuilt" against a real
assertion in `DevMojoTest`, so a reader takes the promise at face value. Nothing anywhere asserts
`Round.report()` either; `ClasspathCensusTest` calls it only inside AssertJ failure descriptions.

What would satisfy both findings, in the author's choice of shape:

* Report the round on the classpath cadence too, so the observable exists where the re-reading
  happens, and say in `Delivered` which sites report. If the author judges one cadence enough, say
  which one phase three covers and why the other does not need it; that is a defensible position
  but it is not the one the item currently states.
* Either write the assertion the `sessionCensus` comment promises, which would also pin the reuse
  end to end through the mojo rather than only through the census, or drop the promise from the
  comment.

Phase one is the change the spec approved, and the evidence for it is strong. The census is held on
`DevMojo` and handed over per round as a constructor port, which is the home and the shape the plan
named; jars take the content hash through `ClasspathSources.hash` and directories take per-file size
and modification time, which is the split the plan argued; an entry leaving the classpath leaves the
cache; the jOOQ catalog is not held, as the plan settled it. The `ClasspathScanner` refactor is
behaviour-preserving: deduplication moved out of `collect` into `compose` over per-entry lists, and
because entries stay in classpath order and files stay in walk order, the surviving copy of a
duplicated FQN is the same one. `ClasspathCensusTest`'s seven cases each pair a counter claim with
an equality check against a cold scan, which is what the Verification asked for and the right test
for a cache whose risk is staleness. They pass, as do `ClasspathScannerTest` and
`JarResidentClassCensusTest`.

One non-blocking note. `CatalogBuilder.censusRoots` spells `ClasspathEntry` fully qualified in both
its return type and its body although the class is imported at the top of the file; it is inherited
from the code it replaced and bears on nothing.

## Author response

### Round 1

Finding accepted, and the fork is settled the first way the findings name: the jOOQ catalog leaves
phase one and keeps loading fresh every round. The reviewer's reading of the code is right in every
particular, and re-checked here rather than taken on trust. `JooqCatalog` keeps the `codegenLoader`
as a field and resolves through it lazily, `allTableEntries` tells callers not to outlive it, and
`withCodegenScope` closes the loader in a try-with-resources at the end of the round. A held catalog
would fail on the first save, not degrade.

The findings ask what share of the round that leaves standing, on the grounds that the measurement
table timed one of the two reads and not the other. That was the right thing to ask, and the missing
number is now measured. A harness that rebuilds and closes a `URLClassLoader` per round, as
`withCodegenScope` does, loads this repo's 69-table fixture catalog in 324 ms cold and 36 to 46 ms
warm, against the census's 383 to 476 ms. The catalog is roughly a tenth of the pair, so phase one
still addresses the great majority of the round even with the catalog excluded, and the two arms that
would make the catalog reusable are named in the plan as the separate work they are rather than
folded in here.

Measuring it also corrected the item's own framing. "Two whole-classpath reads" was wrong about the
catalog: it is one `Class.forName` whose cost is the static-initializer cascade across the generated
table classes, so it scales with table count rather than classpath size. The stack trace confirms the
cascade directly, `DefaultCatalog.<clinit>` to the schema's `<clinit>` to every table's. "What a
round costs today" now says this, which also makes the exclusion legible where a reader meets the
cost rather than only where the plan justifies it.

The three sections that disagreed now say the same thing. Plan section one settles it and says why,
"What Ready covers" states that the catalog is explicitly not authorised and that the round still
pays for it, and the open question is gone because it is no longer open.

Both non-blocking notes are folded in. The plan now says the census lives on `DevMojo` as a field
handed over per round, citing `sessionCapture` as the precedent and `RunContext` as the ruled-out
option, which is the shape the note pointed at. The unsourced 180,000-row figure is removed; the open
question now cites R914's measured 193,863 rows across a whole store, which is the number that
actually exists, and states the `jvm_` share as substantial rather than putting a figure on it.

No change to phases two and three, to the verification, or to what the item declines to address.
