---
id: R1205
title: "An unrouted @nodeId argument under a field-level @condition(override: true) is refused, though the override suppresses the predicate a route would serve"
status: Spec
bucket: bug
priority: 3
theme: nodeid
depends-on: []
created: 2026-11-23
last-updated: 2026-11-25
---

# An unrouted @nodeId argument under a field-level @condition(override: true) is refused, though the override suppresses the predicate a route would serve

## Goal

A `@nodeId(typeName:)` argument or input field whose implicit predicate an enclosing `@condition(override: true)` suppresses builds without a foreign-key route from the field's table to the node's table. The override leaves the condition method as the only thing narrowing the rows, so nothing joins to the node's table and a route has nothing to serve; the id is decoded and handed to the method, which is the whole of what the author asked for. Today the build refuses every spelling of this, and the remedy it names, a `@reference` path, asks the author to describe a join the generated query never makes.

The shape, on the sakila catalog, where `film_actor` sits between `film` and `actor` and no foreign key joins the two directly:

```graphql
type Query {
  filmsByActors(actorIds: [ID!] @nodeId(typeName: "Actor")): [Film!]!
    @condition(condition: {
      className: "no.sikt.graphitron.rewrite.test.conditions.ProjectedKeyConditionFixtures",
      method: "filmsWithAnyActor",
      argMapping: "actorIds: actorIds.actor_id"
    }, override: true)
}
```

`filmsWithAnyActor(Table<?> film, List<Integer> actorIds)` writes the `EXISTS` over `film_actor` itself. When this item lands the field builds as written: the method receives the decoded `actor_id` values, a malformed or wrong-type id is the usual client error, and an omitted list reaches the method as `null`. The same field without `override: true` stays refused, because the condition then adds to the implicit predicate `actorIds` binds on `film`, and that predicate needs the route.

This is the schema of [GitHub issue 583](https://github.com/sikt-no/graphitron/issues/583): organisations that are members of one of several collections, through a membership table. Its reporter cannot keep `@nodeId` on the argument without writing paths that mean nothing.

## What happens today

Two judges refuse a schema. The *store* is the fact store, the database a build captures a schema into; a defect row it holds stops the build. The *walk* is the older classifier, which still classifies every field of a graph the store's verdict accepts, and whose refusals reach the author as validation errors. Since `f34cef0` the walk renders no condition code; the store's `ConditionEmitter` does, decode included.

Probed on trunk at `29c56d5` against the sakila catalog, with a condition method on the classpath for each row. The store's answer was read through `FactExpectationTest` over a scratch fact document; what an author is told was read by generating the rows the store accepts as a scratch scenario through `GenerateTestSchemas`, the run the build makes. `film` and `actor` share no foreign key, and `film` has two to `language`; both kinds of missing route behave alike. A parameter bound to an id whole, by name or by an entry stopping on the argument, must be the node's record or a tuple of its key, and no fixture has one, so those rows also drew `NODE_ID_KEY_ARITY_EXCEEDS_SLOT` for the fixture's `List<Integer>`; that row is the fixture's and is left out below. For the entry stopping on the argument the walk's answer was therefore read from the classifier alone, through `TestSchemaHelper.buildSchema`.

Every row below is under an enclosing `override: true`, written on the field unless the row says otherwise.

| The id | Store | Walk, where the store accepts |
|---|---|---|
| A direct argument, read by a parameter named after it | `NODE_ID_LANDS_ON_TABLE` | not reached |
| A direct argument no parameter reads | `ARGUMENT_UNBOUND` and `NODE_ID_LANDS_ON_TABLE` | not reached |
| An input field whose argument a parameter takes whole, directly under the argument or one input object deeper | `NODE_ID_LANDS_ON_TABLE` | not reached |
| The same, the override written on the argument | `NODE_ID_LANDS_ON_TABLE` | not reached |
| A direct argument an `argMapping` entry names, stopping on it or naming its key column, one id or a list | none | Refused: "no foreign key found between tables 'film' and 'actor'; auto-discovery is single-hop ..." |
| An input field an `argMapping` entry descends onto, the override on the field or on the argument | none | Refused at the input field with the same message, and at the field as "1 input field could not be resolved" |
| The reported shape, a list and a single id read by one condition through entries naming each key column | none | Refused, once per argument |
| The id's own `@condition(override: true)` | none | Builds |

So no spelling builds under an enclosing override, and which judge refuses depends on the spelling. Only the id's own override builds, which is no help to a condition reading two arguments.

Without the override every spelling is refused by the store with `NODE_ID_LANDS_ON_TABLE`, by name and through `argMapping` alike. That is the rule and not part of this item; see the next section.

## The rule, and where the code falls short of it

A condition without `override: true` is ANDed with every argument's implicit predicate (`docs/manual/reference/directives/condition.adoc`), that predicate needs the route, and none or several foreign keys is `NODE_ID_LANDS_ON_TABLE` with `@reference` as the remedy (Alf, 2026-11-06 and 2026-11-07, recorded in R876's graph and in `docs/architecture/reference/code-generation-triggers.adoc`). An id an `argMapping` entry hands to the method is still compared by the filter, so it owes the route as well: `83034e2` put that in the store's rule, `facts/defects/node-id-lands-on-table.graphqls` pins it at `filmsMapped`, and `defects/NODE_ID_LANDS_ON_TABLE.adoc` says it. The reporter's schema built on 10.0.0-RC56 with no paths and no override; that was older behaviour of the walk.

