package no.sikt.graphitron.validation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generate.GraphQLGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

abstract public class ValidationTest extends GeneratorTest {
    protected ListAppender<ILoggingEvent> logWatcher;

    @Override
    protected String getSubpath() {
        return "validation/";
    }

    @Override
    protected boolean validateSchema() {
        return true;
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

    protected void assertErrorsContain(Runnable f, String ... values) {
        assertThatThrownBy(f::run)
                .isInstanceOf(InvalidSchemaException.class)
                .hasMessageContainingAll(values);
    }

    protected void assertErrorsContain(String file, String ... values) {
        assertErrorsContain(file, Set.of(), values);
    }

    protected void assertErrorsContainOnce(String file, String ... values) {
        assertErrorsContainExactlyNTimes(file, 1, Set.of(), values);
    }

    protected void assertErrorsContain(String file, Set<SchemaComponent> components, String ... values) {
        assertThatThrownBy(() -> getProcessedSchema(file, components))
                .isInstanceOf(InvalidSchemaException.class)
                .hasMessageContainingAll(values);
    }

    protected void assertErrorsDoNotContain(String file, String ... values) {
        assertThatThrownBy(() -> getProcessedSchema(file))
                .isInstanceOf(InvalidSchemaException.class)
                .satisfies(it -> assertThat(it.getMessage()).doesNotContain(values));
    }

    protected void assertErrorsContainExactlyNTimes(String file, int n, Set<SchemaComponent> components, String ... values) {
        assertThatThrownBy(() -> getProcessedSchema(file, components))
                .isInstanceOf(InvalidSchemaException.class)
                .matches(it ->
                        Arrays.stream(values).allMatch(value -> Pattern.compile(Pattern.quote(value)).matcher(it.getMessage()).results().count() == n));
    }

    @BeforeEach
    public void setup() {
        super.setup();
        var logWatch = new ListAppender<ILoggingEvent>();
        logWatch.start();
        ((Logger) LoggerFactory.getLogger(ValidationHandler.class)).addAppender(logWatch);
        this.logWatcher = logWatch;
    }

    @AfterEach
    public void teardown() {
        super.teardown();
        ((Logger) LoggerFactory.getLogger(GraphQLGenerator.class)).detachAndStopAllAppenders();
    }
}
