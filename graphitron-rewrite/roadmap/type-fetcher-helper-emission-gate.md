---
id: R80
title: "Replace string-scan helper-emission gate in `TypeFetcherGenerator`"
status: Backlog
bucket: cleanup
depends-on: []
---

# Replace string-scan helper-emission gate in `TypeFetcherGenerator`

`TypeFetcherGenerator.generateTypeSpec` decides whether to emit the
`graphitronContext` helper by serialising every just-emitted method's
`CodeBlock` to a `String` and substring-greping it for `graphitronContext(env)`.
This was introduced as a fix for a regression where a `*Fetchers` class
containing only `ServiceRecordField` fetchers called the helper without
emitting it (the previous predicate enumerated `SqlGeneratingField` plus a
handful of interface/union/DML variants and silently dropped
`ServiceRecordField`, which is the only `BatchKeyField` that does not extend
`SqlGeneratingField`). The string-scan fixes the symptom but is generation-
thinking inverted: the generator now reads its own emitted source to gate a
sibling emission, so any future emitter that writes `graphitronContext(dfe)`
or splits the call across `CodeBlock` arguments would silently regress with
no compile signal, and the production code is itself a code-string assertion
on a generated method body, the shape the test-tier rules ban. The companion
unit test (`graphitronContextHelper_emittedForServiceRecordOnlyClass`) carries
the same string assertion. Replace the post-scan with an emission-time
mechanism: either (a) a small per-class emission context that each emitter
calls when it writes a `graphitronContext(env)` lookup, with the class
assembly draining the requested-helpers set; or (b) lift "fetcher emits a
`graphitronContext` call" into a capability interface alongside
`SqlGeneratingField` / `MethodBackedField` / `BatchKeyField` so the gate is
one `instanceof` over a real classification. Either shape removes the
string coupling from production code and the matching code-string assertion
from the test, and naturally extends to any future helper the class assembly
needs to gate. While here, add a pipeline fixture covering a service-record-
only type so the compile tier (`graphitron-sakila-example`) catches the
original class of bug without a unit-tier guard.
