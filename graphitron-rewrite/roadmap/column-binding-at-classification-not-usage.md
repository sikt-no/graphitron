---
id: R214
title: Column-binding requirement captured at classification, not derived at usage
status: Spec
bucket: architecture
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# Column-binding requirement captured at classification, not derived at usage

Today the input-field classifier (`BuildContext.classifyInputFieldInternal`) decides whether a field requires column binding by consulting only the field's own `@condition` directive. It has no awareness of the enclosing override cascade. Consumer-side logic (`FieldBuilder.walkInputFieldConditions`) honors the cascade at predicate-emission time, but by then the classifier has already rejected schemas whose fields would never have needed a column. The classifier's view and the consumer's view of "is this field required to bind a column?" disagree, and the classifier's stricter view wins by firing first. This work moves the cascade-resolved column-binding decision onto the field at classification, so each `InputField` variant carries its own authoritative answer and consumers read that answer rather than re-deriving it.

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

Three structural changes, in order:

1. **Thread `enclosingOverride` into the classifier.** `classifyInputField(field, parentTypeName, resolvedTable, expandingTypes, errors)` grows an `enclosingOverride` parameter (`boolean`, or a small `ClassifyContext` record if more cascade state surfaces later). `InputFieldResolver.resolve` (plain inputs, classified per call site) and `TypeBuilder.buildTableInputType` (`@table` inputs, classified once at type-build) pass what they know. For plain inputs that's the arg-level override flag from the consuming `ArgumentRef` — direct. For `@table` inputs the flag is unknowable at type-build (the type can have multiple consumers with different cascades); see (2).

2. **Defer `@table` input column-coverage to consumption.** A `@table` input field with no matching column becomes a new `InputField.UnboundField` variant carrying its name, location, the attempted column name (for the "did you mean" hint), and the field's `@condition` directive shape (if any). `TypeBuilder.buildTableInputType` admits it without rejecting; the resulting `TableInputType` is well-formed but contains one or more `UnboundField`s. Consumers (`projectFilters`, `MutationInputResolver`) inspect: if the cascade resolves the field as override-suppressed, fine; otherwise emit the rejection at the field's source location. The validator's rejection point moves from "type build" to "field use", so `R213`'s attribution fix falls out by construction (the rejection lands on the field, not on the consumer).

3. **Capture predicate-emission shape on the classified field.** With cascade context in hand, the classifier resolves once whether each field emits an implicit predicate, an explicit `@condition` predicate, both, or neither, and writes the answer into the variant choice itself. `walkInputFieldConditions`'s `!enclosingOverride && cf.condition().isEmpty()` check (`FieldBuilder.java:1538-1550` and parallel arms) becomes a single switch on the field variant: `ColumnField` → emit implicit + optional explicit; `ConditionOnlyField` → emit explicit only; `UnboundField` → emit nothing (under override cascade) or trigger rejection (otherwise). Consumers walk and emit; no re-derivation.

The `InputField` sealed hierarchy gains `UnboundField` and the existing `ColumnField` / `ConditionOnlyField` admission domains shift: today a field with `override: true` and a matching column lands on `ColumnField` (dead-storing the column); under this work it lands on `ConditionOnlyField` uniformly, because override:true means the column is unused regardless of whether it exists. This is the design-alt-#2 restructure that R211's spec deferred (`condition-override-true-misleading-column-miss-message.md`, "Design alternatives considered" §2).

## Mutation semantics

Mutations route through `MutationInputResolver` and today don't classify fields with a filter-vs-value role; the role is inferred at emit time by walking PK columns and `@value` directives. With cascade-aware classification we can capture role on the field itself, per the same principle:

- **PK-bound column** → mutation role `Condition` (UPDATE/DELETE WHERE side).
- **Non-PK-bound column, no `@value`** → ambiguous today; R144 leans on `@value` presence to disambiguate. Under this work, default to `Value` for INSERT/UPDATE SET; reject on DELETE (where non-PK columns have no meaningful binding).
- **`@condition(override: true)` on any field** → role `Condition` (the explicit method owns the predicate). No column binding required, no `@value` permitted.
- **`@condition(override: false)` on a mutation input field** → reject at classification. Mutations write values; a bare `@condition` field is a filter that competes with the mutation's verb-specific WHERE shape (PK columns drive DELETE/UPDATE WHERE). The `@value` + `@condition` exclusivity already covers part of this; extend to all `@condition` on mutation input fields. Bug-fix-shaped, not feature.

This part of the work depends on (1)-(3) above for the cascade plumbing, then adds a `MutationRole` slot on the relevant `InputField` variants populated when classification sees a mutation context. The `MutationInputResolver` admission set (`MutationInputResolver.java:438-465`) reads the role rather than walking PK columns at admission.

## Acceptance tests

1. **Plain input + arg-level `override:true` + non-binding field → admitted.** New pipeline test: `input Filter { foo: String }`, `things(filter: Filter @condition(..., override: true))`. Today rejects with "no column 'foo' found"; under this work `foo` classifies as `ConditionOnlyField` (or `UnboundField` admitted by the cascade) and the schema builds.

2. **Field-level `override:true` on a non-binding field still admitted.** R210's existing test `plainInput_overrideTrueWithoutMatchingColumn_resolvesAsConditionOnlyField` stays green; the classification path it uses is the same one (1) takes after cascade-threading.

