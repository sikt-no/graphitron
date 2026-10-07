---
id: R998
title: "Four unindexed H2 statements cost the sis dev loop about a minute per run"
status: Spec
bucket: dx
priority: 1
theme: tooling
depends-on: []
created: 2026-10-07
last-updated: 2026-10-07
---

# Four unindexed H2 statements cost the sis dev loop about a minute per run

## Goal

`mvn graphitron:dev` on the sis schema takes 2m57s to start, and every schema save reruns the
same pipeline (capture, the 28 derivation stages, and the generator pass). About 65s of that is four
store statements that H2 runs as nested loops because no index serves the join. The goal is to
remove those 65s from startup and from every save, without changing any fact the store holds or
any diagnostic a consumer sees.

## Evidence

The evidence is a profile of sis at `b46296e19` on 2026-10-07: a timestamped Maven log, plus JFR
with a stack depth of 1024. 80% of main-thread samples end inside H2. Each fix below was timed as
a standalone statement in H2 2.4.240 against a copy of the sis fact store, which is the H2
database `graphitron:dev` keeps under `target/graphitron-model/`. All four costs have the same
shape: a join that no index serves, so H2 either compares every pair of rows or re-runs a derived
table once for every outer row.

## Plan

One commit per fix. They are independent of each other.

### 1. Index directive applications by source position

`GraphQLAstCapture.directiveApplicationArguments` joins `graphql_ast_applied_argument_entry`
(6,375 rows on sis) to `graphql_directive_application` (6,902 rows). It joins on the position of
the `@` sign: `(graph_name, source_name, source_line, source_column)`. Those columns are not a
prefix of the application's primary key `(graph_name, coordinate, directive_name, ordinal)`, so
nothing serves the join.

Add `graphql_directive_application_position_ix` on those four columns. Write it beside the
existing `*_ix` indexes, with a comment naming the reader. The source columns are nullable for
synthesised applications, which an index allows. Measured: 11.0s → 0.03s, same 6,375 rows. This
changes the DDL hash, so the store path moves, which is expected.

### 2. Stop re-running the ArgMappingCandidates carrier per row

`ArgMappingCandidates.carrier()` is a `UNION ALL` derived table with two arms: an argument at an
argument coordinate, and an input field at an input-field coordinate. `expand` left-joins it once
per depth, and `markContestedCarrierName` checks it with `EXISTS`. H2 re-runs the whole union for
each candidate row (6,212 at depth 0).

The fix has the two readers flatten the arms into the reader's own join tree, as one left-join
chain per arm:

* The argument arm is `graphql_argument_element` on `(graph_name, coordinate)`, which is that
  table's `UNIQUE` key, then `graphql_argument` on its primary key.
* The input-field arm is `graphql_field_element` on its `UNIQUE (graph_name, coordinate)`, then
  `graphql_type` gated on `INPUT_OBJECT`, then `graphql_field` reached through the gated type
  row, so an output-field coordinate gets no carrier.

Each reader takes the carrier's name and type as a `COALESCE` over the two arms.

The shape has to be a flat chain. Wrapping each arm in its own derived table, with no union, was
timed too, and it is as slow as the union (6.08s), because H2 does not seek into a derived table.

So the arm's join conditions, including the `INPUT_OBJECT` gate, are written once, in one Java
helper per arm. Both places that need the carrier call those helpers: the flat chain the two
readers use, and the union the two seeding statements keep. The seeds keep the union because each
of them reads it once. A carrier rule is then spelled once, even though it is read in two shapes.

At most one arm matches a coordinate. An argument coordinate ends in `(argument:)` and a field
coordinate does not. Today that is an observation, so `graphql_argument_element` gains
`CHECK (coordinate = type_name || '.' || field_name || '(' || argument_name || ':)')` beside the
field-grain checks in fix 3. That turns the arms' disjointness into a constraint. It holds for
530 of 530 rows on sis.

The fix adds no relation, following the lever order in `fact-model.adoc` § "Derived reads are
views, not stored facts". A rewrite comes before storing a rule, and storing is bought only when a
reader count demands it. Here the existing `UNIQUE` keys already serve every lookup the rewrite
makes, and the rewrite brings the cost close to zero, so a stored carrier table would buy nothing.

Measured on a copy of the sis store:

* `expand` at depth 0: 6.57s → 0.05s. The result is identical: 5,253 rows, 2,628 marked
  deprecated, none closing a cycle.
* `markContestedCarrierName`'s predicate: 2.5s → 0.06s, with the same zero matches.

The carrier is a value-carrying position rebuilt by unioning two subtype relations, and
`fact-model.adoc` would rank capturing it as a fact above every other lever. That belongs in a
separate item, not this one.

### 3. Key the hot upserts on the primary key

jOOQ 3.20 renders `onDuplicateKeyUpdate()` on H2 as `MERGE … ON (pk match OR unique match)` when
the target table carries a second `UNIQUE` key. No index serves the `OR`, so each source row scans
the target. Two hot sites:

* `EmittedAnchor.fields`, target `graphitron_field`. Measured 7.32s → 0.48s.
* `GraphQLAstCapture.fieldElements`, target `graphql_field_element`. Measured 4.90s → 0.16s.

