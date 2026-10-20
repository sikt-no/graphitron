package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.generated.util.ColumnValues;
import no.sikt.graphitron.rewrite.fixtures.codegen.OrgCodeStringConverter;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.Converter;
import org.jooq.DataType;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit coverage for the generator-emitted {@link ColumnValues} helper over column types no
 * sakila key column has: a narrow integer, a {@code NUMERIC} with a declared precision and scale, and
 * a converter-backed {@code bigint}. The execution tier reaches the helper only through
 * {@code int4} and converter-backed keys, so the precision clause, the numeric value a converted
 * column exposes, and a converter that wraps on its way to the database are pinned here.
 *
 * <p>{@code exact} is the inverse of the node-id encoder, so it accepts only the text the encoder
 * writes for a value of the column. {@code coerce} is the documented coercion of an authored value,
 * minus a wrap.
 */
@UnitTier
class ColumnValuesTest {

    private static final DataType<Short> SMALLINT = SQLDataType.SMALLINT;
    private static final DataType<Integer> INTEGER = SQLDataType.INTEGER;
    private static final DataType<Long> BIGINT = SQLDataType.BIGINT;
    private static final DataType<BigDecimal> NUMERIC_4 = SQLDataType.NUMERIC.precision(4);
    private static final DataType<BigDecimal> NUMERIC_4_1 = SQLDataType.NUMERIC.precision(4, 1);
    private static final DataType<String> ORG_CODE = SQLDataType.BIGINT.asConvertedDataType(new OrgCodeStringConverter());
    private static final DataType<Long> TRUNCATING = SQLDataType.SMALLINT.asConvertedDataType(new TruncatingConverter());

    /** Takes a {@code long} to a {@code smallint} by a narrowing cast, silently, as a careless converter might. */
    static final class TruncatingConverter implements Converter<Short, Long> {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        @Override public Long from(Short databaseObject) { return databaseObject == null ? null : databaseObject.longValue(); }
        @Override public Short to(Long userObject) { return userObject == null ? null : (short) userObject.longValue(); }
        @Override public Class<Short> fromType() { return Short.class; }
        @Override public Class<Long> toType() { return Long.class; }
    }

    @Test
    void exact_acceptsTheEncodersImage() {
        assertThat(ColumnValues.exact(SMALLINT, "1234")).isEqualTo((short) 1234);
        assertThat(ColumnValues.exact(INTEGER, "99999")).isEqualTo(99999);
        assertThat(ColumnValues.exact(BIGINT, "99999999999")).isEqualTo(99999999999L);
        assertThat(ColumnValues.exact(NUMERIC_4, "9999")).isEqualByComparingTo("9999");
        assertThat(ColumnValues.exact(NUMERIC_4_1, "999.9")).isEqualByComparingTo("999.9");
        assertThat(ColumnValues.exact(ORG_CODE, "186")).isEqualTo("186");
        assertThat(ColumnValues.exact(TRUNCATING, "1234")).isEqualTo(1234L);
    }

    @Test
    void exact_refusesTextTheEncoderNeverWrites() {
        for (String text : new String[] {"abc", "", " 1234", "01", "1.5"}) {
            assertThat(ColumnValues.exact(SMALLINT, text)).as("smallint '%s'", text).isNull();
            assertThat(ColumnValues.exact(INTEGER, text)).as("integer '%s'", text).isNull();
            assertThat(ColumnValues.exact(BIGINT, text)).as("bigint '%s'", text).isNull();
        }
        assertThat(ColumnValues.exact(SMALLINT, (String) null)).isNull();
    }

    @Test
    void exact_refusesAWrappedNumber() {
        assertThat(ColumnValues.exact(SMALLINT, "99999")).as("wraps to -31073").isNull();
        assertThat(ColumnValues.exact(SMALLINT, "99999999999")).isNull();
        assertThat(ColumnValues.exact(INTEGER, "99999999999")).as("wraps to 1215752191").isNull();
        assertThat(ColumnValues.exact(INTEGER, "4294967297")).as("wraps to 1").isNull();
    }

    @Test
    void exact_refusesANumberOutsideTheDeclaredPrecisionOrScale() {
        assertThat(ColumnValues.exact(NUMERIC_4, "99999")).as("five integer digits in NUMERIC(4)").isNull();
        assertThat(ColumnValues.exact(NUMERIC_4, "1.5")).as("a fraction in NUMERIC(4)").isNull();
        assertThat(ColumnValues.exact(NUMERIC_4_1, "1000.0")).as("four integer digits in NUMERIC(4,1)").isNull();
        assertThat(ColumnValues.exact(NUMERIC_4_1, "1.55")).as("two fraction digits in NUMERIC(4,1)").isNull();
    }

    @Test
    void exact_followsAConverterBackedValueToItsDatabaseValue() {
        assertThat(ColumnValues.exact(ORG_CODE, "abc")).as("Long.valueOf refuses it").isNull();
        assertThat(ColumnValues.exact(ORG_CODE, "99999999999999999999")).as("outside bigint").isNull();
        assertThat(ColumnValues.exact(ORG_CODE, "0186")).as("the canonical value is \"186\"").isNull();
    }

    @Test
    void exact_refusesAConverterSideWrapThatFromDoesNotGiveBack() {
        assertThat(ColumnValues.exact(TRUNCATING, "70000")).isNull();
    }

    @Test
    void coerce_keepsTheDocumentedCoercion() {
        assertThat(ColumnValues.coerce(INTEGER, "1.5")).as("the integral part").isEqualTo(1);
        assertThat(ColumnValues.coerce(INTEGER, 1.5d)).isEqualTo(1);
        assertThat(ColumnValues.coerce(INTEGER, " 1234")).as("trimmed as jOOQ trims").isEqualTo(1234);
        assertThat(ColumnValues.coerce(INTEGER, 7L)).isEqualTo(7);
        assertThat(ColumnValues.coerce(NUMERIC_4_1, "1.5")).isEqualByComparingTo("1.5");
        assertThat(ColumnValues.coerce(ORG_CODE, "0186")).as("routes at the database value 186").isEqualTo("186");
        assertThat(ColumnValues.coerce(ORG_CODE, 186L)).isEqualTo("186");
    }

    @Test
    void coerce_refusesAWrapAndANumberOutsideThePrecision() {
        assertThat(ColumnValues.coerce(INTEGER, "4294967297")).isNull();
        assertThat(ColumnValues.coerce(INTEGER, 4294967297L)).isNull();
        assertThat(ColumnValues.coerce(SMALLINT, "99999")).isNull();
        assertThat(ColumnValues.coerce(NUMERIC_4, "99999")).isNull();
        assertThat(ColumnValues.coerce(NUMERIC_4, "1.5")).as("NUMERIC(4) admits no fraction digit").isNull();
        assertThat(ColumnValues.coerce(TRUNCATING, 70000L)).as("the converter wraps on its way down").isNull();
    }

    @Test
    void coerce_refusesWhatTheColumnCannotStore() {
        assertThat(ColumnValues.coerce(INTEGER, "abc")).isNull();
        assertThat(ColumnValues.coerce(INTEGER, "")).isNull();
        assertThat(ColumnValues.coerce(INTEGER, null)).isNull();
        assertThat(ColumnValues.coerce(ORG_CODE, "abc")).isNull();
        assertThat(ColumnValues.coerce(ORG_CODE, "99999999999999999999")).isNull();
    }
}
