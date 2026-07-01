package no.sikt.graphitron.rewrite.schema.input;

import java.util.Objects;
import java.util.Optional;

/**
 * One resolved schema source paired with optional attribution the rewrite
 * pipeline will apply to its contents.
 *
 * <p>The {@code sourceName} is the canonical identifier the supplier hands to
 * {@link no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader#load}; the same
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
    String sourceName,
    Optional<String> tag,
    Optional<String> descriptionNote
) {
    public SchemaInput {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(descriptionNote, "descriptionNote");
    }

    public static SchemaInput plain(String sourceName) {
        return new SchemaInput(sourceName, Optional.empty(), Optional.empty());
    }
}
