---
id: R358
title: "Guard table-name comparisons against case-sensitivity drift"
status: Spec
bucket: cleanup
priority: 5
theme: structural-refactor
depends-on: []
created: 2026-06-23
last-updated: 2026-06-23
---

# Guard table-name comparisons against case-sensitivity drift

`TableRef.tableName()` is deliberately **not** case-canonical: it is preserved verbatim from the `@table(name:)` directive value so author-facing diagnostics echo what the user wrote (`model/TableRef.java:14-19`, `JooqCatalog.java:983-987`). But the *same logical table* can be minted as two `TableRef`s carrying different casing, because the two `ServiceCatalog` resolution paths feed `toTableRef` different strings (`ServiceCatalog.java:57-64`):

| path | feeds `toTableRef` | value (ureg) |
|---|---|---|
| `resolveTable(sqlName)` | the **verbatim `@table(name:)` string** | `UTDANNINGSSPESIFIKASJON` |
| `resolveTableByRecordClass(recordClass)` | the **jOOQ `Table.getName()`** | `utdanningsspesifikasjon` |

The jOOQ catalog **lookup** side is already fully case-insensitive (`JooqCatalog.findTable` line 142, `findColumn` 812-813, FK lookups 341/476/531), which is why the UPPERCASE `@table` resolves at all. The drift exists only *downstream*, on the model field `tableName()`: it ships an identity string **less canonical than the catalog it came from**, so every consumer has to re-establish a case-folding contract the catalog already guaranteed. Comparison correctness then depends on **every** comparison site remembering `equalsIgnoreCase` over `equals`. The codebase mostly does (eight sites, below), but R357 was a real misclassification caused by the one site (`FieldBuilder.java:5114`) that drifted to case-sensitive `equals`. The drift is invisible until a schema with Oracle-style UPPERCASE `@table(name:)` over a lowercase jOOQ catalog hits the exact construction pair that mixes the two casing sources.

This item makes the idiom enforceable instead of remembered, in two phases: a cheap mechanical guard that lands green on its own, then the structural lift that moves the comparison onto the type so the guard becomes a backstop rather than the only line of defence.

## The drift surface (inventory)

Verified against `graphitron/src/main/java/no/sikt/graphitron/rewrite/`.

**Case-sensitive `.tableName().equals(` (the violations):**

- `FieldBuilder.java:5114` — `elementTableRef.get().tableName().equals(expectedSqlName)`. The R357 bug; **R357 converts this**. Listed here only because the Phase 1 guard must see zero of these once R357 lands.
- `FieldBuilder.java:3105` — `targetNodeType.table().tableName().equals(tableSqlName)`. An earlier draft classed this **inert** ("both operands share verbatim provenance, so they cannot diverge in case"); that does not hold. `targetNodeType` is resolved **by name** (`ctx.nodes.forName(explicitTypeName)`, `:3100`), not from the carrier; and the carrier operand (`tableSqlName = tableToMatch.tableName()`) is `inputArg.table()` (`:4003`) or `binding.tableRef()` (`:4312`) — the record-composite `@service` carrier path R357 showed can carry lowercase jOOQ casing against a verbatim UPPERCASE `@table`. So `:3105` is plausibly a **latent instance of the R357 bug**, one explicit `@nodeId(typeName:)` hop away, not a provably-inert site. **R358 converts it** — the correct fix whether or not the divergent shape is reachable; the reachability obligation this places on the implementer is in Tests.

**Case-insensitive `equalsIgnoreCase` table-name comparisons (the established idiom, eight sites, two orientations):**

- *left-operand* `X.tableName().equalsIgnoreCase(Y)`: `FieldBuilder.java:4422`, `:5504`, `:5720`; `TypeBuilder.java:799`; `GraphitronSchemaValidator.java:669`.
- *right-operand* `someString.equalsIgnoreCase(X.tableName())`: `NodeIdLeafResolver.java:292`, `:323`; `BuildContext.java:2393` (left side is jOOQ `getName()`).

The orientation split matters for the guard: a regex anchored on `\.tableName\(\)\.equals` only sees left-operand violations. A future right-operand drift (`foo.equals(x.tableName())`) slips past a left-anchored guard; Phase 2 closes this by construction (see below).

**Beyond comparisons:** `tableName()` is also threaded as a raw lookup key into consumers that re-implement case-folding internally: `ctx.catalog.nodeIdMetadata(rt.tableName())` (`FieldBuilder.java:1121`), `ctx.nodes.forTable(...)` (`FieldBuilder.java:3115/3634/3705/3919`). These are safe today (the lookups case-fold), but they confirm `tableName()` is a de-facto identity key whose case-insensitivity contract is assumed everywhere; they are out of scope here (see Scope).

