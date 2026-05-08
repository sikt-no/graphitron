---
id: R99
title: "LSP classpath scan misses sibling modules when dev goal runs from a sub-module"
status: Backlog
bucket: feature
theme: lsp
depends-on: []
---

# LSP classpath scan misses sibling modules when dev goal runs from a sub-module

When a consumer runs `mvn graphitron:dev` from inside one sub-module of a multi-module project (e.g. `cd opptak-subgraph && mvn graphitron:dev`), Maven loads only that module's pom and the reactor contains a single project. `AbstractRewriteMojo` reads `MavenSession.getAllProjects()` to assemble classpath roots for the LSP catalog scan (the Phase 5e widening that was meant to cover the multi-module case), so the scan only sees the current module's `target/classes`. Sibling modules that hold service / condition / record classes referenced by the schema are silently absent: `data.externalReferences()` is empty, and the LSP returns no completions for `@service(service: {className: "..."})` / `@condition` / `@record`, no hover, and no unknown-class diagnostics on those references. Running from the parent with `mvn -pl <module> graphitron:dev` works because there `getAllProjects()` returns the full reactor regardless of `-pl` filtering, but running from inside a module is a normal workflow (faster startup, scoped logs) and the silent failure is the worst-case shape: the user sees an empty popup and no error to grep for.

The fix is to detect the "single-project reactor" case and walk up to the parent pom, parse its `<modules>`, and add each `<module>/target/classes` to the classpath roots. No Maven re-resolution: the mojo can read the parent's pom.xml directly and resolve module directories relative to it. The walk should stop at the first ancestor pom that lists the current project as a `<module>` (covers the common standard layout) and not climb arbitrarily; failing to find one leaves behaviour unchanged from today. An escape hatch (`<classpathRoots>` or `-Dgraphitron.lsp.classpathRoots=`) is worth considering for non-standard layouts but is secondary; the primary goal is that the default workflow Just Works from a sub-module.

Scope:

- `graphitron-rewrite/graphitron-maven/src/main/java/.../AbstractRewriteMojo.java` (line ~113): widen the classpath-roots assembly with the parent-pom walk-up. Keep the existing `getAllProjects()` path as the primary source; the walk-up is a fallback that runs when that path returns a single project.
- Test coverage in the maven-plugin module: a fixture multi-module reactor that exercises the sub-module invocation and asserts the sibling's classes land in the catalog. The fixture-based pipeline tier (see `rewrite-design-principles.adoc`) is the right level — unit tests of the walk-up logic alone won't catch the wiring.
- A diagnostic log line at LSP startup: "classpath scan: N reactor roots, M external references". Would have made this issue self-diagnose; cheap to add.
- Docs: a section in the dev-goal documentation under `graphitron-rewrite/docs/` (or wherever the dev-goal usage is documented; check at Spec time) covering the multi-module workflow and the new fallback. Reference from the public site if the dev goal has a page there.
- A short note in `CLAUDE.md` next to the existing catalog-jar footgun, since this is the same shape (a `-pl` / sub-module gotcha that produces silent empty results); keep it brief and link to this item or the docs.

Non-goals: rewriting how the catalog is built, supporting non-Maven build systems, or surfacing classpath roots in the LSP wire protocol.
