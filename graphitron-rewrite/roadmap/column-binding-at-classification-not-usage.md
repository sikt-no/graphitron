---
id: R215
title: Column-binding requirement captured at classification, not derived at usage
status: In Review
bucket: architecture
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# Column-binding requirement captured at classification, not derived at usage

Today the input-field classifier (`BuildContext.classifyInputFieldInternal`) decides whether a field requires column binding by consulting only the field's own `@condition` directive. It has no awareness of the enclosing override cascade. Consumer-side logic (`FieldBuilder.walkInputFieldConditions`) honors the cascade at predicate-emission time, but by then the classifier has already rejected schemas whose fields would never have needed a column. The classifier's view and the consumer's view of "is this field required to bind a column?" disagree, and the classifier's stricter view wins by firing first. This work captures the field's classification shape (column-bound vs unbound) at classification with full cascade context, and keeps the call-site policy (does *this* call site emit the implicit predicate?) at the consumer where it belongs.

## Symptom

Documented cascade (manual/how-to/migrating-from-legacy.adoc, "behavior-divergence-condition-cascade"): a field inherits `override: true` from its containing argument, parent field, or ancestor input-field. A field inside an `override: true` cascade does not participate in any implicit column predicate.

The classifier doesn't see this. A schema like

```graphql
input Filter { foo: String }
type Query {
  things(filter: Filter
    @condition(condition: {className: "...", method: "filterByFoo"}, override: true)
  ): [Thing!]!
}
```

rejects with `"input field 'foo': no column 'foo' found in table 'thing'"` even though at consumption time `enclosingOverride = true` (from the arg-level `@condition`) would have suppressed the implicit predicate for every field, making the column irrelevant by construction. The author has to invent a `@field(name: "...")` that points at a real column whose value will never be read, or restructure to avoid the cascade — both workarounds against documented semantics.

R210 carved out one slice of this (field-level `override: true` on a field with no matching column → `ConditionOnlyField`); R211 fixed the diagnostic noise on the same gate. Neither addresses the cascade case, where the field carries no directive of its own and inherits from an ancestor.

## Direction

Five structural changes:

1. **Introduce `ClassifyContext` and thread it through the classifier.** `classifyInputField(field, parentTypeName, resolvedTable, expandingTypes, errors)` grows a `ClassifyContext` parameter — a small record carrying `Set<String> expandingTypes`, `boolean enclosingOverride`, and reserved space for future axes (mutation context lift, nested-input cascade walker state). Recursive descent through `NestingField` composes `ctx.expanding(typeName).withOverride(nf.condition().override())` rather than threading scalars; the existing `expandingTypes` set folds into the same shape. Plain-input call sites (`InputFieldResolver.resolve`) seed the context from the consuming `ArgumentRef`'s arg-level override; `@table` input type-build sites (`TypeBuilder.buildTableInputType`) seed with `enclosingOverride = false` (the type is classified globally; see §2 for how the call-site cascade still gets honored).

2. **Replace `ConditionOnlyField` with `InputField.UnboundField` carrying `Optional<ArgConditionRef> condition`.** One sealed variant for "this input field is not column-bound" — the defining property is the absence of a column binding, regardless of whether an explicit `@condition` is present. `Optional<ArgConditionRef>` covers both today's `@condition(override:true)`-with-no-column case (`condition.isPresent()`) and the cascade-admitted-bare-field case (`condition.isEmpty()`). The variant carries the field's facts (name, location, attempted column name for the "did you mean" hint, optional condition); the validator and consumers apply policy. The rename is load-bearing: every site currently switching on `ConditionOnlyField` becomes `UnboundField`, the Java compiler's exhaustiveness check is the safety net, and the walk forces a deliberate read of every consumer (`walkInputFieldConditions`, `MutationInputResolver`, `EnumMappingResolver`, `CatalogBuilder`, `ContextArgumentClassifier`, `TypeFetcherGenerator`'s admitted-carrier set).

3. **Defer `@table` input column-coverage to consumption.** A `@table` input field with no matching column becomes `UnboundField` at type-build rather than rejecting the whole type. `TypeBuilder.buildTableInputType` admits it; the resulting `TableInputType` is well-formed but may contain `UnboundField` entries. Consumers inspect: under override-cascade admission the field is fine; under non-override consumption it rejects with the field's own `SourceLocation`. R213's attribution work falls out by construction (rejections fire at the field's location, not the consumer's), so R213 may merge into this work once the consumer admission map is filled in.

