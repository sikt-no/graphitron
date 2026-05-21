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

5. **Custom JetBrains plugin reimplementing the LSP semantics natively.** The path the JetBrains GraphQL plugin took — no LSP at all; reuse the in-process generator and drive editor surfaces directly. Rejected: order-of-magnitude more code than (2), duplicates everything `graphitron-lsp` already does (parsing, completions, hover, definitions, diagnostics, inlay hints), and forks the LSP and IDE codebases. Re-evaluate only if the platform LSP API regresses on something graphitron needs.

The chosen shape (1) minimises code and preserves the warm-JVM contract; (3) is the natural fast-follow once (1) is in users' hands and validated against real schemas.

## Direction

### Modules

Two new modules under `graphitron-rewrite/`:

- **`graphitron-lsp-bridge`** (Maven, child of `graphitron-rewrite-parent`). Single class `no.sikt.graphitron.lsp.bridge.StdioBridge` with a `main(String[] args)` that:
  - parses `--port <int>` (default 8487) and `--host <str>` (default `127.0.0.1`);
  - opens a `Socket` to that endpoint;
  - launches two daemon threads: `System.in.transferTo(socket.getOutputStream())` and `socket.getInputStream().transferTo(System.out)`;
  - exits with status 0 when either copy returns (clean EOF) and nonzero on `IOException`.

  `<release>17</release>` (same target Java floor as generated output; the bridge has no Java-25 surface). Zero compile dependencies; JDK 17's `InputStream.transferTo` is sufficient. Produces a shaded executable jar via `maven-shade-plugin`'s `Main-Class` manifest entry, so `java -jar` is enough; no classpath argument required. Sits inside the main reactor so a normal `mvn install -f graphitron-rewrite/pom.xml -Plocal-db` builds it.

- **`graphitron-intellij-plugin`** (Gradle, *not* in the Maven reactor). The IntelliJ Platform Plugin SDK is Gradle-only in practice; mixing it into the Maven reactor via `gradle-maven-plugin` invites a class of build failures the project has not signed up for. The Gradle module reads the bridge jar from a fixed relative path (`../graphitron-lsp-bridge/target/graphitron-lsp-bridge-<version>-shaded.jar`) and copies it into the plugin's `src/main/resources/bridge.jar` at package time. `mvn install -f graphitron-rewrite/pom.xml -Plocal-db` produces the bridge; `./gradlew :graphitron-intellij-plugin:buildPlugin` (run separately) produces the plugin zip. Documented as a two-step build in `graphitron-rewrite/docs/getting-started.adoc` and in a new `intellij-plugin-release.adoc` modeled on `tree-sitter-natives-release.adoc`.

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

2. **"No dev server running" UX.** Notification, no auto-launch. When the plugin tries to `ensureServerStarted` and the bridge subprocess exits immediately (socket connect refused), surface an editor-level notification: *"graphitron LSP not reachable on localhost:<port>. Start it with `mvn graphitron:dev` in the project root."* Two notification actions: "Open Settings" (jumps to the Graphitron port field) and "Don't show again for this project" (per-project notification suppression key). Auto-launching `mvn graphitron:dev` ourselves is rejected: choice of terminal, log destination, JDK selection, and `-Plocal-db` profile-aware-or-not is all consumer-specific. The dev mojo is a deliberate side-terminal long-running process; the plugin attaches to it, the plugin does not own its lifecycle.

3. **Bridge jar location.** New `graphitron-lsp-bridge` module — *not* a resource inside `graphitron-lsp`. Rationale: `graphitron-lsp` depends on `graphitron`, `lsp4j`, `jtreesitter`, and `graphitron-tree-sitter-natives`. The bridge has zero compile dependencies and zero overlap with that surface; bundling it as a resource inside `graphitron-lsp` would force the plugin to drag the entire `graphitron-lsp` dependency closure into its resource jar (or hand-extract a single class out of the jar at build time, which is brittle). A standalone module also makes the bridge separately testable and separately publishable if a future consumer wants stdio bridging without an IntelliJ plugin.

