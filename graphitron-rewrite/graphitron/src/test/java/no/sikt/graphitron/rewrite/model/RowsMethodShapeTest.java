package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier coverage of the {@link RowsMethodShape} structural algebra, pinning the
 * forward/inverse identity its class javadoc asserts ("the cross-product cannot drift"). The
 * forward {@link RowsMethodShape#outerRowsReturnType} builds {@code Map<K, V>} / {@code List<V>}
 * (and their list-cardinality variants) from a per-key {@code V}; the inverse
 * {@link RowsMethodShape#perKeyFromOuter} (added for R364) peels {@code V} back out. The two must
 * round-trip across the full {@code (isMapped, isList)} cross-product, otherwise the emitter's
 * {@code elementType()} and the validator's {@code validateChildServiceReturnType} would compute
 * different rows-method shapes for the same field.
 */
@UnitTier
class RowsMethodShapeTest {

    private static final ClassName KEY = ClassName.get("org.jooq", "Row1");
    private static final TypeName LEAF = ClassName.get("java.lang", "String");

    private static ReturnTypeRef scalar(boolean isList) {
        FieldWrapper wrapper = isList ? new FieldWrapper.List(true, true) : new FieldWrapper.Single(true);
        return new ReturnTypeRef.ScalarReturnType("Rating", wrapper);
    }

    private static void assertRoundTrips(boolean isMapped, boolean isList) {
        var returnType = scalar(isList);
        TypeName outer = RowsMethodShape.outerRowsReturnType(LEAF, returnType, KEY, isMapped);
        assertThat(RowsMethodShape.perKeyFromOuter(outer, returnType, isMapped))
            .as("perKeyFromOuter must invert outerRowsReturnType (isMapped=%s, isList=%s)", isMapped, isList)
            .isEqualTo(LEAF);
    }

    @Test
    void roundTrips_mappedSingle() {
        assertRoundTrips(true, false);
    }

    @Test
    void roundTrips_mappedList() {
        assertRoundTrips(true, true);
    }

    @Test
    void roundTrips_positionalSingle() {
        assertRoundTrips(false, false);
    }

    @Test
    void roundTrips_positionalList() {
        assertRoundTrips(false, true);
    }

    @Test
    void perKeyFromOuter_returnsNull_whenMappedShapeIsActuallyAList() {
        // A bare List where the mapped (Set-keyed) batch shape requires a Map<K, V>: unpeelable,
        // so the inverse reports null and the validator turns that into a classify-time rejection
        // rather than peeling the wrong slot.
        TypeName bareList = no.sikt.graphitron.javapoet.ParameterizedTypeName.get(
            ClassName.get("java.util", "List"), LEAF);
        assertThat(RowsMethodShape.perKeyFromOuter(bareList, scalar(false), true)).isNull();
    }

    @Test
    void perKeyFromOuter_returnsNull_whenListFieldMissesItsInnerListNesting() {
        // A list-cardinality field whose mapped value is Map<K, V> instead of Map<K, List<V>>:
        // the inner list-nesting outerRowsReturnType adds for list fields is absent, so peeling
        // the inner List fails and the inverse reports null.
        TypeName mapOfLeaf = no.sikt.graphitron.javapoet.ParameterizedTypeName.get(
            ClassName.get("java.util", "Map"), KEY, LEAF);
        assertThat(RowsMethodShape.perKeyFromOuter(mapOfLeaf, scalar(true), true)).isNull();
    }

    @Test
    void perKeyFromOuter_returnsNull_forRawType() {
        assertThat(RowsMethodShape.perKeyFromOuter(ClassName.get("java.util", "Map"), scalar(false), true))
            .isNull();
    }
}
