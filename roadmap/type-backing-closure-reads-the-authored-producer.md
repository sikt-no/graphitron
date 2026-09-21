---
id: R962
title: "The type-backing closure probes the census-resolved producer once per hop reached; the condition is the authored reference, and reading it there is a seek"
status: In Review
bucket: architecture
priority: 1
theme: dev-loop
depends-on: []
created: 2026-09-21
last-updated: 2026-09-21
---

# The type-backing closure probes the census-resolved producer once per hop reached; the condition is the authored reference, and reading it there is a seek

## Goal

A `graphitron:dev` refresh on one consumer subgraph spends about three and a half minutes inside one statement, the frontier insert of the type-backing closure, and after this lands that statement costs what its joins cost, milliseconds, on that subgraph and on every other. The type-backing closure is the store's answer to which Java class stands behind each SDL object or input type: a `@service` method's return grounds the type it produces, and from there each field of a backed type is read off the backing class's member to back the field's own type, until nothing new is reached. Nothing an author sees changes, with one exception that is a correction rather than a cost: a field whose `@service` names a class the classpath census never read is no longer silently backed off its parent's member, which is how the classification walk already treats it.

The rest of this item is the diagnosis, the measurement that localises the cost to one term, and the change, stated so the reviewer can check each against the fact model's own rules rather than against the reporter's stopwatch.

## The defect

`TypeBackingRows.derive` (in `graphitron-model`) writes `intent_type_backing_class` at capture cadence: it clears the graph's partition, inserts the seeds from `intent_type_backing_seed`, and then runs a frontier statement until it inserts nothing. The frontier joins the backed rows to `intent_field_accessor_hop`, the relation stating where a field's accessor lands for a given standing class, and applies one closure condition: a coordinate with a producer of its own is not read off its parent, its value coming from the method rather than from the member. That condition was spelled as an anti-join against `intent_field_producer_method`:

```sql
AND NOT EXISTS (SELECT 1 FROM intent_field_producer_method p
                 WHERE p.graph_name = h.graph_name AND p.type_name = h.type_name
                   AND p.field_name = h.field_name)
```

`intent_field_producer_method` is the authored reference resolved against the census, and it carries `COUNT(*) OVER (PARTITION BY ...)` to state how many census methods the reference matched. The fact-model page's rule under "Derived reads are views, not stored facts" names this shape exactly: a view carrying a window function cannot be pruned by a predicate applied outside it, and an anti-join against such a view is probed once per candidate row however it is spelled. The plan confirms the mechanism. H2 pushes the three correlated equalities into the view as a `QUALIFY` clause, which runs after the window, so every probe evaluates the whole view body first: the two-arm reference union joined to `store_graph_source` joined to `jvm_method`, once per hop row the closure reaches. The cost is therefore the census size times the number of hops reached, and it has nothing to do with the graph's own size, which is why a modest subgraph pays minutes.

## Measurement

Taken with the `store-performance` skill's procedure on a captured store of the sakila example `schema.graphqls` (915 fields, 92 authored producer references) with a census of the `graphitron-sakila-service` classes, then again with `graphitron-model`'s own jar added to the census so the census grew while the graph stayed fixed. Query statistics on, result reuse off, repeated sweeps.

Every child of the frontier answers in milliseconds on its own on both fixtures: `intent_field_producer_method` whole in under a millisecond, `intent_field_accessor_hop` in single digits, the seed view likewise. The frontier statement itself, run over the settled closure with the producer condition spelled four ways on the same fixture:

| producer condition | census of 289 methods, 312 hop rows | census of 16,376 methods, 1,549 hop rows |
|---|---|---|
| `NOT EXISTS` over `intent_field_producer_method` (as shipped) | 178 to 693 ms | 1,219 to 1,233 ms |
| `NOT EXISTS` over `intent_field_producer_reference` | 11 to 13 ms | 28 to 29 ms |
| two `NOT EXISTS` over the entry tables | 10 to 12 ms | 27 to 28 ms |
| no producer condition at all (the floor) | 10 to 11 ms | 25 ms |

The term is one anti-join, its cost tracks the census and not the graph, and reading the reference lands within a few percent of the floor. The writer's own insert statement on the large fixture went from 43 ms per execution to 2.6 ms, and the whole `derive` from about 100 ms to 12 to 16 ms. On the consumer's subgraph the same arithmetic, thousands of reached hops times a per-probe evaluation over a census of tens of thousands of methods, is the three and a half minutes reported.

## Implementation

The change is R876's first rung, a captured fact read where it is captured, and no rewrite of the closure's shape. It is filed under that rung deliberately: the fact-model page's anti-join paragraph offers two levers, materialize the expanded relation or capture the excluded fact for the first time, and this case is neither. The fact was captured all along; the reader was pointed past it at its resolution.

