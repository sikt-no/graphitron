package no.sikt.graphitron.rewrite.model;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an emitter site as relying on a {@link LoadBearingClassifierCheck}.
 * The audit test ({@code LoadBearingGuaranteeAuditTest}) verifies that every
 * {@link #key()} cited here resolves to exactly one producer in the rewrite
 * module.
 *
 * <p>Repeatable so an emitter that depends on multiple classifier checks can
 * declare each dependency separately. {@link #reliesOn()} should describe
 * what the emitter does with the assumption (typed local without cast,
 * absent null guard, etc.), again at arm granularity rather than method
 * granularity.
 *
 * <p>The inverse asymmetry — a new emitter that <em>should</em> depend on a
 * guarantee but forgets this annotation — is not caught by the audit. That
 * drift mode falls back to the generated {@code *Fetchers} compile failure
 * named in the principles doc.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Repeatable(DependsOnClassifierChecks.class)
public @interface DependsOnClassifierCheck {

    /** Matches {@link LoadBearingClassifierCheck#key()} on the producer. */
    String key();

    /**
     * What the emitter does with the guaranteed shape: e.g.,
     * "declares typed Result&lt;FilmRecord&gt; without cast", "treats
     * parentTable == null as a classifier-invariant violation".
     */
    String reliesOn();
}
