---
id: R628
title: "Producer-binding probe grounds a dot-path leaf parameter against the outer input type"
status: Backlog
bucket: bug
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-11
last-updated: 2026-08-11
---

# Producer-binding probe grounds a dot-path leaf parameter against the outer input type

`RecordBindingResolver`'s producer-binding probe resolves each reflected parameter to an SDL argument name and then grounds an input-axis observation against *that argument's* type: `sdlArgName` from the argMapping override, `inputSdl = unwrappedTypeName(arg.getType())`, then `addInputObservation(inputSdl, new ProducerBinding.RootService(paramElement, ...))`. The override value it reads is head-only (the file-private parser drops everything after the first `.`), so for a dot-path entry the argument it grounds against is the *outer* input object while `paramElement` is the Java type of the parameter that receives the *leaf* value. `argMapping: "req: input.request"` therefore records the outer `Input` SDL type as backed by `req`'s Java class, which is a different type than the one that parameter actually carries. `shouldBind` filters primitives, `String`, `Number`, `Boolean`, `Character`, enums, arrays and `java.*`, so the mis-grounding only fires when the leaf parameter is a consumer-authored class, which is precisely the dot-path-into-input-object shape that the argMapping seam unification widens. Fixing it means deciding what the right grounding is for a path-bound parameter (most likely the leaf field's input type rather than the head argument's, which needs the resolved path rather than the head string), so it is a design question about producer grounding rather than a rename. Surfaced by two independent Spec reviews of the argMapping seam unification, which deliberately left the probe's behaviour unchanged apart from deleting its private parser.
