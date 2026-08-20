---
id: R760
title: "The assignability closure does not terminate on a real census"
status: Spec
bucket: architecture
priority: 3
theme: dev-loop
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# The assignability closure does not terminate on a real census

`intent_class_assignable` closes the declared supertype relation transitively, as a view, over the
whole classpath census. On a census that declares one class name from two classpath entries the
recursion does not return, because its `UNION ALL` replicates every path once per duplicate edge row
and the frontier doubles per hop. On a census with no duplicate names it does return, in 17 seconds,
which is the second half of the problem: seventeen seconds is what an all-pairs closure over 8,821
declared edges costs under a path-guarded recursive view, and nothing reads the relation.

**The verdict this spec lands: delete the view.** Reinstate a closure when a consumer exists,
anchored at the names that consumer asks about rather than as an all-pairs relation, and
materialized rather than viewed. The three alternatives (ship the rewrite the filing pass drafted,
write a better view, materialize the existing one) are each measured and rejected below. The edge
relation the view closes over stays for now and is R762's to settle, on the terms set out below.

## What changes when this lands

Nothing changes for a graphitron consumer, and that is the point worth stating plainly: the relation
has no production reader, so its cost and its non-termination are both latent. What changes is what
the next reader gets. Today the first code to read `intent_class_assignable` on a workspace store
holding two entries for one class name gets a build that hangs with no diagnostic, and the first
code to read it on any real census pays seventeen seconds for an answer it did not ask for. After
this item there is no such relation to read, the published schema reference no longer asserts three
things about it that are false, and the general rule in `docs/architecture/explanation/fact-model.adoc`
says what the measurements say rather than the opposite.

## The measurements

Two censuses, one sandbox (4 vCPU), H2 2.4.240 throughout. Wall clock is whole-process, so it
carries about 0.4 s of JVM start and store open.

**Census A**, the persisted workspace store a full reactor build leaves behind: 8,821 edge rows,
8,821 distinct `(class_name, supertype_name)` pairs, 153 classpath entries, 7,215 distinct subtype
names, 8,458 distinct names in all. No class name is declared by two entries, and no two names
declare each other.

| Form | Rows | Wall |
|---|---|---|
| the shipped view | 24,419 | 17 s |
| the rewrite drafted at filing (recurse over deduped names, re-attach `source_name`) | 24,419 | 27 s |
| the same rewrite anchored at the first hop rather than at the class name | not reached | cancelled after 2 min |
| an anchored reverse closure from three named target types | 20 | 1.2 s |

**Census B**, stated row by row in memory: a chain of depth *d*, every edge declared by two
classpath entries, which is the shape the filing pass modelled outside H2.

| Depth | Edge rows | The shipped view |
|---|---|---|
| 12 | 24 | 156 rows in 0.43 s |
| 14 | 28 | 210 rows in 0.61 s |
| 16 | 32 | 272 rows in 1.10 s |
| 18 | 36 | 342 rows in 3.18 s |
| 20 | 40 | no result in 15 s |
| 24 | 48 | no result in 20 s |

Two readings, and the second is the one that reshapes the item.

The cliff is the duplicate-doubling the filing pass diagnosed, and the diagnosis is correct: the
recursive term joins `ON s.class_name = r.supertype_name` and projects `r.source_name` unchanged, so
two edge rows differing only in `source_name` produce identical output rows, `UNION ALL` keeps both,
and both recurse. What is new is the scale at which it bites. **Forty stated rows reproduce the
hang.** The filing pass reached for a census-scale store to demonstrate this and did not need one:
the defect is a function of duplicate declarations and chain depth, both of which a test fixture
states in a loop. Census B at depth 24 is 48 rows, and the corrected recursion returns its 600 rows
in 0.44 s where the shipped form does not finish.

And the cost is not the duplicates. Census A holds no duplicate names at all, so deduplicating the
edge relation there is a no-op, and the shipped view still takes 17 seconds. What costs is the path
guard itself: it enumerates simple paths, and over 8,821 edges across 153 entries there are a great
many. This is exactly the claim the view's own comment makes and gets backwards ("the guard
enumerates simple paths, which over the acyclic shape a census actually holds costs what the
unguarded form costs"). On the acyclic shape a census actually holds, the guard costs seventeen
seconds.

