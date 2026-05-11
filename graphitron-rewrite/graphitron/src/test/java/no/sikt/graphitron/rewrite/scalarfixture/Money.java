package no.sikt.graphitron.rewrite.scalarfixture;

import java.math.BigDecimal;

/**
 * Fixture domain type for custom-scalar resolver tests. Wraps a {@link BigDecimal} amount so
 * the resolved Java type ({@code Money}) is unambiguously not one of graphql-java's spec
 * built-ins, surfacing test failures cleanly when the resolver mis-classifies.
 */
public record Money(BigDecimal amount) {}
