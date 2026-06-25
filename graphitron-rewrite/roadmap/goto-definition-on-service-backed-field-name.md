---
id: R376
title: "Goto-definition on a service-backed or computed field name"
status: Spec
bucket: feature
priority: 3
theme: lsp
depends-on: []
created: 2026-06-25
last-updated: 2026-06-25
---

# Goto-definition on a service-backed or computed field name

Goto-definition on an SDL field name resolves to the Java the field binds to only when the field
maps to a jOOQ column, a POJO accessor, or a record component, i.e. only through the enclosing
type's backing. A field whose value comes from a Java method, a `@service` field, an
`@externalField`, or a computed field, has no field-name jump at all: the cursor must be moved
into the directive's `method:` / `className:` argument before `Definitions` will navigate. That is
the inconvenience this item removes. The bound class and method are already resolved and already
sitting in the LSP snapshot (the declaration-name hover prints "Service method `X#Y`" for these
fields today), so the jump target exists; goto simply never consults it for the field-name trigger.

## What exists today

Goto-definition is three providers chained with `.or()` in `GraphitronTextDocumentService`:

- **`Definitions`** (`definition/Definitions.java`): cursor *inside a directive argument*. Dispatches
  on the cursor coordinate's `Behavior` and resolves the service half via `methodTarget` /
  `classTarget` (`Definitions.java:181-215`). This is the path a user is forced onto today for a
  service-backed field, by parking the cursor on `method:` or `className:`.
- **`DeclarationDefinitions`** (`definition/DeclarationDefinitions.java`): cursor *on a
  type-/field-declaration name*. Resolves through the shared `DeclTarget` (`DeclarationDefinitions.java:61-79`).
- **`IntraSchemaDefinitions`**: cursor on a type *reference*.

The field-name gap is in `DeclTarget.resolve`'s `FieldName` arm → `ofField` (`DeclTarget.java:108-126`),
which dispatches *only* on the enclosing type's `TypeBackingShape`:

- `@service` field on a root (`Query`/`Mutation`): parent is `NoBacking.Root` → `None` → no jump.
- `@externalField` / computed field on a `@table` type: `columnTarget` looks up a column named like
  the field, finds none → `None` → no jump.

So the field-name resolves to nothing for exactly the method-backed fields this item targets.

## The target data already exists

The build tier classifies these fields and carries the bound class + method on the classification,
projected into the snapshot at `LspSchemaSnapshot.fieldClassificationsByCoord()` (keyed
`"Type.field"`; helper `fieldClassification(typeName, fieldName)` at `LspSchemaSnapshot.java:120`).
The method-backed `FieldClassification` variants, each carrying `methodClassName()` + `methodName()`:

[cols="1,1"]
|===
| Variant | Site

| `ServiceBacked` (`FieldClassification.java:307`)
| field-level `@service`

| `Computed` (`:325`)
| field-level `@externalField`

| `TableMethod` (`:261`)
| `@tableMethod` child field

| `QueryService` (`:415`)
| root `@service` query field

| `MutationService` (`:439`)
| `@service` mutation field

| `QueryTableMethod` (`:375`)
| `@tableMethod` root query field
|===

`DeclarationHovers` already reads these for the classification block. Goto and the hover Javadoc
overlay do not.

## Approach: route through the shared `DeclTarget`, resolve by arity with a name-level fallback

Resolve the method-backed field name through the same `DeclTarget` both goto and the
declaration-name hover overlay project, so the capability lands in goto *and* closes the
hover-overlay gap in one move, and the goto/hover parity the module is built around stays
structural. Two pieces.

### 1. New resolution arm in `ofField`

Before the `TypeBackingShape` dispatch, consult `built.fieldClassification(parentTypeName, fieldName)`.
If it is one of the six method-backed variants, resolve to a `SourceMethod` for its
`(methodClassName, methodName)`. This takes **precedence** over the parent-type backing: a field
carrying `@service` / `@externalField` is bound to that method, not to a column on its parent table.
The arm stays in the pure `ofField` core (it reads the already-projected classification off `built`,
no source-index read, mirroring how the existing arms read `built.typeBacking(...)`).

