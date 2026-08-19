package no.sikt.graphitron.rewrite.catalog;


import java.util.Map;
import java.util.Optional;

/**
 * Projection of the parsed user schema's classifications, produced by a generator pass. No language
 * server surface reads it: every one of them asks the fact store, the last coordinate to move being
 * the generated call surface a {@code @routine} field binds to, which the catalog census carries.
 * The dev goal reads the availability arm alone, as its signal that a round classified.
 *
 * <p>The classification maps outlive that reader twice over: inside the classifier, where the type
 * projection is derived from the field one, and in the generator's tests, where they are the
 * classifier's own assertion surface.
 *
 * <p>Sealed over one axis, which is availability: whether the build pipeline has produced a
 * projection yet. A freshness axis stood beside it, separating the latest successful parse from the
 * last one before a regression, and it retired with its only reader. Nothing distinguishes a
 * projection built two edits ago from one built now, because what a stale projection was silencing
 * is read from the store now, where a document reports what the graph last captured.
 */
public sealed interface LspSchemaSnapshot permits LspSchemaSnapshot.Unavailable, LspSchemaSnapshot.Built {

    /**
     * Pre-build state: the dev pipeline has not produced a successful
     * snapshot yet. Consumers treat this as "no info to act on" and avoid
     * punishing the user for what cannot reliably be seen.
     */
    record Unavailable() implements LspSchemaSnapshot {}

    /**
     * Projection produced from a successful parse, whichever one it was.
     *
     * @param fieldClassificationsByCoord per-field classifications, keyed by
     *        {@code "ParentType.fieldName"}; the value is the {@link FieldClassification} variant the
     *        LSP's inlay-hint and hover arms render. An absent entry means the classifier produced no
     *        field for that coordinate, the buffer having been mid-edit at the last build.
     * @param typeClassificationsByName per-type classifications, keyed by the SDL type name, absent
     *        on the same terms
     */
    record Built(
        Map<String, FieldClassification> fieldClassificationsByCoord,
        Map<String, TypeClassification> typeClassificationsByName
    ) implements LspSchemaSnapshot {

        public Built {
            fieldClassificationsByCoord = Map.copyOf(fieldClassificationsByCoord);
            typeClassificationsByName = Map.copyOf(typeClassificationsByName);
        }

        /**
         * Convenience constructor for callers (LSP unit tests, ad-hoc fixtures) whose subject is a
         * build having happened rather than any classification. Fills both projections with empty
         * maps.
         */
        public Built() {
            this(Map.of(), Map.of());
        }

        /**
         * Convenience lookup; returns {@link Optional#empty()} when no field
         * classification is on file for the {@code (typeName, fieldName)} coordinate.
         */
        public Optional<FieldClassification> fieldClassification(String typeName, String fieldName) {
            return Optional.ofNullable(fieldClassificationsByCoord.get(typeName + "." + fieldName));
        }

        /**
         * Convenience lookup; returns {@link Optional#empty()} when no type classification
         * is on file for {@code name}.
         */
        public Optional<TypeClassification> typeClassification(String name) {
            return Optional.ofNullable(typeClassificationsByName.get(name));
        }
    }

    static LspSchemaSnapshot unavailable() {
        return new Unavailable();
    }
}
