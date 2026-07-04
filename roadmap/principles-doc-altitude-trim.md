---
id: R433
title: "Rewrite design principles: state principles at altitude, drop live-inventory enumerations"
status: Backlog
bucket: docs
priority: 4
theme: docs
depends-on: []
created: 2026-07-04
last-updated: 2026-07-04
---

# Rewrite design principles: state principles at altitude, drop live-inventory enumerations

`docs/architecture/explanation/rewrite-design-principles.adoc` violates its own "Documentation names
only live tests/code" rule by carrying unguarded live inventories that rot silently, and it canonizes
surfaces the R222/R333 pivot dissolves. Read-through findings (2026-07-04 design session):

**Inventory-rot instances** (enumerations of live type/file censuses with no guarding test):

1. Sealed-hierarchies section: enumerates `SourceKey.Reader`'s arms (five listed, seven exist) and
   `Wrap`'s arms. Point at the exemplar; do not enumerate it.
2. The four-axis `SourceKey`/`LoaderRegistration` census + dispatch-axes xref. The principle stated
   there (per-axis meaning in the type system, no god accessor) is R222/R333's own principle; only
   the exemplar inventory is transitional.
3. Parse-boundary section: "Today five files cross that boundary" + "Two other classes also import
   org.jooq directly today". File censuses rot with every refactor.
4. Error-quality section: "Today: 17 occurrences across five files; 7 in BuildContext, ...". A
   count-by-file census.
5. Helper-locality: "Compliant emitters (audit 2026-04-25): ...". A dated compliance roster.
6. Sub-taxonomies section: re-enumerates `Wrap` arms and slot locations.

**The discriminator to write in as a principle** (the doc already contains its own counter-example):
the validator-mirrors section names the four-way dispatch partition *and*
`GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`, an inventory that is fine to
name because a live test pins it. New/widened principle: inventories appear in principles only when a
named test guards them; otherwise state the rule, one canonical exemplar, and the smell. Counts, arm
lists, and compliance rosters belong in guarded tests or generated reports.

**Vision divergence to fix in the same pass** (banner-first, per the 2026-07-04 session):
`SourceKey` exemplars get a forward note (R431 decomposes it; the principle survives the
decomposition, worth saying explicitly); "Established interfaces: ... `MethodBackedField` ..." gets
the R222 retirement note, with `ServiceField` as the better current exemplar of the capability
pattern; the preamble gets one sentence pointing at R222/R333 as the target architecture these
principles also govern.

**Stale claims**: "`ArgCallEmitter.buildNodeIdDecodeExtraction` is the current worked counter-example
and the R260 cleanup target" — R260 shipped; the live instance of that smell is R334's `@condition`
arg extraction, or drop the citation.

**Trim candidates**: the wire-format section's full retired-carrier enumeration compresses to the
rule + "see R50 in the changelog"; the two "candidate type-system lift" worked examples in the
classifier-guarantees section (R240/R239 territory) compress. Untouched: the emitter-conventions half
(DSL.val, `__`-identifier discipline, helper-locality rule, DTO-parent batching) is load-bearing; the
`__` mega-bullet may split for readability but loses no content.

Net effect: shorter doc; every remaining named symbol is stable, test-pinned, or forward-noted; the
principles stop canonizing surfaces the roadmap dissolves.
