package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.rewrite.catalog.DirectiveShape;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeShape;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * R368 slice 6 — the {@code directives} resource: a directive-vocabulary cheat-sheet (arguments,
 * applicable locations, descriptions). Exposed as an MCP <em>resource</em> (standing pinned
 * context the client holds), not a tool, because the directive grammar is shape, not state: it
 * sits at the top of the stability gradient (changes only with a graphitron version).
 *
 * <p>The content is a frozen spine plus a live overlay: the bundled grammar (off
 * {@code Workspace.vocabulary()}, always present) unioned with the user-declared directives off the
 * live snapshot ({@code Workspace.snapshot()}). Bundled entries win on a name collision; re-reads
 * reflect the latest snapshot, degrading to the bundled grammar alone when no build has succeeded.
 * Both halves render their applicable locations uniformly off {@link DirectiveShape#locations()}
 * (the slice-6 widening exists so the user-declared half, which had thrown its locations away at
 * projection time, can show them too).
 */
final class DirectivesResource {

    private DirectivesResource() {}

    /** Resource URI; the directive cheat-sheet a client pins as standing context. */
    static final String URI = "graphitron://directives";

    static McpSchema.Resource resource() {
        return McpSchema.Resource.builder(URI, "directives")
            .title("Graphitron directive vocabulary")
            .description("The graphitron directive cheat-sheet: per directive, its arguments "
                + "(name, type, description), applicable locations, and description. Composed from "
                + "the bundled grammar (frozen, always present) unioned with the schema's "
                + "user-declared directives (live; reflects the latest successful build). Pinned "
                + "standing context, re-readable.")
            .mimeType("text/markdown")
            .build();
    }

    /**
     * Renders the cheat-sheet from the bundled directive shapes unioned with the live snapshot's
     * user-declared directives. {@code bundled} is projected once at server construction off the
     * frozen {@code vocabulary()} registry; bundled entries win on a name collision (the snapshot
     * carries the bundled directives too, post-merge, so {@code putIfAbsent} keeps only the
     * genuinely user-declared additions).
     */
    static McpSchema.ReadResourceResult read(List<DirectiveShape> bundled, LspSchemaSnapshot snapshot) {
        var byName = new LinkedHashMap<String, DirectiveShape>();
        bundled.stream()
            .sorted(Comparator.comparing(DirectiveShape::name))
            .forEach(d -> byName.put(d.name(), d));
        if (snapshot instanceof LspSchemaSnapshot.Built b) {
            b.directives().stream()
                .sorted(Comparator.comparing(DirectiveShape::name))
                .forEach(d -> byName.putIfAbsent(d.name(), d));
        }

        var sb = new StringBuilder();
        sb.append("# Graphitron directives\n\n");
        sb.append("Directive vocabulary cheat-sheet. Bundled grammar unioned with the schema's "
            + "user-declared directives.\n");
        for (var d : byName.values()) {
            render(sb, d);
        }
        var contents = McpSchema.TextResourceContents.builder(URI, sb.toString())
            .mimeType("text/markdown")
            .build();
        return McpSchema.ReadResourceResult.builder(List.of(contents)).build();
    }

    private static void render(StringBuilder sb, DirectiveShape d) {
        sb.append("\n## @").append(d.name()).append('\n');
        if (!d.locations().isEmpty()) {
            sb.append("\non ").append(String.join(" | ", d.locations())).append('\n');
        }
        d.description().filter(s -> !s.isBlank())
            .ifPresent(desc -> sb.append('\n').append(desc.strip()).append('\n'));
        if (!d.args().isEmpty()) {
            sb.append("\nArguments:\n");
            for (var arg : d.args()) {
                sb.append("- `").append(arg.name()).append(": ").append(renderType(arg.type())).append('`');
                arg.description().filter(s -> !s.isBlank())
                    .ifPresent(desc -> sb.append(" — ").append(desc.strip().replace('\n', ' ')));
                sb.append('\n');
            }
        }
    }

    /** Renders a {@link TypeShape} back to its SDL form ({@code String!}, {@code [Foo!]}). */
    private static String renderType(TypeShape type) {
        return switch (type) {
            case TypeShape.Named n -> n.typeName() + (n.nonNull() ? "!" : "");
            case TypeShape.List l -> "[" + renderType(l.inner()) + "]" + (l.nonNull() ? "!" : "");
        };
    }
}
