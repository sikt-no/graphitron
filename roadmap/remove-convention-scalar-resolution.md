---
id: R464
title: "Remove convention-based scalar resolution; require @scalarType for non-spec scalars"
status: Backlog
bucket: cleanup
priority: 4
theme: classification-model
depends-on: []
created: 2026-07-10
last-updated: 2026-07-10
---

# Remove convention-based scalar resolution; require @scalarType for non-spec scalars

Graphitron carries a second, implicit path for binding non-spec scalars: a hardcoded "convention table" in `ScalarTypeResolver` (`ScalarTypeResolver.java:433-480`) mapping bare SDL scalar names (`BigDecimal`, `UUID`, `DateTime`, ...) to `graphql.scalars.ExtendedScalars` constants. When a scalar declaration carries no `@scalarType` directive, the classifier falls back to this table (`TypeBuilder.java:1244-1251`), resolves the constant against the consumer's codegen classpath, and recovers the Java type from its `Coercing`. This item removes that fallback: `@scalarType(scalar: "fully.qualified.Class.FIELD")` becomes the single, explicit way to bind any non-spec scalar. The convention table, its `resolveByConvention` path, the drift-guard test (`ScalarTypeResolverTest.conventionTable_coversEveryExtendedScalarsField`), the inverse-resolution test, and the LSP completion source that reads the table (`graphitron-lsp/.../ScalarTypeCompletions.java`) all go away.

The convention layer is not worth its carrying cost. Graphitron deliberately does **not** ship graphql-java-extended-scalars to consumers (it is `test` scope only; `graphitron/pom.xml:66-69` states "the runtime artifact is the consumer's choice and graphitron stays out of it"), so the fallback only fires for a consumer who has *already explicitly* added the library to their own classpath, exactly the consumer for whom writing one `@scalarType` line is trivial. In exchange for that marginal, opt-in-conditional convenience we pay: (a) recurring curation, every extended-scalars bump adds constants and trips the drift test, forcing a per-scalar map-or-exclude decision (the 3.20-era bump to extended-scalars 24.0 surfaced six: `YearMonth`, `Year`, `AccurateDuration`, `NominalDuration`, `SecondsSinceEpoch`, `HexColorCode`); and (b) a genuine footgun, because resolution keys off classpath presence, a consumer who pulls extended-scalars in *transitively* would have a bare `scalar BigDecimal` silently start resolving with no directive and no intent, so the same SDL classifies differently depending on the dependency graph. Requiring `@scalarType` everywhere collapses scalar binding to one explicit path, deletes the classpath-dependent surprise, and removes the drift test as a gate on future graphql-java / extended-scalars upgrades (it tripped during the graphql-java version-upgrade dry run and motivated this item). This is a pre-1.0 breaking change: the one bare `scalar BigDecimal` in the sakila-example schema (`graphitron-sakila-example/.../schema.graphqls:12`) must gain an explicit `@scalarType`, as must any consumer relying on directive-less extended-scalar names; the fix is mechanical and the actionable error message already points at it.

Deferred alternative (not chosen): keep the zero-config convenience but drop the hardcoded allow-list, resolving *any* `ExtendedScalars` constant reflectively (indexing by field name and `getName()` to preserve the `BigDecimal`/`GraphQLBigDecimal` dual spelling) so new library scalars flow through automatically without curation. This removes cost (a) but not footgun (b). Revisit only if consumers actually ask for directive-free scalar binding, which is considered unlikely.
