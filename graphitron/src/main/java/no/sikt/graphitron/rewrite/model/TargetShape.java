package no.sikt.graphitron.rewrite.model;

/**
 * The shape a field <em>projects</em> at its {@link Target} endpoint: the named thing inside
 * the target wrapper, on the same catalog-vs-Java polarity {@link SourceShape} uses for the source
 * endpoint. {@code SourceShape ⊆ TargetShape}: a source is always a row, so it has only the row
 * shapes ({@link Table} / {@link Record}), while the target adds the scalar shapes ({@link Column} /
 * {@link Field}) and the {@link Connection} container shape a source can never be.
 *
 * <ul>
 *   <li>{@link Table} / {@link Column}: the catalog side, a catalog table-bound result, or a single
 *       column projected from a table-backed parent.</li>
 *   <li>{@link Record} / {@link Field}: the Java side, a service / DML record-backed object, or a
 *       scalar reflected off such a record. {@code Table : Column :: Record : Field}.</li>
 *   <li>{@link Connection}: the Relay-connection container shape, wrapping the inner element shape.
 *       Its many-ness lives on its own {@code edges} / {@code nodes} fields, classified normally; the
 *       windowed-<em>read</em> verb lives on the {@link OperationMember.Paginate} member.</li>
 *   <li>{@link Interface} / {@link Union}: the polymorphic shapes. Both are catalog-bound (every
 *       participant is a {@code @table} / NodeType), the catalog projection landing on participant
 *       rows. They carry no payload (no participant set, join paths, or backing distinction);
 *       consumers dispatch on the shape identity alone. {@code Union} is target-only and
 *       {@code Table}-backed only.</li>
 * </ul>
 */
public sealed interface TargetShape {
    /** A catalog table-bound result (the catalog row side; includes single-table polymorphic interfaces). */
    record Table() implements TargetShape {}
    /** A service record-backed (Pojo / JavaRecord / jOOQ record) object (the Java row side). */
    record Record() implements TargetShape {}
    /** A single catalog column projected from a table-backed parent (the catalog scalar side). */
    record Column() implements TargetShape {}
    /** A scalar reflected off a service record-backed parent (the Java scalar side). */
    record Field() implements TargetShape {}
    /** A Relay connection wrapping its inner element shape. */
    record Connection(TargetShape inner) implements TargetShape {}
    /** A multi-table polymorphic interface result. */
    record Interface() implements TargetShape {}
    /** A multi-table polymorphic union result ({@code Table}-backed, target-only). */
    record Union() implements TargetShape {}
}
