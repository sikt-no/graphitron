package no.sikt.graphitron.rewrite.model;

/**
 * The {@code target} axis: the field's <em>projection endpoint</em>, a wrapper around a
 * {@link TargetShape} whose arm is the field's own output cardinality. The same {@code wrapper(shape)}
 * form as {@link Source}, at the other end of the field's edge: {@link Single} | {@link List} wrapping
 * the shape the field projects.
 *
 * <p>The wrapper is read straight off the field's GraphQL return type ({@code field.getType()} /
 * {@link FieldWrapper}): the value the retired {@code SourceKey.Cardinality} computed from
 * {@code wrapper().isList()}. Keeping cardinality <em>as a wrapper bound to this endpoint</em>, never a
 * free {@code One} / {@code Many} enum, is the structural fix the wrapper algebra exists to hold: the
 * same {@code {One, Many}} values appear on the {@link Source} wrapper (accumulated) and here (local), so
 * a detached cardinality value would be ambiguous. A Relay connection is {@code Single(Connection(inner))}
 * (the windowed-read verb is {@link Operation.Paginate}, not a wrapper fact).
 *
 * <p>{@code target()} is the projection-axis primitive; the {@code mapping} axis was later retired and
 * the classification corpus migrated onto it. The fused {@code TableConnection} mapping
 * decomposed into this wrapper's {@code Single(Connection)} shape plus the {@link Operation.Paginate}
 * windowed-read verb.
 */
public sealed interface Target {

    /** The shape inside the wrapper: what the field projects. */
    TargetShape shape();

    /** The field returns a single instance of {@link #shape()} (GraphQL non-list wrapper). */
    record Single(TargetShape shape) implements Target {}

    /** The field returns a GraphQL list of {@link #shape()}. */
    record List(TargetShape shape) implements Target {}
}
