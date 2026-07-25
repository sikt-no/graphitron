---
id: R51
title: "Merge PropertyField and RecordField into one record-read leaf carrying a sealed locator"
status: In Progress
theme: classification-model
bucket: cleanup
priority: 5
depends-on: []
last-updated: 2026-07-25
---

# Merge PropertyField and RecordField into one record-read leaf carrying a sealed locator

`ChildField.PropertyField` and `ChildField.RecordField` are one leaf wearing two names. Both classify
"read a value off a record-backed parent's in-memory object": same `source` fact, same empty operation
set, same read mechanism, and every consumer treats them as a pair. The evidence in the emit layer is
conclusive: both `bind` arms dispatch to the shared `propertyOrRecordBinding` helper
(`FetcherEmitter`), whose javadoc literally says "Binding for a `PropertyField` / `RecordField`"; the
same file carries four more two-arm `instanceof` chains extracting the same slots from either leaf
(`bindDualShape`, `isEnvDependentAccessorRead`, `inlineSuccessRead`, `isInlineArmSwitchedDataField`);
both `TypeFetcherGenerator` dispatch arms are identical no-ops; `validatePropertyField` is an empty
method pointing at the nearly-empty `validateRecordField`; `CompileDependencyGraphBuilder` carries
both as no-op arms twice; and the LSP projection already merges them onto one label,
`FieldClassification.RecordOrProperty`, with byte-identical arm bodies. The `@classified` corpus
agrees: `FilmStats.count` (PropertyField) and `FilmDetails.stats` (RecordField) assert the identical
dimensional fingerprint `target: Single, targetShape: Field, sourceShape: Record`. A repeated two-arm
`instanceof` chain at every consumer is the signature of a missing single type.

What actually differs is carried facts, not identity: the classification trigger (scalar/enum vs
object SDL return) and the target fact (`RecordField` carries a `ReturnTypeRef` and answers
`listOrSingle(Field)`; `PropertyField` carries none and hard-codes `Single(Field)`). In R333
vocabulary that is one fact, the target; encoding it as permit identity is the cross-product disease
R222 dissolves, and the shape R432 already merged away for the batched leaves.

## Target shape

One merged record-read leaf. Fresh name chosen at pickup, fresh and not reused, per R432's rationale:
every switch arm and `instanceof` must be compiler-forced through the rename. It carries:

**`ReturnTypeRef returnType`, covering both cases.** The object construction path keeps its resolved
`ReturnTypeRef`; the scalar path gains a `ReturnTypeRef.ScalarReturnType(name, wrapper)` it does not
carry today. `target()` derives `listOrSingle(returnType.wrapper(), Field())` **unconditionally**.
This is a decided model change, and the pinned behavior it overrides was challenged rather than
assumed away. `PropertyField` today hard-codes `Single(Field)` and sits in
`WrapperAlgebraTest.CARDINALITY_NOT_MODELED`, a **five-member family** (the column family
`ColumnBackedField` / `ColumnBackedReferenceField` / `ParticipantColumnReferenceField`, plus
`PropertyField` and `ErrorsField`) pinned always-`Single` with a "faithful read regardless of the
GraphQL wrapper" rationale. That rationale does not bind, for two reasons. First, it describes the
emit mechanism, not the model fact: the bare property read is wrapper-agnostic, but so is
`RecordField`'s identical bare read, and `RecordField` obeys the SDL-list mirror; the same mechanism
answering two ways depending on which leaf name carries it is drift, not design. Second,
`OutputField.single`'s own javadoc gives the pin's real cause: it is "the default wrapper for a leaf
that **carries no return wrapper**", i.e. slot absence rationalized post-hoc, not a deliberate model
answer. R333 defines the target wrapper as the field's own output cardinality read off
`field.getType()`, so once the merged leaf carries a wrapper the mirror is the only honest
derivation. Consequences: the `error-field` / `error-type` corpus rows flip `target: Single` to
`target: List` for `path: [String!]!`, the merged leaf **exits the exemption family** (whose four
remaining members stay pinned deliberately: they still carry no wrapper slot, and the column
family's exit is R333's named `List(Column)` missing corner, real to-many machinery rather than a
representational fix, tracked there and not here), and no generator consumer moves (nothing in the
emit reads `target()`; the surfaces that move are named in the acceptance bar below). The residual
asymmetry between the merged leaf and the still-pinned four is accepted and recorded, not
accidental.

