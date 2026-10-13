---
id: R1026
title: "A nullable nesting wrapper whose columns are all null resolves to an object, not null"
status: Backlog
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-10-13
last-updated: 2026-10-13
---

# A nullable nesting wrapper whose columns are all null resolves to an object, not null

## Goal

A nullable field returning a *nesting type* (a type without `@table` whose fields read columns of the enclosing `@table` type's row) has a documented null contract, pinned by a test. The proposed default is Graphitron 9's behaviour: the field resolves to `null` when every column the wrapper projects is null. Today it always resolves to an object, so a wrapper with nothing in it reaches the client as `{antall: null, tidsenhet: null}`. A client that tests `varighet != null` breaks on migration, and nothing in the manual says the behaviour changed.

From sis:

```graphql
type EmneUndervisningsoversikt @table(name: "EMNE") {
    varighet: Emneundervisningsvarighet
}
type Emneundervisningsvarighet {
    antall: Int @field(name: "TALL_VARIGHET_UND")
    tidsenhet: Tidsenhet
}
```

For an emne with no duration, Graphitron 9 returns `"varighet": null`; Graphitron 10 returns `"varighet": {"antall": null, "tidsenhet": null}`. sis's `EmnerQueryIT.fetchEmnerQuery` (asserting every present `varighet.antall` is an `Integer`) and the `studieoppbygningQuery` approval (`maksimumsvekting: null` became `{verdi: null}`, `beskrivelseAlleSprak: null` became `{en: null, nb: null, nn: null}`) are the consumer-side reproductions.

## Questions for Spec

- **Is v9's rule the right contract?** "All projected columns null" conflates "no value" with "a value whose parts are all null". The second is rare for the wrapper shapes sis uses (a quantity plus unit, a text per language), and v9 parity is a strong default for a migration target. The alternative is to keep v10's behaviour and document it as a breaking change in `docs/manual/how-to/migrating-from-legacy.adoc`. Either way the decision gets written down. Silent divergence is the defect.
- **What counts as a projected column.** Only scalar leaves the wrapper reads from the parent row. A nested wrapper inside a wrapper presumably counts as null when it is itself null by this rule, and a child that issues its own query (`@splitQuery`, a reference to another table) does not hold a column of the parent row at all. Spec settles whether a wrapper containing such a child can be null-collapsed, or whether that child makes the wrapper always non-null.
- **Non-null wrappers.** A `Varighet!` field cannot collapse to null. It keeps resolving to an object, and the rule applies only to nullable fields.
- **Where it lives.** The nesting field is `ChildField.NestingField`; whether the check belongs in the projection or in the fetcher that hands the parent row down to the wrapper is a plan question.

## Tests

A pipeline- or execution-tier pair over a sakila nesting type with a nullable column: a row where the wrapper's columns are all null resolves the field to `null`, and a row with one non-null column resolves to an object. Add a non-null-wrapper control that keeps resolving to an object.

## Provenance

Reported by the sis Oracle IT run (fs-plattform, branch `sis/upgrade-graphitron`, write-up in `sis/GRAPHITRON10_STATUS.md` § "Third IT run"), on a pre-R1019 snapshot not re-checked on trunk. No manual page states the null contract of a nesting field either way (checked at `1cb2e1b04`).
