package no.sikt.graphitron.rewrite.capture;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.SchemaError;
import no.sikt.graphitron.rewrite.schema.SdlVerdicts;

import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_ERROR;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SYNTAX_ERROR;

/**
 * Transcribes what the SDL toolchain concluded about the document, beside
 * {@link SdlFactCapture}'s transcription of what the document says. Three stages judge a schema on
 * the way in, and each writes here on refusal: the parser one source at a time, then the registry
 * over the combined definitions, then assembly over the registry.
 *
 * <p>Written on every pass, on either outcome, which is what the two relations' emptiness means.
 * A stage that refused nothing leaves no rows, so empty partitions read as "the document was read
 * clean" rather than "nothing has looked yet"; that is only true because the stages run
 * unconditionally, assembly included, whether or not the run then has any use for the assembled
 * schema. A caller that skipped a stage would be writing an emptiness it had not earned.
 *
 * <p>Absence discipline follows the schema side of this stratum rather than the compile arm's:
 * graphql-java signals an unlocated verdict with a {@code (-1, -1)} sentinel, which
 * {@link SchemaError#of} has already normalised to an absent location by the time a row is written
 * here, so nothing downstream compares against a sentinel value.
 */
final class SdlVerdictCapture {

    private SdlVerdictCapture() {}

    /** Writes both relations from one pass's stage verdicts. */
    static void capture(FactSink sink, SdlVerdicts verdicts) {
        for (var failure : verdicts.syntaxFailures()) {
            writeSyntaxError(sink, failure);
        }
        int ordinal = 0;
        for (var error : verdicts.schemaErrors()) {
            writeSchemaError(sink, ordinal++, error);
        }
    }

    /**
     * One refused source. The claim is the source name, mirroring the relation's key: the parser
     * stops at a source's first syntax error, so a second row for one source would be a capture
     * bug rather than something an author can provoke, and the gate says so instead of letting a
     * primary-key violation say it.
     */
    private static void writeSyntaxError(FactSink sink, RewriteSchemaLoader.SyntaxFailure failure) {
        if (!sink.claim(GRAPHQL_SYNTAX_ERROR, failure.sourceName())) {
            return;
        }
        var row = sink.dsl().newRecord(GRAPHQL_SYNTAX_ERROR);
        row.setSourceName(failure.sourceName());
        row.setMessage(failure.brief());
        SourceLocation location = failure.location();
        if (location != null) {
            row.setSourceLine(location.getLine());
            row.setSourceColumn(location.getColumn());
        }
        sink.add(row);
    }

    /**
     * One document-wide verdict. The ordinal runs across both stages in the order they ran, so the
     * key is the emit order of the whole read rather than of one stage, and a registry refusal
     * always precedes an assembly verdict from the same pass.
     */
    private static void writeSchemaError(FactSink sink, int ordinal, SchemaError error) {
        var row = sink.dsl().newRecord(GRAPHQL_SCHEMA_ERROR);
        row.setOrdinal(ordinal);
        row.setStage(error.stage().name());
        row.setErrorClass(error.errorClass());
        row.setMessage(error.message());
        SourceLocation location = error.location();
        if (location != null) {
            row.setSourceName(location.getSourceName());
            row.setSourceLine(location.getLine());
            row.setSourceColumn(location.getColumn());
        }
        sink.add(row);
    }
}