4. **`since-build` / `until-build` window and the retest policy.** `since-build = 241` (2024.1 — when the LSP API stabilised out of `@ApiStatus.Experimental`). No `until-build` (unbounded). Retest cadence: on each new Ultimate `major.minor` release, run the manual smoke (open a `.graphqls` file in a consumer project, attach to a running dev mojo, verify diagnostics + hover). Budget ~half a day per JetBrains major release. If the LSP API ships a breaking change, bump `since-build` and document the lower-bound version in the release notes; do not attempt to multi-version the plugin.

### Distribution

Phase 1 (this item): build + sideload `.zip` for internal testing by alf and one or two consumer-team developers. No public artifact, no marketplace listing. Plugin version `0.1.0`.

Phase 2 (separate roadmap item, R213+): custom plugin repository hosted at a Sikt URL (`updatePlugins.xml` + jar). Users add the repo URL under **Settings → Plugins → ⚙ → Manage Plugin Repositories**, then install Graphitron from the IDE's plugin list. This is the path internal-tool plugins typically take and lets us iterate without JetBrains review latency.

Phase 3 (later, contingent on stability + Community parity via the LSP4IJ fast-follow): JetBrains Marketplace listing.

R212 ships Phase 1 only.

## Acceptance tests

1. **Bridge: stdin-to-socket and socket-to-stdout copy a full LSP `initialize` exchange byte-for-byte.** Unit test in `graphitron-lsp-bridge`. Spin up a loopback `ServerSocket` on an ephemeral port, write a captured `initialize` request frame onto a `PipedOutputStream` connected to the bridge's `System.in` substitute, read from the bridge's `System.out` substitute, assert the bytes match what the server socket echoed back. Both directions are covered in one test (the bridge runs both copy threads regardless of direction).

2. **Bridge: clean EOF on either side closes both threads and exits 0.** Unit test. Close the loopback server socket, assert the bridge `main` returns within 1s; close `System.in` instead, assert the same. Covers the two natural shutdown shapes (editor exits, dev mojo exits).

