package no.sikt.graphitron.model.capture.config;

import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import org.jooq.DSLContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SCHEMA_EXTENSION;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SCHEMA_INPUT;
import no.sikt.graphitron.model.sink.FactSink;

/**
 * The graph's SDL recipe as store rows, encode and decode in one place. Both directions live
 * together because a decode written apart from its encoder is exactly what drifts from it: the
 * empty-tag collapse, the ordinal spine and the {@code kind} dispatch have to agree, and the
 * round-trip anchor over these two methods is what holds them to each other.
 *
 * <p>{@link #decode} is production code with a production reader (the freshness replay decodes a
 * sibling graph's rows and re-expands them through {@link SchemaRecipe#expand(Path)}), not a test
 * helper: a hand-rolled row-to-recipe decode owned by nobody is what this class retires.
 */
public final class StoredRecipe {

    /** The three {@code store_graph_schema_input.kind} values, closed by the relation's CHECK. */
    static final String KIND_PATTERN = "pattern";
    static final String KIND_FILE = "file";
    static final String KIND_NAMED = "named";

    private StoredRecipe() {}

    /**
     * Transcribes the recipe, written fresh by every run from its resolved configuration; the warm
     * path's graph-scoped clear has already emptied the previous run's rows. Buffered through the
     * sink like any graph-keyed rows, so they carry the graph stamp.
     */
    static void write(FactSink sink, SchemaRecipe recipe) {
        int ordinal = 0;
        for (SchemaRecipe.Binding binding : recipe.bindings()) {
            var row = sink.dsl().newRecord(STORE_GRAPH_SCHEMA_INPUT);
            row.setOrdinal(ordinal++);
            switch (binding.entry()) {
                case SchemaRecipe.Entry.Pattern pattern -> {
                    row.setKind(KIND_PATTERN);
                    row.setEntryValue(pattern.glob());
                }
                case SchemaRecipe.Entry.Literal literal -> {
                    switch (literal.source()) {
                        case SchemaSource.File ignored -> row.setKind(KIND_FILE);
                        case SchemaSource.Named ignored -> row.setKind(KIND_NAMED);
                    }
                    row.setEntryValue(literal.source().sourceName());
                }
            }
            row.setTag(binding.tag().orElse(null));
            row.setDescriptionNote(binding.descriptionNote().orElse(null));
            sink.add(row);
        }
        int position = 0;
        for (String extension : recipe.extensions()) {
            var row = sink.dsl().newRecord(STORE_GRAPH_SCHEMA_EXTENSION);
            row.setOrdinal(position++);
            row.setExtension(extension);
            sink.add(row);
        }
    }

    /**
     * Reads a graph's recipe back out of the store, or empty when the store holds no anchor row for
     * the graph. A graph the store anchors but has never captured (a diagnostics preamble mints the
     * anchor with {@code onDuplicateKeyIgnore}) decodes to a recipe with no entries; whether that
     * is worth replaying is the {@code build_file_stamp} contract's question, not this decode's.
     */
    public static Optional<SchemaRecipe> decode(DSLContext dsl, String graphName) {
        var anchor = dsl.select(STORE_GRAPH.BUILD_FILE_PATH)
            .from(STORE_GRAPH)
            .where(STORE_GRAPH.GRAPH_NAME.eq(graphName))
            .fetchOptional();
        if (anchor.isEmpty()) {
            return Optional.empty();
        }
        String buildFilePath = anchor.get().value1();
        var bindings = new ArrayList<SchemaRecipe.Binding>();
        dsl.select(STORE_GRAPH_SCHEMA_INPUT.KIND, STORE_GRAPH_SCHEMA_INPUT.ENTRY_VALUE,
                STORE_GRAPH_SCHEMA_INPUT.TAG, STORE_GRAPH_SCHEMA_INPUT.DESCRIPTION_NOTE)
            .from(STORE_GRAPH_SCHEMA_INPUT)
            .where(STORE_GRAPH_SCHEMA_INPUT.GRAPH_NAME.eq(graphName))
            .orderBy(STORE_GRAPH_SCHEMA_INPUT.ORDINAL)
            .forEach(row -> bindings.add(new SchemaRecipe.Binding(
                entryOf(row.value1(), row.value2()),
                Optional.ofNullable(row.value3()),
                Optional.ofNullable(row.value4()))));
        List<String> extensions = dsl.select(STORE_GRAPH_SCHEMA_EXTENSION.EXTENSION)
            .from(STORE_GRAPH_SCHEMA_EXTENSION)
            .where(STORE_GRAPH_SCHEMA_EXTENSION.GRAPH_NAME.eq(graphName))
            .orderBy(STORE_GRAPH_SCHEMA_EXTENSION.ORDINAL)
            .fetch(0, String.class);
        return Optional.of(new SchemaRecipe(
            buildFilePath == null ? null : Path.of(buildFilePath), bindings, extensions));
    }

    /**
     * The row's {@code kind} back into an entry. The arm is recovered from what the writer recorded
     * rather than by asking a filesystem question about a stored string, which is the whole reason
     * the column exists.
     */
    private static SchemaRecipe.Entry entryOf(String kind, String value) {
        return switch (kind) {
            case KIND_PATTERN -> new SchemaRecipe.Entry.Pattern(value);
            case KIND_FILE -> new SchemaRecipe.Entry.Literal(SchemaSource.file(Path.of(value)));
            case KIND_NAMED -> new SchemaRecipe.Entry.Literal(SchemaSource.named(value));
            default -> throw new IllegalStateException(
                "store_graph_schema_input.kind holds '" + kind + "', which the relation's CHECK "
                    + "should have refused; the store and this decode disagree about the taxonomy");
        };
    }
}
