package no.sikt.graphitron.rewrite.model.auditfixture;

import no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck;

/**
 * Fixture for the {@code LoadBearingGuaranteeAuditTest} meta-test: a consumer
 * that cites a key with no producer anywhere in the rewrite module. The
 * audit's failure-detection is exercised against this class so that future
 * refactors of the walker can't silently regress the orphan-detection.
 *
 * <p>The headline production audit scopes its walk to exclude this package,
 * so this deliberate violation never trips the production assertion.
 */
public final class OrphanedConsumer {

    private OrphanedConsumer() {}

    @DependsOnClassifierCheck(
        key = "audit-fixture-orphan",
        reliesOn = "intentionally orphaned: exercises the meta-test only")
    static void consumer() {}
}
