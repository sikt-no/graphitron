package no.sikt.graphitron.render;

import no.sikt.graphitron.command.CatalogColumn;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-arm unit tests for {@link ColumnComparison}: a total function over two columns' Java types,
 * needing no schema, catalog or fixture. Emissions are pinned as exact text, because what they say
 * is whether the generated module compiles at all and whether the SQL it issues moved. A comparison
 * that reads plausibly and coerces the wrong operand, or coerces a pair that never diverged, is
 * exactly the failure a shape assertion would let through.
 *
 * <p>The undiverged arms matter as much as the diverged ones. Every schema that compiles today goes
 * through them, so a rule that fired one coerce too eagerly would churn every approved generated
 * source in the tree while fixing nothing.
 */
@UnitTier
class ColumnComparisonTest {

    /** The raw end of a converter-diverged key: no converter, so jOOQ's own boxed type. */
    private static final ColumnRef RAW =
        new ColumnRef("org_code", "ORG_CODE", "java.lang.Long");

    /** The converted end of the same key: a {@code Converter<Long, String>} is registered on it. */
    private static final ColumnRef CONVERTED =
        new ColumnRef("org_code", "ORG_CODE", "java.lang.String");

    /**
     * A hand-built placeholder ref: {@code "related_1"} is not a class name, so
     * {@link CatalogRefs#columnType(ColumnRef)} is null and the ref exists only for its names.
     */
    private static final ColumnRef PLACEHOLDER =
        new ColumnRef("org_code", "ORG_CODE", "related_1");

    private static final TableRef CONVERTER_ORG = new TableRef(
        "converter_org", "CONVERTER_ORG",
        "com.example.jooq.tables.ConverterOrg",
        "com.example.jooq.tables.records.ConverterOrgRecord",
        "com.example.jooq.Tables",
        List.of(CONVERTED), List.of(CONVERTED));

    // ----------------------------------------------------------------------------------------
    // equality: both operands are aliased table columns
    // ----------------------------------------------------------------------------------------

    @Test
    void twoColumnsOfTheSameTypeCompareUncoerced() {
        assertThat(ColumnComparison.equality("c0", RAW, "table", RAW).toString())
            .as("this is every schema that compiles today; a coerce here would churn approved output")
            .isEqualTo("c0.ORG_CODE.eq(table.ORG_CODE)");
    }

    @Test
    void aDivergedPairCoercesTheArgumentOntoTheReceiver() {
        assertThat(ColumnComparison.equality("c0", RAW, "table", CONVERTED).toString())
            .isEqualTo("c0.ORG_CODE.eq(table.ORG_CODE.coerce(c0.ORG_CODE))");
    }

    @Test
    void theCoerceFollowsTheReceiverRatherThanTheConvertedSide() {
        assertThat(ColumnComparison.equality("c0", CONVERTED, "table", RAW).toString())
            .as("the receiver wins in both directions; neither raw nor converted is recoverable "
                + "from a TypeName, and the direction is invisible in the emitted SQL")
            .isEqualTo("c0.ORG_CODE.eq(table.ORG_CODE.coerce(c0.ORG_CODE))");
    }

    @Test
    void aPlaceholderTypeOnEitherSideComparesUncoerced() {
        assertThat(ColumnComparison.equality("c0", PLACEHOLDER, "table", CONVERTED).toString())
            .as("a null type is no reason to coerce, not a divergence")
            .isEqualTo("c0.ORG_CODE.eq(table.ORG_CODE)");
        assertThat(ColumnComparison.equality("c0", CONVERTED, "table", PLACEHOLDER).toString())
            .isEqualTo("c0.ORG_CODE.eq(table.ORG_CODE)");
    }

