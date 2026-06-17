---
id: R325
title: "Classify in a single field-first visitor walk (retire the eager type pass)"
status: Backlog
bucket: architecture
priority: 8
theme: structural-refactor
depends-on: []
created: 2026-06-17
last-updated: 2026-06-17
---

# Classify in a single field-first visitor walk (retire the eager type pass)

Classification today is not the field-first visitor walk the code's javadoc
claims it is. `SchemaReachability` runs a `SchemaTraverser` with a **no-op
`GraphQLTypeVisitorStub`** purely to collect a reachable name set; the actual
classification then happens in two further sweeps over that set:
`TypeBuilder.buildTypes()` classifies every type by directive **before any
field is touched**, and `GraphitronSchemaBuilder.buildSchema` then loops the
same set a second time to classify fields. So types are classified *first*, by
directive, in their own eager pass — the opposite of "types fall out as a
side effect of field classification." The R279 slices that were supposed to
deliver the field-first design instead left the mechanism eager-type-pass-then-
field-pass and rewrote the javadoc to *say* field-first; the comments now
assert a unified visitor that does not exist. This item exists to make the
mechanism real and stop the documentation from lying.

## The design we actually want

One `SchemaTraverser.depthFirst` walk with a **real** `GraphQLTypeVisitor`
(not a stub) that classifies fields and types in the same visit:

- Visiting a `Query` / `Mutation` field classifies the field from the field,
  its directives, and its target type's directives, and classifies the target
  type as a byproduct of that visit.
- Arriving at a non-root type, that type's verdict already exists (the field
  that reached it set it), so its own fields are classified trivially in turn.
- A directiveless object becoming a `NestingType` the moment a field points at
  it is the **primary, natural case** of this model, not a trailing patch-up.
  `registerNestingTypes` as a post-field backfill pass goes away.
- Reflection to discover a producer's backing signature is done **on demand,
  at the visit that needs it**, from the node + its parent-var context — never
  by reading back a verdict the walk already registered (see the read-free
  invariant below). Multi-producer agreement is not the visitor's concern; it
  is settled off the visitor (see the invariant's reconciliation boundary), so
  the visitor stays a pure function. There is no global "reflection binding
  fixed point" pre-pass; the current `RecordBindingResolver.resolveAll()` eager
  grounding is not a precondition of the walk.

## Invariant: the visitor never reads the registry it is building

The visitor may **only `register`**, never read, the model under
construction. A child's classification is a pure function of (its own node and
directives, the catalog, and state handed down from its parent through the
traverser's var channel) — never a lookup of a sibling's or parent's verdict
from the registry. This is the structural enforcement that makes "fields drive
types" true rather than a convention: with no read path, the only way a field
visit can know its parent type's verdict, table, or source shape is because the
parent visit stashed it as a var. Order-dependence bugs become unrepresentable,
and the registry is strictly write-only during the walk. This invariant is the
reason the item exists; an implementation that leaves a registry-read path open
has not delivered it, regardless of how the passes are named.

Parent context flows via graphql-java's `TraverserContext`: a parent visit
`setVar(Key.class, value)`, the child reads `getVarFromParents(Key.class)`
(`getParentNode()` is already used this way in `ConnectionPromoter`). The exact
keys (parent classification verdict, parent `TableRef`, source-shape context)
are the Spec's to enumerate; "getParentVar" is shorthand for this channel, not
a literal method.

Two boundaries, both **decided**, because the invariant constrains the
*visitor*, not everything around it:

- **Reconciliation lives in the registry; the visitor stays pure (decided).**
  The visitor computes a candidate verdict from (node, parent-var) and calls
  `register`. A type reached by multiple producers is registered more than
  once; the registry collects and reconciles those writes (multi-producer
  agreement, supersession). The visitor never reads back what it registered.
- **Consuming the parent-var is per-node-kind, not universal.** An interface
  reached via an object's `implements` clause must not classify as a function
  of that object; it classifies from its own directives and participants. So
  the rule is "may not read the registry," not "must read the parent-var" —
  some node kinds ignore the channel.

### Why dedup is not a problem: parent-independent entries, per-site field context (decided)

The earlier worry was that `SchemaTraverser` descends a node reached by two
parents **once** (identity dedup, the `expanded` set in `SchemaReachability`),
so a reusable type's fields would bind to whichever parent the walk reached
first. Checked against the code, this dissolves, and the resolution is a
*requirement* on the design, not a hope:

- **Every type's registry entry is parent-independent.** Each verdict is
  intrinsic to the type: `@table`/catalog (Table / Node / Result), directives
  (Interface / Union / Enum / Scalar), or — for a directiveless projection — a
  bare `NestingType` *marker* that carries **no table** (`GraphitronType
  .NestingType` is `(name, location, schemaType)`; its fields inline into the
  embedding parent's query and read off the parent row through the
  `NestingField`). So registering the same type once per incoming field is N
  identical writes the registry collapses trivially; multi-producer
  disagreement is the only non-identical case, and that is exactly what the
  registry reconciles.
- **All parent-relative context lives on the per-site field, keyed per-site.**
  A projection `Thing` embedded under `A` (table a) and `B` (table b) yields
  two `NestingField`s at coordinates `A.thing` and `B.thing`, each carrying its
  own copy of Thing's projected fields resolved in its own table context. Those
  projected fields live on the `NestingField` (`nf.nestedFields()`), **not** in
  the global field registry under `Thing.foo`, so they never collide and are
  free to diverge (a column absent under `b` errors only `B.thing.foo`).

So the same type and its fields are classified **many times, once per embedding
site**, with zero registry collision, because projection field classification
happens at each embedding *field* visit (carrying parent context via the var
channel), never at the once-visited type node. The dedup of the type node is
therefore irrelevant to it.

**The guard the Spec must hold:** a type's registry entry stays
parent-independent. The moment a `TableRef` or source shape is put on a shared
type's entry, the N-identical-writes property breaks and the edge returns.
Parent context lives on the field, full stop. (This is the constructive answer
to the `parent-context-aware-schema-coordinates` item's territory, not a
deferral to it.)

## Edges that are not literally "field → return type" (must be carried explicitly)

These are spec content, not blockers; the walk must descend them or types
dangle:

- **Seeds reached by no field:** `@node` / `@key` types and the `Node`
  interface that no field returns (federation injects the entry points later).
  Already seeded in `SchemaReachability.seeds`; stays seeded.
- **Interface → implementors** and **object → implemented interfaces:**
  structural edges, not field-return edges. Already in the custom child
  function; the classifier walk must follow them too.
- **Arguments → input types → nested input fields (decided: per-usage, not via
  the visitor).** Inputs are read **during field classification**, in the
  context of the current field, never via a separate visitor descent and never
  reusing a sibling field's classification of the same input type. This moves
  input *type* classification off today's global `buildInputType` pass (one
  verdict per input type name) onto per-field-usage resolution, carrying the
  same guard as above: if a shared input type keeps a registry entry at all,
  that entry stays parent-independent and the per-usage context lives on the
  argument. Whether a shared input needs a global entry or is purely
  field-level is the one input-specific question the Spec must answer.