Under an override, three statements already agree that no route is owed:

* The manual: field-level `override: true` "suppresses the implicit predicates from the field's direct arguments and from any input-typed argument nested under them".
* The store's rule, in the comment on the arm that draws the row: "An id that binds no table predicate owes no route".
* The walk's author-owned arm, `NodeIdLeafResolver.Resolved.AuthorOwnedPredicate`: the author has taken the predicate, so only the decode is needed.

Neither judge follows them past the id's own directive.

**The store** already holds the fact. `graphitron_occurrence_overridden` has one row per *occurrence* (a field, argument or input field at one place it is reached from, so one input field used under two fields is two occurrences) that is at or under a `@condition(override: true)`: the field, each of its arguments, and every input field under them. The other filter arms of the same rule view read it, as does `graphitron_occurrence_taken`. The arm for a named id with no path does not. It tests the id's own `@condition` by joining `graphitron_directive_application` to `graphitron_ast_input_value_condition_entry`, which re-derives the first arm of `graphitron_occurrence_overridden_rule` and none of the second, and it is keyed on the declaration, so it could not tell an input type used under an overridden field from the same type under a plain one. An id under an enclosing override is then exempt only where an `argMapping` landing names it, which is why the rows of the table split as they do.

**The walk** refuses a second time what the store has already judged. `NodeIdLeafResolver.resolve` turns a route nobody wrote and no single foreign key supplies into a refusal, unless its `authorOwnedPredicate` argument is set, and its five callers (`FieldBuilder.buildNodeIdArgPlan`, `walkInputTypeForSameTableNodeId`, `collectNestedNodeIdResolutions` and `classifyArgument`, each through `leafOverride`, and `BuildContext.classifyInputFieldInternal` reading the field's own directive) set it from the leaf's own override alone. At every spelling probed with no override covering the id, the store's verdict stopped the build before the walk ran, so that refusal reached an author in one case only: where the store had accepted the id, which is the rows above. The walk's treatment of a column is the contrast. An argument or input field naming no column is never refused there: it becomes `ScalarArg.UnboundArg` or `InputField.UnboundField`, carriers that bind nothing, are admitted where an enclosing override suppresses them, and are otherwise left to the store's `COLUMN_UNRESOLVED`, "whose verdict stops the build first" in their own javadoc.

A defect raised on a valid schema is a regression fixed ahead of planned work (`docs/architecture/explanation/defects-as-facts.adoc`), which is why this item sits above the ones around it.

## Implementation

Two commits, the store's first. Neither adds a relation, a stage, a defect code, a classifier arm or a message; the second removes a refusal.

**The store's exemption reads the stored fact** (`fix`). In `graphitron_occurrence_node_id_landing_entry_defect_rule`, in the arm commented "A named node id with no route written, at a slot on a table other than the node's":

* Replace the `NOT EXISTS` over `graphitron_directive_application` and `graphitron_ast_input_value_condition_entry` with a `NOT EXISTS` over `graphitron_occurrence_overridden` at the occurrence, `o.coordinate`. It covers the id's own override, which is that relation's first arm, so nothing that is exempt today stops being exempt.
* Drop the inner `NOT EXISTS` over `graphitron_occurrence_overridden` inside the filter-term test of the same arm, which the line above makes redundant.
* Restate the arm's comment as the one rule: an id under an override binds no table predicate and owes no route, as one an `argMapping` entry hands to a call and one under a `@service` do not.

`occurrence-overrides` runs at stage 35 and `occurrence-builds`, which owns this rule, at 42, so the read goes upstream. The grain becomes the occurrence: an input type reached under one overridden field and one plain one still draws its row for the plain occurrence, reported at the declaration as today.

What the change leaves alone, each checked in the probe or stated by the arm above it:

* A path the author wrote that ends off the node's table is still refused under an override, by the arm for written and inferred paths. Naming a foreign key asks the build to check it, which is also the walk's rule.
* A `@lookupKey` needs no carve-out. A lookup key drives its lookup's join whatever stands in for the filter, and a node id of another table as a lookup key is `LOOKUP_KEY_OFF_TABLE` under an override already (probed: that row and no other).
* An id under an override that nothing reads keeps `ARGUMENT_UNBOUND` and loses the second row beside it.

The change only widens an exemption, so it can draw no new row. Count the rows it stops drawing over the FS-plattform captures, as `83034e2` did, and put the count in the commit message.

**The walk stops refusing a route nobody wrote** (`fix`). The store owns validation (Alf, 2026-11-13, `docs/architecture/explanation/pipeline-overview.adoc`), and a walk refusal that duplicates a stored defect is removed where it untangles simply (`defects-as-facts.adoc`). This one does, along the line the column case already drew:

* `NodeIdLeafResolver.resolve` loses its `authorOwnedPredicate` parameter. A leaf with no stated route and none or several foreign keys resolves to one arm whatever overrides it, today's `Resolved.AuthorOwnedPredicate`, renamed for what it now says: no route, and the walk does not judge that. The five callers stop computing the flag and `FieldBuilder.leafOverride` goes.
* Where the leaf carries its own `@condition(override: true)`, the consumers build what they build today, `ScalarArg.ConditionOwnedArg` and its input-field counterpart, and `FieldBuilder.firstMixedContract` keeps refusing a multitable field where some participants route such a leaf and others do not. That check concerns the leaf's own method, which is handed a different table on each side of the split; under an enclosing override the method is the field's and receives the field's table either way, so the check does not widen.
* Everywhere else the leaf takes the unbound carrier, `ScalarArg.UnboundArg` or `InputField.UnboundField`: nothing to bind, admitted where an enclosing override suppresses it, and otherwise a shape the store's `NODE_ID_LANDS_ON_TABLE` has refused before the walk ran. A leaf whose own `@condition` does not override keeps the treatment the column case gives it.
* A stated route that fails to resolve stays the walk's refusal, as does every refusal about the leaf itself (an unknown type, a polymorphic container, a key past jOOQ's `Row22`).

