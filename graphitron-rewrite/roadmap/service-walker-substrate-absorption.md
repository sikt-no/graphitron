---
id: R256
title: "Absorb the service walker substrate: typed per-arm errors + multi-arg ctors"
status: Ready
bucket: structural
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-05-29
last-updated: 2026-05-30
---

# Absorb the service walker substrate: typed per-arm errors + multi-arg ctors

## Problem

R238 landed the `ServiceMethodCall` walker carrier (sealed `Static` | `Instance`,
each carrying `List<MappingEntry>` arg slots; `MappingEntry` is the sealed
`FromArg` | `FromContext` | `FromDsl`) across the four root sync `@service`
permits, plus the substrate every later slice inherits: the `WalkerResult<C>`
sealed wrapper, the `ServiceMethodCallError` sub-seal of `Rejection.AuthorError`,
graphitron-side `Diagnostic`/`Severity` with the LSP projector at the wire
boundary, and the orchestrator's collect-Err-exclude-field flow. But it shipped
`ServiceMethodCallWalker` as a **translator over an already-resolved
`MethodRef.Service`** (`ServiceMethodCallWalker.walk` receives `fieldDef` and does
not read it; the javadoc marks it "reserved for future direct-reflection use").
A behavior-preserving translator carries the substrate's gaps forward unchanged.
Three consequences were carved out of R238 so it could land Done without
overstating its surface; this item closes them.

**1. The typed taxonomy is hollow.** Of the ~10 `ServiceMethodCallError` arms
R238 designed, only two are reachable, because the walker translates a model that
already succeeded. Every *reflection-time* failure is produced **upstream in
`ServiceCatalog`** as `Rejection.structural(...)` prose, before the
`MethodRef.Service` the walker translates exists:

| Failure | Current production site |
|---------|-------------------------|
| class not loaded | `ServiceCatalog.java:352` / `:593` / `:678` (`Rejection.structural`) |
| return-type mismatch | `ServiceCatalog.java:204` / `:525` |
| instance-holder missing `(DSLContext)` ctor | `checkServiceInstanceHolderShape`, `:371` / `:382` |
| parameter names missing (no `-parameters`) | `ServiceCatalog.java:248` / `:563` |
| arg-to-parameter unbindable (DTO-source / unrecognized) | `ServiceCatalog.java:283-285`, `:323-333` |
| ambiguous overload | *never produced* — `methods.get(0)` at `:200` / `:516` / `:633` silently takes the first name match |
| method not found | already typed: `Rejection.unknownServiceMethod(...)` → `AuthorError.UnknownName(SERVICE_METHOD)` |

The two live arms (`MultipleDslContextSlots`, `ParameterUnbindable`) fire inside
the walker itself; everything else is prose `Structural` that loses its identity
at the LSP boundary (`Diagnostics.lspCodeOf` returns `null` for `Structural`).

**2. `Instance` is `(DSLContext)`-only; overloads resolve silently.**
`ServiceMethodCallWalker` hard-codes `ctorArgs = [new FromDsl()]` for every
`InstanceWithDslHolder` shape, mirroring `checkServiceInstanceHolderShape`'s
`(DSLContext)`-only constructor enforcement. There is no multi-arg ctor
resolution (`(DSLContext, ContextArg)`), and `methods.get(0)` resolves name
collisions by JVM declaration order with no arity check, so the designed
`AmbiguousMethod` arm has no producer.

**3. Transitional cruft.** `ContextArgumentClassifier.syntheticServiceMethodRef`
(`:160-168`) fabricates an empty `MethodRef.Service` (empty params, dummy
`CallShape.Static(false)`) solely so the `ConflictSite(MethodRef site, …)`
constructor (`ConflictSite.java:16`) accepts a walker-carrier conflict; the
bean-helper queue round-trips `ValueShape` composites back into synthetic
`CallSiteExtraction.InputBean`.

## Design fork: where reflection lives (resolved)

R238's continuation note called for "fresh SDL+classloader reflection" inside
`walker/internal/`, i.e. relocating `ServiceCatalog`'s parse-and-reflect work into
the walker so it owns a self-sufficient production path. **This Spec deliberately
revises that.** Per principles-architect against "Classification belongs at the
parse boundary": `ServiceCatalog` is one of the small enumerated set of files
permitted to read the `java.lang.reflect` tree; moving reflection into the walker
makes the walker a *new* reader of that tree, widening the parse boundary for no
gain. The substrate that matters (`WalkerResult` / `ServiceMethodCallError` /
`Diagnostic`) **already exists**. So "absorb the substrate" here means:

