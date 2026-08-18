package no.sikt.graphitron.rewrite;

/**
 * Fixture: record-shaped consumer bean carrying both outcomes of the nested-input rule at once. Its
 * SDL input declares two nested input fields: one whose name matches no component here (so it is a
 * grouping input and its {@code length} field flattens onto this record), and one named
 * {@code period}, which matches the {@link TestInputNested} component below and therefore keeps
 * binding as a nested bean rather than flattening.
 *
 * <p>Adding a member is how an author opts a group back out of flattening, and this fixture is the
 * pin for that: the same SDL nesting resolves two different ways depending only on whether the Java
 * side names it.
 */
public record TestInputBeanGroupedWithNested(
    String title,
    Integer length,
    TestInputNested period
) {
}
