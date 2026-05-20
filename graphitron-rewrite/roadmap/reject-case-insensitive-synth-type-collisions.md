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
group, demote every member to `UnclassifiedType` carrying
`Rejection.invalidSchema(...)` that names the group.
`GraphitronSchemaValidator.validateUnclassifiedType` already projects
that to a `ValidationError`; no validator-side change.

Demote-all (rather than first-wins) because there's no logical winner
between two SDL-declared types; renaming either is the fix, and silently
picking one tilts the schema's public surface against the author. Each
group member produces its own `ValidationError`, all naming the same
collision group, so the diagnostic is actionable from either entry point.

The message specialises by origin: synthesised `ConnectionType`,
`EdgeType`, and `PageInfoType` arms point at
`@asConnection(connectionName: ...)` as the actionable fix; SDL-declared
types get a generic rename hint.

`Locale.ROOT` for the fold; GraphQL identifiers are ASCII-only per the
spec rule `[_A-Za-z][_0-9A-Za-z]*`.

The check applies only to variants that emit a Java file. A
`default boolean producesEmittedFile() { return true; }` on the sealed
`GraphitronType` root is overridden to `false` on `ScalarType` and
`UnclassifiedType`; every other variant (TableType, NodeType, every
ResultType arm, RootType, every InputType arm, TableInputType,
ConnectionType, EdgeType, PageInfoType, PlainObjectType, EnumType,
TableInterfaceType, InterfaceType, UnionType, ErrorType) routes through
the generator's per-type emission paths and stays on the default.

## Tests

Pipeline coverage in `GraphitronSchemaBuilderTest.CaseInsensitiveTypeClashCase`:

- `sdlVsSdl`: two SDL `@record` types differing only in case both demote
  to `UnclassifiedType` with messages naming the full group.
- `synthVsSynth`: two `@asConnection` carriers with case-equivalent
  `connectionName:` arguments both demote; the message specialises to
  "synthesised connection type".
- `sdlVsSynth`: an SDL type case-equal to a synth Connection name
  demotes both.
- `threeWayGroup`: three case-equivalent SDL types each surface a
  `ValidationError` naming all three members.
- `noClashBaseline`: distinct names produce no collision diagnostics
  (negative-control).

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
