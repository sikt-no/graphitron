---
id: R194
title: Reject case-insensitive synthesised type-name collisions
status: Spec
bucket: correctness
priority: 3
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# Reject case-insensitive synthesised type-name collisions

## Problem

When two carrier fields on the same parent type differ only in the case of one
or more letters, `@asConnection`'s default name-synthesis derives two Connection
(and Edge) type names that are valid-but-only-case-different in GraphQL but
collide when graphitron writes them out as Java source files on a
case-insensitive filesystem (macOS APFS default, Windows NTFS default). The
files clobber each other silently, the consumer build picks up only one of the
two pairs, and `javac` fails downstream with cascading "cannot find symbol"
errors that don't name the root cause. Concrete repro reported by a consumer:

```graphql
extend type Query {
    poengklasserv2(filter: PoengklasseV2FilterInput): [Poengklasse!]
        @asConnection @deprecated(reason: "Bruk poengklasserV2")
    poengklasserV2(filter: PoengklasseV2FilterInput): [Poengklasse!]
        @asConnection
}
```

`ConnectionPromoter.resolveConnectionName`
(`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/ConnectionPromoter.java:326`)
derives:

- `Query.poengklasserv2` → `QueryPoengklasserv2Connection` /
  `QueryPoengklasserv2Edge`
- `Query.poengklasserV2` → `QueryPoengklasserV2Connection` /
  `QueryPoengklasserV2Edge`

Both pairs register cleanly via `TypeRegistry.synthesize` because the registry
is case-sensitive (`LinkedHashMap` on the String key, see
`TypeRegistry.java:32`). The build then emits four `.java` files whose case-
insensitive names match in pairs; one of each pair overwrites the other on
the consumer's filesystem during code generation. The author's typo
(`poengklasserv2` vs. `poengklasserV2`) is the underlying cause, but graphitron
should refuse to participate in producing code that won't compile, not silently
emit it.

## Today's behaviour

`ConnectionPromoter.promote(BuildContext)`
(`ConnectionPromoter.java:81`) walks every carrier and registers
`ConnectionType` / `EdgeType` entries via `ctx.typeRegistry.synthesize(...)`
keyed by the synthesised name. The registry rejects exact-string duplicates
(`TypeRegistry.synthesize` throws on a re-classify), but a case-only differing
name is, by design, a distinct registry key and synthesises happily. No
downstream gate catches the clash: `GraphitronSchemaValidator` walks the
type-axis arm by arm but has nothing keyed on case-folded names, and the
file-emit step trusts the type name as-given.

The same defect exists in the legacy
`MakeConnections.maybeCreateConnectionType`
(`graphitron-schema-transform/src/main/java/no/fellesstudentsystem/schema_transformer/transform/MakeConnections.java:272`),
but legacy modules are out of scope for AI work per `CLAUDE.md`; this item
fixes the rewrite only. The legacy fix lands separately if and when a human
maintainer chooses to backport.

## Design

### Detection: in the promoter, before synthesis

The check belongs in `ConnectionPromoter.promote` next to the existing
`ctx.typeRegistry.contains(promotion.connectionName())` dedup branch. Two new
predicates, evaluated as each new name is about to be synthesised:

1. Build a case-folded view of the registry's existing keys
   (`Map<String, String>` from `name.toLowerCase(Locale.ROOT)` → original
   name), seeded from `ctx.typeRegistry.entries().keySet()` at the start of
   `promote(...)`.
2. For each `(connectionName, edgeName)` pair the promoter is about to write:
   - Exact-match hit (same name) follows the existing
     `instanceof ConnectionType existing` dedup arm. Unchanged.
   - Case-folded hit against a *different* exact name is the new failure mode.
     Surface a typed rejection (below) and skip the `synthesize` call for
     both `ConnectionType` and `EdgeType` on this carrier so the registry
     doesn't accumulate inconsistent state.

The folded view updates after each successful synthesis so a third carrier
that collides with the second also surfaces correctly. `Locale.ROOT` because
the names are ASCII-only by GraphQL spec rule (`/[_A-Za-z][_0-9A-Za-z]*/`); a
Turkish locale wouldn't change folding outcomes here, but `Locale.ROOT` pins
the contract.

