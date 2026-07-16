---
id: R201
title: "Honor @field(name:) in @error payload construction shape resolution"
status: In Review
bucket: bug
priority: 5
theme: error-channel
depends-on: []
created: 2026-05-20
last-updated: 2026-07-16
---

# Honor @field(name:) in @error payload construction shape resolution

## Problem

`FieldBuilder.resolvePayloadConstructionShape` (`FieldBuilder.java:521-577`, with the mutable-bean helper `tryMutableBean` at `:586-630`; called from the PayloadClass arm at `:2998`) picks an `@error` payload class's construction shape, then the emitter (catch-arm `payloadFactoryLambda` in `TypeFetcherGenerator`, forking to `payloadFactoryLambdaCtor` / `payloadFactoryLambdaSetters` per shape) generates either an all-fields-ctor invocation or a no-arg-ctor + per-SDL-field setter sequence against it. (The second consumer this item originally cited, the validator pre-step's `declareEarlyPayloadFromErrors`, was retired by R244: its replacement `ChannelEarlyReturnEmitter` wraps violations in `Outcome.ErrorList` and reads no construction shape.) Neither arm reads `@field`. The mutable-bean arm matches `set<UcFirst(sdlFieldName)>` on `payloadCls.getMethods()` via the Java-bean conversion in `javaBeanSetterName` (`:604`, defined `:633`) and builds each `SetterBinding` off the raw SDL field name (`:624`); the javadoc contract on `resolvePayloadConstructionShape` (immediately above `:521`) pins "setter method name matches the SDL field name under Java-bean conversion" as an invariant the emitter relies on. The all-fields-ctor arm (`:529-550`) picks the single ctor whose parameter count equals `sdlFieldNames.size()` and the emitter then assumes positional alignment with SDL declaration order. Either way, a payload class whose Java component or setter names diverge from the SDL field names has no remap, exactly the shape R191 now admits on data fields.

The read side of class-backed payload data fields already honors the directive (the scalar/enum arm at `FieldBuilder.java:5781-5784` remaps the accessor base before `resolveRecordAccessor`), so construction is the only remaining blind spot on the payload surface. R191 (output accessors on free-form `@record` parents), R200 (input bean/record member binding), and R202 (`@error` extra-field source accessors, Done 2026-07-16) have all shipped; this item is the last leg of the `@field` symmetry set, and R202's changelog explicitly flags the read/construct asymmetry as open until it lands.

## Contract

The `@field(name:)` value on a carrier payload type's SDL field names the Java member backing that field, for construction as well as for the already-honored read side. Concretely:

1. **Mutable-bean arm.** The setter base is the directive value when present, the SDL field name otherwise (`set<UcFirst(base)>` under the existing `javaBeanSetterName` conversion). Because the bean predicate requires a setter to *exist* for every SDL field, a data-field directive participates in the shape's existence check: a payload whose setter matches the SDL name while the directive names something else now rejects. That is intentional parity with the read side (the getter is remapped to the directive value, so the setter must match), and it is the one behavior change on previously-admitted carriers; it is pinned by a dedicated pipeline case (test 2 below).
2. **All-fields-ctor arm.** Ctor selection by parameter arity is name-independent and unchanged. The directive is load-bearing only at the errors-slot location. Presence is tested by `hasAppliedDirective(DIR_FIELD)` on the errors-shaped field, matching the read-side idiom (`FieldBuilder.java:5781`), *not* by comparing the resolved base to the SDL name: a `@field(name:)` whose value coincides with the SDL field name still counts as present and takes the present-branch below (name-match when names are available, reject on a name-less POJO). Collapsing presence into the resolved base would silently drop exactly such a directive off the very slot this item exists to honor.
   - No directive on the errors-shaped field: the SDL declaration index rule stands (status quo).
   - Directive present and parameter names are available (record components via `getRecordComponents()`, always present for records; or `Parameter.isNamePresent()` for POJOs compiled with `-parameters`): the errors slot is the parameter whose name matches the directive value. An unresolvable name rejects, naming the directive value and the candidate parameter names; a typo must not silently fall back to position.
   - Directive on the errors-shaped field but no name info (non-record POJO without `-parameters`): reject with guidance (compile with `-parameters`, convert to a record, or use the mutable-bean shape). Rationale: this is the one place where falling back to position would silently ignore a directive the author put on the very slot being resolved, the failure mode this item exists to remove. Payloads whose *data* fields carry directives while the errors field does not stay on the positional path with no reject; data-field directives are inert on this arm (non-errors slots are default-filled by parameter index/type regardless of name).
3. **Blank value.** A present-but-blank `@field(name: "")` on any payload field rejects the channel (R200/R202 precedent).
4. **Diagnostics.** Reject strings gain a `(remapped to '<base>' by @field)` parenthetical when a directive was in play (R202 precedent), so a failed override is diagnosable as an override.

## Design

