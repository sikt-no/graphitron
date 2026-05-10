---
id: R44
title: "Deprecate `@multitableReference`"
status: Spec
bucket: cleanup
priority: 5
theme: interface-union
depends-on: []
---

# Deprecate `@multitableReference`

No consumer of graphitron-rewrite uses `@multitableReference`. Rather than carry the
stub through the RC and ship coverage we don't need, the directive joins `@notGenerated`
on the deprecation list: it stays declared in `directives.graphqls` so the GraphQL parser
does not fail with an "unknown directive" error, but the rewrite classifier rejects every
application with a "no longer supported" message and points the schema author at the
migration note.

The shape `ChildField.MultitableReferenceField` and its sibling `TypeFetcherGenerator`
stub entry exist only to keep the `[deferred]` rejection alive while we figured out
whether to ship support. Now that the answer is no, both can retire alongside the
classifier change. The directive declaration itself remains for parser tolerance.

## Implementation

### Directive declaration

`graphitron-rewrite/graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`:
rewrite the directive description to mirror the `@notGenerated` removal note ("Removed.
`@multitableReference` is no longer supported. The directive stays declared here only so
the GraphQL parser does not fail with an 'unknown directive' error; the rewrite classifier
rejects any application with a 'no longer supported' message. Remove any use of this
directive from your schema."). Leave the `directive @multitableReference(routes:
[ReferencesForType!]) on FIELD_DEFINITION` declaration shape alone so consumer SDLs that
still mention it parse before classification rejects. The accompanying
`input ReferencesForType` declaration also stays; removing it would break parser
tolerance for schemas that still reference the type even though the rewrite never
classifies on it.

### Classifier rejection

`FieldBuilder.classifyField` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java:1438`):
the current early-return `return new MultitableReferenceField(parentTypeName, name, location);`
becomes the same shape as the `@notGenerated` rejection one block up at line 1422 — a
`UnclassifiedField` carrying `Rejection.directiveConflict(List.of(DIR_MULTITABLE_REFERENCE),
"@multitableReference is no longer supported. Remove the directive; the rewrite generates
multi-table interface dispatch from @discriminate / @discriminator without an explicit
multitable-reference path.")`. Move the rejection *above* the `detectChildFieldConflict`
call (currently at line 1432), matching the `@notGenerated` precedent's load-bearing
ordering: the deprecation message must win over a "@multitableReference and @service are
mutually exclusive" conflict so the author sees the real reason first. Capture the
ordering in a one-line comment that mirrors the `@notGenerated` one ("Reject any
application before conflict detection so the user sees the no-longer-supported reason
rather than a misleading 'conflict with @service' message when both directives are
present.").

Delete the existing block-comment at `FieldBuilder.java:1428–1430` ("Detect conflicts
among the child-field exclusive directives before the @multitableReference early-return;
that return would otherwise silently mask a conflicting directive on the same field.").
The comment pins the *pre-R44* invariant — conflict detection runs first to avoid
masking by the multitable early-return. R44 inverts the ordering, so the rationale
becomes wrong. A stale ordering comment next to a freshly-edited code path is the most
insidious flavour of drift; reviewers trust comments that survived a diff. The
replacement is the new one-line comment above the multitable rejection (per the previous
paragraph); the conflict-detection block needs no ordering comment of its own.

`detectChildFieldConflict` at the same file's line 2667: drop the `hasMultitable` /
`DIR_MULTITABLE_REFERENCE` slot from the mutual-exclusivity vocabulary. Once the
deprecation rejection runs first, the conflict detector never sees a live
`@multitableReference` application; carrying it in the slot list is dead vocabulary and
encourages the misleading conflict message we just rerouted around. The `@reference` /
`@nodeId` slot pairing left in the function continues to do the work it does today.

### Model

`ChildField.java` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ChildField.java`):
delete the `MultitableReferenceField` record at lines 597–601 and remove the
`ChildField.MultitableReferenceField` entry from the `permits` clause at line 23. Sealed-switch
exhaustiveness picks up every dispatch site that needs an arm removed.

`GraphitronSchemaValidator.java`: remove the dispatch arm at line 105 (`case
no.sikt.graphitron.rewrite.model.ChildField.MultitableReferenceField f -> validateMultitableReferenceField(f, errors);`)
and the `validateMultitableReferenceField` method at lines 835–841. The "not supported in
record-based output" message there was the validation-side echo of the now-removed
classification path; with the classifier rejecting first, the validator never sees the
variant.

`TypeFetcherGenerator.java`: drop the `MultitableReferenceField` entry from
`STUBBED_VARIANTS` (lines 264–266) and the dispatch arm at line 477
(`case ChildField.MultitableReferenceField f -> builder.addMethod(stub(f));`). The
`stub(f)` helper itself stays — `ColumnReferenceField`, `CompositeColumnReferenceField`,
and `TableMethodField` still use it.

`BuildContext.java`: keep the `DIR_MULTITABLE_REFERENCE` constant — the classifier
rejection still names the directive, and `GraphitronSchemaBuilder.validateDirectiveSchema`
at line 275 calls `assertDirective(ctx, DIR_MULTITABLE_REFERENCE)` to verify the SDL
declaration is intact. Keep the directive's membership in
`PASSTHROUGH_FORBIDDEN_DATA_FIELD_DIRECTIVES` at lines 500–504 too: the set rejects
graphitron-domain directives on passthrough payload data fields, and `DIR_NOT_GENERATED`
sits in the same set (line 503) as the deprecated-but-membership-retained precedent. An
`@multitableReference` field never reaches passthrough analysis post-classification
anyway, but mirroring the `@notGenerated` precedent keeps the set's invariant ("any
directive that changes fetcher contract") truthful by direct reading.

`SchemaDirectiveRegistry.java`: keep `"multitableReference"` in
`GENERATOR_ONLY_DIRECTIVES` (line 44). The directive is still SDL-declared, so the
parser-side registry still recognises it.

## Tests

### Unit-tier deletions

- Delete `MultitableReferenceFieldValidationTest.java` outright. The "not supported in
  record-based output" assertion has no surviving call site once the validator arm goes.

### Pipeline-tier rewrite

- `GraphitronSchemaBuilderTest.java`: rewrite `MultitableReferenceFieldCase` (line 405)
  from "produces a `MultitableReferenceField`" to "produces an `UnclassifiedField` with
  the no-longer-supported deprecation message". Assert the deprecation message text
  (`"@multitableReference is no longer supported. Remove the directive..."`) so a future
  change to the message wording lands as a deliberate test update. The case stays the
  parameterised single entry it is today; nothing else needs the variant.
- `GraphitronSchemaBuilderTest.java`: `MULTITABLE_REFERENCE_AND_SERVICE_CONFLICT` (line
  5967) becomes redundant — once the deprecation rejection runs first, the conflict
  message never fires for this combination. Remove the case rather than retarget it; the
  remaining four child-field conflict cases continue to cover the conflict-detection
  logic. (The `@notGenerated` precedent has the same reasoning: there is no
  `NOT_GENERATED_AND_SERVICE_CONFLICT` case for the same reason.)

The generator-side dispatch test and `VariantCoverageTest` pick up the
`MultitableReferenceField` removal mechanically through sealed-switch exhaustiveness — no
explicit edits needed there.

## Documentation

`docs/manual/how-to/migrating-from-legacy.adoc`:

- Add a short subsection alongside the `@notGenerated` removal at line 20 ("== Hard
  removal: `@multitableReference`"), describing the rejection message and the migration:
  drop the directive; if the use case was multi-table interface dispatch, model it as
  `@reference` + interface/union dispatch (the rewrite generates the resolver from
  `@discriminate` / `@discriminator` without a separate multi-table-reference path).
- Fix line 38: the existing `@notGenerated` matrix sentence currently directs readers to
  `@multitableReference` as the rewrite's replacement for legacy `interface T
  @notGenerated`. With R44 deprecating `@multitableReference`, that sentence is wrong.
  Replace the `@multitableReference` cross-reference with a pointer to the
  `@discriminate` / `@discriminator` recipe (`how-to/polymorphic-types.adoc`).
- Update line 182 (the "unchanged from legacy" survey list): remove
  `@multitableReference` from the list of unchanged directives and surface it instead in
  the new hard-removal subsection.

`docs/manual/reference/directives/multitableReference.adoc`: rewrite the page in the
`@notGenerated.adoc` shape — opener paragraph naming the directive's legacy purpose and
that the rewrite no longer supports it, SDL signature kept (the directive is still
parsed), `== Migration` section pointing at the `@discriminate` / `@discriminator`
recipe, `== Diagnostic` section quoting the rejection message verbatim, `== Constraints`
mirroring the "every application is rejected, no warn-only mode" wording, and a
`== See also` block. The current page's `[NOTE]` (rewrite "currently stubs"), canonical
example, and parameter table go.

`docs/manual/reference/directives/notGenerated.adoc:27`: the matrix's "interface T
@notGenerated / union U @notGenerated" row currently directs readers to `@discriminate`
/ `@discriminator` *and* `@multitableReference`. Drop the `@multitableReference`
cross-reference; the remaining `@discriminate` / `@discriminator` pointer is the live
guidance after R44.

`docs/manual/reference/directives/index.adoc`: the alphabetical list at line 25 gains
the `_(rejected, remove from the schema)_` annotation that line 29 (`@notGenerated`)
already carries; move the entry from the "Joining" category at line 46 down to the
"Rejected by the rewrite" category at line 66 (alongside `@notGenerated`).

`docs/manual/reference/index.adoc:43,64`: the parent reference index also lists
`@multitableReference` (alphabetical at line 43, "Joining" category at line 64). Apply
the same fix — `_(rejected, remove from the schema)_` annotation, move under "Rejected
by the rewrite" if that category exists at this level (mirror whichever shape is in use).

`docs/manual/reference/directives/reference.adoc:92,98`: the `@reference` page carries
two cross-references to `@multitableReference` — line 92 ("Cannot be combined with
`@multitableReference` on the same field") and line 98 ("for the union/interface
variant"). Both become misleading after R44; line 92's combinability constraint is
trivially true (you can't combine with a deprecated directive in any meaningful way) and
line 98 directs readers at a recipe that no longer exists. Drop line 92, retarget line
98 to `@discriminate` / `@discriminator` (or to the `polymorphic-types.adoc` recipe).

`docs/manual/how-to/join-with-references.adoc:233,239`: the recipe carries the same two
cross-references — combinability constraint at 233, "for the union/interface variant"
pointer at 239. Same fix as `reference.adoc`.

`docs/manual/how-to/polymorphic-types.adoc:100–104,129,136`: the most damaging surface.
Line 100 actively recommends `@multitableReference` as the recipe for "more than one FK
between participating tables, or each implementer needs a different join shape"; lines
102–105 carry the legacy `[NOTE]` block ("the rewrite generator currently *stubs*
`@multitableReference` on record-based outputs") that R44 invalidates; lines 129 and 136
repeat the "stubbed in the rewrite" framing. Rewrite the section: drop the recommendation
at line 100 (or replace with "use auto-discovered per-branch FK paths or an explicit
discriminator-driven shape"), strike the `[NOTE]` block, and remove the "stubbed"
framing from lines 129 and 136. The `AddressOccupant` shape the page already documents
remains the supported multi-table-child idiom; the page reads consistently once the
`@multitableReference` paragraphs are out.

`graphitron-rewrite/docs/code-generation-triggers.adoc:230`: the dispatch table row
"`@reference` to multi-table interface | `MultitableReferenceField` | Field method stub"
contradicts the post-R44 classifier. Rewrite the row in the shape of line 232 (the
`@notGenerated` row), e.g. "| `@multitableReference` | `UnclassifiedField` | Validation
error; the directive is no longer supported (`INVALID_SCHEMA` rejection). Remove it; the
rewrite generates multi-table interface dispatch from `@discriminate` /
`@discriminator`."

### Surfaces that survive unchanged (call-out, not edit)

The following sites mention `@multitableReference` and intentionally stay:

- `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/util/SchemaDirectiveRegistryTest.java:15`
  asserts the directive name appears in `GENERATOR_ONLY_DIRECTIVES`. The directive is
  still SDL-declared, so the membership stands.
- `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/schema/DirectiveDefinitionEmitterTest.java:24`
  asserts the directive appears in emitted SDL output. Same reasoning.
- `docs/manual/_generated/supported-directives.adoc` regenerates from the SDL declaration
  set (`@notGenerated` is still listed there at line 25 post-its-deprecation, by the same
  logic). No Spec action.
- `graphitron-rewrite/roadmap/inference-axis-coverage.adoc:233` is regenerated from
  `mvn -Pleaf-coverage verify`; once `MultitableReferenceField` leaves the sealed
  hierarchy, the row drops out automatically. The file's banner says "Never edit by hand."
- `graphitron-rewrite/roadmap/synthesize-payload-carrier.md:250` mentions
  `@multitableReference` in a list of fetcher-contract directives. That plan's author is
  out-of-scope for R44 to amend; if they accept the deprecation, they will retarget on
  their next revision.

## Pattern note

After R44, `@multitableReference` shares the deprecated-but-declared shape with
`@notGenerated`: SDL-declared for parser tolerance, classifier-rejected via
`Rejection.directiveConflict(List.of(<NAME>), "<NAME> is no longer supported. ...")`,
ordered ahead of `detectChildFieldConflict` so the deprecation message wins over a
mutual-exclusivity message. Two parallel implementations is fine — the cleanup is small
and the duplication is local to the classifier. If a *third* deprecated-but-declared
directive lands, factor out a `Rejection.deprecatedDirective(name, message)` helper and a
shared early-return form; do not pre-extract for two.

## Non-goals

Restoring or extending multi-table reference support. If a consumer surfaces a real use
case post-RC, that's a fresh roadmap item, not a revival of this stub.

The `[deferred] multitable-reference-on-scalar` slug currently held by
`STUBBED_VARIANTS` becomes a dead anchor when the entry is removed; no migration of the
slug is needed because no other roadmap item references it.
