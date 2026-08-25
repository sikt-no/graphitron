package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;

/**
 * Where the graph's own capture saw a type declared. The SDL-family counterpart of
 * {@link SourceDeclarations}: same question, same conversion to the editor's coordinates, asked of a
 * schema file rather than a {@code .java} one.
 *
 * <p>A type has as many declaration sites as the schema gives it, all five extension kinds being
 * live, so this reader picks rather than looks up. Merge order is the pick: the base definition holds
 * {@code merge_ordinal} 0 and the extensions follow in document order, so the first site is the
 * declaration an author means by the name, and on a base-less extension chain (an author error a
 * detection reports) the first extension answers instead of nothing. That the incumbent projection
 * reduced the same population to one entry per type before the language server ever saw it is why
 * this could only ever be reproduced here, not chosen.
 *
 * <p>A site whose source no editor can open is skipped, not refused: the next site in merge order
 * answers. Capture writes a schema file's {@code source_name} as the absolute normalized path it
 * read, which is the convention {@code StoreAccess.sourceNameOf} reads in the other direction, so
 * a relative name is a source that is not a file at all. Three of those exist: graphitron's bundled
 * directive definitions, a programmatic caller's SDL label, and the source name the {@code @link}
 * tag synthesiser stamps. Their declarations are real facts about the graph and stay readable for
 * every other question; they are simply nowhere to jump.
 */
public final class SdlDeclarations {

    private SdlDeclarations() {}

    /**
     * Where an editor jumps for the named type, or empty when the graph declares it at no openable
     * site. The position is the declaration's own start (the {@code type} keyword), the site being
     * what the store records; a name span is a parse's answer, and a caller holding the file in a
     * buffer has one.
     */
    public static Optional<Location> typeLocation(StoreHandle store, String typeName) {
        if (typeName == null) return Optional.empty();
        var sites = store.dsl()
            .select(GRAPHQL_TYPE_DECLARATION.SOURCE_NAME,
                GRAPHQL_TYPE_DECLARATION.SOURCE_LINE, GRAPHQL_TYPE_DECLARATION.SOURCE_COLUMN)
            .from(GRAPHQL_TYPE_DECLARATION)
            .where(GRAPHQL_TYPE_DECLARATION.GRAPH_NAME.eq(store.graphName()))
            .and(GRAPHQL_TYPE_DECLARATION.TYPE_NAME.eq(typeName))
            .orderBy(GRAPHQL_TYPE_DECLARATION.MERGE_ORDINAL)
            .fetch();
        for (var site : sites) {
            var location = location(site.value1(), site.value2(), site.value3());
            if (location.isPresent()) return location;
        }
        return Optional.empty();
    }

    /**
     * One site in the editor's coordinates: a {@code file:} URI and a 0-based line / column,
     * collapsed to a zero-width range. The store holds the SDL parse's own convention (an absolute
     * path, 1-based line and column), so this is the same edge conversion
     * {@link SourceDeclarations} makes for the Java parse's.
     *
     * <p>Visible to the package so {@link SdlTypeUsages} converts its rows through this one
     * conversion rather than a second copy of it. Both read captured SDL positions, and the
     * skip-a-site rule above (a relative {@code source_name} is a source no editor can open) is
     * a property of the rows rather than of the question being asked, so the two surfaces cannot
     * be allowed to disagree about it.
     */
    static Optional<Location> location(String sourceName, Integer line, Integer column) {
        if (sourceName == null || line == null || column == null || line < 0 || column < 0) {
            return Optional.empty();
        }
        Path path;
        try {
            path = Path.of(sourceName);
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
        if (!path.isAbsolute()) return Optional.empty();
        var start = new Position(Math.max(line - 1, 0), Math.max(column - 1, 0));
        return Optional.of(new Location(path.toUri().toString(), new Range(start, start)));
    }
}
