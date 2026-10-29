package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.generated.util.NodeIdEncoder;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The line the generated {@link NodeIdEncoder} logs when it converts an id from its type's legacy key
 * shape: the type id, once per type id at {@code INFO} and at {@code DEBUG} after that, so a schema
 * owner can tell when the old ids are gone, and never a value the client sent.
 *
 * <p>The encoder remembers the type ids it has logged in a static set, so this converts the one type
 * id no other test does, {@code PaymentLegacyProbe}, and reads back only the records naming it. The
 * records are read off the JBoss logger on {@code NodeIdFoldWarningTest}'s terms.
 */
@UnitTier
class NodeIdLegacyConversionLogTest {

    private static final String TYPE_ID = "PaymentLegacyProbe";
    private static final int INFO = org.jboss.logmanager.Level.INFO.intValue();
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
    void aConversionLogsItsTypeIdOnceAtInfoThenAtDebug_andNoClientValue() {
        String legacy = NodeIdEncoder.encode(TYPE_ID, 31337, 271828);

        Object[] decoded = NodeIdEncoder.decodeColumns(TYPE_ID, legacy, null, Tables.PAYMENT.PAYMENT_ID);
        assertThat(decoded).containsExactly(31337);
        assertThat(NodeIdEncoder.decodeColumns(TYPE_ID, legacy, null, Tables.PAYMENT.PAYMENT_ID))
            .containsExactly(31337);

        assertThat(events(INFO)).hasSize(1);
        assertThat(events(DEBUG)).hasSize(1);
        assertThat(events(null)).allSatisfy(record -> assertThat(record.getMessage())
            .doesNotContain("31337").doesNotContain("271828"));
    }

    /** The records naming the type id, at {@code level} or at any level when it is {@code null}. */
    private List<LogRecord> events(Integer level) {
        synchronized (recorded) {
            return recorded.stream()
                .filter(record -> level == null || record.getLevel().intValue() == level)
                .filter(record -> record.getMessage().contains(TYPE_ID))
                .toList();
        }
    }
}
