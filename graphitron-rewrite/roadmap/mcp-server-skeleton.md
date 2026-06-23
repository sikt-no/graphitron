---
id: R341
title: "MCP server skeleton: Streamable HTTP in graphitron:dev with a static about-prompt"
status: Spec
bucket: feature
theme: lsp
depends-on: []
created: 2026-06-19
last-updated: 2026-06-23
---

# MCP server skeleton: Streamable HTTP in graphitron:dev with a static about-prompt

Onboarding to graphitron means absorbing a lot of implicit context at once: that the schema-authoring loop runs under `mvn graphitron:dev`, that an LSP is bound on a loopback port, what the directives mean, and where the docs live. An agent (Claude Code, Cursor) sitting alongside a developer has none of this unless the developer pastes it in. This item delivers the smallest useful MCP server that closes that gap and, more importantly, establishes the transport and lifecycle foundation that the live-data discovery tools in R118 build on. It ships static content only; the value is proving the integration end to end so the substantive tools land on a known-good seam.

This Spec resolves the three open questions the Backlog item left for Spec (module placement, embedded HTTP host + coordinates, content shape), records the narrow principle surface the work touches, and promotes one robustness item (fail-fast on a taken bind port) out of Deferred into scope on the reasoning given below.

## Scope

- An MCP server embedded in the long-running `graphitron:dev` JVM, hosted over the MCP Streamable HTTP transport from the official MCP Java SDK on a lightweight embedded servlet container. stdio is unusable in this process because Maven (and, under `quarkus:dev`, Quarkus) write freely to stdout, which the stdio transport cannot share; this is the same constraint that pushed the LSP to a socket transport.
- Bound on a loopback address (`127.0.0.1`), matching the LSP's posture, on a hard-coded port `8488` (distinct from the LSP's `8487`). A configurable or overridable port is deliberately out of scope for the skeleton; see Deferred. A taken `8488` fails fast with a clear message (see Implementation), so the hard-coding does not degrade the failure contract below the LSP's.
- The server exposes static content only: the `instructions` string returned in the MCP initialize handshake (always-on ambient context: what graphitron is, that the dev loop and LSP are running), plus a single `about` prompt (an on-demand explainer of the project and the dev loop, surfaced as a slash command in MCP-aware clients).
- Lifecycle: the server is a sibling of `DevServer`. It is constructed in `DevMojo.bindServer` alongside the LSP server, shares the one JVM, and is closed in `DevMojo.cleanup`. It does not read the live `Workspace` in this item.

## Decisions (resolving the Spec open questions)

### 1. Module placement: a dedicated `graphitron-mcp` module

The MCP server logic and its embedded HTTP hosting live in a new Maven module `graphitron-mcp` (child of `graphitron-rewrite-parent`). `graphitron-maven-plugin` gains a compile-scope dependency on it and does only the lifecycle wiring (construct in `bindServer`, close in `cleanup`).

The load-bearing reason is **forward-looking dependency quarantine for R118, not transport symmetry with the LSP**. R118 adds heavy native dependencies (an ONNX in-process embedder plus a Lucene-or-DuckDB vector store). Those must not live in `graphitron-maven-plugin`'s own compile surface; a dedicated module is the seam that holds them, and the skeleton seeds it. This is the "Fewer runtime dependencies mean fewer things to break" half of *Stability through simplicity* (`docs/graphitron-principles.adoc`) and *Separate business logic from API code*.

A note on the LSP precedent, so the reasoning is recorded honestly rather than over-claimed: `graphitron-lsp` is a dedicated module while the socket glue (`DevServer`) lives in `graphitron-maven-plugin`. That split is *transport/lifecycle in the plugin, request-handling logic in the module*. The MCP case does **not** mirror it on the transport axis: the SDK's `HttpServletStreamableServerTransportProvider` plus embedded Jetty *is* the analogue of `DevServer`'s hand-rolled socket plumbing, and it is library code, not ours to split out. So the module is justified on the *dependency-quarantine and publishable-surface* axis the LSP split also serves, not on transport symmetry. If R118 did not exist, folding the static content plus a Jetty handle next to `DevServer` would be the simpler call.

Two consequences to record:

