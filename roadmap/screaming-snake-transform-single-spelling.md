---
id: R778
title: "Collapse the four verbatim copies of the CamelCase to SCREAMING_SNAKE transform"
status: Backlog
bucket: cleanup
priority: 5
theme: error-channel
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# Collapse the four verbatim copies of the CamelCase to SCREAMING_SNAKE transform

Converting a CamelCase identifier to a `SCREAMING_SNAKE` constant name is spelled four times in
the tree, character for character the same loop each time: `BuildContext.toScreamingSnake`,
`ErrorChannelWalker.toScreamingSnake` (whose own javadoc says "Same transform as
`BuildContext.toScreamingSnake`, kept here so the walker has no dependency on it"),
`FieldBuilder.toScreamingSnake`, and `ErrorMappingsClassGenerator.byTypeConstantName`.

Three of the four mint names in one namespace (`ErrorChannel.mappingsConstantName`, from an SDL
outcome type name, a payload class simple name, or a wrapper SDL type name), so they must agree
or two channels collide. The fourth mints the nested `ErrorMappings.ByType` constants and is
deliberately in its own namespace, so it is free to disagree; today it happens not to. Four
copies that must partly agree and partly need not is the shape worth resolving into one named
transform whose callers state which namespace they are in.

The obstacle is packaging rather than logic: the three classifier copies are private and live in
`no.sikt.graphitron.rewrite`, while the fourth is public because a generator has to call it. The
work is choosing where one shared spelling lives across that boundary, not writing the loop.
Cosmetic on its own; worth taking the next time any of the four is edited.
