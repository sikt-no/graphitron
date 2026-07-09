---
id: R103
title: "Lift jOOQ column defaults onto input fields connected to that column"
status: Backlog
bucket: architecture
priority: 4
theme: mutation-write
depends-on: []
---

# Lift jOOQ column defaults onto input fields connected to that column

When a GraphQL input field is wired (via `@field(name:)` or implicit name match) to a jOOQ-generated column whose `DataType` carries a `defaulted()` expression, surface that default in the schema so clients can see it and so omitted values get a typed, server-known default rather than silently relying on the database. The current generator path already emits `DSL.defaultValue(dataType)` when an input key is absent at insert/update time (`TypeFetcherGenerator.java:1456`, `:1496`, `:1508`, `:1769`), so the runtime story is correct — the gap is purely on the *contract* side: the SDL says nothing about which input fields have a database-supplied default, and clients that introspect the schema have to read the migrations to find out.

The proposed shape is a directive on `INPUT_FIELD_DEFINITION` (working name `@columnDefault`) that the classifier auto-attaches when the bound jOOQ column reports `field.getDataType().defaulted()`. The directive carries the default's *kind* — literal vs. expression — because jOOQ's `defaultValue()` returns a `Field<T>` that may be a constant (`DEFAULT 0`, `DEFAULT 'open'`) or an unbound expression (`DEFAULT now()`, `DEFAULT nextval('seq')`). Literal defaults further lift into the SDL's native `= <literal>` slot on the input field so graphql-java's introspection surfaces them and clients see the same default the database will apply; expression defaults stay in the directive only because they can't be statically rendered at schema-build time. Open question for the spec: whether the `= <literal>` lift is a separate concern (an "auto-default" sub-step the user opts into) or part of the same directive — splitting them keeps the directive purely descriptive and lets schema authors override with their own `= <expr>` when they want a different client-facing default.

Out of scope to revisit at Spec time:

- **Generated columns vs. defaults.** jOOQ exposes `readonly()` for generated/identity columns separately from `defaulted()`. The directive proposed here is specifically for `defaulted()`; generated/identity columns are a different conversation (the input field for an identity PK is usually omitted from the GraphQL input entirely).
- **Validator coverage.** `DataType.defaultValue()` returning an expression that *evaluates* to a constant at the DB side (e.g. `DEFAULT '0'::numeric`) is up to jOOQ's classifier; whatever it reports as `Param`/non-`Param` is what the directive's kind-discriminator carries. No attempt to be smarter than jOOQ about this.
- **Read-side projection.** Output types backed by the same column do not need this; the database always returns the resolved value at SELECT time. This item is input-side only.
- **Override semantics.** If the SDL author already wrote an explicit `inputField: T = <literal>`, the directive should not silently replace it. Behaviour at conflict (warn, error, prefer SDL) is a Spec-time decision.
