package no.sikt.graphitron.rewrite;

/**
 * Fixture: record-shaped consumer bean with a Java-primitive component ({@code int n}) used
 * by classifier and emitter tests to verify the primitive-to-wrapper box at the resolver boundary.
 * The matching SDL input type is {@code input TestInputBeanWithPrimitive { n: Int!, s: String }};
 * the {@code FieldBinding} for {@code n} must carry {@code javaElementTypeName ==
 * "java.lang.Integer"}, not {@code "int"}.
 */
public record TestInputBeanWithPrimitive(int n, String s) {
}
