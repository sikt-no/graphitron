---
id: R186
title: "Nested input types in @mutation fields"
status: Backlog
bucket: architecture
priority: 6
theme: mutations-errors
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# Nested input types in @mutation fields

`MutationInputResolver` structurally rejects any `InputField.NestingField` on an `@mutation` input with the message *"nested input types in @mutation fields are not yet supported"* (`MutationInputResolver.java:464`). The rejection is `Rejection.structural` with no plan-slug pointer, so consumers hitting this today have no roadmap item to track against. Unlike the sibling `ColumnReferenceField` / `CompositeColumnReferenceField` arms in the same switch (which point at R24's join-projection work) and unlike the multi-table parent-child INSERT case (R122), a plain nested non-`@table` input that maps onto columns of the same DML target table has no roadmap coverage at all.

## Forcing-function schema

```graphql
input EndreOrganisasjonInput @table(name: "ORGANISASJON") {
  id: ID! @nodeId(typeName: "URegOrganisasjon")
  originalnavn: String! @field(name: "NAVN_ORIGINAL")
  lokalisering: LokaliseringInput!
}

input LokaliseringInput {
  landkode: String!
  bynavn: String
  regionnavn: String
}

type Mutation {
  endreOrganisasjon(input: [EndreOrganisasjonInput!]!): EndreOrganisasjonPayload
    @mutation(typeName: UPDATE)
}
```

`LokaliseringInput` is a plain (non-`@table`) input that groups columns of `ORGANISASJON`. There is no second table involved; this is purely a grouping shape on the consumer side. Today this fails to classify with the `NestingField` structural rejection, even though every leaf column targets the same DML table as the outer `@table` input.

## Relationship to neighbouring items

- **R122 (compound mutations)** covers nested inputs that introduce *additional* tables (parent + child INSERT). A non-`@table` nested input that flattens onto the outer table's columns is structurally a different shape and shouldn't have to wait for R122.
- **R24 (`NodeIdReferenceField` join projection)** covers FK-target `@nodeId` carriers, orthogonal axis.
- **R96 (input-type variant reshape)** and **R94 (input-record shape)** ship the per-input-type Java class the validator pre-step walks; the emitted class already recurses into nested input components via `fromMap`, so the validation surface composes without further work.

## Open questions (for the Spec phase)

- **Column-coverage check.** Should the classifier verify that every leaf in the nested input maps onto a column of the outer `@table`, or accept the flatten-on-emit shape and let the DML emitter surface a "column not found" rejection? The former gives better diagnostics; the latter is mechanically simpler.
- **`@field(name:)` scoping.** The `@field` directive on a nested input field points at a column of the *outer* `@table`. Is this the contract, or does a nested `@table`-less input default to "column name = SDL field name" with the outer table as the resolution context?
- **Nested + `@nodeId`.** Does a nested input admit `@nodeId` carriers, or are they reserved for the top-level input (where the surrounding `@table` is unambiguous)?
- **DML verb coverage.** INSERT / UPDATE / UPSERT / DELETE — does the nested-flatten shape compose with all four, or are there verbs (likely DELETE, where the lookup-key set is narrower) where the shape is degenerate and should be rejected?
- **List-typed nested inputs.** Should `lokalisering: [LokaliseringInput!]` be admitted (and if so, what does flattening N nested elements onto one outer row mean), or rejected as outside scope until a forcing-function schema appears?

## Out of scope

- Nested `@table` inputs that introduce a second DML target — that's R122's territory.
- Nested inputs whose leaves are themselves `@table`-backed shapes (R23's multi-parent territory on the output side has no symmetric input meaning yet).
- Designing the emit shape for the flattened columns; that's a Spec-phase decision.
