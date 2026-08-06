---
id: R602
title: "Decide the INPUT_OBJECT locations of @record and @table: narrow or retired-location convention"
status: Backlog
bucket: cleanup
priority: 4
theme: diagnostics
depends-on: []
created: 2026-08-06
last-updated: 2026-08-06
---

# Decide the INPUT_OBJECT locations of @record and @table: narrow or retired-location convention

Two graphitron directives still declare `INPUT_OBJECT` among their SDL locations while the
semantics that once justified it are gone, and two roadmap-era doctrines give opposite answers
about what to do. The retired dimensional-model umbrella's directive-narrowing stage said the
declarations shrink: `@table` and `@record` drop `INPUT_OBJECT`, followed by a fixture sweep.
The shipped state says the opposite: `@table(name: String) on OBJECT | INPUT_OBJECT | INTERFACE`
(`directives.graphqls:38`) deliberately keeps the location under the retired-location convention
(dropping it would make graphql-java fail authored schemas with a locationless grammar error
instead of a graphitron diagnostic), the classify-time rejection was downgraded to
ignored-and-warned, and the model store codifies that reading: `intent_table.type_name` is
documented as "the OBJECT, INPUT_OBJECT, or INTERFACE carrying @table", with the
ignored-and-warned status a detection.

The decision to make, once, with both halves in view: does the retired-location convention
govern `@record(record: ExternalCodeReference) on OBJECT | INPUT_OBJECT`
(`directives.graphqls:393`) too, or does `@record` genuinely narrow? `@record` is the open half:
nothing documents whether its `INPUT_OBJECT` location is a live semantic, a retired location
awaiting the convention's treatment, or a declaration to drop. The outcome is either a one-line
convention entry naming `@record`'s input location retired (plus the warning detection), or a
narrowing with its fixture sweep. Either way the answer lands in the directives reference and,
if the store has shipped, as a comment or detection beside `intent_record`, and the old
umbrella's narrowing stage closes with it.
