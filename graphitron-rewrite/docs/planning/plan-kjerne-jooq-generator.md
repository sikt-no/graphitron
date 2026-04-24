# KjerneJooqGenerator Rewrite — Emit NodeId Metadata Constants

> **Status:** Spec
>
> Rewrite Sikt's externally-owned `KjerneJooqGenerator` so every platform-id table class additionally emits two public static finals — `__NODE_TYPE_ID` and `__NODE_KEY_COLUMNS` — that [plan-nodeid-directives.md](plan-nodeid-directives.md) reflects on. Scratch-only in this repo (proposed location: `scratch/kjerne-jooq/`); final sources move to Sikt's external repo and the rewrite picks up a released jar.

## Why

`plan-nodeid-directives.md` is **[In Progress]** with a hard external blocker: `JooqCatalog.nodeIdMetadata(tableSqlName)` must reflect on `public static final String __NODE_TYPE_ID` and `public static final Field<?>[] __NODE_KEY_COLUMNS` on the jOOQ-generated table class. No KjerneJooqGenerator release emits those today. Classifier Steps 2–6 of that plan cannot ship until one does.

Doing the generator work here — in the same branch family as the rewrite — lets us prototype emission, review the exact Java shape against the rewrite's reflection code, and hand Sikt a reference implementation rather than a prose spec.

## Current State

The current external generator (`no.fellesstudentsystem.kjerneapi_codegen.KjerneJooqGenerator`) subclasses jOOQ's `JavaGenerator` and:

1. Loads an XML `Configuration` at the path in the jOOQ property `kjernejooq_configuration_path`. The relevant portion for this plan is `<views><view>` entries carrying `viewId`, `viewName`, and `<plattformId><column>` ordered columns.
2. Optionally loads an `AvailableIndexes` catalogue from `kjernejooq_indexes_path` (Utdanningsregisteret skips this).
3. After `super.generate(database)`, writes an `IdHelpers.java` into the target package: a `Map<Integer, String> nameById` view-id → view-name lookup, a lazy `tableByName` lookup, and delegate methods (`lagId`, `unpack`, `getRows`, `getTable`) backed by `PlatformIdHelpers`.
4. Overrides `generateTableClassFooter(TableDefinition, JavaWriter)` to append, per configured view:
   - `getIndexes()` — when `view.getIndexes()` is non-empty.
   - `getViewId()` — the numeric view id.
   - `@Deprecated getIdFields()` — `List<TableField<R,?>>` of the platform-id columns.
   - `@Deprecated getIdValues(String id)` — `Object[]` unpack of an encoded id.
   - `getId()` — `SelectField<String>` assembling the id via `IdHelpers.lagId`.
   - `hasId(String)` / `hasIds(Set<String>)` / `hasIds(List<String>)` — `Condition` emitters built on a VALUES-RowN seek.
   - For each FK whose referenced table also has a configured view: `get<Qualifier>()` / `has<Qualifier>(...)` using the source-side columns mapped through `getSourceColumns` (which drops the `FODSELSDATO` leg and rewrites `PERSONNR` → `PERSONLOPENR` as legacy person-key workarounds).
5. Overrides `generateRecordClassFooter(TableDefinition, JavaWriter)` to append record-class `get<Qualifier>()` / `set<Qualifier>(String)` for the table's own platform id and for each FK-reachable view id.

Caveats observed in the current source:

- The `set<Qualifier>` emitter calls `this.changed(field, false)` only when the qualifier matches `^Id_*$` — i.e. only for the table's own primary id, not FK-derived ids. The pattern relies on `Id` being suffixed with trailing underscores when the synthesized name collides with a real column (`getName` loop).
- `generateRecordClassFooter`'s foreign-key loop has an inherited bug: when `otherView.getPlattformIdColumns() == null`, it `return`s instead of `continue`-ing, skipping all later FKs. The table-class version has the same shape. Fix-or-preserve is an open question.
- The `role` prefix logic (`KeyTools.generateRoleName`) is an external helper that folds `HAR` to empty; behaviour must be preserved by the new version.

## Desired End State

A rewrite that:

