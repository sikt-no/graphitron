---
id: R201
title: "Honor @field(name:) in @error payload construction shape resolution"
status: Backlog
bucket: bug
priority: 5
theme: error-channel
depends-on: []
created: 2026-05-20
last-updated: 2026-07-13
---

# Honor @field(name:) in @error payload construction shape resolution

`FieldBuilder.resolvePayloadConstructionShape` (`FieldBuilder.java:518-574`, with the mutable-bean helper `tryMutableBean` at `:583-627`; called from the PayloadClass arm at `:2970`) picks an `@error` payload class's construction shape, then the emitter (catch-arm `payloadFactoryLambda` in `TypeFetcherGenerator`, forking to `payloadFactoryLambdaCtor` / `payloadFactoryLambdaSetters` per shape) generates either an all-fields-ctor invocation or a no-arg-ctor + per-SDL-field setter sequence against it. (The second consumer this item originally cited, the validator pre-step's `declareEarlyPayloadFromErrors`, was retired by R244: its replacement `ChannelEarlyReturnEmitter` wraps violations in `Outcome.ErrorList` and reads no construction shape.) Neither arm reads `@field`. The mutable-bean arm matches `set<UcFirst(sdlFieldName)>` on `payloadCls.getMethods()` via the Java-bean conversion in `javaBeanSetterName` (`:601-604`) and builds each `SetterBinding` off the raw SDL field name (`:621`); the javadoc contract on `resolvePayloadConstructionShape` (`:495-512`) pins "setter method name matches the SDL field name under Java-bean conversion" as an invariant the emitter relies on. The all-fields-ctor arm (`:526-547`) picks the single ctor whose parameter count equals `sdlFieldNames.size()` and the emitter then assumes positional alignment with SDL declaration order. Either way, a payload class whose Java component or setter names diverge from the SDL field names has no remap, exactly the shape R191 now admits on data fields. The fix is to thread `@field(name:)` on each SDL field into both arms: in the mutable-bean arm, derive the setter base from the directive value when present (`set<UcFirst(directiveName)>`) and persist the SDL-to-Java mapping on `SetterBinding`; in the all-fields-ctor arm, the directive value picks the matching parameter (by name where the ctor exposes parameter names, otherwise by the directive's positional alignment with the record component / canonical-ctor parameter order). Update the javadoc contract at `:495-512` to reflect the new invariant (setter / parameter name matches the directive value when present, the SDL field name otherwise). This is the output-side mirror of R200; together they restore symmetry across input bean / record binding (R200) and output payload bean / record construction (this item). R461 (accessor-resolution unification behind `ClassAccessorResolver.enumerate`, In Review as of 2026-07-13) is adjacent but does not read `@field(name:)`; the remap this item wants happens upstream of the candidate enumeration.
