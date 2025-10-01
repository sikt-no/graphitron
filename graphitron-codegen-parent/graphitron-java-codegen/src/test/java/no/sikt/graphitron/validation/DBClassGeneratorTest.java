package no.sikt.graphitron.validation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.CodeGenerationThresholds;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generate.GraphQLGenerator;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.db.DBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.Ignore;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


class DBClassGeneratorTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "validation/";
    }

    @Override
    protected boolean validateSchema() {
        return true;
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new DBClassGenerator(schema));
    }

    protected ListAppender<ILoggingEvent> logWatcher;

    protected String getWarning() {
        return logWatcher
                .list
                .stream()
                .filter(it -> it.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .findFirst()
                .orElse("");
    }

    protected void assertWarningsContain(String... values) {
        assertThat(getWarning()).contains(values);
    }

    protected void assertErrorsContain(Runnable f, String ... values) {
        assertThatThrownBy(f::run)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll(values);
    }

    @BeforeEach
    public void setup() {
        super.setup();
        var logWatch = new ListAppender<ILoggingEvent>();
        logWatch.start();
        ((Logger) LoggerFactory.getLogger(DBClassGenerator.class)).addAppender(logWatch);
        this.logWatcher = logWatch;
    }

    @AfterEach
    public void teardown() {
        super.teardown();
        ((Logger) LoggerFactory.getLogger(GraphQLGenerator.class)).detachAndStopAllAppenders();
    }

    @Test
    @DisplayName("Throws error when exceeding crash point")
    void throwsErrorWhenExceedingCrashPoint() {
        GeneratorConfig.setCodeGenerationThresholds(new CodeGenerationThresholds(null, 1, null, null));
        assertErrorsContain(
                () -> generateFiles("thresholdEvaluator"),
                "Code generation crash point has exceeded for the following methods:\n" +
                        "Code size in QueryQueryDBQueries.queryForQuery has exceeded its CRASH_POINT (current/limit) 5/1"
        );

    }

    @Test
    @DisplayName("Logging a warning when exceeding upper bound")
    void warnsWhenExceedingUpperBound() {
        GeneratorConfig.setCodeGenerationThresholds(new CodeGenerationThresholds(1, null, null, null));
        generateFiles("thresholdEvaluator");
        assertWarningsContain(
                "Code generation upper bound has exceeded for the following methods, this may cause performance issues:\n" +
                        "Code size in QueryQueryDBQueries.queryForQuery has exceeded its UPPER_BOUND (current/limit) 5/1"
        );
    }
}