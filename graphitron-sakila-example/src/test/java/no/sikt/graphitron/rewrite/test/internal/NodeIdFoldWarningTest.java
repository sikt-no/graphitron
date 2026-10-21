package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.generated.util.NodeIdEncoder;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.Converter;
import org.jooq.DataType;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The warning the generated {@link NodeIdEncoder} logs when it refuses a key slot because the
 * column's converter does not give the parsed value back: a node id that resolves to {@code null}
 * for that reason names its column in the server log, once at {@code WARN}, while a malformed id
 * over a converter that is fine logs nothing.
 *
 * <p>The encoder remembers the columns it has warned about in a static set, so each test decodes
 * over a column name of its own, and reads back only the records that name it, since other test
 * classes run concurrently. This module's slf4j binds to the JBoss LogManager that Quarkus brings,
 * so the records are read off that logger with a {@code java.util.logging} handler; the binding
 * formats the slf4j message before the record is published.
 */
@UnitTier
class NodeIdFoldWarningTest {

    private static final String TYPE_ID = "Fold";

    /** The sis shape: a {@code DATE} whose converter goes through {@code LocalDate}, dropping the time. */
    private static final DataType<LocalDateTime> DATE_ONLY =
        SQLDataType.LOCALDATE.asConvertedDataType(Converter.ofNullable(LocalDate.class, LocalDateTime.class,
            LocalDate::atStartOfDay, LocalDateTime::toLocalDate));
    /** A {@code String} user type on {@code bigint} whose converter strips whitespace on the way down. */
    private static final DataType<String> STRIPPING_CODE =
        SQLDataType.BIGINT.asConvertedDataType(Converter.ofNullable(Long.class, String.class,
            String::valueOf, s -> Long.valueOf(s.strip())));

    private static final int WARN = org.jboss.logmanager.Level.WARN.intValue();
    private static final int DEBUG = org.jboss.logmanager.Level.DEBUG.intValue();

    private final Logger encoderLogger = org.jboss.logmanager.Logger.getLogger(NodeIdEncoder.class.getName());
    private final List<LogRecord> recorded = Collections.synchronizedList(new ArrayList<>());
    private final Handler handler = new Handler() {
        @Override public void publish(LogRecord record) { recorded.add(record); }
        @Override public void flush() {}
        @Override public void close() {}
    };
    private Level inherited;

    @BeforeEach
    void capture() {
        inherited = encoderLogger.getLevel();
        encoderLogger.setLevel(org.jboss.logmanager.Level.DEBUG);
        encoderLogger.addHandler(handler);
    }

    @AfterEach
    void release() {
        encoderLogger.removeHandler(handler);
        encoderLogger.setLevel(inherited);
    }

    @Test
    void aLossyConverterWarnsOncePerColumn() {
        Field<LocalDateTime> column = DSL.field(DSL.name("fold", "lossy_date"), DATE_ONLY);
        String id = NodeIdEncoder.encode(TYPE_ID, LocalDateTime.of(2007, 8, 22, 2, 24));

        assertThat(decode(id, LocalDateTime.class, column)).isNull();
        var warns = events("lossy_date", WARN);
        assertThat(warns).hasSize(1);
        assertThat(warns.getFirst().getMessage())
            .contains("lossy_date")
            .contains("2007-08-22T02:24")
            .contains("2007-08-22T00:00");

        assertThat(decode(id, LocalDateTime.class, column)).isNull();
        assertThat(events("lossy_date", WARN)).as("no second WARN for the same column").hasSize(1);
        assertThat(events("lossy_date", DEBUG)).as("the repeat drops to DEBUG").hasSize(1);
    }

    @Test
    void aValueTheConverterGivesBackDecodesAndLogsNothing() {
        Field<LocalDateTime> column = DSL.field(DSL.name("fold", "midnight_date"), DATE_ONLY);
        String id = NodeIdEncoder.encode(TYPE_ID, LocalDateTime.of(2007, 8, 22, 0, 0));

        assertThat(decode(id, LocalDateTime.class, column)).containsExactly(LocalDateTime.of(2007, 8, 22, 0, 0));
        assertThat(events("midnight_date", null)).isEmpty();
    }

    @Test
    void aPaddedNumberOverAPlainColumnLogsNothing() {
        Field<Integer> column = DSL.field(DSL.name("fold", "padded_integer"), SQLDataType.INTEGER);
        String id = NodeIdEncoder.encode(TYPE_ID, "01");

        assertThat(decode(id, null, column)).isNull();
        assertThat(events("padded_integer", null)).isEmpty();
    }

    @Test
    void aForgedSlotIsLoggedWithItsControlCharactersEscaped() {
        Field<String> column = DSL.field(DSL.name("fold", "stripping_code"), STRIPPING_CODE);
        String id = NodeIdEncoder.encode(TYPE_ID, "7\n");

        assertThat(decode(id, String.class, column)).isNull();
        var warns = events("stripping_code", WARN);
        assertThat(warns).hasSize(1);
        assertThat(warns.getFirst().getMessage())
            .contains("7\\n")
            .doesNotContain("\n");
    }

    private static Object[] decode(String id, Class<?> parseClass, Field<?> column) {
        return NodeIdEncoder.decodeColumns(TYPE_ID, id, new Class<?>[] {parseClass}, column);
    }

    /** The records naming {@code columnName}, at {@code level} or at any level when it is {@code null}. */
    private List<LogRecord> events(String columnName, Integer level) {
        synchronized (recorded) {
            return recorded.stream()
                .filter(record -> level == null || record.getLevel().intValue() == level)
                .filter(record -> record.getMessage().contains(columnName))
                .toList();
        }
    }
}
