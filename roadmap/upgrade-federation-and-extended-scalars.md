---
id: R465
title: "Upgrade federation-graphql-java-support 6.0->6.2 and extended-scalars 22->24"
status: Backlog
bucket: tech-debt
priority: 4
theme: classification-model
depends-on: []
created: 2026-07-10
last-updated: 2026-07-10
---

# Upgrade federation-graphql-java-support 6.0->6.2 and extended-scalars 22->24

The two graphql-java satellite artifacts lag their pinned versions: `federation-graphql-java-support` is on 6.0.0 (latest 6.2.0) and `graphql-java-extended-scalars` is on 22.0 (latest 24.0), both pinned in the root `pom.xml` dependency-management block (lines ~148-157). This is the conservative, ready-now half of the graphql-java catch-up: it holds `graphql-java` itself at 25.0 (the jump to 26.0 is a separate, harder effort, it breaks generator compilation and runs ahead of what federation/extended-scalars officially support) while pulling the satellites current. A dry run confirmed the shape: with graphql-java held at 25.0, federation 6.2.0 (which is built against graphql-java 25.0) and extended-scalars 24.0 (built against 24.1) both resolve and the reactor compiles clean. Federation 6.2.0 introduced no failures. Bumping the two versions is the whole mechanical change.

**R464 has shipped** (`7786ce4`), so this is now unblocked and reduces to a pure version bump. The only fallout from the extended-scalars bump in the dry run was the convention-table drift-guard test (`ScalarTypeResolverTest.conventionTable_coversEveryExtendedScalarsField`) tripping on six new constants (`YearMonth`, `Year`, `AccurateDuration`, `NominalDuration`, `SecondsSinceEpoch`, `HexColorCode`), each demanding a map-or-exclude decision. R464 deleted that convention layer and its drift test outright, so this upgrade has nothing to curate.
