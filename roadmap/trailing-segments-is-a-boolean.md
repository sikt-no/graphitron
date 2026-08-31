---
id: R901
title: "trailing_segments counts what every reader only asks as a yes or no"
status: Backlog
bucket: cleanup
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# trailing_segments counts what every reader only asks as a yes or no

`intent_argmapping_binding_leaf.trailing_segments` counts how many segments a written argMapping path
spells beyond the candidate it actually reached. An earlier reading of it proposed collapsing the
count to a boolean, on the grounds that every reader only asks whether anything is left over.

**The column's own comment refuses that, and the refusal is the interesting part.** It states three
readings that differ in kind rather than in degree: zero on a `@nodeId` leaf is the bare binding a
rejection closes, one is a key-column projection, and two or more is a typo or a nested form neither
this relation nor its readers claim to resolve. If that is right, a boolean destroys a distinction
two readers need and the proposal is simply wrong.

**So the question is not whether to collapse it but what it is a count of.** A quantity whose three
values mean three unrelated things is a vocabulary wearing an integer's clothes, and this schema
elsewhere spells such a thing as a named discriminator bound by check constraints rather than as
arithmetic a reader has to know how to read. Whether the values above two are one case or several is
the part that has to be measured rather than argued: if nothing in a real consumer schema ever reaches
three, the open-ended arm is carrying a case that does not occur.

Establish what the values actually are against a captured consumer schema, then decide between the
count as it stands and a spelled vocabulary. Do not collapse to a boolean without answering the
comment first.

