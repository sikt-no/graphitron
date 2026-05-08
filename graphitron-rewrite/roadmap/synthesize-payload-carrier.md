---
id: R75
title: "Synthesize payload carrier for canonical data+errors shapes"
status: Spec
bucket: architecture
priority: 7
theme: mutations-errors
depends-on: [error-handling-parity]
---

# Synthesize payload carrier for canonical data+errors shapes

R12 establishes a positional canonical-constructor contract on `@record` payload classes: SDL field index *i* binds to ctor parameter *i*, with the errors-shaped field producing the slot graphitron writes into from the catch arm (`FieldBuilder.findCanonicalCtor`, `FieldBuilder.resolveErrorChannel`). The contract is load-bearing for the error channel, but its current shape leaks framework concerns into the consumer's source tree: the consumer has to author a Java class whose fields include `errors`, even though `errors` is never theirs to populate. The transport shape and the domain shape are entangled in their code.

Legacy graphitron didn't have this entanglement. `TypeDTOGenerator` (`graphitron-codegen-parent/.../dto/DTOGenerator.java`) generated payload POJOs from the SDL by default, with `@record(className: ...)` reserved as the opt-out for consumers who needed to author a custom carrier. graphitron-rewrite has accidentally inverted the default: `@record(className)` is now effectively mandatory and synthesis is unavailable. Restoring synthesis as the default is parity-restoration as much as ergonomic improvement.

This item picks up that thread, but with a stronger architectural shape than legacy had. The consumer's service should never write or even mention the payload class. Their domain layer returns a domain object; graphitron synthesizes the transport carrier and **flattens the domain object's fields onto the carrier's non-errors slots**. The errors slot is filled by graphitron's catch-arm machinery, exactly as R12 already specifies.

## Concrete friction

The smallest reproducible case is a passthrough `@service` mutation whose payload SDL is canonical "data + errors":

```graphql
type LagreKvotesporsmalSvarPayload
    @record(record: { className: "no.sikt.fs.opptak.service.LagreKvotesporsmalSvarPayload" }) {
    svar: [KvoteSporsmalSvar!]!
    errors: [LagreKvotesporsmalSvarError]
}
```

Today the consumer must author `LagreKvotesporsmalSvarPayload` with an all-fields canonical constructor `(List<KvoteSporsmalSvar>, List<LagreKvotesporsmalSvarError>)` so `findCanonicalCtor` can locate the errors slot. If they write the obvious shape (a no-arg constructor plus a `(List)` data-only constructor, leaving errors-population to graphitron), the field is rejected with "payload class … has 2 declared constructors but none has parameter count 2 matching the SDL field count". The rejection is mechanically correct given R12's contract, but it's the wrong *shape* of constraint to place on a consumer: their service's domain output has nothing to do with `errors`, yet they're forced to author a class that mentions both.

## What legacy does today

Two reference points worth carrying forward:

- `TypeDTOGenerator` synthesizes payload POJOs from the SDL by default. The consumer authors nothing for the canonical "data + errors" case; `@record(className: ...)` is the escape hatch for custom carriers.
- Errors are injected via setter (`payload.setErrors(errors)`) by `ExceptionStrategyConfiguration.PayloadCreator`. The all-fields constructor is generated but is not load-bearing for error injection.

We keep R12's canonical-constructor contract instead of going back to setter injection. Immutability and Java records are non-negotiable, and mutating an object returned by consumer code is a category of API graphitron should not expose. So this item adopts legacy's "synthesize by default" stance but binds the synthesized carrier through R12's constructor contract.

## What R12 already provides

Most of the carrier-side machinery this item once proposed has shipped under R12.
Code-verified, in `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/`:

- **`model/ErrorChannel.java`** — typed record carrying `payloadClass`, `errorsSlotIndex`,
  `defaultedSlots` (each slot pre-resolved to a `defaultLiteral`), and `mappingsConstantName`.
  The errors slot is identified by SDL field declaration index, exactly as the synthesis
  trigger here would want.
