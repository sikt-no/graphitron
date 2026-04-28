---
title: "Decompose `FieldBuilder`"
status: Backlog
bucket: architecture
priority: 2
---

# Decompose `FieldBuilder`

Split the 2,217-line / 56-private-method builder along the field taxonomy. Blocked on argument-resolution unification. Proposed split: `QueryFieldBuilder`, `MutationFieldBuilder`, `ChildFieldBuilder` plus a shared argument-classification module.

Size figures audited 2026-04-28 against trunk (`FieldBuilder.java`); refresh on each plan revision rather than letting the prose drift.
