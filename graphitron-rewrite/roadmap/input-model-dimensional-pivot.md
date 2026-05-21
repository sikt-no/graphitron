---
id: R222
title: "Input model: dimensional pivot under visitor-driven classification"
status: Spec
bucket: structural
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# Input model: dimensional pivot under visitor-driven classification

The input-type classification surface has been the most-churned model area in the rewrite over the last quarter: R94, R96, R150, R155, R178, R191, R205, R210, R211, R215 all landed surgical patches on it, and Backlog still carries R171, R97, R213, R209, R200, R195, R98, R220, R193, R172 on the same surface. R164 (`field-model-two-axis-pivot`) names the underlying disease one layer over: a sealed hierarchy that tries to encode a multi-dimensional space as a one-dimensional permit set. Input types are the same disease in a smaller organ; this item performs the pivot on inputs first, as the proof-of-concept for R164 and as the natural home for an R166-style visitor walk on the smaller surface.

## What is

`GraphitronType` permits two sibling input-like roots: `InputType` (four leaves at `GraphitronType.java:323-385`) and `TableInputType` (one record at `GraphitronType.java:400-407`). The five permits today encode three independent axes onto one identity slot:

| Permit | Backing-class kind | Table-bound? | Eager column classification? |
|---|---|---|---|
| `PojoInputType` | POJO (or null) | no | no |
| `JavaRecordInputType` | Java record | no | no |
| `JooqRecordInputType` | jOOQ `Record<?>` | no | no |
| `JooqTableRecordInputType` | jOOQ `TableRecord<?>` | yes — from backing class | no |
| `TableInputType` | (graphitron-emitted from R94) | yes — from `@table` directive **or** consumer chain | yes — `List<InputField>` |

Three axes — *backing-class kind*, *table-binding (and where the binding came from)*, *whether fields are eagerly column-classified* — collapsed onto five permit identities. The cross product is sparse and lopsided:

- The "table-bound × non-jOOQ-TableRecord-backed × eager-classified" cell is `TableInputType`.
- The "table-bound × jOOQ-TableRecord-backed" cell is `JooqTableRecordInputType`, and **the table comes from a different source** (reflection on the backing class vs `@table` directive vs consumer inference).
- The "unbound" row has four permits that differ only on backing-class kind.

Two further signals point at the encoding being the wrong shape:

1. `TypeBuilder.buildInputType` (`TypeBuilder.java:1000-1026`) already classifies a directiveless input as `TableInputType` when exactly one consumer's return type is a `@table` type, via `findReturnTablesForInput` (an O(N) back-scan over all schema fields). Whether an SDL input is "table-bound" is *already* a property of the consumer chain, not the input declaration — encoded as a back-scan that runs once per input rather than once per use.

2. R215's lift (In Review) admits `InputField.UnboundField` into `TableInputType.inputFields()`, so the column-coverage invariant that previously distinguished `TableInputType` from `InputType` (one classifies columns eagerly, the other doesn't) has eroded. The classifier-side commitment is now "best-effort eager, deferred to consumption on miss" — which collapses the third axis.

The capability marker `HasInputRecordShape` (`HasInputRecordShape.java:17-22`) already has to list five permits to span both branches; R171 (Backlog) names this as the immediate symptom and proposes a `sealed interface InputLikeType` parent. That fix is structural and leaves the cross-product encoding intact.

Eight consumer sites discriminate on the split today:

- `GraphitronSchemaValidator.java:80-81` — case arms on `InputType` and `TableInputType`.
- `MutationInputResolver.java:368` — `instanceof GraphitronType.TableInputType` gate.
- `EnumMappingResolver.buildLookupBindings(GraphitronType.TableInputType, ...)` (`EnumMappingResolver.java:303`).
- `CatalogBuilder.java:206 / 498 / 570 / 639` — four switch sites on `TableInputType`.
- `FieldBuilder.java:967` — `instanceof GraphitronType.TableInputType` arm in `classifyInputFieldOnArg`.
- `TypeBuilder.java:209-210` — the type-pivot in the synthesis pass.

