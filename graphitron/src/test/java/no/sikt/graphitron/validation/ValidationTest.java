package no.sikt.graphitron.validation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.mojo.GraphQLGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

abstract public class ValidationTest extends GeneratorTest {
    protected ListAppender<ILoggingEvent> logWatcher;

    @Override
    protected String getSubpath() {
        return "validation/";
    }

    protected String getWarning() {
        return logWatcher
                .list
                .stream()
                .filter(it -> it.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .findFirst()
                .orElse("");
    }

    protected void assertNoWarnings() {
        assertThat(getWarning()).isEmpty();
    }

    protected void assertWarningsContain(String... values) {
        assertThat(getWarning()).contains(values);
    }

    protected void assertErrorsContain(Runnable f, String value) {
        assertThatThrownBy(f::run)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(value);
    }

    protected void assertErrorsContain(String file, String value) {
        assertThatThrownBy(() -> getProcessedSchema(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(value);
    }

    protected void assertErrorsContain(String file, String ... values) {
        assertThatThrownBy(() -> getProcessedSchema(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll(values);
    }

    @BeforeEach
    public void setup() {
        var logWatch = new ListAppender<ILoggingEvent>();
        logWatch.start();
        ((Logger) LoggerFactory.getLogger(GraphQLGenerator.class)).addAppender(logWatch);
        this.logWatcher = logWatch;
    }

    @AfterEach
    public void teardown() {
        ((Logger) LoggerFactory.getLogger(GraphQLGenerator.class)).detachAndStopAllAppenders();
    }
}
