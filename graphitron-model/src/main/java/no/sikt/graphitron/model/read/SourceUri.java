package no.sikt.graphitron.model.read;

import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The one spelling on which an editor's document URI and a stored {@code source_name} meet, in both
 * directions.
 *
 * <p>Every stored column holds a path: capture writes a schema file's {@code source_name} as the
 * absolute normalized path it read, and the diagnostics stratum's {@code file} columns hold the same
 * form. The URI is a wire spelling and lives only on the wires that require it, the language server
 * protocol's document identity and the MCP tools' published locations, so a boundary holding one
 * spelling and needing the other converts here.
 *
 * <p>It lives in the module that declares those columns rather than in either module that fills or
 * reads them, for the reason {@link no.sikt.graphitron.model.grammar.QualifiedNameGrammar} states
 * about its own split: the two directions were owned separately, the forward trip by the generator
 * and the reverse by the language server, which is two opinions about one spelling with nothing
 * holding them to each other. Round-tripping is the property that matters and it is only checkable
 * where both halves are.
 *
 * <p>Neither direction throws. A value that is not a path, and a URI that names no local file,
 * each answer that rather than failing: what the store has to say about content that is not on
 * disk is nothing, which is an answer a caller can act on.
 */
public final class SourceUri {

    private SourceUri() {
    }

    /**
     * The URI form of a stored {@code source_name}. A value the platform will not accept as a path
     * is returned as written, and an absent one stays absent, so a caller comparing against it
     * still gets a total function and an unmatched row rather than an exception.
     */
    public static String of(String sourceName) {
        if (sourceName == null) {
            return null;
        }
        try {
            return Path.of(sourceName).toUri().toString();
        } catch (InvalidPathException e) {
            return sourceName;
        }
    }

    /**
     * The URI form of a stored directory path: the URI of a file inside it with that last segment
     * removed, never the converted directory path itself. {@link Path#toUri()} decides on a trailing
     * slash by asking the filesystem, so converting a directory that happens to exist on disk yields
     * one and a directory that does not yields none, which would put the presence of a directory on
     * the wire. Deriving from a file inside it is the same truncation the {@code diagnostic} view
     * performs over paths, applied on the URI side.
     *
     * <p>Total on the same terms as {@link #of}: whatever the caller holds is what gets rendered, so
     * a value the truncation left as a whole path renders as that path's URI.
     */
    public static String ofDirectory(String directory) {
        if (directory == null) {
            return null;
        }
        String inside = of(directory + "/x");
        int lastSlash = inside.lastIndexOf('/');
        return lastSlash < 0 ? inside : inside.substring(0, lastSlash);
    }

    /**
     * The stored {@code source_name} for a document URI, or empty where the URI names no local file
     * (an untitled buffer, a non-file scheme). Absolute and normalized, which is the form capture
     * writes, so the two meet without either side re-normalizing.
     */
    public static Optional<String> sourceNameOf(String uri) {
        if (uri == null || !uri.startsWith("file:")) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(URI.create(uri)).toAbsolutePath().normalize().toString());
        } catch (IllegalArgumentException | FileSystemNotFoundException e) {
            return Optional.empty();
        }
    }
}
