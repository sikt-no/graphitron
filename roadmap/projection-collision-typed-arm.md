---
id: R891
title: "The projection address collision splices a folded name that exists nowhere"
status: Backlog
bucket: bug
priority: 3
theme: diagnostics
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# The projection address collision splices a folded name that exists nowhere

The projection address census's validator mirror hands the author a case-folded name that names no generated class: `ProjectionCommands.AddressCollision` carries `foldedSimpleName`, a fully lowercased string spliced into the rejection text, and the private helper `byFoldedName` is an identity function whose comment apologises for exactly that. This is the projection twin of the defect R889 repairs on the launcher side (a rejection quoting a lowercased `fqcn#method` that exists nowhere), and R889 already names the typed-collision arm as Backlog material rather than widening its own fix. The repair has the same shape on both censuses: carry the colliding group as typed data (the shape `Rejection.InvalidSchema.CaseFoldCollision` exists for, and which the schema-builder's type-stem pass already produces) and render real, unfolded names in the author-facing message, keeping the fold as the grouping key only. R890's guard quarantines the stringly shape rather than endorsing it; this item is the cleanup the quarantine points at.