## Plan

### Phase 1 — Guard the known footgun (must-ship, lands green independently)

A `@UnitTier` source-scan meta-test, modelled on `generators/UnifiedEmissionPinsTest` (which `Files.readString`s `src/main/java` sources and asserts a regex match count). New test `TableNameComparisonCaseGuardTest` in `no.sikt.graphitron.rewrite`:

- **Walk the right root, recursively.** `Files.walk(Path.of("src/main/java/no/sikt/graphitron/rewrite"))`, `.java` files only. The comparison sites span `FieldBuilder`, `TypeBuilder`, `GraphitronSchemaValidator`, `NodeIdLeafResolver`, `BuildContext` (package root) and `model/` (where the predicate will live), so a copy of the precedent's non-recursive `Files.list` over `generators/` would scan the wrong tree and **pass vacuously**, an enforced-but-checking-nothing guard. The test must assert it actually scanned a nonzero number of files.
- **Assert zero** matches of `\.tableName\(\)\s*\.equals\(` (case-sensitive, left-operand). `\.equals\(` does not match `\.equalsIgnoreCase\(`, so the eight idiom sites pass untouched.
- **Convert `FieldBuilder.java:3105`** to `equalsIgnoreCase` so the build stays green. (`:5114` is R357's; if R357 has not landed when Phase 1 is implemented, convert `:5114` too, defensively.)
- **Failure message** in the `UnifiedEmissionPinsTest` style: name the offending file/line, state the idiom (`TableRef.tableName()` is case-preserved; compare with `equalsIgnoreCase`, or `TableRef.sameTable(...)` once Phase 2 lands), and frame touching the test as the deliberate review point.

This phase delivers the stated goal, "make the idiom enforceable instead of remembered," for the spelling that actually bit. It is honest scaffolding, not a total invariant: see Scope for what it does **not** catch.

### Phase 2 — Move the predicate onto `TableRef` (the real lift)

Give `TableRef` the same-table predicate so the comparison lives once on the type instead of being re-derived at the call sites (the "model carries what the consumer needs" principle, `rewrite-design-principles.adoc:11/17`: *the same predicate evaluated by multiple consumers is a sign the resolver is under-specified, and an opportunity for one site to drift from another* — which is exactly R357):

```java
/** True when {@code other} names this table, case-insensitively. {@code tableName()} stays
 *  the verbatim diagnostic echo; this is the canonical identity comparison. */
public boolean sameTable(String other) {
    return other != null && tableName.equalsIgnoreCase(other);
}
public boolean denotesSameTableAs(TableRef other) {
    return other != null && sameTable(other.tableName());
}
```

- **Migrate the ~10 comparison sites** onto `sameTable(String)` / `denotesSameTableAs(TableRef)`, both orientations. String-operand sites take `ref.sameTable(str)`; `TableRef`-vs-`TableRef` sites (`FieldBuilder.java:5720`, `TypeBuilder.java:799`, `GraphitronSchemaValidator.java:669`) take `a.denotesSameTableAs(b)`. Behaviour-preserving; preserve each site's existing null-guard semantics (the predicate is null-safe, so redundant `!= null` guards can drop where the site allows).
- **Strengthen the guard.** With every call site routed through the predicate, the only legal raw `tableName()` comparison is inside `TableRef` itself. Tighten the scan to forbid **all** raw `tableName()` comparison (`\.tableName\(\)\s*\.equals` matching both `.equals(` and `.equalsIgnoreCase(`, plus the right-operand form `\.equals(IgnoreCase)?\([^;\n]*\.tableName\(\)`), **excluding `model/TableRef.java`** (the `UnifiedEmissionPinsTest` "exclude the unified emitter itself" pattern). Assert zero. This is orientation-complete and spelling-closed for the comparison mode: any reintroduced raw comparison, either orientation, either case-sensitivity, trips it.

After Phase 2 the guard is a backstop on a predicate that is correct by construction, not the sole defence against a remembered idiom.

## Tests

- **Phase 1 — the guard is its own test.** `TableNameComparisonCaseGuardTest` (`@UnitTier`): asserts zero case-sensitive matches and a nonzero scanned-file count (the vacuous-pass tripwire). On `:3105` the conversion to `equalsIgnoreCase` is a defensive fix for a shape that may be reachable (see the drift inventory), so the implementer must **either** (a) confirm from call-site provenance that case-divergence cannot reach `:3105` at *both* `:4003` and `:4312`, and drop a one-line comment at `:3105` recording why `equalsIgnoreCase` is load-bearing there, **or** (b) add a focused pipeline fixture (explicit `@nodeId(typeName:)` with UPPERCASE `@table` over lowercase jOOQ on the carrier path) pinning the classification. R357's `ServiceRecordCompositeCarrierPipelineTest` proves the case-mismatch shape on a *different* site (`:5114`/`:5116`), so it does not substitute for (b).
- **Phase 2 — predicate + behaviour preservation.** A focused `@UnitTier` test on `sameTable` / `denotesSameTableAs` (matching/ mismatched casing both directions, null arg). The ~10 migrated sites are behaviour-preserving, covered by the existing pipeline suite plus R357's case-mismatch fixture; no new pipeline fixture. The strengthened guard is the structural net.
- **Smoke:** full reactor green, `mvn -f graphitron-rewrite/pom.xml install -Plocal-db`.

## Scope and residual blind spots

Named deliberately so the guard is not mistaken for a total invariant (per the architect's read):

- **Other comparison spellings.** `Objects.equals(a, b)`, `==` on interned strings, `Set.contains`, `switch` on a name — none present today, none guarded. A syntactic guard pins spellings, and spellings are open-ended; this guard is a tripwire for the spelling that bit, not a proof that no same-table comparison can ever drift.
- **The lookup-key consumption mode.** `nodeIdMetadata(...)`, `nodes.forTable(...)` take `tableName()` as a raw key and case-fold internally. Safe today; not a comparison, so not guarded. Canonicalising `tableName()` at construction would subsume both modes but deletes the case-preservation invariant; out of scope (see Alternatives).
- **The `ColumnRef.sqlName()` sibling.** Structurally identical defect on the *column* identity string: one live `.sqlName().equals(` (`GraphitronSchemaValidator.java:883`) alongside six `equalsIgnoreCase` comparison sites (`FieldBuilder.java:5724`, `TypeBuilder.java:1273`, `BuildContext.java:2411`, `NodeIdLeafResolver.java:353/499/538`); the catalog-internal `findColumn` lookup (`JooqCatalog.java:813`) is the column analogue of `findTable`, the already-case-insensitive layer, not a comparison site. R358 scopes to `tableName` only; **file the `sqlName` case as its own Backlog item** rather than let it fall into the blind spot silently. (`:883`'s operands may, like `:3105`, be non-divergent; that item makes the per-site call.)

## Alternatives considered

- **Guard-only (drop Phase 2).** Defensible and cheap, but the guard pins one spelling of one orientation of one consumption mode; it does not move the under-specified predicate off ~10 consumers. "Make the idiom enforceable" is only half-delivered without the type owning the comparison. Recommend keeping Phase 2; it is the stub's "real lift" and is a contained, behaviour-preserving migration.
- **Canonicalise at the divergent producer.** Have `resolveTableByRecordClass` feed `toTableRef` a canonical name (or have `TableRef` carry a separate canonical identity alongside the verbatim `tableName()`), making `.equals` simply correct everywhere and subsuming the lookup-key mode too. This is the fuller fix, but it touches the case-preservation invariant boundary and `TableEntry`'s surface, with a larger blast radius for a priority-5 cleanup. The typed predicate is the more proportionate lift; this route is the natural successor if the `sqlName` item and the lookup-key mode are later folded into one canonical-identity pass.

## Sequencing & relations

- **depends-on R357** (`payload-list-dto-recordfield-source`): R357 converts `FieldBuilder.java:5114`; landing the Phase 1 guard before it would fail on `:5114`. R357 is a priority-2 migration unblocker; R358 is priority-5 cleanup, so the natural order is R357 → R358 and subsuming R357 would drag the unblocker to the cleanup's timeline. R358 owns `:3105` and the structural pin; if R357 slips, Phase 1 converts `:5114` defensively (see Phase 1).
- **R358 supersedes the *need* for the idiom, not the R357 commit.** R357 is the correct defensive change under the current producer contract and should ship as-is; R358 then makes future drift impossible to land.
- **R329** (shipped) — the `@service` record-composite carrier R357 rides on; its fixtures use lowercase `@table(name:)`, so they never reached this case-sensitivity guard.

## Out of scope

- Canonicalising `tableName()` at construction (would change author-facing diagnostic casing; see Alternatives).
- The `ColumnRef.sqlName()` sibling and the lookup-key consumer family (filed/named separately; see Scope).
- Comparison spellings other than `.equals` / `.equalsIgnoreCase` on `tableName()` (none present; not guarded speculatively).