- `TypeBackingRows.expand` anti-joins `graphitron_service_entry` and `graphitron_external_field_entry`, the entry tables holding one row per application of the two producer directives, whatever the application spelled. A correlated `NOT EXISTS` against each is a primary-key seek. Not `intent_field_producer_reference`, the decode of those entries, which drops an application spelled without its method, and not `intent_field_producer_method`, its census resolution, which drops an application naming a class no classpath entry declared: in both the author still said the value does not come from the parent's member, and `RecordBindingResolver.propagateResultChildren`, the classification walk this relation shadows, skips the field on the applied directive alone. The class javadoc states the condition in those terms and leads with the fact; the cost is stated as its symptom, in relative figures.
- The `COMMENT ON TABLE intent_type_backing_class` sentence stating the closure condition is restated the same way. It gains two sentences the change makes load-bearing: the population the read adds to the relation's stated absences (a type whose only producer names an unreached class has no row, the same silence as a type nothing reaches, and the reference-to-resolution join is how a reader tells them apart), and the one arm on which the condition is broader than the walk's (`@externalField`, which the walk does not skip on; that breadth predates this change and is disclosed rather than closed).
- `COMMENT ON VIEW intent_type_backing_seed` gains one clause saying why the seed reads the resolution while the closure's condition reads the entries: the class a producer delivers is knowable only from the census method the reference matched, and the skip condition is about the authored application. Two readings of one producer, stated as the split they are.
- Nothing else moves. The seed still drives from `intent_field_producer_method`, once per arm and never correlated, which is the shape the window-function rule prescribes, and the seed insert measured unchanged. The pass loop is untouched: with the term gone the per-pass statement is at the floor on the largest fixture measured, so re-deriving from every backed row rather than from the last pass's delta is not a cost anyone is charged for. The writer's position in `FactCapture.derive` is unchanged; moving it into the graphitron gatherer's stage list is R955's placement.

The condition is more correct stated this way, and the correctness argument is the primary one; the timing is what a reader pointed past a captured fact costs. A coordinate carrying `@service` or `@externalField` gets its value from the method the author named whether or not a classpath entry the census read declares that class; the census is a completion aid, not the runtime. The resolved form found no producer for an unreached class and backed the field's type off the parent's member, a wrong class dressed as an answer.

## Tests

- `TypeBackingClassTest.aProducerReferenceTheCensusNeverReachedStillStopsTheHop`: `Film.rating` carries a `@service` naming `app.RatingService`, which the hand-built census does not declare, while `app.FilmRecord` has a `rating` component delivering `app.WrongRatingRecord`. The type `Rating` has no backing row. Under the resolved form it would be backed by `app.WrongRatingRecord`, so the case fails on the old statement and passes on the new one.
- `TypeBackingClassTest.aProducerApplicationMissingItsMethodStillStopsTheHop`: `Film.score` carries a `@service` spelled with a class and no method, which the reference view drops, while the record's `score` component delivers `app.WrongScoreRecord`. `Score` has no backing row. This is the case that separates reading the entries from reading the reference view.
- The existing closure suite is unchanged and green: `TypeBackingClassTest`, `TypeBackingShadowTest` (the walk differential over a real census), `TypeBackingSeedTest`, `FieldProducerMethodTest`, `FactCaptureAgreementTest`, `SeparateFetchTest`, and the `graphitron-model` gates (`MaterializeRegistryGateTest`, `MetaDeclarationGateTest`, `RelationRegistrationGateTest`).
- No test pins the cost. The `store-performance` skill's posture is that a wall-clock assertion in a test is not evidence; the measurement above is the record, and the class javadoc and table comment carry the reason so the next reader does not re-derive it.

## Other solutions we've considered

- **Compute the closure as a Java loop over rows read once**, the shape R876 gave the container peel at capture. Every other term of the frontier measured at the floor after the anti-join moved, so a loop would have restated a statement that is not slow and traded a rule the catalog can see for one it cannot; the entry rule R876 states leaves the loop for a rule SQL cannot state, and the frontier is not one.
- **Register or snapshot `intent_field_producer_method`.** The fact-model page's anti-join paragraph says materialization is the lever when the excluded relation states a rule over rows the store already holds; here the excluded fact is the author's own directive, already a table, so the registration would have bought a refresh to reach a row the entry tables hold. Rung four where rung one was available.
- **Anti-join `intent_field_producer_reference`, the decode of the two entries.** It measures at the floor too, and it was the first form of this change. It states one decode step above the fact the rule is about: the view keeps only a reference with both its class and its method, so an application spelled without its method would not stop the hop, while the walk skips on the applied directive alone. The entry tables are the fact itself and cost the same seek.
- **Split `intent_field_producer_method` into a resolution view without the window and a `candidates` view over it**, so that readers needing only existence probe a prunable view. Correct as far as it goes, and still the wrong fact: existence of a resolution is not the closure's condition, and the split would have kept the census in a predicate that is about the document.

## What this item does not do

- It does not move `TypeBackingRows` into the graphitron gatherer's stage list; that is R955's placement and the writer's position in `FactCapture.derive` is unchanged.
- It does not add this instance to the fact-model page's anti-join paragraph. That paragraph names two levers for an anti-join against an unprunable view, and this case is a third the page does not yet name: the excluded fact was already captured and the reader was pointed past it. One sentence there would stop the next reader who meets such an anti-join from pricing a registration before asking which relation states the excluded fact; it is a follow-up on the page rather than part of this change.
- It does not close the `@externalField` breadth against the walk. The store skips a hop over an `@externalField` coordinate and the walk does not; that reading predates this change, is now disclosed in the table comment, and whether `TypeBackingShadowTest` should pin it as an intended departure is a question for that test's charter rather than for this item.

## Reviewer findings
