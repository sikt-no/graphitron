---
id: R139
title: "Dev-pipeline to LSP schema-snapshot side-channel; first client unknown-directive validator"
status: In Review
bucket: architecture
theme: lsp
depends-on: []
---

# Dev-pipeline to LSP schema-snapshot side-channel; first client unknown-directive validator

> Extend the existing dev-pipeline-to-LSP data flow (today: `CompletionData` shipped via `Workspace.setCatalog`) with a second side-channel that carries a projection of the parsed user schema. The first client is the unknown-directive validator in `Diagnostics`, which today warns on every directive not declared in graphitron's bundled `directives.graphqls` and flags user-declared directives (`@key`, `@external`, `@auth`-style guards, anything else) as if they were typos. With the snapshot in hand, the validator warns only when the snapshot is fresh AND the name resolves nowhere (`Built.Current` + name absent), silences when the name resolves in the user's schema (`Built.Current` + name found), and silences pre-build (`Unavailable`) and post-parse-failure (`Built.Previous`) on the conservative principle "do not punish the user for what we cannot reliably see". Same shape as the existing `externalReferences().isEmpty()` gate. The plumbing also unlocks hover prose, unknown-arg validation, and type-name completion on user-declared types as follow-on items, without revisiting the data flow.

This item is the directive-warning case from the design conversation that produced branch `claude/fix-graphql-directive-warnings-3hCFB`. The narrow framing (silence the warning) was rejected in favour of the broader plumbing: the LSP's current "only graphitron's bundled SDL exists" assumption is the root cause of the false positive, not the warning itself, and the cure should remove that assumption rather than paper over one symptom.

---

## Motivation

`Diagnostics.compute` (`graphitron-lsp/.../diagnostics/Diagnostics.java:87-91`) emits an LSP `Warning` for every directive name that is neither in the parsed bundled `directives.graphqls` registry (`LspVocabulary.registry()`) nor in the hard-coded `SPEC_BUILTIN_DIRECTIVES` set (`skip`, `include`, `deprecated`, `specifiedBy`, `oneOf`). User-authored directives fall through this arm: a schema carrying `@key(fields: "id")` or `@requiresAuthentication` gets pelted with one warning per use. The pinned test `unknownDirectiveProducesWarning` (`DiagnosticsTest.java:586`) currently locks this behaviour in for the typo case (`@tabel`), with no concession to user-declared shapes.

The data the validator needs to make a better decision already exists. The dev pipeline parses the user's full schema (graphitron directives plus user directives plus user types) to build the model that drives generation; the LSP's tree-sitter view sees only the open buffer's bytes. The pre-existing `CompletionData` shipped via `Workspace.setCatalog` (`Workspace.java:129`, called from `DevMojo.regenerate` at `:192`) shows the wedge: build-pipeline data flows into the LSP as a narrow projection on `volatile` refs; pre-`mvn compile` consumers degrade silently (`Diagnostics.java:325` `externalReferences().isEmpty()`). The unknown-directive validator should consume an equivalent projection of the parsed schema.

The narrow alternative (parse user-declared `directive` definitions from open workspace buffers via tree-sitter) was rejected in conversation as duplication: the build pipeline already handles `extend type`, multi-file aggregation, and the bundled-directives overlay merge; reimplementing any of that against tree-sitter drifts. Tree-sitter stays the primary parse for buffer-instant feedback, but the structural snapshot lives where the build pipeline already produces it.

---

## Design

### The wedge: a second `volatile` ref on `Workspace`

`Workspace` today carries `volatile CompletionData catalog` and exposes `setCatalog(CompletionData)` that swaps the ref and marks every open file for recalculation (`Workspace.java:129-132`). R139 adds `volatile LspSchemaSnapshot snapshot` next to it, swapped in lockstep with the catalog on each successful generator pass, read on every diagnostics / completion / hover request without taking the file lock.

The pair is mutated through three operations:

```java
public final class Workspace {
    private volatile CompletionData catalog;
    private volatile LspSchemaSnapshot snapshot = new LspSchemaSnapshot.Unavailable();

    public CompletionData catalog() { return catalog; }
    public LspSchemaSnapshot snapshot() { return snapshot; }

    /** Classpath-change path: only the catalog changes; the snapshot keeps its current state. */
    public void setCatalog(CompletionData catalog) {
        this.catalog = catalog;
        markAllForRecalculation();
    }

    /** Successful regenerate: catalog and snapshot swap atomically, one recalculation. */
    public void setCatalogAndSnapshot(CompletionData catalog, LspSchemaSnapshot.Built snapshot) {
        this.catalog = catalog;
        this.snapshot = snapshot;
        markAllForRecalculation();
    }

    /** Failed regenerate after a previous success: demote `Current` → `Previous` so consumers
     *  can tell the snapshot is stale. No-op on `Unavailable` or `Previous`. */
    public void demoteSnapshot() {
        if (snapshot instanceof LspSchemaSnapshot.Built.Current c) {
            this.snapshot = new LspSchemaSnapshot.Built.Previous(c.directives());
            markAllForRecalculation();
        }
    }
}
```

The atomic pair update (`setCatalogAndSnapshot`) closes a race against the prior two-setter shape: between separate `setCatalog` and `setSnapshot` calls a concurrent diagnostic computation could read a new catalog with an old snapshot or vice versa. The dev mojo's `regenerate` is the only producer of the success-path pair, so funnelling it through one setter is cheap; the classpath-only `rebuildCatalog` path keeps `setCatalog` (the snapshot doesn't change when only `.class` files moved).

