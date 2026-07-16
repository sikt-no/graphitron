package no.sikt.graphitron.rewrite.model;

/**
 * A {@link GraphitronType} variant that contributes a {@code <name>.java} (or related
 * per-type-stem) file to the generator's emission.
 *
 * <p>Every {@link GraphitronType} arm implements this except {@link GraphitronType.ScalarType}
 * (handled by {@link no.sikt.graphitron.rewrite.ScalarTypeResolver}, no per-type file) and
 * {@link GraphitronType.UnclassifiedType} (already diagnosed; never reaches emission). Mirrors
 * the {@link SqlGeneratingField} / {@link BatchKeyField} precedent: an orthogonal capability
 * marker the relevant variants opt into, read by callers via {@code instanceof EmitsPerTypeFile}.
 *
 * <p>The capability is read today by the case-insensitive type-name collision detector in
 * {@link no.sikt.graphitron.rewrite.GraphitronSchemaBuilder#rejectCaseInsensitiveTypeCollisions}
 * so the check only flags variants that would otherwise clobber each other on
 * case-insensitive filesystems.
 *
 * <p>This interface is intentionally standalone (does not extend {@link GraphitronType}) so it can
 * be applied as an orthogonal capability without being restricted by the sealed hierarchy. Callers
 * receive {@link GraphitronType} and pattern-match with {@code instanceof EmitsPerTypeFile}.
 */
public interface EmitsPerTypeFile {
}
