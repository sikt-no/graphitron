package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.generated.util.ColumnValues;
import no.sikt.graphitron.rewrite.fixtures.codegen.OrgCodeStringConverter;
import no.sikt.graphitron.rewrite.fixtures.codegen.UtcLocalDateTimeConverter;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.Converter;
import org.jooq.DataType;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit coverage for the generator-emitted {@link ColumnValues} helper over column types no
 * sakila key column has: a narrow integer, a {@code NUMERIC} with a declared precision and scale, and
 * a converter-backed {@code bigint}. The execution tier reaches the helper only through
 * {@code int4} and converter-backed keys, so the precision clause, the numeric value a converted
 * column exposes, and a converter that wraps on its way to the database are pinned here.
 *
 * <p>{@code exact} is the inverse of the node-id encoder, so it accepts only the text the encoder
 * writes for a value of the column. Its parse class is the one the generator hands it for a slot
 * ({@code SlotParse}): {@code null} for a column with no converter or a converter to a class jOOQ
 * has no text form for, the converter's user type otherwise. {@code coerce} is the documented
 * coercion of an authored value, minus a wrap.
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
        assertThat(ColumnValues.exact(SMALLINT, null, "1234")).isEqualTo((short) 1234);
        assertThat(ColumnValues.exact(INTEGER, null, "99999")).isEqualTo(99999);
        assertThat(ColumnValues.exact(BIGINT, null, "99999999999")).isEqualTo(99999999999L);
        assertThat(ColumnValues.exact(NUMERIC_4, null, "9999")).isEqualByComparingTo("9999");
        assertThat(ColumnValues.exact(NUMERIC_4_1, null, "999.9")).isEqualByComparingTo("999.9");
        assertThat(ColumnValues.exact(ORG_CODE, String.class, "186")).isEqualTo("186");
        assertThat(ColumnValues.exact(TRUNCATING, Long.class, "1234")).isEqualTo(1234L);
    }

    @Test
    void exact_refusesTextTheEncoderNeverWrites() {
        for (String text : new String[] {"abc", "", " 1234", "01", "1.5"}) {
            assertThat(ColumnValues.exact(SMALLINT, null, text)).as("smallint '%s'", text).isNull();
            assertThat(ColumnValues.exact(INTEGER, null, text)).as("integer '%s'", text).isNull();
            assertThat(ColumnValues.exact(BIGINT, null, text)).as("bigint '%s'", text).isNull();
        }
        assertThat(ColumnValues.exact(SMALLINT, null, (String) null)).isNull();
    }

    @Test
    void exact_refusesAWrappedNumber() {
        assertThat(ColumnValues.exact(SMALLINT, null, "99999")).as("wraps to -31073").isNull();
        assertThat(ColumnValues.exact(SMALLINT, null, "99999999999")).isNull();
        assertThat(ColumnValues.exact(INTEGER, null, "99999999999")).as("wraps to 1215752191").isNull();
        assertThat(ColumnValues.exact(INTEGER, null, "4294967297")).as("wraps to 1").isNull();
    }

    @Test
    void exact_refusesANumberOutsideTheDeclaredPrecisionOrScale() {
        assertThat(ColumnValues.exact(NUMERIC_4, null, "99999")).as("five integer digits in NUMERIC(4)").isNull();
        assertThat(ColumnValues.exact(NUMERIC_4, null, "1.5")).as("a fraction in NUMERIC(4)").isNull();
        assertThat(ColumnValues.exact(NUMERIC_4_1, null, "1000.0")).as("four integer digits in NUMERIC(4,1)").isNull();
        assertThat(ColumnValues.exact(NUMERIC_4_1, null, "1.55")).as("two fraction digits in NUMERIC(4,1)").isNull();
    }

    @Test
    void exact_followsAConverterBackedValueToItsDatabaseValue() {
        assertThat(ColumnValues.exact(ORG_CODE, String.class, "abc")).as("Long.valueOf refuses it").isNull();
        assertThat(ColumnValues.exact(ORG_CODE, String.class, "99999999999999999999")).as("outside bigint").isNull();
        assertThat(ColumnValues.exact(ORG_CODE, String.class, "0186")).as("the canonical value is \"186\"").isNull();
    }

    @Test
    void exact_refusesAConverterSideWrapThatFromDoesNotGiveBack() {
        assertThat(ColumnValues.exact(TRUNCATING, Long.class, "70000")).isNull();
    }

    // ===== The parse step: a converter-backed slot is parsed as the type it was encoded from =====

    private static final String SMALL_HOURS = "2007-08-22T02:24";
    private static final LocalDateTime SMALL_HOURS_VALUE = LocalDateTime.of(2007, 8, 22, 2, 24);

    /** An Oracle {@code DATE} the way a consumer maps it: a {@code java.sql.Date} carrying the time in its milliseconds. */
    private static final DataType<LocalDateTime> DATE_WITH_TIME =
        SQLDataType.DATE.asConvertedDataType(Converter.ofNullable(java.sql.Date.class, LocalDateTime.class,
            date -> new Timestamp(date.getTime()).toLocalDateTime(),
            time -> new java.sql.Date(Timestamp.valueOf(time).getTime())));
    /** A database type that cannot hold the time at all. */
    private static final DataType<LocalDateTime> DATE_ONLY =
        SQLDataType.LOCALDATE.asConvertedDataType(Converter.ofNullable(LocalDate.class, LocalDateTime.class,
            LocalDate::atStartOfDay, LocalDateTime::toLocalDate));
    /** The execution fixture's {@code change_instant_domain}: a {@code timestamptz} read at UTC. */
    private static final DataType<LocalDateTime> CHANGE_INSTANT =
        SQLDataType.TIMESTAMPWITHTIMEZONE.asConvertedDataType(new UtcLocalDateTimeConverter());

    /** A consumer value class whose {@code toString()} is its database-side text. */
    record PlainCode(long value) {
        @Override public String toString() { return Long.toString(value); }
    }
    /** One whose {@code toString()} is not. */
    record WrappedCode(long value) {}

    private static final DataType<PlainCode> PLAIN_CODE = SQLDataType.BIGINT.asConvertedDataType(
        Converter.ofNullable(Long.class, PlainCode.class, PlainCode::new, PlainCode::value));
    private static final DataType<WrappedCode> WRAPPED_CODE = SQLDataType.BIGINT.asConvertedDataType(
        Converter.ofNullable(Long.class, WrappedCode.class, WrappedCode::new, WrappedCode::value));

    @Test
    void exact_userTypeSlot_keepsTheTimeADateOnlyParseWouldDrop() {
        assertThat(ColumnValues.exact(DATE_WITH_TIME, LocalDateTime.class, SMALL_HOURS))
            .isEqualTo(SMALL_HOURS_VALUE);
        assertThat(ColumnValues.exact(DATE_WITH_TIME, null, SMALL_HOURS))
            .as("parsed as java.sql.Date, the time is gone and the trip lands on 00:00")
            .isNull();
    }

    @Test
    void exact_userTypeSlot_overADatabaseTypeThatCannotHoldTheTime_isRefused() {
        assertThat(ColumnValues.exact(DATE_ONLY, LocalDateTime.class, SMALL_HOURS))
            .as("the trip comes back at 00:00, never another row")
            .isNull();
        assertThat(ColumnValues.exact(DATE_ONLY, LocalDateTime.class, "2007-08-22T00:00"))
            .isEqualTo(LocalDateTime.of(2007, 8, 22, 0, 0));
    }

    @Test
    void exact_roundTripsEachUserTypeTheFixturesUse() {
        assertThat(ColumnValues.exact(CHANGE_INSTANT, LocalDateTime.class, SMALL_HOURS)).isEqualTo(SMALL_HOURS_VALUE);
        assertThat(ColumnValues.exact(CHANGE_INSTANT, null, SMALL_HOURS))
            .as("OffsetDateTime does not parse text without an offset")
            .isNull();
        assertThat(ColumnValues.exact(ORG_CODE, String.class, "1120")).isEqualTo("1120");
        assertThat(ColumnValues.exact(ORG_CODE, String.class, "abc")).as("Long.valueOf refuses it").isNull();
        assertThat(ColumnValues.exact(TRUNCATING, Long.class, "-12")).isEqualTo(-12L);
    }

    @Test
    void exact_databaseTypeSlot_decodesOnlyWhenTheConvertersTextAgrees() {
        assertThat(ColumnValues.exact(PLAIN_CODE, null, "186")).isEqualTo(new PlainCode(186));
        assertThat(ColumnValues.exact(WRAPPED_CODE, null, "186"))
            .as("WrappedCode[value=186] is not the slot text")
            .isNull();
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