- **Publishable surface.** `graphitron-maven-plugin` is published, and a Maven plugin's declared dependencies are resolved from the consumer's repositories when the plugin executes. So `graphitron-mcp` must be published exactly like `graphitron-lsp`; it cannot carry `maven.deploy.skip`, or the plugin fails to resolve at every consumer. There is no mechanical check on the publish config (the publishable surface is README.adoc prose plus the release workflow, not an assertion), so this stays review-enforced; `graphitron-rewrite/scripts/verify-standalone-build.sh` exercises the new module only as a reactor member that must pull no forbidden legacy coords.
- **The skeleton's added mass, and R118's.** The skeleton adds only the MCP SDK plus Jetty (~2-3 MB) to the plugin's transitive tree. That tree is dragged into *every* plugin invocation (`generate-sources`, `validate`, `dev`), not just `dev`, because Maven resolves a plugin's full dependency closure regardless of goal. Modest for Jetty; **not** modest for R118's ONNX/vector-store mass. Keeping R118's heavy deps off the non-`dev` goals' classpath (optional deps + reflective load, or a separate plugin) is **R118's decision, flagged here so it is picked up there**, not solved by the module boundary alone. R341 does not pretend to solve it.

Module name `graphitron-mcp` matches the existing `graphitron-lsp` / `graphitron-javapoet` naming. Compiles at `<release>25</release>` per the parent pom.

### 2. Embedded HTTP host and SDK coordinates

Host the SDK's servlet-based Streamable HTTP transport provider (`HttpServletStreamableServerTransportProvider`, a `jakarta.servlet` `HttpServlet`) in **embedded Jetty 12 (EE10 / jakarta.servlet 6)**. Lifecycle is a clean `Server.start()` / `Server.stop()` that maps onto `AutoCloseable`, it is pure Java with no native dependency, and the EE10 jakarta namespace matches the SDK's servlet API.

This is the same decision as the "is the skeleton worth it" question (§ Principle surface): the skeleton's stated job is to prove the seam R118 lands on, so it must prove the seam R118 *actually uses*. R118's tools are ordinary request/response MCP tools hosted in this same dev JVM; the servlet-in-Jetty path is the one they will use. A hand-rolled adapter over the JDK's `com.sun.net.httpserver.HttpServer` was considered and rejected: it trades a dependency for bespoke request-path code we own and debug forever (the wrong side of the *Stability through simplicity* trade), and no server-side Streamable HTTP provider for `com.sun.net.httpserver` is confirmed in the SDK, so it would prove a throwaway seam.

Coordinates, pinned in the parent pom's `dependencyManagement` (confirm the exact patch is still current at implementation time):

- MCP Java SDK: import `io.modelcontextprotocol.sdk:mcp-bom` version `2.0.0` (released 2026-06-11, the first major since 1.x; the 2.x line tracks the 2025-11-25 MCP spec). Depend on `io.modelcontextprotocol.sdk:mcp` without a version. The core `mcp` artifact ships the servlet Streamable HTTP **server** transport (`HttpServletStreamableServerTransportProvider`, Servlet 6.0 async) with no web-framework dependency. Note the 2.x split, so the wrong artifact is not pulled: the Spring transports (`mcp-spring-webflux` / `mcp-spring-webmvc`) moved out to `org.springframework.ai` in 2.0; the servlet provider this skeleton needs stays in core `mcp`. The provider's builder takes a JSON mapper (Jackson-backed by default), pulled in transitively through core `mcp`; confirm the exact mapper type against the 2.0.0 builder signature at implementation time.
- Jetty 12: import `org.eclipse.jetty:jetty-bom`, depend on `org.eclipse.jetty.ee10:jetty-ee10-servlet` (brings `jetty-server` and `jakarta.servlet-api` 6.x transitively).
- `org.slf4j:slf4j-api` for logging, matching `DevServer`'s slf4j use.

Servlet-in-Jetty is the resolved transport, not a default pending a cheaper alternative. As of SDK 2.0.0 the confirmed Streamable HTTP *server* transport is the servlet provider above; there is no self-contained `com.sun.net.httpserver` server provider in the SDK to prefer over it (a hand-rolled adapter over the JDK server was considered and rejected in the paragraph above for the same reason). This is also the seam R118's servlet-hosted request/response tools will use, so the skeleton proves the seam R118 actually lands on rather than a throwaway one.

The exact Jetty 12 EE10 artifact set is finicky about the EE10/jakarta vs EE8/javax split; pin against `graphitron-rewrite/pom.xml` with coordinates, not prose, and confirm the `jakarta.servlet` 6.x alignment with the SDK's `HttpServlet` base class.

### 3. Static content shape

