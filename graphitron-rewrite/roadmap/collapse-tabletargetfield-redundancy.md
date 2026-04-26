---
title: "Collapse `TableTargetField` structural redundancy"
status: Backlog
bucket: cleanup
priority: 3
---

# Collapse `TableTargetField` structural redundancy

Six `Table*Field` variants share identical components; evaluate sealed intermediates (`StandardTableField`, `RecordBoundField`).