Lifecycle by event:

| From            | Event                       | To                          |
|-----------------|-----------------------------|-----------------------------|
| `Unavailable`   | first successful regenerate | `Built.Current(directives)` |
| `Built.Current` or `Built.Previous` | successful regenerate    | `Built.Current(directives)` |
| `Built.Current` | regenerate fails            | `Built.Previous(directives)` (via `demoteSnapshot`) |
| `Built.Previous`| regenerate fails            | `Built.Previous` (no-op)    |
| `Unavailable`   | regenerate fails            | `Unavailable` (no-op)       |

`Built.Previous` exists because `DevMojo.regenerate` at `:193-197` and `:199-202` catches failures and *keeps the previous catalog* rather than zeroing it. The snapshot followed the same shape would silently leave a `Built` ref behind that no longer reflects what the user just typed; consumers reading it would punish the user for what was true a parse ago. The `Current` / `Previous` split lets consumers distinguish "we have fresh info" from "we have stale info", which the unknown-directive arm uses to silence under stale conditions (see below). The split is symmetric with `Diagnostics.validateClassName`'s pre-build gate on `catalog.externalReferences().isEmpty()` at `Diagnostics.java:325`: the principle is "do not punish the user for what we cannot reliably see yet", which extends naturally from pre-build silence to stale-data silence.

### The projection: `LspSchemaSnapshot`, sealed over availability and freshness

Per *Wire-format encoding is a boundary concern* and the catalog precedent (`CompletionData` projects `JooqCatalog` + `GraphQLSchema` rather than re-exporting either), the LSP module never sees `graphql.schema.idl.TypeDefinitionRegistry` directly. A new sealed interface in `graphitron`'s `catalog` package (sibling to `CompletionData`) carries the structural data the LSP needs. Two axes get their own sub-seal per *Sealed hierarchies over enums for typed information* and the principles' "sealed sub-interfaces per axis" pattern: availability (`Unavailable | Built`) and, within `Built`, freshness (`Current | Previous`):

```java
public sealed interface LspSchemaSnapshot permits Unavailable, Built {

    record Unavailable() implements LspSchemaSnapshot {}

    sealed interface Built extends LspSchemaSnapshot permits Built.Current, Built.Previous {
        List<DirectiveShape> directives();

        default Optional<DirectiveShape> directive(String name) {
            return directives().stream().filter(d -> d.name().equals(name)).findFirst();
        }

        record Current(List<DirectiveShape> directives) implements Built {}
        record Previous(List<DirectiveShape> directives) implements Built {}
    }

    static LspSchemaSnapshot unavailable() {
        return new Unavailable();
    }
}

public record DirectiveShape(
    String name,
    List<InputValueShape> args,
    Optional<String> description
) {}

public record InputValueShape(
    String name,
    TypeShape type,
    Optional<String> description
) {}

public sealed interface TypeShape permits TypeShape.Named, TypeShape.List {
    boolean nonNull();

    record Named(String typeName, boolean nonNull) implements TypeShape {}
    record List(TypeShape inner, boolean nonNull) implements TypeShape {}
}
```

Consumers indifferent to freshness switch on `Built` and read `directives()` / `directive(name)` regardless of which sub-variant they got (phase 2's hover surface, for example, will probably want to display whatever shape it has even when stale, with at most a "from last successful build" indicator). Consumers that need the freshness gate (phase 1's unknown-directive arm) switch through to `Built.Current` vs `Built.Previous`. The exhaustive switch is a compile-time check that every consumer makes that decision explicitly; future relaxations or splits surface as switch arms javac demands.

`TypeShape` is sealed rather than the rendered SDL string the first draft of this spec carried: phase 2's hover / arg-completion / unknown-arg-validation consumers all need to discriminate list vs. named and nullable vs. non-null, and the spec already commits to those as the same projection's clients. A `String typeRendering` field would force every phase 2 consumer to re-parse the rendering, the precise "switches on a raw string or recomputes a derived name" smell the principles call out. Rendering the sealed shape back to SDL for hover labels is a one-line walk where needed.

`Built` carrying `directives` (with full `DirectiveShape` args + descriptions, not just names) is deliberate, *not* speculative phase-2-shape. Phase 2 is the natural follow-on to phase 1 with the same data shape; landing the projection at half-shape now and widening on phase 2's first commit would mean two snapshot-version migrations in the LSP's first month. The defer-until-consumer rule the spec applies to `declaredTypeNames` (no phase 2 consumer named in this item; the type-name-completion surface lives in a separate prospective roadmap entry) does not apply to `DirectiveShape.args` / `description`, which the spec's own phase 2 section names as immediate next clients.

### The producer: extend `CatalogBuilder`, not a parallel builder

`CatalogBuilder` (`graphitron/.../rewrite/catalog/CatalogBuilder.java`, the existing producer of `CompletionData`) gains a `buildSnapshot(TypeDefinitionRegistry registry)` method returning `LspSchemaSnapshot.Built.Current`. It walks `registry.getDirectiveDefinitions().values()`, projects each into `DirectiveShape`, and wraps the list in `Current(...)`. The two projections share a producer because they share a parse, but they are independent enough (the catalog reads from jOOQ + external references; the snapshot reads from the registry) that no shared internal state is needed.

