---
id: R273
title: Source NodeId metadata from @node + catalog PK (inferred from `implements Node`), and settle wrong-type/malformed mismatch semantics, retiring the legacy __NODE bare-ID arm
status: Spec
bucket: architecture
priority: 5
theme: nodeid
depends-on: [nodeid-filter-malformed-vs-mismatched]
created: 2026-06-02
last-updated: 2026-06-02
---

# Source NodeId metadata from @node + catalog PK, settle mismatch semantics, retire the legacy __NODE bare-ID arm

This item merges two threads that turn out to be one piece of work, because they collide at a single
classification site (the bare-`ID` filter arm):

1. **Metadata sourcing (mechanism).** Today several classification sites read NodeId `typeId` /
   `keyColumns` by reflecting the `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` constants the jOOQ generator
   (Sikt's `KjerneJooqGenerator`, modelled in fixtures by `NodeIdFixtureGenerator`) appends to
   NodeType tables. Per project direction those constants are **legacy**: their only sanctioned role
   should be to let a `Node`-implementing type bound to such a table *infer* the `@node` directive at
   classification time. After that inference, **nothing should read `__NODE_*` again** — all NodeId
   metadata comes from the `@node` directive plus the catalog primary key.

2. **Mismatch semantics (policy).** When a NodeId argument is in **filter** position, a supplied node
   ID that decodes to the wrong type (or is malformed) is currently dropped silently
   (`SkipMismatchedElement`) and the query proceeds as if that ID were never passed. The reviewer is
   not convinced this silent-drop is ever correct: a client that passes a `Film` id where an `Actor`
   id is expected has almost certainly made a mistake, and returning a partial-or-empty result with no
   signal hides it. This item revisits the decision and chooses deliberately.

The collision: the moment we route the bare-`ID` filter arm through the modern resolver (the natural
consequence of "infer `@node`, then source from `@node` + catalog PK"), we also pick that arm's
mismatch behavior, because the modern resolver's only behavior today is `SkipMismatchedElement`. You
cannot modernize the arm's metadata sourcing without simultaneously deciding its skip-vs-throw
semantics. So the policy decision and the refactor are the same item.

## Current behavior (as found)

### Skip vs throw

The skip-vs-throw decision is classified at the parse boundary into
`CallSiteExtraction.NodeIdDecodeKeys`, a sealed pair (`model/CallSiteExtraction.java`):

- `SkipMismatchedElement` is produced for **filter** arguments: `[ID!] @nodeId(typeName: T)` on an
  input-object field or the equivalent top-level field-argument, and the same-table / FK-target
  `@nodeId` filter shapes in `FieldBuilder.java` (the `@nodeId`-arg block around `:1021`, sites
  `:1052` and `:1074`). A `null` decode return (malformed input or typeId mismatch) short-circuits
  the bad element to "no row matches" and never throws. The emitted helper filters it out:
  `CompositeDecodeHelperRegistry.buildHelper` uses `.filter(Objects::nonNull)` (list) or
  `return key == null ? null : ...` (scalar) on the SKIP path.
- `ThrowOnMismatch` is produced at **exactly one site**: the bare-`ID` block at `FieldBuilder.java`
  `:1107-1140` (extraction created at `:1131`). A bare `id:` (no `@nodeId`, no `@lookupKey`) resolving
  to the table PK is treated as an authored-input key, and a `null` decode raises a
  `GraphqlErrorException`. The block's own comment (`:1019`) calls it "the legacy implicit scalar-ID
  arm." Its construction was non-compiling until R265 fixed it.

So the framing today is role-based: a mismatched id in a *filter* is "data that matches nothing" (like
a non-existent value in `WHERE pk IN (...)`), while a mismatched id used as a *key* is a contract
violation. The doc on `NodeIdDecodeKeys` states this rationale explicitly.

The question is **orthogonal to nullability**: `ID` vs `ID!` governs presence (absent arg omits the
predicate; present-but-empty list emits `falseCondition()`), handled separately. Skip-vs-throw governs
what a *present, well-formed-but-wrong-type* id means. A non-null marker does not by itself imply
"reject a wrong-type id."

### Metadata sourcing (the `__NODE_*` reads)

`__NODE_*` is read at six sites (five key/typeId reads + one diagnostic), via
`JooqCatalog.nodeIdMetadata(...)` / `nodeIdMetadataDiagnostic(...)`:

- **`TypeBuilder.java:748` (diagnostic) + `:754` (read)** — the NodeType promotion site. Promotion is
  currently **opt-in via explicit `@node`** (`:756-759`): a `@table` type without `@node` stays a
  `TableType` even if its backing jOOQ class carries `__NODE_*`. The comment at `:744-747` records why
  auto-promotion-on-metadata was removed: it "silently collided typeIds across types whose backing
  tables shared `__NODE_TYPE_ID`, with no SDL-side opt-out." When `@node` *is* present, `__NODE_*` is
  used as a fallback source for `typeId` / `keyColumns` (`:816-834`).
- **`BuildContext.java:1858`** — shim/reference target metadata gate.
- **`BuildContext.java:1934`** — NodeId decode context.
- **`BuildContext.java:2158`** — `resolveTargetKeys` tier 1. This method already has the modern
  fallback at tier 3 (`:2165-2176`): `@node` on the SDL + PK columns from `catalog.findPkColumns(...)`,
  with `typeId` from `@node(typeId:)` or the type name. So the `@node` + catalog-PK path already
  exists and is exercised by every `@node`-only NodeType.
- **`FieldBuilder.java:1108`** — the bare-`ID` throw arm, which reads `nodeIdMetadata` *directly*
  rather than going through `resolveTargetKeys`, so it is the one place that bypasses the modern
  fallback entirely.

## What to decide (policy)

**Settled by R378 (do not re-litigate here).** R378 decided the filter-position policy: a malformed
*or* well-formed-wrong-type id throws (the two are distinguished in the message, not in behaviour),
and the error surfaces to the client (path B: query fetch fields surface a generated client-error
type rather than redacting it, built so a later `@error`-on-queries lift catches the same type). So
`ThrowOnMismatch` **survives**; Deliverable 5's "delete `ThrowOnMismatch` / `Mode.THROW` if skip
wins" branch is dead. R273 *inherits* this policy and keeps only the metadata-sourcing mechanism
below (infer `@node`, source from `@node` + catalog PK, reroute the bare-`ID` arm onto the settled
throw policy). The sub-questions below are retained as the record of what R378 weighed, not as open
decisions.

Is "no match" the right semantics for a wrong-type / malformed id in filter position, or should it
surface as an error (or a partial result plus a non-fatal `errors` entry)? Sub-questions:

- Distinguish the two failure modes the decode collapses into one `null`: a **malformed** base64
  string (almost certainly a client bug) versus a **well-formed id of a sibling node type** (could be
  a legitimate cross-type query against a heterogeneous id list, or a bug). They may warrant different
  treatment.
- If error-surfacing is chosen, decide the shape: hard `GraphqlErrorException` (fail the field) versus
  a partial result plus a non-fatal `errors` extension. The latter fits Relay-style clients better but
  is a larger change.
- Whether the choice is author-controllable per argument (a directive opt-in) rather than a single
  global policy, since filter-by-many-ids and identify-one-row are genuinely different uses.

The chosen policy determines the fate of the throw arm: if "skip everywhere in filter position" wins,
the bare-`ID` arm's `ThrowOnMismatch` becomes dead code to delete (along with the registry's `THROW`
mode if no other producer remains); if "surface an error" wins, the behavior generalizes across the
filter arms rather than living only on the legacy bare-`ID` arm.

