package no.sikt.graphitron.rewrite.model;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a classifier check that an emitter relies on for correctness. The
 * check rejects a shape the emitter would otherwise have to handle defensively
 * (cast, null guard, {@code instanceof}); relaxing the check breaks the
 * generated source — at compile time if you're lucky, at runtime if you're
 * not. {@code rewrite-design-principles.md § "Classifier guarantees shape
 * emitter assumptions"} names the pattern; this annotation makes the pairing
 * mechanical.
 *
 * <p>One producer per {@link #key()}. Consumers reference the same key via
 * {@link DependsOnClassifierCheck#key()}; the audit test
 * ({@code LoadBearingGuaranteeAuditTest}) enforces the matching at build time.
 *
 * <p>The annotation lands at method (or type) granularity, but
 * {@link #description()} should name the specific arm that does the
 * rejecting — a method like {@code reflectTableMethod} has many rejection
 * arms, only one of which is load-bearing.
 *
 * <p>Producers without consumers are allowed: some classifier checks reject
 * shapes for hygiene rather than because an emitter relies on them.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Repeatable(LoadBearingClassifierChecks.class)
public @interface LoadBearingClassifierCheck {

    /**
     * Stable identifier shared with each {@link DependsOnClassifierCheck} consumer.
     * Convention: kebab-case, descriptive of the contract
     * (e.g., {@code service-catalog-strict-tablemethod-return}).
     */
    String key();

    /**
     * What shape the emitter is allowed to assume because this check rejects
     * the alternative. Name the specific arm the emitter relies on, not the
     * whole method.
     */
    String description();
}