## The drafted rewrite is not semantics-preserving

The filing pass proposed recursing over the deduplicated name graph and re-attaching `source_name`
afterwards, on the argument that "hops already join on the name alone, and `source_name` is only
ever carried from the base row". The first half is true and the second half is where it breaks: the
base row's entry is the entry that declared the *first* edge, and the rewrite re-attaches every
entry that declares *any* edge for that class name.

Stated as four rows, `E1` declaring `C -> A` and `A -> AA`, `E2` declaring `C -> B` and `B -> BB`:

| Form | Rows |
|---|---|
| the shipped view | `E1 C A`, `E1 C AA`, `E1 A AA`, `E2 C B`, `E2 C BB`, `E2 B BB` |
| the drafted rewrite | the six above, plus `E1 C B`, `E1 C BB`, `E2 C A`, `E2 C AA` |

Entry `E1`'s answer for `C` gains supertypes only `E2`'s copy of `C` declares. That is precisely
what `source_name`'s own column comment says the partition prevents ("the reason one workspace's
modules do not fold their hierarchies into each other's answers"), and it is observable through the
graph partition: a graph that read only `E1` would see a supertype no entry it read ever declared.

The reason the filing pass measured "identical result sets" is that neither census it measured can
show the difference. Census A declares no class name twice. The store the filing pass measured had
969 pairs declared by two entries, and two entries holding the same classfile declare the same edge
set, so the fold is invisible. It becomes visible the moment two entries disagree, which is a jar
beside a rebuilt `target/classes` of another version, and a workspace store that holds every entry
every graph ever read is where that meets.

Anchoring the re-attachment at the first hop instead of at the class name does restore the row set
(verified against a census combining `ClassAssignableTest`'s own fixture, the divergent-entry case
above, and a cycle back through the subtype: 25 rows each, both `EXCEPT` directions empty). It also
takes the query from 27 seconds to over two minutes on census A, because the anchored join puts
8,821 edge rows on the driving side of a recursive CTE that H2 re-evaluates rather than materializes.
So the corrected form is exact and unusable, and the fast form is inexact.

## No view form is both safe and cheap

Three properties are wanted: terminate on any census the store can hold, answer per classpath entry
as the column comments promise, and not cost seconds. Under H2 they do not co-exist in a view.

* **A recursive `UNION` will not do the deduplicating.** H2 does not deduplicate against rows
  earlier iterations produced, verified directly: the three-edge cyclic set `A -> B`, `B -> A`,
  `B -> C` closed with `UNION` does not return in 8 s. So a termination guard is unavoidable, and
  this one claim in the shipped comment holds. The schema's two reference-chain closures escape it
  a different way, on a monotone `position` rather than on a path (see the audit below).
* **A path guard terminates and costs.** Seventeen seconds on census A, and the cost is inherent to
  enumerating simple paths over a 153-entry census rather than to any duplicate row.
* **A depth bound trades one unbounded quantity for another.** Capping the recursion at the number
  of distinct names terminates, and under a cycle it runs to the cap producing up to cap times the
  pair count of intermediate rows, which is the same hazard with a larger constant.

Materializing is the shape the tree already has for a closure with no safe view form, and it is
available: `Materializations.refresh` refreshes a target with no graph column whole, so a
census-grained target needs no new partition shape, and `intent_type_domain` and
`intent_type_backing_class` are both closures held this way for exactly the stated reason. What
materializing does not do is make the closure cheap. It moves seventeen seconds onto the capture
cadence, which every build pays, for a relation with no reader. That is the trade this item
declines, and it is the one the build-wall-clock work is separately trying to claw back.

## What happens to `jvm_class_supertype`

Deleting the view leaves the edge relation with no consumer at all, which is the other half of the
question and R762's subject: 8,817 rows written on every capture, inside a `jvm_` family whose writes
are 8.1 seconds of a 13.3-second capture. Nothing here contradicts that. What this item does is
remove the ambiguity R762 is currently reasoning around, because today the population's status
depends on whether a broken view survives.

This item does not delete the capture, and the reason is scope rather than doubt. The view is a DDL
object with one test behind it; the capture is a scanner arm, a fact-sink writer, a capture-agreement
anchor and a row population, and R762 holds the measurements for exactly that trade across all nine
census relations. Splitting it the other way would put a capture-depth decision inside an item about
a recursive view.

