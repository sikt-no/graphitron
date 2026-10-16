---
id: R1056
title: "TypeFetcherGenerator threads an assembled schema that no emit step reads"
status: Backlog
bucket: cleanup
depends-on: []
created: 2026-10-16
last-updated: 2026-10-16
---

# TypeFetcherGenerator threads an assembled schema that no emit step reads

## Goal

`TypeFetcherGenerator.generate` takes the assembled `GraphQLSchema` (the generate-time graphql-java schema built from the SDL) and passes it through `generateForType` into `generateTypeSpec`, which no longer reads it: its last reader was the `@service` validation pre-step, which now takes each argument's shape from the classified model instead. After this lands, the fetcher generator's signatures stop declaring an input the emitter does not use, so the next reader of the generator does not go looking for an emit-time schema read that is not there. The parameter has roughly a hundred test call sites through the `generateTypeSpec` overloads, which is why it was left out of the change that removed its last reader.
