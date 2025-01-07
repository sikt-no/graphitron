package no.sikt.graphitron.validation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generate.GraphQLGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.setProperties;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Reading schema - Logs when reading schema files")
public class ReadingTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "reading";
    }

    @Test
    @DisplayName("Schema files are read and their names printed")
    void logReadSchemaFiles() {
        setProperties();
        var testDirectory = getSourceTestPath() + "logReadSchemaFiles";
        GeneratorConfig.setSchemaFiles(testDirectory + "/schema1.graphqls", testDirectory + "/subdir/schema2.graphqls", testDirectory + "/subdir/subsubdir/schema3.graphqls");

        GraphQLGenerator.getProcessedSchema().validate();
        assertThat(getInfo())
                .contains("Reading graphql schemas", "schema1.graphqls", "schema2.graphqls", "schema3.graphqls")
                .doesNotContain("notASchema");
    }

    private String getInfo() {
        return logWatcher
                .list
                .stream()
                .filter(it -> it.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .findFirst()
                .orElse("");
    }
}
