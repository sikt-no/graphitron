---
id: R212
title: IntelliJ plugin wrapping graphitron:dev LSP
status: Spec
bucket: feature
theme: lsp
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# IntelliJ plugin wrapping graphitron:dev LSP

## Problem

`mvn graphitron:dev` binds the LSP on a TCP port (`DevServer` at `localhost:8487`, configurable via `graphitron.dev.port`). That transport is deliberate and load-bearing for the dev loop: one long-lived JVM keeps the schema watcher, the classpath watcher, and the in-memory catalog warm across editor restarts, so reattach is sub-second and a save fires a single regen no matter which side observes the write first. The trade-off is that IntelliJ cannot drink from a TCP socket out of the box:

- **IntelliJ Ultimate's built-in LSP API** (`com.intellij.platform.lsp`, since 2023.2) only knows how to spawn a subprocess and speak JSON-RPC over its stdin/stdout. `LspServerDescriptor.createCommandLine()` returns a `GeneralCommandLine`; there is no public extension point for a TCP transport.
- **IntelliJ Community** has no built-in LSP support at all.
- **LSP4IJ** (Red Hat, works in Community) supports TCP, but the UI for it is awkward; the documented configs are stdio-shaped.

Today's editor instructions in `getting-started.adoc` ("point your LSP client at `localhost:8487` (TCP)") work for Neovim / Helix / VS Code and degrade to "install LSP4IJ and configure a TCP `StreamConnectionProvider` yourself" for IntelliJ users. Internal users have flagged this as a blocker — IntelliJ is the dominant editor in the consuming teams, and the current path is far enough off the IntelliJ-native well-trodden track that nobody completes it. A `java -jar bridge.jar --port 8487` command (the alternative considered earlier) does not close the gap either: the user has to know where the bridge jar lives, paste an absolute path into IDE settings, and update it on every plugin upgrade.

## Background

The existing transport-and-process shape is documented in `graphitron-rewrite/docs/getting-started.adoc` "Dev loop". Four cooperating components live in one JVM: LSP server (TCP), schema watcher, classpath watcher, generator dispatch. State is in-memory only; the warm-JVM model is the reason `DevServer.serve()` constructs a *fresh* `GraphitronLanguageServer` per connection backed by a *shared* `Workspace`, so editor reattach drops the per-connection state without touching the parsed-buffer + catalog substrate. R203 is currently In Progress on the `libtree-sitter` packaging; R99 covers a related multi-module classpath-scan gap. Neither overlaps this item's surface; R212 only adds an editor-side surface, not a generator-side one.

The existing stdio entry point at `graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/server/Launcher.java:14-25` is *not* a candidate transport for IntelliJ. It instantiates a fresh `GraphitronLanguageServer` with no shared workspace and no schema watcher — a cold LSP, useful for direct invocation from a test harness but missing every dev-loop affordance. The plugin must wire through `DevServer`, not `Launcher`.

## Design alternatives considered

Four shapes were on the table:

