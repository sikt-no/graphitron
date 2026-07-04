---
id: R433
title: "Rewrite design principles: state principles at altitude, drop live-inventory enumerations"
status: Ready
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

## In Review -> Ready rework findings (2026-07-04, reviewer session != implementer)

All six enumerated inventory instances, the new altitude section, the vision-alignment notes, and the
stale-R260 fix were implemented faithfully; every referenced symbol/test/roadmap id was verified live
(`GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`, R222/R333/R431/R238/R239/
R240/R334, the three-file `org.jooq` import set). The doc renders. The rework is scoped to the
net-effect claim above, which the review falsified at four spots:

1. **Stale retired symbol (blocking).** The DSL.val section ("`CallSiteExtraction` solves a different
   problem", ~line 232) still enumerates `Direct`, `EnumValueOf`, `TextMapLookup`, `JooqConvert`.
   R229 retired the `TextMapLookup` permit (per `CallSiteExtraction` javadoc). Restate at altitude
   ("the value-coercion strategies") or name only live arms. Same stale arm also survives in
   `code-generation-triggers.adoc:627` and `argument-resolution.adoc:417`; those are outside this
   item's file but the same family, fix or file follow-up.
2. **Rotted census left in place.** "The reactor is self-contained" enumerates 11 modules; the root
   pom has 12 (`docs` is missing from the list). Either point at the root pom (`<modules>` is the
   live inventory) or note the census is illustrative.
3. **Unguarded count.** "Builder-step results are sealed" says "the thirteen resolver siblings"; no
   test pins thirteen (`SealedHierarchyDocCoverageTest` javadoc explicitly declines), and no obvious
   census reproduces it (7 `*DirectiveResolver` classes, 10 sealed `Resolved` declarations). Drop the
   number ("the resolver siblings"); the same count in `typed-rejection.adoc` is follow-up material.
4. **Discovery recipe over-includes (new text).** The parse-boundary replacement says the classifier
   set is "discoverable by their `java.lang.reflect` imports", but 18 main-source files import
   `java.lang.reflect` — model carriers legitimately holding resolved `Method`/`Field`/`Constructor`
   handles (`AccessorResolution`, `ErrorsSlot`, `NonBoundSetter`, `PayloadConstructionShape`) and
   non-classifier uses (`ClasspathScanner`) — versus ~8 files actually reading the `Type` tree. The
   `org.jooq` recipe works because the import IS the discriminator; here it is not. Key the recipe on
   the thing the principle restricts (the reflection `Type` tree / `getGeneric*` reads), not the
   package import.

Non-blocking observations for the fixer's judgment: the wire-format section keeps live arm lists
(`NodeIdDecodeKeys.{...}`, `ColumnPredicate.{...}`) that are defensible as worked-example slot
anchors but unguarded under the new principle's letter; `SourceKey.Reader`'s javadoc header says
"Six permits today" while seven arms exist (`ProducedRecordRead` missing from its list) — outside
this item's file, but it is the exact rot the doc now points readers at ("read the type for the
current arm set"), worth a follow-up stub or a ride-along on R431.