1. **Gathering stays builder-internal.** In `resolveErrorChannel` (`FieldBuilder.java:2977-2980`), replace the `sdlFieldNames` name list with a per-field carrier, a FieldBuilder-internal `record PayloadSdlField(String sdlFieldName, String javaBaseName, boolean fieldDirectivePresent)` where `javaBaseName` is the house idiom `argString(f, DIR_FIELD, ARG_NAME).orElse(f.getName())` and `fieldDirectivePresent` is `f.hasAppliedDirective(DIR_FIELD)`. The third component is load-bearing and cannot be reconstructed from the first two: `javaBaseName == sdlFieldName` is ambiguous between "no directive" and "a directive whose value coincides with the SDL field name," and contract rule 2 (ctor-arm positional-vs-name-match fork; name-less-POJO reject) and rule 4 (diagnostics parenthetical) both key on *presence*, not on whether the resolved base diverged, exactly as the read side does via `hasAppliedDirective` at `:5781`. The bean arm (rule 1) reads only `javaBaseName`. `resolvePayloadConstructionShape`'s signature changes from `List<String>` to `List<PayloadSdlField>` (`null` still admitted for the skip-setter-predicate case). This is deliberately *not* the R202 carry-on-the-model pattern: the sole consumer is this one classify-time site, and what lands on the model is the resolved artifact (the setter `Method` on `SetterBinding`, the int on `ErrorsSlot.CtorParameterIndex`), never the carrier itself. Architect-reviewed: promoting it to a model type would leak gathering scaffolding into the model.
2. **Bean arm.** `tryMutableBean` (`:558`) derives the setter name from `javaBeanSetterName(javaBaseName)`. `SetterBinding.sdlFieldName` stays the SDL name (wire identity; diagnostics quote the SDL name, with the parenthetical when remapped).
3. **Ctor arm, single-sourced index.** `buildErrorChannelCtorArm` (`:3002`) takes the errors field's `PayloadSdlField` (adding its `javaBaseName` + `fieldDirectivePresent` alongside the existing `errorsFieldIndex`) and computes one `resolvedErrorsCtorIndex` (name-matched under contract rule 2 when `fieldDirectivePresent` and parameter names are available, `errorsFieldIndex` otherwise) and feeds that single value to *both* `collectDefaultedSlots(...)` and `ErrorsSlot.CtorParameterIndex(...)`. This is load-bearing: today both consumers receive `errorsFieldIndex` and agree only because SDL index equals ctor index by convention; if only `ErrorsSlot` switched to the resolved index, the emitter (`TypeFetcherGenerator.payloadFactoryLambdaCtor`) would place `errors` at the resolved slot while `defaultsByIndex` default-fills that same slot and leaves the SDL-index slot unfilled, a mis-constructed payload that still compiles. The Iterable structural check runs against the resolved index.
4. **Emit untouched.** Both emitter arms are selection-agnostic (they invoke `Method.getName()` / parameter indices off the carried model, `TypeFetcherGenerator.java:6836-6880`), matching the R191 precedent; the fix is resolution-side only.
5. **Javadoc.** Update the contract on `resolvePayloadConstructionShape` and the `PayloadConstructionShape` model javadoc to the new invariant: setter/parameter name matches the directive value when present, the SDL field name otherwise; for construction the directive is load-bearing only at the errors-slot location on the ctor arm, while on the bean arm it additionally participates in the shape's existence check. Delete the dead `sdlFieldNames(String)` helper (`FieldBuilder.java:3084`, no callers).

## Tests

Tiering per `development-principles.adoc`: reflection combinatorics at unit tier, classification-model assertions at pipeline tier, one live round-trip at execution tier; no code-string assertions on generated bodies.

- **Unit** (`PayloadConstructionShapeTest`): signature migration; remapped setter resolves (divergent setter names plus directive); remapped-but-still-missing setter rejects naming the SDL field, the directive value, and the parenthetical; camelCase conversion of a directive value.
- **Pipeline** (`ErrorChannelClassificationTest`):
  1. Bean-arm remap admit: payload class with divergently-named setters plus directives classifies, `ErrorsSlot.SetterMethod` bound to the remapped errors setter.
  2. Bean-arm data-field directive participates in existence: a payload with an SDL-name setter but a directive naming a different base rejects (pins contract rule 1's behavior change as an invariant, not prose).
  3. Ctor-arm remap admit: record with components reordered relative to SDL declaration plus `@field` on the errors field classifies, `ErrorsSlot.CtorParameterIndex` equals the name-resolved index and the defaulted slots are computed against it.
  4. Rejects: unresolvable directive value on the ctor arm; directive on the errors field of a name-less POJO (fixture uses `@field(name:)` whose value *coincides with the SDL field name*, so a presence-by-value-divergence shortcut would wrongly admit it and this case pins presence-tracking, not value-divergence); blank `@field(name: "")`.
  5. Regression floor: divergent names without a directive still reject exactly as today.
- **Execution** (sakila): a `@field`-renamed setter-shape payload through the live error path (new fixture mirroring `SetterShapeFilmReviewPayload` in `graphitron-sakila-service`), asserting the error surfaces on the payload's errors field and the data fields arrive defaulted.

## Docs

- `docs/manual/reference/directives/field.adoc`: extend the axis-by-site sentence (line 5) with the payload-construction axis (setter / ctor parameter on carrier payload types).
- `docs/manual/how-to/result-types.adoc`: the payload-shape prose gains the remap rule for both shapes.
- This pays off R202's deliberately read-side-scoped prose, which avoided advertising half-implemented behavior.

## Out of scope

- Ctor-parameter order divergence without a directive: the positional rule and its Iterable structural check stand unchanged.
- The read side (R191 accessor matching, R202 `@error` extra-field accessors): Done, untouched.
- R242 (DML payload positional alignment): orthogonal; it concerns data-list ordering on DML carriers, not error-channel payload construction.
- Renaming or restructuring `@field(name:)` itself.

## Cross-references

This is the output-side mirror of R200; together with R191 and R202 (all Done) it closes the `@field` symmetry set across input binding, output reads, and output construction. Since R244 retired the validator pre-step consumer (see the problem statement), `resolveErrorChannel` is the single live call site. R461 (accessor-resolution unification behind `ClassAccessorResolver.enumerate`, Done) is adjacent but does not read `@field(name:)`; the remap here happens upstream of candidate enumeration.