### 2. Resolve by `(class, name, arity)`, with a name-level fallback that never declines

Arity is used as the primary key, and is cheap: the catalog already carries the bound method's
parameter count, and using it raises the hit rate by landing on the *correct* overload rather than a
sibling. Two overload cases must be kept distinct:

- **Arity-distinguishable overloads** (`greet()` vs `greet(String, int)`) are *not* ambiguous: each is
  a distinct `MethodKey(class, name, paramCount)` (`SourceWalker.java:84`). The bound arity selects the
  right one. Discarding arity here would throw away recoverable precision and risk landing on the wrong
  overload, so the resolution keys on arity first. This is what goto's existing `methodTarget`
  (`Definitions.java:181-197`) already does; this item must not regress it.
- **Same-name/same-arity overloads** (`find(String)` vs `find(int)`) are the *only* genuine ambiguity:
  they collide on one `MethodKey`, get dropped, and are recorded as ambiguous (`SourceWalker.java:306-309`,
  `:201-203`). Today goto declines here (`DefinitionTarget.Ambiguous` → empty, `:213`). Declining is the
  wrong call, the overloads are adjacent in the same file, so landing on either gets the developer to
  the destination. This is the case the fallback covers.

So resolution is a two-step floor: **arity-keyed lookup first** (precise, the correct overload); when
that key is absent or was dropped as a same-arity collision, **fall back to a name-level lookup** and
land on the first declaration of that name. Precision where it exists; never a non-jump where a
declaration is indexed.

Two supporting changes:

- **Carry the arity on `SourceMethod`.** `SourceMethod` carries only `(fqClassName, methodName)`, so the
  two consumers re-derive the arity independently and drift: goto reads it from the catalog, the hover
  overlay hardcodes `0` (`DeclarationHovers.overlay`, `:137-138`), correct only for its current producer
  (zero-arg POJO accessors). A service method takes at least a `DSLContext`, so reusing `SourceMethod`
  as-is would make goto jump while the overlay returns empty, violating `overlayIsPresentExactlyWhenGotoJumps`
  (`DeclarationHoverOverlayParityTest.java:101-125`). Widen to `SourceMethod(fqClassName, methodName, paramCount)`,
  stamped in `resolve()` (which has the catalog: the accessor arm sets `0`, the method-backed arm reads
  the parameter count off the catalog method of that name). When the class lists a single method of that
  name, that is the bound arity and the jump is precise; when the name is overloaded by arity the
  classification does not record which overload bound, so `resolve()` takes the first catalog candidate's
  arity and the name-level fallback below guarantees a jump if that exact key was dropped. Both projections
  then build the identical `MethodKey` and share the identical fallback, so they cannot diverge and the
  arity-0 hardcode is deleted, not special-cased.
- **A non-dropping name-level lookup on `SourceWalker.Index`.** The arity-keyed `methods()` map drops
  collided keys entirely (`:201-203`), so the fallback would still miss a same-arity-collided method.
  Add a lookup keyed by `(class, name)`, first declaration wins, never dropped, so a jump is always
  available when the class is indexed. The arity-keyed map stays primary; the name view is the floor.

Both projections (`DeclarationDefinitions` goto, `DeclarationHovers.overlay`) run the same two-step
resolution through one shared helper: arity key → name fallback → `SourceAbsent` only when the class
carries no declaration of that name at all (or is not indexed). Parity is structural because both arms
call the same helper.

### Consequences for the existing paths

- The existing POJO-accessor field-name path is unchanged in behaviour: its arity is `0`, the arity-keyed
  lookup resolves exactly as before, and the hover overlay's arity-0 hardcode is replaced by the carried
  arity rather than special-cased.
- `DefinitionTarget.Ambiguous` stops being a non-jump: the same-arity-collision case now falls back to a
  name-level jump. The directive-argument `Definitions.methodTarget` path is aligned to the same floor
  (arity-primary, name fallback, never decline); whether to fold that alignment into this item or split
  it is an open question below.
- `SourceMethod.accessorMethodName` is a misnomer once the variant also names service methods; rename to
  `methodName` as part of the widening (touches the two projections + the producer + the parity test).