The recommendation to carry into R762, stated so its Spec pass can accept or reject it rather than
re-derive it: drop the supertype capture with the rest of the depth cut. The one use of the rows that
is not a consumer is R685's evidence, counting reactor classes whose supertype resolves in a
third-party jar, and that analysis runs over any store captured before the cut. If a container test
does arrive later it wants the anchored closure described below, which needs the edges resident for
the target names it asks about and nothing else, so reinstating a narrow capture beside a narrow
closure is the shape either way.

## What a reinstated closure should look like

Guidance for whoever brings the first consumer, not work in this item.

The consumer the shipped comment names is a container test: is this class assignable to
`java.util.List`, `org.jooq.Result`, and a handful of others. That question does not want an
all-pairs closure. It wants the classes that reach a named target, which is a closure over the
reversed edges seeded from those targets, and over census A that is 20 rows in 1.2 s including
process start. Anchored at the targets it stays cheap as the census grows, because the census grows
sideways (more entries, more unrelated classes) and not upward through `java.util.List`.

Two constraints carry over into whatever is reinstated. Recurse over an edge relation deduplicated
to the pairs it denotes, never over the raw table, because duplicate rows under `UNION ALL` are the
doubling. And anchor the entry attribution at the first hop, because that is what the entry
partition means and re-attaching by class name folds two entries' hierarchies together.

## Implementation

* `graphitron-model/src/main/resources/no/sikt/graphitron/model/graphitron-model.sql`: delete
  `CREATE VIEW intent_class_assignable` and its four `COMMENT ON` statements. Amend the two
  cross-references to it in sibling comments, `intent_name_matched_key_pair`'s "exactly as
  intent_class_assignable leaves its own to store_graph_source" and
  `intent_type_backing_class`'s "which is the reading intent_class_assignable declined for the same
  reason", each to state its own rule or to cite a relation that still exists. Amend
  `jvm_class_supertype`'s comment, which currently reads as though a closure over it is live: the
  edges are a captured fact recorded ahead of the closure that will read them, which is the census's
  ordinary posture and worth saying rather than leaving the relation looking orphaned.
* The same file, `intent_authored_field_claim`: give its `lookup_bearing` recursion a deduplicated
  edge relation. Its recursive term projects `f.type_name` and joins on `f.named_type`, so an input
  object with two fields of one named type produces two identical rows, which is the same doubling
  under the same `UNION ALL`. Fold the `graphql_field` / `graphql_type` join into a
  `SELECT DISTINCT graph_name, type_name, named_type` CTE and recurse over that. The relation is
  consumed only through `EXISTS`, so deduplicating changes no answer.
* `graphitron/src/test/java/no/sikt/graphitron/rewrite/capture/FactCaptureAgreementTest.java`: drop
  the `intent_class_assignable` registration and the class-javadoc paragraph that names
  `ClassAssignableTest` as its anchor.
* Delete `graphitron-model/src/test/java/no/sikt/graphitron/model/intent/ClassAssignableTest.java`.
  Its seven cases pin a relation that no longer exists. Nothing in it needs relocating:
  `seedSupertype` and the census helpers stay for the scanner-side tests, and the cross-entry,
  chain-terminus and converging-chain shapes it states are the shapes the reinstatement guidance
  above hands to the next author.
