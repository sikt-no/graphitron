---
id: R392
title: "Discriminated TypeResolver reads discriminator ambiguously (double-projection)"
status: In Review
bucket: bug
priority: 2
theme: interface-union
depends-on: []
created: 2026-06-26
last-updated: 2026-06-26
---

# Discriminated TypeResolver reads discriminator ambiguously (double-projection)

Residual hole left by R388. In a discriminated single-table interface (`@table @discriminate`), the
generated `TypeResolver` reads the discriminator with a bare, unqualified
`record.get(DSL.field(DSL.name(discriminatorColumn)), String.class)`
(`GraphitronSchemaClassGenerator.java:141`). R388 qualified the three SQL emission sites in
`TypeFetcherGenerator` but never touched this fourth, read-side site. Worse, when the interface exposes
the discriminator as a queryable field, the discriminator column is projected **twice** into the result:
once by the routing add in `buildInterfaceFieldsList` (post-R388, the two-part `"base"."col"`) and once by
the participant `$fields` as the real catalog column (the three-part `"schema"."base"."col"`). The bare
read then matches both and jOOQ logs `Ambiguous match found for "<col>". Both "base"."col" and
"schema"."base"."col" match`, resolving to the first by luck. Reproduced against the R388 `jti_subject`
fixture with the query shape `{ allSubjects { subjectId ... on AppAccount { subjectKind clientId } } }`
(discriminator field selected inside the inline fragment): four `Ambiguous match` INFO lines, one per row's
`TypeResolver` call; routing still returns the right type only because both duplicate columns hold the same
value. R388's execution test missed it because the ambiguity is INFO-level (non-fatal) and the test
asserted the functional result, not the absence of the warning.

## Implementation status — shipped (In Review)

The fix landed together with this item; `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` is green
end-to-end (full reactor, 0 test failures). What shipped:

- A shared `MultiTablePolymorphicEmitter.DISCRIMINATOR_COLUMN = "__discriminator__"` constant, mirroring
  the multi-table path's `TYPENAME_COLUMN = "__typename"` synthetic-column convention.
- `TypeFetcherGenerator.buildInterfaceFieldsList` now projects the routing discriminator as
  `<base>.field(DSL.name(base, col)).as("__discriminator__")` — the two-part qualified name keeps the
  `Field<Object>` the `.as(...)` wrapping carries, and the alias is distinct from any `$fields`-projected
  real column.
- `GraphitronSchemaClassGenerator` (the discriminated `TypeResolver`) reads
  `record.get(DSL.field(DSL.name("__discriminator__")), String.class)` instead of the raw column name, so
  the routing read is unambiguous and the user-facing discriminator field (if exposed) still resolves from
  its own column. The WHERE filter and LEFT JOIN ON-clause keep referencing the real qualified column
  (unaffected).
- Regression: execution-tier `GraphQLQueryTest.allSubjects_discriminatorFieldInsideFragment_routesViaSyntheticAlias`
  (discriminator field selected inside the inline fragment; asserts routing per type plus the
  `as "__discriminator__"` projection via `SQL_LOG`). The schema-generator unit test
  `GraphitronSchemaClassGeneratorTest` was updated from asserting the raw-column read to asserting the
  alias read (`build_typeResolver_routesOffSyntheticDiscriminatorAlias`).

Awaiting the In Review → Done approval, which the reviewer rule reserves for a session distinct from the
implementer.

Not in scope: the per-participant-`@table` joined-table inheritance compile error (R389), and the
data-specific `navn`-null report (a cross-table `@reference` detail field returning null), which does **not**
reproduce against the equivalent fixture — `clientId` populates correctly — and is most likely a
data/FK-correlation issue in the consumer schema (the composite-FK join correlates on the discriminator
column too, so a detail row whose discriminator column does not match the base row's drops out of the
LEFT JOIN).
