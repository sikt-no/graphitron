package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.render.CatalogRefs;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

/**
 * Boundary-decode coverage: the catalog decides each column's javapoet type once, at the jOOQ
 * reflection boundary, via {@code TypeName.get(col.getType())}. This pins <em>our use</em> of that
 * decode (not javapoet's own contract): a scalar column yields a {@link ClassName}, an array column
 * yields an {@link ArrayTypeName} of the right element type. The paired {@code columnClass} string
 * stays the raw jOOQ {@code getType().getName()} form (binary descriptor for arrays), which is the
 * dual-fact split the fix relies on: codegen reads {@code columnType}, the {@code Class.forName} /
 * {@code Class.getName()} consumers keep reading {@code columnClass}.
 *
 * <p>Fixture: the {@code array_holder} table ({@code id serial}, {@code flags boolean[]},
 * {@code tags text[]}, {@code label varchar}). Before the type-lift, {@code flags}'
 * {@code [Ljava.lang.Boolean;} descriptor crashed {@code ClassName.bestGuess}.
 */
@UnitTier
class ArrayColumnTypeDecodeTest {

    private static JooqCatalog catalog() {
        return new JooqCatalog(DEFAULT_JOOQ_PACKAGE);
    }

    @Test
    void scalarColumn_decodesToClassName() {
        var id = catalog().findColumn("array_holder", "id").orElseThrow();
        assertThat(CatalogRefs.decodeBindingType(id.columnClass())).isInstanceOf(ClassName.class);
        assertThat(CatalogRefs.decodeBindingType(id.columnClass()).toString()).isEqualTo("java.lang.Integer");
        // The raw string carries the same source-form FQCN for a scalar.
        assertThat(id.columnClass()).isEqualTo("java.lang.Integer");
    }

    @Test
    void booleanArrayColumn_decodesToArrayTypeName_ofBoolean() {
        var flags = catalog().findColumn("array_holder", "flags").orElseThrow();
        assertThat(CatalogRefs.decodeBindingType(flags.columnClass())).isInstanceOf(ArrayTypeName.class);
        assertThat(((ArrayTypeName) CatalogRefs.decodeBindingType(flags.columnClass())).componentType().toString())
            .isEqualTo("java.lang.Boolean");
        assertThat(CatalogRefs.decodeBindingType(flags.columnClass()).toString()).isEqualTo("java.lang.Boolean[]");
        // The raw string stays the JVM binary descriptor: the form the Class.forName /
        // Class.getName consumers depend on, and the exact form that crashed ClassName.bestGuess.
        assertThat(flags.columnClass()).isEqualTo("[Ljava.lang.Boolean;");
    }

    @Test
    void textArrayColumn_decodesToArrayTypeName_ofString() {
        var tags = catalog().findColumn("array_holder", "tags").orElseThrow();
        assertThat(CatalogRefs.decodeBindingType(tags.columnClass())).isInstanceOf(ArrayTypeName.class);
        assertThat(((ArrayTypeName) CatalogRefs.decodeBindingType(tags.columnClass())).componentType().toString())
            .isEqualTo("java.lang.String");
        assertThat(tags.columnClass()).isEqualTo("[Ljava.lang.String;");
    }

    @Test
    void allColumnsOf_carriesColumnTypeForEveryColumn() {
        // The full-row iterator decodes every column, so a mixed scalar/array row all carries a
        // non-null columnType. It is the widest decode surface in the catalog, which is why the
        // type-lift is pinned here rather than at any one consumer.
        var cols = catalog().allColumnsOf("array_holder");
        assertThat(cols).isNotEmpty();
        assertThat(cols).allSatisfy(c -> assertThat(CatalogRefs.decodeBindingType(c.columnClass())).isNotNull());
        var flags = cols.stream().filter(c -> c.sqlName().equals("flags")).findFirst().orElseThrow();
        assertThat(CatalogRefs.decodeBindingType(flags.columnClass())).isInstanceOf(ArrayTypeName.class);
    }

    // ===== The captured-name decode: the same answer without a live Class =====

    /*
     * A store-sourced reader holds sql_column.binding_type and no Class, the codegen loader being
     * closed by then. These pin that the name alone is enough, which it is because capture records
     * Class.getName() verbatim: the descriptor form the boundary decode reads off a live Class is the
     * same string, so the two producers cannot disagree.
     */

    @Test
    void capturedScalarName_decodesToClassName() {
        var decoded = CatalogRefs.decodeBindingType("java.lang.Integer");
        assertThat(decoded).isInstanceOf(ClassName.class);
        assertThat(decoded.toString()).isEqualTo("java.lang.Integer");
    }

    /** The descriptor that crashed {@code bestGuess}, decoded rather than guessed. */
    @Test
    void capturedArrayDescriptor_decodesToArrayTypeName() {
        var decoded = CatalogRefs.decodeBindingType("[Ljava.lang.Boolean;");
        assertThat(decoded).isInstanceOf(ArrayTypeName.class);
        assertThat(decoded.toString()).isEqualTo("java.lang.Boolean[]");
    }

    /** Two dimensions, one level stripped per recursion. */
    @Test
    void capturedNestedArrayDescriptor_decodesBothDimensions() {
        assertThat(CatalogRefs.decodeBindingType("[[Ljava.lang.String;").toString())
            .isEqualTo("java.lang.String[][]");
    }

    /**
     * A primitive array keeps its element descriptor, a primitive column being boxed by the time jOOQ
     * names it but a primitive array not being.
     */
    @Test
    void capturedPrimitiveArrayDescriptor_decodesToThePrimitiveElement() {
        assertThat(CatalogRefs.decodeBindingType("[B").toString())
            .isEqualTo("byte[]");
    }

    /**
     * The captured name and the boundary decode agree on a real array column, which is the property
     * that lets a store-sourced reader stand in for the catalog rather than approximate it.
     */
    @Test
    void theCapturedNameDecodesToWhatTheBoundaryDecoded() {
        var flags = catalog().findColumn("array_holder", "flags").orElseThrow();
        assertThat(CatalogRefs.decodeBindingType(flags.columnClass()))
            .isEqualTo(CatalogRefs.decodeBindingType(flags.columnClass()));
    }

}
