---
title: "Retire `@nodeId` synthesis shim"
status: Backlog
bucket: cleanup
priority: 12
---

# Retire `@nodeId` synthesis shim

`TypeBuilder.buildTableType` promotes a metadata-carrying type to `NodeType` even when SDL lacks `implements Node @node`; `FieldBuilder` Path-2 and `BuildContext.classifyInputField` similarly synthesize `NodeIdField` for bare scalar `ID` fields without `@nodeId`. Each site fires a per-occurrence WARN today.

Once consumer schemas declare the directives explicitly (production schema in alf is canonical; one external-consumer release window is the courtesy gate), delete the three shim branches and turn the WARN into a terminal classifier error. Test fixtures retain the synthesized cases until then; flip them to canonical `implements Node @node` + `@nodeId` SDL alongside the deletion.
