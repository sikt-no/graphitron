---
id: R122
title: "Compound mutations: parent entity row + child normalised rows in one INSERT"
status: Backlog
bucket: architecture
priority: 8
theme: mutations-errors
depends-on: []
last-updated: 2026-05-23
---

# Compound mutations: parent entity row + child normalised rows in one INSERT

A common entity-storage pattern is one parent row in an entity table plus N
rows in one or more normalised child tables (typed-attributes,
many-to-many association rows, etc.) keyed off the parent's PK. Today
graphitron's `@mutation(typeName: INSERT)` admits exactly one DML target
table per mutation; consumers wanting "insert one entity + its normalised
children" have to author a `@service` mutation that orchestrates the
inserts in Java, even when the relationships are entirely declarative
from the SDL/jOOQ catalog perspective.

The compound-mutation feature lets a single `@mutation(typeName: INSERT)`
declare both shapes:

```graphql
type CreateOrderPayload {
    order: Order
    items: [OrderItem!]
    shippingAddresses: [ShippingAddress!]
}

type Mutation {
    createOrder(input: CreateOrderInput!): CreateOrderPayload
        @mutation(typeName: INSERT)
}
```

Graphitron emits the parent INSERT first, captures the parent's PK, then
emits the child INSERTs with the captured PK threaded into each child
row's FK column.

## Relationship to R75 (passthrough payloads via identity passthrough)

R75 establishes the trigger function and identity-passthrough mechanism
for plain SDL payload types. R75's Phase 2 trigger admits one
`@table`-element field as the data field plus zero or more non-`@table`
slot fields; multi-`@table`-element payloads are explicitly rejected with
a per-condition message that names the trigger criterion.

R122 widens that rejection: the trigger admits multiple `@table`-element
fields when they form a parent-child structure with declared FK
relationships. The PassthroughInfo record lifts to a sealed sub-taxonomy
(per the "Sub-taxonomies for resolution outcomes" principle): a `Single`
arm carries one data field as today; a `Compound` arm carries the parent
descriptor plus a list of child descriptors with FK column bindings.

The slots concern (R75 Phase 2's `localContext` mechanism) composes with
compound mutations: a compound payload may also carry non-data slot
fields (errors, affected-row counts, warnings). The `SlotCarrier` capability
on mutation-field carriers extends to `MutationCompoundInsertField` (the
new sealed permit on `DmlTableField`) without modification.

## Design space narrows under R222

R222 (`input-model-dimensional-pivot`) introduces a recursive `InputUsage`
model where a nested-input slot can carry an `InputUsage` with its own
table distinct from the parent's. `InputUsage` is the SQL-side carrier
(`(Input, TableRef, List<InputField>)`); the recursive `InputField` arms
are `NestingField` (same-table SDL grouping) and the new
`TableTargetField` this item adds (cross-table FK-linked nesting).
Combined with `@reference(path:)` on nested-input slots (the directive
already exists on `INPUT_FIELD_DEFINITION` for `@nodeId` leaves; R122
extends its reach), the SDL declaration for a compound mutation
flattens deterministically:

```graphql
input CreateOrderInput @table(name: "order") {
    customer: ID! @nodeId(typename: "Customer") @reference(path: [{key: "fk_order_customer"}])
    total: Float
    items: [CreateOrderItemInput!] @reference(path: [{key: "fk_order_item_order"}])
}

input CreateOrderItemInput @table(name: "order_item") {
    product: ID! @nodeId(typename: "Product") @reference(path: [{key: "fk_order_item_product"}])
    quantity: Int
    unitPrice: Float
}
```

Under that shape, the open questions earlier drafts of this item carried
collapse as follows:

- **Parent identification:** the root `InputUsage` is the parent, by
  construction. No `@parent` directive needed.
- **Child FK declaration:** declared on the nested-input slot via
  `@reference(path:)`. The classifier verifies the FK against the jOOQ
  catalog (which it already does for `@nodeId @reference`); typo'd
  `key:` produces a Levenshtein-hinted error.
- **Child input shape:** a list-typed nested-input field whose nested
  `InputUsage.table` is the `@reference(path:)` terminal table.
- **Cardinality:** the SDL list type on the nested-input slot
  (`[CreateOrderItemInput!]`) declares it.

The questions that remain Spec-stage work for R122:

- **New `InputField` arm: `TableTargetField`** (working name, mirroring
  `ChildField.TableTargetField` on the output side at `ChildField.java:317`).
  `NestingField` is *not* the right arm — that's reserved for the
  SDL-grouping case where the nested fields stay on the parent's table
  (per `InputField.java:186-195`). The moment the nested input crosses a
  table boundary, the model shape changes. R122's `TableTargetField`
  carries the nested `InputUsage` (with the child's own `TableRef`) plus
  the FK descriptor (the constraint name and the FK column on the child
  side that holds the parent's PK). The mutation emitter may use a jOOQ
  `TableRecord` internally when constructing the child INSERT — that is
  an emitter implementation detail, not a model slot. `BackingClass`
  itself is not on `TableTargetField`: it's the user's declared
  materialization target for a domain-form service-method param, which
  R164 attaches per-param on the domain-form arm.
- **Visitor arm.** R222 names the seam (see its Phase 3 description and
  §"Recursion through nested inputs") but does not implement it. The
  visitor's nested-input recursion needs an arm that recognises an
  `@reference(path:)` directive on a nested-input slot whose terminal
  element resolves to a table different from the parent's, constructs the
  child `InputUsage` with that table, and wraps it in `TableTargetField`
  rather than `NestingField` or `UnboundField`. The pre-existing
  `@reference(path:)` resolver on the `@nodeId` path is the model for
  how the directive is parsed and the FK constraint is verified against
  the jOOQ catalog.
- **Mutation emitter orchestration.** Parent INSERT first, capture the
  parent's PK, then child INSERTs with the captured PK threaded into each
  child row's FK column. New emitter dispatch on `TableTargetField`: walk
  the nested `InputUsage.classifiedFields` for the child's own columns,
  plant the captured PK into the FK column from the `TableTargetField`'s
  FK descriptor. Recurses naturally if a child itself carries a
  `TableTargetField` (grandchild support falls out, though the MVP is
  one level).
- **Transactional shape.** Single jOOQ transaction wrapping all the
  inserts is the obvious default; is there ever a reason to split?
- **Error semantics.** If a child INSERT fails after the parent INSERT
  succeeds, do we roll back the whole transaction (yes, default) or
  surface a per-child error in the localContext slots (only after R75
  Phase 2's slot-population mechanism lands)?

## Out of scope (R122-internal)

- **Compound UPDATE / DELETE / UPSERT.** The pattern generalises (update
  parent + update children, delete cascade, etc.) but the MVP scope is
  INSERT only; later phases or sibling items extend.
- **Compound on `@service` mutations.** R75 Phase 3 covers `@service`
  passthrough payloads; the consumer constructs the result directly,
  including any compound shape, without graphitron-side orchestration.
- **Three-level nesting (grandparent + parent + grandchildren).** Out of
  scope; a follow-up item can lift if the demand surfaces.
