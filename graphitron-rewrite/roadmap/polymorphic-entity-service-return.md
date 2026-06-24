---
id: R365
title: "Support returning a polymorphic entity (interface/union) from a @service mutation"
status: Ready
bucket: bug
priority: 5
theme: interface-union
depends-on: []
created: 2026-06-24
last-updated: 2026-06-24
---

# Support returning a polymorphic entity (interface/union) from a @service mutation

## Problem

A `@service` mutation (or query) that operates on a multitable interface cannot return the affected
polymorphic entity (interface or union), with or without a typed-error envelope. Every candidate
shape is rejected at codegen. This shape worked in graphitron 9.3, so under the rewrite's
feature-equivalence goal this is a regression, not merely an unbuilt feature: the generator's "not yet
supported" message understates it. Confirm the exact 9.3 surface that worked (which of the shapes
below, or another) so the restored API matches rather than inventing a new one.

## Rejected shapes today (exact messages, confirmed)

1. `@service` returning the interface directly (single or list):
   `@service returning a polymorphic type is not yet supported` (`ServiceDirectiveResolver.java:224`,
   in `projectReturnType`). Only an all-`@error` nullable-list "errors channel" lifts via
   `liftToErrorsField`; anything else falls to `Resolved.Rejected`.
2. A union of a `@record` success carrier plus `Error`-implementing types:
   `implementing type '<X>' is not table-bound (missing @table directive)` (`TypeBuilder.java:767`;
   the union is classified via `buildParticipantList(..., allowNonTableMembers=false)` at line 918).
   Nuance for the Spec: `@error` members are admitted (as `ParticipantRef.Unbound`); it is the
   non-error `@record` success carrier that trips the rule. "All union members must be @table-bound" is
   the wrong gloss.
3. A hand-declared payload object `{ field: ..., errors: [Error] }` returned by `@service`:
   `... returns SDL Object type '<XPayload>', which did not classify into the model (no @table or
   record-backed binding, no producer-backed carrier promotion, not embedded as a nesting projection
   of a table-backed parent) ...` (`GraphitronSchemaBuilder.java:584-593`, in
   `rejectDanglingTypeReferences`). The original report quoted "no @table/@record binding"; the actual
   text is "no @table or record-backed binding".

## Plan

This is the largest item in the cluster and is design-led, not a localized patch. The route is
**pinned to (a) — auto-fetch extended to polymorphic returns** (rationale below). The 9.3-baseline
fidelity gate that previously blocked sign-off is **confirmed by the maintainer** (see below): route
(a)'s bare interface/union return faithfully restores the 9.3 contract, so the design is complete and
`depends-on` stays empty.

### Pinned route: (a) auto-fetch of `@service`-returned records, extended to polymorphic returns

The service returns the concrete PK-populated `TableRecord` per branch, and Graphitron resolves it to
the right participant and fetches the rest — no `@splitQuery` payload field, no `errors`-envelope
wiring required for the base shape. This is pinned over the alternative for three reasons:

