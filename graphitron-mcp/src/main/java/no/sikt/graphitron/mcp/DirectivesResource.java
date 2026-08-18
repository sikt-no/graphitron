package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Records;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_LOCATION;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;

/**
 * The {@code directives} resource: a directive-vocabulary cheat-sheet (arguments, applicable
 * locations, descriptions). Exposed as an MCP <em>resource</em> (standing pinned context the client
 * holds), not a tool, because the directive grammar is shape, not state: it sits at the top of the
 * stability gradient, changing only with a graphitron version or with the author declaring a
 * directive of their own.
 *
 * <p>One statement over {@code graphql_directive} and its two child relations, which is the whole
 * vocabulary: capture records every definition the merged registry holds, so graphitron's own
 * grammar and the schema's own declarations are rows alike and their union is something the store
 * holds rather than something this resource assembles.
 *
 * <p>Before the first successful capture the store holds no definitions, and that is reported as
 * what it is. Answering with graphitron's own grammar alone would be a vocabulary silently missing
 * the author's directives, which reads as a grammar that forbids them.
 */
final class DirectivesResource {

    private DirectivesResource() {}

    /** Resource URI; the directive cheat-sheet a client pins as standing context. */
    static final String URI = "graphitron://directives";

    private static final String HEADING = "# Graphitron directives\n";

    static McpSchema.Resource resource() {
        return McpSchema.Resource.builder(URI, "directives")
            .title("Graphitron directive vocabulary")
            .description("The graphitron directive cheat-sheet: per directive, its arguments "
                + "(name, type, default, description), applicable locations, whether it is "
                + "repeatable, and its description. Every directive the schema defines, "
                + "graphitron's own vocabulary and the author's declarations alike, read off the "
                + "live build. Pinned standing context, re-readable.")
            .mimeType("text/markdown")
            .build();
    }

    /**
     * Renders the cheat-sheet from the graph's captured directive definitions. Re-reads reflect the
     * latest capture; a store holding no definitions reports that rather than substituting a partial
     * vocabulary.
     */
    static McpSchema.ReadResourceResult read(StoreHandle store) {
        if (store == null) {
            return body(HEADING + """

                This server holds no fact store handle, so the directive vocabulary cannot be read
                and an answer will not be fabricated. A dev session (mvn graphitron:dev) always
                wires its session store handle in.
                """);
        }
        var directives = store.dsl()
            .select(GRAPHQL_DIRECTIVE.DIRECTIVE_NAME, GRAPHQL_DIRECTIVE.REPEATABLE,
                GRAPHQL_DIRECTIVE.DESCRIPTION, locations(), arguments())
            .from(GRAPHQL_DIRECTIVE)
            .where(GRAPHQL_DIRECTIVE.GRAPH_NAME.eq(store.graphName()))
            .orderBy(GRAPHQL_DIRECTIVE.DIRECTIVE_NAME.asc())
            .fetch(Records.mapping(Directive::new));
        if (directives.isEmpty()) {
            return body(HEADING + """

                No directive definitions have been captured for this graph yet, so there is no
                vocabulary to report. The dev session writes it on its first successful schema
                parse; re-read this resource then.
                """);
        }

        var sb = new StringBuilder(HEADING);
        sb.append("\nDirective vocabulary of this schema: every directive it defines, graphitron's "
            + "own and the author's alike.\n");
        directives.forEach(d -> render(sb, d));
        return body(sb.toString());
    }

    /** One directive definition: what it is, where it may be applied, what it takes. */
    private record Directive(
        String name, Boolean repeatable, String description, List<String> locations,
        List<Argument> arguments
    ) {}

    /** One formal argument of a definition. */
    private record Argument(String name, String typeSdl, String defaultValue, String description) {}

    /**
     * The locations the definition permits, by name. Alphabetical rather than as the SDL spells
     * them, an order the relation does not carry: a permitted-location set has no declaration order
     * to preserve, unlike an argument list, whose ordinal is stored precisely because a reader can
     * ask what the author wrote first.
     */
    private static Field<List<String>> locations() {
        return multiset(
            select(GRAPHQL_DIRECTIVE_LOCATION.LOCATION)
                .from(GRAPHQL_DIRECTIVE_LOCATION)
                .where(GRAPHQL_DIRECTIVE_LOCATION.GRAPH_NAME.eq(GRAPHQL_DIRECTIVE.GRAPH_NAME)
                    .and(GRAPHQL_DIRECTIVE_LOCATION.DIRECTIVE_NAME.eq(GRAPHQL_DIRECTIVE.DIRECTIVE_NAME)))
                .orderBy(GRAPHQL_DIRECTIVE_LOCATION.LOCATION.asc()))
            .convertFrom(r -> r.map(Record1::value1));
    }

    /** The formal arguments, in the order the definition declares them. */
    private static Field<List<Argument>> arguments() {
        return multiset(
            select(GRAPHQL_DIRECTIVE_ARGUMENT.ARGUMENT_NAME, GRAPHQL_DIRECTIVE_ARGUMENT.TYPE_SDL,
                GRAPHQL_DIRECTIVE_ARGUMENT.DEFAULT_VALUE_SDL, GRAPHQL_DIRECTIVE_ARGUMENT.DESCRIPTION)
                .from(GRAPHQL_DIRECTIVE_ARGUMENT)
                .where(GRAPHQL_DIRECTIVE_ARGUMENT.GRAPH_NAME.eq(GRAPHQL_DIRECTIVE.GRAPH_NAME)
                    .and(GRAPHQL_DIRECTIVE_ARGUMENT.DIRECTIVE_NAME.eq(GRAPHQL_DIRECTIVE.DIRECTIVE_NAME)))
                .orderBy(GRAPHQL_DIRECTIVE_ARGUMENT.ORDINAL.asc()))
            .convertFrom(r -> r.map(Records.mapping(Argument::new)));
    }

    private static void render(StringBuilder sb, Directive d) {
        sb.append("\n## @").append(d.name()).append('\n');
        var facts = new ArrayList<String>();
        if (!d.locations().isEmpty()) {
            facts.add("on " + String.join(" | ", d.locations()));
        }
        if (Boolean.TRUE.equals(d.repeatable())) {
            facts.add("repeatable");
        }
        if (!facts.isEmpty()) {
            sb.append('\n').append(String.join(", ", facts)).append('\n');
        }
        prose(d.description()).ifPresent(desc -> sb.append('\n').append(desc).append('\n'));
        if (!d.arguments().isEmpty()) {
            sb.append("\nArguments:\n");
            for (var arg : d.arguments()) {
                sb.append("- `").append(arg.name()).append(": ").append(arg.typeSdl());
                if (arg.defaultValue() != null && !arg.defaultValue().isBlank()) {
                    sb.append(" = ").append(arg.defaultValue().strip());
                }
                sb.append('`');
                prose(arg.description())
                    .ifPresent(desc -> sb.append(" — ").append(desc.replace('\n', ' ')));
                sb.append('\n');
            }
        }
    }

    /** An SDL description as written, or absent where the author wrote none. */
    private static Optional<String> prose(String description) {
        return Optional.ofNullable(description).map(String::strip).filter(s -> !s.isEmpty());
    }

    private static McpSchema.ReadResourceResult body(String markdown) {
        var contents = McpSchema.TextResourceContents.builder(URI, markdown)
            .mimeType("text/markdown")
            .build();
        return McpSchema.ReadResourceResult.builder(List.of(contents)).build();
    }
}
