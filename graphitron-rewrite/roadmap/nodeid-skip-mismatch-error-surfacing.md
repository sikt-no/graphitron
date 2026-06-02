---
id: R273
title: "Decide whether SkipMismatchedElement should surface an error instead of silently dropping wrong-type node IDs"
status: Backlog
bucket: validation
priority: 6
theme: nodeid
depends-on: []
created: 2026-06-02
last-updated: 2026-06-02
---

# Decide whether SkipMismatchedElement should surface an error instead of silently dropping wrong-type node IDs

When a NodeId argument is in **filter** position, a supplied node ID that decodes to the wrong
type (or is malformed) is currently dropped silently and the query proceeds as if that ID were
never passed. The reviewer is not convinced this silent-drop is ever the correct behavior: a client
that passes a `Film` id where an `Actor` id is expected has almost certainly made a mistake, and
returning a partial-or-empty result with no signal hides that mistake rather than reporting it.
This item is to revisit the decision and choose deliberately.

## Current behavior (as found)

The skip-vs-throw decision is classified at the parse boundary into
`CallSiteExtraction.NodeIdDecodeKeys`, a sealed pair (`model/CallSiteExtraction.java`):

- `SkipMismatchedElement` is produced for **filter** arguments: `[ID!] @nodeId(typeName: T)` on an
  input-object field or the equivalent top-level field-argument, and the same-table / FK-target
  `@nodeId` filter shapes in `FieldBuilder.java` (the `@nodeId`-arg block around `:1021`, sites
  `:1052` and `:1074`). A `null` decode return (malformed input or typeId mismatch) short-circuits
  the bad element to "no row matches" and never throws. The emitted helper filters it out:
  `CompositeDecodeHelperRegistry.buildHelper` uses `.filter(Objects::nonNull)` (list) or
  `return key == null ? null : ...` (scalar) on the SKIP path.
- `ThrowOnMismatch` is produced for **lookup / mutation key** arguments (a bare `id:` resolving to
  the table PK, or an explicit `@lookupKey`); a `null` decode is treated as an authored-input error
  and raises a `GraphqlErrorException`.

So the framing today is role-based: a mismatched id in a *filter* is "data that matches nothing"
(like a non-existent value in `WHERE pk IN (...)`), while a mismatched id used as a *key* is a
contract violation. The doc on `NodeIdDecodeKeys` states this rationale explicitly.

Note the question is **orthogonal to nullability**: `ID` vs `ID!` governs whether the argument may
be omitted or null (presence), handled separately (absent arg omits the predicate; present-but-empty
list emits `falseCondition()`). Skip-vs-throw governs what a *present, well-formed-but-wrong-type* id
means. A non-null marker does not by itself imply "reject a wrong-type id."

## What to decide

Is "no match" the right semantics for a wrong-type / malformed id in filter position, or should it
surface as an error (or at least a GraphQL `errors` entry alongside a partial result)? Sub-questions:

- Distinguish the two failure modes the decode collapses into one `null`: a **malformed** base64
  string (almost certainly a client bug) versus a **well-formed id of a sibling node type** (could
  be a legitimate cross-type query against a heterogeneous id list, or could be a bug). They may
  warrant different treatment.
- If error-surfacing is chosen, decide the shape: hard `GraphqlErrorException` (fail the field),
  versus a partial result plus a non-fatal `errors` extension. The latter fits Relay-style clients
  better but is a larger change.
- Whether the choice should be author-controllable per argument (a directive opt-in) rather than a
  single global policy, since filter-by-many-ids and identify-one-row are genuinely different uses.

## Out of scope / relationship to other items

- **R265** (the `GraphqlErrorException(String)` ctor compile fix) is unrelated: it only makes the
  existing `ThrowOnMismatch` arm compile and changes no policy. This item is about whether the
  *policy itself* (skip in filter position) is right.
- The partially-wired bare-`ID`-arg filter path noted in `FieldBuilder.java:1107-1140` (scalar-ID
  filter semantics "not yet shipped", surfaced as `UnclassifiedArg`) overlaps this question; whatever
  policy is chosen here should inform how that path is wired when it lands.
</content>
