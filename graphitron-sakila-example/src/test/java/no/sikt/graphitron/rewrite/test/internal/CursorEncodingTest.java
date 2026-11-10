package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.generated.schema.GraphitronClientException;
import no.sikt.graphitron.generated.util.ConnectionHelper;
import no.sikt.graphitron.rewrite.fixtures.codegen.UtcLocalDateTimeConverter;
import no.sikt.graphitron.rewrite.test.jooq.public_.enums.MpaaRating;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.Field;
import org.jooq.Param;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.types.ULong;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.rewrite.test.jooq.public_.Tables.FILM;
import static no.sikt.graphitron.rewrite.test.jooq.public_.Tables.VALIDITY_PERIOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cursor text {@link ConnectionHelper#encodeCursor} writes per column type, pinned against
 * Oracle's {@code JSON_ARRAY} rendering, which is what Graphitron 9 issued there: a number bare
 * with no trailing fractional zeros, a boolean bare, a date as a date-time without a fraction, a
 * timestamp with its fraction only when non-zero, a timestamp with time zone with its fraction
 * always and its offset, a string with Oracle's escapes. The literals below are Oracle's output for
 * the same values. The format depends only on the column and the value, never on the dialect, so
 * detached records over typed columns pin it on every database without one in the build.
 *
 * <p>Each case also reads back through {@link ConnectionHelper#decodeCursor} to the value it was
 * written from, except NaN, which JSON cannot hold.
 *
 * <p>The last section pins the read side's second format, the cursors the Graphitron 10 release
 * candidates issued: which format a cursor string is read as, and how each token binds per column
 * type, the way those releases bound it.
 */
@UnitTier
class CursorEncodingTest {

    private static String encode(List<Field<?>> columns, Object... values) {
        Record record = DSL.using(SQLDialect.DEFAULT).newRecord(columns.toArray(new Field<?>[0]));
        for (int i = 0; i < values.length; i++) {
            @SuppressWarnings("unchecked")
            Field<Object> column = (Field<Object>) columns.get(i);
            record.set(column, values[i]);
        }
        return ConnectionHelper.encodeCursor(record, columns);
    }

    private static String json(List<Field<?>> columns, Object... values) {
        return new String(Base64.getUrlDecoder().decode(encode(columns, values)), StandardCharsets.UTF_8);
    }

    /** The one-column cursor's JSON for {@code value} in a column of {@code type}. */
    private static <T> String json(org.jooq.DataType<T> type, T value) {
        return json(List.<Field<?>>of(DSL.field("c", type)), value);
    }

    private static List<Object> readBack(List<Field<?>> columns, Object... values) {
        var bound = new ArrayList<Object>();
        for (Field<?> seek : ConnectionHelper.decodeCursor(encode(columns, values), columns)) {
            bound.add(((Param<?>) seek).getValue());
        }
        return bound;
    }

    private static <T> Object readBack(org.jooq.DataType<T> type, T value) {
        return readBack(List.<Field<?>>of(DSL.field("c", type)), value).get(0);
    }

    // ===== sis's row =====

    @Test
    void sisRow_isGraphitron9sOracleCursor() {
        List<Field<?>> columns = List.of(
            DSL.field("institusjonsnr_eier", SQLDataType.NUMERIC.precision(8)),
            DSL.field("emnekode_nr", SQLDataType.NUMERIC.precision(8)),
            DSL.field("emnekode", SQLDataType.VARCHAR(12)),
            DSL.field("versjonskode", SQLDataType.VARCHAR(3)),
            DSL.field("beskrivelsesavsnitt", SQLDataType.VARCHAR(10)),
            DSL.field("sprak", SQLDataType.VARCHAR(10)),
            DSL.field("terminkode", SQLDataType.VARCHAR(4)),
            DSL.field("arstall", SQLDataType.NUMERIC.precision(4)));
        Object[] row = {new BigDecimal("1234"), new BigDecimal("185"), "MUS101", "1", "MÅL", "ENGELSK", "VÅR",
            new BigDecimal("2017")};
        assertThat(json(columns, row)).isEqualTo("[1234,185,\"MUS101\",\"1\",\"MÅL\",\"ENGELSK\",\"VÅR\",2017]");
        assertThat(encode(columns, row)).as("Graphitron 9's cursor for the row on Oracle")
            .isEqualTo("WzEyMzQsMTg1LCJNVVMxMDEiLCIxIiwiTcOFTCIsIkVOR0VMU0siLCJWw4VSIiwyMDE3XQ");
        assertThat(readBack(columns, row)).containsExactly(row);
    }

    // ===== Numbers =====

    @Test
    void numeric_isBareWithoutTrailingFractionalZeros() {
        assertThat(json(SQLDataType.NUMERIC, new BigDecimal("1.50"))).isEqualTo("[1.5]");
        assertThat(json(SQLDataType.NUMERIC.precision(10, 2), new BigDecimal("100.00"))).isEqualTo("[100]");
        assertThat(json(SQLDataType.NUMERIC, new BigDecimal("-0"))).isEqualTo("[0]");
        assertThat(json(SQLDataType.NUMERIC, new BigDecimal("0.00"))).isEqualTo("[0]");
        assertThat(json(SQLDataType.NUMERIC, new BigDecimal("0.0000001"))).as("plain, not 1E-7").isEqualTo("[0.0000001]");
        assertThat(json(SQLDataType.NUMERIC, new BigDecimal("123456789012345678"))).isEqualTo("[123456789012345678]");
        assertThat(json(SQLDataType.BIGINT, Long.MAX_VALUE)).isEqualTo("[9223372036854775807]");
        assertThat(json(SQLDataType.BIGINTUNSIGNED, ULong.valueOf("18446744073709551615")))
            .isEqualTo("[18446744073709551615]");
        assertThat(json(SQLDataType.INTEGER, 4)).isEqualTo("[4]");
        assertThat(json(SQLDataType.SMALLINT, (short) -7)).isEqualTo("[-7]");
        assertThat(json(SQLDataType.DOUBLE, 0.1d)).isEqualTo("[0.1]");
    }

    @Test
    void numeric_readsBackToTheValueWritten() {
        assertThat((BigDecimal) readBack(SQLDataType.NUMERIC.precision(10, 2), new BigDecimal("100.00")))
            .isEqualByComparingTo("100");
        assertThat((BigDecimal) readBack(SQLDataType.NUMERIC, new BigDecimal("0.0000001")))
            .isEqualByComparingTo("0.0000001");
        assertThat(readBack(SQLDataType.BIGINT, Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertThat(readBack(SQLDataType.BIGINTUNSIGNED, ULong.valueOf("18446744073709551615")))
            .isEqualTo(ULong.valueOf("18446744073709551615"));
        assertThat(readBack(SQLDataType.INTEGER, 4)).isEqualTo(4);
        assertThat(readBack(SQLDataType.DOUBLE, 0.1d)).isEqualTo(0.1d);
    }

    @Test
    void notANumber_isAString() {
        assertThat(json(SQLDataType.DOUBLE, Double.NaN)).as("JSON has no NaN").isEqualTo("[\"NaN\"]");
    }

    // ===== Booleans =====

    @Test
    void boolean_isBare() {
        assertThat(json(SQLDataType.BOOLEAN, true)).isEqualTo("[true]");
        assertThat(json(SQLDataType.BOOLEAN, false)).isEqualTo("[false]");
        assertThat(readBack(SQLDataType.BOOLEAN, true)).isEqualTo(true);
        assertThat(readBack(SQLDataType.BOOLEAN, false)).isEqualTo(false);
    }

    // ===== Dates and timestamps =====

    @Test
    void localDate_isADateTimeAtMidnight() {
        assertThat(json(SQLDataType.LOCALDATE, LocalDate.of(2003, 8, 15))).isEqualTo("[\"2003-08-15T00:00:00\"]");
        assertThat(readBack(SQLDataType.LOCALDATE, LocalDate.of(2003, 8, 15))).isEqualTo(LocalDate.of(2003, 8, 15));
    }

    @Test
    void sqlDate_keepsItsTimeOfDay() {
        var date = new java.sql.Date(Timestamp.valueOf("2003-08-15 13:14:15").getTime());
        assertThat(json(SQLDataType.DATE, date)).isEqualTo("[\"2003-08-15T13:14:15\"]");
        assertThat(((java.sql.Date) readBack(SQLDataType.DATE, date)).getTime())
            .as("binds the time of day, not midnight").isEqualTo(date.getTime());
    }

    @Test
    void sqlDate_keepsItsTimeOfDay_whateverTheJvmZone() {
        TimeZone previous = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Oslo"));
        try {
            var date = new java.sql.Date(Timestamp.valueOf("2003-08-15 13:14:15").getTime());
            assertThat(json(SQLDataType.DATE, date)).isEqualTo("[\"2003-08-15T13:14:15\"]");
            assertThat(((java.sql.Date) readBack(SQLDataType.DATE, date)).getTime()).isEqualTo(date.getTime());
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void timestamp_writesAFractionOnlyWhenNonZero_toSixDigitsOrNine() {
        assertThat(json(SQLDataType.LOCALDATETIME, LocalDateTime.of(2003, 8, 15, 13, 14, 15)))
            .isEqualTo("[\"2003-08-15T13:14:15\"]");
        assertThat(json(SQLDataType.LOCALDATETIME, LocalDateTime.of(2003, 8, 15, 13, 14, 15, 120_000_000)))
            .isEqualTo("[\"2003-08-15T13:14:15.120000\"]");
        assertThat(json(SQLDataType.LOCALDATETIME, LocalDateTime.of(2003, 8, 15, 13, 14, 15, 123_456_789)))
            .isEqualTo("[\"2003-08-15T13:14:15.123456789\"]");
        assertThat(json(SQLDataType.TIMESTAMP, Timestamp.valueOf("2003-08-15 13:14:15.12")))
            .as("java.sql.Timestamp takes the same form").isEqualTo("[\"2003-08-15T13:14:15.120000\"]");
        for (int nanos : new int[] {0, 120_000_000, 123_456_789}) {
            var value = LocalDateTime.of(2003, 8, 15, 13, 14, 15, nanos);
            assertThat(readBack(SQLDataType.LOCALDATETIME, value)).isEqualTo(value);
        }
    }

    @Test
    void timestampWithTimeZone_alwaysWritesTheFraction_thenTheOffset() {
        var utc = OffsetDateTime.of(2003, 8, 15, 13, 14, 15, 0, ZoneOffset.UTC);
        var plusOne = OffsetDateTime.of(2003, 8, 15, 13, 14, 15, 123_456_789, ZoneOffset.ofHours(1));
        assertThat(json(SQLDataType.OFFSETDATETIME, utc)).isEqualTo("[\"2003-08-15T13:14:15.000000Z\"]");
        assertThat(json(SQLDataType.OFFSETDATETIME, plusOne)).isEqualTo("[\"2003-08-15T13:14:15.123456789+01:00\"]");
        assertThat(readBack(SQLDataType.OFFSETDATETIME, utc)).isEqualTo(utc);
        assertThat(readBack(SQLDataType.OFFSETDATETIME, plusOne)).isEqualTo(plusOne);
    }

    @Test
    void instant_isWrittenAtUtc_whateverTheJvmZone() {
        TimeZone previous = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Oslo"));
        try {
            var instant = Instant.parse("2003-08-15T13:14:15Z");
            assertThat(json(SQLDataType.INSTANT, instant)).isEqualTo("[\"2003-08-15T13:14:15.000000Z\"]");
            assertThat(readBack(SQLDataType.INSTANT, instant)).isEqualTo(instant);
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    // ===== Strings =====

    @Test
    void string_usesOraclesEscapes() {
        assertThat(json(SQLDataType.VARCHAR, "a\"b\\c")).isEqualTo("[\"a\\\"b\\\\c\"]");
        assertThat(json(SQLDataType.VARCHAR, "\b\f\n\r\t")).isEqualTo("[\"\\b\\f\\n\\r\\t\"]");
        assertThat(json(SQLDataType.VARCHAR, "\u0001\u001f\u007f")).as("uppercase hex, U+007F escaped")
            .isEqualTo("[\"\\u0001\\u001F\\u007F\"]");
        assertThat(json(SQLDataType.VARCHAR, "/æ€😀")).as("raw").isEqualTo("[\"/æ€😀\"]");
        for (String value : List.of("a\"b\\c", "\b\f\n\r\t", "\u0001\u001f\u007f", "/æ€😀", "06")) {
            assertThat(readBack(SQLDataType.VARCHAR, value)).isEqualTo(value);
        }
    }

    @Test
    void numericLookingString_staysQuoted() {
        assertThat(json(SQLDataType.VARCHAR(2), "06")).isEqualTo("[\"06\"]");
    }

    // ===== Null and converters =====

    @Test
    void null_isNull() {
        List<Field<?>> columns = List.of(DSL.field("d", SQLDataType.LOCALDATE), DSL.field("id", SQLDataType.INTEGER));
        assertThat(json(columns, null, 2)).isEqualTo("[null,2]");
        assertThat(readBack(columns, null, 2)).containsExactly(null, 2);
    }

    @Test
    void converterColumn_writesTheDatabaseValue() {
        // A timestamptz read at UTC into a LocalDateTime: the cursor carries the OffsetDateTime the
        // database holds, in the timestamp-with-time-zone form, not the LocalDateTime's.
        var type = SQLDataType.TIMESTAMPWITHTIMEZONE.asConvertedDataType(new UtcLocalDateTimeConverter());
        var value = LocalDateTime.of(2007, 8, 22, 0, 0);
        assertThat(json(type, value)).isEqualTo("[\"2007-08-22T00:00:00.000000Z\"]");
        assertThat(readBack(type, value)).isEqualTo(value);
    }

    // ===== A column with no SQL type =====
    // A database type jOOQ has no built-in type for, read through a converter: the element is the
    // database value's text, and reading it back hands that text to the converter.

    private static final Span YEAR_2024 = new Span("2024-01-01T00:00Z", "2025-01-01T00:00Z");

    @Test
    void columnWithNoSqlType_writesTheDatabaseValuesText() {
        assertThat(Span.TYPE.getSQLDataType()).as("typed the way codegen types an unknown column").isNull();
        assertThat(json(Span.TYPE, YEAR_2024)).isEqualTo("[\"[2024-01-01T00:00Z,2025-01-01T00:00Z)\"]");
    }

    @Test
    void columnWithNoSqlType_readsBackToTheValueWritten() {
        assertThat(readBack(Span.TYPE, YEAR_2024)).isEqualTo(YEAR_2024);
        List<Field<?>> columns = List.of(DSL.field("id", SQLDataType.INTEGER), DSL.field("period", Span.TYPE));
        assertThat(json(columns, 7, YEAR_2024)).isEqualTo("[7,\"[2024-01-01T00:00Z,2025-01-01T00:00Z)\"]");
        assertThat(readBack(columns, 7, YEAR_2024)).containsExactly(7, YEAR_2024);
    }

    @Test
    void columnWithNoSqlType_anElementItsConverterRefuses_isTheClientError() {
        // Refused by the JSON reading and then by the release-candidate reading it falls back to,
        // which hands the whole text to the same converter.
        Field<?> period = DSL.field("period", Span.TYPE);
        assertRejected(jsonCursor("[\"garbage\"]"), period);
        assertRejected(jsonCursor("[true]"), period);
    }

    private static String jsonCursor(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    // ===== A release-candidate cursor =====
    // The release candidates wrote each order column's value as its toString(), U+0001 for SQL
    // NULL, joined by U+0000, in standard Base64. The decoder reads that format beside the JSON one,
    // telling them apart by the Base64 alphabet, then by the decoded text.

    /** The cursor a Graphitron 10 release candidate wrote for a row with these order-column values. */
    static String releaseCandidateCursor(Object... values) {
        String text = Stream.of(values).map(v -> v == null ? "\u0001" : v.toString())
            .collect(Collectors.joining("\u0000"));
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static List<Object> bound(String cursor, Field<?>... columns) {
        var bound = new ArrayList<Object>();
        for (Field<?> seek : ConnectionHelper.decodeCursor(cursor, List.of(columns))) {
            bound.add(((Param<?>) seek).getValue());
        }
        return bound;
    }

    private static void assertRejected(String cursor, Field<?>... columns) {
        assertThatThrownBy(() -> ConnectionHelper.decodeCursor(cursor, List.of(columns)))
            .as(cursor)
            .isInstanceOf(GraphitronClientException.class)
            .hasMessage("cursor is not valid (was: \"" + cursor + "\")");
    }

    @Test
    void releaseCandidateCursor_underASilentAlphabet_isReadByItsText() {
        assertThat(releaseCandidateCursor(100)).isEqualTo("MTAw");
        assertThat(bound("MTAw", FILM.FILM_ID)).containsExactly(100);
        assertThat(releaseCandidateCursor(null, 2)).isEqualTo("AQAy");
        List<Object> nullAndTwo = bound("AQAy", VALIDITY_PERIOD.VALID_TO, VALIDITY_PERIOD.PERIOD_ID);
        assertThat(nullAndTwo).containsExactly(null, 2);
        Field<?>[] seek = ConnectionHelper.decodeCursor("AQAy", List.of(VALIDITY_PERIOD.VALID_TO, VALIDITY_PERIOD.PERIOD_ID));
        assertThat(seek[0].getDataType().getType()).as("a typed NULL").isEqualTo(LocalDate.class);
    }

    @Test
    void textThatIsAlsoAJsonCursor_readsAsJson_andTextThatFailsJson_readsAsAReleaseCandidateCursor() {
        // The text ["xy"] is the release candidates' cursor for that title and the JSON cursor for
        // the title xy, byte for byte; every cursor encodeCursor writes has to read right, so JSON wins.
        assertThat(releaseCandidateCursor("[\"xy\"]")).isEqualTo("WyJ4eSJd")
            .isEqualTo(encode(List.of(FILM.TITLE), "xy"));
        assertThat(bound("WyJ4eSJd", FILM.TITLE)).containsExactly("xy");
        assertThat(releaseCandidateCursor("[abcd]")).isEqualTo("W2FiY2Rd");
        assertThat(bound("W2FiY2Rd", FILM.TITLE)).containsExactly("[abcd]");
    }

    @Test
    void releaseCandidateCursor_withAStandardAlphabetCharacter_isReadAsOne() {
        String cursor = releaseCandidateCursor("ME?", 7);
        assertThat(cursor).as("precondition: the standard alphabet speaks").isEqualTo("TUU/ADc=").contains("/");
        assertThat(bound(cursor, FILM.TITLE, FILM.FILM_ID)).containsExactly("ME?", 7);
    }

    @Test
    void releaseCandidateTokens_bindPerColumnType() {
        assertThat(bound(releaseCandidateCursor(2), FILM.FILM_ID)).containsExactly(2);
        assertThat(bound(releaseCandidateCursor(new BigDecimal("1.50")), FILM.RENTAL_RATE))
            .containsExactly(new BigDecimal("1.50"));
        assertThat(bound(releaseCandidateCursor(LocalDate.of(2026, 3, 1)), VALIDITY_PERIOD.VALID_TO))
            .containsExactly(LocalDate.of(2026, 3, 1));
        for (var timestamp : List.of(LocalDateTime.of(2024, 1, 1, 10, 11, 12),
                LocalDateTime.of(2024, 1, 1, 10, 11, 12, 123_456_000))) {
            assertThat(bound(releaseCandidateCursor(timestamp), FILM.LAST_UPDATE)).containsExactly(timestamp);
        }
        assertThat(bound(releaseCandidateCursor(MpaaRating.G), FILM.RATING)).containsExactly(MpaaRating.G);
    }

    @Test
    void malformedReleaseCandidateCursor_isTheClientError() {
        assertRejected("Mg-+", FILM.FILM_ID);
        assertRejected("Mg=x", FILM.FILM_ID);
        assertRejected("MQAy", FILM.FILM_ID);
        assertRejected(releaseCandidateCursor("abc"), FILM.FILM_ID);
        assertRejected(releaseCandidateCursor("abc"), FILM.RENTAL_RATE);
        assertRejected(releaseCandidateCursor("bogus"), VALIDITY_PERIOD.VALID_TO);
        assertRejected(releaseCandidateCursor("bogus"), FILM.LAST_UPDATE);
        // The constant's name where it differs from the literal: the release candidates wrote it
        // and could not read it back, and neither can this.
        assertThat(MpaaRating.NC_17.toString()).isEqualTo("NC_17");
        assertRejected(releaseCandidateCursor(MpaaRating.NC_17), FILM.RATING);
    }
}
