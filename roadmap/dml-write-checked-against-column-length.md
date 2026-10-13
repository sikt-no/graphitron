---
id: R1025
title: "A DML mutation write is not checked against the column length before the statement"
status: Backlog
bucket: architecture
priority: 4
theme: mutation-write
depends-on: []
created: 2026-10-13
last-updated: 2026-10-13
---

# A DML mutation write is not checked against the column length before the statement

## Goal

A value too long for its column, sent through a `@mutation(typeName: INSERT | UPDATE | UPSERT | DELETE)` write, comes back as a typed error in the payload's errors list with a path to the offending input field. It no longer reaches the database and returns as a redacted driver error. Graphitron 9 gave this for free and Graphitron 10 does not, so a consumer migrating from 9 loses client-visible input validation on every DML mutation with no build-time signal.

From sis (Oracle), with `union RegistrerEsiForStudenterError = UgyldigInput` on the payload:

```graphql
type Mutation {
    registrerEsiForStudenter(input: [EsiInput!]!): RegistrerEsiForStudenterPayload
        @mutation(typeName: INSERT) @table(name: "ESI")
}
```

Graphitron 9, for an `esiId` of 300 characters against a 256-character column:

```json
{"data":{"registrerEsiForStudenter":{"errors":[{"__typename":"UgyldigInput",
  "message":"size must be between 0 and 256","path":["registrerEsiForStudenter","input","0","esiId"]}]}}}
```

Graphitron 10 sends the value, gets `ORA-12899: value too large for column "FS"."PERSONID"."PERSONIDVERDI" (actual: 300, maximum: 256)`, matches no `@error` handler, and returns `data: null` with "An error occurred. Reference: …". sis has the same pattern on `STUDENTKORT.STUDENTKORTNR` and on `MERKNAD` (510 > 500) across its `registrerMeldingerOm*` mutations.

## What is and is not covered already

Graphitron 9 validated the jOOQ record it built from the mutation input with Jakarta Bean Validation. jOOQ's codegen, with `<validationAnnotations>`, puts `@Size(max = n)` on the record's setters from the column's declared length, so every `varchar(n)` column was length-checked before the statement.

The rewrite has the validation pipe but not this constraint source:

- R747: the `VALIDATION` handler runs a `Validator` over graphitron's emitted input records, which carry no annotations, so it never fires. That item is about the empty walk in general.
- R92: database `CHECK` constraints become Jakarta rules. A column's declared length is not a `CHECK`.
- R98: merges SDL validation directives, `CHECK`-derived rules and consumer-record annotations into one constraint set per input field. Column length is not one of its sources.

So the fact this item adds is the column's declared length, read from the jOOQ catalog (`DataType.length()`) for each input field that maps to a column of the mutation's table.

## Open questions for Spec

- **Where the fact enters.** Either as a fourth source in R98's merged constraint set (then this item narrows to "the catalog's column length is a constraint source", and the client also sees the limit through introspection), or the DML path validates the jOOQ record it builds the way 9 did (smaller, but it depends on the consumer's jOOQ codegen having `<validationAnnotations>` on, and the violation path then names a record property rather than the SDL input field). The first fits the architecture R98 is building; the second is the literal parity path.
- **How a violation is routed.** sis's `UgyldigInput` declares only `DATABASE` handlers (ORA codes 1438, 2290, 2291, 1), none for `VALIDATION` and none for 12899, yet 9 routed the violation into it. Spec has to decide whether a length violation needs an explicit `{handler: VALIDATION}` on the payload's error type (and if so whether the migration guide says so), or lands in the errors union by some default. If a declaration is required, a DML mutation over length-bounded columns whose payload declares no `VALIDATION` handler is a candidate for a build-time warning.
- **Path shape.** 9's path walks the GraphQL argument (`input`, `0`, `esiId`). The rewrite's `ConstraintViolations.toGraphQLError` builds its own path; Spec confirms the two agree for a list input.
- **Should a consumer declare limits themselves instead?** Using SDL `@Size` on each input field (R98's phase 1) is the alternative the reporter asked about. It works without this item, but repeats a fact the catalog already holds, and it drifts silently when a column is widened.

## Provenance

Reported by the sis Oracle IT run (fs-plattform, branch `sis/upgrade-graphitron`, write-up in `sis/GRAPHITRON10_STATUS.md` § "Third IT run"; consumer tests include `MutationValidationIT.mutationWithJavaBeanValidationViolationShouldReturnCustomValidationErr`). The run used a pre-R1019 snapshot. R1019 touched tenant binding, not the error channel, so the behaviour is expected to be the same on trunk, but that has not been re-checked.
