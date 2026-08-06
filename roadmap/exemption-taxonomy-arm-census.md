---
id: R586
title: "Exemption taxonomy: unpopulated arms and the population census"
status: Backlog
bucket: testing
theme: classification-model
depends-on: []
created: 2026-08-04
last-updated: 2026-08-06
---

# Exemption taxonomy: unpopulated arms and the population census

The sealed `Exemption` taxonomy (graphitron test sources) carries an arm no registry row constructs: `HarnessSingleCatalog` has zero rows in `ExemptionRegistry`, zero readers, and no roadmap item names the per-example `RewriteContext` slot its javadoc anticipates. The file's own precedent (the removed rides-another-rows-key arm) says an arm earns its place through population, so either the arm should be deleted or the anticipated population named by a live plan. Separately, nothing enforces this: `ExemptionRegistryTest`'s reflective guard finds stray `Map<..., Exemption>` fields but not unpopulated taxonomy arms, so the next arm minted ahead of its population is again an audit finding rather than a build failure. Decide the arm's fate and add the arm-population census (each `Exemption` permit constructed by at least one registered row, or carrying a stated reason for existing unpopulated).

## Fact-base note (2026-08-06)

R589 makes exemptions explicit derived rows, censused in `roadmap/audits/2026-08-06-demand-exemption-census.md` (thirteen populations, against which the Java taxonomy is a partial shadow). Re-anchor the census on the demand/exemption relations: arm population becomes "does a row exist", not reflection over test sources.
Context and the whole-board picture: `roadmap/audits/2026-08-06-fact-base-impact-sweep.md`.
