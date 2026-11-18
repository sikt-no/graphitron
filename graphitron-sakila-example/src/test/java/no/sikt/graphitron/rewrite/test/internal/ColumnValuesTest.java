package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.generated.util.ColumnValues;
import no.sikt.graphitron.rewrite.fixtures.codegen.OrgCodeStringConverter;
import no.sikt.graphitron.rewrite.fixtures.codegen.UtcLocalDateTimeConverter;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.Converter;
import org.jooq.DataType;
import org.jooq.impl.DefaultDataType;
import org.jooq.impl.SQLDataType;
import org.jooq.postgres.extensions.bindings.CitextBinding;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.rewrite.test.jooq.public_.Tables.CURSOR_PERIOD;
import static no.sikt.graphitron.rewrite.test.jooq.public_.Tables.CURSOR_SORT_TEXT;
import static no.sikt.graphitron.rewrite.test.jooq.public_.Tables.FILM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/**
 * Direct unit coverage for the generator-emitted {@link ColumnValues} helper over column types no
 * sakila key column has: a narrow integer, a {@code NUMERIC} with a declared precision and scale, and
 * a converter-backed {@code bigint}. The execution tier reaches the helper only through
 * {@code int4} and converter-backed keys, so the precision clause, the numeric value a converted
 * column exposes, and a converter that wraps on its way to the database are pinned here.
 *
 * <p>{@code exact} is the inverse of the node-id encoder, so it accepts only the text the encoder
 * writes for a value of the column. It parses the text as the column says: as the converter's user
 * type where jOOQ has a text form for it, through the column's own coercion otherwise, so a
 * converter-backed slot keeps what its user type's text carries. {@code coerce} is the documented
 * coercion of an authored value, minus a wrap. {@code folded} names the value a converter gave back
 * in place of the parsed one, which the node-id decode logs when it refuses such a slot.
 * {@code writtenByDatabase} decides which party writes and reads a connection cursor's element:
 * pinned for one type of each family on each side, and for the columns codegen types through
 * jOOQ's PostgreSQL extensions or leaves without a SQL type.
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
        assertThat(ColumnValues.exact(DATE_WITH_TIME, SMALL_HOURS))
            .isEqualTo(SMALL_HOURS_VALUE);
    }

    @Test
    void exact_userTypeSlot_overADatabaseTypeThatCannotHoldTheTime_isRefused() {
        assertThat(ColumnValues.exact(DATE_ONLY, SMALL_HOURS))
            .as("the trip comes back at 00:00, never another row")
            .isNull();
        assertThat(ColumnValues.exact(DATE_ONLY, "2007-08-22T00:00"))
            .isEqualTo(LocalDateTime.of(2007, 8, 22, 0, 0));
    }

    @Test
    void exact_roundTripsEachUserTypeTheFixturesUse() {
        assertThat(ColumnValues.exact(CHANGE_INSTANT, SMALL_HOURS)).isEqualTo(SMALL_HOURS_VALUE);
        assertThat(ColumnValues.exact(ORG_CODE, "1120")).isEqualTo("1120");
        assertThat(ColumnValues.exact(ORG_CODE, "abc")).as("Long.valueOf refuses it").isNull();
        assertThat(ColumnValues.exact(TRUNCATING, "-12")).isEqualTo(-12L);
    }

    @Test
    void exact_databaseTypeSlot_decodesOnlyWhenTheConvertersTextAgrees() {
        assertThat(ColumnValues.exact(PLAIN_CODE, "186")).isEqualTo(new PlainCode(186));
        assertThat(ColumnValues.exact(WRAPPED_CODE, "186"))
            .as("WrappedCode[value=186] is not the slot text")
            .isNull();
    }

    // ===== folded: a converter that does not give the parsed value back =====

    @Test
    void folded_namesWhatALossyConverterGivesBack() {
        assertThat(ColumnValues.folded(DATE_ONLY, SMALL_HOURS))
            .as("the time part the database type cannot hold")
            .isEqualTo(LocalDateTime.of(2007, 8, 22, 0, 0));
    }

    @Test
    void folded_isNullWhereNoConverterLostAnything() {
        assertThat(ColumnValues.folded(DATE_ONLY, "2007-08-22T00:00")).as("round-trips").isNull();
        assertThat(ColumnValues.folded(DATE_WITH_TIME, SMALL_HOURS)).as("lossless").isNull();
        assertThat(ColumnValues.folded(INTEGER, "01")).as("a padded number, no converter").isNull();
        assertThat(ColumnValues.folded(WRAPPED_CODE, "186")).as("a database-type slot").isNull();
        assertThat(ColumnValues.folded(ORG_CODE, "abc")).as("does not parse").isNull();
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

    // ===== fromDatabase: a value written as the column's database value, as a cursor carries it =====

    /** A {@code bigint} whose user text is not the database text: 186 reads as {@code "C186"}. */
    private static final DataType<String> PREFIXED_CODE = SQLDataType.BIGINT.asConvertedDataType(
        Converter.ofNullable(Long.class, String.class, l -> "C" + l, s -> Long.valueOf(s.substring(1))));

    @Test
    void fromDatabase_parsesAsTheDatabaseTypeAndRunsTheConverter() {
        assertThat(ColumnValues.fromDatabase(ORG_CODE, "186")).isEqualTo("186");
        assertThat(ColumnValues.fromDatabase(PREFIXED_CODE, "186"))
            .as("the converted value, not the String input handed back as a user value")
            .isEqualTo("C186");
        assertThat(ColumnValues.fromDatabase(INTEGER, "1234")).isEqualTo(1234);
        assertThat(ColumnValues.fromDatabase(BIGINT, "1234567890123456789"))
            .as("a 19-digit number keeps every digit").isEqualTo(1234567890123456789L);
    }

    @Test
    void fromDatabase_readsASqlDateAsADateTime_keepingItsTimeOfDay() {
        assertThat(sqlDateFromDatabase("2003-08-15T13:14:15")).isEqualTo(Timestamp.valueOf("2003-08-15 13:14:15"));
        assertThat(sqlDateFromDatabase("2003-08-15T00:00:00")).isEqualTo(Timestamp.valueOf("2003-08-15 00:00:00"));
        assertThat(sqlDateFromDatabase("2003-08-15")).isEqualTo(Timestamp.valueOf("2003-08-15 00:00:00"));
        assertThat(sqlDateFromDatabase("2003-08-15T13:14:15+01:00"))
            .as("no date-time, so the plain date parse still reads it, at midnight")
            .isEqualTo(Timestamp.valueOf("2003-08-15 00:00:00"));
        assertThat(ColumnValues.fromDatabase(SQLDataType.DATE, "garbage")).isNull();
    }

    /** The {@code java.sql.Date} {@code fromDatabase} reads, as a timestamp so the comparison sees its time of day. */
    private static Timestamp sqlDateFromDatabase(String text) {
        java.sql.Date date = ColumnValues.fromDatabase(SQLDataType.DATE, text);
        assertThat(date).as(text).isNotNull();
        return new Timestamp(date.getTime());
    }

    @Test
    void fromDatabase_readsATimestamptzRenderingAsTheUtcLocalDateTime() {
        for (String text : new String[] {"2007-08-22T02:24:00+00:00", "2007-08-22T02:24Z",
                "2007-08-22T04:24:00.000000+02:00"}) {
            assertThat(ColumnValues.fromDatabase(CHANGE_INSTANT, text)).as(text).isEqualTo(SMALL_HOURS_VALUE);
        }
        assertThat(ColumnValues.fromDatabase(CHANGE_INSTANT, SMALL_HOURS))
            .as("the user type's own text is no timestamptz")
            .isNull();
    }

    // ===== writtenByDatabase: which party writes and reads a column's cursor element =====

    @Test
    void writtenByDatabase_givesJavaTheColumnsItReadsExactly() {
        for (DataType<?> type : List.<DataType<?>>of(SMALLINT, INTEGER, BIGINT, NUMERIC_4_1, SQLDataType.DOUBLE,
                SQLDataType.BOOLEAN, SQLDataType.VARCHAR(20), SQLDataType.CHAR(2), SQLDataType.CLOB,
                SQLDataType.DATE, SQLDataType.LOCALDATE, SQLDataType.LOCALDATETIME, SQLDataType.TIMESTAMP,
                SQLDataType.OFFSETDATETIME, SQLDataType.INSTANT, SQLDataType.UUID,
                FILM.RATING.getDataType(), ORG_CODE, CHANGE_INSTANT)) {
            assertThat(ColumnValues.writtenByDatabase(type)).as(type.toString()).isFalse();
        }
    }

    @Test
    void writtenByDatabase_givesTheDatabaseEveryOtherColumn() {
        for (DataType<?> type : List.<DataType<?>>of(SQLDataType.LOCALTIME, SQLDataType.TIME,
                SQLDataType.OFFSETTIME, SQLDataType.INTERVAL, SQLDataType.BLOB, SQLDataType.VARBINARY,
                SQLDataType.INTEGER.array(), SQLDataType.VARCHAR.array(), SQLDataType.JSONB, SQLDataType.OTHER,
                Span.TYPE, CURSOR_PERIOD.VALID_PERIOD.getDataType(), CURSOR_SORT_TEXT.MAC.getDataType(),
                CURSOR_SORT_TEXT.SPAN.getDataType())) {
            assertThat(ColumnValues.writtenByDatabase(type)).as(type.toString()).isTrue();
        }
    }

    @Test
    void writtenByDatabase_givesACitextColumnBoundByTheExtensionsToTheDatabase() {
        // How jOOQ codegen types a citext column when jooq-postgres-extensions is on its classpath: a
        // type jOOQ has no SQL type for, read through CitextBinding. The database reads its element
        // back as citext, so the seek compares as the column does, ignoring case.
        DataType<String> citext = DefaultDataType.getDefaultDataType("\"public\".\"citext\"").nullable(false)
            .asConvertedDataType(new CitextBinding());
        assertThat(citext.getSQLDataType()).isNull();
        assertThat(ColumnValues.writtenByDatabase(citext)).isTrue();
    }

    @Test
    void fromDatabase_refusesWhatTheColumnCannotHold() {
        assertThat(ColumnValues.fromDatabase(NUMERIC_4, "99999")).as("five digits in NUMERIC(4)").isNull();
        assertThat(ColumnValues.fromDatabase(NUMERIC_4, "9999")).isEqualByComparingTo("9999");
        assertThat(ColumnValues.fromDatabase(INTEGER, "abc")).isNull();
        assertThat(ColumnValues.fromDatabase(INTEGER, null)).isNull();
    }

    // ===== inRange: a value's size is bounded before anything parses or rescales it =====

    /** A value refused on its size is refused before it is parsed or rescaled, well inside this budget. */
    private static final Duration PROMPTLY = Duration.ofSeconds(1);
    private static final DataType<BigInteger> DECIMAL_INTEGER = SQLDataType.DECIMAL_INTEGER;
    private static final DataType<BigDecimal> NUMERIC = SQLDataType.NUMERIC;
    private static final DataType<Double> DOUBLE = SQLDataType.DOUBLE;
    private static final String MANY_DIGITS = "7".repeat(500_000);

    @Test
    void everyEntryPoint_refusesAnExtremeNumberPromptly_onEveryNumericColumn() {
        List<DataType<?>> types = List.of(SMALLINT, INTEGER, BIGINT, DECIMAL_INTEGER, NUMERIC, NUMERIC_4_1, DOUBLE,
            ORG_CODE, TRUNCATING);
        List<String> texts = List.of("1e30000000", "-1e30000000", "1E+30000000", "1e-30000000", "0e30000000",
            " 1e30000000 ", MANY_DIGITS, "0." + MANY_DIGITS);
        assertTimeout(PROMPTLY, () -> {
            for (DataType<?> type : types) {
                for (String text : texts) {
                    String what = type.getName() + " " + text.substring(0, Math.min(16, text.length()));
                    assertThat(ColumnValues.inRange(type, text)).as(what).isFalse();
                    assertThat(ColumnValues.coerce(type, text)).as("coerce " + what).isNull();
                    assertThat(ColumnValues.exact(type, text)).as("exact " + what).isNull();
                    assertThat(ColumnValues.folded(type, text)).as("folded " + what).isNull();
                    assertThat(ColumnValues.fromDatabase(type, text)).as("fromDatabase " + what).isNull();
                }
                assertThat(ColumnValues.coerce(type, new BigDecimal("1e30000000"))).as(type.getName()).isNull();
                assertThat(ColumnValues.coerce(type, new BigDecimal("1e-30000000"))).as(type.getName()).isNull();
                assertThat(ColumnValues.coerce(type, BigInteger.ONE.shiftLeft(1_000_000))).as(type.getName()).isNull();
            }
        });
    }

    @Test
    void inRange_passesEveryValueTheColumnsNumberClassHolds() {
        assertThat(ColumnValues.coerce(BIGINT, "9223372036854775807")).isEqualTo(Long.MAX_VALUE);
        assertThat(ColumnValues.coerce(BIGINT, "-9223372036854775808")).isEqualTo(Long.MIN_VALUE);
        assertThat(ColumnValues.exact(BIGINT, "-9223372036854775808")).isEqualTo(Long.MIN_VALUE);
        assertThat(ColumnValues.coerce(SMALLINT, "-32768")).isEqualTo((short) -32768);
        assertThat(ColumnValues.coerce(INTEGER, "1e3")).as("an exponent within range still binds").isEqualTo(1000);
        assertThat(ColumnValues.coerce(INTEGER, "1.5" + "0".repeat(ColumnValues.MAX_DIGITS - 1)))
            .as("MAX_DIGITS fraction digits").isEqualTo(1);
        assertThat(ColumnValues.coerce(INTEGER, "1.5" + "0".repeat(ColumnValues.MAX_DIGITS)))
            .as("one fraction digit more").isNull();
        String widest = "9".repeat(ColumnValues.MAX_DIGITS);
        assertThat(ColumnValues.coerce(NUMERIC, widest)).as("MAX_DIGITS integer digits").isEqualByComparingTo(widest);
        assertThat(ColumnValues.coerce(DECIMAL_INTEGER, widest)).isEqualTo(new BigInteger(widest));
        assertThat(ColumnValues.coerce(NUMERIC, widest + "9")).as("one integer digit more").isNull();
        assertThat(ColumnValues.coerce(DOUBLE, "1e308")).isEqualTo(1e308);
        assertThat(ColumnValues.inRange(SQLDataType.VARCHAR, "1e30000000"))
            .as("a text column reads the text as text, at any exponent").isTrue();
        assertThat(ColumnValues.coerce(SQLDataType.VARCHAR, "1e30000000")).isEqualTo("1e30000000");
        assertThat(ColumnValues.inRange(SQLDataType.VARCHAR, MANY_DIGITS)).isTrue();
    }

    // ===== fromWire: a client's wire value, coerced as jOOQ coerces it, behind the same bound =====

    @Test
    void fromWire_refusesAnExtremeNumberPromptly_onEveryNumericColumn() {
        List<DataType<?>> types = List.of(SMALLINT, INTEGER, BIGINT, DECIMAL_INTEGER, NUMERIC, NUMERIC_4_1, DOUBLE,
            ORG_CODE, TRUNCATING);
        List<String> texts = List.of("1e30000000", "-1e30000000", "1E+30000000", "1e-30000000", "0e30000000",
            " 1e30000000 ", "1e999999999", "9e-999999999", MANY_DIGITS, "0." + MANY_DIGITS, "-" + MANY_DIGITS);
        assertTimeout(PROMPTLY, () -> {
            for (DataType<?> type : types) {
                for (String text : texts) {
                    String what = type.getName() + " " + text.substring(0, Math.min(16, text.length()));
                    assertThat(ColumnValues.fromWire(type, text)).as(what).isNull();
                }
            }
        });
    }

    @Test
    void fromWire_coercesWhatJooqCoerces_withinTheBound() {
        assertThat(ColumnValues.fromWire(INTEGER, "12")).isEqualTo(12);
        assertThat(ColumnValues.fromWire(INTEGER, " 12 ")).as("trimmed as jOOQ trims").isEqualTo(12);
        assertThat(ColumnValues.fromWire(INTEGER, "1e3")).isEqualTo(1000);
        assertThat(ColumnValues.fromWire(INTEGER, "abc")).as("what jOOQ cannot read stays null").isNull();
        assertThat(ColumnValues.fromWire(INTEGER, null)).isNull();
        assertThat(ColumnValues.fromWire(INTEGER, 7)).isEqualTo(7);
        assertThat(ColumnValues.fromWire(BIGINT, "9223372036854775807")).isEqualTo(Long.MAX_VALUE);
        String widest = "9".repeat(ColumnValues.MAX_DIGITS);
        assertThat(ColumnValues.fromWire(DECIMAL_INTEGER, widest)).isEqualTo(new BigInteger(widest));
        assertThat(ColumnValues.fromWire(NUMERIC, widest)).isEqualByComparingTo(widest);
        assertThat(ColumnValues.fromWire(SQLDataType.VARCHAR, MANY_DIGITS))
            .as("a text column reads any text as text").isEqualTo(MANY_DIGITS);
    }

    @Test
    void fromDatabase_readsBackEveryDoubleToTextWrites() {
        for (double d : new double[] {Double.MIN_VALUE, -Double.MIN_VALUE, Double.MAX_VALUE, 1e-300, 0.1}) {
            String text = ColumnValues.toText(DOUBLE, d).text();
            assertThat(ColumnValues.fromDatabase(DOUBLE, text)).as(text).isEqualTo(d);
        }
    }
}