## What to implement (mechanism)

Once the policy is settled, land the metadata-sourcing refactor that makes `__NODE_*` an
inference-only signal:

1. **Infer `@node` at classification time.** In `TypeBuilder.buildTableType`, a `@table` type that
   `implements Node` and whose backing table carries `__NODE_*` metadata is promoted to a `NodeType`
   even without an explicit `@node` directive. The `implements Node` gate supplies the SDL-side opt-out
   that was missing when metadata-based auto-promotion was removed (`:744-747`), so the typeId-collision
   regression that motivated removing it does not return. Keep the malformed-metadata diagnostic.
2. **Source typeId / keyColumns from `@node` + catalog PK only.** After inference, resolve `typeId`
   (`@node(typeId:)` or type-name default) and `keyColumns` (`@node(keyColumns:)` or catalog PK
   default) without consulting `__NODE_*`. The `@node`-only path in `TypeBuilder` (`:787-805`) and
   `resolveTargetKeys` tier 3 already do exactly this; the work is to make them the *only* path.
3. **Reroute the bare-`ID` filter arm.** `FieldBuilder.java:1107-1140` stops reading `nodeIdMetadata`
   directly. Per the policy decision it either infers `@nodeId` and routes through the modern resolver
   (`SkipMismatchedElement`) or carries the chosen error-surfacing behavior. Either way it no longer
   depends on `__NODE_*`, so a bare `id: ID!` on a `@node`-backed field (e.g. `filmByNode(id: ID!):
   Film`) classifies off `@node` + catalog PK.