3. **Bridge: socket-refused at startup exits nonzero within 500ms.** Unit test. Point the bridge at an unused port, assert it exits with a nonzero status quickly enough that the IntelliJ "server crashed" notification fires (the platform's LSP machinery treats fast-exit as the trigger). Pins the "no dev server running" UX path.

4. **Plugin: smoke test against a running `mvn graphitron:dev`.** Manual test in `graphitron-rewrite/docs/intellij-plugin-release.adoc` checklist (no automated IDE smoke; IntelliJ test infrastructure is heavy for this surface). Steps: (a) install plugin zip via *Install Plugin from Disk*; (b) open `graphitron-sakila-example`; (c) start `mvn graphitron:dev -Plocal-db` in a side terminal; (d) open `schema.graphqls`; (e) verify a deliberate typo (`@servicee`) renders the `@service` typo diagnostic with the same prose the dev mojo's console emits; (f) verify hover over a `@table` argument renders the table description.

5. **Plugin: "no dev server" notification fires when port is unused.** Manual test, same checklist. Skip step (c) above; verify the notification appears with the documented prose and that the "Open Settings" action lands on the Graphitron port field.

6. **Plugin: port-mismatch survives a settings change.** Manual test. With dev mojo running on `-Dgraphitron.dev.port=9000`, set the plugin's port to 8487 (default), verify the no-server notification; change the plugin's port to 9000 in Settings, verify the LSP attaches without an IDE restart (the platform's `LspServerManager` restarts the server on descriptor changes).

No unit test for plugin-side code paths in MVP — the platform `LspServer*` types are testable only via `BasePlatformTestCase`, which requires a full IntelliJ test fixture, and the MVP plugin surface is thin enough (one provider, one descriptor, one settings class, ~150 lines total) that manual smoke covers it without ratchet cost. If the plugin grows non-trivial logic (pom discovery, auto-restart heuristics), revisit and add `BasePlatformTestCase` coverage at that point.

## Out of scope (deferred to fast-follow items)

- **Community Edition support via LSP4IJ.** Tracked as a future Backlog item; same bridge jar, second `plugin.xml` extension, runtime dependency on LSP4IJ. Ship after Phase 1 internal smoke validates the bridge shape against real schemas.
- **Pom-based port discovery.** Tracked as a Backlog candidate, contingent on Phase 1 user feedback. If the settings step is real friction, read `graphitron.dev.port` from the consumer pom; otherwise leave it as a settings field.
- **Marketplace publication.** Phase 3. Requires LSP4IJ parity (Community is half the audience) plus internal validation against ≥3 consumer projects.
- **Auto-launching `mvn graphitron:dev` from the plugin.** Explicitly rejected per Direction §2. Not a fast-follow either; the plugin attaches, it does not orchestrate.
- **Custom Graphitron-specific actions, gutter icons, or completion UI.** The platform's LSP machinery already maps diagnostics, completions, hover, definitions, and code actions to IDE surfaces; adding bespoke UI duplicates that mapping and locks us into the platform's specific UI types instead of LSP's transport-neutral protocol. Revisit only if a graphitron-specific signal does not fit any LSP feature.

## References

- `graphitron-rewrite/graphitron-maven-plugin/src/main/java/no/sikt/graphitron/rewrite/maven/DevMojo.java:62-70` — the `port` parameter and `LOOPBACK_HOST` constants the plugin's settings default mirrors. `graphitron.dev.port` is the canonical override property.
- `graphitron-rewrite/graphitron-maven-plugin/src/main/java/no/sikt/graphitron/rewrite/maven/dev/DevServer.java:55-77` — the TCP server the bridge connects to. `serve()` constructs a fresh `GraphitronLanguageServer` per connection backed by a shared `Workspace`, so multi-attach (one IntelliJ + one Neovim against the same dev mojo) works out of the box.
- `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/server/Launcher.java:14-25` — the *non*-candidate stdio entry point. Cold LSP, no shared workspace, no watchers; the bridge must hit `DevServer`'s socket, not this main.
- `graphitron-rewrite/docs/getting-started.adoc` § "Dev loop" — the existing editor-side documentation surface; R212 ships an "IntelliJ users:" subsection pointing at the plugin install path.
- `graphitron-rewrite/graphitron-tree-sitter-natives/pom.xml` — the precedent for a standalone module published with its own release cadence. R212's `graphitron-lsp-bridge` stays inside the reactor (no release-cadence pressure) but follows the same "small standalone purpose-built module" shape.
- JetBrains IntelliJ Platform LSP API: `com.intellij.platform.lsp` (Ultimate, 2023.2+; stabilised 2024.1). `LspServerSupportProvider`, `ProjectWideLspServerDescriptor`, `LspServerStarter`.
- LSP4IJ (Red Hat): the Community-edition path. Plugin id `com.redhat.devtools.lsp4ij`; `ServerDefinition` extension point.
- R203 (`publish-tree-sitter-natives-jar`), In Progress — the natives jar the dev mojo already depends on. R212 has no compile-time dependency on R203 but the Phase 1 smoke checklist (acceptance test #4) needs an R203-shipped JDK environment to be reproducible on macOS/Windows.
- R99 (`lsp-submodule-sibling-classpath`), Backlog — orthogonal classpath-scan fix; surfaces under the same multi-module shapes the IntelliJ plugin will mostly see in practice, but the failure mode (empty completions for sibling-module classes) is unrelated to R212's transport work.
- Internal user report (alf): IntelliJ developers consistently abandon the LSP setup at the "configure TCP server in LSP4IJ" step; the plugin is the documented fix.