- **`model/ResultAssembly.java`** — the success-arm counterpart for service-backed fetchers.
  Holds `payloadClass`, `resultSlotIndex`, `resultSlotType`, `defaultedSlots`. Populated by
  `FieldBuilder.resolveServiceResultAssembly`, which walks the developer-supplied payload
  class's canonical constructor looking for a parameter whose `TypeName` matches the
  service method's reflected return type and binds that one parameter as the result slot.
- **`FieldBuilder.resolveErrorChannel`** — full canonical-ctor reflection: identifies the
  errors slot by SDL index, captures every other slot's language default literal, resolves
  `mappingsConstantName` (with classifier-side cross-field hash-suffix dedup in
  `MappingsConstantNameDedup`).
- **Catch-arm dispatch** — `buildServiceFetcherCommon` emits a `payloadFactory` lambda that
  walks the captured slots positionally: errors slot ← lambda parameter, every other slot
  ← its `defaultLiteral`. Live for service-backed fetchers; DML side has its own
  `PayloadAssembly`/`buildMutationDeleteFetcher` pair with the same shape.
- **`@record` already optional at SDL** — `TypeBuilder.buildResultType:528-562` returns
  `PojoResultType(name, location, null)` when either `@record` is absent or its `className`
  is missing. The downstream resolvers short-circuit on `fqClassName == null`, so a
  null-className object is currently a no-op carrier; the carrier infrastructure itself
  doesn't need a code change to admit synthesized classes — it needs the synthesized class
  on the classpath and `fqClassName` populated.

**Bare-value shorthand falls out for free.** When the synthesized payload has a single
non-errors slot and the service returns that slot's type, R12's existing
`resolveServiceResultAssembly` walks all canonical-ctor parameters looking for a `TypeName`
match and binds the single matching parameter. No new resolver shape is needed for that case.

## Phasing

R75 ships in three phases ordered by user-visible value. Each phase is a discrete
implementation track; downstream phases bind structurally to upstream ones but are not
schedule-coupled.

- **Phase 1: bulk DML payload synthesis (no error channel).** Closes the legacy-parity
  gap on `@mutation` returning a plain Object payload with one or more data slots and
  no `errors:` field. Specified in implementation-ready detail below.
- **Phase 2: error-channel integration on synthesized payloads (sketch).** Lights up
  when the SDL payload adds an `errors:` field; reuses R12's `ErrorChannel` against
  the synthesized class. Sketch below; needs a Spec revision before implementation.
- **Phase 3: service-side multi-slot flattening (sketch).** Extends the carrier
  classifier so a `@service` method can return a domain record whose components map by
  name to multiple non-errors payload slots. Sketch below; needs a Spec revision
  before implementation.

This Spec body specifies Phase 1. Promoting R75 from Spec → Ready means signing off
on Phase 1 only; Phases 2 and 3 each return to Spec for sharpening before Ready.

## Phase 1: bulk DML payload synthesis (no error channel)

### Reproduction case

```graphql
input OpprettKvotesporsmalPreutfyllingInput @table(name: "kvotesporsmal_preutfylling") {
    kvotesporsmalPreutfyllingKode: String! @field(name: "KVOTESPORSMAL_PREUTFYLLING_KODE")
}

type KvotesporsmalPreutfyllingPayload {
    kvotesporsmalPreutfylling: [KvotesporsmalPreutfylling!]
}

type Mutation {
    opprettKvotesporsmalPreutfylling(
        input: OpprettKvotesporsmalPreutfyllingInput!
    ): KvotesporsmalPreutfyllingPayload @mutation(typeName: INSERT)
}
```

Today this fails at `MutationInputResolver.validateReturnType:174-177` with *"return
type 'KvotesporsmalPreutfyllingPayload' is not yet supported; use ID or a @table
type"*. The plain Object type lands as `ReturnTypeRef.ScalarReturnType`, and the DML
emitter has no path for "synthesize and bind a list-of-rows payload". Legacy graphitron
handles this via `TypeDTOGenerator` synthesizing the payload POJO and wiring
`INSERT … RETURNING` to a `Result<TableRecord>` that fills the data slot.

