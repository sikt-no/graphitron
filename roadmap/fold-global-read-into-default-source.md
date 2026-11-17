---
id: R1196
title: "The untenanted global-read and default-source routes fold into one"
status: Backlog
bucket: cleanup
priority: 7
theme: model-cleanup
depends-on: []
created: 2026-11-17
last-updated: 2026-11-17
---

# The untenanted global-read and default-source routes fold into one

## Goal

The classification's untenanted `GlobalRead` and `DefaultSource` routes fold into one, since with no request default tenant both emit `dslDefault`: every read and write of a global table runs on the key whose tenant part is empty, and a split the emitter no longer tells apart stops existing in the model. R1119 slice 8c.3 removed the default tenant and left the split standing.
