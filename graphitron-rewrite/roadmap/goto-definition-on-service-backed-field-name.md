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

## Approach: route through the shared `DeclTarget`, resolve methods by name

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

### 2. Resolve `SourceMethod` by method name, not by `(name, arity)`

Method navigation should resolve **by method name**, landing on any declaration of that name on the
class. The "take me to method `X`" intent is satisfied by any `X`; landing on a sibling overload is
strictly more helpful than declining. Keying navigation by name (not arity) removes two defects in
one move:

- **The arity-0 hover hardcode.** `SourceMethod` carries only `(fqClassName, methodName)`, but the
  source index is keyed by `(class, name, paramCount)` (`SourceWalker.MethodKey`, `SourceWalker.java:84`).
  The two consumers re-derive the arity independently and drift: goto reads it off the catalog
  (`Definitions.methodTarget`, `:181-197`), the hover overlay hardcodes `0`
  (`DeclarationHovers.overlay`, `:137-138`), correct only for its current producer (zero-arg POJO
  accessors). A service method takes at least a `DSLContext`, so reusing `SourceMethod` as-is would
  make goto jump while the overlay returns empty, violating `overlayIsPresentExactlyWhenGotoJumps`
  (`DeclarationHoverOverlayParityTest.java:101-125`). Name-level resolution needs no arity at all, so
  the divergence cannot occur: both consumers call one shared name-level lookup.
- **The ambiguity non-jump.** `SourceWalker` keys methods by parameter *count*, so two overloads with
  the same name and same arity but different parameter types (`find(String)` / `find(int)`) collide on
  one `MethodKey`, get dropped, and are recorded as ambiguous (`SourceWalker.java:306-309`,
  `:201-203`). Goto then declines (`DefinitionTarget.Ambiguous` → empty in `Definitions.resolve`,
  `:213`). Declining is the wrong call: the overloads are adjacent in the same file, so landing on
  either gets the developer to the destination. Resolving by name eliminates the concept: there is
  no "can't pick an overload," only "first declaration of this name."

Supporting change in `SourceWalker.Index`: the arity-keyed `methods()` map drops collided keys
entirely (`:201-203`), so a name lookup over the survivors would still miss a same-arity-collided
method. Add a name-level lookup (a `Map` keyed by `(class, name)`, first declaration wins, never
dropped on collision) so a jump is always available when the class is indexed. The arity-keyed map
stays for the directive-argument path's overload precision; navigation reads the name-level view.

Both projections (`DeclarationDefinitions` goto, `DeclarationHovers.overlay`) resolve `SourceMethod`
through that one helper: found → jump / overlay that `Decl`; class indexed but no method of that name
→ the existing `SourceAbsent` no-jump; class not indexed → `SourceAbsent`. Parity is structural
because both arms call the same lookup.

### Consequences for the existing paths

- The existing POJO-accessor field-name path also routes through the name-level lookup. Behaviour is
  preserved (a single `getX` resolves by name exactly as it did by `(name, 0)`), and the hover
  overlay's arity-0 hardcode is deleted rather than special-cased.
- `DefinitionTarget.Ambiguous` becomes dead on the navigation path. The directive-argument
  `Definitions.methodTarget` path is aligned to the same principle (resolve by name, never decline on
  an arity collision); whether to fold that alignment into this item or split it is an open question
  below.
- `SourceMethod.accessorMethodName` is a misnomer once the variant also names service methods; rename
  to `methodName` as part of the change (touches the two projections + the producer + the parity test).

## Why not a goto-only fix

A goto-only step in `DeclarationDefinitions` (resolve the classification via `methodTarget`, leave
`DeclTarget` and hover untouched) is smaller and also closes the goto gap, but it leaves the
hover-overlay arity defect live and lets goto and hover diverge for service fields, against the
module's structural-parity design. Routing through `DeclTarget` with name-level resolution fixes the
latent defect at its source for a bounded cost.

## Acceptance

- Field-name goto jumps to the bound method for each method-backed variant: at minimum a root
  `@service` (`QueryService`), a field-level `@service` (`ServiceBacked`), an `@externalField`
  (`Computed`), and a `@tableMethod` child (`TableMethod`). Exercised in `DeclarationDefinitionsTest`.
- The declaration-name hover overlay shows the bound method's Javadoc for the same fields (no longer
  arity-0-only); `DeclarationHoverOverlayParityTest` gains a non-zero-arity `SourceMethod` case so
  goto-jumps-iff-overlay-present holds for service methods.
- A same-name/same-arity overload still produces a jump (to the first declaration), pinned by a test
  on the new name-level index lookup; no `Ambiguous` non-jump remains on the navigation path.
- Existing `graphitron-lsp` suite stays green; the precedence rule does not regress column / accessor
  / component resolution for non-method-backed fields.

## Open questions

- **Directive-arg path alignment**: fold the `Definitions.methodTarget` name-level switch (retiring
  `DefinitionTarget.Ambiguous` as a non-jump) into this item, or split it into a follow-up? It is the
  same principle and helper; leaning fold-in since leaving one path declining-on-ambiguity while the
  other lands-by-name is incoherent.
- **Tiebreak determinism**: when several declarations share a name, define "first" as source order
  within a file and first-file-wins across the merge (the walker's natural visit order). Confirm that
  is deterministic across the index merge in `SourceWalker`.
- **Short class-name binding**: a directive `className:` may be a simplified name when the package is
  imported in `externalReferences` config; resolution against `externalReferences().className()` /
  the FQN-keyed source index is a pre-existing limitation of the directive-arg path, inherited here,
  not solved by this item. Flag only.

## Scope

In scope: the `ofField` resolution arm, the `SourceMethod` rename + name-level projection, the
name-level lookup added to `SourceWalker.Index`, the hover-overlay arm update, the directive-arg path
alignment (pending the open question), and tests. Spans `graphitron-rewrite/graphitron-lsp/` and the
small `SourceWalker.Index` addition in `graphitron-rewrite/graphitron/`.

Out of scope: how `FieldClassification` is computed in the build tier (the data is already produced);
new LSP protocol surface; the legacy modules at the repo root.

## Lineage

Surfaced 2026-06-25 from a user request: after goto-definition reached the directive-argument and
declaration-name cases, the remaining friction was that service-backed / computed field *names* still
required navigating into the directive. Investigation found the jump target already resolved in the
snapshot, a latent arity-0 hardcode in the hover overlay, and an ambiguity non-jump rooted in the
index keying methods by parameter count; the user steered resolution to method-name level, which
removes both defects and the friction together.
