---
title: "Unify `FkJoin` construction in `parsePathElement`"
status: Backlog
bucket: cleanup
priority: 2
theme: model-cleanup
depends-on: []
---

# Unify `FkJoin` construction in `parsePathElement`

The `{key:}` branch at `BuildContext.java:557-564` hand-builds `FkJoin`. Delegate to `synthesizeFkJoin` for the source-validated success path, keeping the null-source fallback and connectivity-error arms bespoke.