1. **Emits two new public static finals on every table class that has a configured view with a non-null `plattformIdColumns`:**

   ```java
   public static final String __NODE_TYPE_ID = "368";
   public static final Field<?>[] __NODE_KEY_COLUMNS = new Field<?>[] {
       INSTITUSJONSNR_EIER, STUDIEPROGRAMKODE, TERMINKODE_FRA, ARSTALL_FRA
   };
   ```

   — `__NODE_TYPE_ID` is `String.valueOf(view.getViewId())` (the integer `368` for `VITNEMALSTEKST`). `PlatformIdHelpers.lagId(viewId, vals)` encodes the numeric viewId into the base64 prefix; the constant therefore carries the same value that the composite ID already uses in production. `view.getViewName()` (the SQL view name) is kept for the `IdHelpers.nameById` lookup and is *not* the typeId. `__NODE_KEY_COLUMNS` lists the `TableField` references in the order of `<plattformId><column>` entries. Order is load-bearing per plan-nodeid-directives §KjerneJooqGenerator contract.

2. **Preserves every existing emission byte-for-byte equivalent** so consumers on the current generator continue to compile unchanged. Legacy-platform-id explicitly allows `getId()` / `hasId` / `hasIds` to stay — the rewrite stops calling them but non-graphitron callers still do. Record-class `set<Qualifier>` keeps its `changed(..., false)` only-on-primary-id quirk unless we explicitly decide to change it (open question).

3. **Carries the same XML config contract** so existing configuration files (Vitnemålsportalen, FS, Utdanningsregisteret) work without changes. The example at the top of this plan is representative; other elements of the XML that the generator does not use today remain ignored.

4. **Ships as a drop-in `JavaGenerator` subclass** — same extension point, same jOOQ properties (`kjernejooq_configuration_path`, `kjernejooq_indexes_path`), same target-package `IdHelpers.java` output. Existing Sikt `pom.xml` wiring is unchanged.

## Scratch Layout

Proposed: all scratch sources live under `scratch/kjerne-jooq/` at the repo root, gitignored from the main Maven build (not a module in `pom.xml`). Structure:

```
scratch/kjerne-jooq/
├── README.md                          — what this is, how to try it out
├── src/main/java/…/KjerneJooqGenerator.java
├── src/main/java/…/<helpers>          — KeyTools, etc., if we need to vendor them here
└── notes/                             — sketches, rejected alternatives
```

No `pom.xml` in this repo for it — building/testing happens when the user copies the files into the external repo. An `.mise.toml` task for "copy scratch to external repo" is out of scope.

If the user prefers a different location (e.g. a throwaway branch, or `docs/planning/kjerne-jooq/`), we switch before starting.

## What We're NOT Doing

- **Running tests inside this repo that depend on the new generator.** The Graphitron rewrite's test suite does not execute this code — verification happens when Sikt cuts a release and the rewrite picks it up. We can compile the scratch files manually during iteration but we won't wire them into `mvn verify`.
- **Removing `@Deprecated` method emissions.** Legacy-platform-id says these can stay. Removing them is a separate, later decision that trades "smaller generated code" against "breaks every non-graphitron consumer call site" — not justified by this plan's scope.
- **Changing the XML config shape.** Any new config fields (e.g. a `<typeIdOverride>` to decouple `__NODE_TYPE_ID` from `viewName`) would need a separate deliberation on the Sikt side and is out of scope here.
- **Fixing the `return`-instead-of-`continue` FK-loop bug** in `generateRecordClassFooter` / `generateTableClassFooter`. Behavioural preservation is the default; fixing it would be a named open question with a before/after snippet if we decide to.
- **Introducing a `typeId` ↔ jOOQ-table reflection helper inside the generator itself.** The rewrite-side consumer (`JooqCatalog.nodeIdMetadata`) handles reflection — the generator's job is only to emit the constants.

## Key Constraints

