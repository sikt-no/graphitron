---
id: R852
title: "A field-site terminal condition hop resolves only through its signature, never the declared target"
status: Backlog
bucket: bug
priority: 3
theme: classification-model
depends-on: [reference-path-condition-terminal-column-scope]
created: 2026-08-27
last-updated: 2026-08-27
---

# A field-site terminal condition hop resolves only through its signature, never the declared target

Once `roadmap/reference-path-condition-terminal-column-scope.md` lands, both hop views resolve a
bare condition element through the route the condition method's signature declares. That covers a
filter path completely and covers a projection path only when the method is concrete-typed. The
Java-side rule (`BuildContext.resolveConditionJoinTarget`) has one more rung at the projection
site: a chain-ending element on an output field *prefers* the carrier field's return-type `@table`
binding as the target, which is what lets a method typed `(Table<?>, Table<?>)` work there.
`Customer.addressByCondition` in the sakila example schema is the exercised coordinate: its method,
`customerToAddress`, is wildcard-typed, so the signature route has no row, the chain never reaches
the terminal, and `intent_field_column_scope`'s `PATH_TERMINAL` arm stays silent at a coordinate
the generator resolves.

The preference is a property of the projection site, not of the hop: the input-field walk reads
the same field-site hop relation as a filter site and must not inherit it. So the rung belongs at
the field walk, in `intent_field_column_scope`'s territory (terminal-position detection plus the
field's navigated-type binding), not as a hop arm. Where a concrete second parameter and a declared
target disagree, the generator prefers the declared target and flags the mismatch as a validation
finding, which is the ordering this rung must transcribe.