Each consumer asks "does the input have a table?" by switching on the permit identity. None of them care about the backing-class kind on the unbound side.

## What's to be: two-level dimensional model

The pivot separates the *consumer-independent* surface (a fact about the SDL input declaration) from the *consumer-dependent* surface (a fact about how the input is used at a particular call site). Two records, one per level, with one slot per dimension.

```java
sealed interface Input extends GraphitronType, EmitsPerTypeFile permits Input.Of {

    InputRecordShape recordShape();          // R94 — always present
    Optional<BackingClass> backingClass();   // axis 1
    GraphQLInputObjectType schemaType();
}

@LoadBearingClassifierCheck(
    key = "input.record-shape-derived-from-backing",
    guarantees = "Input.recordShape() is consistent with Input.backingClass(): a JavaRecord "
        + "backing implies a recordShape sourced from the record's canonical components; a Pojo "
        + "backing implies a recordShape sourced from JavaBean setters; absent backing implies "
        + "a recordShape sourced from SDL fields. The classifier is the single producer.")
record Of(
    String name,
    SourceLocation location,
    GraphQLInputObjectType schemaType,
    InputRecordShape recordShape,
    Optional<BackingClass> backingClass
) implements Input {
    public Of {
        // Spec'd compact-constructor invariant: recordShape and backingClass cannot drift.
        // The validator audit (LoadBearingGuaranteeAuditTest) pins this key against
        // InputRecordGenerator + the per-arg materialiser as the load-bearing consumers.
    }
}

sealed interface BackingClass {
    String fqClassName();
}
record Pojo(String fqClassName) implements BackingClass {}
record JavaRecord(String fqClassName) implements BackingClass {}
record JooqRecord(String fqClassName) implements BackingClass {}
record JooqTableRecord(String fqClassName, TableRef table) implements BackingClass {}
```

The `Input` record carries only what's true of the SDL declaration in isolation: name, location, the assembled-schema form, the per-input-type emit shape (R94's `InputRecordShape`), and the backing class (when one is named or reflected). `BackingClass` is a four-arm sealed family that carries what the four `*InputType` permits used to encode in their identity. The classifier-side invariant linking `recordShape` to `backingClass` is named explicitly (key `input.record-shape-derived-from-backing`) so consumers cannot drift the two slots apart and `LoadBearingGuaranteeAuditTest` enforces the producer-consumer chain.

```java
record InputTypeArg(
    String name,
    String typeName,
    boolean nonNull,
    boolean list,
    Optional<TableBinding> binding,
    List<InputField> classifiedFields,
    Optional<ArgConditionRef> argCondition,
    SchemaCoordinate consumer,             // visit-site coordinate; LSP hover + diagnostics
    // Mutation-only partitions, R144:
    List<InputField.LookupKeyField> lookupKeyFields,
    List<InputField.SetField> setFields
) implements ArgumentRef {
    public InputTypeArg {
        // Cross-axis invariant pinned for the duration of the pivot. Key:
        //   input-arg.binding-absent-implies-no-classified-fields
        // The adapter shims in Phase 1-2 preserve this; Phase 3's visitor produces it
        // by construction. The validator audit pins it against MutationInputResolver,
        // EnumMappingResolver, FieldBuilder.classifyInputFieldOnArg, GraphitronSchemaValidator,
        // and the four CatalogBuilder sites as load-bearing consumers.
        if (binding.isEmpty() && !classifiedFields.isEmpty()) {
            throw new IllegalStateException(
                "classifiedFields populated without a TableBinding at " + consumer);
        }
    }
}

record TableBinding(TableRef table, Optional<SourceLocation> directiveLocation) {}
```

`InputTypeArg` carries what's true of *this argument occurrence* of the input: whether it's table-bound here (it may be at one call site and not at another), and the column-classified fields that fall out when it is. The `consumer` slot is the visiting coordinate (e.g. `Query.films`), surfaced for LSP hover and diagnostics; carrying it on the arg itself instead of on `TableBinding` keeps the binding's shape minimal.