4. **Purge the remaining `__NODE_*` reads.** Drop the `nodeIdMetadata` reads at `BuildContext.java`
   `:1858`, `:1934`, and `resolveTargetKeys` tier 1 (`:2158`); they fall through to the `@node` +
   catalog-PK resolution. The only surviving consumer of `JooqCatalog.nodeIdMetadata` /
   `nodeIdMetadataDiagnostic` is the `TypeBuilder` inference + diagnostic.
5. **Retire or reshape the throw arm** per the policy outcome (delete `ThrowOnMismatch` and the
   registry `THROW` mode if no producer remains, or generalize the chosen error behavior across the
   filter arms).

## Regression coverage

This is where R265's deferred compilation-tier guard lands. Once the bare-`ID` arm sources keys from
`@node` + catalog PK (no `__NODE_*`), a `graphitron-sakila-example` field with a bare arity-1 `ID`
argument on a `@node`-backed query field (`filmByNode(id: ID!): Film`, Film being `@table @node` with
PK `film_id`) compiles its generated `*Conditions` decode helper against the real graphql-java 25 API
on the modern path, with no need to wire a public table into `NodeIdFixtureGenerator.METADATA`. Pair
it with an execution-tier assertion for the chosen mismatch policy (skip → row drops to "no match";
error → the field/extension surfaces). The existing `CompositeDecodeHelperRegistryTest` string
assertions move or retire with the arm they cover.

## Relationship to other items

- **R265** (the `GraphqlErrorException(String)` ctor compile fix) is the predecessor: it made the
  existing throw arm compile and changed no policy, then deferred its compile-tier guard here because
  the arm is reachable only via the legacy `__NODE_*` path. This item owns whether that arm survives.
- The partially-wired bare-`ID` filter path is no longer a separate "not yet shipped" gap; rerouting
  it is Deliverable 3 above.

## Out of scope

- The encode side and wire format of NodeIds (typeId-prefixed base64) are unchanged; this is about how
  the generator *sources* the metadata and *classifies* mismatches, not how ids are encoded.
- `KjerneJooqGenerator` / `NodeIdFixtureGenerator` keep emitting `__NODE_*`; the change is that the
  generator stops *reading* them except to infer `@node`. Removing the constants from the generators is
  a later, separate cleanup once nothing reads them.

## Note

This item spans a policy decision plus a five-site classification refactor; it warrants a real Spec
pass and a `principles-architect` consult before implementation (wire-format-is-a-boundary-concern and
generation-thinking both bear on the skip-vs-throw arm).