**`ValueLocator locator`, one non-null sealed component replacing the `columnName` / `column` /
`accessor` nullable triple.** This is R333's field-level accessor fact ("The accessor is
field-level"): arm identity instead of null checks, gated by the parent's source-object shape. Four
arms per the real populations:

- `TypedColumn(ColumnRef)`: the parent is a `JooqTableRecordType` with a resolvable column; reads
  `record.get(Tables.X.COL)`.
- `JavaAccessor(AccessorResolution.Resolved)`: class-backed parent (Java record component, getter,
  public field), today's resolved-accessor slot.
- `ByName(String sqlName)`: untyped `DSL.field(name)` read, scoped to `JooqRecordCarrier` parents
  (the nesting-reuse case where no typed constant resolves).
- `DefaultRead(String name)`: graphitron locates nothing and graphql-java's default property
  machinery applies. This is the home for the two both-slots-null populations that are not `ByName`:
  fields on `@error`-type parents (whose parent is not a `ResultType` at all; their read is mediated
  by `ErrorType.accessorBaseFor` / `accessorOverrides` against the developer's exception class) and
  class-backed parents whose backing class could not be loaded. Without this arm, `ByName` would
  carry two incompatible meanings.

No transform axis: encodings stay entailed by the column facts the locator points at. `ValueLocator`
names the axis, not the current membership; it is deliberately partial against R333's full locator
family (passthrough and localContext arms are `NestingField`'s identity read and
`ChildField.Transport`, the latter already an arm-set of this same axis under another name), and
those siblings are expected to converge onto it in a follow-up, not here.

**`domainReturnType()` derived by an exhaustive switch on the locator**: `TypedColumn` answers the
column's Java type, every other arm answers `Object`. This dissolves today's drift where the same
resolvable column answers its type on `PropertyField` but `Object` on `RecordField` (both
construction paths run `resolveColumnOnJooqTableRecord`; only the leaf name differs). The change
moves exactly one population, object reads with a resolvable parent-table column, and it is
diagnostic-visible rather than output-visible: `collectDomainReturnTypeConflicts` groups producers by
this answer and raises `MultiProducerDomainTypeDisagreement`, so the acceptance bar pins that channel.

**Read sites switch on the locator; the parent `ResultType` is consulted only for the cast target.**
Today `propertyOrRecordBinding` and `inlineSuccessRead` each independently reconstruct the same
three-way fork as `resultType instanceof` cross-checks against nullable slots. After the merge both
are exhaustive switches over the locator (R333's composition rule: the source object gates and casts,
the locator locates). Two corollary deletions are intended, not accidental:
`isEnvDependentAccessorRead`'s `JooqRecordCarrier` early-return (structurally impossible once the
accessor lives on an arm) and `inlineSuccessRead`'s defensive terminal `throw`.

**Validator rule instead of the empty pair.** The gating invariant (locator arm compatible with the
parent type's source-object shape: `TypedColumn` only under a table-backed parent, `JavaAccessor`
only under a class-backed one, `ByName` only under a `JooqRecordCarrier`) is cross-axis; the leaf
does not carry the parent classification, so a compact constructor cannot see it. It becomes a real
validate-time rule with a directed message in the merged leaf's validator method, replacing the
empty `validatePropertyField` / `validateRecordField` pair rather than deleting the site. That makes
`propertyOrRecordBinding`'s currently-unguarded `PojoResultType.Backed` cast and accessor switch
guaranteed by a checked fact instead of construction-site coincidence. Compact-ctor invariants are
reserved for what the leaf can see alone (non-null components; `ScalarReturnType` xor result-typed
`returnType` agreement with whatever the merged record needs structurally).