3. **`@table` input + non-binding field consumed by non-override arg → rejected at the field's source location.** New pipeline test: `input FilmInput @table(name: "film") { foo: String }`, `films(filter: FilmInput): [Film!]!`. Asserts the resulting `UnclassifiedField`'s rejection carries `foo`'s source location, not `Query.films`'s. R213's attribution work folds into this.

4. **`@table` input + non-binding field consumed by override-cascade arg → admitted.** Same `FilmInput` as (3), but the consuming arg carries `@condition(..., override: true)`. The `UnboundField` survives consumption; no rejection.

5. **Mutation input + bare `@condition(override:false)` on any field → rejected at classification.** New pipeline test asserts `UnclassifiedField` with rejection naming the offending field, prose calling out the structural conflict ("@condition on a mutation input field is not supported; mutations write values").

6. **Mutation input + `@condition(override:true)` on a non-PK field → admitted as `Condition` role.** Lets emission consult `MutationRole` directly instead of inferring from PK membership at emit time.

7. **Regression: R210's existing override:true-with-matching-column tests (TableInputType and plain-input) stay green.** Under this work the variant shifts from `ColumnField + override` to `ConditionOnlyField`; the consumer-side filter list (`f.filters()`) should be unchanged (one explicit `ConditionFilter`, no implicit `BodyParam` for that field), because `walkInputFieldConditions`'s `ColumnField` arm and `ConditionOnlyField` arm both currently produce that shape under override. Cross-check the assertions name field counts and explicit-vs-implicit splits, not the variant class itself.

## Risks and boundary cases

- **`InputField.UnboundField` is a new admission shape.** Downstream consumers (`CatalogBuilder`, `EnumMappingResolver`, `ContextArgumentClassifier`, `MutationInputResolver`, `walkInputFieldConditions`) all need an explicit arm for it. Missing one is a silent emit-time bug; the sealed-switch exhaustiveness check should catch all of them, but `@DependsOnClassifierCheck` annotations on the new variant's admissions are the durable guard.

- **`ColumnField + override` → `ConditionOnlyField` shift breaks `MutationInputResolver`'s admission set.** Today `ColumnField` admits on mutation; `ConditionOnlyField` would not. A `@mutation @table` input field with `@condition(override:true)` and a matching column flips from admitted to rejected. Per the mutation semantics section above, that's the desired behavior — but call it out in the migration notes.

- **Cascade context for nested plain inputs.** `NestingField` propagates override in `walkInputFieldConditions` (`FieldBuilder.java:1568-1572`). The classifier needs the same recursive propagation when descending into a nested input type. The `expandingTypes` set is already threaded; `enclosingOverride` extends along the same path.

- **`@table` inputs reachable from multiple consumers with different cascades.** Worked example: `input Filter @table(name: "film") { foo: String }` consumed by `things(filter: Filter)` (column required) AND `otherThings(filter: Filter @condition(..., override: true))` (column not required). With deferred consumption-time rejection (direction §2), each consumer makes its own call; the type-build pass produces one well-formed `TableInputType` shape both can read.

## Relationship to R211 and R213

- **R211** (`condition-override-true-misleading-column-miss-message.md`, In Review at this writing) shipped a placeholder `Unresolved(lookupColumn=null)` at the field-level override:true gate to suppress the misleading "no column found" arm. Under this work the gate moves above the column lookup and the placeholder becomes unreachable. R211 stays as a safety-net regression guard (its `doesNotContain("no column 'sakskode' found")` assertion still pins the right output shape); the placeholder code can be removed when the gate restructure lands.

- **R213** (`input-field-rejection-attribution.md`, Backlog) captured the broader problem that plain-input field rejections attribute to the consuming field rather than the input field's source location. Direction §2 here (deferring `@table` column-coverage to consumption) makes attribution natural: rejections fire at the field's location by construction. R213 may merge into this work (one item lands both), or stay as a sibling if the classifier-side rejection (override:false on a non-binding plain input field) still needs separate attribution work.

## References

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java:1538-1572` — `classifyInputField` / `classifyInputFieldInternal`; missing `enclosingOverride` parameter.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java:1780-1820` — R210/R211 gate; subsumed by the restructure (the gate moves above the column lookup, fires unconditionally on override:true).
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/InputFieldResolver.java:60-97` — plain-input classification call site; passes arg-level override into the new parameter.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/TypeBuilder.java:1031-1059` — `@table` input type-build; column-coverage rejection moves to consumption.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java:1340-1374` — consumer-side `enclosingOverride` derivation; folds into the field variant choice.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java:1528-1602` — `walkInputFieldConditions`; simplifies to a switch on variant after classification owns the predicate-emission decision.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/MutationInputResolver.java:438-465` — mutation admission; reads `MutationRole` after this work.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/InputField.java` — sealed hierarchy gains `UnboundField`; existing variants may grow a `MutationRole` slot.
- `docs/manual/how-to/migrating-from-legacy.adoc` (`#behavior-divergence-condition-cascade`) — the documented cascade semantics the classifier currently fails to honor.
- R210 (`condition-override-true-column-not-required.md`) — introduced the field-level override:true gate; subsumed.
- R211 (`condition-override-true-misleading-column-miss-message.md`) — placeholder `Unresolved` at the gate; redundant under this restructure (kept as a regression guard via its tightened test).
- R213 (`input-field-rejection-attribution.md`) — attribution fix; folds into direction §2.
- Surfaced by alf's production `opptak-subgraph` schema and the R211 discussion; the cascade-vs-classifier mismatch is the root condition R211 patched at one site.