4. **Honest classifier/consumer split.** Classification owns "*can* this field bind a column" — the variant identity (`ColumnField`, `CompositeColumnField`, `ColumnReferenceField`, `CompositeColumnReferenceField`, `UnboundField`, etc.) records the structural answer once. Consumption owns "*does* this call site emit the implicit predicate" — `walkInputFieldConditions` becomes a single exhaustive switch on the variant whose arms each consume `enclosingOverride` directly. No per-arm `cf.condition().isEmpty()` re-derivation; no admission-set policy smuggled into the variant. The cascade is irreducibly a call-site property (the same `@table` input type can be consumed by two arguments with different `enclosingOverride`), so the call-site decision stays at the call site — what the variant gains is the structural pre-resolution that lets the consumer's switch be one-axis exhaustive over `enclosingOverride × variant`.

5. **`ColumnField + override:true` → `UnboundField(condition: Some(...))`.** Today a field with `override: true` and a matching column lands on `ColumnField` (dead-storing the column; `walkInputFieldConditions:1538-1550` suppresses the implicit body param because `!enclosingOverride && cf.condition().isEmpty()` is false). The column is unused regardless of whether it exists, so the variant collapses to `UnboundField` uniformly under `override: true`. This is the design-alt-#2 restructure that R211's spec deferred (`condition-override-true-misleading-column-miss-message.md`, "Design alternatives considered" §2).

## Validator rules

Per the architect's finding (the validator-mirrors-classifier principle runs in one direction; structural prohibitions that don't gate a generator branch are validator's job), the following rules live in `GraphitronSchemaValidator` rather than the classifier:

- **`UnboundField` with `condition.isPresent() && !condition.get().override()` → reject.** A bare `@condition(override: false)` field is required to bind a column by the documented cascade rules; if it didn't bind, that's a schema author bug. The validator reads the variant and emits at the field's source location.

- **`UnboundField` with `condition.isEmpty()` consumed under non-override cascade → reject at the consumer.** This rejection happens at projection time (`projectFilters` / `MutationInputResolver`), not in the validator's whole-schema walk, because the cascade is call-site-dependent. The consumer emits at the field's source location.

- **`@condition` (any override flag) on a mutation input field → reject in the validator.** Mutations write values; a `@condition` field is filter-shape that competes with the mutation's verb-specific WHERE shape. The `@value` + `@condition` exclusivity already covers part of this; the validator extends it to all `@condition` on mutation input fields. This stays validator-side rather than classifier-side so the classifier doesn't need a mutation context plumbed in just to host one rule.

## Mutation semantics

Mutations route through `MutationInputResolver` and today don't classify fields with a filter-vs-value role; the role is inferred at emit time by walking PK columns and `@value` directives. Role is determined by `DmlKind × @value-presence × PK-membership`, of which only `@value` and PK-membership are field-local; `DmlKind` lives at the mutation argument. Stamping role on the field at classification would imply one classification per consumer, contradicting §3's deferral logic — so the role decision lives at `MutationInputResolver`'s projection, not on the carrier.

`MutationInputResolver` projects an admitted `InputField` to a sealed `MutationField`:

- **`MutationField.Value`** — non-PK column binding, `@value`-marked under R144, or non-PK default on INSERT/UPDATE SET.
- **`MutationField.Condition`** — PK column binding under DELETE/UPDATE WHERE; or `UnboundField` with `condition.isPresent() && condition.get().override()` (the "developer takes over the filter half" case, legitimate under UPDATE/DELETE).

Admission set updates:

- `ColumnField` / `CompositeColumnField` / reference variants: admitted as today.
- `UnboundField` with `condition.isPresent() && condition.get().override()`: admitted on UPDATE/DELETE only; `MutationField.Condition` projection (the developer owns the WHERE half). Rejected on INSERT (no WHERE clause for the override to bind into).
- `UnboundField` with `condition.isEmpty()`: rejected — nothing to write, nothing to filter by. The validator can also catch this if the same plain rule fires in non-mutation contexts.

The classifier produces the variant; the resolver decides what it means.

## Consumer admission map

Explicit per-consumer policy on `UnboundField`. Each admission decision rejects at the field's `SourceLocation`, not the consumer's.

