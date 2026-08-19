---
id: R709
title: "The routine call surface resolves from the census, and the LSP's last projection arm retires"
status: Spec
bucket: architecture
priority: 3
theme: lsp
depends-on: []
created: 2026-08-18
last-updated: 2026-08-19
---

# The routine call surface resolves from the census, and the LSP's last projection arm retires

The capture this item was filed for has shipped, ahead of the item and inside another one. What is
left is the half that was always going to be short: one view resolving a `@routine` application to
the call surface the census now holds, and the language server's producer reader picking it up so
the classification projection loses its last reader.

## What shipped elsewhere

R704 (`routine-composition-surface-from-facts`, Done, see `roadmap/changelog.md`) needed the same
catalog facts for the read surface it was deriving, and captured them as its slice 7:

* `sql_routine`, keyed like `sql_table` and carrying the routine's schema and SQL name, its
  `ROUTINE_TYPE` discriminator, and the two values nothing downstream can recompute:
  `routines_class_fqn` and `routines_method_name`, the generated `Routines` class and the
  value-parameter method an emitted FROM clause calls.
* `sql_routine_parameter`, the ordered IN parameters of that method with their Java binding types.

Both are captured off the resolved `Table<?>` inside the codegen scope, which is the shape this item
asked for. Two findings from that landing change what a reader here should expect. jOOQ generates no
`Routine` object for a table-valued function at all, only the result-table class and the `Routines`
convenience method, so the database's own parameter names survive only as jOOQ's camelCase transform
of them and the SQL types not at all; both columns were left out rather than shipped always-null.
And the class and method names are nullable, their nullness being what separates a routine that
takes no parameters from one whose call surface the generated model does not expose.

**The open question is answered.** This item asked whether the routine's own parameters belonged
here or whether that was an assumed yes. They landed, and the reason is the one this item guessed
at: a parameter list that did not name its method would not say which of jOOQ's several generated
forms it described, so the method name and the parameters are one fact and were captured together.

**The population is narrower than the relation's name.** Capture walks jOOQ's table census, and a
table-valued function is the one routine form that appears in it, so every `sql_routine` row today
is a function that also has a `sql_table` row. That is exactly the population `@routine` admits, so
nothing this item serves is missing; procedures and scalar routines arrive as further `routine_type`
values when something needs them, which is what the supertype shape is for. Table-valuedness is the
join to a `FUNCTION`-typed `sql_table` row, not a column.

## What is left

**The view.** R704 also added `@routine(name:)` to `intent_spelled_table`'s population, on the
ground that jOOQ models a function result as a catalog table and the spelling rule does not vary by
site. So the resolution this item wants is a short join rather than a new rule: `graphitron_routine`
to `intent_spelled_table` on `routine_ref` for the schema and name, then to `sql_routine` for the
pair. Ambiguity is already rows on the spelling view and should stay rows here, on the discipline
the neighbouring relations state.

Concretely, `intent_field_routine_method`, named and shaped as the sibling it is of
`intent_field_producer_method`: the authored reference resolved against the census, one row per
call surface the application matches.

```
CREATE VIEW intent_field_routine_method
  (graph_name, type_name, field_name, ordinal,
   source_name, table_schema, routine_name, class_name, method_name, parameters, candidates)
```

* Keyed on the application, not the field. `@routine` is repeatable and `graphitron_routine`
  carries an `ordinal`, so a field with two applications is two rows in written order, which is the
  order the chain interleaves them in.
* `class_name` and `method_name` are `sql_routine`'s `routines_class_fqn` and
  `routines_method_name`. A row where the generated model exposes no call surface (the DDL states
  those two columns are null together) is not a row here: this relation is the call surface, and
  naming a class that does not exist would be a worse answer than naming nothing.
* `parameters` is the count of `sql_routine_parameter` rows for the routine, which is the call
  surface's arity. See below, where it replaces a classpath count.
* `candidates` counts over the application, as the producer view counts over the reference: how
  many catalog routines this one `@routine(name:)` spelling resolves to, 1 on an unambiguous one.
* No `table_type` filter. A `@routine(name:)` that names a stored table resolves on the spelling
  view and then matches no `sql_routine` row, so the join says "not a callable" without this
  relation restating what `sql_table.table_type` already means. Absence has two causes and the two
  joins separate them: no spelled-table row means the name matched no catalog object at all, and a
  spelled-table row with no routine row means the object it matched is not callable.

