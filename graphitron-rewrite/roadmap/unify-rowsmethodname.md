---
title: "Unify `rowsMethodName()`"
status: Backlog
bucket: cleanup
priority: 1
---

# Unify `rowsMethodName()`

Lift the `"rows" + capitalize(name())` copy-paste from four `BatchKeyField` leaves to a default method on the interface.
