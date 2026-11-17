---
id: R1197
title: "The connection instrumentation generator stops taking the tenant key type it no longer reads"
status: Backlog
bucket: cleanup
priority: 8
theme: model-cleanup
depends-on: []
created: 2026-11-17
last-updated: 2026-11-17
---

# The connection instrumentation generator stops taking the tenant key type it no longer reads

## Goal

`GraphitronConnectionInstrumentationGenerator.generate` stops taking the tenant key type and the session identity it no longer reads, now the instrumentation reads only the request's connection source and no request tenant set; its callers stop passing them.
