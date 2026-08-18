---
id: R720
title: "Three routine read-surface residues: prose naming the directive, a non-discriminating order pin, and a misfiled rejection"
status: Backlog
bucket: cleanup
priority: 4
theme: routine
depends-on: []
created: 2026-08-18
last-updated: 2026-08-18
---

# Three routine read-surface residues: prose naming the directive, a non-discriminating order pin, and a misfiled rejection

Three small residues from the `@routine` read-surface work, filed together because they are one
mistake told three ways: a claim about the binding written as a claim about the directive that used
to be the only way to state it. None is a defect a build catches, and none was worth holding that
item's Done gate.

**Prose naming the directive where the code holds the binding.** A routine's result now binds its
return type, so a chain's landing is table-bound whether or not the author wrote `@table`. Four
places still say `@table`: `QueryField.java`'s `QueryTableField` javadoc ("terminates on the field's
`@table` return type"), the terminus-invariant comment above the same record's compact constructor,
that invariant's own throw message ("the field's `@table` type is bound to ..."), and
`RoutineResolution.java`'s interface javadoc ("the chain's terminus is the field's `@table` type").
The record component is `ReturnTypeRef.TableBoundReturnType` in every case, which is the binding.
An author who took the directive removal up and then hits the terminus invariant reads a message
about a directive their schema does not carry. This is the same sweep that repointed
`isDirectivelessNestingTarget`, the producing-edge registration and
`GraphitronSchemaBuilder.unsupportedFacetCarrierReason`, run over the prose the code left behind.

**A rejection message forked on the wrong question.** `RoutineDirectiveResolver.bindReturn` picks
its message by `hasAppliedDirective(DIR_REFERENCE)`, and tells anything carrying `@reference` that
"the chain lands on a catalog table and the return type must name it". A hops-then-routine chain
carries `@reference` and lands on the routine's own result, which is exactly the shape the return
grounding covers, so on the narrow path where that chain's return is unbindable anyway (a scalar,
an interface, a union) the author is told the wrong thing about where their chain lands. The fork
wants to ask where the chain ends, which is whether the last application is the `@routine`, the
same question `RecordBindingResolver.groundRoutineReturnType` asks one layer up.

**An execution-tier order assertion that passes either way.**
`RoutineFieldExecutionTest.routineTerminusListOrdersByItsAuthoredDefaultOrder` is the test named
for the reported bug, and it does not discriminate against it. The fixture function returns
`(184, 'admin'), (185, 'user')` from a literal `VALUES` set in `init.sql`, and the field's
`@defaultOrder` is ascending over those same two columns, so the asserted row order is the order
the function body produces and a dropped `ORDER BY` passes. The behaviour is genuinely pinned
elsewhere, by the `DESC` arm of `orderByArgumentSortsTheRoutineResultBothWays` and by the executed
`ORDER BY` in `RootLauncherSqlBaselineTest`'s routine baselines, so this is a weak test rather than
a coverage hole. Give the fixture an order the function's own row order contradicts, so the
assertion fails when the clause goes missing.
