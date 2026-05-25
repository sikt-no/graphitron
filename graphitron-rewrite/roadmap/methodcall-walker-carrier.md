---
id: R238
title: "MethodCall walker carrier (R222 foundation slice)"
status: Backlog
bucket: structural
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-05-25
last-updated: 2026-05-25
---

# MethodCall walker carrier (R222 foundation slice)

R222's Stage 1 foundation slice. R222 names the target shape (dimensional slots on a single field type, populated by producers reading graphql-java primitives directly, with validity riding on a `WalkerResult<Ok|Err>` wrapper) but the pattern hasn't landed anywhere in tree yet; every Stage 2/3 slice that follows will inherit whatever conventions Stage 1 sets for slot identity, producer substrate, `No<Family>` framing, LSP `Diagnostic` code namespace, and source attribution. `MethodCall` is the cleanest carrier to land those conventions on: the smallest consumer surface (~27 references, concentrated in `TypeFetcherGenerator`'s `@service` / `@externalField` / `@tableMethod` / `@condition` arms), existing groundwork in the extracted `CallParam` record and `RowsMethodCall` factory, real failure modes (`AuthorError.UnknownName`, arity mismatches, unbound resolver-method params) to exercise the `Err` path, and a two-layer composition that demonstrates R222's structure cleanly: the walker carrier holds the bound argument list, and the `DataFetcherBuilder.Service` dimensional slot composes it with reflection on the registration container. The slice's scope is one slot on `GraphitronField`, one producer reading `GraphQLFieldDefinition`, one consumer migration (the method-call dispatch arm in `TypeFetcherGenerator`), one LSP wire arm projecting `AuthorError` to `graphitron.<code>` Diagnostic records. The `MethodArguments` → `MethodCall` rename and the `MethodArgumentBinding` arm family land here too, since R222 keeps them inside the umbrella rather than as external dependencies.
