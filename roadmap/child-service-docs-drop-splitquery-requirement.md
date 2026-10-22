---
id: R1082
title: "Manual says child @service needs @splitQuery; the classifier requires a Sources parameter instead"
status: In Progress
bucket: docs
depends-on: []
created: 2026-10-21
last-updated: 2026-10-22
---

# Manual says child @service needs @splitQuery; the classifier requires a Sources parameter instead

## Goal

The user manual tells authors that a `@service` on a non-root field (a child field, one below Query or Mutation) must carry `@splitQuery` (the directive that moves a field into its own batched DataLoader query), and that the build rejects it otherwise. The build does not work that way. Every child `@service` is delivered through a DataLoader whatever its directives say, and what the build actually requires is a `Sources` parameter (the `Set<Key>` or `List<Key>` of parent keys the method is called with), paired with a `Map<Key, V>` or `List<V>` return. When this item lands, the manual states that contract and drops the `@splitQuery` requirement. Writing `@splitQuery` beside a child `@service` then earns the same lint warning, with a delete fix, on every parent shape, where today it warns on one shape and is silent on the others.

The minimal pair, from the Sakila example schema (`graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`), where `Film` is a `@table` type:

```graphql
type Film @table(name: "film") {
    # Builds and runs today, with no @splitQuery. The method takes the batch of parent keys:
    #   Map<Record1<Integer>, String> titleUppercase(Set<Record1<Integer>> filmIds, DSLContext dsl)
    titleUppercase: String @service(service: {
        className: "no.sikt.graphitron.rewrite.test.services.FilmService",
        method: "titleUppercase"
    })
}
```

A method on the same coordinate with no `Sources` parameter, such as `String titleUppercase(DSLContext dsl)`, is rejected with "declares no Sources parameter, but a child @service field resolves through a DataLoader and needs one", and adding `@splitQuery` does not change that verdict. The manual currently says the opposite on both counts.

## Evidence

The manual passages that state the requirement:

* `docs/manual/reference/directives/service.adoc`, a bullet under "Constraints": "On non-root fields, `@service` requires `@splitQuery`", plus the See-also line calling the how-to's coverage "the `@splitQuery` pattern".
* `docs/manual/how-to/handle-services.adoc`: the page `:description:`, the intro sentence's "the contract the rewrite imposes when `@service` lands on a non-root field", the "`Map<Key, Value>`" subsection's "(under `@splitQuery`, see next section)", the section heading "`@service` on a child field: the `@splitQuery` contract" with its first paragraph ("allowed *only* under `@splitQuery` ... the classifier rejects the schema at build time") and "Under `@splitQuery`, the runtime contract is:", the "Non-root `@service` requires `@splitQuery`" pitfall, and the closing See-also line. The same page already carries a correct pitfall ("*Every child `@service` batches.*"), so the page contradicts itself today.
* `docs/manual/reference/directives/splitQuery.adoc`: the "Constraints" bullet "Required when `@service` is used on a non-root field", and the `filmsFromCity` example that pairs the two directives (introduced as "resolver-style fields ... need an explicit split").
* `docs/manual/how-to/migrating-from-legacy.adoc` and `docs/manual/reference/directives/notGenerated.adoc`: the `field: T @splitQuery @notGenerated` row of each mapping table, "(with `@splitQuery` for non-root batching)" and "Service methods can be batched with `@splitQuery` at non-root sites".

What the code does:

* The two child arms, `FieldBuilder`'s `DIR_SERVICE` branches in the record-backed parent classifier and in `classifyChildFieldOnTableType`, call `ServiceDirectiveResolver.resolve` (with `ParentContext.RecordParent` and `ParentContext.TableParent`) without reading `@splitQuery`. `ServiceDirectiveResolver.childSourcesVerdict` rejects a child service with no `Sources` parameter.
* `DeliveryFactRelation.mint` returns before any marker read for a coordinate with a service row ("The service call is the delivery"), so `@splitQuery` on a child `@service` changes no delivery fact either. The directive is inert there.
* The redundancy lint fires on only one of the shapes. In the record-parent `DIR_SERVICE` branch, `warnIfSplitQueryOnRecordParent` runs only when the field also carries `@sourceRow` (pinned by `ServiceRecordParentBatchKeyTest.splitQueryBesideADeclaredProducer_stillGetsTheRedundancyAdvisory`). A record-backed parent without `@sourceRow`, and every `@table` parent, accept the directive silently. An author following the manual gets a warning on one shape and nothing on the others.
* `ServiceCatalog.classifySourcesType` maps a `Set<E>` source to the mapped container (`Map<Key, V>` return) and a `List<E>` source to the positional one (`List<V>` return); `ServiceDirectiveResolver.validateChildServiceReturnType` enforces the pairing.
* The Sakila example's `Film.titleUppercase`, `titleLowercase`, `titleTitlecase`, `languageByService` and `City.cityUppercase`, `cityLowercase` are child `@service` fields with no `@splitQuery`, and they build and execute.