Two things the implementer has to establish, not assume:

* **Nothing the walk refused alone is left without a stored answer.** The probe found a stored `NODE_ID_LANDS_ON_TABLE` at every unrouted spelling with no override. The walk's own tests of this refusal (`NodeIdLeafResolverTest`, and the cases found by its two messages, "no foreign key found between tables" and "multiple foreign keys found between tables", in `GraphitronSchemaBuilderTest` and the pipeline tests) each name a shape: a bare `@nodeId`, a multitable participant, a nested input. Each moves to a fact document row stating the stored defect for that shape. A shape the store states no defect for and no override covers is a finding to bring back to this item before the refusal goes, not a schema that starts building.
* **The decode ledger tells the truth about these coordinates.** `NodeIdDecodeLedger.recordLeaf` records today's arm as a decode the walk installs, and `FieldBuilder.disposeRefusedNodeIdArgument` records an `UnboundArg` as a refusal; for an id that builds under an enclosing override with its decode rendered by the store's `ConditionEmitter`, both are false, and the ledger keeps a coordinate's first row, so a false one also shadows the projected-key row `NodeIdDecodeCoverage.violations` folds in from the store. The constraint here is narrow: such a coordinate does not fail the coverage rule, is not recorded as refused, and is not recorded as a walk install it does not carry. What row a decode the store emits should get is not settled on any page and this item does not settle it; it goes onto R876's routes node. `NodeIdDecodeCoverage.violations` only asks that a row exists, so it is no evidence of a decode: the execution test's malformed-id case is.