## Why not a goto-only fix

A goto-only step in `DeclarationDefinitions` (resolve the classification via `methodTarget`, leave
`DeclTarget` and hover untouched) is smaller and also closes the goto gap, but it leaves the
hover-overlay arity defect live and lets goto and hover diverge for service fields, against the
module's structural-parity design. Routing through `DeclTarget` with the shared arity-primary
resolution fixes the latent defect at its source for a bounded cost.

## Acceptance

- Field-name goto jumps to the bound method for each method-backed variant: at minimum a root
  `@service` (`QueryService`), a field-level `@service` (`ServiceBacked`), an `@externalField`
  (`Computed`), and a `@tableMethod` child (`TableMethod`). Exercised in `DeclarationDefinitionsTest`.
- An arity-distinguishable overload (`greet()` vs `greet(String, int)`) resolves to the *correct*
  overload via the bound arity, pinned so the precision is not silently lost to a name-only fallback.
- The declaration-name hover overlay shows the bound method's Javadoc for the same fields (no longer
  arity-0-only); `DeclarationHoverOverlayParityTest` gains a non-zero-arity `SourceMethod` case so
  goto-jumps-iff-overlay-present holds for service methods.
- A same-name/same-arity overload still produces a jump (to the first declaration) via the name-level
  fallback, pinned by a test on the new index lookup; no `Ambiguous` non-jump remains on the navigation
  path.
- Existing `graphitron-lsp` suite stays green; the precedence rule does not regress column / accessor
  / component resolution for non-method-backed fields.

## Open questions

- **Directive-arg path alignment**: fold the `Definitions.methodTarget` fallback (retiring
  `DefinitionTarget.Ambiguous` as a non-jump in favour of the name-level floor) into this item, or split
  it into a follow-up? It is the same principle and helper; leaning fold-in since leaving one path
  declining-on-ambiguity while the other falls back is incoherent.
- **Fallback tiebreak determinism**: the fallback only fires for the same-arity-collision case; when it
  does, define "first declaration" as source order within a file and first-file-wins across the merge
  (the walker's natural visit order). Confirm that is deterministic across the index merge in `SourceWalker`.
- **Arity for an arity-overloaded service name**: `FieldClassification` carries `methodName` but not the
  resolved overload's signature, so for a name with several arities `resolve()` cannot recover the bound
  one and takes the first catalog candidate (the name-level fallback still guarantees a jump, just maybe
  to a sibling). Accept this degradation for a rare case, or record the bound arity in `FieldClassification`
  upstream if the precision is judged worth a build-tier change (currently out of scope).
- **Short class-name binding**: a directive `className:` may be a simplified name when the package is
  imported in `externalReferences` config; resolution against `externalReferences().className()` /
  the FQN-keyed source index is a pre-existing limitation of the directive-arg path, inherited here,
  not solved by this item. Flag only.

## Scope

In scope: the `ofField` resolution arm, the `SourceMethod` arity widening + rename, the arity-primary
projection with its name-level fallback, the non-dropping name-level lookup added to
`SourceWalker.Index`, the hover-overlay arm update, the directive-arg path alignment (pending the open
question), and tests. Spans `graphitron-rewrite/graphitron-lsp/` and the small `SourceWalker.Index`
addition in `graphitron-rewrite/graphitron/`.

Out of scope: how `FieldClassification` is computed in the build tier (the data is already produced);
new LSP protocol surface; the legacy modules at the repo root.

## Lineage

Surfaced 2026-06-25 from a user request: after goto-definition reached the directive-argument and
declaration-name cases, the remaining friction was that service-backed / computed field *names* still
required navigating into the directive. Investigation found the jump target already resolved in the
snapshot, a latent arity-0 hardcode in the hover overlay, and an ambiguity non-jump rooted in the
index keying methods by parameter count. A first design draft dropped arity entirely and resolved by
name; the user corrected that, arity is cheap, the catalog already carries it, and it raises the hit
rate by landing on the correct overload. The settled design keeps arity primary and uses a name-level
fallback only for the genuine same-arity collision, so precision is preserved and the navigation never
declines.