The registry fed to `buildSnapshot` is the *post-merge* one: `GraphitronSchemaBuilder.buildBundle(...)` has already applied multi-file `extend type` aggregation, the graphitron-bundled-directives overlay, and any tag-link synthesis. That's the registry with the most useful information; using the pre-merge raw user registry would miss `extend type` and overlay semantics. No bundled-directive exclusion is needed: the resolution function below (`DirectiveResolution.resolve`) short-circuits on bundled hits before consulting the snapshot, so a bundled directive present in the snapshot is observationally invisible. Removing the filter also removes the otherwise-awkward question of how the producer (in `graphitron`) would learn which names `LspVocabulary` (in `graphitron-lsp`) considers bundled.

`buildSnapshot` returns a `Built.Current` (never `Unavailable` or `Built.Previous`): availability and freshness are lifecycle facts owned by `Workspace` / `DevMojo`, not classifier facts owned by the producer. A failed parse never reaches `buildSnapshot` because `GraphQLRewriteGenerator.buildBundle(...)` throws before this point; the dev mojo's existing catch blocks at `:193-197` and `:199-202` translate that throw into a `demoteSnapshot()` call (see below). This keeps the producer's contract narrow: given a registry, return its directive projection. The contract pinned in the load-bearing table next.

`GraphQLRewriteGenerator` already constructs the registry during its parse step; `DevMojo.regenerate` at `:192` calls `new GraphQLRewriteGenerator(ctx).buildCatalog()`. The diff exposes a sibling method that returns both projections as a pair — `BuildOutput(CompletionData catalog, LspSchemaSnapshot.Built.Current snapshot)` — so the dev mojo can push them atomically through `Workspace.setCatalogAndSnapshot`.

### Load-bearing producer invariants

The `Built` family carries an implicit contract: the registry the snapshot was built from parsed without errors, and every user directive in that registry round-trips into `DirectiveShape`. The unknown-directive validator relies on this; if `buildSnapshot` ever returned a `Built` from a partial parse, the snapshot would silently authorise false negatives (a real typo gets a green light). The `Current` / `Previous` freshness split is *not* relaxation of this invariant: a `Previous` snapshot was a `Current` at its production time and reflected a clean parse at that point. Per *Validator mirrors classifier invariants* and *Classifier guarantees shape emitter assumptions*, this is an `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` pair across the two modules:

| Key                                              | Producer site                                          | Consumer site                                        |
|--------------------------------------------------|--------------------------------------------------------|------------------------------------------------------|
| `snapshot-built-implies-clean-parse`             | `CatalogBuilder.buildSnapshot` (only invoked on a clean post-merge registry; throws bubble up before it runs, never produces partial `Built`) | `Diagnostics`' unknown-directive arm (warns only under `Built.Current` + name-not-found; silences under `Unavailable` and `Built.Previous`) |
| `snapshot-directive-roundtrip-faithful`          | `CatalogBuilder.buildSnapshot` (every `DirectiveDefinition` in the registry produces exactly one `DirectiveShape` with the same name, args, description) | `Workspace.resolveDirective` (returns the snapshot's shape when bundled SDL lacks the name; consulted by both phase 1's directive-name check and phase 2's hover / arg-validation arms) |

The producer-side annotation is enforced by `LoadBearingGuaranteeAuditTest` (`LoadBearingGuaranteeAuditTest.java:44-46` scans `target/classes/no/sikt/graphitron/rewrite/`): the audit walks the `graphitron` module's compiled output, so the `@LoadBearingClassifierCheck` on `CatalogBuilder.buildSnapshot` is in scope and a duplicate or removed producer surfaces. The consumer sites in this pair live in `graphitron-lsp` (`Diagnostics`' unknown-directive arm and `Workspace.resolveDirective` under `no.sikt.graphitron.lsp.*`); the audit's current scan does not cross into that module's classes, so the `@DependsOnClassifierCheck` markers are placed for find-usages navigation, not auto-enforced as orphan-checks today.

Consumer-side enforcement rides on the pipeline-tier tests below: `userDeclaredDirectiveSilencedBySnapshot` pins the `Built.Current + User` arm, `unknownDirectiveSilencedByUnavailableSnapshot` pins the `Unavailable` arm, and `unknownDirectiveSilencedByStaleSnapshot` pins the `Built.Previous` arm. A relaxation that admits a partial parse, or a consumer arm removed entirely, breaks one of those tests rather than the audit. Widening the audit's scan to include `graphitron-lsp/target/classes/no/sikt/graphitron/lsp/` would close the gap, but requires moving (or duplicating) the audit into a test surface where both modules' compiled classes are on the classpath at test-run time, which is its own design decision and out of scope here. The follow-on item is tracked under "Future evolution" below.

### The boundary: snapshot lives on the catalog side, not in `LspVocabulary`

`LspVocabulary` is the parsed bundled SDL plus the hand-coded overlay; it is immutable per session and ships with the LSP jar. R139 keeps it that way. Composing the snapshot into the vocabulary's `TypeDefinitionRegistry` would mix two lifecycles (immutable session-lifetime vs. volatile build-driven) on one record, and the structural-invariant check in `LspVocabulary`'s constructor (every overlay coordinate resolves against the registry) only makes sense against the bundled SDL: user directives must not be allowed to satisfy graphitron-overlay coordinates.

