---
id: R709
title: "The routine call surface resolves from the census, and the LSP's last projection arm retires"
status: Backlog
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

**The language server's third arm.** `DeclTarget.projectedMethod` reads
`LspSchemaSnapshot.Built.fieldClassification` and maps a `RoutineBacked` classification to a
`DeclarationFacts.ProjectedMethod(className, methodName)`. That pair is exactly
`(routines_class_fqn, routines_method_name)`, so the arm becomes a read of the view above and the
`Built` requirement goes with it: a routine-backed field's jump to its call surface stops needing a
completed build behind it, which is the last coordinate on those surfaces that does.

Only the identity is missing today, not the arity. `DeclarationFacts.projectedArityArm` already
counts `jvm_method_parameter` over the classpath census, keyed on the class and method the
projection named, so it keeps working unchanged once the name it is keyed on comes from the store
instead. R638 settled that the projection is read *before* the statement is built rather than inside
it, so this is a substitution at one call site and not a restructuring of the statement.

**The projection retires.** `DeclTarget`'s `@routine` identity is the last thing keeping the
classification projection alive on the declaration surfaces, which `roadmap/lsp-reads-the-fact-store.md`
(R638) states in three places. Closing this arm is what lets that projection go.

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