- **Java 17 for the generated code.** Consumers may still be on Java 17; the emitted `__NODE_KEY_COLUMNS = new Field<?>[] { ... }` syntax is plain Java 8+ and is safe. The generator source itself can target whatever Sikt's build uses today (ask during iteration).
- **jOOQ `JavaGenerator` API compatibility.** The current code uses `JavaWriter.println`, `out.tab(1).println`, `getStrategy().getFile/getFullJavaClassName/getJavaIdentifier/getJavaClassName`. The rewrite stays on these APIs — no jumping to a fresh jOOQ generator-strategy mechanism mid-rewrite.
- **`org.jooq.Field<?>[]` — not `TableField`.** The rewrite's `NodeIdMetadata` probe expects `public static final Field<?>[] __NODE_KEY_COLUMNS`. Declaring the narrower `TableField` type would still match if we change the rewrite-side probe, but `Field<?>` keeps the rewrite contract as written in plan-nodeid-directives §"KjerneJooqGenerator contract" (`Field<?>[]`) and matches the jOOQ method-reference signatures consumers already expect.

## Open Questions

1. **Scratch directory location** — `scratch/kjerne-jooq/` as proposed, or somewhere else? Needs a `.gitignore` carve-out if we want the files checked in despite not being built.
2. **Clean-slate rewrite or patched copy?** The current source is ~350 LOC with accreted naming and two small bugs. Options:
   - (a) Minimal surgical patch: paste the current source, add constant emission in one new method called from `generateTableClassFooter`, leave everything else identical.
   - (b) Clean-slate rewrite: extract small helpers (`EmitIdConstants`, `EmitHasMethods`, `EmitRecordAccessors`), fix the FK-loop bug, keep emission byte-equivalent for the pieces we don't deliberately change.
   - Preference? (b) is my recommendation iff we're comfortable shouldering the "prove byte-equivalence" burden; (a) is the cheap safe default.
3. ~~**`__NODE_TYPE_ID` value source.**~~ **Resolved:** stringified `view.getViewId()` (e.g. `"368"`). That is what `PlatformIdHelpers.lagId(Integer viewId, ...)` encodes into the base64 prefix today; the constant mirrors the existing on-the-wire value. `viewName` is a lookup key inside `IdHelpers.nameById`, not the typeId.
4. **FK-reached views.** Do we emit `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` constants only once per table (for the table's own view) or also per FK-reached view with a different suffix (e.g. `__PERSON_ID_TYPE_ID`)? Legacy-platform-id §"Classification" only reads the table's own metadata — so once per table is sufficient. Confirming before committing to the emission shape.
5. **Indexes / `PlatformIdHelpers` / `IdHelpers.java`** — all untouched by this plan? The `@nodeId` directive-support rewrite doesn't read them, but keeping the existing generator intact by default means the new version emits them identically.
6. **Verification plan.** Without a build in this repo, how do we know the new version is correct before Sikt integrates it? Options:
   - (a) Manual: copy into the external repo, run its tests, report back.
   - (b) Local harness: vendor a minimal jOOQ `Database` stub in `scratch/kjerne-jooq/` that exercises the generator against a fixture Sikt-shaped schema, run `mvn compile` against it manually.
   - (c) Assertion-free: rely on Sikt's downstream CI.
   - (a) seems like the pragmatic default; (b) is worth doing if we iterate more than 2–3 times.

## Phases

*Draft — to be filled in once open questions land.* Rough shape:

1. **Phase 1 — Constant emission.** Add a new method `generateIdConstants(TableDefinition, View, JavaWriter)` invoked at the top of `generateTableClassFooter` before any existing emitter. Emits exactly the two `public static final` fields and nothing else.
2. **Phase 2 — Behavioural preservation check.** Manual byte-diff (or small harness) against the current generator's output for a representative Vitnemålsportalen / FS configuration. Fix any regressions introduced by (2) — the plan's answer.
3. **Phase 3 — Handoff.** README in `scratch/kjerne-jooq/` captures the "copy into external repo" procedure. Roadmap entry moves to Pending Review when the sources are stable; Done when Sikt cuts a release the rewrite can depend on.

## Dependencies and Ordering

- **Depends on:** nothing in-tree. The rewrite-side consumer (`JooqCatalog.nodeIdMetadata`) is Step 2 of [plan-nodeid-directives.md](plan-nodeid-directives.md) and is independent of this plan.
- **Blocks:** [plan-nodeid-directives.md](plan-nodeid-directives.md) Steps 2–6 at release time. Until Sikt ships a release carrying these constants, the rewrite cannot integration-test `NodeType` synthesis against real tables. Pipeline tests can proceed (they use synthetic `JooqCatalog` fixtures).