## User documentation (first-client check)

The replacement text, page by page. If the implementer finds a sentence here that does not read simply against the page around it, raise it rather than paper over it.

`service.adoc`, the "Constraints" bullet, replaced with:

> * On a non-root field, `@service` always batches. The generated fetcher collects the parents the request touched and calls the method once per field per request, through a DataLoader. The method therefore declares a `Sources` parameter, the parent keys, and returns one value per key: `Set<Key>` paired with `Map<Key, V>`, or `List<Key>` paired with `List<V>` in the same order. `Key` is `Row<N>`, `Record<N>` or a typed `TableRecord`, and carries only the key columns. A method with no `Sources` parameter is rejected at build time; there is no per-parent service call. `@splitQuery` is not needed: the field is already split, so the directive is ignored and draws the `splitquery-redundant-on-service-child` lint warning, which offers to delete it. Single-statement nested resolution is not supported on the service path.

and the See-also line reads "covers nested-input mapping, response shaping, and the child-field batching contract in depth."

`handle-services.adoc`:

* `:description:` ends "...and wire @service onto a child field through the batched Sources contract."
* The section heading becomes "`@service` on a child field: the batched contract". No xref targets it; the one inbound pointer is the `Map<Key, Value>` subsection's prose "see next section", which drops "under `@splitQuery`".
* The section's first paragraph becomes: "`@service` on a non-root field always resolves through a DataLoader: there is no per-parent service call, and no directive selects one. What the build checks is the method's shape: it must declare a `Sources` parameter, the batch of parent keys, and return one value per key. A method with no `Sources` parameter is rejected at build time." "Under `@splitQuery`, the runtime contract is:" becomes "The runtime contract is:".
* The list's dispatch step names both containers: the service returns `Map<KeyType, ChildValue>` for a `Set` source, or a `List<ChildValue>` in source order for a `List` source.
* The "Non-root `@service` requires `@splitQuery`" pitfall is deleted; the existing "*Every child `@service` batches.*" pitfall already states the contract. Its last sentence gains: "`@splitQuery` beside a child `@service` is redundant, and the build warns about it."
* "*Argument binding is by name.*" becomes: "*Argument binding is by name first.* GraphQL argument `ids` binds to Java parameter `ids` by default; rename via `argMapping:` when the Java parameter has a different name. What is left unbound after that pairs up when the pairing is unique: one parameter and one argument left over bind to each other, and otherwise a parameter whose Java type occurs once among the leftover parameters binds to the one leftover argument of that type. Anything still unpaired needs an `argMapping:` entry. Mismatches are caught at build time." (`ServiceCatalog.inferBindingsByType`'s two rules, arity-unique then type-unique.)
* The See-also line for `@splitQuery` is deleted; the page no longer leans on the directive.

`splitQuery.adoc`:

* The `filmsFromCity` example and the sentence introducing it are deleted.
* The "Required when `@service` is used on a non-root field" bullet is replaced by one on the "redundant and at most warned about" side of the line the page already draws: "Redundant but not rejected beside xref:service.adoc[`@service`] on a non-root field. A child service is always called once per batch of parents, so the split the directive asks for is already the delivery. Writing `@splitQuery` there yields a lint warning (`splitquery-redundant-on-service-child`) offering to delete it, and the directive is ignored."

`migrating-from-legacy.adoc` and `notGenerated.adoc`, the `@splitQuery @notGenerated` row: the target column reads xref `@service` alone, and the note reads "A child `@service` is the batched custom fetcher: the method runs once per batch of parents, taking `Set<Key>` and returning `Map<Key, Value>`. Drop `@splitQuery`; it is not needed beside `@service`, and the build warns about it." The migrating page keeps its trailing link to the how-to section.

## Implementation

Docs: the five pages above, edited to the draft.

Code, the one new lint rule:

* `LintRule` (`graphitron-model`) gains `SPLITQUERY_REDUNDANT_ON_SERVICE_CHILD("splitquery-redundant-on-service-child", Source.CLASSIFIER)`, beside the two existing `@splitQuery` redundancy rules, and the `lint_rule` table's seed in `graphitron-model.sql` gains the matching `('splitquery-redundant-on-service-child', 'CLASSIFIER', 'WARNING')` row beside theirs.
* `FieldBuilder` emits it through the existing `warnIfSplitQueryRedundant`, with the reason "@splitQuery is redundant on a child @service field; the service call is already batched through a DataLoader. The directive will be ignored." A small `warnIfSplitQueryBesideChildService` helper beside `warnIfSplitQueryOnRecordParent` carries the rule and reason. It is the first statement of both child `DIR_SERVICE` branches, ahead of `serviceResolver.resolve`, so a resolver rejection does not suppress it (the ordering the record-parent branch already uses, for the reason its comment gives). The emit stays out of `ServiceDirectiveResolver`: the resolver returns a sealed `Resolved` verdict and also serves the root arms, and a diagnostic side effect there would sit behind the very guards the ordering exists to precede. The condition the helper reads, a service row plus the `@splitQuery` marker, is the same service fact `DeliveryFactRelation.mint` decides delivery on, so the warning fires exactly where the directive is inert.
* In the record-parent branch, the existing `warnIfSplitQueryOnRecordParent` call inside `if (sourceRow != null)` is replaced by the new call, now unconditional, so a `@sourceRow` + `@service` coordinate warns exactly once, under the service rule. `SPLITQUERY_REDUNDANT_ON_RECORD_PARENT` keeps its other emit sites (the `@service` carrier data field, `@sourceRow` without `@service`, the class-backed parent's table-bound child) and its id. Its javadoc and the comments at the retained sites stay accurate. The comment at the replaced site is rewritten to the new rule's subject.
* The `@service`-carrier payload data field (the `warnIfSplitQueryOnRecordParent` call under "@splitQuery on the carrier data field is structurally redundant") is not a child `@service` coordinate, since the `@service` sits on the mutation, and is left as is.

No consumer's build breaks: no rule id is retired, so a `<lint><disabledRules>` entry naming `splitquery-redundant-on-record-parent` still validates. At a `@sourceRow` + `@service` coordinate, such an entry no longer silences the warning, because the warning now comes under the new id. That is a warning, never an error (every rule is a warning, per the `lint_rule.severity` column), and the Done entry in `roadmap/changelog.md` records the move.

## Tests

* `LintRuleCatalogueTest.everyClassifierAdvisoryRuleExists` adds the new id to the pinned set. The model-side `LintRuleCatalogueTest.theCatalogueAndTheEnumAgree` (in `graphitron-model`) holds the `lint_rule` seed in step with the enum, so it fails if either half of the new rule lands without the other.
* `ServiceRecordParentBatchKeyTest.splitQueryBesideADeclaredProducer_stillGetsTheRedundancyAdvisory` asserts the new rule (and, as a second assertion, that the record-parent rule does not also fire at `Parent.rank`).
* `ClassifierAdvisoryFixPipelineTest` gains the `@table`-parent case (`@service` with a `Set<FilmRecord>` source, as `Film.titleTitlecase` wires it), asserting one `SPLITQUERY_REDUNDANT_ON_SERVICE_CHILD` finding at the coordinate with a delete fix spanning exactly `@splitQuery`, and that the field still classifies to its service variant. A second case, the same coordinate whose method has no `Sources` parameter, asserts the warning fires beside the rejection.
* `ServiceRecordParentBatchKeyTest` gains the class-backed-parent case without `@sourceRow`, beside the existing `@sourceRow` one and on the same private `classBackedParent` fixture, asserting one finding under the new rule.
* A negative: a child `@service` without `@splitQuery` draws no finding under either `@splitQuery` rule.

The docs half is demonstrated by the five pages reading as the draft above, and by a grep of `docs/manual` for `@splitQuery` within a sentence of "non-root" or "child" that finds only the redundancy bullet and the migration rows. Under `## Retired vocabulary` below, the Done reviewer has the query.

## Retired vocabulary

Retired as claims about `@service` (the directive itself is not retired): "requires `@splitQuery`", "only under `@splitQuery`", "the `@splitQuery` contract", "the `@splitQuery` pattern", "for non-root batching", "per-parent batch wrapper non-root services require".

## Other solutions we've considered

* *Docs only.* Fix the pages and describe `@splitQuery` beside a child `@service` as ignored, leaving the lint where it is. The manual would then have to say the directive is ignored everywhere but warned about only when the parent is record-backed and the field also carries `@sourceRow`. That sentence describes an accident of where one call sits, not a rule, and an author cannot predict it from the schema. Making the warning uniform costs one enum constant, one helper and two call sites through a shape that already exists.
* *Reuse `splitquery-redundant-on-record-parent` on the table-parent arm.* No new id, but the id would then name a parent shape the finding is not about, and a consumer silencing record-parent redundancy would silence table-parent service findings with it. Each existing `@splitQuery` redundancy rule names the delivery that makes the directive redundant (the record handoff, the discriminated re-projection); the service call is a third such delivery and gets its own name.