Consumers that want the union of bundled + snapshot directives go through a single resolution function. The sealed result type and its static entrypoint:

```java
public sealed interface DirectiveResolution {
    record Bundled(DirectiveDefinition def) implements DirectiveResolution {}
    record User(DirectiveShape shape) implements DirectiveResolution {}
    record Unknown() implements DirectiveResolution {}

    static DirectiveResolution resolve(
            LspVocabulary vocabulary, LspSchemaSnapshot snapshot, String name) {
        var bundled = vocabulary.registry().getDirectiveDefinition(name);
        if (bundled.isPresent()) return new Bundled(bundled.get());
        return switch (snapshot) {
            case LspSchemaSnapshot.Unavailable u -> new Unknown();
            case LspSchemaSnapshot.Built b -> b.directive(name)
                .<DirectiveResolution>map(User::new)
                .orElseGet(Unknown::new);
        };
    }
}
```

The resolution function reads `Built` via the sealed sub-interface, so `Current` and `Previous` are treated identically here: if the user-declared `@key` was in the last successful parse, it still resolves to `User` after a parse failure, just sourced from a stale registry. Freshness is an *orthogonal* axis owned by the consumer: callers that care about the staleness of the resolved shape inspect the snapshot variant directly (see the unknown-directive arm below); callers that don't (most phase 2 hover / completion paths, which prefer stale info to no info) read straight off the resolution.

`Workspace` exposes `resolveDirective(String)` as a convenience wrapper that calls `DirectiveResolution.resolve(vocabulary, snapshot, name)` with the workspace's own vocabulary and the current `snapshot` volatile read. The wrapper exists for phase 2's hover / completion / arg-validation handlers, which run from request callbacks that naturally hold a `Workspace`. `Diagnostics.compute` is a `static` method invoked from a non-Workspace context (the existing two-overload at `Diagnostics.java:72-78`), so the unknown-directive arm calls the static `DirectiveResolution.resolve(...)` directly against the `LspSchemaSnapshot` and `LspVocabulary` already in scope.

The static entrypoint is the *only* path that encodes the bundled-vs-snapshot precedence (bundled shadows snapshot); the workspace wrapper and the diagnostic arm both delegate to it. Phase 2's hover and arg-validation arms switch on the same sealed result through `Workspace.resolveDirective`. The unknown-directive arm in `Diagnostics` becomes an exhaustive switch on the snapshot variant gated by the resolution outcome; see the next section.

The collision case (bundled shadows snapshot) covers the user accidentally declaring `directive @table(name: String)` in their own SDL: graphitron's overlay binds the directive's `name:` slot to a `CatalogTableBinding`, and the LSP must use graphitron's shape, not the user's. The collision is also a candidate for a separate diagnostic (warn the user that their directive shadows a graphitron one), but that is out of scope here.

---

## First client: the unknown-directive arm in `Diagnostics`

`Diagnostics.compute`'s static shape grows an `LspSchemaSnapshot` parameter alongside `CompletionData`: the existing two-overload at `Diagnostics.java:72-78` becomes `compute(WorkspaceFile, CompletionData, LspSchemaSnapshot)` plus the vocabulary-explicit `compute(LspVocabulary, WorkspaceFile, CompletionData, LspSchemaSnapshot)`. The unknown-directive arm reads through `DirectiveResolution.resolve` for the union and switches on the snapshot variant for the freshness-aware silence policy:

```java
var resolution = DirectiveResolution.resolve(vocabulary, snapshot, directiveName);
if (resolution instanceof DirectiveResolution.Bundled bundled) {
    // existing arg / required / leaf-coordinate validation path
    validateAgainst(bundled.def(), ...);
    continue;
}
switch (snapshot) {
    case LspSchemaSnapshot.Unavailable u -> { /* pre-build silence */ }
    case LspSchemaSnapshot.Built.Previous p -> { /* stale-snapshot silence */ }
    case LspSchemaSnapshot.Built.Current c -> {
        if (resolution instanceof DirectiveResolution.Unknown) {
            out.add(diagnostic(file, directive.nameNode(), DiagnosticSeverity.Warning,
                "Unknown directive '@" + directiveName + "'. Not declared in any "
                + "directive definition reachable from the parsed schema."));
        }
    }
}
```

Two concerns, two sites: `DirectiveResolution.resolve` encodes the bundled-vs-snapshot precedence (consulted once per directive); the inline switch encodes the freshness-aware warn policy (read once per snapshot variant). The exhaustive switch is the principle-compliant shape — javac demands an arm for every permit, so a future split (e.g. splitting `Unavailable` into `NoBuildYet | ParseFailed` if phase 2 wants to surface them differently) surfaces here as a compile error, not silent drift.

The silence-under-`Previous` policy is a deliberate trade. After a parse failure, the snapshot reflects the last successful parse: most directives in it are still valid, but a real typo introduced in the same edit that broke the parse would also be silenced until the user fixes the parse error. That regression is acceptable for the same reason `Unavailable` silences: the typo signal is small, the false-positive cost on every user-directive use is large, and the user is already handling the parse error which dominates the surface anyway. After experience with the LSP, if the silence-on-`Previous` trade turns out wrong, the policy is one arm-swap away (warn under `Previous` too); the seal makes that change a local edit.

