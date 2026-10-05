package no.sikt.graphitron.model.capture.document;

import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.LoadingRewrites;
import org.jooq.DSLContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Combines the documents into one schema and records what combining them raised.
 *
 * <p>The three stages below the parser all live here, because they are the questions that cannot
 * be asked of a document on its own. The reduce asks whether the corpus agrees with itself. The
 * composition runs the loading rewrites the generator runs, {@link LoadingRewrites}, which is what
 * makes a federation {@code @link}'s imports and the configured tags part of the schema at all. The
 * assembly asks whether what came of the two is a schema: that every named type resolves, that an
 * object satisfies the interfaces it claims, that a directive sits somewhere its definition
 * permits, that there is a query root. None of the three verdicts is a fact about a file, so none
 * belongs to a gatherer that reads files.
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
 * <h2>The composition is the generator's, and it reads configuration</h2>
 *
 * <p>The assembly judges the corpus as the generator composes it, so a directive the generator
 * accepts cannot be reported undeclared here. The composition is configured, by the inputs' tags
 * and notes and by which federation spec versions the library knows, which is why this gatherer's
 * corpora include the configuration as well as the documents.
 *
 * <p>A refusal of the composition never throws. It is recorded, and the assembly then judges the
 * merged registry instead, so what is recorded after it is a true statement about the corpus as
 * written rather than about a composition stopped partway. A corpus mid-edit with two
 * {@code @link}s keeps every fact beside them.
 *
 * <h2>What it writes and what it hands back</h2>
 *
 * <p>The refusals of all three stages, as {@code graphql_schema_problem} rows numbered within their
 * own stage. The parse stage is not among them: the gatherer that ran the parser wrote those, each
 * stage numbering and sweeping its own rows.
 *
 * <p>It returns the merged registry beside the assembly. The decode walks the merged registry, the
 * corpus as written, which the composition started from and never touches: the rewrites run on a
 * reduce of their own. The assembly is a narrower claim than an executable schema:
 * {@link SchemaAssembly} carries the schema on the arm that has one, and a caller that reaches for
 * it has to have said what it does when there is none. A capture whose corpus did not assemble is
 * an ordinary outcome here, the verdict being the thing this gatherer was run for.
 */
public final class GraphQLAssemblyCapture {

    private GraphQLAssemblyCapture() {}

    /**
     * What this gatherer made of one reading.
     *
     * @param merged   the corpus as written, reduced into one registry; what the decode walks
     * @param assembly the assembly over the composition, or over {@code merged} where the
     *                 composition refused; {@link SchemaAssembly#registry()} says which was judged
     */
    public record AssemblyReading(TypeDefinitionRegistry merged, SchemaAssembly assembly) {}

    /**
     * The corpus reduced into one registry.
     *
     * <p>Every document that states something, whether or not this reading is what made it say so:
     * the corpus is composed from the whole of it, and a source left untranscribed because its
     * bytes had not moved is still one of the documents the schema is made of.
     */
    private static SchemaLoader.PerSourceParse merge(List<GraphQLSourceCapture.SourceDocument> documents) {
        return SchemaLoader.merge(documents.stream()
            .filter(GraphQLSourceCapture.SourceDocument.Stated.class::isInstance)
            .map(GraphQLSourceCapture.SourceDocument.Stated.class::cast)
            .map(GraphQLSourceCapture.SourceDocument.Stated::registry).toList());
    }

    /**
     * Reduces the reading's documents into one registry, composes and assembles it, and makes
     * {@code graph}'s problem rows be what the three stages raised.
     *
     * <p>The documents are reduced twice. The rewrites run in place and a refusal can stop them
     * partway, and a read-only handle would not protect the decode, since it shares the extension
     * lists the tag and note rewrites edit; so the composition is handed a reduce of its own and the
     * merged registry stays the corpus as written.
     *
     * <p>The instant is the caller's, on every gatherer's terms: the rows this writes carry it and
     * the sweep tells this reading's rows from the last one's by it.
     */
    public static AssemblyReading capture(DSLContext dsl, GraphIdentity graph,
                                          GraphQLSourceCapture.CorpusReading reading,
                                          LocalDateTime readAt) {
        var merged = merge(reading.documents());
        var composition = merge(reading.documents()).registry();
        var refusal = switch (LoadingRewrites.apply(composition, reading.inputs())) {
            case LoadingRewrites.Outcome.Applied ignored -> null;
            case LoadingRewrites.Outcome.Refused refused -> refused.refusal();
        };
        var assembly = SchemaAssembly.of(refusal == null ? composition : merged.registry());
        GraphQLSchemaProblems.writeAssembled(dsl, graph.name(), merged.registryErrors(),
            Optional.ofNullable(refusal), assembly.errors(), readAt);
        return new AssemblyReading(merged.registry(), assembly);
    }
}
