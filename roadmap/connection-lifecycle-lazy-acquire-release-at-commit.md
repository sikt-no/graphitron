---
id: R644
title: "Lazy acquisition and release at transaction end, deleting the settle re-fire"
status: Backlog
bucket: architecture
priority: 1
theme: runtime-connection
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# Lazy acquisition and release at transaction end, deleting the settle re-fire

The owned-connection runtime pins one connection per source for the whole operation and then spends real machinery keeping mounted identity alive across a mutation field's commit. `ConnectionRuntimeClassGenerator`'s own javadoc states why the machinery exists: graphitron cannot verify whether a consumer's mounted state survives a commit or rollback ("identity parked in an `ON COMMIT DELETE ROWS` temp table would not"), so it asks the consumer to declare survival with `<stateSurvivesTransactions>true</stateSurvivesTransactions>` and, undeclared, bakes `remountAfterSettle=true` and re-fires the hook pair through `PinnedConnection.afterSettle`, wired as the transaction provider's settle-completion `Runnable`.

Releasing the connection when its transaction ends makes that whole question moot instead of answering it. A later fetcher that needs a connection takes the same lazy acquisition path as the first one did: pin, normalize autocommit, mount, use. Nothing ever depends on state surviving a commit, because no state is expected to outlive the connection that carried it. That deletes `remountAfterSettle`, `afterSettle`, the provider's settle-completion callback, the `<stateSurvivesTransactions>` element and its validation, and the "settle" vocabulary that names a mechanism nobody would otherwise need. It also removes the question of how a re-fire reaches the mount payload, since every mount happens on the acquisition path that already has it.

Two shape changes come with it. Single-tenant acquisition becomes lazy like the tenant-routed path already is: `getDslContext(env)` stops being a read of an eagerly-published `graphQLContext` key and pins on demand, which is what `TenantConnections.dslFor` does today. And the per-tenant state stops being a global object beside a map of connections: one carrier per tenant key holds that key's connection, its mounted identity, its handle and its `DSLContext`, with single-tenant as the one-key case. The emitters are largely untouched, since `TenantDslEmitter`, `HandleMethodBody` and `ConnectionHelperClassGenerator` all emit `getDslContext(env)` or `dslFor(...)` call sites whose signatures do not change.

Open questions for the Spec pass, none of which look blocking. `CommitPolicy.ROLLBACK_ONLY` never commits, so its deferred operation transaction never reaches a release point and the dev path keeps pinning for the operation by construction rather than by special case; that wants confirming rather than assuming. The DML two-step's post-commit read-back moves to a possibly different pooled connection, which is safe because the keys travel in a Java local out of `dsl.transactionResult(...)` rather than in session state, but any other same-connection assumption in generated SQL needs a sweep. A mutation field's worth of pool acquire and release replaces a re-fire, which is roughly round-trip-neutral against a mount-only hook and adds pool traffic. And a connection carrying identity returns to the pool once per transaction instead of once per operation, so the mount-only safety argument (every reader mounts first, wholesale) becomes more load-bearing while consumers who configure an unmount pay it more often.

R639 is downstream of this: its settle re-fire bullet, its retained-payload question, its `<stateSurvivesTransactions>` handling and its `$session` read route all get smaller or disappear if this lands first, and the per-tenant carrier gives the `$session` handle a stable `Configuration` to live on, which the current split between an eager single-tenant `DSLContext` and a per-call tenant-routed one does not.