- **`instructions`** (returned in the MCP `initialize` result): a short, always-on ambient string. Content: graphitron is a GraphQL-schema-plus-jOOQ-to-Java resolver generator; this server runs inside a live `mvn graphitron:dev` session; an LSP is bound on a loopback port for schema authoring; the `about` prompt and the docs are where to go for more. Keep it to a few sentences; it is prepended to the client's context on every session, so length is a cost. The bundled string deliberately does **not** name the LSP's port number: the LSP port is user-overridable (`-Dgraphitron.dev.port=N`), so a hard-coded `8487` baked into a static resource would become an assertable-false claim the moment a user overrides it, and the LSP port is not something an MCP agent acts on anyway (the agent connects to the MCP port, not the LSP). Describing the LSP as "a loopback port" stays true under any override, so this string needs no deferred machinery to stay honest, unlike the project-identity stamp in Deferred. The MCP port the agent actually connects to is fixed (`8488`), so naming it precisely in the startup log and `.mcp.json` carries no such risk.
- **`about`** prompt: a single prompt with **no arguments**, returning one prose explainer message about the project and the dev loop. No parameters keeps the skeleton's surface minimal and the slash command zero-friction; a parameterised explainer earns its arguments only when there is something to vary. MCP-aware clients surface it as a slash command (Claude Code: `/mcp__graphitron__about`).
- Both strings load once at startup from bundled jar resources under `graphitron-mcp/src/main/resources/mcp/` (`instructions.txt`, `about.md`), mirroring `LspVocabulary`'s "shape, not state, read once at startup" posture (`graphitron-lsp/.../LspVocabulary.java:52-53`, `:86-88`). Editing the prose is a resource edit, not a logic change.

## Implementation

- **`graphitron-rewrite/pom.xml`**: add `<module>graphitron-mcp</module>` (before `graphitron-maven-plugin`). Add version properties and import `mcp-bom` + `jetty-bom` in `dependencyManagement` per Decision 2.
- **`graphitron-mcp/pom.xml`**: dependencies `io.modelcontextprotocol.sdk:mcp`, `org.eclipse.jetty.ee10:jetty-ee10-servlet`, `org.slf4j:slf4j-api`; test deps `junit-jupiter`, `assertj-core`, `logback-classic`, and the SDK's HttpClient-based Streamable HTTP **client** transport for the integration test. The skeleton depends on **neither** `graphitron` nor `graphitron-lsp` (it serves static content only); R118 adds the live-model dependency when it wires catalog/schema tools.
- **`graphitron-mcp/src/main/resources/mcp/instructions.txt`, `about.md`**: the static prose from Decision 3.
- **`graphitron-mcp/.../GraphitronMcpServer.java`**: `public final class GraphitronMcpServer implements AutoCloseable`, mirroring `DevServer`'s shape. Constructor takes an `InetSocketAddress` (so production passes `127.0.0.1:8488` and the test binds an ephemeral port), builds the `HttpServletStreamableServerTransportProvider` on the `/mcp` endpoint path, builds an `McpSyncServer` registering `serverInfo` (name `graphitron` to match the client-config server key; version read from the jar manifest's implementation version, falling back to a `"dev"` literal when absent so the required `serverInfo.version` is never null while the server runs from `target/classes` under the tests or an IDE. The version is cosmetic here (it drives nothing), so the fallback is deliberately trivial rather than a build-stamped constant; the dynamic project-identity stamp stays Deferred), the `instructions` string, and the `about` prompt, mounts the provider servlet in an embedded Jetty `Server` at `/mcp`, and starts it. Exposes `port()` (the bound local port, for ephemeral-port tests and the startup log). `close()` stops Jetty and closes the MCP server. A failed bind surfaces as an `IOException` (Jetty wraps the underlying `BindException`); the constructor translates/propagates it so the caller can produce the Mojo message, exactly as `DevServer` does.
- **`DevMojo.bindServer`** (`DevMojo.java:166-177`): after the LSP `DevServer` is constructed, construct `GraphitronMcpServer` on `127.0.0.1` and the MCP port. That port lives in a package-private `int mcpPort` field defaulting to a `DEFAULT_MCP_PORT = 8488` constant (mirroring `DEFAULT_PORT`/`port`), but **not** a `@Parameter`: it stays non-overridable for users (the configurable port is Deferred) while letting `DevMojoTest` inject an ephemeral taken port instead of binding the well-known 8488. On bind failure, translate to `MojoExecutionException` with a clear message ("graphitron:dev: MCP port 8488 is already in use ...") mirroring the existing LSP arm, and **close the already-constructed LSP `DevServer` before rethrowing** so a partial bind does not leak the LSP socket. Hold the server in a field and close it in **`DevMojo.cleanup`** (`DevMojo.java:376-384`) alongside `server.close()`. So the bind-failure test can assert no LSP-socket leak, relax `DevMojo.server` from `private` to package-private and add a package-private `DevServer.isClosed()` backed by the existing `closed` `AtomicBoolean` (`DevServer.java:47`).
- **`DevMojo.execute`**: extend the existing startup log line (`DevMojo.java:152-153`) with a second line naming the MCP URL and a copy-pasteable `claude mcp add --transport http graphitron http://127.0.0.1:8488/mcp`. No config file is generated or mutated at startup.
- **`graphitron-sakila-example/.mcp.json`**: committed, static, hard-coding `http://127.0.0.1:8488/mcp`:
  ```json
  { "mcpServers": { "graphitron": { "type": "http", "url": "http://127.0.0.1:8488/mcp" } } }
  ```
  This is the worked example a developer clones rather than re-adding per session, and it is the live artifact the user docs and the startup log line name (per *Documentation names only live tests/code*).
