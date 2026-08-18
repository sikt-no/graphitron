package no.sikt.graphitron.rewrite.catalog;


import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Projection of the parsed user schema, shipped through the dev pipeline to
 * the LSP as a side-channel. The LSP uses this to resolve directive references
 * that {@code LspVocabulary} (the bundled-SDL view) does not declare:
 * user-authored federation directives, {@code @auth}-style guards, anything
 * else the consumer's schema brings along.
 *
 * <p>Every other surface asks the fact store, so what the language server still
 * reads here is the directive surface and, at one coordinate, which Java method
 * a method-backed field binds to. The classification maps outlive their reader
 * as the classifier's own assertion surface in the generator's tests.
 *
 * <p>Sealed over two orthogonal axes. The first ({@link Unavailable} vs.
 * {@link Built}) carries <em>availability</em>: whether the build pipeline
 * has produced a snapshot yet. The second ({@link Built.Current} vs.
 * {@link Built.Previous}) carries <em>freshness</em>: whether the snapshot
 * reflects the user's latest edit or the last successful parse before a
 * regression. Consumers that don't care about freshness switch on the
 * {@link Built} super-permit and read {@link Built#directives()} uniformly;
 * consumers that care (the unknown-directive validator) switch through to the
 * leaf permits.
 *
 * <p>{@code Workspace} owns the lifecycle and the volatile reference; the
 * producer ({@code CatalogBuilder.buildSnapshot}) only ever returns
 * {@link Built.Current}.
 */
public sealed interface LspSchemaSnapshot permits LspSchemaSnapshot.Unavailable, LspSchemaSnapshot.Built {

    /**
     * Pre-build state: the dev pipeline has not produced a successful
     * snapshot yet. Consumers treat this as "no info to act on" and avoid
     * punishing the user for what cannot reliably be seen.
     */
    record Unavailable() implements LspSchemaSnapshot {}

    /**
     * Snapshot produced from a successful parse. The {@link Current} permit
     * is the freshest known projection; {@link Previous} is the most recent
     * successful one, retained when a later parse fails so consumers don't
     * lose the last good directive surface.
     */
    sealed interface Built extends LspSchemaSnapshot permits Built.Current, Built.Previous {
        List<DirectiveShape> directives();

        /**
         * Per-field LSP classification projection. Keyed by
         * {@code "ParentType.fieldName"}; value is the {@link FieldClassification} variant
         * the LSP's inlay-hint and hover arms render. Absent entries mean the classifier
         * produced no field for that coordinate (e.g. the buffer is mid-edit).
         */
        Map<String, FieldClassification> fieldClassificationsByCoord();

        /**
         * Per-type LSP classification projection. Keyed by the SDL type name; value
         * is the {@link TypeClassification} variant the LSP's inlay-hint and hover arms
         * render. Absent entries mean the classifier produced no type for that name.
         */
        Map<String, TypeClassification> typeClassificationsByName();

        default Optional<DirectiveShape> directive(String name) {
            return directives().stream().filter(d -> d.name().equals(name)).findFirst();
        }

        /**
         * Convenience lookup; returns {@link Optional#empty()} when no field
         * classification is on file for the {@code (typeName, fieldName)} coordinate.
         */
        default Optional<FieldClassification> fieldClassification(String typeName, String fieldName) {
            return Optional.ofNullable(fieldClassificationsByCoord().get(typeName + "." + fieldName));
        }

        /**
         * Convenience lookup; returns {@link Optional#empty()} when no type classification
         * is on file for {@code name}.
         */
        default Optional<TypeClassification> typeClassification(String name) {
            return Optional.ofNullable(typeClassificationsByName().get(name));
        }

        record Current(
            List<DirectiveShape> directives,
            Map<String, FieldClassification> fieldClassificationsByCoord,
            Map<String, TypeClassification> typeClassificationsByName
        ) implements Built {
            public Current {
                directives = List.copyOf(directives);
                fieldClassificationsByCoord = Map.copyOf(fieldClassificationsByCoord);
                typeClassificationsByName = Map.copyOf(typeClassificationsByName);
            }

            /**
             * Convenience constructor for callers (LSP unit tests, ad-hoc fixtures) that only
             * populate the directive surface. Fills the classification projections with empty
             * maps.
             */
            public Current(List<DirectiveShape> directives) {
                this(directives, Map.of(), Map.of());
            }
        }

        record Previous(
            List<DirectiveShape> directives,
            Map<String, FieldClassification> fieldClassificationsByCoord,
            Map<String, TypeClassification> typeClassificationsByName
        ) implements Built {
            public Previous {
                directives = List.copyOf(directives);
                fieldClassificationsByCoord = Map.copyOf(fieldClassificationsByCoord);
                typeClassificationsByName = Map.copyOf(typeClassificationsByName);
            }

            /**
             * Convenience constructor for callers (LSP unit tests, ad-hoc fixtures) that only
             * populate the directive surface. Fills the classification projections with empty
             * maps.
             */
            public Previous(List<DirectiveShape> directives) {
                this(directives, Map.of(), Map.of());
            }
        }
    }

    static LspSchemaSnapshot unavailable() {
        return new Unavailable();
    }
}
