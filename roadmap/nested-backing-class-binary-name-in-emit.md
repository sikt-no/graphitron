---
id: R688
title: "The emitter spells a nested backing class with its binary $ name, so generated fetchers for a nested result type do not compile"
status: Backlog
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-08-17
last-updated: 2026-08-17
---

# The emitter spells a nested backing class with its binary $ name, so generated fetchers for a nested result type do not compile

A result type whose backing class is a nested Java class (a record or POJO declared inside another class, binary name `Outer$Nested`) generates source that does not compile. The emit sites derive the cast target with `ClassName.bestGuess(fqClassName)`, which splits on `.` only, so the `$` survives into an import statement and a cast:

```java
import no.sikt.graphitron.rewrite.test.services.SharedValueTypeService$Translations;
...
return ((SharedValueTypeService$Translations) source).en();
```

javac rejects both. The failure is a plain build break at the consumer, with no build-time diagnostic pointing at the cause, and the author's only signal is a `cannot find symbol` on generated code they did not write.

Observed while adding the shared-value-type compilation fixture to `graphitron-sakila-example`: a nested `record Translations` inside the service class produced exactly the output above. Flattening the fixture to top-level classes was the workaround; nothing else changed.

The model already has the correct spelling in one place. `RowsMethodShape.fromBinaryName` splits on `$` so a nested class resolves to the JLS-legal `Outer.Nested`, and `DomainReturnType.claimForBacking` routes every backing-class claim through it. The emit seats do not: at least `FetcherEmitter` (the record-read cast target, the composite class, the payload class) and `GeneratorUtils` reach for `bestGuess` on the same `fqClassName` strings. A first cut is to name the correct helper once at a boundary both the model and the emitters read, and route the emit seats through it, rather than fixing each `bestGuess` call in place.

Worth deciding while specifying: whether the fix belongs at the emit seats or one step earlier, at the point a `GraphitronType.ResultType`'s `fqClassName` is stored, so no consumer can reach a binary name at all.