* `docs/architecture/explanation/fact-model.adoc`: rewrite the recursive-view paragraph. Its general
  rule is right and is the reason the paragraph exists ("the argument for a recursive view is about
  the rows the relation can contain"), and its worked example is now a wrong verdict about a
  relation that is going away. Restate it on what was measured: deduplicate the edge relation rather
  than only guarding the path, H2's recursive `UNION` does not deduplicate against history so a
  guard is unavoidable, a path guard is not free at census scale, and the duplicate-doubling
  reproduces at forty stated rows.

## Tests

The deletion carries its own coverage, and one gate makes the documentation edit non-optional:
`SchemaIdentifierDriftCheck` scans the authored `.adoc` pages for backticked schema identifiers and
fails on one the booted store does not hold, so a forgotten `fact-model.adoc` mention fails the
build rather than shipping as a dangling reference. The in-SQL cross-references are not covered by
any gate, which is why they are enumerated above.

One test is added, for the `lookup_bearing` dedupe, in
`graphitron-model/src/test/java/no/sikt/graphitron/model/intent/AuthoredClaimTest.java`: a retired
lookup-key site seeded with `seedInputFieldLookupKey`, above a chain of input objects twenty deep
where each level declares two fields of the level below, asserting the claim rows the fixture
expects. Without the dedupe that fixture doubles per level and the test hangs; with it the answer is
immediate. It carries
`@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)`,
which is the mechanism and the reasoning `PersistentStoreTest.aHeldFileDemotesInsteadOfBlocking`
already uses: a mechanism that can hang has to fail this test instead of wedging the build.

A census-scale fixture is deliberately not added anywhere, which answers the question the filing
pass raised about `ClassAssignableTest` running three orders of magnitude below production. The
fixture scale was never the gap. Census B reproduces the hang at forty stated rows, so what the
fixture was missing was two shapes it could have stated all along: a class name declared by two
entries, and a chain deeper than about eighteen hops. Row-by-row censuses state both, and
`ClassAssignableTest`'s class javadoc argues correctly for stating them that way. No new tier, and
no scale harness for the `jvm_`-derived relations.

## The sibling recursions

The filing pass asked whether other recursive derivations share the shape. All five recursive terms
in the schema, audited:

* `intent_class_assignable`, this item's, deleted.
* `intent_authored_field_claim`'s `lookup_bearing`: the same shape, `UNION ALL` over a relation whose
  rows duplicate at the projection. Inert today because its base is `graphitron_field_lookup_key`,
  the retired input-field site, which is empty on an accepted schema, and bounded to one graph's
  input-object graph when it is not. Deduplicated in this item, since a known-doubling recursion left
  in place after the mechanism was diagnosed is how it gets rediscovered.
* `intent_field_reference_step_target` and `intent_argument_reference_step_target`: `UNION` with a
  strictly increasing `position`, bounded by the written path's own length. Safe, and safe for a
  reason worth naming: the bound is a column of the data rather than a guard over it.
* `intent_field_chain_terminus`'s `walk`: `UNION ALL`, but bounded the same way by a strictly
  increasing `seq` over the chain's tail. It can fan out multiplicatively where several candidate
  hops answer one position, and the depth is a written path so the exponent is small. Safe, and not
  the same hazard: the census closure's depth is the graph's own.
* `intent_type_domain` and `intent_type_backing_class` are closures over the cyclic SDL type graph
  and are not SQL recursions at all: both are materialized tables written by capture-cadence
  derivations. They were never exposed to this defect.

## Retired vocabulary

For the Done-gate retirement sweep. The relation name `intent_class_assignable` and the jOOQ
identifier `INTENT_CLASS_ASSIGNABLE` go entirely, along with the whole of the view's comment. These
claims in it are individually false and should not survive being paraphrased anywhere:

* "a general transitive relation is here because it is cheap over a census relation that had to
  exist anyway"
* "The guard enumerates simple paths, which over the acyclic shape a census actually holds costs
  what the unguarded form costs"
* "it removes a build that hangs with no diagnostic from the set of possible outcomes"
* "A view rather than a materialized relation, which is the one thing that distinguishes it from the
  schema's other closure"

And in `docs/architecture/explanation/fact-model.adoc`, the same claim in its other habitat: "which
is why that view carries a path guard costing nothing on the acyclic shape a census actually holds".

## How to re-measure

Every figure above is reproducible without a reactor build. Census A is whatever persisted store the
last full build left:

```bash
ls ~/.cache/graphitron/model/*/*/store.mv.db
# copy one aside, then drive it with H2's own script runner
cp <store>/store.mv.db /tmp/probe.mv.db
java -cp ~/.m2/repository/com/h2database/h2/2.4.240/h2-2.4.240.jar org.h2.tools.RunScript \
  -url "jdbc:h2:/tmp/probe;ACCESS_MODE_DATA=r" -script probe.sql -showResults
```

Set `SET QUERY_TIMEOUT <ms>` as the first statement of every script; without one the duplicated-census
queries do not come back. Census B needs no store at all: create `jvm_class_supertype` alone in an
in-memory database, insert a depth-*d* chain twice over under two `source_name` values, and run the
recursion with and without a `SELECT DISTINCT` over the edges. Both the doubling and the semantics
divergence show at single-digit row counts.