`TableBinding` carries the resolved `TableRef` plus an `Optional<SourceLocation>` for the originating `@table` directive when one drove the resolution. Absent location means the binding was consumer-derived or backing-class-derived — the distinction is recoverable from `arg.input().backingClass() instanceof JooqTableRecord` if any consumer ever needs it; the architectural review's read is that no current consumer does, so the binding type stays narrow.

### What collapses

| Pre-pivot | Post-pivot |
|---|---|
| `GraphitronType.InputType` 4-arm sealed root | `Input` with `Optional<BackingClass>` slot; 4 arms move onto `BackingClass` |
| `GraphitronType.TableInputType` sibling root | `Input` + `InputTypeArg.binding` populated at each consumer position |
| `JooqTableRecordInputType.table` slot | `BackingClass.JooqTableRecord.table` (the reflected-from-class path) |
| `TableInputType.table` slot | `InputTypeArg.binding().get().table()` (the directive / consumer-derived path; directive `SourceLocation` carried on the binding when present) |
| `TableInputType.inputFields` | `InputTypeArg.classifiedFields` |
| `HasInputRecordShape` capability marker (5 permits) | direct slot on `Input` |
| `ArgumentRef.InputTypeArg.TableInputArg` | merged into `InputTypeArg` |
| `ArgumentRef.InputTypeArg.PlainInputArg` | merged into `InputTypeArg` (with `binding = empty`) |
| 8 `instanceof TableInputType` discriminations | `arg.binding().isPresent()` at the consumption point |
| `TypeBuilder.findReturnTablesForInput` back-scan | resolved at the visit site under R166 walk |

The cross-cutting `GraphitronSchemaValidator.validateTableInputType` arm becomes a `walkInputs` pass that visits every `InputTypeArg` and dispatches on `binding` presence; the per-permit-identity dispatch retires.

## Visitor-driven classification (R166 absorption for inputs)

Today's classifier is iteration-driven: `TypeBuilder` walks `schema.getAllTypesAsList()` once and classifies each `INPUT_OBJECT` in isolation. The table-binding decision is made per-input-type via the `findReturnTablesForInput` back-scan, which scans every schema field asking "who returns a `@table` type and consumes this input?" The decision is the wrong shape for two reasons: it's O(N) per input rather than O(1) at the use site, and it collapses divergent consumers (the `tables.size() > 1` arm at `TypeBuilder.java:1022` demotes the input to `InputType` even when each consumer has a coherent local table).

Under the pivot, classification is driven by `graphql.schema.SchemaTraverser.depthFirst(visitor, roots)` seeded with the consumer-reachable surface (root operation types). The visitor visits each field with its arguments in scope: when it reaches `Query.films(filter: FilmConditionInput!)`, the *visiting context* already carries the consumer's return type (`[Film @table]`). The `InputTypeArg` for `filter` is produced at the visit site, with `TableBinding` resolved from the enclosing field's return type plus the input's own directives. No back-scan.

Five consequences fall out structurally:

1. **Consumer-derived table resolution is local.** R97 Phase 2 (switch from directive-driven to consumer-derived) becomes the default, not a flag flip; the `InputTypeArg.consumer` slot carries the coordinate that drove the inference for diagnostics and LSP hover. R97's Phase 1 (`argMapping` grouping) and Phase 3 (narrow directive scope, fixture migration) stay as follow-up work.
2. **Cross-consumer reuse works correctly.** `FilmConditionInput` used by two queries returning different tables produces two `InputTypeArg` carriers, each with its own `TableBinding`. The `tables.size() > 1` collapse retires.
3. **R213 dissolves.** Plain-input field rejections currently lose source location because the classifier is detached from the use site. The visitor sits *at* the input field when classifying it; the `SourceLocation` it carries is the input field, not the consumer.
4. **Reachability falls out.** An SDL input never referenced from any reachable consumer produces no `InputTypeArg`. The `Input` record still exists for the per-type emit, but per-arg classification is gated on visit. Subset of R166 Phase 1's reachability discipline.
5. **R209 re-homes.** The typed-rejection lift `FieldRegistry.classifyInput` defers today (because `InputFieldResolution.Unresolved` doesn't carry a typed `Rejection` payload) becomes structurally trivial: column-miss is detected at the per-arg visit site, where the `TableRef` and the candidate-column list are both in scope. The `RejectionKind.AUTHOR_ERROR` default arm at `FieldRegistry.java:108-110` retires; typed `Rejection.AuthorError.UnknownName` with Levenshtein hint is the only path.

## What this absorbs from the open roadmap

| Item | Absorption mode |
|---|---|
| R171 (sealed `InputLikeType` parent) | Dissolves — capability declarations land on `Input` directly; the parent-root fix becomes moot. |
| R97 (deprecate `@table` on input types) | Phase 2 falls out structurally. `argMapping` grouping (Phase 1) and directive-scope narrowing (Phase 3) remain as follow-up. |
| R213 (rejections at consumer field) | Visit-time source location. |
| R209 (FieldRegistry classify-input trace) | Typed rejection lift becomes trivial at the per-arg visit site. |
| R166 Phase 2 (`InputTypeGenerator` under visitor) | Subset — the input-side slice of R166's broader visitor-driven emission. |
| R221 (validator walks `PlainInputArg.fields()` for `UnboundField` rejection) | Dissolves — `GraphitronSchemaValidator.validateTableInputType` becomes `validateInputArg` (Phase 4) that walks every `InputTypeArg.classifiedFields` uniformly. The `TableInputType.inputFields()` vs `PlainInputArg.fields()` split that motivated R221 retires under the pivot's unified `InputTypeArg`. |

Items adjacent but not absorbed:

- **R220 / R193** (`ServiceCatalog` predicate consolidation, sealed `UnresolvedParam`) — same disease (one-dimensional encoding of multi-dimensional space), different file. The pivot primes the pattern; those items apply it on the consumer-side surface independently.
- **R164** — this item proves out the dimensional pattern on the smaller input surface. R164 then applies the pattern to the larger field hierarchy with the precedent already in tree.
- **R98** (multi-source input validation) — once `HasInputRecordShape` becomes a direct slot on `Input`, attaching `ConstraintSet` to it is a one-site change. Lands as a follow-up.
- **R200 / R195** (honor `@field(name:)` in `InputBeanResolver`) — these are about *naming binding* between SDL fields and Java members on the backing class. The pivot doesn't change naming resolution; both items stay in scope on `InputBeanResolver`.

## Cross-axis invariants

The dimensional split admits states the old model could not. Three load-bearing invariants pin those states explicitly, each carried as a `@LoadBearingClassifierCheck` key whose producer is the visitor (Phase 3 onward) and whose consumers are the per-arg emitters:

1. **`input-arg.binding-absent-implies-no-classified-fields`** — `InputTypeArg.binding.isEmpty() ⇒ classifiedFields.isEmpty()`. Pinned as a compact-constructor invariant on `InputTypeArg` from Phase 1 onward so the Phase 1-2 adapter cannot construct illegal states.
2. **`input.record-shape-derived-from-backing`** — `Input.recordShape()` and `Input.backingClass()` cannot drift. Producer: classifier; consumers: `InputRecordGenerator`, the per-arg materialiser.
3. **`input.per-type-emit-ignores-per-arg-classification`** — `InputRecordGenerator` and `InputTypeGenerator` read only `Input` (never `InputTypeArg`); per-arg consumers read only `InputTypeArg` (never the consumer-position-dependent classification cached on an `Input`). The reachability degenerate-state (an `Input` with no `InputTypeArg` occurrences emits per-type but has no classified consumption) is consistent by construction once this key holds. Pinned with `@DependsOnClassifierCheck` on the two per-type generator entry points.

The Phase 1-2 adapter shims must satisfy all three keys as part of their "lossless round-trip" criterion; otherwise the legacy validator's invariants are bypassed by callers that construct the new types directly. The audit walker (`LoadBearingGuaranteeAuditTest`) is the safety net.

## Phasing

Five phases, each independently shippable and individually reversible.

### Phase 1: introduce `Input` and `BackingClass` alongside existing permits

- Add `sealed interface Input` and the `BackingClass` family to `model/`.
- Implement an adapter that constructs an `Input` from each of the five existing permits (lossless round-trip).
- Add a `Map<String, Input>` slot on `GraphitronSchema` populated from the existing types map.
- No consumer migrates yet; the adapter is the bridge.

Acceptance: every classified input today produces an equivalent `Input` via the adapter; no behaviour change.

### Phase 2: introduce `InputTypeArg` with dimensional axes

- Add the unified `InputTypeArg` record + `TableBinding` slot.
- Adapter from existing `TableInputArg` / `PlainInputArg` → new `InputTypeArg`.
- The compact-constructor invariant `input-arg.binding-absent-implies-no-classified-fields` (see Cross-axis invariants) lands here; the adapter is the producer for Phase 1-2 and must satisfy it.
- New record sits alongside the existing arg types; no consumer migrates yet.

Acceptance: every classified arg today produces an equivalent `InputTypeArg` via the adapter; the compact-constructor invariant holds for every adapter-produced instance.

### Phase 3: visitor-driven per-arg classification

- Add `GraphQLSchemaVisitor`-based walker seeded from root operations.
- At each `argument` visit on an `INPUT_OBJECT`-typed arg, produce the `InputTypeArg` directly — resolving `TableBinding` from (a) the input's `@table` directive when present (carries the directive's `SourceLocation`); (b) the input's `BackingClass.JooqTableRecord.table` when the backing class supplies one; (c) the enclosing field's return type when neither of the above applies and the consumer's return type is `@table`-bound (consumer-derived inference). When none of the three apply, `binding` is empty and `classifiedFields` is empty.
- Retire `TypeBuilder.findReturnTablesForInput`. Per-input-type table classification disappears; per-arg replaces it.
- **Annotation continuity:** migrate R215's two `@LoadBearingClassifierCheck` keys (`input-field.unbound-implies-no-column`, `input-field.unbound-with-override-condition-admits-on-mutation-update-delete`) from the legacy `BuildContext.classifyInputFieldInternal` producer-site to the new visitor entry point. Run `LoadBearingGuaranteeAuditTest` before and after to confirm no orphaned consumers. Same for the three new keys introduced under Cross-axis invariants.
- Wire R97 Phase 2's consumer-derived inference here. Test: cross-consumer divergence fixture (one input used by two consumers with different tables) classifies successfully per-arg.