**The language server's third arm.** `DeclTarget.projectedMethod` reads
`LspSchemaSnapshot.Built.fieldClassification` and maps a `RoutineBacked` classification to a
`DeclarationFacts.ProjectedMethod(className, methodName)`. That pair is exactly
`(routines_class_fqn, routines_method_name)`, so the arm becomes a read of the view above and the
`Built` requirement goes with it: a routine-backed field's jump to its call surface stops needing a
completed build behind it, which is the last coordinate on those surfaces that does.

**It is not a substitution at one call site, and the reason is worth stating.** An earlier reading
of this item said it was, on the ground that R638 settled the projection is read *before* the
statement is built rather than inside it. That ordering is exactly what a store read cannot inherit.
The pair being a value in hand is what lets `candidateClasses` and `candidateMethodNames` widen
themselves with `union(select(val(...)))`, and what lets `projectedArityArm` be keyed on a literal.
Read the pair from the store first and the request costs two statements, which
`DeclarationDefinitionStatementCountTest` and its hover sibling pin at one.

So the identity becomes a subquery rather than a value, and the shape it takes is the one every
other candidate population in that statement already has:

* `candidateClasses` unions `select class_name from intent_field_routine_method where <coordinate>`
  in place of the literal, and `candidateMethodNames` unions `method_name` the same way.
* `projectedArityArm` becomes an arm over the view, returning the pair and its arity together
  rather than an arity for a pair the caller named. What it returns is what `ofField` needs to build
  a `SourceMethod`, so `DeclarationFacts.ProjectedMethod` retires as an input and the pair arrives
  as a row like every other.
* `DeclTarget.of` loses its `ProjectedMethod` parameter, both consumers lose the
  `LspSchemaSnapshot` argument they pass only to produce one, and `DeclTarget.projectedMethod`
  retires outright.

The statement stays one, and it gets simpler rather than longer: the "what is not here" section of
`DeclarationFacts`' javadoc, which exists entirely to explain the handed-in value, goes with it.

**The arity stops depending on the classpath census.** `projectedArityArm` counts
`jvm_method_parameter` for the projected pair, and `projectedArity`'s own javadoc records what that
usually returns: 0, "where the generated sources are not on the scanned classpath, which is the
ordinary state for a jOOQ `Routines` class". The routine family knows the answer without the
generated class being scanned at all, `sql_routine_parameter` being the ordered parameter list of
exactly the method the pair names. So the view carries the count and the arity becomes correct in
the ordinary session rather than falling back to the name-level match. That is the loop this item is
an instance of: a consumer needed a fact, the fact was modeled at the grain it belongs to, and the
consumer that was approximating it stops.

**The projection retires.** `DeclTarget`'s `@routine` identity is the last thing keeping the
classification projection alive on the declaration surfaces, which `roadmap/lsp-reads-the-fact-store.md`
(R638) states in three places. Closing this arm is what lets that projection go.

## Coverage

The tiers are the ones the neighbouring work used, so this adds cases rather than a harness.

* The view's own rows, in the generator's fact-capture agreement tier: an unambiguous application
  resolving to one call surface, a spelling that matches a stored table rather than a callable
  (no rows, and the spelled-table row still there to say why), a routine whose generated model
  exposes no call surface (no rows), and a field carrying two applications (two rows, in written
  order).
* The declaration surfaces, in `graphitron-lsp`: a `@routine` field's declaration name jumping to
  its generated call surface, and hovering it, both **under an unavailable snapshot**. That is the
  assertion the whole item is for, and it is the one no existing case can make.
* The statement-count enforcers, unchanged in number and now covering the routine coordinate too:
  the fixture schemas gain a routine-backed field so the count is pinned where the new subqueries
  land.
* `DeclarationHoverOverlayParityTest` drops its `LspSchemaSnapshot.unavailable()` plumbing, the
  resolution no longer taking one.

## Ordering

One slice, because the halves do not stand alone: the view with no reader is a relation nobody
asked for, and the arm cannot land before the view exists. The prose sweep below rides in the same
commit, on the rule that a change retiring a claim retires the sentences making it.

## Prose that has gone stale

The claim these sites make is that no relation carries the routine call surface, which was true when
they were written and is not now. Each moves with the arm rather than ahead of it.

* `DeclarationFacts.java`'s class javadoc and its `ProjectedMethod` javadoc, both saying the catalog
  census has no routine family.
* `DeclTarget.java`'s class javadoc ("a derivation over jOOQ's routine codegen that no relation
  carries") and the comment at the projected-method arm.
* `DeclarationDefinitions.java` and `DeclarationHovers.java`, each naming the `@routine` arm as the
  one thing a completed build still buys.
* R638's own body, in the three places it names this item. That item is In Progress and owns its own
  text; the correction belongs to whoever is next in it, not to a passing edit from here.