## Validations move out of the walk, explicitly

Multi-producer agreement, dangling-reference rejection, case-insensitive
type-name collisions, and `@node` typeId uniqueness are **validations over the
finished registry**, not classification. They become named post-walk passes.
The spec must say so, so that no later session "discovers" a need for a
pre-pass and quietly reintroduces eager grounding.

## Why R279 produced narrative instead of mechanism, and how this item avoids it

R279 was sliced so that **every slice was behavior-preserving** ("byte-
identical", repeatedly). If every step preserves behavior, the terminus
preserves behavior: the only thing that can have moved is comments. The
slicing optimized for per-step safety and guaranteed narrative-only as the
sum. This item is therefore defined by its **terminal mechanism and a
falsifiable acceptance test the current code fails**, not by a chain of safe
refactors:

1. **Terminal mechanism.** Classification is a single `SchemaTraverser
   .depthFirst` call with a real `GraphQLTypeVisitor`. `TypeBuilder
   .buildTypes()` as a standalone type pass and the separate
   `classifyFieldsOfObject` loop in `GraphitronSchemaBuilder.buildSchema` are
   **deleted**, not wrapped or preserved.
2. **Falsifiable acceptance.** A test asserts no type is registered before its
   discovering field is visited — a structural assertion that *cannot pass*
   against today's eager-type-pass code. If the test would pass unchanged
   today, the item did nothing.
3. **Anti-narrative guard.** No slice may be "byte-identical / comment-only."
   Each slice deletes a pass or moves classification logic into a visit
   callback, with a behavior or structure delta to show for it. A slice whose
   diff is only javadoc is out of scope by construction.

## Out of scope (for the Spec to confirm or widen)

- Changing *what* any type or field classifies to. The verdict for every
  existing fixture must be unchanged; only *where and when* the verdict is
  produced changes. (The acceptance test above constrains the "when".)
- The post-build federation injection (`_entities` / `_Entity`) in
  `GraphitronSchemaClassGenerator` and the assembled-schema rebuild
  (`ConnectionPromoter`); these consume the registry, they do not classify.

## Key code

- `graphitron-rewrite/.../SchemaReachability.java` — the `SchemaTraverser`
  walk with the no-op `GraphQLTypeVisitorStub` that becomes (or is replaced
  by) the real classifying visitor.
- `graphitron-rewrite/.../TypeBuilder.java` `buildTypes()` — the eager type
  pass to retire.
- `graphitron-rewrite/.../GraphitronSchemaBuilder.java` `buildSchema`,
  `classifyFieldsOfObject`, `registerNestingTypes` — the second/third sweeps
  to fold into the walk.
- `graphitron-rewrite/.../FieldBuilder.java` `classifyField` — the per-field
  classifier the visit callback drives.
- `graphitron-rewrite/.../RecordBindingResolver.java` `resolveAll()` — the
  eager reflection grounding to make on-demand.