Removing the refusal takes no help from an author. Its message, with the candidate keys, is one the store's verdict already pre-empts wherever the route is owed.

R876's node "The classifier reads its routes from the store, the @nodeId filter first" deletes the resolver's route search. This commit leaves it less to delete and adds nothing for it to migrate.

**Docs**, in the commit whose rule they state:

* `docs/manual/reference/directives/condition.adoc`, as drafted below.
* `docs/manual/reference/directives/reference.adoc`, path rules, and `docs/architecture/reference/code-generation-triggers.adoc`, "Paths on arguments and input fields": both say a `@nodeId(typeName:)` "always has a path". The store has exempted an id under a `@service`, one an `argMapping` entry hands to a call, and one with its own override for some time, so the sentence gains "where a table predicate compares it" and the list of what does not. The architecture page states a dated ruling of Alf's; see Questions.
* `docs/architecture/reference/argument-resolution.adoc`: `ConditionOwnedArg` is described as a `@nodeId` argument with no route "whose own `@condition(override: true)`" owns the predicate; say that an unrouted id without one is an `UnboundArg`. The javadoc of `ScalarArg.UnboundArg` and `InputField.UnboundField` says such a slot "compares nothing, the store's `COLUMN_UNRESOLVED`"; it gains the unrouted id and its code.
* `graphitron-model/src/main/resources/no/sikt/graphitron/model/defects/NODE_ID_LANDS_ON_TABLE.adoc`: its last sentence names the `argMapping` case "without `override: true`"; restate it as the rule, an id is judged where a filter compares it and an override at or above it means none does.
* `roadmap/changelog.md`, at Done: the entry says that a named id with no route and no override built on 10.0.0-RC56 and is refused since, and names the two remedies, a path or an override. Calling that an unrecorded breaking change is a statement about what was intended; see Questions.

## User documentation (first-client check)

`condition.adoc`, the `override` row of the argument table, its last sentence but one:

> On a `@nodeId` leaf with no join route to its node's table, an override that suppresses the leaf's predicate also suppresses the route demand: nothing compares the id on a table, so there is nothing for a `@reference` path to describe, and the method receives the leaf's decoded key.

The constraint that today begins "On a `@nodeId` leaf whose route to the target table does not resolve, `override: true` takes the whole `WHERE` contribution" gains what "the override" is and what each kind hands the method:

> This holds for whichever override suppresses the leaf's implicit predicate: the leaf's own, the enclosing field's, or that of an argument or input field the leaf is nested under. With the leaf's own override its method receives the resolving table and the decoded key. Under an enclosing override the leaf has no method of its own; the enclosing condition's method reads it as it reads any argument, by a parameter bound to it or by an `argMapping` entry, and an id nothing reads is refused as `ARGUMENT_UNBOUND`. A path written on such a leaf is still checked, and one that ends off the node's table is refused. Without an override at or above the leaf the route is required: write it with `@reference`.

That reads as one rule with one exception list, which is the test this section is for.

## Tests

