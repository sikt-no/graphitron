---
id: R1114
title: "Editor: complete and check FieldSort.name against the table its path lands on"
status: Backlog
bucket: dx
priority: 6
theme: lsp
depends-on: []
created: 2026-10-28
last-updated: 2026-10-28
---

# Editor: complete and check FieldSort.name against the table its path lands on

## Goal

An author writing `@defaultOrder(fields: [{name: "...", path: [...]}])` or the same entry in an `@order` enum value gets completion, hover, go-to-definition and an unknown-column diagnostic for `name` against the table the `path` lands on. Today the editor answers silent for a `FieldSort.name` beside a `path` (`LspVocabulary.namesRelatedColumn`), because the store has no relation saying which table a `FieldSort` path reaches, and checking the name against the field's own table would flag every valid related-table column. The build already resolves and rejects such names; this item brings the same answer to edit time.

The plan adds a stored `FieldSort` path relation with its first reader, under the adding-a-relation discipline: it is keyed by the `FieldSort` element's position, not by a `@reference` application as `graphitron_ast_reference_element_entry` is, and its landing table is derived in the anchoring phase from the captured foreign keys. The editor's column scope for a `FieldSort.name` beside a `path` then reads that landing table instead of going silent. `ReferenceElement.key` inside a `FieldSort` could read the same relation to complete keys from the path's running table rather than from the enclosing type's bound table.
