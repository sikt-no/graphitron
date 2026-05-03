---
id: R30
title: "Selection parser audit"
status: Done
bucket: cleanup
priority: 7
theme: model-cleanup
depends-on: []
---

# Selection parser audit

`selection/` hand-rolls ~500 LOC; audit whether re-parsing is needed given what graphql-java already provides.

## Audit finding

The parser is needed. `@experimental_constructType(selection: "...")` carries a generation-time string argument that must be parsed before any graphql-java runtime is in play; graphql-java's `DataFetchingFieldSelectionSet` / `SelectedField` APIs only exist inside a live query execution and cannot substitute here. The `selection/` package is the correct home for this parsing; keep it and wire it into the `@experimental_constructType` classifier (tracked as a separate roadmap item).