## Slices

R432/R314 additive-cutover-retire template:

1. **Additive**: land `ValueLocator` and the merged leaf with compact ctor, `target()` /
   `domainReturnType()` derivations, and consumer arms. The leaf enters
   `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`'s partition and the
   validator's exhaustive switch the moment it exists (both are exhaustive over every sealed leaf;
   "unreachable" still needs a status).
2. **Cutover**: flip the three `FieldBuilder` construction sites (scalar arm, object arm via
   `recordFieldOrUnclassified`, `classifyChildFieldOnErrorType`) to build the merged leaf with the
   locator arm chosen from the same resolution logic that populates the slots today; migrate the
   validator rule, `@classified` corpus rows, and LSP projection switch.
3. **Retire**: delete the two old leaves; collapse every paired `instanceof` / switch arm; rename
   `propertyOrRecordBinding` and rewrite its javadoc and the surrounding two-leaf narration (the
   `ChildField` "distinguishing this leaf from RecordField" prose, the "See RecordField's analogous
   slot" cross-references, the paired no-op dispatch comments, and `OutputField.single`'s javadoc
   mention of the property projection, which the merged leaf no longer uses); sweep the architecture
   docs (`code-generation-triggers.adoc` names both leaves) and regenerate, not hand-edit, the
   generated `supported-schema-shapes.adoc` fragment.

## Acceptance bar

Byte-identical generated sakila output is the backstop, not the definition; nothing in the generator
consumes `target()`, so bytes can hold while the model moves. The bar is the named enforcer deltas,
each one-way and intended:

- `@classified` corpus: `error-field` / `error-type` rows flip `target: Single` to `List`;
  `mapping`'s rows (`FilmStats.count`, `FilmDetails.stats`) unchanged apart from the leaf name.
- `WrapperAlgebraTest`: the `CARDINALITY_NOT_MODELED` entry for the retired leaf is removed, the
  exemption set correspondingly smaller.
- `GeneratorCoverageTest`: two `IMPLEMENTED_LEAVES` entries become one.
- Diagnostic channel: no entry gained or lost across the corpus on
  `MultiProducerDomainTypeDisagreement` (pins the `domainReturnType` derivation change).
- Sakila regeneration diff empty on slices 2 and 3.

The change is representational: no per-locator-arm unit tests and no code-string assertions on
generated bodies; corpus rows plus the `graphitron-sakila-example` compile are the right tiers.

## Out of scope

- Folding `NestingField` (the passthrough locator arm) and `ErrorsField`'s `Transport` (the
  localContext arm-set) onto `ValueLocator`; when they join, `locator()` lifts from a leaf component
  to a capability interface per the R238 pattern, and the exhaustiveness bookkeeping relocates. A
  component on the single merged leaf is the correct home until then (the read sites fork on locator
  identity, which is a sealed switch, not a capability).
- The type-level source-object fact (R333's cast-target side of the read); this item consumes the
  parent classification as-is.

## Lineage

Surfaced during R50's `columnName` cleanup on the column-backed carriers (since merged by R508 into
`ChildField.ColumnBackedField` / `ColumnBackedReferenceField`), where the table-backed-only invariant
let those carriers retire `columnName` outright. Two earlier framings of this item are rejected:
splitting the two leaves per parent kind into sealed arms (pre-R333; multiplies permits the R222
pivot dissolves, parent kind being a `source`-side fact), and keeping two leaves while sealing only
the locator (the leaf split carries no behavioral weight worth preserving). The Spec draft was
architect-consulted; the review contributed the unconditional `target()` decision, the fourth locator
arm, the locator-switch acceptance, the validator rule replacing the empty pair, and the
enforcer-delta acceptance bar.

## Retired vocabulary

`ChildField.PropertyField`, `ChildField.RecordField`, `propertyOrRecordBinding`,
`validatePropertyField`, `validateRecordField`. In `CARDINALITY_NOT_MODELED` only the
`PropertyField` entry and the list-shaped-scalar rationale sentence retire; the set itself stays,
four members strong.