1. **Ultimate-only plugin spawning a bundled stdio↔TCP bridge (chosen for MVP).** Plugin contributes an `LspServerSupportProvider` whose descriptor returns a `GeneralCommandLine` invoking `java -jar <plugin-bundled bridge.jar> --port <port>`. The bridge is a tiny standalone `main` that opens a `Socket` to `127.0.0.1:<port>` and copies bytes between socket↔stdin/stdout on two threads. Pros: works with zero IDE config; warm-JVM benefit preserved; bridge jar is co-versioned with the plugin so path/version drift is impossible; ~150 lines of plugin code, ~50 of bridge. Cons: Ultimate-only (`com.intellij.modules.lsp` is not on Community's classpath); spawns one extra JVM per editor session (~150ms cold start, only on reattach); the platform LSP API was experimental until ~2024.1.

2. **Plugin uses lsp4j directly against the TCP socket; no bridge process.** Skip the platform LSP machinery; the plugin opens its own socket, runs an lsp4j client in-process, drives PSI / annotators / completion contributors / gutter markers from incoming JSON-RPC. Pros: works on Community out of the box; no extra subprocess; full control over UX. Cons: 3–5× the code (we reimplement what the platform's LSP layer hands us for free — diagnostics-to-`HighlightInfo` mapping, completion item rendering, hover popups, code-action menus, document sync); ongoing maintenance burden grows linearly with each LSP feature graphitron ships. Deferred: not the right shape for an MVP; revisit if Community demand becomes a sustained ask.

3. **LSP4IJ adapter — register the same server config as a Red Hat `ServerDefinition`.** LSP4IJ works on Community and Ultimate alike and is the de-facto LSP host for non-Ultimate IntelliJ. A second `plugin.xml` extension contributes the bridge command as an LSP4IJ server definition. Pros: Community parity with negligible incremental code (~30 lines of XML + a thin Kotlin class); same bridge jar serves both paths. Cons: adds a runtime dependency on a third-party plugin (user must install LSP4IJ first); slightly different UX (LSP4IJ has its own tool window and settings surface, which diverges from the platform-native experience). **Fast-follow, not MVP** — ship Ultimate-native first to prove the bridge shape, add the LSP4IJ registration once the bridge is stable.

4. **Stdio mode in DevMojo itself.** Add `-Dgraphitron.dev.stdio=true` to `DevMojo`, read/write JSON-RPC on the Mojo's own stdin/stdout. Rejected: Maven writes plenty of unsolicited output to stdout (warnings, lifecycle banners, the user's own `getLog()` calls), which corrupts JSON-RPC framing. Detaching from Maven by spawning a separate JVM is just the bridge shape, plus the loss of the warm-JVM benefit. The only viable variant of this idea is "run the LSP outside Maven entirely," which is a generator-architecture redesign far outside R212's scope.

5. **Custom JetBrains plugin reimplementing the LSP semantics natively.** The path the JetBrains GraphQL plugin took — no LSP at all; reuse the in-process generator and drive editor surfaces directly. Rejected on the principle that `graphitron-lsp` is the canonical surface for graphitron's editor-facing semantics, and `LspServerSupportProvider` is the platform's adapter from that surface to IntelliJ; reimplementing means forking the editor-facing semantics into IntelliJ-shaped data structures (`PsiElement`, `Annotator`, `CompletionContributor`) and then carrying drift between the LSP and the IntelliJ implementation forever. Other LSP clients (Neovim, Helix, VS Code, future LSP4IJ) keep walking the LSP surface; only IntelliJ's would diverge. Secondary: order-of-magnitude more code than (2), duplicates everything `graphitron-lsp` already does (parsing, completions, hover, definitions, diagnostics, inlay hints). Re-evaluate only if the platform LSP API regresses on something graphitron needs.

The chosen shape (1) minimises code and preserves the warm-JVM contract; (3) is the natural fast-follow once (1) is in users' hands and validated against real schemas.

## Direction

### Modules

Two new modules under `graphitron-rewrite/`:

- **`graphitron-lsp-bridge`** (Maven, child of `graphitron-rewrite-parent`). Single class `no.sikt.graphitron.lsp.bridge.StdioBridge` with a `main(String[] args)` that:
  - parses `--port <int>` (default 8487) and `--host <str>` (default `127.0.0.1`);
  - opens a `Socket` to that endpoint;
  - launches two daemon threads: `System.in.transferTo(socket.getOutputStream())` and `socket.getInputStream().transferTo(System.out)`;
  - exits with status 0 when either copy returns (clean EOF) and nonzero on `IOException`.

  `<release>17</release>` — the bridge is the only generator-tree code that runs **outside** the consumer's `mvn` JVM (IntelliJ spawns it as a subprocess), so it has to be runnable on the JBR shipped inside IntelliJ. JBR 17 was the floor since 2022.3; JBR 21 lands across the 2024.x line, but pinning to 17 covers every supported IntelliJ version the plugin's `since-build=241` allows. This is **not** the "generated output targets Java 17" rule (which is a different principle entirely, about the SDK floor of code we emit for consumers' jOOQ-bound runtime); it's a JBR-runtime constraint specific to the bridge. Everything else under `graphitron-rewrite/` continues to compile at `<release>25</release>` per the parent pom. Zero compile dependencies; JDK 17's `InputStream.transferTo` is sufficient. Produces a shaded executable jar via `maven-shade-plugin`'s `Main-Class` manifest entry, so `java -jar` is enough; no classpath argument required. Sits inside the main reactor so a normal `mvn install -f graphitron-rewrite/pom.xml -Plocal-db` builds it.

- **`graphitron-intellij-plugin`** (Gradle, *not* in the Maven reactor — sits **outside** the rewrite-build-independence invariant by design). The IntelliJ Platform Plugin SDK is Gradle-only in practice; mixing it into the Maven reactor via `gradle-maven-plugin` invites a class of build failures the project has not signed up for. The principle at stake is `rewrite-design-principles.adoc`'s "rewrite builds independently": `mvn install -f graphitron-rewrite/pom.xml` (verified by `graphitron-rewrite/scripts/verify-standalone-build.sh` against a clean empty local repo) must produce the publishable rewrite artifact set from a self-contained reactor. The plugin is *not* in that set — it's a downstream editor surface, not a generator artifact a consumer's pom would depend on — and the precedent is `graphitron-tree-sitter-natives`, which sits inside `graphitron-rewrite/` but is deliberately omitted from the parent pom's `<modules>` list for the same release-cadence-independence reason. The Gradle module reads the bridge jar from a fixed relative path (`../graphitron-lsp-bridge/target/graphitron-lsp-bridge-<version>-shaded.jar`) and copies it into the plugin's `src/main/resources/bridge.jar` at package time. `mvn install -f graphitron-rewrite/pom.xml -Plocal-db` produces the bridge; `./gradlew :graphitron-intellij-plugin:buildPlugin` (run separately, JDK 25, same JDK floor the parent pom's enforcer pins) produces the plugin zip. CI gates: the existing `rewrite-build.yml` workflow keeps owning the Maven side; a new `intellij-plugin-build.yml` workflow invokes `./gradlew :graphitron-intellij-plugin:buildPlugin` on PR and trunk pushes that touch `graphitron-intellij-plugin/**` or `graphitron-lsp-bridge/**`, uploading the plugin zip as a workflow artifact. `verify-standalone-build.sh`'s forbidden-coord grep needs no change (the plugin doesn't publish a Maven artifact under any forbidden coord), but the script is taught to ignore the Gradle module's outputs by path so a future "did the rewrite stay standalone?" run doesn't false-positive on a stale `graphitron-intellij-plugin/build/` tree. Documented as a two-step build in `graphitron-rewrite/docs/getting-started.adoc` and in a new `intellij-plugin-release.adoc` modeled on `tree-sitter-natives-release.adoc`.

  Module contents:
  - `build.gradle.kts` using `org.jetbrains.intellij.platform` 2.x (the current Gradle plugin from JetBrains).
  - `src/main/kotlin/no/sikt/graphitron/intellij/`
    - `GraphitronLspProvider : LspServerSupportProvider` — entry point. On `fileOpened`, filters by extension (`.graphqls` / `.graphql`) and project-detection (the consumer pom declares `<plugin><artifactId>graphitron-maven-plugin</artifactId>` somewhere in the reactor — checked via IntelliJ's `MavenProjectsManager`), then `serverStarter.ensureServerStarted(GraphitronLspDescriptor(project))`.
    - `GraphitronLspDescriptor : ProjectWideLspServerDescriptor` — overrides `isSupportedFile` (same extension filter) and `createCommandLine`. The command line is `java -jar <unpacked-bridge-path> --port <port>` where the bridge is unpacked once per session from `bridge.jar` to a temp directory (`PathManager.getSystemPath().resolve("graphitron/bridge-<version>.jar")`), and the port comes from settings (see below).
    - `GraphitronSettings : PersistentStateComponent<GraphitronSettings.State>` — application-level singleton storing `port: Int = 8487` and a future-proofing `host: String = "127.0.0.1"`. Surfaced in **Settings → Tools → Graphitron** via a `Configurable` with a single port field. No pom-reading in MVP; settings is the source of truth, default matches `DevMojo.DEFAULT_PORT`.
  - `src/main/resources/META-INF/plugin.xml` declaring `<idea-version since-build="241" />` (IntelliJ 2024.1 — the release where the LSP API stabilised out of `@Experimental`), no `until-build` (unbounded; retest on each Ultimate release per the standing policy below), `<depends>com.intellij.modules.lsp</depends>`, `<depends>com.intellij.modules.platform</depends>`, the `LspServerSupportProvider` extension registration, and the `applicationConfigurable` for the settings panel.
  - `src/main/resources/bridge.jar` — populated by Gradle at `processResources` from the Maven build output.

### Open-question resolutions

1. **Port discovery from pom vs. settings panel.** Settings panel only, for MVP. Rationale: reading the consumer pom requires either the Maven plugin (project must have the IntelliJ Maven integration active and the pom indexed) or hand-rolled XML parsing of `<plugin><configuration><port>` plus the `<properties>` block for `<graphitron.dev.port>`. Both add code; both miss the `-Dgraphitron.dev.port=N` command-line override path entirely (a session-scoped override that lives nowhere in the pom). The settings panel is one number the user sets once. Default 8487 covers the overwhelming common case. Pom-discovery is filed as a fast-follow if the settings step becomes a documented friction point.

2. **"No dev server running" UX.** Notification, no auto-launch. When the plugin tries to `ensureServerStarted` and the bridge subprocess exits immediately (socket connect refused), surface an editor-level notification: *"graphitron LSP not reachable on localhost:<port>. Start it with `mvn graphitron:dev` in the project root."* Two notification actions: "Open Settings" (jumps to the Graphitron port field) and "Don't show again for this project" (per-project notification suppression key). Auto-launching `mvn graphitron:dev` ourselves is rejected on the principle that **Maven's classpath and lifecycle are the source of truth, and the editor is a consumer of that source of truth, not an orchestrator of it.** `DevMojo`'s class javadoc names it "the single user-facing entry point for editing graphitron schemas" because that's the surface where build hygiene lives — Maven's classpath resolution, the `-Plocal-db` footgun, the catalog-jar invariant, the reactor-aware classpath roots R99 is fixing. The IntelliJ plugin can't reproduce any of that without re-implementing the Maven side of the dev loop, which is precisely what alternative #4 ("stdio mode in DevMojo itself") was rejected for. Choice of terminal, log destination, JDK selection, and `-Plocal-db` profile-aware-or-not is downstream noise; the principled reason is the layered one above.

3. **Bridge jar location.** New `graphitron-lsp-bridge` module — *not* a resource inside `graphitron-lsp`. Rationale: `graphitron-lsp` depends on `graphitron`, `lsp4j`, `jtreesitter`, and `graphitron-tree-sitter-natives`. The bridge has zero compile dependencies and zero overlap with that surface; bundling it as a resource inside `graphitron-lsp` would force the plugin to drag the entire `graphitron-lsp` dependency closure into its resource jar (or hand-extract a single class out of the jar at build time, which is brittle). Dependency hygiene is the load-bearing reason; the standalone module is also cleanly testable in isolation, which falls out of the same hygiene argument.

4. **`since-build` / `until-build` window and the retest policy.** `since-build = 241` (2024.1 — when the LSP API stabilised out of `@ApiStatus.Experimental`). No `until-build` (unbounded). Retest cadence: on each new Ultimate `major.minor` release, run the manual smoke (open a `.graphqls` file in a consumer project, attach to a running dev mojo, verify diagnostics + hover). Budget ~half a day per JetBrains major release. If the LSP API ships a breaking change, bump `since-build` and document the lower-bound version in the release notes; do not attempt to multi-version the plugin.

### Distribution

Phase 1 (this item): build + sideload `.zip` for internal testing by alf and one or two consumer-team developers. No public artifact, no marketplace listing. Plugin version `0.1.0`.

Phase 2 (separate roadmap item, R213+): custom plugin repository hosted at a Sikt URL (`updatePlugins.xml` + jar). Users add the repo URL under **Settings → Plugins → ⚙ → Manage Plugin Repositories**, then install Graphitron from the IDE's plugin list. This is the path internal-tool plugins typically take and lets us iterate without JetBrains review latency.

Phase 3 (later, contingent on stability + Community parity via the LSP4IJ fast-follow): JetBrains Marketplace listing.

R212 ships Phase 1 only.

## User documentation (first-client check)

Per `workflow.adoc`'s "Plans with a user-visible surface … include a draft of the user docs as the first client of the design," here is the prose that goes into `getting-started.adoc` under the existing "Dev loop" section's editor subsection, plus a sketch of the new `intellij-plugin-release.adoc`. Until this reads simply on its own, the design is wrong.

### Drafted prose for `getting-started.adoc` (new subsection: "IntelliJ users")

> *IntelliJ users.* Install the Graphitron plugin from the Sikt plugin repository (Settings → Plugins → ⚙ → Manage Plugin Repositories → add `https://<sikt-plugin-repo-url>/updatePlugins.xml` → restart). The plugin requires IntelliJ Ultimate 2024.1 or later. Community Edition is not yet supported; an LSP4IJ-based path is planned.
>
> Once the plugin is installed, start the dev loop in a side terminal:
>
> ```bash
> mvn graphitron:dev -Plocal-db
> ```
>
> Open any `.graphqls` file in the project. The plugin auto-attaches to the dev mojo on `localhost:8487` and surfaces diagnostics, hover, completion, and go-to-definition. The dev mojo is the source of truth for the loop's lifecycle — start it before opening files, stop it with Ctrl+C, restart it when you change `pom.xml`. The plugin attaches and reattaches automatically.
>
> If you see a notification "graphitron LSP not reachable on localhost:8487," the dev mojo is not running. Start it as above. If you've overridden the port with `-Dgraphitron.dev.port=N`, set the matching port in **Settings → Tools → Graphitron**.
>
> One plugin, many editors: the dev mojo serves all connected LSP clients off the same warm catalog. Run `mvn graphitron:dev` once, attach IntelliJ and Neovim simultaneously, save in either editor — both see the regenerated diagnostics.

This is roughly four short paragraphs. Friction modes it surfaces:

- **The port-override path is a two-step procedure** (override on the Maven command, mirror in IDE settings) — it reads OK in prose because the override is a *named property* `graphitron.dev.port`, not a magic number. The fast-follow pom-discovery item would collapse this to one step.
- **The "you must start the dev mojo first" instruction is unavoidable** — it's the layered architecture, not a UX bug. Worth checking with alf whether the prose names it clearly enough.
- **The Community gap is documented up front** rather than hidden — readers on Community find out at "requires IntelliJ Ultimate 2024.1 or later" and don't waste time on the install.
- **The notification action prose** ("Open Settings") matches Direction §2; the prose at the notification site has to match the prose on the doc page word-for-word, or a reader pattern-matching from doc to UI loses the thread.

If alf reads this draft and the IntelliJ-users subsection doesn't get a "yes this is the path I'd want my team on" within the first read, the design is wrong and needs to change before implementation — not the prose. (For example: if the side-terminal-mojo prerequisite is a real blocker, that's a design signal pointing toward a Run Configuration or a bundled dev-mojo-launcher action, both of which are currently scoped out.)

### Drafted skeleton for `intellij-plugin-release.adoc`

Sections:

1. *What this document is.* Release plumbing for `graphitron-intellij-plugin`, parallel to `tree-sitter-natives-release.adoc`. Not user-facing — release engineers only.
2. *Build the plugin locally.* `mvn install -f graphitron-rewrite/pom.xml -Plocal-db` (produces bridge jar), then `./gradlew :graphitron-intellij-plugin:buildPlugin` (produces the plugin zip under `graphitron-intellij-plugin/build/distributions/`).
3. *Manual smoke checklist* (acceptance tests §§4-6 above, lifted out of the Spec and into the release doc where they belong long-term).
4. *Sideload-for-internal-testing flow* (Phase 1 distribution): how to share the plugin zip with consumer-team developers; how to capture their feedback.
5. *Custom-repo publication* (Phase 2): hosting `updatePlugins.xml` + jar; bumping `plugin.xml` version; the URL consumers add to IntelliJ.
6. *Marketplace publication* (Phase 3): JetBrains review process, trademark / branding checks, the "signed plugin" requirement (`gradle-intellij-plugin` handles signing if `pluginCertificateChain` and `pluginPrivateKey` are set).
7. *JetBrains release retest policy* (Direction §4 above): which IDE versions to smoke-test against on each major release.
8. *Bridge ABI* (one paragraph): the bridge's command-line surface (`--port`, `--host`) is the contract between the plugin's `createCommandLine()` and the bridge `main`; changing it is a breaking change for any future plugin that ships an older bundled bridge against a newer dev mojo, or vice versa.

The acceptance-test §§4-6 prose currently lives in the Spec but logically belongs in the release doc; treat the Spec entries as the contractual specification and the release-doc entries as the human-runnable checklist that derives from them.

## Acceptance tests

Bridge tests carry `@UnitTier` (consistent with `testing.adoc`'s "structural invariants on builders / catalogs / writers" framing — the bridge is a small standalone helper, not a pipeline-tier behaviour). The tier-enforcement walk is per-module by construction: `graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/internal/TierAnnotationEnforcementTest.java` scans its own module's `target/test-classes` and asserts every test class carries exactly one tier annotation, and `graphitron` has a sibling copy doing the same for its own classpath (per `testing.adoc` § "every in-scope module" rule). The new `graphitron-lsp-bridge` module ships its own sibling copy of `TierAnnotationEnforcementTest` under `graphitron-lsp-bridge/src/test/java/no/sikt/graphitron/lsp/bridge/internal/`, consuming the `@UnitTier` / `@PipelineTier` / `@CompilationTier` / `@ExecutionTier` meta-annotations from `graphitron`'s tests-jar (the same path `graphitron-sakila-example` uses). Without that copy the bridge module would tacitly opt out of the "every test carries a tier identity" invariant.

1. **`@UnitTier` — Bridge: stdin-to-socket and socket-to-stdout copy a full LSP `initialize` exchange byte-for-byte.** Unit test in `graphitron-lsp-bridge`. Spin up a loopback `ServerSocket` on an ephemeral port, write a captured `initialize` request frame onto a `PipedOutputStream` connected to the bridge's `System.in` substitute, read from the bridge's `System.out` substitute, assert the bytes match what the server socket echoed back. Both directions are covered in one test (the bridge runs both copy threads regardless of direction).

2. **`@UnitTier` — Bridge: clean EOF on either side closes both threads and exits 0.** Close the loopback server socket, assert the bridge `main` returns within 1s; close `System.in` instead, assert the same. Covers the two natural shutdown shapes (editor exits, dev mojo exits).

3. **`@UnitTier` — Bridge: socket-refused at startup exits nonzero within 500ms.** Point the bridge at an unused port, assert it exits with a nonzero status quickly enough that the IntelliJ "server crashed" notification fires (the platform's LSP machinery treats fast-exit as the trigger). Pins the "no dev server running" UX path.

4. **Plugin: smoke test against a running `mvn graphitron:dev`.** Manual test in `graphitron-rewrite/docs/intellij-plugin-release.adoc` checklist (no automated IDE smoke; IntelliJ test infrastructure is heavy for this surface). Steps: (a) install plugin zip via *Install Plugin from Disk*; (b) open `graphitron-sakila-example`; (c) start `mvn graphitron:dev -Plocal-db` in a side terminal; (d) open `schema.graphqls`; (e) verify a deliberate typo (`@servicee`) renders the `@service` typo diagnostic with the same prose the dev mojo's console emits; (f) verify hover over a `@table` argument renders the table description.

5. **Plugin: "no dev server" notification fires when port is unused.** Manual test, same checklist. Skip step (c) above; verify the notification appears with the documented prose and that the "Open Settings" action lands on the Graphitron port field.

6. **Smoke checklist: port-mismatch survives a settings change.** *Checklist-style smoke, not a contract the plugin owns.* With dev mojo running on `-Dgraphitron.dev.port=9000`, set the plugin's port to 8487 (default), verify the no-server notification; change the plugin's port to 9000 in Settings, verify the LSP attaches without an IDE restart. The behaviour under test ("`LspServerManager` restarts the server on descriptor changes") is the IntelliJ Platform's contract, not graphitron's — if the platform regresses, the test catches it, but we're not the owner. Kept in the checklist because humans verifying that settings work end-to-end is genuine value; not a regression ratchet on our codebase.

No unit test for plugin-side code paths in MVP — the platform `LspServer*` types are testable only via `BasePlatformTestCase`, which requires a full IntelliJ test fixture, and the MVP plugin surface is thin enough (one provider, one descriptor, one settings class, ~150 lines total) that manual smoke covers it without ratchet cost. Adding `BasePlatformTestCase` coverage to assert "the IntelliJ platform behaves as documented" would also be testing the platform, not us. If the plugin grows non-trivial logic (pom discovery, auto-restart heuristics, port-conflict recovery), revisit and add `BasePlatformTestCase` coverage at that point — the threshold is "this Kotlin code makes a decision a manual test can't reliably verify."

## Out of scope (deferred to fast-follow items)

- **Community Edition support via LSP4IJ.** Tracked as a future Backlog item; same bridge jar, second `plugin.xml` extension, runtime dependency on LSP4IJ. Ship after Phase 1 internal smoke validates the bridge shape against real schemas.
- **Pom-based port discovery.** Tracked as a Backlog candidate, contingent on Phase 1 user feedback. If the settings step is real friction, read `graphitron.dev.port` from the consumer pom; otherwise leave it as a settings field.
- **Marketplace publication.** Phase 3. Requires LSP4IJ parity (Community is half the audience) plus internal validation against ≥3 consumer projects.
- **Auto-launching `mvn graphitron:dev` from the plugin.** Explicitly rejected per Direction §2. Not a fast-follow either; the plugin attaches, it does not orchestrate.
- **Custom Graphitron-specific actions, gutter icons, or completion UI.** The platform's LSP machinery already maps diagnostics, completions, hover, definitions, and code actions to IDE surfaces; adding bespoke UI duplicates that mapping and locks us into the platform's specific UI types instead of LSP's transport-neutral protocol. Revisit only if a graphitron-specific signal does not fit any LSP feature.

## References

- `graphitron-rewrite/graphitron-maven-plugin/src/main/java/no/sikt/graphitron/rewrite/maven/DevMojo.java:62-70` — the `port` parameter and `LOOPBACK_HOST` constants the plugin's settings default mirrors. `graphitron.dev.port` is the canonical override property.
- `graphitron-rewrite/graphitron-maven-plugin/src/main/java/no/sikt/graphitron/rewrite/maven/dev/DevServer.java:95-115` — the per-connection `serve()` method the bridge effectively drives. It constructs a fresh `GraphitronLanguageServer` per connection backed by the constructor-supplied shared `Workspace` (see the constructor doc comment at lines 49-57), so multi-attach (one IntelliJ + one Neovim against the same dev mojo) works out of the box.
- `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/server/Launcher.java:14-25` — the *non*-candidate stdio entry point. Cold LSP, no shared workspace, no watchers; the bridge must hit `DevServer`'s socket, not this main.
- `graphitron-rewrite/docs/getting-started.adoc` § "Dev loop" — the existing editor-side documentation surface; R212 ships an "IntelliJ users:" subsection pointing at the plugin install path.
- `graphitron-rewrite/graphitron-tree-sitter-natives/pom.xml` — the precedent for a standalone module published with its own release cadence. R212's `graphitron-lsp-bridge` stays inside the reactor (no release-cadence pressure) but follows the same "small standalone purpose-built module" shape.
- JetBrains IntelliJ Platform LSP API: `com.intellij.platform.lsp` (Ultimate, 2023.2+; stabilised 2024.1). `LspServerSupportProvider`, `ProjectWideLspServerDescriptor`, `LspServerStarter`.
- LSP4IJ (Red Hat): the Community-edition path. Plugin id `com.redhat.devtools.lsp4ij`; `ServerDefinition` extension point.
- R203 (`publish-tree-sitter-natives-jar`), In Progress — the natives jar the dev mojo already depends on. R212 has no compile-time dependency on R203 but the Phase 1 smoke checklist (acceptance test #4) needs an R203-shipped JDK environment to be reproducible on macOS/Windows.
- R99 (`lsp-submodule-sibling-classpath`), Backlog — orthogonal classpath-scan fix; surfaces under the same multi-module shapes the IntelliJ plugin will mostly see in practice, but the failure mode (empty completions for sibling-module classes) is unrelated to R212's transport work.
- Internal user report (alf): IntelliJ developers consistently abandon the LSP setup at the "configure TCP server in LSP4IJ" step; the plugin is the documented fix.
