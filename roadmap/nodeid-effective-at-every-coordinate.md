---
id: R728
title: "Four @nodeId sites where the directive binds nothing or its join shape cannot be emitted"
status: Backlog
bucket: feature
priority: 3
theme: nodeid
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# Four @nodeId sites where the directive binds nothing or its join shape cannot be emitted

`@nodeId` is a *binding* directive. It names a wire format, the base64 `(typeId, key columns)`
node ID, and asks the generator to cross between that wire form and typed key columns: encode on
an output field, decode on an argument or an input field. Where the binding is wired, an author
writes the directive and never sees the base64. Where it is not, the author writes the same
directive, the build says nothing, and the raw string flows through to consumer code that takes
it apart by hand.

A field report against 10.0.0-RC32 names four sites where that second thing happens. Three of
them are the directive binding nothing: an argument of a `@service` field, a member of a
bean-backed `@service` input, and a field on an `@error` type. The fourth is different in kind
and shares the same consequence: two filter shapes whose predicate needs a correlated `EXISTS`
the emitter does not build, so the author decodes the node ID by hand inside a `@condition`
method. In each case the schema *reads* as though graphitron owns the wire format and it does
not.

This is filed as one item because the four share a subject rather than a mechanism. The subject
is the promise the directive makes at every site it may legally be written. Splitting them
produces four items each of which is individually easy to defer as a corner, and leaves nobody
owning the question the reporter is actually asking, which is whether `@nodeId` means the same
thing everywhere.

## The four sites

Each site below was reproduced on trunk. The evidence is the classified carrier or the generated
body, not a reading of the source.

### 1. A plain argument on a `@service` field

```graphql
publiserRundeV2(plasstildelingId: ID! @nodeId(typeName: "PlasstildelingV2")): PubliserRundeV2Payload
    @service(service: {className: "...", method: "publiserRundeV2"})
```

The generated fetcher passes the wire string straight to the service method, so the service
decodes by hand. `ServiceCatalog.argExtraction` is the whole story: it inspects wire coercion and
enum parity and never looks at `@nodeId`, so every scalar `@service` argument resolves to
`CallSiteExtraction.Direct`. The classified field carries
`leafTransform=Direct[]` on the argument's `ValueShape.Scalar`.

`ServiceMethodCallEmitter.scalarLeaf` does carry a `CallSiteExtraction.NodeIdDecodeKeys` arm, and
that arm emits the same raw cast as `Direct`. So even if the extraction were minted, the emitter
would drop it. Both halves are in scope.

### 2. A member of a bean-backed `@service` input

The same silence one level in. An `ID` field carrying `@nodeId(typeName:)` on an input type that
backs a consumer bean generates:

```java
java.lang.String title = (java.lang.String) raw.get("title");
```

inside the `create<Bean>` helper. This is the shape the reporter reached for as the workaround
for site 1 and found equally inert.

### 3. A field on an `@error` type

An `@error` type's extra fields are read off the matched exception through an accessor. An `ID`
field carrying `@nodeId(typeName:)` classifies as a plain
`GraphitronField.RecordReadField` with a `DefaultRead` locator: the accessor's value is emitted
verbatim, and the directive contributes nothing. The consumer's exception constructor therefore
takes an already-encoded string, and the encoding happens in hand-written code:

```java
new SoknadFeilSvarfristUtloptException(encodeOpptaksrunde(record), ...)
```

The ask is `@nodeId(typeName:)` on the error field plus a typed value on the exception, with the
encode emitted.

### 4. Two filter shapes whose predicate needs an EXISTS

Both have fully declared foreign-key paths. They differ in whether the emitter can build the
predicate.

**Reverse direction, filtering parents by their children's node IDs.** This one already works and
is reported as broken because nothing points the author at the spelling. The single reverse hop
classifies as `NodeIdLeafResolver.Resolved.FkTarget.TranslatedFk` and lowers to
`BodyParam.RemoteColumnPredicate`, the correlated `EXISTS`, on both the argument and the
input-field surface. What stops an author reaching it is that foreign-key auto-discovery searches
from the containing table outward only, so a reverse hop rejects with "no unique FK from X to Y"
unless the child's constraint is named explicitly:

```graphql
utdanningstilbudIds: [ID!] @nodeId(typeName: "UtdanningstilbudV2")
    @reference(path: [{key: "utdanningstilbud_opptak_fkey"}])
```

The remedy here is documentation and possibly a better rejection message, not an emitter. See
"Relationship to other items" for the manual page that currently tells the reader this shape
cannot exist.

**One-to-many through a junction table.** `Sak` filtered by `Tagg` IDs via `soknad_tagg`. Every
hop is a declared foreign key, but the chain is not *identity-carrying*: the second hop's
source-side columns are absent from the first hop's target-side columns, so no column tuple on
the parent's own row holds the decoded key. `NodeIdLeafResolver.validateLift` rejects it. The
message names the failing hop and is accurate about the cause, but the shape it describes is
ordinary and the emitter it needs is the same correlated `EXISTS` the single-hop translated case
already emits, walked over more than one hop.

## What already ships, so the Spec does not rebuild it