Acceptance: every Sakila and `graphitron-fixtures-codegen` fixture compiles unchanged. The cross-consumer divergence fixture works.

### Phase 4: migrate consumers

Move each consumer off the legacy permit discrimination onto the new slots. Order chosen to keep blast radius small per PR:

- `MutationInputResolver` — reads `arg.binding()` and `arg.classifiedFields()` directly.
- `EnumMappingResolver.buildLookupBindings` — signature lifts from `(TableInputType, ...)` to `(InputTypeArg, ...)`.
- `FieldBuilder.classifyInputFieldOnArg` — drops the `instanceof TableInputType` arm.
- `GraphitronSchemaValidator.validateTableInputType` — becomes `validateInputArg`, dispatches on `binding` presence.
- `CatalogBuilder` four sites — each reads from `Input` or `InputTypeArg`.
- `InputRecordGenerator`, `InputTypeGenerator` — read `Input.recordShape()` and `Input.schemaType()` directly; permit-identity dispatch retires.

Compiler exhaustiveness on the sealed `Input` interface is the safety net for each migration.

Acceptance: no consumer references `GraphitronType.InputType`, `TableInputType`, or any of the four `*InputType` permits directly.

### Phase 5: delete the legacy model

- Remove `InputType`, `TableInputType`, `JavaRecordInputType`, `PojoInputType`, `JooqRecordInputType`, `JooqTableRecordInputType` from `GraphitronType.permits`.
- Delete the `HasInputRecordShape` capability marker (the slot lives on `Input` directly).
- Delete `ArgumentRef.InputTypeArg.TableInputArg` and `PlainInputArg`.
- Delete the Phase 1-2 adapter shims.

