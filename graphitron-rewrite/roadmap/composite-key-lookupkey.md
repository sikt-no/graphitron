---
id: R5
title: "Composite-key `@lookupKey` on list-of-input-object arguments"
status: Backlog
bucket: architecture
priority: 4
theme: model-cleanup
depends-on: []
---

# Composite-key `@lookupKey` on list-of-input-object arguments

Add `ArgumentRef.CompositeLookupArg` carrying `(input-field-name, target-column)` pairs resolved from `@field(name:)` directives; `buildInputRowsMethod` already handles arbitrary-arity VALUES + JOIN.