Both tables carry `PRIMARY KEY (graph_name, type_name, field_name)` and
`UNIQUE (graph_name, coordinate)`. Switch both sites to `onConflict(<pk columns>).doUpdate()`.

**Behaviour change.** Today a row that collides with an existing row on `coordinate` alone, under
a different key, overwrites that row. After the change it raises a unique violation. That
collision cannot happen: in both tables the coordinate is the type name, a dot and the field name.
On sis that holds for 7,134 of 7,134 `graphql_field_element` rows and 8,436 of 8,436
`graphitron_field` rows.

Every writer spells the coordinate that way:

* `graphitron_field` has one writer, `EmittedAnchor.fields`. Its two source views,
  `graphitron_field_authored` and `graphitron_field_minted_candidate`, both concatenate the type
  name, a dot and the field name.
* `graphql_field_element` is written by `GraphQLAstCapture.fieldElements`, from coordinates
  `GraphQLAstEntries` spells as the parent's name, a dot and the field name. Test seeders write it
  through `SchemaCoordinateSyntax.ofField`, which spells it the same way.

GraphQL names cannot contain a dot, which makes the concatenation injective. So the `OR` arm is
dead, and the change removes a branch that could only ever fire on corrupt input.

To make that a constraint rather than an observation, both tables gain
`CHECK (coordinate = type_name || '.' || field_name)`. The `CHECK` makes the coordinate a function
of the key. If a writer ever breaks it, the store refuses the row at insert instead of silently
overwriting another row.

**General rule.** Ten tables in `graphitron-model.sql` carry a second `UNIQUE` key:
`graphitron_argument`, `graphitron_field`, `graphitron_field_chain_application`,
`graphitron_field_chain_link`, `graphitron_tabletype`, `graphql_argument_element`,
`graphql_directive_argument_element`, `graphql_field_element`, `graphql_type` and `meta_gatherer`.
Only upserts into those tables render the `OR`. The rest of the 179 `onDuplicateKeyUpdate` sites
render a plain primary-key `MERGE`. This item fixes the two sites the profile names. Converting the
remaining upserts into the other eight tables is a separate Backlog item, with a guard test as its
deliverable: an upsert into a table with a second unique key must name its conflict target. That
item exists for correctness as much as speed, and none of those sites shows in the sis profile.

### 4. Read the claim conflicts before reading the claims

`AuthoredClaimConflicts.typeClaims` joins the `intent_authored_claim_conflict` view to
`intent_authored_type_claim`. H2 drives the join from the claims and re-evaluates the conflict
view, which has a `GROUP BY`, once per claim. `fieldClaims` has the same shape against
`intent_authored_field_claim`. On sis the conflict view has **zero** rows. Reading the view alone
takes 0.16s; the joins as written take 4.6s (`typeClaims`) and 5.8s (`fieldClaims`), and both
return nothing. Both run in `AuthoredClaimRejectionRows.derive`, and `typeClaims` runs again from
`typeGrain` in the generator pass.

The fix takes the conflict view once per answer and pairs it on its key, as `fact-model.adoc`
states the rule for views:

* The violated coordinates are read first.
* If there are none, the answer is an empty map.
* Otherwise, the claims are read restricted to those coordinates. `typeClaims` restricts on type
  names. `fieldClaims` restricts on `(type_name, field_name)` pairs, using a row-value `IN`.

The new form returns the same map, and the cost drops to the view read.

Each claim reader gets an overload that takes the violated keys. The existing two-argument entries
that `AuthoredClaimRejectionRows` calls become "read the keys, then call the overload".

`typeGrain` already reads the view itself, gated by `inDomain`. It collects the type names from
that read and passes them to the overload, so it no longer reads the view a second time through
`typeClaims`.

Reading the stored `intent_authored_claim_rejection` rows in `typeGrain` instead was considered
and rejected. Those rows carry the rendered message, not the `Rejection` value and location that
`typeGrain` builds.

R817 is the same file and a related shape: `fieldGrain` reads a recursive view once per conflict
row. It stays separate. On a schema with no conflicts it costs nothing, which is why the sis
profile does not show it.

## Tests

* Fix 3 and the fix 2 check: a test in `graphitron-model` seeds a `graphql_field_element`, a
  `graphitron_field` and a `graphql_argument_element` row whose coordinate does not match its key,
  and asserts that the `CHECK` refuses each one. Existing capture tests cover the upsert path itself.
* Fix 2: `ArgMappingCandidateTest` pins the candidate set, including the contested carrier mark and
  the deprecated spelling, and it must pass unchanged. A case is added for an argMapping at an
  output field's coordinate whose field name matches an input type's field, which pins that the
  `INPUT_OBJECT` gate stays in the flattened chain.
* Fix 4: `AuthoredClaimConflictsTest` pins the non-empty path through its seeded conflicts. It
  gains a case with no conflicts, which asserts that both claim maps are empty.
* Fix 1 changes no behaviour, so it gets no new test.

## Verification

Install the build, then run `mvn graphitron:dev -pl :sis-graphql-spec` in the sis checkout with
this worktree's `aether.enhancedLocalRepository` flags, so it resolves this build's jars. Report
the second run, because fixes 1, 2 and 3 move the store path and the first run rebuilds it. Compare
"deriving 28 stages", "derivation stratum done in", "initial run failed validation" and "LSP
listening" against the profile's 2026-10-07 column.
