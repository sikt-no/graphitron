package no.sikt.graphitron.rewrite.model;

/**
 * The {@code target} axis (R316): the field's <em>projection endpoint</em>, a wrapper around a
 * {@link TargetShape} whose arm is the field's own output cardinality. The same {@code wrapper(shape)}
 * form as {@link Source}, at the other end of the field's edge: {@link Single} | {@link List} wrapping
 * the shape the field projects.
 *
 * <p>The wrapper is read straight off the field's GraphQL return type ({@code field.getType()} /
 * {@link FieldWrapper}): the value {@code SourceKey.Cardinality} computes today from
 * {@code wrapper().isList()}. Keeping cardinality <em>as a wrapper bound to this endpoint</em>, never a
 * free {@code One} / {@code Many} enum, is the structural fix R316's wrapper algebra exists to hold: the
 * same {@code {One, Many}} values appear on the {@link Source} wrapper (accumulated) and here (local), so
 * a detached cardinality value would be ambiguous. A Relay connection is {@code Single(Connection(inner))}
 * (the windowed-read verb is {@link Operation.Paginate}, not a wrapper fact).
 *
 * <p><strong>Slice-3 additive cutover (R316).</strong> {@code target()} is the new primitive;
 * {@link OutputField#mapping()} survives as a derived {@code default} bridge deriving the retired
 * {@link Mapping} from {@code target().shape()} so the R281 corpus keeps classifying unchanged until
 * slice 4 migrates it. {@link Mapping} (and the {@code TableConnection} value, now decomposed into
 * {@code Single(Connection)} + {@link Operation.Paginate}) retire with that cutover.
 */
public sealed interface Target {

    /** The shape inside the wrapper: what the field projects. */
    TargetShape shape();

    /** The field returns a single instance of {@link #shape()} (GraphQL non-list wrapper). */
    record Single(TargetShape shape) implements Target {}

    /** The field returns a GraphQL list of {@link #shape()}. */
    record List(TargetShape shape) implements Target {}
}
