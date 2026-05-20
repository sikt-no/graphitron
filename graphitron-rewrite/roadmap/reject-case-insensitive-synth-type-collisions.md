---
id: R194
title: Reject case-insensitive type-name collisions
status: Spec
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
`GraphitronSchemaBuilder` immediately after `ConnectionPromoter.promote`
returns. For each case-fold collision group, demote every member to
`UnclassifiedType` carrying `Rejection.invalidSchema(...)` that names the
group. `GraphitronSchemaValidator.validateUnclassifiedType`
(`GraphitronSchemaValidator.java:918`) already projects that to a
`ValidationError`; no validator-side change.

Demote-all (rather than first-wins) because there's no logical winner
between two SDL-declared types; renaming either is the fix, and silently
picking one tilts the schema's public surface against the author. Each
group member produces its own `ValidationError`, all naming the same
collision group, so the diagnostic is actionable from either entry point.

`Locale.ROOT` for the fold; GraphQL identifiers are ASCII-only by spec
rule (`/[_A-Za-z][_0-9A-Za-z]*/`).

The check applies only to variants that emit a Java file. Add
`default boolean producesEmittedFile() { return true; }` to the sealed
`GraphitronType` root, overridden to `false` on `ScalarType` and
`UnclassifiedType`. Audit the full hierarchy at implementation time in
case other non-emitting variants exist.

## Implementation sites

- `GraphitronSchemaBuilder`: new private
  `rejectCaseInsensitiveTypeCollisions(BuildContext)` method, invoked
  after `ConnectionPromoter.promote(ctx)`.
- `GraphitronType`
  (`graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronType.java`):
  add `producesEmittedFile()` with `false` overrides on non-emitting variants.
- `TypeRegistry`: uses existing `demote(name, UnclassifiedType)`. No surface change.
- `GraphitronSchemaValidator`: no change.

## Tests

Primary signal: a `CaseInsensitiveTypeClashCase` arm in
`GraphitronSchemaBuilderTest` with sub-arms for sdlVsSdl, synthVsSynth,
sdlVsSynth, and a three-way group. Each asserts the colliding names land
as `UnclassifiedType` and that the `BuildResult` carries an
`INVALID_SCHEMA` `ValidationError` per member naming the full group.

Supplementary unit-tier coverage exercises the detector against a
hand-populated `TypeRegistry`: single-character difference, multi-member
group, no-clash baseline, non-emitting variants ignored.

Compilation and execution tiers not applicable: the rejection prevents
emission.

## Out of scope

- Legacy `MakeConnections` / legacy classifier in
  `graphitron-schema-transform/`. Same algorithmic shape; CLAUDE.md scope.
- Auto-mangling colliding names. We're choosing pushback precisely because
  the clash is an API-design signal.
- Federation cross-subgraph clashes (gateway tooling owns that).
- Derived-filename collisions (e.g. `<Type>Fetchers` stems that collide
  via different parents). The type-name check covers the bulk; revisit if
  a real consumer case ever surfaces independently.

## Open questions

1. *Demote-all vs. demote-non-first.* Spec body picks all. Demote-non-first
   cuts error-noise but picks an arbitrary winner. Genuinely open.
2. *Message specialisation per origin.* Generic ("type 'X' clashes with
   'Y'") versus origin-aware ("synthesised connection type 'X' from `Q.f`
   clashes with SDL type 'Y'", with the `@asConnection(connectionName: …)`
   hint when applicable). Recommendation: specialise; the synth case has an
   actionable hint the SDL case doesn't.