Acceptance: build green; one PR worth of deletions; nothing references the old permits.

### Phase 6 (optional, picks up R97's residue)

- Narrow the SDL `@table` directive's scope from `OBJECT | INTERFACE | INPUT_OBJECT` to `OBJECT | INTERFACE`.
- Migrate every `@table`-decorated SDL input across Sakila, `graphitron-fixtures-codegen`, and LSP fixtures to drop the directive.
- Closes R97.

Can land independently after Phase 5; not a blocker for the structural pivot.

## Dependencies and sequencing

- **R215** (`column-binding-at-classification-not-usage`, In Review) — the `UnboundField` deferral R215 introduces is the same column-coverage relaxation the pivot generalises to "no per-input-type column classification at all." Two `@LoadBearingClassifierCheck` keys R215 shipped (`input-field.unbound-implies-no-column` and `input-field.unbound-with-override-condition-admits-on-mutation-update-delete`) name `BuildContext.classifyInputFieldInternal` as the producer; under this pivot's Phase 3 the producer site moves into the visitor walk. `LoadBearingGuaranteeAuditTest` does not detect producer drift (only missing producers), so Phase 3 carries an explicit commitment to migrate both annotation producer-sites to the new visitor entry point and to re-run the audit per phase. Easier to spec and review with R215 already in tree; not a hard build-order prerequisite, but the annotation continuity is.
- **R94** (shipped) — the `HasInputRecordShape` slot R94 lifted onto every input variant becomes a direct field on `Input`. No new prerequisite; the new `input.record-shape-derived-from-backing` key formalises the invariant R94 left in prose.
- **R166 Phase 1** (reachability slot) — orthogonal. The visitor scaffolding this item lands can be reused by R166 Phase 1, or R166 Phase 1 can land standalone first and this item piggybacks. Spec-stage call.
- **R164** — this item is the *partial* proof-of-concept. What transfers cleanly: the dimensional decomposition discipline (one sealed sub-taxonomy per dimension, not one sealed sub-taxonomy of the cross product), the phased adapter approach, the consumer-migration ordering, the `@LoadBearingClassifierCheck` continuity. What does **not** transfer: the two-level split (`Input` consumer-independent / `InputTypeArg` per-occurrence) is specific to input usage; fields are properties of the SDL field declaration with no per-consumer level to lift onto. R164's authors should model on the dimensional + phasing halves, not on the two-level split.

Likely scope: 1-2 weeks of focused work, somewhat smaller than R164's 2-4 weeks because the consumer surface is narrower and the existing model is already mid-erosion (R215 did the precondition work).

## Vocabulary

- **`Input`** (consumer-independent) vs **`InputTypeArg`** (per-consumer occurrence). The two-level distinction is explicit in the names so readers don't conflate "the SDL input declaration" with "the input at this call site."
- **`BackingClass`** replaces the four `*InputType` permit names. Reading `input.backingClass()` directly answers "which Java class materialises this input?", which is what consumers of that signal always wanted.
- **`TableBinding.directiveLocation`** (`Optional<SourceLocation>`) replaces the implicit "this input has `@table`" signal with an explicit slot; absence means the binding came from consumer-derivation or backing-class reflection, which the validator can disambiguate from `Input.backingClass()` if needed.
- "Table-bound input" prose retires: in code, the predicate is `arg.binding().isPresent()`; in prose, "input arg with a resolved table binding."

