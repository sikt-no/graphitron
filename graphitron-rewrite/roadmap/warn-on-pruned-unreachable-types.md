---
id: R319
title: "Warn on pruned unreachable output types instead of dropping them silently"
status: Backlog
bucket: architecture
priority: 5
theme: structural-refactor
depends-on: []
created: 2026-06-17
last-updated: 2026-06-17
---

# Warn on pruned unreachable output types instead of dropping them silently

R279 slice 6 made classification reachability-pruned: an output composite (object / interface /
union) that the field-first walk never reaches is no longer classified, gets no generated file, and
the prune is observable. That is the right behavior, but it is currently *silent*: an author who
writes a type no field returns gets no signal that their type was dropped, which reads as a
mysteriously missing resolver. The intent is a warning, not an error: unreachable types are allowed
and pruned, and a healthy reachable schema must still build, so this must never block. Emit a
build warning naming each pruned unreachable output type (and ideally why it is unreachable), so
dead schema surfaces without failing an otherwise sound build. Small, additive, and orthogonal to
R317's byte-identical reordering; it rides best on top of the single-pass walk R317 lands.
