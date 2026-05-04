---
id: R63
title: "Type UPSERT dialect requirement on the model"
status: Spec
bucket: architecture
priority: 7
theme: mutations-errors
depends-on: []
---

# Type UPSERT dialect requirement on the model

> UPSERT's "PostgreSQL-only" requirement is currently expressed as a free-form
> `CodeBlock` threaded through a 9-arg `buildDmlFetcher` overload. Lift it onto
> the model as typed data so the constraint is discoverable on
> `MutationUpsertTableField`, the verb-neutral skeleton stays verb-neutral,
> and the validator can eventually reject it at validate time once the
> consumer's target dialect is known at codegen time.

Background lives in commit `181c28f` ("R22: append architectural follow-ups
from post-shipping review"); this item carries that lift forward as standalone
work after R22's stub-lift phases shipped.

---

## Motivation

`TypeFetcherGenerator.buildMutationUpsertFetcher` builds a `postDslGuard`
`CodeBlock` inline (`TypeFetcherGenerator.java:1577-1584`):

```java
var postDslGuard = CodeBlock.builder()
    .beginControlFlow("if (dsl.dialect().name().startsWith($S))", "ORACLE")
    .addStatement("throw new $T($S)", UnsupportedOperationException.class,
        "@mutation(typeName: UPSERT) is not supported on Oracle: ...")
    .endControlFlow()
    .build();
```

The shared `buildDmlFetcher` was given a 9-arg overload
(`TypeFetcherGenerator.java:1642`) to accept this `CodeBlock` as a generic
pre-DSL guard. UPSERT is the only caller; INSERT, UPDATE, and DELETE pass
through the 8-arg overload and supply nothing.

Two smells:

1. *Discoverability.* "UPSERT only emits valid SQL on PostgreSQL" is an
   architectural fact about `MutationUpsertTableField`. Today it is expressed
   only as runtime-emitted Java that performs a string-prefix check on
   `dsl.dialect().name()`. A reader of the model cannot see the constraint;
   the shared skeleton grew an open-ended escape hatch that any future verb
   can fill with arbitrary code, blunting the verb-neutral promise.
2. *Validator can't see it.* Per *Validator mirrors classifier invariants*,
   classifier decisions that imply runtime failure modes should surface at
   validate time. The current shape can't: a free-form `CodeBlock` carries
   no semantic information the validator can read.

## Design

Two new model types under `no.sikt.graphitron.rewrite.model`:

```java
public sealed interface DialectRequirement
        permits DialectRequirement.None, DialectRequirement.RequiresFamily {

    record None() implements DialectRequirement {
        public static final None INSTANCE = new None();
    }

    record RequiresFamily(SqlDialectFamily family, String reason)
        implements DialectRequirement {}
}

public enum SqlDialectFamily {
    POSTGRES, ORACLE, MYSQL, MSSQL, H2, SQLITE, OTHER;

    /**
     * Maps a jOOQ {@code SQLDialect.name()} to a graphitron dialect family.
     * Name-prefix-based to cover commercial-only dialect enum values that
     * aren't present in the OSS jOOQ distribution (e.g. ORACLE12C,
     * ORACLE19C). Generated code consults this at request time via the
     * dispatched {@code dsl.dialect().name()} string.
     */
    public static SqlDialectFamily fromDialectName(String name) { ... }
}
```

Sealed-with-`None` (rather than `Optional<DialectRequirement>`) because:

- The principle "Sealed hierarchies over enums for typed information" prefers
  named arms over presence-or-absence.
- Future arms (`RejectsFamily`, `RequiresAnyOf(Set<SqlDialectFamily>)`) can
  land without touching every consumer.
- Pattern-matching reads tighter than a chain of `Optional.ifPresent`.

`SqlDialectFamily` is a graphitron-internal enum (not jOOQ's `SQLDialect`)
because the OSS jOOQ distribution omits commercial-only values like
`ORACLE19C`. The mapping function lives at the boundary, same shape as the
inline name-prefix check today.

`MutationField.DmlTableField` gains the requirement on the sealed supertype:

```java
sealed interface DmlTableField extends MutationField {
    DmlReturnExpression returnExpression();
    ArgumentRef.InputTypeArg.TableInputArg tableInputArg();
    DialectRequirement dialectRequirement();   // never null
    SourceLocation location();
}
```

Each of the four DML records gains a `DialectRequirement dialectRequirement`
component. The classifier (`FieldBuilder.buildDmlField`) populates:

- INSERT/DELETE: `DialectRequirement.None.INSTANCE`.
- UPDATE: `DialectRequirement.None.INSTANCE` for single-row; if R77 (bulk
  DML) has shipped, the bulk arm carries
  `RequiresFamily(SqlDialectFamily.POSTGRES, "...UPDATE...FROM (VALUES)
  ...")` because jOOQ silently emulates `UPDATE...FROM` on non-Postgres
  dialects with semantics drift. The classifier reads `tia.list()` to
  pick.
- UPSERT: `new DialectRequirement.RequiresFamily(SqlDialectFamily.POSTGRES,
  "...UPSERT...MERGE INTO...")`. The reason string carries the actionable
  message that today is hardcoded in the inline `CodeBlock`.

If R77 ships first, the inline `postDslGuard` `CodeBlock` lives on both
UPSERT and bulk-UPDATE call sites until R63 lifts both at once. The
9-arg `buildDmlFetcher` overload deletion in this plan covers both.

## Emitter rewrite

`buildDmlFetcher` consults `f.dialectRequirement()` and renders the runtime
guard via a single helper. The 9-arg `postDslGuard` overload deletes:

```java
private static MethodSpec buildDmlFetcher(
        String fetcherName,
        DmlReturnExpression rex,
        Optional<ErrorChannel> errorChannel,
        String inputArgName,
        TableRef tableRef,
        ResolvedTableNames tablesOnly,
        String tableLocal,
        String outputPackage,
        CodeBlock dmlChain,
        DialectRequirement dialectRequirement) {
    // ...
    builder.addStatement("$T dsl = graphitronContext(env).getDslContext(env)", DSL);
    emitDialectGuard(builder, dialectRequirement);
    // ...
}

private static void emitDialectGuard(MethodSpec.Builder b, DialectRequirement req) {
    switch (req) {
        case DialectRequirement.None ignored -> { /* no-op */ }
        case DialectRequirement.RequiresFamily r -> {
            b.beginControlFlow("if ($T.fromDialectName(dsl.dialect().name()) != $T.$L)",
                    SqlDialectFamily.class, SqlDialectFamily.class, r.family().name())
             .addStatement("throw new $T($S)",
                    UnsupportedOperationException.class, r.reason())
             .endControlFlow();
        }
    }
}
```

Verb-neutral skeleton stays verb-neutral; UPSERT no longer needs special-case
handling at the call site. INSERT/UPDATE/DELETE pass `DialectRequirement.None`
and the helper emits nothing.

The runtime check on the consumer side runs through `SqlDialectFamily`'s
mapping, not a string-prefix check, which is the same logic but lives once at
the boundary instead of being inlined into emitted Java.

## Tests

Pure model refactor, not a behaviour change. Acceptance gates:

- Existing execution-tier PostgreSQL tests against UPSERT pass unchanged
  (`upsertFilm_updateBranch_writesAndReturnsProjectedFilm`,
  `upsertFilm_insertBranch_writesAndReturnsProjectedFilm`). The emitted guard
  body changes (now consults `SqlDialectFamily.fromDialectName`), but the
  PostgreSQL path passes through the no-op arm exactly as it does today.
- New unit-tier assertion: `MutationUpsertTableField.dialectRequirement()`
  returns `RequiresFamily(POSTGRES, ...)`; the other three DML records return
  `None.INSTANCE`.
- New unit-tier assertion on `SqlDialectFamily.fromDialectName`: covers
  `POSTGRES` for any `POSTGRES*` name, `ORACLE` for `ORACLE`, `ORACLE12C`,
  `ORACLE19C`, `ORACLE23AI`, etc., `OTHER` for unrecognised names.
- New compilation-tier test: a generated UPSERT fetcher's body throws
  `UnsupportedOperationException` with the message from the model's
  `reason()` slot when invoked under an Oracle-flavoured `DSLContext`. Either
  via a Testcontainers Oracle instance, which is heavy, or via a stub
  `DSLContext` that reports `dialect().name() == "ORACLE19C"`; the latter is
  cheaper and the existing fixture path doesn't include Oracle.

## Implementation sites

- New file `model/DialectRequirement.java`: sealed interface, two arms.
- New file `model/SqlDialectFamily.java`: enum + `fromDialectName` mapping.
- `model/MutationField.java`: `DmlTableField` interface gains
  `dialectRequirement()`; each of the four DML records gains the component.
- `FieldBuilder.buildDmlField`: populate `DialectRequirement.None.INSTANCE`
  by default, `RequiresFamily(POSTGRES, ...)` for UPSERT and (if R77 has
  shipped) for bulk UPDATE.
- `TypeFetcherGenerator`:
  - Delete the 9-arg `buildDmlFetcher` overload.
  - Add `emitDialectGuard` private helper.
  - `buildDmlFetcher` (single overload now) calls `emitDialectGuard`.
  - `buildMutationUpsertFetcher` deletes the inline `postDslGuard`
    `CodeBlock`; the call site shrinks to the same shape as
    `buildMutationInsertFetcher` etc.
  - If R77 has shipped, `buildMutationUpdateFetcher`'s bulk arm deletes
    its inline `postDslGuard` symmetrically.
- `MappingsConstantNameDedup` and any other site that rebuilds DML records:
  thread the new component through.

## Future evolution (out of scope)

- *Validator-time rejection.* When the consumer's configured target dialect
  becomes known at codegen time (a separate plan; today the dialect is a
  request-time fact), the validator can fail the build per *Validator
  mirrors classifier invariants*. Until that lands, the runtime guard
  remains, but it is rendered from typed model data rather than from a
  hand-built `CodeBlock`.
- *Lifting the Oracle restriction.* A separate plan could hand-emit a
  PostgreSQL-equivalent `MERGE INTO` statement on Oracle (rather than relying
  on jOOQ's silent translation) when the dialect family is Oracle. The model
  shape introduced here (`DialectRequirement`) doesn't gate that work; it
  just typifies today's contract.

## Non-goals

- Cross-dialect abstraction over `RETURNING` and `ON CONFLICT`. Graphitron
  targets PostgreSQL; the rewrite does not introduce a dialect translation
  layer.
- Reusing `DialectRequirement` outside `DmlTableField`. The slot is added to
  `DmlTableField` only; promote it to a wider field-set if a second
  `DialectRequirement`-bearing field type appears.
