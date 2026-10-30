---
id: R1134
title: "An argument @condition(override: true) is rejected as an unbound column before the override is considered"
status: Spec
bucket: bug
priority: 4
theme: classification-model
depends-on: []
created: 2026-10-30
last-updated: 2026-10-30
---

# An argument @condition(override: true) is rejected as an unbound column before the override is considered

## Goal

A scalar argument whose `@condition(override: true)` owns the predicate no longer has to name a
column. An argument's `@condition` is a developer-written Java method that returns a SQL
condition, and `override: true` means that method replaces the argument's implicit `column = ?`
predicate (`condition.adoc`: "at argument level, the implicit `column = ?` for that one
argument"). An argument with nothing to compare against has no implicit predicate to replace, so
the build accepts it. Today it fails first:

```graphql
type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
type Query {
    # `sakskode` is no column of `film`. The method owns the predicate, so none is needed.
    films(sakskode: String
        @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub",
                               method: "sakskodeCondition"}, override: true)): [Film!]!
}
```

fails with `argument 'sakskode': column 'sakskode' could not be resolved in table 'film'`. The
same shape on an input field already classifies (`plainInput_overrideTrueWithoutMatchingColumn_classifiesAsConditionOwnedField`,
mined from the opptak `SakFilterV2Input.sakskode` field); the argument coordinate is the gap. The
minimal pair stays rejected: the same argument with `override: false` (or no `@condition`) still
needs its column, because the implicit predicate it composes with has nothing to bind. kreg
(fs-plattform's Graphitron 10 port) worked around it with `@reference(path: [{condition: ...}])
@field(name: "<column on target>")` on the argument.

## Implementation

All sites are in `FieldBuilder` unless named.

* **Where the rejection is produced.** `classifyArgument` mints `ArgumentRef.ScalarArg.UnboundArg`
  at two sites on the scalar path, both after `argCondition` (the arg's built `ArgConditionRef`)
  is in hand: the closing "Scalar arg: bind to column" miss (`column '...' could not be resolved
  in table`, the one the item names) and the `@reference(path:)` arm's `refCol.isEmpty()` miss
  (`no column '...' reachable via @reference path`). Earlier arms (`@nodeId`, node `id`) are
  untouched; the `@nodeId` no-route case is already lifted by `NodeIdLeafResolver`'s
  `AuthorOwnedPredicate` into `ConditionOwnedArg`.
* **Lift at both sites.** On a column miss, when `argCondition` is present with `override()` true
  and the argument carries no `@lookupKey` (a lookup needs a column tuple to drive its VALUES+JOIN,
  so that combination stays a rejection), return a `ConditionOwnedArg` instead of `UnboundArg`.
  `override: false`, no `@condition`, and a failed condition build (already an `UnclassifiedArg`
  from `conditionResolver.resolveArg`) are unchanged. A `@reference` path that fails to parse
  (`ctx.parsePath`) stays rejected: only the column miss is lifted.
* **Widen `ArgumentRef.ScalarArg.ConditionOwnedArg`** (recommended over a second carrier, which
  would duplicate three switch arms). Its `decode` component becomes `Optional<HelperRef.Decode>`,
  empty for a plain scalar; the javadoc stops saying "`@nodeId` scalar arg" and keeps the
  `override: true` compact-constructor pin and the pairing with `InputField.ConditionOwnedField`.
  Readers: `nodeIdArgDecodes` (the `ConditionOwnedArg` arm) puts an entry only when `decode` is
  present; `projectFilters` (arm already adds `co.condition().filter()` and no `BodyParam`) and
  `columnBindingOf` (already `Optional.empty()`) need no change; the `NodeIdLeafResolver`
  construction passes `Optional.of(slotDecode)`.
* **Parameter binding needs no column.** `resolveArg` reflects the method against the argument's
  own slot type (`ArgBindingMap` plus `reflectTableMethod`), not against a resolved column, so the
  method's `String sakskode` parameter binds by name and the generated call site is unchanged.
* **Docs.** `docs/manual/reference/directives/condition.adoc`: extend the "Argument-level
  `override: true` is local" constraint with the no-column case (and that `override: false` or
  `@lookupKey` still need the column). `docs/architecture/reference/argument-resolution.adoc`
  lists the scalar carriers; add the widened `ConditionOwnedArg` role. Update the `UnboundArg`
  javadoc in `ArgumentRef` ("column could not be resolved" is now always a genuine miss).

Out of scope: a field-level `@condition(override: true)` over an unbound argument that has no
`@condition` of its own stays rejected, because the field-level method may or may not read that
argument and the cascade verdict for it is a different decision (the input-field analog, an
`UnboundField` admitted by an enclosing override, is the shape to follow if the author wants
it). The kreg port has a concrete case, filed as its own item (R1139): `navn(spraakkoder: [String!]) @splitQuery @reference(...) @condition(condition: navnFilterCondition, override: true)`, where `spraakkoder` matches no column. Also distinct from `input-field-condition-drops-implicit-predicate` (R1092), which is about
an input field's `@condition` *without* override dropping a column predicate that does resolve;
the two touch different carriers and share no code, so neither depends on the other.

## Tests

* Pipeline tier, `GraphitronSchemaBuilderTest` (next to `UNBOUND_ARG_COLUMN_NOT_IN_TABLE`): the
  argument above with `TestConditionStub.sakskodeCondition` classifies as a
  `QueryField.QueryTableField` whose filters hold the `ConditionFilter` for the method and no
  `GeneratedConditionFilter`; the same argument without `override: true` is still an
  `UnclassifiedField` with `could not be resolved in table 'film'` and kind `AUTHOR_ERROR`; the
  same argument with `override: true` and `@lookupKey` is still rejected. Add the `@reference`
  miss twin with `override: true`.
* Pipeline tier: a `ColumnBindingLedger` assertion that the lifted argument records no column
  slot, as `conditionOwnedField_withNoMatchingColumnResolvesNone` does for the input field.
* Compilation and execution tiers: no corpus example applies (`graphitron-model`'s corpus
  carries no `@condition` verdicts, and this is a classification lift, not a new verdict). Add one
  field to the sakila example schema (`graphitron-sakila-example`, beside `filmsByTitleBeans`)
  with an unbound scalar argument and a `sakila-service` conditions fixture, so the emitted
  fetcher compiles at release 17, plus one `GraphQLQueryTest` case that the method's predicate
  narrows the result (`docs/architecture/how-to/testing.adoc` for tier placement).
* Retired vocabulary: none.

Provenance: reported by the kreg Graphitron 10 port session (fs-plattform's kreg module on 10.0.0-RC46, branch `worktree-kreg-g10` at `7858137af4`, not pushed), 2026-10-30; the reporter found it present on graphitron `origin/main` the same day.
