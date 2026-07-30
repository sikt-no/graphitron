---
id: R559
title: "Fetcher-to-TenantConnections recompile edge parity"
status: Backlog
bucket: cleanup
priority: 40
theme: dev-loop
depends-on: []
created: 2026-07-30
last-updated: 2026-07-30
---

# Fetcher-to-TenantConnections recompile edge parity

Tenant-routed fetchers reference the generated `TenantConnections` carrier (verified in the
multitenant sakila package), but the compile-dependency graph carries no fetcher-to-TenantConnections
edge: not in the retired model-walking builder, and not in the plan projection (`PlanCompileGraph`)
that replaced it, whose oracle fixtures configure no tenancy. The gap is harmless for the dev loop's
schema-edit recompiles today, because `TenantConnections`' ABI moves only on a codegen-config change,
which re-baselines through a full `compileAll` rather than an incremental prune. It is still a parity
hole between the emitted references and the graph: either add the edge (blanket it onto the frozen
cover if its ABI is schema-invariant per configuration, or derive it from the launcher rows' tenancy
facts), or record the exclusion in `PlanCompileGraph`'s declared-superset story, and add a
tenancy-configured fixture to the three-leg oracle in `IncrementalCompileHarnessTest` so the verdict
is enforced rather than argued. Surfaced during the recompile-graph projection work (R549 slice 7a).
