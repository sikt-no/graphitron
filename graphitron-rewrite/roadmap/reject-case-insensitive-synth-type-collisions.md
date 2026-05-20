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

The GraphQL spec makes type names case-sensitive
(`/[_A-Za-z][_0-9A-Za-z]*/`), and `graphql-java` follows the spec: `type Foo`
and `type foo` parse as two distinct types. Graphitron then maps each
emitted-file-producing type name to a Java filename on disk. On
case-insensitive filesystems (macOS APFS default, Windows NTFS default), any
two type names that differ only in case map to the *same* filename and clobber
each other silently. The consumer build then picks up only one of the two
emitted classes, and `javac` fails downstream with cascading "cannot find
symbol" errors that don't name the root cause.

The trigger is not synthesis-specific. Three reproducible flavours, all the
same bug:

1. *SDL-vs-SDL* — author writes two types differing only in case
   (typo, copy-paste, or two parallel domain concepts with unfortunate naming):

   ```graphql
   type Poengklasse { id: ID! }
   type poengklasse { id: ID! }
   ```

2. *Synth-vs-synth* — two sibling carriers differ only in case, so
   `ConnectionPromoter`'s default name-synthesis (`<Parent><Field>Connection`)
   derives two case-clashing Connection (and Edge) names. Concrete repro
   reported by a consumer:

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
   derives `QueryPoengklasserv2Connection` / `Edge` and
   `QueryPoengklasserV2Connection` / `Edge`.

3. *SDL-vs-synth* — author declares a type whose name case-folds to the same
   string as a synthesised Connection/Edge/PageInfo name (e.g. the SDL declares
   `Querypoengklasserv2connection` while a sibling carrier synthesises
   `QueryPoengklasserV2Connection`).

In all three, registration succeeds because `TypeRegistry`'s backing map is
case-sensitive (`LinkedHashMap` on the String key, see
`TypeRegistry.java:32`), no downstream gate catches the clash
(`GraphitronSchemaValidator` walks per-type but has nothing keyed on
case-folded names), and the file-emit step trusts the type name as-given.
Graphitron should refuse to participate in producing code that won't compile,
not silently emit it.

## Today's behaviour

Type registration flows through several entry points, all of which terminate
in `TypeRegistry`'s case-sensitive map:

- `TypeBuilder` classifies every author-declared SDL type via
  `ctx.typeRegistry.classify(name, ...)`.
- `ConnectionPromoter.promote(BuildContext)` (`ConnectionPromoter.java:81`)
  registers synthesised `ConnectionType` / `EdgeType` / `PageInfoType` via
  `ctx.typeRegistry.synthesize(...)`, and enriches structural Connection
  variants via `enrich(...)`.
- `EntityResolutionBuilder` and `NodeIdLeafResolver` classify federation
  participants and NodeId-routed types into the same registry.

Each entry point rejects exact-string duplicates by contract
(`classify` / `synthesize` throw on collision), but a case-only-differing name
is, by design, a distinct registry key and registers happily.
`GraphitronSchemaValidator` walks each `GraphitronType` variant in turn but
holds no cross-type case-fold view, and the file-emit step trusts the type
name as-given.

The same algorithmic defect exists in the legacy
`MakeConnections.maybeCreateConnectionType`
(`graphitron-schema-transform/src/main/java/no/fellesstudentsystem/schema_transformer/transform/MakeConnections.java:272`)
and likely in the legacy SDL-classification path, but legacy modules are out
of scope for AI work per `CLAUDE.md`; this item fixes the rewrite only. The
legacy fix lands separately if and when a human maintainer chooses to
backport.

## Design

### Detection: a single post-classification pass

The check belongs over the *full* classified type set, not at any one
registration site. A single case-folded uniqueness pass runs after all
classifiers have populated `ctx.typeRegistry` ; that means after
`TypeBuilder` (SDL types), `ConnectionPromoter.promote` (synth + structural
Connection types), `EntityResolutionBuilder` (federation participants), and
`NodeIdLeafResolver` (NodeId-routed types). Logical home:
`GraphitronSchemaBuilder`, immediately after `ConnectionPromoter.promote`
returns and before the validator runs.

