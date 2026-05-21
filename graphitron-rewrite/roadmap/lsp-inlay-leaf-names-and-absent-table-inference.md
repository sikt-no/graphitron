---
id: R217
title: "LSP inlay classification labels surface model leaf names; inferred-@table renders on declarations when directive is absent"
status: Backlog
bucket: lsp
priority: 3
theme: lsp
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# LSP inlay classification labels surface model leaf names; inferred-@table renders on declarations when directive is absent

The R160 LSP inlay-hint surface trades pedagogical value for prose polish in two places. The classification arm (both inlay and hover) renders pretty strings like `"table type"`, `"column"`, `"query table-method field"` via `LspClassificationLabels.projectionLabel` / `projectionTypeLabel`, decoupled from the projection record names a developer would learn the model by (`FieldClassification.TableTarget`, `TypeClassification.TableInput`, etc.). Developers reading hints get no foothold into the taxonomy they will eventually navigate. The inferred-directive arm only fires at *existing* `@table` / `@field` / `@reference` directive nodes whose canonical argument is omitted, because it walks `Directives.findAll(root)`; types that classify as `TableInput` (or `Table`/`Node`/`TableInterface`) but carry no `@table` directive at all render nothing, hiding the binding that the developer most needs to see. Switch the inlay label switches to render bare projection-record simple names (`TableTarget`, `Column`, `Table`, `TableInput`, ...) — the hover keeps its rich payload and additionally prefixes the qualified name (`FieldClassification.TableTarget`) so the developer can search for it. Extend the inferred-directive arm with a second pass that walks input-type (and object-type) declarations, and where the classification is `Table` / `Node` / `TableInterface` / `TableInput` and no `@table` directive is present on the type, render a synthetic `@table(name: "...")` ghost at the type-name node.
