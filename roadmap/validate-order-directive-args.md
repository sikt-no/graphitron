---
id: R181
title: "Validate @order/@defaultOrder: empty directive and @index coexistence"
status: Backlog
bucket: validation
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-05-19
last-updated: 2026-05-20
---

# Validate @order/@defaultOrder: empty directive and @index coexistence

## The symptom

A real user report (paraphrased) crashed the schema build:

```graphql
enum OrgOrder {
  NAVN @order @index(name: "organisasjon_navn_original_ix") @field(name: "NAVN_ORIGINAL")
}
```

`NullPointerException` at `OrderByResolver.resolveOrderEntries` line 240 (`List.of(value)` with `value == null`). The user's intent was `@order(index: "organisasjon_navn_original_ix")`; standalone `@index(name:)` is a deprecated alias for that.

There are two distinct problems hiding behind that NPE, plus one trap to avoid in the fix.

## Problem 1: `@order` silently shadows the deprecated `@index`

`resolveEnumValueOrderSpec` only falls back to `@index` when `@order` is absent (`OrderByResolver.java:174-186`). When both directives appear on the same enum value, the `@index` payload is dropped on the floor and `@order` runs alone. In the repro that means an empty `@order` reaches the resolver, even though the index name the user wanted is sitting one directive over.

The diagnostic the user actually needs is "collapse these into `@order(index: ...)`", not "your catalog lookup failed". The validator should reject `@order` + `@index` coexistence on a single enum value with a fix-it pointing at the canonical form.

## Problem 2: the directive SDL contradicts itself

`directives.graphqls:253` declares:

```graphql
directive @order(
  index: String
  fields: [FieldSort!]
  primaryKey: Boolean = false
) on ENUM_VALUE
```

with the doc string "Exactly one of index, fields, or primaryKey must be set". The `primaryKey: Boolean = false` default means `primaryKey` is *always* set as far as graphql-java is concerned, so the rule as written is unsatisfiable. A validator built literally from this SDL would reject every usage.

Pick one before writing the validator:

- **Drop the default** (`primaryKey: Boolean`). "Set" then means "user supplied", and the validator is one line. Costs: breaking change to any consumer that wrote `primaryKey: false` explicitly.
- **Restate the rule.** Keep the default, change the doc to "exactly one of (`index` supplied, `fields` supplied, `primaryKey: true`)", and special-case `primaryKey: false` as "not selecting primary key" in the validator.

`@defaultOrder` (`directives.graphqls:276`) has the same shape and inherits the same decision.

## Problem 3: don't just null-guard line 240

`resolveOrderEntries` returns `null` to mean "catalog lookup failed", and the caller turns that into `"enum value 'X': could not resolve @order columns in table 'Y'"` (`OrderByResolver.java:188`). A defensive null-guard at line 240 would route the empty-`@order` case down that same path, giving the user a misleading catalog-failure message for a malformed-directive problem.

The empty-directive case needs its own rejection *before* the catalog-lookup return-null path, with its own message.

## Scope

1. Pick a meaning for `primaryKey: Boolean = false` in `directives.graphqls` and align the doc string with the chosen meaning. (Decision precedes implementation.)
2. Add a validator that rejects `@order` + `@index` on the same enum value, with a fix-it suggesting `@order(index: <name>)`.
3. Add a validator that rejects `@order` and `@defaultOrder` when zero or more than one of `index` / `fields` / `primaryKey` is set (per the rule from step 1). Distinct diagnostic from the catalog-failure path.
4. Defence-in-depth null-guard at `OrderByResolver.resolveOrderEntries:240`; the validators above should make it unreachable, but a missing guard would silently reintroduce the NPE if the validator is later weakened.
