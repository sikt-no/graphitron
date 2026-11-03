---
id: R1145
title: "A Coercing that binds its type parameters through a generic base class is rejected as erased"
status: Backlog
bucket: bug
priority: 6
theme: codegen-correctness
depends-on: []
created: 2026-11-03
last-updated: 2026-11-03
---

# A Coercing that binds its type parameters through a generic base class is rejected as erased

## Goal

A custom scalar whose `Coercing` inherits its type parameters from a generic base class, such as `class MoneyCoercing extends AbstractTypedCoercing<Money, Money>` where `AbstractTypedCoercing<I, O> implements Coercing<I, O>`, resolves to `Money` instead of being rejected as `CoercingErased`. A *custom scalar* is a `scalar` declaration bound by `@scalarType` to a `GraphQLScalarType` constant, and its coercing's `I` is the Java type Graphitron binds input-record components, service parameters and projections to.

## What the code does today

`ScalarTypeResolver.findCoercingInputType` walks the coercing's class and its superclasses to the first `Coercing<I, O>` parameterisation and takes `I` as written there. On the generic base, `I` is the base's own type variable, so the resolver reports the coercing as erased (`ERASED_NAMED_CLASS`), although the subclass bound `I` to `Money`. The resolver's javadoc names this very shape as supported. A fix substitutes type variables down the superclass chain: map each class's type parameters to the actual arguments its subclass supplied, and resolve `I` through that map before the concreteness check. A generic base reached through a generic interface (`interface TypedCoercing<T> extends Coercing<T, T>`) is the same walk over interface edges.

Found while verifying the custom-scalar array and list support, which carries the input type as a `java.lang.reflect.Type` and so is the natural place to add the substitution.