> Keep reflection at `ServiceCatalog`. Migrate its reflection-time failures from
> `Rejection.structural(...)` prose onto typed `ServiceMethodCallError` arms that
> flow through the existing `WalkerResult` / LSP substrate. The walker stays a
> translator; it gains richer `MethodRef.CallShape` inputs (multi-arg ctor, a
> disambiguated method) to translate, not a reflection engine.

This keeps the failure-classification predicate single-homed at the existing
parse-boundary reader and avoids the double-model "fresh reflection in the
walker" cost. (Full retirement of the `MethodRef.Service` → `ServiceMethodCall`
double model — making the walker the sole resolver — is explicitly **not** in
scope; file a separate item if ever wanted.)

The item title ("Absorb the service walker substrate") is retained for continuity
with the R238/R261 cross-references that already name this slug; read it as
"absorb the prose failures *onto* the existing typed substrate," not "absorb
reflection *into* the walker" — the latter is the reading this section rejects.

## Design fork: where the wire-coercion rejection lives (resolved)

The sibling item R261 (`wire-coercion-cast-guard`) retires the raw
`(DeclaredType) wireValue` cast fallthrough that produces runtime
`ClassCastException`s, and its `@service` slice (input-bean field, scalar arg)
overlaps this item. R261 `depends-on` R256, so the boundary between them must be
pinned here. Per principles-architect:

- The **wire-coercion compatibility judgment** ("does graphql-java's coercion
  output class for this SDL leaf assign to the declared Java type") is a single
  predicate identical across all arg-classification sites (input-bean field,
  service arg, `@condition` nested, `@externalField`). It belongs at the
  **classifier** that already holds both operands (`ServiceCatalog.argExtraction`,
  `InputBeanResolver`), single-homed, per "Generation-thinking / one predicate,
  one home" and "Validator mirrors classifier invariants". **It is R261's, not
  R256's.**
- **R256 owns the reflection-intrinsic failure arms and the propagation channel.**
  Its `ParameterUnbindable` / `input-bean-shape` arms do **not** compute wire
  compatibility; they *consume* the typed rejection R261's predicate produces and
  thread it through the walker substrate. `input-bean-shape` reports *structural*
  bean constructibility (constructible bean, required setters/ctor present), a
  distinct failure axis from scalar wire-compatibility; conflating the two would
  make one arm carry two unrelated kinds of failure.

Net: R256 builds the typed-rejection *channel* and the reflection-intrinsic arms;
R261 supplies the coercion *judgment* that flows through that channel for the
service slice and reuses the same predicate for the non-service sites. This gives
R261's "resolve it once, consistently with R256" a concrete shape and keeps the
two items from each implementing half a cast guard.

**The one shared file, pinned for order-independence.** Both items can touch
`ServiceMethodCallEmitter.scalarLeaf` (`:155` emits the `(SakRecord)`/`(Long)`
raw cast for `Direct`; `:159-162` carry the latent `JooqConvert`/`NodeIdDecodeKeys`/`default`
raw casts R261's secondary scope names). Retiring those raw casts is the *emitter
consequence of R261's judgment*, so **it is R261's, not R256's**. R256 leaves
`scalarLeaf` emitting exactly as today and only guarantees the
`ServiceMethodCallError` channel exists and is end-to-end testable *without* the
coercion predicate (a reflection-time arm fails the build on its own). That makes
the `depends-on` order-independent in practice: R256 landing first leaves the
`Direct` cast live until R261 (no regression, the bug already exists); R256 must
*not* "helpfully" harden `scalarLeaf`, or it has done R261's judgment work in the
wrong home.

## Deliverables

### 1. Typed per-arm errors (migrate prose → seal)

For each reflection-time failure in the table above, re-add the
`ServiceMethodCallError` arm R238 trimmed and produce it **at the `ServiceCatalog`
site** in place of the `Rejection.structural(...)` it returns today (the typed arm
implements `Rejection.AuthorError`, so it threads through the existing
`ServiceReflectionResult.rejection` → exclude-field flow unchanged). Note
`Diagnostics.lspCodeOf` dispatches on the *sub-seal* by `instanceof`
(`ServiceMethodCallError` / `UpdateRowsError`), not generically on an
`AuthorError.lspCode()`: arms re-added under `ServiceMethodCallError` need no
projector change, but the new `ReflectionError` sub-seal (see the partition below)
needs its own `lspCodeOf` branch plus an `lspCode()` declaration on the seal. Each
arm carries its failure's structured components (class name, method name,
expected vs actual return type, the unbindable parameter + candidate args, etc.),
not a baked string. Per the established five-piece checklist (R246/R238
precedent), each arm lands with all of:

1. the `record` arm in the appropriate seal's `permits` clause (see the
   shared-vs-service partition below);
