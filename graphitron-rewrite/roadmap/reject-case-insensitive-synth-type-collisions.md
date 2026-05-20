---
id: R194
title: Reject case-insensitive type-name collisions
status: In Review
bucket: correctness
priority: 3
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# Reject case-insensitive type-name collisions

## Problem

Graphitron emits one Java file per type-name stem, and GraphQL identifiers
are case-sensitive (`type Foo` and `type foo` parse as distinct types). On
case-insensitive filesystems (APFS, NTFS) any two type names differing only
in case map to the same filename and silently clobber each other; the
consumer build then fails downstream with cascading "cannot find symbol"
errors.

We're choosing to reject at build time rather than rework the naming
strategy (e.g. content-hash-suffixing collisions). Case-only collisions are
an API-design smell: two types `Poengklasse` and `poengklasse` are almost
always an author error, and on the rare occasion they aren't, downstream
clients will struggle with them too. Graphitron should push back, not paper
over.

Three triggers collapse into one rule: SDL-vs-SDL (the author writes two
case-equivalent types), synth-vs-synth (two `@asConnection` carriers
differing only in case derive case-clashing Connection names; this is the
consumer-reported repro), and SDL-vs-synth.

## Design

One case-folded uniqueness pass over `ctx.typeRegistry.entries()`, run in
`GraphitronSchemaBuilder` after `ConnectionPromoter.promote` *and*
`ConnectionPromoter.rebuildAssembledForConnections` return. (The spec
originally called for placement immediately after `promote`; running
after the schema rebuild instead keeps the assembled `GraphQLSchema`
typeRefs resolvable when a synthesised Connection type is demoted, and
avoids pruning carrier rewrites that point at the demoted name. The
validator-side outcome is identical.) For each case-fold collision
group, demote every member to `UnclassifiedType` carrying a typed
`Rejection.InvalidSchema.CaseFoldCollision(group, origin)` rejection.
`GraphitronSchemaValidator.validateUnclassifiedType` already projects
that to a `ValidationError`; no validator-side change.

Demote-all (rather than first-wins) because there's no logical winner
between two SDL-declared types; renaming either is the fix, and silently
picking one tilts the schema's public surface against the author. Each
group member produces its own `ValidationError`, all naming the same
collision group, so the diagnostic is actionable from either entry point.

`CaseFoldCollision` is a sealed sub-variant of `Rejection.InvalidSchema`
carrying `List<String> group` (every case-equivalent name in the
collision, in registry-iteration order), a `CaseFoldCollision.Origin`
enum (`SDL`, `SYNTH_CONNECTION`, `SYNTH_EDGE`, `SYNTH_PAGE_INFO`), and a
`String prefix` that accumulates wrap-site prose threaded through
`prefixedWith` (the validator prepends `"Type 'X': "` on every
diagnostic). The `message()` override prepends `prefix` and switches on
`origin` to specialise the actionable fix hint: synthesised Connection /
Edge / PageInfo arms point at `@asConnection(connectionName: ...)`; SDL
types get a generic rename hint. The builder computes `origin` via a
`switch` on the demoted variant's classifier type and constructs one
`CaseFoldCollision` per member (factory entry seeds `prefix = ""`).
`prefixedWith` returns a new `CaseFoldCollision` with the accumulated
prefix; the typed `group` and `origin` survive the validator's
`prefixedWith("Type 'X': ")` wrap so downstream consumers (LSP fix-its,
watch-mode formatter) can `instanceof CaseFoldCollision` on
`ValidationError.rejection` and render the collision group structurally
without re-parsing prose, per the R58 contract that every sealed leaf
preserves its typed variant under `prefixedWith`.

`Locale.ROOT` for the fold; GraphQL identifiers are ASCII-only per the
spec rule `[_A-Za-z][_0-9A-Za-z]*`.

The check applies only to variants that emit a Java file. The
file-emission property is a generation-side capability, not a
classification concern, so it lives on a standalone capability interface
`EmitsPerTypeFile` (mirroring the codebase's `SqlGeneratingField` /
`BatchKeyField` precedent) rather than as a default method on the
`GraphitronType` sealed root. Sealed sub-interfaces `TableBackedType`,
`ResultType`, and `InputType` extend it (so every arm under each
inherits the capability); leaf variants `RootType`, `TableInputType`,
`ConnectionType`, `EdgeType`, `PageInfoType`, `PlainObjectType`,
`EnumType`, `TableInterfaceType`, `InterfaceType`, `UnionType`, and
`ErrorType` implement it directly. `ScalarType` (handled by
`ScalarTypeResolver`, no per-type file) and `UnclassifiedType` (already
demoted, never reaches emission) deliberately do not. The detector
filters via `instanceof EmitsPerTypeFile`.

## Tests

Pipeline coverage in `GraphitronSchemaBuilderTest.CaseInsensitiveTypeClashCase`:

- `sdlVsSdl`: two SDL `@record` types differing only in case both demote
  to `UnclassifiedType` with messages naming the full group.
- `synthVsSynth`: two `@asConnection` carriers with case-equivalent
  `connectionName:` arguments both demote; the message specialises to
  `SYNTH_CONNECTION` ("synthesised connection type").
- `sdlVsSynth`: an SDL type case-equal to a synth Connection name
  demotes both, exercising the `SDL` and `SYNTH_CONNECTION` origin arms
  in the same group.
- `synthEdgeVsSdl`: an SDL type case-equal to a synth Edge name (derived
  from a `connectionName:` argument by replacing `Connection` with
  `Edge`) demotes both, exercising the `SYNTH_EDGE` origin arm.
- `synthPageInfoVsSdl`: an SDL type case-equal to the synthesised
  `PageInfo` demotes both, exercising the `SYNTH_PAGE_INFO` origin arm.
- `threeWayGroup`: three case-equivalent SDL types each surface a
  `ValidationError` naming all three members.
- `noClashBaseline`: distinct names produce no collision diagnostics
  (negative-control).

Compilation and execution tiers not applicable: the rejection prevents
emission.

## Implementation status

- Phase 1 shipped at 5e5f5e3: builder pass + initial 5 pipeline cases.
- Phase 2 shipped at a1feace: `EmitsPerTypeFile` capability lift, typed
  `CaseFoldCollision` arm, `synthEdgeVsSdl` + `synthPageInfoVsSdl` pipeline
  cases, doc tree + mermaid-class + severity-coverage updates.
- Phase 3 shipped at HEAD: `CaseFoldCollision` carries an accumulating
  `String prefix`; `prefixedWith` returns the same variant rather than
  degrading to `Structural`, so the typed `group` / `origin` survive the
  validator's wrap and reach `ValidationError.rejection` per the R58
  contract. `RejectionRenderingTest.prefixedWithPreservesCaseFoldCollisionTypedFields`
  pins the contract for this leaf alongside the four existing
  preservation tests.

## Out of scope

- Legacy `MakeConnections` / legacy classifier in
  `graphitron-schema-transform/`. Same algorithmic shape; CLAUDE.md scope.
- Auto-mangling colliding names. We're choosing pushback precisely because
  the clash is an API-design signal.
- Federation cross-subgraph clashes (gateway tooling owns that).
- Derived-filename collisions (e.g. `<Type>Fetchers` stems that collide
  via different parents). The type-name check covers the bulk; revisit if
  a real consumer case ever surfaces independently.
