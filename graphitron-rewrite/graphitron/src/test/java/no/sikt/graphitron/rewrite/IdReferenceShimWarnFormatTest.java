package no.sikt.graphitron.rewrite;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.TestSchemaHelper.buildSchema;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the IdReferenceField synthesis shim emits a WARN whose message contains
 * the field's {@code parentTypeName.fieldName} and the full canonical replacement directive,
 * in the format that future migration tooling is expected to parse.
 *
 * <p>Pins the log-message contract at the point where the shim is introduced; any change to
 * the format should update this test and the corresponding migration tooling together.
 */
class IdReferenceShimWarnFormatTest {

    private static final String LOGGER_NAME = BuildContext.class.getName() + ".idRefShim";

    private static final RewriteContext IDREF_CTX = new RewriteContext(
        List.of(),
        Path.of(""),
        Path.of(""),
        "fake.code.generated",
        "no.sikt.graphitron.rewrite.idreffixture",
        Map.of()
    );

    private ListAppender<ILoggingEvent> appender;
    private Logger shimLogger;

    @BeforeEach
    void attachAppender() {
        shimLogger = (Logger) LoggerFactory.getLogger(LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        shimLogger.addAppender(appender);
        shimLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void detachAppender() {
        shimLogger.detachAppender(appender);
        appender.stop();
    }

    private static final String SHIM_SDL = """
        type Studieprogram @table(name: "studieprogram") { studieprogramId: String }
        type Studierett @table(name: "studierett") { studierettId: ID }
        input StudierettFilterInput @table(name: "studierett") {
          studieprogramIds: [ID!] @field(name: "STUDIEPROGRAM_ID")
        }
        type Query { studierett: Studierett }
        """;

    private static final String AMBIGUOUS_SDL = """
        type Studieprogram @table(name: "studieprogram") { studieprogramId: String }
        type Studierett @table(name: "studierett") { studierettId: ID }
        input StudierettFilterInput @table(name: "studierett") {
          registrarStudieprogramIds: [ID!] @field(name: "REGISTRAR_STUDIEPROGRAM_STUDIEPROGRAM_ID")
        }
        type Query { studierett: Studierett }
        """;

    @Test
    void shimWarnContainsParentTypeAndFieldName() {
        buildSchema(SHIM_SDL, IDREF_CTX);

        assertThat(appender.list).hasSize(1);
        String msg = appender.list.get(0).getFormattedMessage();
        assertThat(msg).contains("StudierettFilterInput.studieprogramIds");
    }

    @Test
    void shimWarnContainsCanonicalReplacement() {
        buildSchema(SHIM_SDL, IDREF_CTX);

        assertThat(appender.list).hasSize(1);
        String msg = appender.list.get(0).getFormattedMessage();
        // studierett has two FKs to studieprogram (FK1 + FK2), so findUniqueFkToTable returns
        // empty → fkAmbiguous=true → canonical replacement includes both @nodeId and @reference.
        assertThat(msg).contains("@nodeId(typeName: \"Studieprogram\")");
        assertThat(msg).contains("@reference(path: [{key: \"studierett_studieprogram_id_fkey\"}])");
    }

    @Test
    void shimWarnContainsReferenceWhenFkAmbiguous() {
        // FK2 (registrar_studieprogram_fkey) is picked by the shim. Since studierett has two FKs
        // to studieprogram, findUniqueFkToTable returns empty, so the WARN must include @reference.
        buildSchema(AMBIGUOUS_SDL, IDREF_CTX);

        assertThat(appender.list).hasSize(1);
        String msg = appender.list.get(0).getFormattedMessage();
        assertThat(msg).contains("@nodeId(typeName: \"Studieprogram\")");
        assertThat(msg).contains("@reference(path: [{key: \"studierett_registrar_studieprogram_fkey\"}])");
    }
}
