package no.sikt.graphitron.model.capture.document;

import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.schema.SchemaLoader;
import org.jooq.DSLContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Combines the documents into one schema and records what combining them raised.
 *
 * <p>The two stages below the parser both live here, because they are the two questions that cannot
 * be asked of a document on its own. The reduce asks whether the corpus agrees with itself, and the
 * assembly asks whether what it agreed on is a schema: that every named type resolves, that an
 * object satisfies the interfaces it claims, that a directive sits somewhere its definition permits,
 * that there is a query root. Neither verdict is a fact about a file, so neither belongs to a
 * gatherer that reads files.
 *
 * <h2>The order is the source gatherer's, the reduce is this gatherer's own</h2>
 *
 * <p>Documents are reduced in the order {@link GraphQLSourceCapture} handed them over, oldest file
 * first, and that order decides collisions: two documents declaring one name leave the first
 * standing and the second refused. Honouring the order is this gatherer's obligation. How it
 * merges is nobody else's business, and it does not merge the way graphql-java's own registry merge
 * does: that one refuses a whole document whose declaration clashes, where {@link SchemaLoader#merge}
 * refuses the clashing declaration and keeps everything else the document declares.
 *
 * <p>A refusal is recorded and the reduce continues. A corpus an author is mid-edit on will raise
 * several, and stopping at the first would report one of them and hide the rest.
 *
 * <h2>What it writes and what it hands back</h2>
 *
 * <p>The refusals of both stages, as {@code graphql_schema_problem} rows numbered within their own
 * stage. The parse stage is not among them: the gatherer that ran the parser wrote those, each stage
 * numbering and sweeping its own rows.
 *
 * <p>It returns the assembly rather than an executable schema, which is a narrower claim than it
 * sounds: {@link SchemaAssembly} carries the schema on the arm that has one, and a caller that
 * reaches for it has to have said what it does when there is none. A capture whose corpus did not
 * assemble is an ordinary outcome here, the verdict being the thing this gatherer was run for.
 */
public final class GraphQLAssemblyCapture {

    private GraphQLAssemblyCapture() {}

    /**
     * The corpus composed into one registry.
     *
     * <p>Every document that states something, whether or not this reading is what made it say so:
     * the corpus is composed from the whole of it, and a source left untranscribed because its
     * bytes had not moved is still one of the documents the schema is made of.
     *
     * <p>Exposed because the decode still rides a walk of the merged registry rather than reading
     * the entry stratum, and the pass that drives it should compose the corpus the same way this
     * does rather than a second way that could differ. It stops being needed when the decode stops
     * needing a registry.
     */
    public static SchemaLoader.PerSourceParse merge(List<GraphQLSourceCapture.SourceDocument> documents) {
        return SchemaLoader.merge(documents.stream()
            .filter(GraphQLSourceCapture.SourceDocument.Stated.class::isInstance)
            .map(GraphQLSourceCapture.SourceDocument.Stated.class::cast)
            .map(GraphQLSourceCapture.SourceDocument.Stated::registry).toList());
    }

    /**
     * Reduces {@code documents} into one registry, assembles it, and makes {@code graph}'s problem
     * rows be what the two stages raised.
     *
     * <p>The instant is the caller's, on every gatherer's terms: the rows this writes carry it and
     * the sweep tells this reading's rows from the last one's by it.
     */
    public static SchemaAssembly capture(DSLContext dsl, GraphIdentity graph,
                                         List<GraphQLSourceCapture.SourceDocument> documents,
                                         LocalDateTime readAt) {
        var merged = merge(documents);
        var assembly = SchemaAssembly.of(merged.registry());
        // What the merge refused and what the assembly refused are the same question asked of the
        // same corpus, so they are written as one list in the order the stages ran.
        var raised = new ArrayList<>(merged.registryErrors());
        raised.addAll(assembly.errors());
        GraphQLSchemaProblems.writeAssembled(dsl, graph.name(), List.copyOf(raised), readAt);
        return assembly;
    }
}
