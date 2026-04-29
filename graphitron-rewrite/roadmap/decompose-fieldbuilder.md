---
title: "Decompose `FieldBuilder`"
status: Backlog
bucket: architecture
priority: 2
theme: structural-refactor
depends-on: []
---

# Decompose `FieldBuilder`

Split the 2,217-line / 56-private-method builder along the field taxonomy. Argument-resolution unification has shipped (Phase 4 landed under Done), so this is no longer blocked. Proposed split: `QueryFieldBuilder`, `MutationFieldBuilder`, `ChildFieldBuilder` plus a shared argument-classification module.

Size figures audited 2026-04-28 against trunk (`FieldBuilder.java`); refresh on each plan revision rather than letting the prose drift.
