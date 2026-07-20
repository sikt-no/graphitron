package no.sikt.graphitron.rewrite.model;

import java.util.Set;

/**
 * The runtime source shape a member field's fetcher can receive at {@code env.getSource()}. A
 * directiveless output type reachable from more than one producer sees more than one shape at the
 * same {@code (type, field)} coordinate, because graphql-java wires one datafetcher per coordinate:
 * the union of shapes proven to reach a coordinate is a genuine cross-edge model fact, reified once
 * (post-walk in {@code GraphitronSchemaBuilder}) and read by every consumer that must agree on it,
 * so the dispatch emitter, the validator, and the pipeline tests never re-derive it independently.
 *
 * <p>See {@link no.sikt.graphitron.rewrite.GraphitronSchema#reachableSourceShapes} for the keyed
 * lookup and {@link #SINGLE_ARM} / {@link #DISPATCHED} / {@link #REJECTED} for the partition of
 * shape-set combinations the emitter and validator share.
 */
public enum ReachableSourceShape {

    /**
     * Reached as a nesting projection of a {@code @table} parent: the source is a generic
     * {@link org.jooq.Record}, and the field reads a column (typed {@code Tables.X.COL} against the
     * representative parent table, or by name). Corresponds to a {@code ChildField.NestingField}
     * edge embedding the type.
     */
    NESTING_RECORD,

    /**
     * Reached as a field of a class-backed result ({@code JavaRecordType} or
     * {@code PojoResultType.Backed}): the source is the producer's reflected backing object, and the
     * field reads a resolved accessor off it.
     */
    CLASS_BACKED_ACCESSOR,

    /**
     * Reached as a field of a jOOQ-record-carrier result ({@code JooqRecordType} or
     * {@code JooqTableRecordType}): the source is the carrier's generic {@link org.jooq.Record}, read
     * by column. Coexistence with {@link #NESTING_RECORD} is unsupported in v1 (both arms would be
     * {@code Record} reads with independently derived read names); the combination is
     * {@linkplain #REJECTED validate-time rejected}.
     */
    JOOQ_RECORD_CARRIER;

    /**
     * Shape-set combinations the emitter serves with a single-arm read, byte-for-byte the same as a
     * type reached only one way. Every singleton is single-arm.
     */
    public static final Set<Set<ReachableSourceShape>> SINGLE_ARM = Set.of(
        Set.of(NESTING_RECORD),
        Set.of(CLASS_BACKED_ACCESSOR),
        Set.of(JOOQ_RECORD_CARRIER));

    /**
     * Shape-set combinations the emitter serves with a run-time {@code source instanceof
     * org.jooq.Record} dispatch composed from the two single-arm reads. The one supported dual reach
     * in v1: a nesting projection coexisting with a class-backed accessor.
     */
    public static final Set<Set<ReachableSourceShape>> DISPATCHED = Set.of(
        Set.of(NESTING_RECORD, CLASS_BACKED_ACCESSOR));

    /**
     * Shape-set combinations the validator rejects: no emitter arm handles them. The
     * {@code JooqRecordCarrier} + nesting mix is the first, re-landing the narrowed remainder of the
     * former fused type-level rejection over the same reified fact the emitter reads.
     */
    public static final Set<Set<ReachableSourceShape>> REJECTED = Set.of(
        Set.of(NESTING_RECORD, JOOQ_RECORD_CARRIER));

    /** True when the emitter serves {@code shapes} with the run-time source-shape dispatch. */
    public static boolean requiresDispatch(Set<ReachableSourceShape> shapes) {
        return DISPATCHED.contains(shapes);
    }

    /** True when {@code shapes} is a combination the emitter can serve (single-arm or dispatched). */
    public static boolean isEmittable(Set<ReachableSourceShape> shapes) {
        return SINGLE_ARM.contains(shapes) || DISPATCHED.contains(shapes);
    }
}