- **A note on the four `8488` copies.** The literal appears in four places that must agree: `DEFAULT_MCP_PORT` (the Java source of truth), the startup log line (derived from the field at runtime, so it tracks automatically), this committed `.mcp.json`, and the user docs. `DEFAULT_MCP_PORT` itself is pinned to 8488 by a `DevMojoTest` assertion (see Tests), mirroring the existing `DEFAULT_PORT == 8487` pin, so the source of truth cannot silently drift from the design. The two static copies (`.mcp.json`, docs) still cannot derive from the constant and so can drift if the default ever changes. The skeleton accepts that, because the default never changes; the deferred single-knob `GRAPHITRON_MCP_PORT` is what later collapses the static copies onto one resolved value. Flagged here, not solved here.

## Tests

The first three are infrastructure-tier integration tests, modelled on `DevServerTest` (`graphitron-maven-plugin/.../dev/DevServerTest.java`), living in `graphitron-mcp/src/test/`. They boot a real `GraphitronMcpServer` on an **ephemeral port** (`InetSocketAddress` with port 0; never the hard-coded 8488, or parallel CI runs of the dev goal collide) and drive it with the SDK's own MCP client over loopback HTTP:

- **`initialize` returns the instructions string.** Connect an MCP client, complete the handshake, assert the `instructions` field carries the bundled ambient text.
- **`prompts/list` advertises `about`; `prompts/get` returns the explainer.** Assert `about` is listed with no arguments, and that fetching it returns the bundled explainer message.
- **Bind failure on a taken port (server level).** Mirror `DevServerTest.bindingTakenPortFailsWithIoException` (`DevServerTest.java:99-106`): bind once, attempt a second bind on the same port, assert the `IOException` the constructor documents. This pins the low-level exception contract `GraphitronMcpServer` exposes; the user-facing Mojo message contract is the separate `DevMojoTest` case below.

Two further tests land in `graphitron-maven-plugin`'s `DevMojoTest` (not `graphitron-mcp`), because the behaviour under test is the Mojo's wiring and its design constants, not the server in isolation:

- **MCP bind failure surfaces the Mojo message and leaks nothing.** Mirrors `DevMojoTest.bindToTakenPortFailsWithOverrideHint` (`DevMojoTest.java:45-61`). Occupy an ephemeral port with a blocker socket, build the Mojo with `port = 0` (the LSP binds a free ephemeral port and succeeds) and `mcpPort` set to the taken port, then assert `mojo.execute()` throws `MojoExecutionException` whose message names the MCP port and carries the MCP guidance text, and assert the already-constructed LSP `DevServer` was closed (`isClosed()`) so the partial bind leaks no socket. This is the case that pins the *failure-contract parity with the LSP bind* the Deferred section promotes into scope: the user-visible `MojoExecutionException`, not the server-level `IOException` above.
- **`DEFAULT_MCP_PORT` is pinned to 8488.** Extend `DevMojoTest.defaultsMatchPlanContract` (`DevMojoTest.java:38-43`), which already pins `DEFAULT_PORT == 8487` and `LOOPBACK_HOST`, with `DEFAULT_MCP_PORT == 8488`. This is the build-time pin on the Java source of truth for the port: the same "catch it at build/startup time" discipline that promotes the fail-fast bind into scope below. Without it the constant is the one copy of `8488` the spec would leave unpinned while pinning the weaker failure contract beside it, an inconsistency *Stability through simplicity* does not warrant. It closes the constant-side drift of the four `8488` copies (see the Implementation note), leaving only the two static copies as accepted drift.

