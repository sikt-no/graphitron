---
id: R1196
title: "What slice 8c.3 left standing in the model and the instrumentation generator goes"
status: Backlog
bucket: cleanup
priority: 7
theme: model-cleanup
depends-on: []
created: 2026-11-17
last-updated: 2026-11-17
---

# What slice 8c.3 left standing in the model and the instrumentation generator goes

## Goal

The classification's untenanted `GlobalRead` and `DefaultSource` routes fold into one, since with no request default tenant both emit `dslDefault`: every read and write of a global table runs on the key whose tenant part is empty, and a split the emitter no longer tells apart stops existing in the model. R1119 slice 8c.3 removed the default tenant and left the split standing. With it, `GraphitronConnectionInstrumentationGenerator.generate` stops taking the tenant key type and the session identity it no longer reads, now the instrumentation reads only the request's connection source; its callers stop passing them (folded in from R1197). Either part may instead become a node of R876, which is deleting much of this code, if its author wants it there.
