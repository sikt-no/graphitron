---
title: "Selection parser audit"
status: Backlog
bucket: cleanup
priority: 7
theme: model-cleanup
depends-on: []
---

# Selection parser audit

`selection/` hand-rolls ~500 LOC; audit whether re-parsing is needed given what graphql-java already provides.