* **The defect's minimal pair, in its keystone.** `facts/defects/node-id-lands-on-table.graphqls` owns the code. It gains a field over `film` with an `Actor` id read by a parameter named after it, written twice: under a field-level `override: true` it draws nothing, and without the override it draws `NODE_ID_LANDS_ON_TABLE` at the argument with detail `public.film`. Two input types of one shape, each holding an `Actor` id and taken whole by `InputFieldConditionFixtures.outerOverrideMethod`, pin the nested case the same way, one under an overridden field and one under a plain field; a third under an argument-level override on its argument draws nothing. The header says what each is there for. The by-name pair needs a condition method taking the id whole, as a `Row1<Integer>` or the node's record, which no fixture has; it goes in `graphitron-sakila-service` beside `InputFieldConditionFixtures.projectNameAtlas`, the composite-key one.
* **`ARGUMENT_UNBOUND` alone.** `facts/defects/argument-unbound.graphqls` gains an unrouted id under a field-level override that no parameter reads, beside one the method does read: one row at the unread id, and no `NODE_ID_LANDS_ON_TABLE` at either.
* **A scenario that generates.** `facts/scenarios/condition-filter.graphqls` has five ids under four fields carrying a field-level override, each id with a `@reference` path written only because the build demanded one. `filmsByLanguagesWithOriginal`, the reported shape, and `OptionalLanguageFilter.languageRef` drop theirs; `filmsByLanguages` and `LanguagesPick.languageIds` keep theirs, so a written path under an override stays covered. The document's `graphitron_path` claim follows (`EMPTY` where the path went). A scenario stores no defect and `GenerateTestSchemas` generates every one, so this document is where the store's verdict and the walk's are held to the same answer, and `ConditionEmitterExecutionTest` goes on compiling and running what it emits.
* **Execution.** `filmsByActors` as in the Goal goes into `graphitron-sakila-example`'s schema with `filmsWithAnyActor` in `ProjectedKeyConditionFixtures`, and beside it a field reading the same list whole by a parameter named after the argument, its method taking a `List` of `Row1<Integer>` or of the node's record, the fixture the keystone's by-name pair adds. An execution test beside `ProjectedNodeIdListConditionExecutionTest` runs both against PostgreSQL: the films of the named actors come back, a malformed id in the list is the client's error, an omitted list constrains nothing.
* **What stays refused.** The keystone's existing rows are unchanged, `filmsMapped` among them, and it gains a row for each shape the walk's deleted cases named (see Implementation). `NodeIdLeafResolverTest` keeps its cases for a stated route that fails, with and without the leaf's own override; its cases for the flag go with the flag.
* **Acceptance.** The table above, row by row. Row 2 keeps `ARGUMENT_UNBOUND` as its one stored row and does not build. Every other row has no stored defect and builds, the last as it does today.

## Retired vocabulary

* `NodeIdLeafResolver.resolve`'s `authorOwnedPredicate` parameter, `FieldBuilder.leafOverride`, and its javadoc sentence "An enclosing cascade does not count here".
* `NodeIdLeafResolver.Resolved.AuthorOwnedPredicate` under that name. The manual's "author-owned" for a leaf whose own override takes the predicate stays.
* The resolver's two auto-discovery refusals at an argument or input field, "no foreign key found between tables ...; auto-discovery is single-hop ..." and "multiple foreign keys found between tables ...", wherever no other caller still mints them.
* "the leaf's own `@condition(override: true)`" as the condition for owing no route: the resolver's javadoc, the SQL comment on the store's arm ("one whose own @condition(override: true) takes the predicate on itself"), and `argument-resolution.adoc`.

## How this sits against other items

* **R1202** (done) made an `argMapping` entry naming a key column past a list of node ids build at a `@condition`. Its probes all had a route, and its scenario rows carry the paths this item removes. With both landed the issue 583 schema builds with `override: true`, entries naming the key column, and no paths.
* **R876.** The store's commit is what survives, and the walk's is a subtraction ahead of the node "The classifier reads its routes from the store, the @nodeId filter first", whose list of what goes loses the refusal and the flag when this lands. That node also takes the two findings named above: the arm's overlap with `COMPARED`, and what the decode ledger records for a decode the store emits. No `depends-on`: nothing here waits on a node.
* **R1139** asks for the same exemption for an argument matching no column, and the 2026-11-25 staleness audit reads it as delivered. This item is the `@nodeId` case that remains; if R1139 is discarded its changelog line points here. Nothing of it is folded in.

## Other solutions we've considered

