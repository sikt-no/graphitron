package no.sikt.graphitron.rewrite;

/**
 * Fixture: a record with <em>fewer</em> components than its SDL input type has fields. Bound to
 * an SDL input {@code { a, b, c }}, the field {@code c} binds to no component, so its value would be
 * silently dropped on the way to the canonical constructor. The record binder rejects this at
 * classify time: a record's correspondence to its SDL input type is total (every field must be
 * consumed), unlike a JavaBean's deliberately partial setter population.
 *
 * <p>The existing {@link TestInputBean} cases are all exact mirrors (component count == field count),
 * so the subset shape is new; it is a forward-looking guard, since a subset record constructs fine
 * today and drops the extra field with no error at any tier.
 */
public record TestInputSubsetRecord(
    String a,
    String b
) {
}