2. `lspCode()` returning a stable namespaced string (`graphitron.service-method-call.*`
   for service-binding arms, `graphitron.reflect.*` for the shared reflection arms);
3. production at the `ServiceCatalog` reflection site, preserved verbatim through
   `WalkerResult.Err` / the `FieldBuilder` switch;
4. a `RejectionSeverityCoverageTest.sampleFor` branch (the test fails with
   "(no test sample)" the moment an arm is added without one);
5. a `typed-rejection.adoc` paragraph **and** an entry in that doc's
   drift-protection list (`SealedHierarchyDocCoverageTest` scans both).

Arms to land: `ClassNotLoaded`, `ReturnTypeMismatch`, `InstanceHolderMissingCtor`,
`ParameterNamesMissing`, `AmbiguousMethod`, the arg-mapping family (the
DTO-source/unrecognized-source/ctor-`FromArg` cases — confirm the final partition
against `ServiceCatalog:283-333` during implementation; the `FromArg`-in-`ctorArgs`
case is the cross-round invariant `ServiceMethodCall`'s javadoc already names).
`MultipleDslContextSlots` and `ParameterUnbindable` stay as-is.

**Shared-vs-service partition (one predicate, one home).** The three reflect
helpers `reflectServiceMethod` / `reflectTableMethod` / `reflectExternalField`
share their class-load (`:352`/`:593`/`:678`), return-type-mismatch (`:204`/`:525`),
parameter-names (`:248`/`:563`) and overload-resolution (`methods.get(0)` at
`:200`/`:516`/`:633`) code. So `ClassNotLoaded`, `ReturnTypeMismatch`,
`ParameterNamesMissing` and `AmbiguousMethod` are **reflection-intrinsic, not
`@service`-specific**. Migrating only the service call site would leave the
structurally identical `@tableMethod`/`@externalField` failures as prose
`Structural` and invite the next item to re-derive the same arm — the exact "one
predicate, one home" drift this Spec invokes elsewhere. **Resolution:** migrate at
the shared helper so all three callers produce the typed arm, and place the
reflection-intrinsic arms under a shared `ReflectionError` sub-seal of
`Rejection.AuthorError` (`graphitron.reflect.*`) rather than forcing
`ServiceMethodCallError.ClassNotLoaded` to be produced for a `@tableMethod`
failure. `ServiceMethodCallError` (`graphitron.service-method-call.*`) keeps the
service-*binding*-specific arms (`MultipleDslContextSlots`, `ParameterUnbindable`,
the arg-mapping family, and the instance-holder arm to the extent it is service
specific). Confirm the precise split — which arms are genuinely shared vs.
service-binding-specific, and whether `checkServiceInstanceHolderShape` is reached
by `@tableMethod` — against the three reflect helpers during implementation.
(`@externalField`-specific shape rejections — must be `public static`, `Field<X>`
return — stay out of scope; they ride that permit's own work.)

### 2. Validator mirror (only where a new fork is introduced)

Per "Validator mirrors classifier invariants": an unimplemented downstream branch
must **fail the build**, not produce broken code. But the two classes of arm here
mirror differently, and the Spec should not assert a single mechanism for both:

- **Reflection-time arms (deliverable 1) are self-mirroring.** They are produced
  *eagerly* at `ServiceCatalog` as a non-null `ServiceReflectionResult.rejection`,
  which already drives the exclude-field flow and fails the build. Migrating them
  from `Structural` prose to a typed arm changes *which* rejection fires, not
  *whether* the build fails. No new validator partition is needed for these; the
  pipeline-tier assertion in deliverable 3 (that the build fails with the typed
  arm and its `lspCode()`) is their mirror.
- **The genuinely new forks need an added check.** `AmbiguousMethod` (pick a method
  vs. reject, where `methods.get(0)` silently picked before) and the multi-arg-ctor
  unbindable case (deliverable 3) are *new* classify-time decisions; for these the
  mirror is the new `ServiceCatalog`-level rejection plus its pipeline-tier
  assertion. Note that the dispatch-partition validator the principle anchors on
  (`GeneratorCoverageTest`) does **not** cover this surface — R261 (`:50-52`)
  establishes it only partitions `GraphitronField` leaves, not the
  `@service`/`CallSiteExtraction` surface — so the mirror lives in the
  `ServiceCatalog`-rejection + pipeline-test pair, not in extending that partition.

### 3. Multi-arg constructors / silent first-match retirement

- Relax `checkServiceInstanceHolderShape` from `(DSLContext)`-only to resolve a
  constructor whose parameters are each bindable from a DSLContext slot or a
  context arg, and have `ServiceCatalog` emit a `MethodRef.CallShape` carrying the
  ordered ctor-arg sources. The walker translates that into
  `Instance.ctorArgs` as a `List<MappingEntry>` — the carrier already supports
  this (sealed `FromDsl` | `FromContext`), with `FromArg` invalid in `ctorArgs`
  per the existing cross-round invariant, so no carrier-shape change is needed,
  only the producer relaxation and the walker's hard-coded `[FromDsl]` removal.
  Relaxing the producer guard pulls two obligations in the *same change* (per
  "Classifier guarantees shape emitter assumptions"): (a) the
  at-most-one-`FromDsl`-per-round invariant, enforced today only for the method
  round (`ServiceMethodCallWalker:90-93`), must now also fire for `ctorArgs` — a
  multi-DSLContext constructor produces `MultipleDslContextSlots` with a new
  `Round.CONSTRUCTOR` discriminant the walker does not emit today; (b) the
  `(DSLContext, ContextArg)` ctor's `FromContext` name resolution must reuse the
  existing method-param context-binding path (`inferBindingsByType` / the ctx-keys
  walk), not a parallel ctor-only one.
- Replace `methods.get(0)` with arity-based disambiguation; a genuine tie
  produces `AmbiguousMethod` (deliverable 1).

### 4. Transitional-cruft cleanup

- Widen `ConflictSite.site` from `MethodRef` to a sealed two-arm identifier
  (`MethodRef` | `ServiceMethodCall`), retiring
  `ContextArgumentClassifier.syntheticServiceMethodRef`. This is "Sealed
  hierarchies over enums" / "Sub-taxonomies for resolution outcomes": the
  fabricated empty `MethodRef.Service` is a sentinel standing in for "this
  conflict came from a walker carrier"; the sealed widening lets the type carry
  that honestly.
- Retire the `ValueShape` → synthetic `CallSiteExtraction.InputBean` round-trip in
  the bean-helper queue: the queue currently re-wraps a `ValueShape` composite as
  a synthetic `InputBean` so the existing bean-helper path consumes it; it should
  consume the `ValueShape` directly. (Sketch the replacement during
  implementation; this is the most opaque of the four deliverables and the one
  most likely to grow — split it out if it does.)

## Tests

- **Unit tier** (`ServiceMethodCallWalkerTest` + a `ServiceCatalog`-level
  rejection test): one case per `ServiceMethodCallError.*` arm plus
  `UnknownName(SERVICE_METHOD)`; the walker-discipline test (no
  `Rejection.structural` for a service reflection failure); arity disambiguation
  (tie → `AmbiguousMethod`, unique arity → bound); a multi-arg-ctor case
  (`(DSLContext, ContextArg)` resolves); SDL-cycle / non-constructible bean →
  `input-bean-shape`.
- **Pipeline tier** (`GraphitronSchemaBuilderTest`): each reflection-time failure
  fails the build with the typed arm and its `lspCode()`, not prose; the validator
  mirror (deliverable 2) is asserted at this tier.
- **Drift guards**: `RejectionSeverityCoverageTest` and
  `SealedHierarchyDocCoverageTest` pass with every new arm sampled and documented.

## Affected code

- `ServiceCatalog.java` — typed arms (service-binding + shared reflection) in
  place of `Rejection.structural(...)` at all three reflect helpers; multi-arg
  ctor resolution in `checkServiceInstanceHolderShape`; arity disambiguation
  replacing `methods.get(0)`.
- `model/ServiceMethodCallError.java` — the re-added service-binding arms;
  `model/ReflectionError.java` (new sub-seal) — the shared reflection arms; both
  with `lspCode()`. `Diagnostics.lspCodeOf` — a branch for `ReflectionError`.
- `walker/ServiceMethodCallWalker.java` — translate richer `CallShape` into
  multi-source `Instance.ctorArgs`; drop the hard-coded `[FromDsl]`.
- `model/ConflictSite.java`, `ContextArgumentClassifier.java` — the sealed
  `site` widening; retire `syntheticServiceMethodRef`.
- `graphitron-lsp/.../RejectionSeverityCoverageTest.java`, `docs/typed-rejection.adoc`
  — sample + paragraph + drift-list per arm.

## Out of scope

- The wire-coercion compatibility *predicate* and its rejection (R261's
  classifier-side judgment); this item builds the channel it flows through and the
  reflection-intrinsic arms only.
- Relocating reflection into `walker/internal/` / retiring the
  `MethodRef.Service` → `ServiceMethodCall` double model (the "fresh reflection in
  the walker" reading of R238's note); a separate item if ever wanted.
- Async/DataLoader `@service` permits (R238 scoped the four root sync permits;
  this item inherits that scope).