Algorithm:

1. Walk `ctx.typeRegistry.entries()` (read-only view in insertion order). For
   each entry whose variant is one that produces an emitted Java file
   (everything except `UnclassifiedType` and scalar variants ; see
   *Emission predicate* below), record `name.toLowerCase(Locale.ROOT) → name`
   into a `Map<String, List<String>>` collision-group multimap.
2. Each group with size ≥ 2 is a case-clash. For every member of every
   colliding group, call
   `typeRegistry.demote(name, new UnclassifiedType(name, location, rejection))`.
   `demote` is the right operation because `classify` / `synthesize` asserts
   no prior entry exists, while `demote` asserts a prior entry does ; which is
   exactly the situation here.

Demoting *every* group member, not just the second-arrived, is the principled
choice for SDL-vs-SDL collisions: there is no logical "winner" between two
author-declared types, and silently picking one would tilt the schema's
public surface against the author's intent. The trade-off is a small amount
of noise (each member surfaces its own `ValidationError`), each carrying the
same actionable message naming the full collision group.

`Locale.ROOT` because GraphQL identifier names are ASCII-only by spec rule
(`/[_A-Za-z][_0-9A-Za-z]*/`). A Turkish locale wouldn't change folding
outcomes today, but `Locale.ROOT` pins the contract against any future
identifier-extension RFC.

#### Emission predicate

Not every classified variant produces an emitted Java file. `ScalarType`
(scalar binding, no class generated) is the main non-emitting case;
`UnclassifiedType` is also trivially excluded. Every other variant in
`GraphitronType` ; the output-type family (`PlainObjectType`, `TableType`,
`NodeType`, `RootType`, `JavaRecordType`, `JooqRecordType`,
`JooqTableRecordType`, `ErrorType`, `TableInterfaceType`, `InterfaceType`,
`UnionType`), the input-type family (`JavaRecordInputType`, `PojoInputType`,
`JooqRecordInputType`, `JooqTableRecordInputType`, `TableInputType`),
`EnumType`, and the synth-only `ConnectionType` / `EdgeType` / `PageInfoType`
; produces a file at the type-name stem. The check should consider only
the emitting variants, otherwise an unemitted clash surfaces as a
non-actionable error.

Implementer note: express the predicate as a `default true`
`producesEmittedFile()` method on `GraphitronType`, with `ScalarType` and
`UnclassifiedType` overriding to `false`, rather than an `instanceof` chain
in the detector. The latter would couple the case-clash check to the type
taxonomy in a way that drifts as variants are added. Audit the full sealed
hierarchy at implementation time and update the override list if any new
non-emitting variant has appeared since this spec was written.

### Surface: `UnclassifiedType` carrying `Rejection.invalidSchema`

`TypeRegistry` already has a "this name failed to classify" sink:
`UnclassifiedType` (`GraphitronType.java:485`) carrying a `Rejection` and a
`SourceLocation`. `GraphitronSchemaValidator.validateUnclassifiedType`
(`GraphitronSchemaValidator.java:62, 918`) projects it into a
`ValidationError` keyed off the `Rejection` variant. This is the established
type-axis path for "the classifier produced no usable variant for this name",
and it fits the case-clash verbatim: the prior classify/synthesize call
produced a valid variant, the case-clash discovery takes that off the table,
the registry demotes to `UnclassifiedType` so the validator surfaces a
diagnostic.

The rejection arm is `Rejection.invalidSchema(...)`
(`InvalidSchema.Structural`): the author can fix it (rename a type, rename a
carrier field, or supply `@asConnection(connectionName: "…")`), but it's a
structural-rule violation rather than a name-against-a-closed-set lookup, so
`InvalidSchema.Structural` is the right shape rather than
`AuthorError.Structural` or any `UnknownName` variant. Precedent: the other
`Rejection.invalidSchema` sites in `GraphitronSchemaValidator`
(lookup-field-returning-connection at L410, input-cardinality-mismatch at
L430) are the same shape: "this combination cannot work, period."

