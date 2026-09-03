---
id: R901
title: "trailing_segments counts what every reader only asks as a yes or no"
status: Done
bucket: cleanup
priority: 5
theme: model-cleanup
depends-on: [derived-read-cost-is-a-shape-problem]
created: 2026-08-31
last-updated: 2026-09-03
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


## Resolved by R876: it was a count of nothing, and it is a name now

Measured first, as this item asked. On the captured consumer schema
(`sis-2026-08-31`, 26 818 lines) all 108 argMapping entries resolve their whole written path:
`trailing_segments` is zero on every row, `intent_argmapping_projection_defect` is empty, and no
value above zero occurs anywhere. So the open-ended arm was carrying a case that does not occur, and
so was the arm below it.

The comment's three readings were right in kind and the count was the wrong carrier for them. Under
the coordinate remodelling the candidate relation holds every spelling legal at a coordinate, which
makes the readings a property of the match rather than an arithmetic:

* zero becomes `graphitron_argmapping_match.bound_path` equalling the entry's `written_path`, the
  whole of what was written having bound;
* one becomes that comparison coming out unequal, and the name a message quotes is
  `graphitron_argmapping_entry.tail_name`, read from the relation that owns the spelling rather
  than copied forward through the resolution;
* two or more stops being a reading at all. Nothing at the coordinate is spelled that way, so there
  is no match row, and the path is refused with every other unresolvable spelling. The
  `TRAILING_SEGMENTS_BEYOND_ONE` verdict went with it, five verdicts remaining.

Not a boolean, then, and not a spelled vocabulary either: a name where there was a count, and an
absence where there was an arm. The distinction two readers needed survives, and neither of them
reads arithmetic to get it.