| Consumer                              | Admits `UnboundField`                                          | Rejection site |
|---------------------------------------|----------------------------------------------------------------|----------------|
| `projectFilters` / `walkInputFieldConditions` | Yes when `condition.isPresent() && override`, or `enclosingOverride` is true. Otherwise rejects. | Field |
| `MutationInputResolver` (UPDATE/DELETE)| Yes when `condition.isPresent() && override` (admits as `MutationField.Condition`). Otherwise rejects. | Field |
| `MutationInputResolver` (INSERT)      | No — INSERT has no WHERE clause for an override to bind into.  | Field |
| `LookupMappingResolver`               | No — VALUES+JOIN lookup-key rows need columns; an `UnboundField` has nowhere to put a deferred slot. | Field |
| `EnumMappingResolver`                 | No — enum mapping resolves through column types; no column means no mapping. | Field |
| `ContextArgumentClassifier`           | Yes when `condition.isPresent()` — the resolver only reads `condition.filter()` to harvest context-key declarations. `condition.isEmpty()` is a no-op (no context keys to collect). | n/a |
| `CatalogBuilder`                      | Yes — records an `UnboundField` entry with no column reference. | n/a |
| `TypeFetcherGenerator`'s admitted-carrier set | Yes when the surrounding role admits it (filter-side under cascade). | n/a — caught upstream |

## Load-bearing annotation discipline

Producer-side `@LoadBearingClassifierCheck` and consumer-side `@DependsOnClassifierCheck` annotations pair up so the audit walker catches orphaned consumers. New keys:

- **`input-field.unbound-implies-no-column`** (producer: `BuildContext.classifyInputFieldInternal`; consumers: `walkInputFieldConditions`, `MutationInputResolver`, `LookupMappingResolver`, `EnumMappingResolver`, `CatalogBuilder`). The classifier guarantees that `UnboundField` is the only variant emitted with no column attached; consumers that switch on absence of a column rely on this exhaustiveness.

- **`input-field.unbound-with-override-condition-admits-on-mutation-update-delete`** (producer: `MutationInputResolver.resolveInput`; consumer: the corresponding emitter site that builds the WHERE clause from `MutationField.Condition`). The resolver guarantees that admitted `UnboundField` carries `condition.isPresent() && override` so the emitter can read `condition.get().filter()` without re-checking presence.