These carry **no four-tier annotation** by design: the new `graphitron-mcp` module and the `graphitron-maven-plugin` layer that `DevServerTest`/`DevMojoTest` already inhabit both sit outside the `@UnitTier`/`@PipelineTier`/`@CompilationTier`/`@ExecutionTier` enforcement `testing.adoc` scopes to `graphitron` and `graphitron-sakila-example`. The "*Pipeline tests are the primary behavioural tier*" principle is about the SDL → model → `TypeSpec` path and does not reach transport glue; this work produces no classifier/emitter behaviour to assert there. That exemption is a property of *this* skeleton's static content, not of the MCP layer as such: when R118 wires live `catalog`/`schema` tools over the warm `Workspace`, the answers those tools return are downstream of the classifier and do acquire a behavioural surface, so R118 must re-evaluate tier placement for its own tools rather than inherit "MCP is outside the tiers" as an unconditional precedent. The module's main and test code must compile clean under the `-Xlint:all -Werror` ratchet (R293) like every other module; keep SDK/Jetty API usage warning-free.

## User documentation (first-client check)

Per `workflow.adoc`'s "plans with a user-visible surface include a draft of the user docs as the first client of the design." This subsection goes into `getting-started.adoc` under the existing "Dev loop" section, immediately after the editor/LSP connection prose (`getting-started.adoc:316-325`). If it does not read simply, the design is wrong and changes before implementation.

The contributor-facing "How this is wired" subsection (`getting-started.adoc:368-398`) is updated in this same item, not deferred to R118: "four cooperating components" becomes five, the MCP server gets its own bullet and a node in the D7 mermaid diagram (`:383-398`), and the Ctrl+C cleanup sentences (`:365-366`, `:379`) name the MCP server alongside the LSP socket, watchers, and debounce. The fifth component lands with this skeleton, so that prose stops being accurate the moment R341 ships; the fix rides here rather than depending on a later item.

Two hard counts in the architecture index (`graphitron-rewrite/docs/README.adoc`) go stale the same way and are fixed in this item: § "Modules" ("Nine modules ..." plus the module table, `README.adoc:44-76`) gains a `graphitron-mcp` row and becomes ten, and § "Publishing" (`README.adoc:84`) adds `graphitron-mcp` to the enumerated publishable surface (per Decision 1 it is published like `graphitron-lsp`, so it must *not* join the `maven.deploy.skip` list beside the example/fixture modules).

