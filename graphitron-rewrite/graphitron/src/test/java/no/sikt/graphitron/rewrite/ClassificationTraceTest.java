package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the JSONL trace emitter. Drives one operation per arm
 * (classify / enrich / demote / synthesize) and asserts the documented
 * field set is present, with line-per-record framing and proper escaping.
 */
@UnitTier
class ClassificationTraceTest {

    @TempDir
    Path tempDir;

    private Path tracePath;

    @BeforeEach
    void enableTracing() {
        tracePath = tempDir.resolve("trace.jsonl");
        ClassificationTrace.resetForTesting(tracePath);
    }

    @AfterEach
    void disableTracing() {
        ClassificationTrace.resetForTesting(null);
    }

    @Test
    void emit_writesOneJsonObjectPerLine_withDocumentedFields() throws IOException {
        ClassificationTrace.emit(ClassificationTrace.Op.classify, "", "Film",
            "GraphitronType.TableType", "schema.graphqls", null, null);
        ClassificationTrace.emit(ClassificationTrace.Op.enrich, "", "Animal",
            "GraphitronType.InterfaceType", "schema.graphqls", null, null);
        ClassificationTrace.emit(ClassificationTrace.Op.demote, "", "Film",
            "GraphitronType.UnclassifiedType", "schema.graphqls",
            RejectionKind.INVALID_SCHEMA, "typeId 'Film' is declared on multiple types");
        ClassificationTrace.emit(ClassificationTrace.Op.synthesize, "", "FilmConnection",
            "GraphitronType.ConnectionType", null, null, null);

        var lines = Files.readAllLines(tracePath);
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0))
            .startsWith("{")
            .endsWith("}")
            .contains("\"op\":\"classify\"")
            .contains("\"parent\":\"\"")
            .contains("\"name\":\"Film\"")
            .contains("\"leaf\":\"GraphitronType.TableType\"")
            .contains("\"source\":\"schema.graphqls\"")
            .contains("\"test\":\"\"")
            .contains("\"tier\":\"\"");
        assertThat(lines.get(1)).contains("\"op\":\"enrich\"");
        assertThat(lines.get(2))
            .contains("\"op\":\"demote\"")
            .contains("\"rejection\":\"INVALID_SCHEMA\"")
            .contains("\"message\":\"typeId 'Film' is declared on multiple types\"");
        assertThat(lines.get(3))
            .contains("\"op\":\"synthesize\"")
            .contains("\"name\":\"FilmConnection\"");
    }

    @Test
    void emit_attributesRecordsToTestContext_setBySetContext() throws IOException {
        ClassificationTrace.setContext(new ClassificationTrace.Context(
            "no.sikt.graphitron.rewrite.SomeTest", "pipeline"));
        try {
            ClassificationTrace.emit(ClassificationTrace.Op.classify, "", "Film",
                "GraphitronType.TableType", null, null, null);
        } finally {
            ClassificationTrace.clearContext();
        }
        var lines = Files.readAllLines(tracePath);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0))
            .contains("\"test\":\"no.sikt.graphitron.rewrite.SomeTest\"")
            .contains("\"tier\":\"pipeline\"");
    }

    @Test
    void emit_escapesQuotesAndBackslashesInStringValues() throws IOException {
        ClassificationTrace.emit(ClassificationTrace.Op.classify, "", "Film",
            "GraphitronType.UnclassifiedType", null,
            RejectionKind.AUTHOR_ERROR, "Unknown column \"foo\\bar\"");

        var lines = Files.readAllLines(tracePath);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0))
            .contains("\"message\":\"Unknown column \\\"foo\\\\bar\\\"\"");
    }

    @Test
    void emit_isNoOp_whenTracingDisabled() throws IOException {
        ClassificationTrace.resetForTesting(null);
        // Re-bind to a fresh path so the no-op claim is visible: writing should not create it.
        Path notWritten = tempDir.resolve("not-created.jsonl");
        assertThat(Files.exists(notWritten)).isFalse();
        ClassificationTrace.emit(ClassificationTrace.Op.classify, "", "Film",
            "GraphitronType.TableType", null, null, null);
        assertThat(Files.exists(notWritten)).isFalse();
        // Re-enable for @AfterEach contract.
        ClassificationTrace.resetForTesting(tracePath);
    }

    @Test
    void isEnabled_reflectsCurrentBindingState() {
        assertThat(ClassificationTrace.isEnabled()).isTrue();
        ClassificationTrace.resetForTesting(null);
        assertThat(ClassificationTrace.isEnabled()).isFalse();
        ClassificationTrace.resetForTesting(tracePath);
        assertThat(ClassificationTrace.isEnabled()).isTrue();
    }
}