* **The wire format is generated and public.** `NodeIdEncoder` lands in `<outputPackage>.schema`
  whenever any type carries `@node`, as a final class with a private constructor and public
  static `encode<TypeName>(k1, ..., kN)` / `decode<TypeName>(String)` per node type, plus
  `peekTypeId(String)`. Hand-rolled base64 in consumer code is never necessary, only inconvenient.
  Site 3's reporter can collapse their three hand-rolled encoders onto `encode<TypeName>` today,
  which is worth telling them whether or not this item ships.
* **The jOOQ `TableRecord` service parameter decodes.** When a `@service` method parameter is a
  generated `*Record`, an `@nodeId` field on the backing input type decodes into that record's key
  columns (same-table identity) or its foreign-key child columns (cross-table reference). This is
  the shape site 1's reporter wants, reachable today by giving the method a
  `PlasstildelingRecord` instead of a `String`. It is a workaround for the ask rather than an
  answer to it, because it forces an input type where the author wrote an argument.
* **Single-hop translated-FK filters emit the `EXISTS`.** Both read surfaces. The write and
  `@lookupKey` rails deliberately refuse the shape at their own gates.
* **Multi-hop identity-carrying chains lift to a single-table predicate.** No JOIN, no subquery,
  chain length is a classifier-time concept.

## Direction for Spec

Two questions look like the spine.

**What does a decoded `@nodeId` deliver to consumer code?** Sites 1 and 2 each have two plausible
answers: the key columns as scalars, or a typed jOOQ `TableRecord`. The record answer is what the
reporter asked for and what the existing `TableRecord` parameter path already does at a
neighbouring coordinate; the key-column answer is what the in-flight `argMapping` projection work
delivers. The Spec should decide whether these are one capability with two shapes or two
capabilities, and say which sites get which.

**Where does an ineffective `@nodeId` get rejected, and is rejection a substitute or a
companion?** The reporter's second ask is a classify-time error naming the slot, on the reasoning
that a silent no-op is worse than a build failure because the schema documents a decode that does
not happen. That rejection is cheap and is worth landing ahead of any emission. The precedent
exists: `@nodeId` on a non-`ID` coordinate is already a validate-time rejection, so the
vocabulary for "this directive cannot bind here" is established and this extends it from the
slot's *type* to the slot's *coordinate*. The open part is whether a site that later gains
emission should reject in the meantime, or whether shipping a rejection for a site the Spec
intends to make work is churn for authors.

Sequencing note: rejection and emission at the same site are two halves of one author-facing
change. Landing a rejection for a spelling whose replacement does not yet work leaves an author
told to write something the generator will not accept, which is worse than either end. Whatever
the Spec decides, it should not ship a rejection that has no accepted replacement.

## Relationship to other items

* **R668** (`nodeid-key-projection-on-routine-params`, In Progress) is the nearest neighbour and
  overlaps sites 1 and 2 partially. It makes a node type's key columns nameable as a trailing
  `argMapping` path segment and its final stage lands the `@routine`, `@service` and output-field
  `@condition` sites together, and it already carries the bare-form rejection this item's second
  question asks about. The overlap is bounded in a way that matters: R668's surface is the
  `argMapping` right-hand side, and the store's pair relations record the mapping *as written*, so
  a `@service` argument with no authored `argMapping` produces no pair row and is reached by
  neither R668's projection nor R668's rejection. That is exactly the reported shape. R668 also
  rules out binding a whole decoded record to a `@service` parameter as a separate capability,
  which is the record-shaped half of this item's first Spec question. Whether this item depends on
  R668, absorbs its unreached cases, or is scoped to exclude them is a Spec decision that should
  be taken with R668's author.
* **R57** (Done, see `roadmap/changelog.md`) shipped the single-hop translated-FK `EXISTS` and
  filed multi-hop translated paths as deferred. Site 4's junction case is that deferral. Its
  reasoning that `EXISTS` is the semantically right shape rather than a convenient one, because a
  non-unique path multiplies no rows and a NULL foreign-key column fails the correlation instead
  of duplicating, is the argument site 4 inherits.
* **R705** (`condition-join-hops-in-reference-filter-paths`, Spec) is R57's sibling deferral for
  the other rejected hop kind. Site 4's second half and R705 want the same emitter reached through
  different path elements; they should be read together.
* **R691** (`multi-hop-nodeid-filter-single-fk-claim`, Backlog) is why site 4's reverse case reads
  as unsupported. `docs/manual/how-to/multi-hop-nodeid-filter.adoc` still tells the reader that a
  single direct foreign key never produces a subquery and that the translated emission "is not yet
  shipping", both of which R57 made false. An author checking the manual before filing concludes
  correctly from the page and incorrectly about the generator.
* **R262** (Done) rejects `@nodeId` on a non-`ID` coordinate at validate time. The precedent for
  this item's rejection half.

## Open questions

* Does the reverse-direction filter shape need anything beyond documentation and a message that
  names the explicit-`{key:}` remedy? It classifies and lowers correctly today; nobody has pinned
  its row semantics at the execution tier, so that is the one thing worth checking before calling
  it shipped.
* Should the `@error` site (3) reuse the output-field encode path, which resolves key columns
  against a catalog table, or does an exception-backed field need its own binding because the
  typed value arrives from a Java accessor rather than from a row? This is the site with the least
  existing machinery pointed at it and may be the one that separates.
