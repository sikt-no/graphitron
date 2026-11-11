---
id: R1166
title: "JDK exceptions are throwables the store knows, with their ancestry"
status: Backlog
bucket: architecture
priority: 4
theme: error-channel
depends-on: []
created: 2026-11-11
last-updated: 2026-11-11
---

# JDK exceptions are throwables the store knows, with their ancestry

## Goal

The fact store (the per-graph database the generator fills from the SDL, the classpath and the jOOQ catalog) holds every exception class an author may name in an `@error` handler, with every type it is, whether the class comes from a jar or from the JDK. Today it holds only the classes read off a classpath entry, so `java.lang.RuntimeException`, `java.lang.IllegalStateException` and `java.sql.SQLException` are known to it only as the name of some library exception's supertype: nothing in the store says that `IllegalArgumentException` is a `RuntimeException`. Two readers need that. Ordering error handlers narrowest first (R1165) compares handler classes by ancestry and cannot place a handler on a JDK class without it, and the editor's completion for `className:` should offer JDK exceptions beside library ones.

```graphql
type FilmLookupCatchAll @error(handlers: [
    {handler: GENERIC, className: "java.lang.IllegalStateException", description: "The film lookup failed."}
]) { path: [String!]! message: String! }
```

After this item the class named here has a `code_throwable` row and `code_throwable_supertype` rows of its own.

## Notes for the Spec

- `code_throwable` and `code_throwable_supertype` are written by `CodeCapture.throwables` from the classpath census alone; `ClassHeaders` opens the platform's runtime image only to follow a classpath class's ancestors.
- Both relations key on `store_source`, so the platform needs a source row. `FederationCapture` is the precedent for a source that is not a classpath entry: it registers a library as `LIBRARY`, stamped by version. A platform source stamped by the runtime version re-reads when the JDK changes and is swept like any other.
- What the source admits is a Spec decision; the throwables in the exported packages of `java.base` and `java.sql` cover what a handler names.
- The ancestry is that of the JDK the generator runs on, which is also what the classifier's reflection reads today; the relation comment should say so.
- `roadmap/derived-read-cost-is-a-shape-problem.md` plans "A class's supertypes are one code fact" and "Every `code_` row hangs off a class". This item adds rows and leaves the shape alone; whichever relation ends up holding supertypes has to cover the platform source.