## Out of scope

- **Field-side dimensional pivot** — R164. This item validates the pattern; R164 applies it to the field surface.
- **`ServiceCatalog` predicate consolidation** — R220 / R193. Same disease in a different file.
- **`argMapping` grouping syntax** — R97 Phase 1. Separable; the per-arg classification slot the pivot introduces is the natural home for the grouping outcome, but the syntax + parser work is independent.
- **Visitor-driven emission for non-input types** — broader R166. The input-side slice is what this item absorbs.
- **Reachability pruning across all type kinds** — R166 Phase 1. Orthogonal; this item only prunes inputs via the visit gate.

## Risk

- **Per-arg classification multiplies work for inputs reused across many consumers.** A filter input used by ten queries today is classified once; under the pivot, it's classified ten times (once per visit). Mitigation: the work per occurrence is what the back-scan was doing N times anyway; memoise per-`Input` shape decisions (BackingClass derivation, `InputRecordShape` build) so only `TableBinding` + `classifiedFields` run per-arg.
- **Phases 1-3 keep both models alive simultaneously; the adapter is a deferred-deletion liability.** Mitigation: phases 4-5 are scheduled with the same urgency as the rest; the adapter is gone within the same release window.
- **The `InputTypeArg.consumer` slot duplicates information the visitor already has in scope.** Today's resolved-coordinate report carries the same coordinate. Mitigation: the slot is cheap (one reference), the per-arg occurrence outlives the visit, and the alternative (recomputing the coordinate from the field/arg names) is brittle. Spec-stage decision: keep it as a slot.
- **Test coverage gap: the cross-consumer divergence case has no fixture today.** Mitigation: add the fixture in Phase 3 as acceptance.

## Tests

- **Pipeline-tier (regression):** every existing `graphitron-fixtures-codegen` fixture and Sakila fixture compiles unchanged through the pivot. Output diffs against trunk must be empty.
- **Pipeline-tier (new):** consumer-derived path — input with no `@table` directive used by a single `@table`-returning consumer produces an `InputTypeArg` with `binding.isPresent()` and `binding.get().directiveLocation().isEmpty()` (consumer-derived). R97 Phase 2's acceptance fixture lands here.
- **Pipeline-tier (new):** cross-consumer divergence — one input used by two consumers with different return tables produces two `InputTypeArg` occurrences with distinct bindings. Today's `InputType` collapse becomes per-arg success.
- **Pipeline-tier (new):** unreached input — an SDL input declared but never referenced from any reachable consumer produces an `Input` record with no `InputTypeArg`; `InputRecordGenerator` still emits, downstream classifiers see no per-arg work.
- **Pipeline-tier (new):** typed rejection on column-miss carries `Rejection.AuthorError.UnknownName` with the directive's source location (R209 lands here).
- **Compilation-tier:** every `graphitron-sakila-example` compile target stays green.
- **Execution-tier:** every existing execution test passes unchanged. No new execute-tier fixtures are required by the pivot itself.

## Architectural principle this codifies

R164 frames the disease: a sealed hierarchy that tries to represent multiple independent dimensions through a single permit set. The cross product is the permit set; adding a value to any axis multiplies the permits below it; the leaves carry redundant or divergent encodings of the same axis.

The cure is the same in both organs: separate the dimensions onto orthogonal slots, with a sealed sub-taxonomy *per dimension* rather than a sealed sub-taxonomy of the *cross product*. This item ships the cure on the input surface where the cross product is smallest (5 permits encoding 3 axes), then R164 ships the same cure on the field surface where it's largest (46 permits across 3 sealed hierarchies encoding 5+ axes). R220 / R193 then apply it to the consumer-side classifier predicates.

The principle is not "minimise the model" — it's "make the model honest about what it's saying." The cross-product encoding hides axes; the dimensional encoding surfaces them. Hidden axes drift, surfaced axes get the compiler's help.