Message format names the full collision group and the fix:

```
type '<ThisName>' clashes with <comma-separated-other-names> on a
case-insensitive filesystem (APFS, NTFS); the names case-fold to
'<lowered>'. Rename one of these types so the names differ by more than
case.
```

For collisions involving synthesised types, the message can append a
synthesis-aware hint: "synthesised from `<Parent>.<field>` via
`@asConnection`; set `@asConnection(connectionName: "…")` on the carrier or
rename the field". The implementer decides whether to fork the message
format per origin or compute it from `GraphitronType` variants at message
construction time. Either is fine; the rejection's structured data (the
collision group as a list of names) is what an LSP fix-it would read, not
the prose.

`SourceLocation` on each `UnclassifiedType` is the demoted type's own
location (preserved from the prior classification's variant). For
synthesised Connection/Edge/PageInfo today, that location is `null`; the
diagnostic still names the type. R194 does not fix the synthesised-type
location gap; that's a separate concern.

## Implementation sites

- `GraphitronSchemaBuilder`: add a new private method (call it
  `rejectCaseInsensitiveTypeCollisions(BuildContext ctx)`) that runs the
  detection pass described above. Call it immediately after
  `ConnectionPromoter.promote(ctx)` returns. No new file.
- `GraphitronType` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronType.java`):
  add a `default boolean producesEmittedFile() { return true; }` accessor on
  the sealed root, with overrides returning `false` on `UnclassifiedType`,
  `ScalarType`, and any other non-emitting variant (audit the sealed
  hierarchy when implementing). This is the "emission predicate" the
  detector consults.
- `TypeRegistry`: no surface change. The detector uses the existing
  `demote(name, UnclassifiedType)` operation.
- `GraphitronSchemaValidator`: no change. `validateUnclassifiedType` already
  surfaces the rejection.

The directive schema is unchanged; no new model variant; no new
`@LoadBearingClassifierCheck` (the failure mode demotes to `UnclassifiedType`
rather than relying on a downstream emitter's narrowed shape).

## Tests

### Pipeline-tier (primary)

`GraphitronSchemaBuilderTest` gains a `CaseInsensitiveTypeClashCase`
enum-style arm, parallel to the existing failure-cases in that file. Each
sub-arm feeds an SDL through `GraphitronSchemaBuilder.buildContextForTests`
and asserts that `ctx.typeRegistry.get(<colliding-name>)` is an
`UnclassifiedType` with a `Rejection.InvalidSchema.Structural`, *and* that
the resulting `BuildResult` carries a `ValidationError` whose kind is
`INVALID_SCHEMA` for each colliding name. Sub-arms cover all three flavours
from the Problem section:

- *sdlVsSdl* — two SDL `type Foo` / `type foo` declarations.
- *synthVsSynth* — the `poengklasserv2` / `poengklasserV2` carrier pair.
- *sdlVsSynth* — author-declared `Querypoengklasserv2connection`-style type
  case-clashes with a synthesised carrier name.
- *threeWay* — three case-equivalent names (e.g. `Foo`, `foo`, `FOO`); the
  detector demotes all three.

Pipeline-tier covers the validator wiring end-to-end and the cross-classifier
nature of the check (SDL types arrive via `TypeBuilder`, synth via the
promoter, all funnel into the same detection pass).

### Unit-tier (supplementary)

A small focused test class (e.g. `CaseInsensitiveTypeCollisionDetectorTest`)
exercises the detector in isolation against a hand-populated
`TypeRegistry`: ASCII-identifier folding, single-character case differences,
multi-member collision groups, no-clash baseline, non-emitting variants
correctly ignored. Pipeline-tier carries the behaviour signal; unit-tier
covers the small predicate space cheaply.

### Compilation-tier and execution-tier

Not applicable: the change is purely a build-time rejection with no emitted
code path. The point of the rejection is that no code gets emitted at all
when the clash is present.

## Done means

- Any two emitted-file-producing types whose names case-fold to the same
  string produce `ValidationError`s at build time naming the full collision
  group and the available fixes, across SDL-vs-SDL, synth-vs-synth, and
  mixed-origin pairings.
- The pipeline-tier and unit-tier tests above pass.
- No emitted Java code change for schemas that don't trip the clash; the
  existing `ConnectionPromoterTest` and `GraphitronSchemaBuilderTest`
  coverage continues to pass unchanged.
- The diagnostic surfaces at the same build phase as other
  `GraphitronSchemaValidator` errors (no late-fail at file-emit time).

## Out of scope

- The legacy `MakeConnections` defect under
  `graphitron-schema-transform/`, and the analogous SDL-type
  case-sensitivity in legacy classification. Same algorithmic shape, but
  legacy modules are out of scope for AI work per `CLAUDE.md`. Filing
  separate backport items is a human-maintainer call.
- Auto-mangling clash-survivors (e.g. appending `_2`). Rejected at the
  design conversation: two types differing only in case is almost always an
  author mistake, and silent mangling would put a non-author-controlled
  suffix into the schema's public surface that downstream clients then pin
  against. The schema author renames; graphitron refuses to participate
  until they do.
- Catching case-clashes across federation subgraphs. Subgraphs assemble at
  the gateway, not in graphitron; gateway tooling owns that surface.
  Per-subgraph clashes within a single graphitron build are caught.
- Catching collisions among *derived* filenames (e.g. `<Type>Fetchers.java`,
  `<Type>Input.java`) that share a stem but differ by suffix. The
  type-name-level check covers the bulk; if a derived-name collision ever
  surfaces independently of a type-name collision, that's a separate item.
- The previously-out-of-scope SDL-vs-synth case is now *in scope* under the
  broadened detector; this bullet is intentionally retired from the list.

## Future evolution

- Lift the case-folded uniqueness predicate into `TypeRegistry` itself as
  an opt-in helper (`tryClassifyOrCaseClash(name, type)`) if a second
  consumer (beyond the post-classification pass introduced here) ever wants
  the check inline at registration time. The post-classification pass is
  cheap enough today that an inline check is not warranted.
- Optional LSP fix-its keyed off the typed `InvalidSchema.Structural` arm:
  "rename `<X>` to `<X>2`" for SDL-vs-SDL, "add
  `@asConnection(connectionName: \"…\")` to `<Parent>.<field>`" for
  synth-involving collisions. The diagnostic carries enough structured data
  (the collision group as a list of names plus per-type locations) for an
  LSP consumer to read it back; the fix-it itself is LSP-side work and
  belongs in a follow-up.
- Derived-filename collision detection (see *Out of scope*) if a real
  consumer-reported case ever appears that the type-name check misses.

## Open questions for the reviewer

1. *Message specialisation per origin.* The detector knows each type's
   variant (and can therefore distinguish synthesised
   `ConnectionType`/`EdgeType`/`PageInfoType` from SDL-declared variants).
   The diagnostic message can either stay generic ("type 'X' clashes with
   'Y' on a case-insensitive filesystem") or specialise per origin
   ("synthesised connection type 'X' from `Q.f` clashes with SDL type
   'Y'"). Recommendation: specialise, because the synth case has an
   actionable hint (`@asConnection(connectionName: …)`) that the SDL-only
   case doesn't, and the implementer's switch on the prior variant is
   cheap. Reviewer override welcome.
2. *Demote-all vs. demote-non-first for collision groups.* Spec body picks
   demote-all on the "no logical winner" principle. Demote-non-first is the
   smaller-error-noise alternative. The choice changes the test assertion
   shape (one error vs. N) but not the underlying contract. Genuinely open.
3. *Locale of the case-fold.* `Locale.ROOT` per the spec body. GraphQL
   names are ASCII-only so the choice is cosmetic, but a future
   identifier-name extension (graphql-spec RFC-level) might change that.
   Genuinely open whether to encode the locale choice in a named constant
   the future spec can point at, or leave it inline.
