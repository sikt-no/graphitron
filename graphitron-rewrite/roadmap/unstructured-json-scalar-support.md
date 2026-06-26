---
id: R387
title: "Native unstructured-JSON scalar support"
status: Backlog
bucket: architecture
priority: 3
depends-on: []
created: 2026-06-26
last-updated: 2026-06-26
---

# Native unstructured-JSON scalar support

Consumers cannot use an unstructured `JSON` scalar (graphql-java-extended-scalars' `ExtendedScalars.Json` / `ExtendedScalars.Object`, both backed by `graphql.scalars.object.ObjectScalar`). Its `Coercing` is an anonymous `Coercing<Object, Object>`, so `ScalarTypeResolver.recoverInputType` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/ScalarTypeResolver.java:261`) returns `null` and the resolver hands back a `CoercingErased` rejection (`ANONYMOUS_CLASS`). This is deliberate today: the convention table omits `Json`/`Object` on purpose (`ScalarTypeResolver.java:474-478`), and `custom-scalars.adoc` documents the rejection, under the stance that `Object` is too ambiguous to drop into a generated record / `Field<X>`. The current escape hatch is "author a typed wrapper `Coercing` with a concrete `I` and bind it via `@scalarType`". A consumer hitting this in practice (jsonb-backed columns surfaced as a GraphQL `JSON` field) is a recurring, likely-to-repeat request, so we want a first-class answer rather than a per-consumer wrapper each time.

The work is a design decision before it is code: what Java type should `JSON` materialize as in generated records, service params, and `Field<X>` projections? The erasure guard exists precisely because `Coercing<Object, Object>` does not answer that. Candidate targets, each with a tradeoff: Jackson `JsonNode` (pulls Jackson onto the generated-runtime surface), `Map<String,Object>`/`List<Object>` (no new dep, but lossy and awkward for array-vs-object), jOOQ `org.jooq.JSONB` (aligns with the DB read side but is the wrong carrier for JSON used as an *input* argument), or a thin graphitron-owned `Json` value type. Spec work should pick a target (or a small set, possibly directive-selectable), decide whether graphitron ships a canonical `Coercing` so consumers do not hand-roll one, and define input-vs-output behaviour (the motivating case is output-only: serialize jsonb to a JSON tree, reject `parseValue`/`parseLiteral`).

Investigate first whether the documented typed-wrapper workaround actually works end-to-end. A consumer reported that even a *named* `Coercing<JSONB, Object>` (concrete `I`, which should pass `recoverInputType`) was rejected with the same `CoercingErased` message. If the message still names `ObjectScalar$1`/`ANONYMOUS_CLASS`, the likely cause is a classpath/wiring gotcha (the `@scalarType` constant must be on the *codegen* classpath, not the plugin classpath, per the NOTE in `custom-scalars.adoc`) rather than the guard rejecting the wrapper, so graphitron was still resolving `ExtendedScalars.Json`. If instead a genuinely concrete `Coercing<JSONB, Object>` on the codegen classpath is rejected, that is a resolver bug in the interface/supertype walk and a separate fix from the native-`JSON` feature. Disambiguate the two before scoping the native path; the answer changes whether this item is "add a feature" or "feature + fix the documented workaround".

Out of scope: changing how graphql-java coerces values (graphitron never coerces; it registers the `GraphQLScalarType` and emits `typeRef`). The federation-namespace synthesis path (`ScalarResolution.Synthesised`) is a possible model to reuse for emitting a graphitron-owned JSON scalar, not a constraint.