> *Agent users (MCP).* Alongside the LSP, `mvn graphitron:dev` runs an MCP server on `http://127.0.0.1:8488/mcp` (Streamable HTTP, loopback only). An MCP-aware agent (Claude Code, Cursor) connected to it gets ambient context about your graphitron project and a `/mcp__graphitron__about` slash command that explains the dev loop.
>
> The `graphitron-sakila-example` project ships a committed `.mcp.json` pointing at this URL, so cloning it needs zero MCP configuration. For your own project, the dev server prints a copy-pasteable line on startup:
>
> ```
> claude mcp add --transport http graphitron http://127.0.0.1:8488/mcp
> ```
>
> The port is fixed at `8488` (the LSP's is `8487`). If `8488` is already taken (a second `graphitron:dev` for another project), the dev server fails fast with a message naming the conflict rather than silently rebinding, so a stale `.mcp.json` never points an agent at the wrong project's server.

Friction the draft surfaces, to check before implementing:

- The fixed port means a second concurrent project's dev server cannot bind; the fail-fast message is the whole UX for that case in the skeleton. The configurable-port follow-on (Deferred) is what makes concurrent projects work; the docs should not promise it yet.
- "Static content only" is invisible to the user and correct to leave unsaid here; the live catalog/schema/docs tools arrive with R118 and get their own doc prose then.

## Deferred (fix later)

The skeleton hard-codes `8488`. The following robustness work is intentionally postponed; the analysis is recorded here so it is not re-derived later.

- Configurable port: a single `GRAPHITRON_MCP_PORT` knob read by both sides, the dev server's bind and the committed `.mcp.json` (via Claude Code's `${GRAPHITRON_MCP_PORT:-8488}` expansion, which is supported in `url` values and resolved from Claude's own environment at startup). One knob keeps the bound port and the configured port in lockstep.
- Project-identity stamp: put the project root or module name into `serverInfo` and the `instructions` string, so that if a client ever connects to the wrong project's server the mismatch is visible rather than silently poisoning answers.
- Concurrent multi-project support generally, of which the two items above are the load-bearing parts.

**Promoted out of Deferred into scope:** *fail-fast on a taken `8488`*. The Backlog draft listed bind-collision handling as deferred robustness, but the precedent sets the bar one port over: `DevMojo.bindServer` already translates a taken `8487` into a clear `MojoExecutionException` naming the override (`DevMojo.java:166-177`). Shipping the *foundational* MCP bind with a weaker failure contract (a bare stack trace, or worse a silent roam) than the LSP bind it sits beside contradicts *Stability through simplicity*'s "problems caught at build/startup time are far cheaper than problems caught in production," and the fix is a few lines mirroring the existing arm. What stays deferred is the *configurable* port and the identity stamp; the fail-fast *diagnostic* on the hard-coded port is table stakes the skeleton's own "prove the seam end to end" promise includes.

Why hard-coding is safe to start: because the port never changes, the committed `.mcp.json` entry stays loaded for the life of a Claude session, so recovering from a dev restart needs at most a `/mcp reconnect` and never a Claude restart. (Verified against Claude Code docs: it retries an initial connection three times, auto-reconnects a mid-session drop up to five times with exponential backoff, and only a changed URL forces a config re-read and restart.) Binding the MCP port early in `graphitron:dev` startup makes the automatic-reconnect path the common one.

## Out of scope (stays in R118)

Live catalog and schema tools over the warm `Workspace` (`catalog.tables`, `catalog.describe`, `schema`); documentation RAG; any vector store, embeddings, or ONNX model; the stdio-to-HTTP proxy for stdio-only clients. The decision on how to keep R118's heavy native dependencies off the non-`dev` plugin goals' classpath (see Decision 1) also lands in R118.

## Principle surface

This item ships transport and lifecycle glue, the same architectural layer as `DevServer`, which the rewrite's classifier/emitter principles (generation-thinking, sealed hierarchies, classification boundaries, validator-mirrors-classifier, narrow component types) deliberately do not reach. Recording this so a reviewer does not look for a sealed hierarchy the work has no occasion to produce. The live principles are *Stability through simplicity* (the dependency-mass and fail-fast decisions) and *Separate business logic from API code* (the module split). The static-content-at-startup posture is the blessed `LspVocabulary` "shape, not state" precedent, and the glue-first-then-behaviour shape is the one the LSP itself took (`DevServer` landed before the richer LSP behaviour). Shipping the seam thin and proving it end to end before R118's native-dep mass arrives is de-risking, not speculative scaffolding, precisely because it proves the seam R118 uses (Decision 2).

## References

- `graphitron-rewrite/roadmap/graphitron-mcp-server.md` (R118): the downstream consumer; depends on this item. Its heavy-dependency-loading decision (Decision 1) and live tool surface (Out of scope) are flagged for it here.
- `graphitron-rewrite/graphitron-maven-plugin/.../DevMojo.java:166-177` (`bindServer`, the `BindException → MojoExecutionException` arm to mirror), `:376-384` (`cleanup`), `:152-153` (the startup log line to extend).
- `graphitron-rewrite/graphitron-maven-plugin/.../dev/DevServer.java:57-70` (the `AutoCloseable` + `InetSocketAddress`-parameter transport-glue shape to mirror), `:72-74` (`port()`), `:117-129` (`close()`).
- `graphitron-rewrite/graphitron-maven-plugin/src/test/.../dev/DevServerTest.java:42` (ephemeral port 0), `:99-106` (bind-taken test): the infrastructure integration-test precedent.
- `graphitron-rewrite/graphitron-lsp/.../LspVocabulary.java:52-53`, `:86-88`: the "shape, not state, read once at startup" precedent for the static content.
- `graphitron-rewrite/docs/getting-started.adoc` § "Dev loop" (`:305-366` the LSP connection prose this item extends; `:368-398` the four-cooperating-components contributor section the MCP server becomes a fifth sibling of).
- MCP Java SDK 2.x (`io.modelcontextprotocol.sdk`, `mcp-bom` + core `mcp`): `HttpServletStreamableServerTransportProvider`, the servlet-based Streamable HTTP server transport; tracks the 2025-11-25 MCP spec.
- `docs/graphitron-principles.adoc` (*Stability through simplicity*, *Separate business logic from API code*); `graphitron-rewrite/docs/rewrite-design-principles.adoc` (*Pipeline tests are the primary behavioural tier*, scoped away from this layer); `graphitron-rewrite/docs/testing.adoc` (four-tier enforcement scope).
