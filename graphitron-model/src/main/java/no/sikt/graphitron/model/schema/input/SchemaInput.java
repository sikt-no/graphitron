package no.sikt.graphitron.model.schema.input;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * One resolved schema source paired with optional attribution the rewrite
 * pipeline will apply to its contents.
 *
 * <p>The {@code source} is a {@link SchemaSource}, sealed, so what the entry points at is a claim
 * its producer made rather than a string a consumer re-classifies; there is no String-shaped
 * constructor to fall through, and every construction site states whether it holds a file or a
 * label. {@link SchemaSource#sourceName()} is the canonical identifier the supplier hands to
 * {@link no.sikt.graphitron.model.schema.SchemaLoader#load}; the same
 * string is returned by {@code SourceLocation.getSourceName()} at applier
 * time, so map lookups keyed on it match byte-for-byte without renormalisation.
 *
 * <p>A {@code tag} causes {@link TagApplier} to append {@code @tag(name: "<tag>")}
 * to every in-scope element defined in the source. A {@code descriptionNote}
 * causes {@link DescriptionNoteApplier} to append the note (with a blank-line
 * separator) to the description of every in-scope element. The two are
 * independent; either, both, or neither may be present on a given entry.
 */
public record SchemaInput(
    SchemaSource source,
    Optional<String> tag,
    Optional<String> descriptionNote
) {
    public SchemaInput {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(descriptionNote, "descriptionNote");
    }

    /** The source's canonical rendering; see {@link SchemaSource#sourceName()}. */
    public String sourceName() {
        return source.sourceName();
    }

    /** An unattributed entry over a schema file on disk. */
    public static SchemaInput file(Path path) {
        return new SchemaInput(SchemaSource.file(path), Optional.empty(), Optional.empty());
    }

    /**
     * An unattributed entry over a bare programmatic label. Choosing this door opts the source out
     * of freshness coverage: a label re-expands to itself, so a currency check counts it as neither
     * present nor lost, and only a {@link #file} entry can be observed to have gone stale.
     */
    public static SchemaInput named(String label) {
        return new SchemaInput(SchemaSource.named(label), Optional.empty(), Optional.empty());
    }
}
