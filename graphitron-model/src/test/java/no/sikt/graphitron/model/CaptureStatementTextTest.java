package no.sikt.graphitron.model;

import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.model.test.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.ZoneOffset;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A capture's statements read the same from one capture to the next, so H2 plans each once and
 * reuses the plan: the instant a reading marks its rows with is a bind parameter, never a literal
 * rendered into the text, where every capture would make it a statement H2 has not seen.
 *
 * <p>Read off every statement the fact documents' captures ran
 * ({@link FactGraphs.Reader#statementTexts}), stepped scenarios' later readings among them. Every
 * scenario's clock starts at {@link Scenario#T0} and moves by minutes, so an instant rendered into a
 * statement would carry that day; a timestamp literal of any day is refused too, the wall clock's
 * included.
 */
class CaptureStatementTextTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final Pattern TIMESTAMP_LITERAL = Pattern.compile("(?i)\\btimestamp\\s*'");

    /**
     * The one timestamp literal a statement may carry: the epoch a claim's check counts instants
     * from, the same in every statement, so no reading's instant.
     */
    private static final String EPOCH = "TIMESTAMP '1970-01-01 00:00:00'";

    @Test
    @DisplayName("no statement a capture runs carries the instant it reads at")
    void noStatementCarriesItsInstant() {
        var texts = FACTS.statementTexts();

        assertThat(texts).as("the captures ran statements").hasSizeGreaterThan(100);
        assertThat(texts)
            .as("statements carrying a timestamp literal or the scenario clock's day, the reading's"
                + " instant inlined")
            .noneMatch(sql -> TIMESTAMP_LITERAL.matcher(sql.replace(EPOCH, "")).find()
                || sql.contains(T0_DAY));
    }

    /** The day every scenario's readings fall on, as a statement would spell it. */
    private static final String T0_DAY = Scenario.T0.atOffset(ZoneOffset.UTC).toLocalDate().toString();
}
