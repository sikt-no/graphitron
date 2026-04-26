---
title: "Decompose `FieldBuilder`"
status: Backlog
bucket: architecture
priority: 2
---

# Decompose `FieldBuilder`

Split the 1,750-line builder along the field taxonomy. Blocked on argument-resolution unification. Proposed split: `QueryFieldBuilder`, `MutationFieldBuilder`, `ChildFieldBuilder` plus a shared argument-classification module.
