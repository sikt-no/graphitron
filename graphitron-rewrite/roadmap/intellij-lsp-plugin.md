---
id: R212
title: "IntelliJ plugin wrapping graphitron:dev LSP"
status: Backlog
bucket: feature
theme: lsp
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# IntelliJ plugin wrapping graphitron:dev LSP

`mvn graphitron:dev` binds the LSP on a TCP port (`DevServer` at `localhost:8487`), which is the right shape for a long-lived warm JVM that survives editor restarts, but it is the wrong shape for IntelliJ. IntelliJ Ultimate's built-in LSP API (`com.intellij.platform.lsp`, since 2023.2) only spawns a subprocess and speaks JSON-RPC over its stdin/stdout; it has no first-class TCP transport. IntelliJ Community has no built-in LSP at all. The current editor instructions in `getting-started.adoc` ("point your LSP client at `localhost:8487` (TCP)") therefore work for Neovim / Helix / VS Code but degrade to "install LSP4IJ and configure a TCP server yourself" for IntelliJ users, which is enough friction that several internal users have reported it as a blocker. A stdio↔TCP bridge launched by a `java -jar` command (the alternative considered in this thread) still leaves the user to source the bridge jar and write IDE config.

The fix is a thin IntelliJ plugin that ships the bridge jar inside its own resources and registers it with the platform's LSP machinery, so the user-visible install is "install the plugin, open a `.graphqls` file, done." The MVP targets Ultimate via a single `LspServerSupportProvider` + `ProjectWideLspServerDescriptor` pair (≈100 lines Kotlin); the descriptor's `createCommandLine()` returns `java -jar <unpacked-bridge> --port <discovered-from-pom>`, and the bridge is a 30-50 line `Socket` ↔ stdin/stdout copier. Discovery reads `graphitron.dev.port` from the consumer's effective pom (default 8487). A Community-edition fast-follow registers the same provider against LSP4IJ's `ServerDefinition` extension point — same plugin, two entry points — but is out of scope for the first ship. The plugin lives as a sibling Gradle module under `graphitron-rewrite/` (the IntelliJ Platform Plugin SDK is Gradle-only in practice) consuming a Maven-built bridge jar via the local repo. Distribution starts on a custom plugin repository (an `updatePlugins.xml` + jar at a Sikt-hosted URL) so we can iterate before submitting to the JetBrains Marketplace. The Spec body should pin down: (1) port discovery from `pom.xml` vs. user settings panel, (2) "no dev server running" UX — surface a notification with a one-click `mvn graphitron:dev` launch or leave it to the user, (3) bridge jar location (own Maven module vs. resource inside `graphitron-lsp`), (4) `since-build` / `until-build` window and the policy for re-testing on each Ultimate release.
