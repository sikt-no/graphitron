---
id: R121
title: "Compound mutations: parent entity row + child normalised rows in one INSERT"
status: Backlog
bucket: architecture
priority: 8
theme: mutations-errors
depends-on: []
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

R121 widens that rejection: the trigger admits multiple `@table`-element
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

## Open design questions (for the Spec phase)

- **Parent identification.** How does the SDL distinguish parent from
  children? Options: a `@parent` directive on the parent field; the parent
  is the field whose `@table` matches the mutation input's `@table`; the
  parent is named by convention (e.g. the field whose name is the
  `@mutation` field's "noun").
- **Child FK declaration.** Each child needs a parent-PK-to-child-FK
  binding. Is this read from the jOOQ catalog (the FK constraint between
  child and parent tables) or declared explicitly in the SDL?
- **Child input shape.** How is the child input data carried in the SDL
  input? As a list field on the input type, with each list element being a
  child-row input? Or as a single nested input that produces N children
  via batch?
- **Cardinality across children.** Is the parent always single? Are
  children always lists, or are single children admitted? Does the
  classifier support "list parent + per-parent list of children" (bulk
  compound INSERT)?
- **Transactional shape.** Single jOOQ transaction wrapping all the
  inserts is the obvious default; is there ever a reason to split?
- **Error semantics.** If a child INSERT fails after the parent INSERT
  succeeds, do we roll back the whole transaction (yes, default) or
  surface a per-child error in the localContext slots (only after R75
  Phase 2's slot-population mechanism lands)?

## Out of scope (R121-internal)

- **Compound UPDATE / DELETE / UPSERT.** The pattern generalises (update
  parent + update children, delete cascade, etc.) but the MVP scope is
  INSERT only; later phases or sibling items extend.
- **Compound on `@service` mutations.** R75 Phase 3 covers `@service`
  passthrough payloads; the consumer constructs the result directly,
  including any compound shape, without graphitron-side orchestration.
- **Three-level nesting (grandparent + parent + grandchildren).** Out of
  scope; a follow-up item can lift if the demand surfaces.