    @Test
    void commandTierColumnsDecodeTheirBoundTypeAndApplyTheSameRule() {
        var raw = new CatalogColumn("org_code", "ORG_CODE", "java.lang.Long");
        var converted = new CatalogColumn("org_code", "ORG_CODE", "java.lang.String");

        assertThat(ColumnComparison.equality("c0", raw, "o0", raw).toString())
            .isEqualTo("c0.ORG_CODE.eq(o0.ORG_CODE)");
        assertThat(ColumnComparison.equality("c0", raw, "o0", converted).toString())
            .as("a store-sourced reader sees the divergence too: javaTypeName is the "
                + "post-converter type jOOQ reports")
            .isEqualTo("c0.ORG_CODE.eq(o0.ORG_CODE.coerce(c0.ORG_CODE))");
    }

    // ----------------------------------------------------------------------------------------
    // equalityAgainstField: the right operand is some other Field expression
    // ----------------------------------------------------------------------------------------

    @Test
    void aFieldOperandTypedByAnUndivergedColumnComparesUncoerced() {
        assertThat(ColumnComparison.equalityAgainstField(
                "c0", RAW, RAW, lookup()).toString())
            .isEqualTo("c0.ORG_CODE.eq(parentInput.field(\"org_code\", "
                + "com.example.jooq.Tables.CONVERTER_ORG.ORG_CODE.getDataType()))");
    }

    @Test
    void aFieldOperandTypedByADivergedColumnCoercesOntoTheReceiver() {
        assertThat(ColumnComparison.equalityAgainstField(
                "c0", RAW, CONVERTED, lookup()).toString())
            .as("the lookup's DataType comes from the parent column, the receiver from the child")
            .isEqualTo("c0.ORG_CODE.eq(parentInput.field(\"org_code\", "
                + "com.example.jooq.Tables.CONVERTER_ORG.ORG_CODE.getDataType()).coerce(c0.ORG_CODE))");
    }

    // ----------------------------------------------------------------------------------------
    // equalityAgainstValue: the right operand is a bare Java value
    // ----------------------------------------------------------------------------------------

    @Test
    void anUndivergedValueOperandBindsNothingAndComparesDirectly() {
        assertThat(ColumnComparison.equalityAgainstValue(
                "c0", RAW, RAW, CONVERTER_ORG, value()).toString())
            .as("byte-identical to what this site emitted before the mint existed")
            .isEqualTo("c0.ORG_CODE.eq(parentRecord.get(DSL.name(\"org_code\"), java.lang.Long.class))");
    }

    @Test
    void aDivergedValueOperandBindsAtItsSourceColumnThenCoerces() {
        assertThat(ColumnComparison.equalityAgainstValue(
                "c0", RAW, CONVERTED, CONVERTER_ORG, value()).toString())
            .as("the two rules compose in order: bind at the source column's DataType, which makes "
                + "the value a Field rendering through that column's converter, then coerce")
            .isEqualTo("c0.ORG_CODE.eq(org.jooq.impl.DSL.val("
                + "parentRecord.get(DSL.name(\"org_code\"), java.lang.Long.class), "
                + "com.example.jooq.Tables.CONVERTER_ORG.ORG_CODE.getDataType()).coerce(c0.ORG_CODE))");
    }

    @Test
    void aPlaceholderTypeSuppressesTheValueBindToo() {
        assertThat(ColumnComparison.equalityAgainstValue(
                "c0", PLACEHOLDER, CONVERTED, CONVERTER_ORG, value()).toString())
            .isEqualTo("c0.ORG_CODE.eq(parentRecord.get(DSL.name(\"org_code\"), java.lang.Long.class))");
    }

    private static CodeBlock lookup() {
        return CodeBlock.of("parentInput.field($S, $T.$L.$L.getDataType())",
            "org_code", CatalogRefs.constantsClass(CONVERTER_ORG), CONVERTER_ORG.javaFieldName(),
            CONVERTED.javaName());
    }

    private static CodeBlock value() {
        return CodeBlock.of("parentRecord.get(DSL.name($S), $T.class)", "org_code", Long.class);
    }
}
