---
id: R201
title: "Honor @field(name:) in @error payload construction shape resolution"
status: Backlog
bucket: bug
priority: 5
theme: service
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# Honor @field(name:) in @error payload construction shape resolution

`FieldBuilder.resolvePayloadConstructionShape` (`FieldBuilder.java:506-609`) picks an `@error` payload class's construction shape, then the emitter (catch-arm `payloadFactoryLambda` in `TypeFetcherGenerator`, and the validator pre-step's `declareEarlyPayloadFromErrors`) generates either an all-fields-ctor invocation or a no-arg-ctor + per-SDL-field setter sequence against it. Neither arm reads `@field`. The mutable-bean arm at `:589-591` matches `set<UcFirst(sdlFieldName)>` on `payloadCls.getMethods()` via the Java-bean conversion in `javaBeanSetterName`; the load-bearing-classifier-check at `:498-505` pins "setter method name matches the SDL field name under Java-bean conversion" as an emitter-relied-on invariant. The all-fields-ctor arm at `:519-535` picks the single ctor whose parameter count equals `sdlFieldNames.size()` and the emitter then assumes positional alignment with SDL declaration order. Either way, a payload class whose Java component or setter names diverge from the SDL field names has no remap — exactly the shape R191 now admits on data fields. The fix is to thread `@field(name:)` on each SDL field into both arms: in the mutable-bean arm, derive the setter base from the directive value when present (`set<UcFirst(directiveName)>`) and persist the SDL-to-Java mapping on `SetterBinding`; in the all-fields-ctor arm, the directive value picks the matching parameter (by name where the ctor exposes parameter names, otherwise by the directive's positional alignment with the record component / canonical-ctor parameter order). Update the load-bearing-classifier-check text to reflect the new invariant (setter / parameter name matches the directive value when present, the SDL field name otherwise). This is the output-side mirror of R200; together they restore symmetry across input bean / record binding (R200) and output payload bean / record construction (this item).