- **Aligns with the documented maintainer stance** ("a `@service` returns PK-populated `TableRecord`s
  and Graphitron fetches the rest"). Participant resolution is *direct*: the returned jOOQ
  `TableRecord` carries its own type identity, so the concrete participant is read off the record's
  runtime class (matched against each `ParticipantRef.TableBound.table().recordClass()`). No
  synthesized `__typename` discriminator column or PK round-trip is needed to *identify* the type —
  unlike the multitable *query* path, whose discriminator exists only to recover the Java type that
  UNION-ALL row projection erases.
- **No hard dependency.** Route (b) (payload field carrying `[Applikasjon] @splitQuery` +
  `errors: [Error]`) depends on **R366** (list-cardinality emit) and **R367** (single-cardinality
  record-parent arm) landing first; route (a) depends on neither, so it can ship independently and
  unblocks the reported regression sooner.
- **Smallest guard surface.** Route (a) touches only the `PolymorphicReturnType` arm of
  `projectReturnType`. It leaves the union-member rule (`TypeBuilder.java:767`) and the
  dangling-payload classifier (`GraphitronSchemaBuilder.java:584-593`) untouched — those only need
  relaxing for the union/payload shapes route (b) introduces.

Route (b) is retained as a **follow-up**, not an alternative: once R366 + R367 land, the payload +
`errors`-envelope shape falls out on top of route (a) and can be added then. It is not on this item's
critical path.

### 9.3 baseline: confirmed by maintainer

The mechanism was always settled; what had to be confirmed was the *exact restored surface* so 10.x
matches 9.3 rather than inventing a new contract. **Confirmed (2026-06-24, maintainer):** route (a)'s
bare interface/union return is the faithful restoration of the 9.3 behaviour. The accepted shape is
the interface/union returned directly (not the `{ field, errors }` payload object), so this item does
**not** take `depends-on: R366, R367`; the payload shape (route (b)) remains a separate follow-up.
With the surface confirmed, this is no longer design-led — it is implementation-ready pending a
Spec → Ready sign-off by a fresh session.

### Implementation (route (a))

`ServiceDirectiveResolver.projectReturnType` (`ServiceDirectiveResolver.java:214-225`) currently
yields `Resolved.Rejected(Rejection.deferred("@service returning a polymorphic type is not yet
supported", ""))` for the `PolymorphicReturnType` arm whenever `liftToErrorsField` returns null.
Replace that reject with a resolved polymorphic-return arm that carries the participant set and the
service method, and emit a fetcher that, for each returned `TableRecord`, **dispatches on its runtime
record class** against the participant set (`ParticipantRef.TableBound` already pairs
`table().recordClass()` with `typeName()`) to pick the participant arm, sets the GraphQL `__typename`
to that participant's type, and auto-fetches the selected columns by PK against that participant's
table. The Java type of the returned record *is* the discriminator, so this needs neither the query
path's synthesized `__typename` column nor its PK-based type reconstruction — those exist only because
UNION-ALL projection erases the Java type, which a returned typed record does not. The participant's
`table()` is still used, but only for the by-PK auto-fetch, not for type identification. The
all-`@error` nullable-list "errors channel" (`liftToErrorsField`) continues to lift as today; this
widens the *non*-errors arm.

### Discriminability: same-table participants need a `@discriminator`

Record-class dispatch tells participants apart by **table identity**, so it only works when each
table-bound participant maps to a *distinct* table. Two participant types backed by the same table
share a `recordClass`, so a returned record matches both arms and cannot be discriminated from its
Java type alone. (The multitable *query* path R363 lowers onto has the same blind spot: two UNION
branches over one table would tag every row of that table with both `__typename`s. So this rule is a
shared invariant of multitable polymorphism, not route-(a)-specific.)

Add a build-time rule in `validateQueryInterfaceField` / `validateQueryUnionField` (and the
`@service`-return arm), with three outcomes:

- **Distinct tables** → discriminate by record class (the base case above). No directive needed.
- **Same-table participants WITH a discriminator** → discriminate by the discriminator column value.
  The directives already exist: `@discriminator(value:)` is read into
  `ParticipantRef.TableBound.discriminatorValue` for *all* participants by `buildParticipantList`
  (`TypeBuilder.java:753-757`), so the per-type values are available. The discriminator *column*
  `@discriminate(on:)` is currently parsed only on the single-table `TableInterfaceType` path
  (`TypeBuilder.java:1337`); the multitable path would need to read it for the colliding subset too.
  The fetcher then reads that column off the returned record and matches the value to each
  participant's `@discriminator(value:)`.
- **Same-table participants WITHOUT a discriminator** → **validation error**, e.g. "interface/union
  '`<X>`' maps types '`<A>`' and '`<B>`' to the same table '`<T>`' with no `@discriminator` to tell
  them apart; add `@discriminate(on:)` + `@discriminator(value:)`, or split the types." This fails the
  build instead of silently mis-dispatching a returned record to the wrong arm.

The validation error is the **floor** and is in scope day one (it closes a real misdispatch hole and
guards R363's query path over the same interfaces). The `@discriminator`-based same-table *dispatch* is
**pinned out of scope for this item** and deferred to a follow-up: the 9.3 surface this restores is
bare interface/union returns over distinct-table participants, and the floor already rejects the
same-table case at build time rather than misdispatching it, so dispatch is an additive capability
beyond the regression, not part of it. Keeping it out matches route (a)'s "smallest guard surface"
rationale. When the follow-up lands, it leans on the existing directive plumbing
(`@discriminator(value:)` already read into `ParticipantRef.TableBound.discriminatorValue` for all
participants; `@discriminate(on:)` parsed today only on the single-table `TableInterfaceType` path at
`TypeBuilder.java:1337`, to be read for the colliding subset too) rather than inventing a new
discriminator signal.

Front-matter `depends-on` stays empty under route (a). Wire `depends-on: R366, R367` only if the 9.3
baseline forces the payload shape (route (b)).

## Tests

- **Pipeline tier (positive, interface + union).** A `@service` field returning a multitable
  interface, and a second returning a union, over *distinct-table* participants, each classify into a
  resolved polymorphic-return arm (no longer `Resolved.Rejected`) carrying the participant set and the
  service method, and generate a fetcher `TypeSpec` that dispatches on the returned record's runtime
  class against each `ParticipantRef.TableBound.table().recordClass()`, sets `__typename` to the
  matched participant, and auto-fetches selected columns by PK against that participant's table. Both
  single and list cardinality.
- **Pipeline tier (validation floor).** An interface/union whose participant set maps two types to the
  *same* table with no `@discriminator` fails classification/validation with the cited build error
  ("... maps types '`<A>`' and '`<B>`' to the same table '`<T>`' with no `@discriminator` ..."). This
  test is the live pin for the new invariant (per "Validator mirrors classifier invariants"); it must
  fail when the floor is relaxed. Assert it fires from the `@service`-return arm *and* from
  `validateQueryInterfaceField` / `validateQueryUnionField` (the shared R363 query path).
- **Execution tier.** A `@service` mutation that returns the concrete PK-populated `TableRecord` per
  branch resolves to the correct participant, exposes the right `__typename`, and auto-fetches the
  selected columns against real PostgreSQL, returning the polymorphic entity end-to-end (the 9.3
  regression actually works). Exercise at least two distinct-table branches so a misdispatch is
  observable.
Same-table `@discriminator` *dispatch* is out of scope (see "Discriminability" above), so its
execution-tier test belongs to that follow-up, not this item; the floor's rejection test above is what
guards the same-table case here.

Phrase any test the implementer adds as code it creates, not as an already-existing assertion, per
"Documentation names only live tests/code".

## Out of scope

- **Route (b): the `{ field, errors }` payload + `errors: [Error]` envelope shape.** A follow-up that
  falls out on top of route (a) once R366 (Done) and R367 (In Review) land; it is not on this item's
  critical path and does not pull `depends-on` onto this item.
- **Same-table `@discriminator`-based dispatch.** Only the build-time validation floor for same-table
  participants ships here; resolving a returned record to the right arm by discriminator column value
  is the deferred follow-up described under "Discriminability".
- **Union members beyond the `@error`/table-bound shapes route (a) already classifies.** The
  union-member rule (`TypeBuilder.java:767`) and the dangling-payload classifier
  (`GraphitronSchemaBuilder.java:584-593`) stay untouched; relaxing them is route (b)'s concern.

## Cross-links

- R366 (list-cardinality polymorphic `@splitQuery` emits non-compiling code) and R367
  (single-cardinality polymorphic child on a record-backed parent is rejected) are the concrete
  emitter blockers on the payload route; both must land for shape (b).
- Related to the `mutations-errors` theme items on payload/error-envelope construction.

## Spec-review revisions (2026-06-24)

Reviewer (Spec gate, session ≠ author) pinned route (a) (auto-fetch extended to polymorphic returns)
as the mechanism — it has no hard dependency, reuses the multitable query path's participant
resolution, and leaves the union-member and dangling-payload guards untouched — and recast route (b)
as a post-R366/R367 follow-up rather than a live alternative. The remaining 9.3-baseline gate was
narrowed from "choose the mechanism" to "confirm the restored surface's fidelity (and whether it
forces `depends-on: R366, R367`)". The 9.3 baseline is now **confirmed by the
maintainer** (route (a)'s bare interface/union return), closing the last open gate. The design is
complete; the item stays in Spec only because the Spec → Ready sign-off must come from a session other
than this one (which authored these revisions), per the reviewer rule.

Also scoped in: a discriminability rule for route (a) — participants must be distinguishable, by
distinct table (record-class dispatch) or by `@discriminator` when they share a table; same-table
participants with no `@discriminator` are a build-time validation error rather than a silent
misdispatch. This also guards R363's query path over the same interfaces.

## Spec-review revisions (2026-06-24, second pass)

Reviewer (Spec gate, session ≠ prior committer) verified all code citations resolve as named
(`ServiceDirectiveResolver.projectReturnType` reject at :224, `TypeBuilder.java` :767 / :918 / :753-757
/ :1337, `GraphitronSchemaBuilder.rejectDanglingTypeReferences` :584-593, `ParticipantRef.TableBound`
with `table().recordClass()` / `typeName()` / `discriminatorValue`, the `validateQueryInterfaceField`
/ `validateQueryUnionField` validator arms) and confirmed the route-(a) pin, the empty `depends-on`,
and the closed 9.3-baseline gate are sound. The item was **held in Spec for one revision**: it carried
no `## Tests` section, unlike both sibling specs (R363, R367) at comparable stages, and it introduces a
*new build-time invariant* (same-table participants without `@discriminator` → validation error) that
the "Validator mirrors classifier invariants" and broader "Documentation names only live tests/code"
principles require a live test to pin. Added a `## Tests` section (pipeline positive for
interface + union over distinct tables, pipeline validation-floor pin for the same-table-no-discriminator
error from both the `@service`-return and query arms, execution-tier end-to-end for the 9.3 regression,
and a conditional execution test for same-table `@discriminator` dispatch). The design is otherwise
complete; the next `Spec → Ready` sign-off must come from a session other than this one (which authored
this revision), per the reviewer rule.

Follow-up tightening (same pass): resolved the one remaining hedge by **pinning same-table
`@discriminator` dispatch out of scope** (floor-only day one; dispatch is the deferred follow-up) so no
"decide at Spec" instruction carries into implementation, made the conditional Tests bullet
unconditional to match, and added an explicit `## Out of scope` section (route (b) payload/errors
envelope; same-table dispatch; the untouched union-member and dangling-payload guards).

## In Review → Ready: rework requested (2026-06-24, reviewer session ≠ implementer)

Route (a) was implemented across all tiers (validation floor at 3366be, classifier + emitter at
976e0a3, execution-tier fixture + emitter fix at 0e82f71) and the build is green for everything R365
touches: `graphitron` 2223 tests and `graphitron-sakila-example` 459 tests (incl.
`ServicePolymorphicReturnExecutionTest`, the 9.3 distinct-table round-trip) both pass; the only
failures are `graphitron-lsp` / `graphitron-mcp` native-runtime (`GraphqlLanguage` / libtree-sitter),
the documented web-sandbox caveat, on files R365 does not touch. The distinct-table regression — the
item's actual deliverable — is faithfully restored.

**One blocking finding: the discriminability floor does not close the same-table misdispatch hole it
claims to, and the emitter asserts an invariant the validator does not provide.**

- The floor (`GraphitronSchemaValidator.validateMultiTableParticipants`) errors only when
  `anyMissingDiscriminator` is true. Two participants on the **same table that each carry
  `@discriminator(value:)`** pass the floor — its own unit test
  `wellFormed_sameTableParticipantsWithDiscriminator_noFloorError` asserts this.
- The case is reachable from plain SDL: `TypeBuilder` reads `@discriminator(value:)` into
  `discriminatorValue` *unconditionally* for every table-bound participant of a plain multitable
  interface/union (no `@discriminate(on:)` needed). Such a schema classifies to
  `QueryServicePolymorphicField` / `MutationServicePolymorphicField`, passes validation, and reaches
  the emitter.
- `MultiTablePolymorphicEmitter.buildServiceDispatchBlock` then emits an
  `if (rec instanceof X) … else if (rec instanceof X)` chain over two identical record classes; the
  second arm is dead, so every record of that table routes to the **first** participant — a silent
  misdispatch (wrong `__typename`, wrong by-PK fetch). The method's own comment claims "the floor
  guarantees distinct record classes across participants, so the if/else-if chain assigns each record
  to exactly one arm", which is false for this subset.
- This violates **"Validator mirrors classifier invariants"** and **"Classifier guarantees shape
  emitter assumptions"** (rewrite-design-principles.adoc), and contradicts this spec's own claim
  (under "Discriminability") that "the floor already rejects the same-table case at build time rather
  than misdispatching it". A deferred/unimplemented dispatch branch must **fail the build**, not
  miscompile.

**Fix for the next pass:** in the shared `validateMultiTableParticipants` same-table branch, also
reject the with-`@discriminator` subset (a deferred "same-table polymorphic dispatch not yet
supported" rejection) until dispatch-by-value lands; correct the `MultiTablePolymorphicEmitter`
invariant comment; and flip `wellFormed_sameTableParticipantsWithDiscriminator_noFloorError` to expect
the deferred rejection. Note the **R363 query path shares this blind spot** (the spec says as much) and
would be guarded by the same single-site change. Everything else — the distinct-table classifier and
emitter, the collapsed `QueryServicePolymorphicField` / `MutationServicePolymorphicField` variant
(exercised end-to-end over both a union and an interface), the child-`@service` deferred reject mirrored
in classifier and validator, and the unit / pipeline / execution coverage — is sound and should carry
forward unchanged.
