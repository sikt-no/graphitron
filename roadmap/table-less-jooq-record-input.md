---
id: R837
title: "A jOOQ record with no table cannot be a @service input parameter"
status: Backlog
bucket: feature
priority: 5
theme: service
depends-on: []
created: 2026-08-26
last-updated: 2026-08-26
---

# A jOOQ record with no table cannot be a @service input parameter

A `@service` method can take a jOOQ record as its parameter, and graphitron builds that record from
the SDL input type's fields. Today that works only when the record is bound to a table. A jOOQ record
with no table behind it classifies fine and then fails to generate, and the message it fails with
describes the wrong problem.

## The two kinds of jOOQ record

jOOQ's generator emits one record class per table, and those are `TableRecord`s: a `FilmRecord` knows
it is a row of `film`, so it can answer "which columns do I have". The other kind has no table. It is
what an ad-hoc projection produces, `dsl.select(FILM.TITLE, FILM.LENGTH).fetch()` yielding a
`Record2<String, Integer>`, and it carries only the fields it was built with. Both implement
`org.jooq.Record`; only the first implements `org.jooq.TableRecord`.

Graphitron already distinguishes them. A result or input type backed by the first lands on
`GraphitronType.JooqTableRecordType` / `JooqTableRecordInputType`; one backed by the second lands on
`JooqRecordType` / `JooqRecordInputType`. Classification is correct on both axes.

## What breaks

The result axis is fine. Reading a value out of a record needs no construction: the generated fetcher
casts the source to `Record` and reads by name, so a table-less record behaves like any other.

The input axis is where it stops. `InputBeanResolver` has exactly one jOOQ arm and it is gated on
`JooqTableRecordInputType`. Anything else that is not a plain Java class or record falls through to
the JavaBean path, which wants a public no-arg constructor and setters. A jOOQ record has neither, so
the build fails with a message about instantiating a bean:

```
bean class '...' is abstract or an interface; the helper can only instantiate concrete classes
```

That message is about the path the resolver fell into, not about what is actually wrong.

## Why a bean constructor is the wrong primitive

A no-arg constructor was never how a jOOQ record is built. `DSLContext#newRecord(Field...)` builds
exactly this shape, and the arity-typed overloads (`newRecord(f1, f2)` returning `Record2<T1, T2>`,
through `Record22`) build the typed forms. Instantiation is not the missing piece.

What is missing is the **field list** those calls take. A table-bound record can produce one from its
table; a table-less record has no table to ask. That is the whole of the gap, and it is why the one
existing jOOQ arm is gated the way it is: the table is where the columns come from.

## The shape of the fix

Three parts, and the first is the only one that needs design.

1. **A `newRecord`-based arm.** Resolve each SDL input field to a column, build the `Field<?>` list
   from those columns, call `newRecord`, and set each value. The precedent for resolving columns
   without a declared table already exists: an unbound `PojoInputType` resolves its fields per usage
   against each consuming field's table, so one input reused on two tables resolves differently at
   each. The same resolution would supply this field list. Worth settling during Spec: whether that
   per-consumer resolution is the right rule here, or whether a table-less record input should
   require the columns to be named explicitly.

2. **A parameter declared as a jOOQ interface.** `looksLikeBeanCandidate` excludes everything under
   `org.jooq.*`, so a parameter typed `org.jooq.Record` or `Record2<String, Integer>` reaches no arm
   at all today. Those are the natural ways a consumer would spell this, and a `newRecord`-based arm
   can serve them: it constructs the record itself, so the declared parameter type only has to be
   assignable, and never has to be a concrete class.

3. **An honest rejection until then.** Whatever is not supported should say what is unsupported. A
   bean-construction failure for a jOOQ record sends the reader looking for a constructor that was
   never the answer.

## Where this came from

Found while promoting the classification corpus's type-verdict fixtures into worked examples on
`docs/architecture/reference/code-generation-triggers.adoc`. The `plain-jooq-record-backing` fixture
pins both leaves; its result half generates and its input half does not. The fixture's backing class
is hand-written, abstract, and implements `Record` without `TableRecord`, which is the only way to
name this shape in a signature at all: jOOQ's own implementations of it (`AbstractRecord`, the
`RecordImplN` family) are package-private, so there is no public concrete class to extend. That is
not a defect in the fixture, and making the fixture concrete is not the fix.

## Scope

Input path only. The result axis works and is not touched. This does not add a way to *declare* an
ad-hoc projection in SDL; it makes an already-classified input shape generate.

`JooqRecordInputType` currently has no generation behaviour distinct from `PojoInputType`. If the
Spec concludes the arm is not worth building, the alternative is to say so in the type model rather
than leave a leaf whose only observable difference is a confusing failure.