### Surface: `UnclassifiedType` carrying `Rejection.invalidSchema`

`TypeRegistry` already has an "this name failed to classify" sink:
`UnclassifiedType` (`GraphitronType.java:485`) carrying a `Rejection` and a
`SourceLocation`. `GraphitronSchemaValidator.validateUnclassifiedType`
(`GraphitronSchemaValidator.java:62, 918`) projects it into a
`ValidationError` keyed off the `Rejection` variant. This is the established
type-axis path for "the classifier produced no usable variant for this name",
and it fits the connection-promotion case verbatim: the synthesise call would
have produced a `ConnectionType`, the case-clash takes that off the table,
the registry records `UnclassifiedType` instead so the validator surfaces a
diagnostic.

The rejection arm is `Rejection.invalidSchema(...)`
(`InvalidSchema.Structural`): the author can fix it (rename a field or supply
`@asConnection(connectionName: "…")`), but it's a structural-rule violation
rather than a name-against-a-closed-set lookup, so `InvalidSchema.Structural`
is the right shape rather than `AuthorError.Structural` or any
`UnknownName` variant. Precedent: the other `Rejection.invalidSchema` sites
in `GraphitronSchemaValidator` (lookup-field-returning-connection at L410,
input-cardinality-mismatch at L430) are the same shape: "this combination
cannot work, period."

Message format pins both carrier sites and names the fix:

```
synthesised connection type '<NewName>' clashes with '<ExistingName>' on a
case-insensitive filesystem; carriers are <Parent>.<field1> and
<Parent>.<field2>. Rename one field, or set @asConnection(connectionName: "…")
on one carrier to disambiguate.
```

`SourceLocation` on the `UnclassifiedType` is the second (losing) carrier
field's location; both carrier qualified names appear inside the rejection
message so the diagnostic round-trips both sites.

### Which synth sites this covers

`ConnectionPromoter` is the only site that *currently* synthesises type names
from arbitrary author-controlled identifiers (the parent type name and the
field name). Synthesised `PageInfo` is a fixed string. NodeId-related
synthesis (per `NodeIdLeafResolver`) doesn't mint new type names. So scoping
the check to `ConnectionPromoter` covers the actual exposure today.

A future synth site that mints names from author-controlled identifiers
should reuse the same case-folded check; lifting the predicate onto
`TypeRegistry` itself (as an opt-in helper, not a default on every
`synthesize` call) is tracked under *Future evolution* below rather than
done up front, since there's no second consumer yet.

### Explicit-name escape hatch stays unchanged

`@asConnection(connectionName: "X")` already short-circuits the
parent-plus-field derivation
(`ConnectionPromoter.resolveConnectionName`, L327-332). The case-folded check
applies to the resolved name regardless of derivation path: if the author
supplies an explicit `connectionName` that happens to case-clash with another
synth name, that clash also surfaces. The diagnostic message for that arm
just needs to point at the directive site rather than the derivation; the
implementer's call whether to fork the message format or keep it generic.
Genuinely open: see Open question 1 below.

## Implementation sites

- `ConnectionPromoter.java`: add the case-folded-keys view inside
  `promote(BuildContext)`, the per-carrier predicate, and the "record
  `UnclassifiedType` and skip synth" branch. No new file.
- `BuildContext` or `TypeRegistry`: no surface changes. The existing
  `typeRegistry.classify(name, new UnclassifiedType(...))` path covers the
  registration. (`classify` rather than `synthesize` because `synthesize`
  asserts the variant is `ConnectionType` / `EdgeType` / `PageInfoType` by
  contract; the failure case is structurally `UnclassifiedType`.)

That's the full delta. No directive-schema change, no new model file, no
validator change (`validateUnclassifiedType` already handles the new entry).

## Tests

### Unit-tier