Observable behaviours:

| Snapshot variant     | Bundled has it | Snapshot has it | Outcome                                |
|----------------------|----------------|-----------------|----------------------------------------|
| `Unavailable`        | no             | (n/a)           | silence (pre-build, no info)           |
| `Built.Previous`     | no             | yes             | silence (stale; name recognised)       |
| `Built.Previous`     | no             | no              | silence (stale; name might be new)     |
| `Built.Current`      | no             | yes             | silence (user-declared, recognised)    |
| `Built.Current`      | no             | no              | warn (genuine typo)                    |
| any                  | yes            | (n/a)           | proceed to arg validation (current path) |

The bundled-yes path is the existing behaviour; the snapshot extension only narrows the warn arm to `Built.Current + Unknown`.

The `SPEC_BUILTIN_DIRECTIVES` set at `Diagnostics.java:68-70` stays in place: spec built-ins do not necessarily appear in user schemas (graphql-java ships them implicitly) and a snapshot built from a user schema that does not redeclare them must not flag them as unknown. The list is closed (five names) and the check is a cheap short-circuit; the more general snapshot mechanism handles everything else.

### Test updates

- `unknownDirectiveProducesWarning` (`DiagnosticsTest.java:586`): the test grows an explicit `Built.Current(List.of())` argument (the post-build, fresh, no-user-directives state), since the typo case requires `Built.Current` for the warning to fire. The `@tabel` typo still warns under `Built.Current` when the snapshot lacks `@tabel`.
- New: `unknownDirectiveSilencedByUnavailableSnapshot` (pre-build state, currently `unknownDirectiveProducesWarning`'s implicit shape): the test passes `LspSchemaSnapshot.unavailable()` and asserts the warning does not fire.
- New: `unknownDirectiveSilencedByStaleSnapshot` (the parse-broken state after a previous success): the test passes `Built.Previous(List.of())` and asserts `@tabel` (a typo not in the snapshot) produces no diagnostics. Pins the silence-on-`Previous` trade so a regression to warn-on-`Previous` breaks here, not silently in the field.
- New: `userDeclaredDirectiveSilencedBySnapshot` (the canonical case in this item's motivation): the test passes `Built.Current(List.of(directiveShape("key", ...)))` and asserts `type Film @key(fields: "id") { ... }` produces no diagnostics.
- New: `userDeclaredDirectiveShadowedByBundledStillValidates` (the collision case): the test passes a `Built.Current` snapshot containing `@table` (the user accidentally redeclared); the bundled shape wins, so `@table(name: "missing_table")` still flags `missing_table` as an unknown table per the existing arm.

---

## Implementation sites

The file-by-file diff:

- New file `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/catalog/LspSchemaSnapshot.java`: the sealed interface plus `Unavailable` and the nested sealed `Built` (with `Current` / `Previous` permits), the `directive(name)` default method, and the `DirectiveShape`, `InputValueShape`, `TypeShape` projections (or one file per record under the same package, whichever lands cleaner).
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/catalog/CatalogBuilder.java`: add `buildSnapshot(TypeDefinitionRegistry registry)` returning `LspSchemaSnapshot.Built.Current`. Walks `registry.getDirectiveDefinitions().values()`, projects each into `DirectiveShape`, wraps in `Current(...)`. No bundled-directive filter: the resolver's bundled-shadows-snapshot precedence handles the collision.
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphQLRewriteGenerator.java`: add `buildOutput()` returning a `BuildOutput(CompletionData catalog, LspSchemaSnapshot.Built.Current snapshot)` pair, computed from the same post-merge `TypeDefinitionRegistry` that `buildCatalog()` already produces. The existing `buildCatalog()` stays as a delegating shim for `rebuildCatalog`'s classpath-only path. Throws on parse failure (existing behaviour); the dev mojo's catch translates that into `demoteSnapshot()`.
- `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/state/Workspace.java`: add `volatile LspSchemaSnapshot snapshot` field defaulting to `new LspSchemaSnapshot.Unavailable()`, `snapshot()` accessor mirroring `catalog()`, `setCatalogAndSnapshot(CompletionData, LspSchemaSnapshot.Built)` atomic-pair setter, `demoteSnapshot()` failure-path transition (no-op unless current ref is `Built.Current`), plus the `resolveDirective(String)` union accessor that returns the sealed `DirectiveResolution` shape. Existing `setCatalog(CompletionData)` stays for the classpath-rebuild path.
- `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/state/DirectiveResolution.java`: the sealed result type for `resolveDirective`, with `Bundled` / `User` / `Unknown` permits and the static `resolve(LspVocabulary, LspSchemaSnapshot, String)` entrypoint as described in the Boundary section.
- `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/diagnostics/Diagnostics.java`: the unknown-directive arm reads through `DirectiveResolution.resolve(vocabulary, snapshot, name)` and exhaustively switches on the snapshot variant for the silence-vs-warn policy, as in the code block above. `Diagnostics.compute`'s two overloads grow an `LspSchemaSnapshot` parameter after the existing `CompletionData`: `compute(WorkspaceFile, CompletionData, LspSchemaSnapshot)` and `compute(LspVocabulary, WorkspaceFile, CompletionData, LspSchemaSnapshot)`. Existing test callsites pass `LspSchemaSnapshot.unavailable()` when they do not care; the five new directive-arm cases pass `Built.Current(...)`, `Built.Previous(...)`, or `unavailable()` per the table above.
- `graphitron-rewrite/graphitron-maven-plugin/src/main/java/no/sikt/graphitron/rewrite/maven/DevMojo.java`: `regenerate` at `:176-202` calls `workspace.setCatalogAndSnapshot(output.catalog(), output.snapshot())` on the success path (replacing today's solo `setCatalog`); the inner catch at `:193-197` and outer catch at `:199-202` both call `workspace.demoteSnapshot()` instead of just `markAllForRecalculation` (demoteSnapshot calls `markAllForRecalculation` itself when it transitions). `rebuildCatalog` at `:205` keeps its solo `setCatalog` (classpath change does not invalidate the schema-derived snapshot).

The change is purely additive in the legacy-comparison sense: `LspVocabulary`, `CompletionData`, and the existing `Workspace.setCatalog` paths stay; the new path joins them. No deprecation, no shim.

---

## Tests

Four tiers, structurally identical to the pattern in R92's spec body.

### Unit-tier

- `LspSchemaSnapshotTest`: invariant pinning. `unavailable()` returns an `Unavailable` instance; `Built.directive(name)` is case-sensitive across both `Current` and `Previous` (the `directive` default method on `Built` is reached identically from both permits); `Built.directives()` is unmodifiable. `TypeShape` and `Built` sealed-switch exhaustiveness are `javac`-checked, not test-checked.
- `CatalogBuilderSnapshotTest`: build a snapshot from a hand-crafted `TypeDefinitionRegistry` (via `SchemaParser`), assert directive names round-trip into the returned `Built.Current`, assert that bundled directives appearing in the registry pass through into the snapshot's `directives` list unchanged (the resolver's precedence handles them; no producer-side filter). The `snapshot-built-implies-clean-parse` invariant is upstream of `buildSnapshot` (it only runs after a clean parse), so the test passes a registry that parses cleanly and asserts the projection; the partial-parse rejection is covered by `GraphQLRewriteGenerator`'s existing throw-on-parse-error behaviour.

### Pipeline-tier

- `DiagnosticsTest` adds the four new cases listed in "Test updates" above (the `Unavailable`, `Previous`, `Current+User`, and `Current+shadow` arms). The existing `unknownDirectiveProducesWarning` test grows an explicit `Built.Current(List.of())` snapshot so the typo case keeps firing under realistic post-build conditions.
- The existing `compute(WorkspaceFile, CompletionData)` test fixture grows an `LspSchemaSnapshot` parameter; all current call sites get `LspSchemaSnapshot.unavailable()`, except the unknown-directive cases that exercise the new arm.

### Compilation-tier

- `graphitron-sakila-example` adds a fixture `schema.graphqls` carrying one user-declared directive (e.g. `directive @auth(role: String!) on FIELD_DEFINITION` plus a single field application). R139 emits no new generated code, so the compile-tier signal here is *not* "the generated source compiles" (the primary use of the tier per `rewrite-design-principles.adoc:136-142`); it is the upstream precondition: the existing build pipeline accepts user-declared directives without error, which is the input contract `CatalogBuilder.buildSnapshot` relies on. The richer end-to-end shape coverage lives in `CatalogBuilderSnapshotTest` at the unit tier (registry → `Built`'s directive list, registry-with-parse-error → `Unavailable`); the compile-tier fixture is a regression guard for the input contract, not a behavioural check on R139's surface.

### Execution-tier

Not applicable: the LSP's behaviour is observable in the snapshot itself (pipeline tier) and in the diagnostics output (pipeline tier). There is no runtime path exercised by this change.

---

## Phasing

Two independent landings.

### Phase 1: snapshot plumbing plus unknown-directive client

*Status: shipped.* `LspSchemaSnapshot` lives at `graphitron/.../catalog/LspSchemaSnapshot.java` (sealed over `Unavailable | Built.{Current, Previous}`) with sibling projections `DirectiveShape`, `InputValueShape`, `TypeShape`. The producer is `CatalogBuilder.buildSnapshot(TypeDefinitionRegistry)` (annotated `@LoadBearingClassifierCheck` for the `snapshot-built-implies-clean-parse` and `snapshot-directive-roundtrip-faithful` keys). `GraphQLRewriteGenerator` exposes `buildOutput()` returning a `BuildOutput(CompletionData catalog, LspSchemaSnapshot.Built.Current snapshot)` pair from the same parsed registry. `Workspace` carries a `volatile LspSchemaSnapshot snapshot` field plus `setCatalogAndSnapshot` (success), `demoteSnapshot` (failure → `Built.Previous`), and the `resolveDirective(String)` wrapper that delegates to the sealed `DirectiveResolution.resolve(LspVocabulary, LspSchemaSnapshot, String)` at `graphitron-lsp/.../state/DirectiveResolution.java`. `DevMojo.regenerate` swaps the pair atomically on success and demotes on catch; the initial-startup path populates the snapshot if the first build succeeds. `Diagnostics.compute`'s two overloads grew an `LspSchemaSnapshot` parameter, with the unknown-directive arm reading through `DirectiveResolution.resolve` and switching exhaustively on the snapshot variant for the freshness-aware silence policy (warns only under `Built.Current + Unknown`).

Test coverage shipped: unit-tier `LspSchemaSnapshotTest` (lookup case sensitivity, unmodifiable defensive copy) and `CatalogBuilderSnapshotTest` (directive round-trip, list/non-null wrapping, no bundled-name filter, description round-trip); pipeline-tier additions to `DiagnosticsTest` (`unknownDirectiveSilencedByUnavailableSnapshot`, `unknownDirectiveSilencedByStaleSnapshot`, `userDeclaredDirectiveSilencedBySnapshot`, `userDeclaredDirectiveShadowedByBundledStillValidates`, plus the existing `unknownDirectiveProducesWarning` updated to pass `Built.Current(List.of())`); compilation-tier sakila fixture declares `directive @auth(role: String!) on FIELD_DEFINITION` and applies it on `Query.customers`.

Implementation note for the reviewer: the existing two-arg `Diagnostics.compute(file, catalog)` overload was replaced (not shimmed), so every test callsite gained an explicit third argument. The bulk pass used `LspSchemaSnapshot.unavailable()` for tests that don't exercise the unknown-directive arm; the new arm-specific tests use the appropriate variant. The `@DependsOnClassifierCheck` markers on the LSP-side consumers (`Diagnostics`, `Workspace.resolveDirective`) are find-usages-only as the spec called out: the audit's current scan only crosses `graphitron/target/classes/`, not `graphitron-lsp/target/classes/`, so the producer-side annotations are the load-bearing pair and the consumer markers document the contract.

### Phase 2: hovers, completion, arg validation against the snapshot

Phase 2 ships as its own roadmap item once phase 1 lands — see *R142: LSP hovers, arg-completion, and arg validation against the schema snapshot* (`lsp-user-directive-hovers-and-args.md`) for the active spec. The split exists because the structural plumbing is the conservative landing: it changes one diagnostic arm and a setter, no new completion / hover surface. Phase 2's user-visible surface is bigger and is best scoped against an in-tree phase 1 (so reviewers can see the snapshot's actual shape, not the spec's projection of it).

#### Phase 2 prep notes (from phase 1 implementation)

Three observations from shipping phase 1, recorded so the phase-2 spec author does not have to rediscover them:

1. *`Diagnostics.compute` parameter-list pressure.* Phase 1 grew the static signature to four parameters (`LspVocabulary, WorkspaceFile, CompletionData, LspSchemaSnapshot`). Phase 2 adds at least one more consumer arm in the same call path; if it adds a parameter again, decide between a `Diagnostics.Request` (or `Diagnostics.Context`) record that bundles the four into one, and accepting a five-parameter list. The shape choice is a phase 2 design call, not a refactor; pick it up-front.

2. *Cross-module audit gap is mechanical, not theoretical.* Phase 1 placed two find-usages-only `@DependsOnClassifierCheck` markers on the LSP side (`Diagnostics.compute`, `Workspace.resolveDirective`); phase 2 adds at least two more (`Hovers`, the unknown-arg arm). Without widening `LoadBearingGuaranteeAuditTest`'s scan to include `graphitron-lsp/target/classes/`, the count grows to ~4 orphan-by-design markers. Phase 2 is the natural moment to either close the gap (move/duplicate the audit into a test surface that sees both modules) or accept the documentation-only intent permanently and rename the markers if "load-bearing" is misleading. The "Future evolution" entry above tracks the underlying issue.

3. *`DevMojo` bootstrap shape.* The phase 1 implementation added a small `DevMojo.InitialOutput` record (catalog + snapshot, with `unavailable()` fallback on first-build failure) because the existing constructor took only a catalog and the snapshot arrived via a separate setter. Phase 2's producer-side projections (if any) land cleaner if the workspace constructor takes a single "initial state" value instead of a catalog plus a follow-up `setCatalogAndSnapshot`. Defer until a second producer-side projection actually needs the seam; mentioned here so it surfaces in the phase 2 review, not after the fact.

---

## Settled design notes

These were called out during the design conversation; each is resolved here so the implementer does not relitigate.

1. *Projection, not raw registry.* The LSP module never imports `graphql.schema.idl.TypeDefinitionRegistry` through the side-channel. The catalog precedent (`CompletionData` does not re-export `JooqCatalog`) and *Wire-format encoding is a boundary concern* both point the same direction. The cost is one extra record per directive at projection time; the benefit is the LSP's contract surface stays narrow and test fixtures stay cheap. `InputValueShape.type` is sealed (`TypeShape.Named | TypeShape.List`) rather than rendered SDL string, so phase 2's arg-validation consumers do not parse strings back.
2. *Bundled-SDL and snapshot have distinct lifecycles; do not merge them.* `LspVocabulary`'s structural invariant (every overlay coordinate resolves) is only meaningful against the bundled SDL. Composing the snapshot into the vocabulary's registry would either break that invariant (user directives could falsely satisfy overlay coordinates) or force the invariant check to discriminate, which is more complexity than the union-accessor on `Workspace` carries.
3. *Tree-sitter stays the primary parse.* The snapshot is project-wide and built-on-success; tree-sitter is per-buffer and instant. Replacing tree-sitter with the snapshot would lose buffer-instant feedback on every edit. The two coexist for the same reason the catalog coexists with `LspVocabulary`: different lifecycles, different consumers.
4. *Availability and freshness are two sealed axes, not one.* The snapshot is `Unavailable` only before the first successful parse; subsequent parse failures demote `Built.Current → Built.Previous` rather than collapsing to `Unavailable`, because the dev mojo's existing catch behaviour keeps the last good catalog and the snapshot follows the same shape. `Built.Previous` silences the unknown-directive arm under the same conservative principle as `Unavailable` ("do not punish the user for what we cannot reliably see"), extended to "no longer sure" alongside "not yet sure". Trade-off: a real typo present in the editor *before* a parse error fires stops warning when the parse breaks and reappears on next successful parse. Acceptable because the parse error dominates the surface and the user fixes it first; revisitable after experience by flipping `Previous` to warn (a local edit on the exhaustive switch).
5. *No bundled-directive filter in the producer.* `CatalogBuilder.buildSnapshot` ships every directive in the registry, including any that happen to coincide with bundled names. The resolver's bundled-shadows-snapshot precedence (`DirectiveResolution.resolve` short-circuits on `vocabulary.registry().getDirectiveDefinition(name)` before consulting the snapshot) makes the redundant snapshot entries observationally invisible. Filtering at the producer would force `graphitron` to learn which names are bundled, but the source of truth for that lives in `graphitron-lsp/LspVocabulary` — a reversed dependency. Letting the resolver handle precedence is both simpler and properly scoped: each module owns what it knows.
6. *Bundled directives shadow snapshot directives, via `DirectiveResolution.resolve`.* The collision case (user redeclares `@table`) is rare but real. Graphitron's overlay must win because the LSP's overlay-driven behaviour (catalog-table binding on `@table(name:)`) is keyed to graphitron's shape. The precedence is encoded once, on `DirectiveResolution.resolve`'s `bundled.isPresent()` short-circuit; consumers switch on the result, never re-check the precedence inline. A future enhancement can warn the user about the shadow; out of scope here.

---

## Future evolution (out of scope)

- *Hover and completion on user directives* (phase 2 above). The plumbing R139 ships is sufficient; the follow-on item is purely additive in `Hovers` / `ArgNameCompletions` / `Diagnostics`' unknown-arg arm.
- *Widen `LoadBearingGuaranteeAuditTest`'s scan to cross the graphitron / graphitron-lsp module boundary.* Today the audit walks the `graphitron` module's `target/classes` only (`LoadBearingGuaranteeAuditTest.java:44-46`); the snapshot's producer is in scope but its LSP-side consumers are not, so the `@DependsOnClassifierCheck` markers on `Diagnostics` and `Workspace.resolveDirective` are find-usages-only, not orphan-enforced. The right home for a cross-module audit is debatable (graphitron-lsp's test surface sees both modules' classes because of the existing dependency; alternatively a new test-only aggregator module), and the call has more to do with the audit's design than with R139's payload. Deferred until a second cross-module load-bearing pair lands.
- *Shadow-warning for user directives that redeclare bundled names.* A separate diagnostic on the bundled-vs-snapshot collision case. Cheap once the snapshot ships; deferred because it is a distinct user-visible behaviour, not a consequence of the plumbing.
- *Add a `declaredTypeNames` set to the `Built` arm.* Useful for type-name completion against user-declared types (including synthesised ones like `FilmConnection`, error-handler input shapes that the build pipeline produces). The build pipeline knows them; the snapshot can carry them. Deferred until phase 2 lands a type-name-completion consumer in the same commit. The seal makes additive widening cheap: a new field on `Built`, not a new permit, not a snapshot-version migration.
- *Lift the snapshot into `LspVocabulary` after a second consumer lands.* If three or more consumers all want the bundled-plus-snapshot union, the indirection through `Workspace.resolveDirective` starts to feel like the wrong seam. Revisit then; until then, the two lifecycles' separation is the load-bearing constraint.
- *Reconsider the silence-on-`Built.Previous` policy after some experience.* The conservative landing silences the unknown-directive arm under stale snapshots; in practice that may turn out too lenient (real typos hide for the duration of every parse glitch) or too strict (stale data is usually still correct for directive names, which change rarely). The seal makes the flip a one-arm edit on the exhaustive switch in `Diagnostics`; the load-bearing-key contract doesn't change. Wait until phase 2 has landed and we have user feedback before deciding.
- *Server-mode LSP (no dev mojo).* The LSP today is only usable under `mvn graphitron-rewrite:dev`; a future standalone server would need a different snapshot source (probably parsing the schema directly on the LSP's own classpath). The contract surface (`Workspace.setCatalogAndSnapshot` / `demoteSnapshot`) does not change; only the producer differs.

---

## Non-goals

- *Replacing the tree-sitter parse.* See settled design note 3.
- *Validating user directives' arg shapes against their declarations.* That is phase 2; phase 1 only silences the unknown-directive warn arm.
- *A pluggable snapshot producer.* The snapshot ships from `CatalogBuilder` alone; consumers cannot inject their own. Symmetric with the catalog.
- *Real-time pickup of user-directive edits via tree-sitter.* The snapshot updates only when the build pipeline runs (debounced via the existing `SchemaWatcher`). A directive declared in an unsaved buffer is invisible to the snapshot until the buffer is saved and the pipeline re-runs. This is the same trade-off the catalog already makes for jOOQ table edits, and it is acceptable here for the same reason: schema changes that introduce new directives are rare, and the warn-on-bundled-typo case (the only signal phase 1 keeps) still fires off tree-sitter regardless.