### Trigger

Synthesis fires for an SDL Object type that satisfies *all* of:

1. Has no `@record` directive, *or* has `@record` without a `record.className` argument.
2. Has no `@table` directive.
3. Is not an interface or union.
4. Is the return type of at least one `@mutation(typeName: …)` field.
5. Declares no `errors:`-shaped field. (Phase 2 territory; until Phase 2 ships,
   payloads with `errors:` fall through to today's rejection.)
6. Every declared field's return type satisfies the **synthesizable slot rule** below.

### Synthesizable slot rule

A field on a candidate payload is synthesizable when its return type is one of:

- A `@table`-mapped output type, in which case the synthesized slot is typed
  `<TableRecordClass>` (single) or `List<TableRecordClass>` (list, with the SDL list
  cardinality preserved).
- A `@record(record: {className: ...})`-mapped output type, in which case the slot is
  typed as the configured Java class.
- A built-in scalar or an SDL enum, in which case the slot is typed by the existing
  scalar-to-Java mapping.

Off-shape fields (interface/union returns, fields with arguments, `@nodeId`-decoded
fields) reject the payload as non-synthesizable; the carrier surfaces as
`UnclassifiedField` with a reason naming the offending field.

### Synthesis output

For a triggering payload type `Foo`, emit a Java record at
`<outputPackage>.synthesized.Foo`:

```java
package <outputPackage>.synthesized;

public record Foo(<slot1Type> <slot1Name>, <slot2Type> <slot2Name>, ...) {}
```

Slot order matches SDL field declaration order. Slot names match SDL field names
(no SCREAMING_SNAKE conversion). The synthesized record has exactly one canonical
constructor, so `findCanonicalCtor` matches trivially.

Cross-schema collision handling: when two schemas produce a `Foo` payload, the second
gets a deterministic 8-hex SHA-256-derived suffix (mirrors `MappingsConstantNameDedup`).

### Classifier reroute

A new synthesis pass runs in `GraphitronSchemaBuilder.buildSchema` after `TypeBuilder`
processes the type set but before `FieldBuilder` classifies fields:

- Walk every SDL Object type. For each one matching the trigger, register it in a
  side-table (`Map<String, ClassName>` of SDL type name → synthesized FQN).
- Emit the synthesized record source files via a new `SynthesizedPayloadClassGenerator`
  in the same generation pass that emits `ErrorMappings` and `ErrorRouter`.

`TypeBuilder.buildResultType:528-562` consults the side-table when the SDL type has
no `@record(className)`: when a synthesized FQN is registered, return
`PojoResultType(name, location, synthesizedFqn)` instead of the current
`PojoResultType(name, location, null)`. Downstream resolvers
(`resolveDmlPayloadAssembly`, `resolveErrorChannel`, `resolveServiceResultAssembly`)
already handle non-null `fqClassName` and pick up the synthesized class without
further changes.

The synthesis pass wears
`@LoadBearingClassifierCheck(key = "synthesized-payload.class-emitted")` on the
registration site; `resolveDmlPayloadAssembly`'s no-fqClassName short-circuit wears
the matching `@DependsOnClassifierCheck`.

### `PayloadAssembly` extension for list-cardinality row slots

`PayloadAssembly` today is a flat record `(payloadClass, rowSlotIndex, rowSlotType,
defaultedSlots)` where `rowSlotType` is the bare jOOQ table record `TypeName`.
`resolveDmlPayloadAssembly:1771-1783` walks ctor parameters comparing against
`tableRecordClass` via `Class.equals`.

Lift to a sealed split keyed on row-slot cardinality:

```java
public sealed interface PayloadAssembly permits PayloadAssembly.SingleRow,
    PayloadAssembly.RowList {
    ClassName payloadClass();
    int rowSlotIndex();
    List<DefaultedSlot> defaultedSlots();

    record SingleRow(ClassName payloadClass, int rowSlotIndex,
        TypeName rowSlotType, List<DefaultedSlot> defaultedSlots)
        implements PayloadAssembly {}

    record RowList(ClassName payloadClass, int rowSlotIndex,
        TypeName rowElementType, List<DefaultedSlot> defaultedSlots)
        implements PayloadAssembly {}
}
```

The resolver walks ctor parameters looking for *either* a parameter typed as
`tableRecordClass` (→ `SingleRow`) *or* a parameter typed `List<tableRecordClass>`
(→ `RowList`). At most one match: a payload with both shapes rejects with a
descriptive reason.

### DML emitter changes

The four DML kinds (`MutationInsertTableField`, `MutationUpdateTableField`,
`MutationUpsertTableField`, `MutationDeleteTableField`) all gain a switch on the
`PayloadAssembly` arm:

- `SingleRow`: existing path, `dsl.<dml>(...).where(...).returning().fetchOne()` →
  bind to typed local `__row` → construct payload positionally.
- `RowList`: emit `dsl.<dml>(...).where(...).returning().fetch()` → bind to typed
  local `List<TableRecord> __rows` → construct payload with `__rows` at
  `rowSlotIndex` and `defaultLiteral` elsewhere.

The async wrapper, `DataFetcherResult` wrapping, and catch-arm dispatch (no channel
in Phase 1, so always `ErrorRouter.redact`) are unchanged from today's `SingleRow`
path.

### Tests

**Pipeline-tier** (`graphitron/src/test/java/no/sikt/graphitron/rewrite/`),
new test class `SynthesizedPayloadPipelineTest`:

- `payload_withSingleTableField_classifiesAsResultReturnType`: trigger fires;
  classifier produces `ResultReturnType(_, _, synthesizedFqn)` instead of
  `ScalarReturnType`.
- `payload_withListOfTableField_synthesizedSlotIsListOfRecord`: synthesis emits a
  record with `List<TableRecord>` slot, and `PayloadAssembly` resolves to `RowList`.
- `payload_withErrorsField_doesNotSynthesize`: payload with `errors:` field falls
  through to today's rejection (Phase 2 territory).
- `payload_withInterfaceField_doesNotSynthesize`: off-shape field rejects.
- `payload_notReachableFromMutation_doesNotSynthesize`: synthesis is conservative,
  scoped to mutation returns.
- `payload_withName_collidingAcrossSchemas_addsHashSuffix`: dedup behavior.
- `dmlEmitter_withRowListSlot_emitsFetch`: emitter path for list-cardinality
  pinned via `methodSpec.toString()` parity comparison against the `SingleRow` path
  (modulo the `fetchOne` → `fetch` and slot-binding deltas).

**Execution-tier** (`graphitron/src/test/java/no/sikt/graphitron/rewrite/sakila/`),
new sakila fixture `SynthesizedPayloadInsertTest`:

- SDL: `type ActorInsertPayload { actor: [Actor!] }` (Actor is the existing sakila
  `@table` Actor type).
- Mutation: `insertActor(input: ActorInsertInput!): ActorInsertPayload @mutation(...)`.
- Driver: invoke the mutation against the testcontainer; assert response shape;
  assert the inserted row is visible in the payload's `actor` list and queryable on
  follow-up reads.

**Audit-tier**: the `@LoadBearingClassifierCheck` ↔ `@DependsOnClassifierCheck`
pair from the classifier-reroute section is picked up automatically by
`LoadBearingGuaranteeAuditTest`; no new audit test needed.

### Open questions for Phase 1

1. **Synthesized-payload package location.** Proposal: `<outputPackage>.synthesized`.
   Alternatives: `<outputPackage>.payloads`, or under the existing
   `<outputPackage>.schema` package. The synthesized classes are graphitron-generated,
   so a dedicated subpackage keeps them separable from consumer code. Lean toward
   `synthesized`; spec phase confirms.
2. **DML emitter coverage scope.** INSERT is the user's blocker; UPDATE and DELETE
   are obvious siblings. UPSERT mechanics differ; needs implementer verification that
   the same `RowList` path applies. Spec phase confirms whether UPSERT is in or out
   for Phase 1.
3. **Single-row data slot (`X` instead of `[X!]`).** The user's case is list-cardinality
   (`[KvotesporsmalPreutfylling!]`); a single INSERT returning a singleton list is
   tolerable. Should synthesis also admit a single-cardinality data slot (mapped to
   `SingleRow` instead of `RowList`)? Restricting Phase 1 to list cardinality is
   simpler; single-cardinality data slots fall through to today's rejection until a
   later phase. Spec phase picks.
4. **`@record`-typed data slots.** What if the SDL data slot is typed as a `@record`
   type instead of `@table`? The Java slot type becomes `List<DomainClass>` rather
   than `List<TableRecord>`. The DML emitter would need a "lift jOOQ row to the
   domain class" step. Probably out of scope for Phase 1; restrict to `@table`-typed
   data slots and let `@record` slots fall through to a "not yet supported" reason.
5. **Co-existence with authored carriers.** `@record(record: {className: ...})`
   payloads keep working unchanged; absent `className` triggers synthesis. Confirm no
   path produces both a synthesized and an authored class for the same SDL type.

## Phase 2: error-channel integration on synthesized payloads (sketch)

When the SDL payload adds an `errors:` field, Phase 2 wires the synthesized class
through R12's existing `ErrorChannel` machinery:

- Synthesis includes the errors slot in the generated record (typed `List<Object>`
  per R12's source-direct convention).
- The Phase 1 trigger condition #5 ("declares no `errors:`-shaped field") flips:
  Phase 2 fires synthesis precisely *because* an `errors:` field is present and the
  field's classifier admits the channel.
- `resolveErrorChannel` runs against the synthesized class as if it were authored;
  no resolver-side change.
- Catch-arm `payloadFactory` walks the synthesized ctor exactly as for authored
  classes.

Spec questions deferred to a Phase 2 revision pass:

- Errors-slot type binding: keep R12's `List<Object>` source-direct, or revisit?
- Interaction with `MappingsConstantNameDedup` when synthesized payloads share their
  handler list with authored ones.
- Whether Phase 2 implies any change to `@error` types' SDL surface, or stays purely
  on the carrier side.

## Phase 3: service-side multi-slot flattening (sketch)

For `@service` mutations whose payload has multiple non-errors slots, allow the
service to return a domain record whose components map by name to the payload's
slots. R12's `resolveServiceResultAssembly:1838-1923` is *single-slot* today: it
walks ctor parameters for a `TypeName` match against the service method's return
type and binds one parameter.

Architectural options:

- Sealed split on `ResultAssembly` (`SingleSlot | Flattened`); the flattened arm
  carries a `Map<String, Integer> componentToSlot` mapping. Both arms reuse
  `defaultedSlots` and the catch-arm payload-factory walk.
- Sibling resolution `FlattenedResultAssembly` carried as a separate `Optional<>`
  slot on the field variants.

Lean toward the sealed split: `WithErrorChannel`-style consumers stay symmetric and
the existing catch-arm `payloadFactory` walk extends naturally.

Example (assuming Phase 3 lands; service collapses to a domain record return):

```java
public record LagreOgBeregnResultat(List<KvoteSporsmalSvar> svar) {}

public LagreOgBeregnResultat lagreOgBeregnKvoteplassering(
        List<KvotesporsmalSvarInput> svarListe) { ... }
```

Spec questions deferred to a Phase 3 revision pass:

- Component matching rules (by name and assignable type? Java-record components are
  positional; how do they bind by name?).
- Wrapper-record naming conventions and consumer ergonomics.
- Whether single-slot flattening collapses to R12's existing `SingleSlot` shape or
  stays in the new `Flattened` arm.
- Bare-value shorthand (single-non-errors-field payloads): R12's existing single-slot
  walk already covers this for free; document and confirm.

## Non-goals

- **Setter-based errors injection.** Rejected explicitly: violates immutability, blocks Java records, and would have graphitron mutate objects produced by consumer code. Legacy's `payload.setErrors(...)` shape is not coming back, even though it would have made the friction case "just work".
- **Authored payloads with custom fields.** Anything beyond the synthesizable shape stays in the consumer's source tree. The escape hatch must remain trivial, not an anti-pattern.
- **Flattening at list cardinality.** Returning `List<DomainRecord>` to flatten onto `List<Payload>` is not supported; list cardinality keeps its R12 §2c rejection (the catch arm has nowhere to put per-element errors).
- **Synthesizing input types.** This item is about output payloads; `@record` on inputs has different ergonomics (deserialization, partial updates) and a different cost/benefit story.
- **Removing `@record` entirely from payload types.** The directive still has a job (declaring that the SDL type is record-shaped, distinguishing it from `@table`). Only `className` becomes optional.

## Relationship to R12

R12 has shipped the carrier-side foundation R75 binds to: `ErrorChannel`,
`ResultAssembly`, `PayloadAssembly`, canonical-ctor reflection, errors-slot-by-SDL-index,
default-literal capture, catch-arm `payloadFactory` dispatch, and
`MappingsConstantNameDedup`. R75 stays additive across all phases:

- **Phase 1** lifts `PayloadAssembly` to a sealed split (`SingleRow | RowList`) and
  adds the synthesis pass that populates `ResultReturnType.fqClassName()`. R12's
  existing single-row resolver and emitter become the `SingleRow` arm unchanged.
- **Phase 2** uses R12's `ErrorChannel` resolver verbatim against the synthesized
  class.
- **Phase 3** lifts `ResultAssembly` to a sealed split (`SingleSlot | Flattened`).
  R12's existing single-slot match becomes the `SingleSlot` arm unchanged; the
  bare-value shorthand falls out for free.

The remaining hard dependency on R12 is structural rather than schedule-bound. R12's
open Remaining-work bullets (rule-6 relaxation, accessor reflection check,
`extensions.constraint`, validator-integration fixture) are all `@error`-side, not
payload-side; none of them gate any R75 phase. Phase 1 has no error-channel dependency
and ships independently.

A forward-reference from R12's §2c to R75 (acknowledging that the authoring
requirement is the planned ergonomic exit) is worth landing on R12 directly. Phase 2
of R75 narrows that pointer to the synthesized-payload-with-errors-channel case.

## Success criteria

**Phase 1 (this Spec body):**

- The reproduction case at the top of Phase 1 (the consumer's
  `KvotesporsmalPreutfyllingPayload` + `OpprettKvotesporsmalPreutfyllingInput`
  fixture, simplified to sakila tables for the execute-tier coverage) compiles and
  serves correctly through `mvn -f graphitron-rewrite/pom.xml install -Plocal-db`,
  with no `@record(className)` declaration on the SDL payload type.
- The synthesized class is emitted at `<outputPackage>.synthesized.<TypeName>` (or
  the location settled in open question 1).
- All `SynthesizedPayloadPipelineTest` cases pass; the
  `SynthesizedPayloadInsertTest` execute-tier driver passes against the sakila
  testcontainer.
- Authored payloads with `@record(record: {className: ...})` are unaffected: same
  generation, same rejection messages, same fixture coverage as today (regression
  pin via the existing R12 fixtures).
- The legacy-parity claim is testable: a SDL fixture taken verbatim from a legacy
  graphitron consumer (no `@record(className)` on the payload) compiles cleanly
  on the rewrite.

**Phases 2 and 3:** success criteria sharpened in their respective Spec revision
passes.
