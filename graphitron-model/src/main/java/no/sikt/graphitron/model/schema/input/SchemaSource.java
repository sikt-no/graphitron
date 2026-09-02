package no.sikt.graphitron.model.schema.input;

import java.nio.file.Path;
import java.util.Objects;

/**
 * What a {@link SchemaInput} points at, as a claim the producer makes rather than a string every
 * consumer re-classifies. A source is either a file on disk or a bare label, and the producer that
 * knows which decides: the {@code <schemaInputs>} expansion mints {@link File} arms because a
 * scanner match is a regular file by construction, and a programmatic caller picks a door
 * explicitly. Nothing probes the filesystem to recover the arm, so identical configuration
 * classifies identically across runs regardless of the working directory or what happens to exist
 * when a value is constructed.
 *
 * <p>Exactly one canonical rendering, {@link #sourceName()}, and it is load-bearing well past this
 * type. It is the string {@link no.sikt.graphitron.model.schema.SchemaLoader#load} hands
 * the parser and the string graphql-java returns as {@code SourceLocation.getSourceName()}, so
 * {@link SchemaInputAttribution}'s map, capture's stamp lookup, the diagnostics stratum's
 * {@code file} columns and the LSP's URI equality all match byte-for-byte with no renormalisation.
 * The diagnostics columns store this string as read; a wire whose protocol names a document by URI
 * renders one from it at its own boundary, through
 * {@link no.sikt.graphitron.model.read.SourceUri}. A
 * divergence of one character costs no compile error and no parse failure; it silently stops tags
 * and description notes from being applied and silently unmatches capture's stamp lookup, which is
 * why the invariant is held by an end-to-end attribution case rather than by an equality on this
 * type.
 */
public sealed interface SchemaSource {

    /**
     * The canonical identifier this source is known by everywhere outside the carrier: at the
     * parser handoff and at every lookup keyed on what the parser hands back.
     */
    String sourceName();

    /**
     * A schema file, normalized at mint to the absolute normalized path
     * {@link SchemaRecipe#expand(Path)} composes from a scanner match, so a minted arm and a
     * re-expanded one render the same string.
     */
    record File(Path path) implements SchemaSource {
        public File {
            Objects.requireNonNull(path, "path");
            path = path.toAbsolutePath().normalize();
        }

        @Override
        public String sourceName() {
            return path.toString();
        }
    }

    /**
     * A bare programmatic label, rendered verbatim. A label is deliberately outside freshness
     * coverage: nothing on disk corresponds to it, so a currency check has nothing to re-expand
     * and no way to notice it went stale.
     */
    record Named(String label) implements SchemaSource {
        public Named {
            Objects.requireNonNull(label, "label");
        }

        @Override
        public String sourceName() {
            return label;
        }
    }

    /** The file door. Returns the arm rather than the interface, for callers that need the arm. */
    static File file(Path path) {
        return new File(path);
    }

    /** The label door. Returns the arm rather than the interface, for symmetry with {@link #file}. */
    static Named named(String label) {
        return new Named(label);
    }
}