- **`input-field.unbound-condition-empty-rejects-at-consumer`** (producer: each admission site that rejects; consumer: the validator's whole-schema audit). Each rejection site emits at the field's source location; the validator's audit cross-checks that no `UnboundField(condition: empty)` survives into emission.

## Acceptance tests

1. **Plain input + arg-level `override:true` + non-binding field → admitted.** New pipeline test: `input Filter { foo: String }`, `things(filter: Filter @condition(..., override: true))`. Today rejects with "no column 'foo' found"; under this work `foo` classifies as `UnboundField(condition: empty)` and the schema builds (the consumer's `enclosingOverride = true` admits it).

2. **Field-level `override:true` on a non-binding field still admitted.** R210's existing test (`plainInput_overrideTrueWithoutMatchingColumn_resolvesAsConditionOnlyField`, after renaming) stays green; `foo` classifies as `UnboundField(condition: Some(override:true))`.

3. **`@table` input + non-binding field consumed by non-override arg → rejected at the field's source location.** New pipeline test: `input FilmInput @table(name: "film") { foo: String }`, `films(filter: FilmInput): [Film!]!`. Asserts the resulting `UnclassifiedField`'s rejection carries `foo`'s `SourceLocation`, not `Query.films`'s. R213's attribution work folds in here.

4. **`@table` input + non-binding field consumed by override-cascade arg → admitted.** Same `FilmInput` as (3), but the consuming arg carries `@condition(..., override: true)`. The `UnboundField` survives consumption; no rejection.

5. **Validator: `@condition(override:false)` on a non-binding plain input field → rejected at validate time with the field's source location.** New unit test against `GraphitronSchemaValidator`; asserts the `ValidationError`'s location is the `@condition` directive's location, not the consuming field's.

6. **Validator: `@condition` on a mutation input field → rejected at validate time.** New unit test; rejection message names the field and calls out the structural conflict ("@condition on a mutation input field is not supported; mutations write values").

7. **Mutation + `@condition(override:true)` on a non-PK field under UPDATE/DELETE → admitted as `MutationField.Condition`.** Lets emission consult the projected role directly instead of inferring from PK membership at emit time.

8. **Mutation + `@condition(override:true)` on a non-PK field under INSERT → rejected.** No WHERE clause for the override to bind into; the resolver's INSERT arm rejects with the field's source location.

9. **Regression: R210's existing override:true-with-matching-column tests stay green.** Under this work the variant shifts from `ColumnField + override` to `UnboundField(condition: Some(override:true))`; the consumer-side filter list (`f.filters()`) should be unchanged (one explicit `ConditionFilter`, no implicit `BodyParam` for that field). Cross-check that assertions name field counts and explicit-vs-implicit splits, not the variant class itself.

10. **Regression: nested plain inputs propagate cascade correctly.** New pipeline test: an input type containing a `NestingField` whose parent carries `@condition(override:true)`. The nested field's `UnboundField(condition: empty)` should be admitted at consumption because the cascade resolves it.

## Risks and boundary cases

- **`UnboundField` is a new sealed variant.** Every downstream sealed-switch site must add an arm. The Java compiler's exhaustiveness check is the safety net; the `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` annotation discipline (see "Load-bearing annotation discipline" above) is the durable cross-check.

- **`ColumnField + override:true` → `UnboundField` shift.** Code paths that read `cf.column()` under a presumed override condition (today's dead storage) need to switch to the `UnboundField` arm and stop reading the column. The grep set is small — `MutationInputResolver`, `LookupMappingResolver`, and `EnumMappingResolver` already filter on the variant; only `walkInputFieldConditions:1538-1550` actually reads the column today, and its `!enclosingOverride && cf.condition().isEmpty()` guard already routes around the override case.

- **`@table` inputs reachable from multiple consumers with different cascades.** Worked example: `input Filter @table(name: "film") { foo: String }` consumed by `things(filter: Filter)` (column required) AND `otherThings(filter: Filter @condition(..., override: true))` (column not required). With §3's deferral, each consumer applies its own cascade; the type-build pass produces one `TableInputType` shape both can read. This is the case the architect's Finding 2 highlighted as why §4's classifier/consumer split has to stay honest.

- **`LookupMappingResolver` and lookup-key carriers.** `InputField.LookupKeyField` (the sealed sub-interface permitting `ColumnField`/`CompositeColumnField`/reference variants) does *not* permit `UnboundField` by design — VALUES+JOIN has nowhere to put a deferred slot. A `@table` input with an `UnboundField` consumed as a lookup-key carrier rejects at `LookupMappingResolver`'s admission filter (per the consumer admission map). The rejection message must name the field, not the lookup-key argument.

- **`ClassifyContext` shape lock-in.** The record needs to be designed for forward growth: `expandingTypes`, `enclosingOverride`, and probably a future `mutationContext` axis. Define the constructors / `with*` helpers up front so adding a new axis doesn't touch every classifier call site.

## Relationship to R211 and R213

- **R211** (`condition-override-true-misleading-column-miss-message.md`, In Review at this writing) shipped a placeholder `Unresolved(lookupColumn=null)` at the field-level override:true gate to suppress the misleading "no column found" arm. Under this work the gate moves above the column lookup and the placeholder becomes unreachable. R211 stays as a safety-net regression guard (its `doesNotContain("no column 'sakskode' found")` assertion still pins the right output shape); the placeholder code can be removed when this lands.

- **R213** (`input-field-rejection-attribution.md`, Backlog) captured the broader problem that plain-input field rejections attribute to the consuming field rather than the input field's source location. Direction §3 here (deferring `@table` column-coverage to consumption) makes attribution natural: rejections fire at the field's location by construction. R213 merges into this work if (a) the consumer admission map's per-site rejection sites all emit at the field's source location, and (b) the validator's `@condition(override:false)` rejection (§Validator rules) also emits at the directive's location. Both are explicit in this spec; expect R213 to fold rather than stay as a sibling.

## Shipped scope (In Review)

The core structural change shipped: `ClassifyContext` threaded through `BuildContext.classifyInputField`, `InputField.UnboundField` (renamed from `ConditionOnlyField`) with `Optional<ArgConditionRef> condition` and `attemptedColumnName` slot, classifier emits `UnboundField` uniformly on column-miss (across plain and `@table` inputs) and on `@condition(override: true)`-with-matching-column (§5 collapse). `@table` input column-coverage deferred to consumption (`TypeBuilder.buildTableInputType` admits `UnboundField` instead of rejecting the whole type). `FieldBuilder.walkInputFieldConditions` is a single exhaustive switch with the `UnboundField` arm consuming `enclosingOverride` directly and emitting a consumer-side rejection (typed `Rejection.AuthorError.UnknownName` with Levenshtein hint) when the cascade doesn't admit. Validator-side rule for `@condition(override: false)` on `UnboundField` fires at the field's source location via a new input-field walk inside `validateTableInputType`. `MutationInputResolver` admits `UnboundField(condition: present, override: true)` on UPDATE / DELETE and rejects on INSERT; the resolver also rejects `@condition(override: false)` on mutation input fields at SDL-walk time. Load-bearing classifier-check annotations carry the new keys (`input-field.unbound-implies-no-column`, `input-field.unbound-with-override-condition-admits-on-mutation-update-delete`).

Coverage: eight new R215 acceptance tests (`r215_plainInputArgLevelOverrideAdmitsNonBindingField`, `r215_tableInputNonBindingFieldRejectsAtConsumer`, `r215_tableInputNonBindingFieldAdmittedUnderOverrideCascade`, `r215_validatorRejectsOverrideFalseOnNonBindingField`, `r215_validatorRejectsConditionOverrideFalseOnMutationInputField`, `r215_mutationUpdateConditionOverrideTrueOnNonPkFieldAdmits`, `r215_mutationInsertConditionOverrideTrueRejects`, `r215_nestedPlainInputPropagatesCascade`); R210's renamed `plainInput_overrideTrueWithoutMatchingColumn_classifiesAsUnboundField` + `tableInput_overrideTrueWithoutMatchingColumn_classifiesAsUnboundField` stay green; six existing tests that asserted the pre-R215 rejection shape (`EXPLICIT_TABLE_UNRESOLVED_COLUMN`, `NESTED_INPUT_FIELD_UNKNOWN_COLUMN`, `NodeIdPipelineTest.InputCase.{ACCESSOR_MISSING, LIST_VARIANT}`) updated to assert the new admit-at-type-build behaviour. Build green: full `mvn install` across all 11 modules; 1905 tests pass.

**Late-round patch (after architect self-check):**

The first-pass `walkInputFieldConditions` UnboundField arm silently dropped the inner `@condition` when an outer `@condition(override:true)` cascade resolved the field; alf flagged this against the documented cascade contract (`docs/manual/how-to/migrating-from-legacy.adoc#behavior-divergence-condition-cascade`: "every `@condition` you write produces SQL; the override flag controls only the *implicit* column predicate"). The arm now mirrors the `ColumnField` arm structure: always emit the explicit `@condition` when present, then decide consumer-side rejection separately. Reject at the consumer outside the cascade for both `condition.isEmpty()` (nothing to filter by) and `condition.isPresent() && !override()` (structurally malformed shape) — the second arm is the validator's job per spec, but the validator's plain-input walk is the R221 follow-up below. Inside the cascade the inner `@condition` fires per the doc contract. New acceptance test #11 (`r215_innerExplicitConditionFiresOnUnboundFieldUnderOverrideCascade`) pins the cascade-doc contract; the three R205 Path B regression tests (`PLAIN_INPUT_ARG_FIELD_CONDITION_EMITTED`, `plainInput_unresolvedFieldWithCondition_rejectsAsUnclassifiedFieldWithUnknownName`, `plainInput_overrideFalseWithoutMatchingColumn_stillRejectsAsUnclassifiedField`) keep their pre-R215 rejection assertions green via the consumer-arm safety net.

**Deferred to follow-up (carved out from R215 final scope):**

- **`MutationField.{Value, Condition}` sealed projection.** Spec §Mutation semantics described a new sealed type produced by `MutationInputResolver` for downstream DML emitters to read instead of inferring role at emit time. The acceptance behaviour shipped (admit on UPDATE / DELETE, reject on INSERT) lives at the resolver's per-field admission loop; the structural lift to a sealed `MutationField` projection that emitters consume directly is a follow-up roadmap item. Acceptance tests 7 and 8 pass against the resolver's admit/reject outcomes; no downstream code consumes `MutationField` yet.
- **R213 exact-location attribution for consumer-side `UnboundField` rejections.** The spec called out that rejections should fire at the field's `SourceLocation`, not the consumer's. The consumer-side rejection message now names the field as `<containerSummary>: input field '<name>'` and the validator-side `@condition(override:false)` rejection does carry the field's location (via `ValidationError.location`). But the surrounding `UnclassifiedField`'s `location` field still points at the consuming query field. Threading the field-level source location through `walkInputFieldConditions` → `projectFilters` → `projectForFilter` → `TableFieldComponents.Rejected` would require a parallel `SourceLocation` channel alongside `List<Rejection>`; deferred as an R213 follow-up. The rejection prose already names the field, so log readers can locate it; the LSP attribution improves when R213's plumbing lands.
- **R221: Validator walks `PlainInputArg.fields()` for `UnboundField` rejection.** Spec §Validator rules promised that `UnboundField + @condition(override:false)` rejects at validate time at the directive's source location regardless of where the field lives. The shipped validator walks `TableInputType.inputFields()` (via the new `validateTableInputType` recursion) which covers `@table` inputs and promoted-plain inputs. Truly plain input types (`GraphitronType.InputType` permits — `JavaRecordInputType`, etc.) don't carry classified `InputField` records on the type itself; their fields classify at consumer time on `PlainInputArg.fields()`, which the validator's whole-schema walk has no view into. The consumer-arm late-round patch (above) acts as a safety net for plain inputs by rejecting the malformed shape outside the cascade; R221 lifts this into the validator so the cascade case also rejects (today's consumer admits-and-emits the explicit `@condition` inside a cascade, which honors the doc contract at runtime but lets a malformed schema build).

## References

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java:1538-1572` — `classifyInputField` / `classifyInputFieldInternal`; gains a `ClassifyContext` parameter.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java:1780-1820` — R210/R211 gate; subsumed by the restructure (the gate moves above the column lookup, fires unconditionally on override:true, produces `UnboundField`).
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/InputFieldResolver.java:60-97` — plain-input classification call site; seeds `ClassifyContext` with the consuming `ArgumentRef`'s override flag.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/TypeBuilder.java:1031-1059` — `@table` input type-build; column-coverage rejection moves to consumption; admits `UnboundField`.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java:1340-1374` — consumer-side `enclosingOverride` derivation; stays where it is (cascade is call-site).
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java:1528-1602` — `walkInputFieldConditions`; simplifies to a single exhaustive switch on variant, with each arm consuming `enclosingOverride` directly.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/MutationInputResolver.java:438-465` — mutation admission; projects to `MutationField.{Condition | Value}`.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java` — gains validator rules for `UnboundField + override:false` and `@condition` on mutation input fields.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/NodeIdLeafResolver.java` — `Resolved.Rejected` precedent for deferred typed rejection; the pattern `UnboundField + consumer admission` mirrors.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/InputField.java` — sealed hierarchy: drop `ConditionOnlyField`, introduce `UnboundField(parentTypeName, name, location, typeName, nonNull, list, Optional<ArgConditionRef> condition)`.
- `docs/manual/how-to/migrating-from-legacy.adoc` (`#behavior-divergence-condition-cascade`) — the documented cascade semantics the classifier currently fails to honor.
- R210 (`condition-override-true-column-not-required.md`) — introduced the field-level override:true gate; subsumed.
- R211 (`condition-override-true-misleading-column-miss-message.md`) — placeholder `Unresolved` at the gate; redundant under this restructure (kept as a regression guard via its tightened test).
- R213 (`input-field-rejection-attribution.md`) — attribution fix; expected to fold into this work via §3's consumption-time rejection at field source locations.
- principles-architect review (May 2026 session) — eight findings; this revision folds all eight (Finding 1: `UnboundField` shape refined to subsume `ConditionOnlyField` via `Optional<ArgConditionRef>` per alf's call; Finding 2: classifier/consumer split kept honest in §4; Finding 3: `ClassifyContext` adopted in §1; Finding 4: `@condition(override:false)` rejection moved to validator; Finding 5: `MutationRole` projection lifted to `MutationInputResolver`; Finding 6: mutation admission split into two facts; Finding 7: load-bearing annotation keys enumerated; Finding 8: explicit consumer admission map).
- Surfaced by alf's production `opptak-subgraph` schema and the R211 discussion; the cascade-vs-classifier mismatch is the root condition R211 patched at one site.
