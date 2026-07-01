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
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

/**
 * Verifies that the FK-qualifier synthesis shim emits a WARN whose message contains
 * the field's {@code parentTypeName.fieldName} and the full canonical replacement directive,
 * in the format that future migration tooling is expected to parse.
 *
 * <p>Pins the log-message contract at the point where the shim is introduced; any change to
 * the format should update this test and the corresponding migration tooling together.
 *
 * <p>Two fixtures cover the two canonical-replacement shapes:
 * <ul>
 *   <li>{@code idreffixture} (studierett → studieprogram): two FKs to the same target →
 *       {@code fkAmbiguous=true} → WARN includes {@code @nodeId} <em>and</em> {@code @reference}.</li>
 *   <li>{@code nodeidfixture} (bar → baz): single FK to a node-typed target →
 *       {@code fkAmbiguous=false} → WARN includes {@code @nodeId} only.</li>
 * </ul>
 */
@UnitTier
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

    private static final RewriteContext NODEID_CTX = new RewriteContext(
        List.of(),
        Path.of(""),
        Path.of(""),
        "fake.code.generated",
        "no.sikt.graphitron.rewrite.nodeidfixture",
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

    // Both AMBIGUOUS_*_SDL trigger fkAmbiguous=true (studierett has two FKs to studieprogram).
    // FK1 hits the HAR-role qualifier; FK2 hits the role-prefixed qualifier. The split exists
    // so both qualifier shapes are exercised against the WARN format.
    private static final String AMBIGUOUS_FK1_SDL = """
        type Studieprogram @table(name: "studieprogram") { studieprogramId: String }
        type Studierett @table(name: "studierett") { studierettId: ID }
        input StudierettFilterInput @table(name: "studierett") {
          studieprogramIds: [ID!] @field(name: "STUDIEPROGRAM_ID")
        }
        type Query { studierett: Studierett }
        """;

    private static final String AMBIGUOUS_FK2_SDL = """
        type Studieprogram @table(name: "studieprogram") { studieprogramId: String }
        type Studierett @table(name: "studierett") { studierettId: ID }
        input StudierettFilterInput @table(name: "studierett") {
          registrarStudieprogramIds: [ID!] @field(name: "REGISTRAR_STUDIEPROGRAM_STUDIEPROGRAM_ID")
        }
        type Query { studierett: Studierett }
        """;

    // Single FK to a node-typed target → fkAmbiguous=false → WARN must NOT include @reference.
    // bar is the only outgoing-FK source in nodeidfixture; bar.id_1 → baz.id is the unique FK
    // and baz carries __NODE_TYPE_ID = "Baz".  qualifier "1BazId" (digit-leading because the FK
    // role is "1") → raw map key "1_baz_id"; @field(name:) supplies a digit-leading column name
    // that GraphQL's identifier rules would otherwise prohibit.
    private static final String UNIQUE_FK_SDL = """
        type Baz @table(name: "baz") { id: ID }
        type Bar @table(name: "bar") { name: String }
        input BarFilterInput @table(name: "bar") {
          bazIds: [ID!] @field(name: "1_BAZ_ID")
        }
        type Query { bar: Bar }
        """;

    @Test
    void shimWarnContainsParentTypeAndFieldName() {
        buildSchema(AMBIGUOUS_FK1_SDL, IDREF_CTX);

        assertThat(appender.list).hasSize(1);
        String msg = appender.list.get(0).getFormattedMessage();
        assertThat(msg).contains("StudierettFilterInput.studieprogramIds");
    }

    @Test
    void shimWarnContainsBothDirectivesWhenFkAmbiguous_fk1HarRole() {
        buildSchema(AMBIGUOUS_FK1_SDL, IDREF_CTX);

        assertThat(appender.list).hasSize(1);
        String msg = appender.list.get(0).getFormattedMessage();
        // studierett has two FKs to studieprogram (FK1 + FK2), so findUniqueFkToTable returns
        // empty → fkAmbiguous=true → canonical replacement includes both @nodeId and @reference.
        assertThat(msg).contains("@nodeId(typeName: \"Studieprogram\")");
        assertThat(msg).contains("@reference(path: [{key: \"studierett_studieprogram_id_fkey\"}])");
    }

    @Test
    void shimWarnContainsBothDirectivesWhenFkAmbiguous_fk2RolePrefix() {
        // FK2 (registrar_studieprogram_fkey) is picked by the shim. Since studierett has two FKs
        // to studieprogram, findUniqueFkToTable returns empty, so the WARN must include @reference.
        buildSchema(AMBIGUOUS_FK2_SDL, IDREF_CTX);

        assertThat(appender.list).hasSize(1);
        String msg = appender.list.get(0).getFormattedMessage();
        assertThat(msg).contains("@nodeId(typeName: \"Studieprogram\")");
        assertThat(msg).contains("@reference(path: [{key: \"studierett_registrar_studieprogram_fkey\"}])");
    }

    @Test
    void shimWarnOmitsReferenceWhenFkUnique() {
        // bar has exactly one FK to baz (bar.id_1 → baz.id), so findUniqueFkToTable resolves and
        // fkAmbiguous=false. The canonical replacement is then @nodeId alone — @reference would be
        // redundant noise on a single-FK source. This pins the false branch of the
        // fkAmbiguous selector at BuildContext.classifyInputField.
        buildSchema(UNIQUE_FK_SDL, NODEID_CTX);

        assertThat(appender.list).hasSize(1);
        String msg = appender.list.get(0).getFormattedMessage();
        assertThat(msg).contains("BarFilterInput.bazIds");
        assertThat(msg).contains("@nodeId(typeName: \"Baz\")");
        assertThat(msg).doesNotContain("@reference");
    }
}
