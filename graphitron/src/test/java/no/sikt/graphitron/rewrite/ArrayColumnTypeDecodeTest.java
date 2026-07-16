package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
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
        assertThat(id.columnType()).isInstanceOf(ClassName.class);
        assertThat(id.columnType().toString()).isEqualTo("java.lang.Integer");
        // The raw string carries the same source-form FQCN for a scalar.
        assertThat(id.columnClass()).isEqualTo("java.lang.Integer");
    }

    @Test
    void booleanArrayColumn_decodesToArrayTypeName_ofBoolean() {
        var flags = catalog().findColumn("array_holder", "flags").orElseThrow();
        assertThat(flags.columnType()).isInstanceOf(ArrayTypeName.class);
        assertThat(((ArrayTypeName) flags.columnType()).componentType().toString())
            .isEqualTo("java.lang.Boolean");
        assertThat(flags.columnType().toString()).isEqualTo("java.lang.Boolean[]");
        // The raw string stays the JVM binary descriptor: the form the Class.forName /
        // Class.getName consumers depend on, and the exact form that crashed ClassName.bestGuess.
        assertThat(flags.columnClass()).isEqualTo("[Ljava.lang.Boolean;");
    }

    @Test
    void textArrayColumn_decodesToArrayTypeName_ofString() {
        var tags = catalog().findColumn("array_holder", "tags").orElseThrow();
        assertThat(tags.columnType()).isInstanceOf(ArrayTypeName.class);
        assertThat(((ArrayTypeName) tags.columnType()).componentType().toString())
            .isEqualTo("java.lang.String");
        assertThat(tags.columnClass()).isEqualTo("[Ljava.lang.String;");
    }

    @Test
    void allColumnsOf_carriesColumnTypeForEveryColumn() {
        // The full-row iterator (the reachable crash path's column source) decodes every column,
        // so a mixed scalar/array row all carries a non-null columnType.
        var cols = catalog().allColumnsOf("array_holder");
        assertThat(cols).isNotEmpty();
        assertThat(cols).allSatisfy(c -> assertThat(c.columnType()).isNotNull());
        var flags = cols.stream().filter(c -> c.sqlName().equals("flags")).findFirst().orElseThrow();
        assertThat(flags.columnType()).isInstanceOf(ArrayTypeName.class);
    }
}