**Widening the walk's escape.** The five callers could pass the leaf's own override or the enclosing one, which the walk carries as `fieldOverride` and `ClassifyContext.enclosingOverride`, so the resolver returns its author-owned arm under either. It is the smaller diff and it was this spec's first draft. It loses to the plan above on three counts: `ClassifyContext`'s own javadoc says the classifier does not read `enclosingOverride` to decide a variant, so this would be the first place it does, a new axis in a classifier `pipeline-overview.adoc` calls "a surface being drained, not a place to extend"; it keeps a walk-side definition of "covered by an override" beside `graphitron_occurrence_overridden`, free to drift from it; and it leaves the duplicate refusal standing for R876 to delete later.

**The walk standing down at these fields**, as it does at a `@service`: its errors dropped where the store owns the field. Not available. A table-bound read is still planned from the walk's classification (R876's "The walk's read path is gone" is blocked, and "The root query fetchers are emitted from the store" is open and not landed), so a field the walk leaves unclassified gets no fetcher. The plan classifies the field and only stops refusing it.

**Folding the arm's other tests into one stored fact.** The arm's remaining tests, a filter term, an `argMapping` landing and a `@service`, together re-derive part of what `graphitron_occurrence_taken` states as `COMPARED`. That relation is written by `occurrence-tenants` at stage 43, after this rule, so the arm cannot read it. Folding them means storing "binds a table predicate" ahead of stage 42 or moving the arm a stage later, and either is a change to the model's shape, not to this exemption. It goes onto R876's routes node as a finding.

## Questions for Alf or Filip

1. **The dated ruling.** "`@nodeId(typeName:)` ... always has a path" is Alf's wording of 2026-11-06, and the store's rule has had exemptions to it since. Qualifying the sentence states what the code already does and adds no rule, but it edits a ruling's text, so Alf should see the new wording before or at review.
2. **What RC56 did.** The changelog entry would say that a named id with no route and no override built on 10.0.0-RC56 and has been refused since without a breaking-change note. That the refusal is intended is ruled; that RC56's acceptance was unintended, and so that the note is owed, is Alf's to confirm with the first.
3. **Removing the walk's refusal in a bug item.** The plan reads the 2026-11-13 ruling, the store owns validation, as covering this removal without a further one. If the removal should instead wait for R876's routes node, the reporter waits with it, or the widened escape above is the interim; that trade is Filip's or Alf's.

## Architect consult

Consulted twice.

At take-up, with the store read of `graphitron_occurrence_overridden` and a widened walk flag as the proposed design. It raised: that replacing the own-directive test is the right shape and also corrects the test's grain from declaration to occurrence (taken, Implementation); that the arm must not read `graphitron_occurrence_taken`, which is downstream, and that the overlap with `COMPARED` is a finding for R876 (taken, Other solutions); that a `@lookupKey` should keep owing its route under an override (settled by the probe: `LOOKUP_KEY_OFF_TABLE` already states it, so no carve-out is written); that the walk should read the stored fact and not recompute it, or the refusal be deleted if it feeds no emission (not taken then, taken in substance after the second consult); that "always has a path" is already untrue and its correction qualifies a dated ruling (Questions, 1), with `reference.adoc`'s path rules and `argument-resolution.adoc` changing beside it (taken, Docs); that R1139 is a cross-reference only (taken); and that one overridden scenario field should keep a written path (taken, Tests).

Before handover, on the drafted spec with the widened flag. It raised: that the draft's premise was wrong, the classifier not reading `enclosingOverride` to decide a variant, and that the column case shows the shape that deletes, an unrouted leaf as an unbound carrier with the store's defect the only refusal (taken: the walk half is rewritten as that removal, and the widened flag moved to Other solutions); that the stand-down claim holds but rules out only leaving the field unclassified (taken, Other solutions); that the resolver has five callers, not three (taken); that the ledger row for these coordinates would be false and `NodeIdDecodeCoveragePipelineTest` proves nothing about them (taken: the constraint is stated, the evidence is the execution test, and the open half goes to R876); that the by-name execution field cannot take a `List<Integer>` (taken, Tests); that the acceptance bullet contradicted row 2 (taken); that the unbound carriers' javadoc needs the new case (taken, Docs); that the question about patching the walk was a false binary once the removal exists (taken: Questions, 3 is what is left of it); and that the changelog's claim about RC56 is a ruling in disguise (taken, Questions, 2).