- `ConnectionPromoterCaseInsensitiveCollisionTest`: pipeline-shaped unit test
  on the promoter (parallel to the existing `ConnectionPromoterTest`).
  Feed an SDL with two `@asConnection` carriers whose default-synthesised
  names case-clash; assert that `ctx.typeRegistry.get("QueryPoengklasserV2Connection")`
  is an `UnclassifiedType` whose `rejection()` is
  `InvalidSchema.Structural` with the expected message format (carrier names
  present, fix hint present). Also assert the first-arrived carrier's
  Connection/Edge pair still classifies as `ConnectionType` / `EdgeType` so
  the diagnostic doesn't take down the unaffected half.

### Pipeline-tier

- `GraphitronSchemaBuilderTest` gains a `CaseInsensitiveConnectionClashCase`
  arm in the same enum style as existing failure cases: feed the same SDL
  through `GraphitronSchemaBuilder.buildContextForTests`, assert that the
  resulting `BuildResult` carries a `ValidationError` whose `kind` is
  `INVALID_SCHEMA` and whose message names both carriers. Pins the validator
  wiring is intact end-to-end.

### Compilation-tier and execution-tier

Not applicable: the change is purely a build-time rejection, no emitted code
path. The point of the rejection is that no code gets emitted at all when
the clash is present.

## Done means

- Two sibling carriers whose default-synthesised Connection names case-clash
  produce a `ValidationError` at build time naming both carriers and the
  available fixes.
- The unit-tier and pipeline-tier tests above pass.
- No emitted Java code change for schemas that don't trip the clash; existing
  `ConnectionPromoterTest` coverage continues to pass unchanged.
- The diagnostic surfaces at the same build phase as other
  `GraphitronSchemaValidator` errors (no late-fail at file-emit time).

## Out of scope

- The legacy `MakeConnections` defect under
  `graphitron-schema-transform/`. Same algorithmic shape, but legacy modules
  are out of scope for AI work per `CLAUDE.md`. Filing a separate backport
  item is a human-maintainer call.
- Generalising the check across `TypeRegistry` to cover hypothetical future
  synth sites. Tracked under *Future evolution*; no second consumer today.
- Auto-mangling clash-survivors (e.g. appending `_2`). Rejected at the design
  conversation: two sibling fields differing only in case is almost always
  an author mistake, and silent mangling would put a non-author-controlled
  suffix into the public Relay surface that downstream Relay clients then
  pin against.
- Warning on case-clashes between author-declared SDL types and synthesised
  names. Distinct shape (the author-declared name wins by precedence; the
  promoter wouldn't synthesise over it). If the author wrote two SDL types
  that case-clash, graphql-java's own validation should already flag that;
  if not, it's a separate item.
- Catching case-clashes across federation subgraphs. Subgraphs assemble at
  the gateway, not in graphitron; gateway tooling owns that surface.

## Future evolution

- Lift the case-folded uniqueness predicate onto `TypeRegistry` itself as an
  opt-in helper (`tryClassifyOrCaseClash(name, type)`) once a second synth
  site exists. Doing it now would be premature abstraction for one consumer.
- Optional LSP fix-it: offer "add `@asConnection(connectionName: \"…\")` to
  one of these carriers" as a code action keyed off the typed
  `InvalidSchema.Structural` arm. The diagnostic carries enough structured
  data already (both carrier names) for an LSP consumer to read it back; the
  fix-it itself is LSP-side work and belongs in a follow-up.

## Open questions for the reviewer

1. *Explicit-`connectionName` collision message.* When the clash involves an
   author-supplied `@asConnection(connectionName: "X")` rather than a
   derived name, the diagnostic could either keep the generic "case clash
   between X and Y" message or fork to "explicit connectionName \"X\" clashes
   with derived \"Y\"". Recommendation: keep generic; the explicit-name path
   is rare and the message format already names both carriers, which is
   what an author needs. Reviewer override welcome.
2. *Locale of the case-fold.* `Locale.ROOT` per the spec body. GraphQL names
   are ASCII-only so the choice is cosmetic, but a future identifier-name
   extension (graphql-spec RFC-level) might change that. Genuinely open
   whether to encode the locale choice in a constant the future spec can
   point at, or leave it inline.
