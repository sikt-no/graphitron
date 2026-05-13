---
id: R155
title: "InputBeanResolver: primitive Java types break codegen"
status: Spec
bucket: bug
priority: 1
theme: service
depends-on: []
created: 2026-05-13
last-updated: 2026-05-13
---

# InputBeanResolver: primitive Java types break codegen

## Problem

`graphitron:dev` blows up on production schemas whose consumer-authored input beans have a Java-primitive field. A real-world stack trace from a production schema:

```
java.lang.IllegalArgumentException: couldn't make a guess for int
    at no.sikt.graphitron.javapoet.Util.checkArgument (Util.java:58)
    at no.sikt.graphitron.javapoet.ClassName.bestGuess (ClassName.java:222)
    at no.sikt.graphitron.rewrite.generators.InputBeanInstantiationEmitter.fieldLocalType (InputBeanInstantiationEmitter.java:120)
    ...
    at no.sikt.graphitron.rewrite.GraphQLRewriteGenerator.generate (GraphQLRewriteGenerator.java:80)
    at no.sikt.graphitron.rewrite.maven.DevMojo.runGeneratorPass (DevMojo.java:275)
```

The break is total: codegen fails on initial run, so the dev loop can't start. Sakila has no input bean with a primitive-typed field, which is why the existing pipeline tests miss it.

## Root cause

`InputBeanResolver.peelJavaListSet` (`InputBeanResolver.java:445`) takes a `java.lang.reflect.Type.getTypeName()` string and returns it as the element type name when the type is not `List<...>` / `Set<...>`. For a primitive record component or setter parameter, `Type.getTypeName()` returns the unboxed primitive literal: `"int"`, `"long"`, `"boolean"`, `"double"`, `"float"`, `"short"`, `"byte"`, `"char"`. That string flows through unchanged into `FieldBinding.javaElementTypeName`, and then:

- `InputBeanInstantiationEmitter.fieldLocalType` (`InputBeanInstantiationEmitter.java:120`) calls `ClassName.bestGuess(fb.javaElementTypeName())` — fails with `IllegalArgumentException: couldn't make a guess for int` because `ClassName.bestGuess` rejects anything that doesn't look like a class name (no dot, lowercase first letter).
- `InputBeanInstantiationEmitter.directExpr` (`InputBeanInstantiationEmitter.java:141`) does the same; even if `fieldLocalType` were patched alone, this site would still fail when the field is actually emitted.

The same bug exists for primitive *array elements* via `List<int>` — but Java doesn't permit that, so only the scalar primitive case is reachable.

## Proposed solution

Map primitive type names to their wrapper FQNs at the boundary where the Java reflection string enters our model, so `FieldBinding.javaElementTypeName` is invariantly a real class name and the two `ClassName.bestGuess` sites need no change.

Concretely, add a small helper in `InputBeanResolver` and call it from `peelJavaListSet` before returning:

```java
private static String boxPrimitive(String name) {
    return switch (name) {
        case "int"     -> "java.lang.Integer";
        case "long"    -> "java.lang.Long";
        case "boolean" -> "java.lang.Boolean";
        case "double"  -> "java.lang.Double";
        case "float"   -> "java.lang.Float";
        case "short"   -> "java.lang.Short";
        case "byte"    -> "java.lang.Byte";
        case "char"    -> "java.lang.Character";
        case "void"    -> name; // unreachable here, but harmless
        default        -> name;
    };
}
```

`peelJavaListSet` then wraps its scalar return: `return new JavaElement(false, boxPrimitive(typeName));`. The list-shape branches already operate on a generic argument string, which can't be a primitive (Java disallows `List<int>`), so they need no change.

The emitted code is unaffected by the box: a cast `(Integer) raw.get("n")` paired with a `setN(int)` setter or an `int n` record component autoboxes/unboxes in Java. Null-handling in `directExpr` (`InputBeanInstantiationEmitter.java:140-148`) is already null-aware via the wrapper cast.

## Test plan

Add a pipeline test under `graphitron/src/test/...` (the InputBean coverage tier — colocate with the existing `InputBeanResolver` / `InputBeanInstantiationEmitter` tests) exercising:

1. A record input bean with a primitive `int` component (`record FooInput(int n, String s) {}`) bound to an SDL `input FooInput { n: Int!, s: String }`. Pipeline must build and the emitted `createFooInput` helper must compile and round-trip a `Map<String,Object>` populated with `Map.of("n", 42, "s", "x")`.
2. A JavaBean input with a primitive `boolean` setter (`void setActive(boolean)`). Same shape.
3. A list of primitives is *not* reachable through the bean path (`List<int>` is illegal at the Java level) and need not be tested; the existing `List<Integer>` coverage already exercises the wrapper case.

Confirm `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` is green; the failing graphitron:dev scenario this item exists to fix is verified by the new pipeline test rather than by reproducing the production schema, since the production schema is consumer-owned.

## Out of scope

- Touching the `Direct` vs `EnumValueOf` vs `InputBean` leaf classification — primitives only ever surface through `Direct`, and the classifier already routes them correctly; the bug is purely in the type-name representation.
- Generalising `ClassName.bestGuess` in `graphitron-javapoet` to accept primitives. The javapoet contract treats primitives via `TypeName.INT` etc., not via `ClassName`, and changing that would be a much wider refactor of no immediate value.
