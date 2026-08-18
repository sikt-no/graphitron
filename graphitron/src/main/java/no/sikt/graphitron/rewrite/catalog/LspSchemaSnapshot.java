package no.sikt.graphitron.rewrite.catalog;


import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Projection of the parsed user schema, shipped through the dev pipeline to
 * the LSP as a side-channel alongside {@link CompletionData}. The LSP uses
 * this to resolve directive references that {@code LspVocabulary} (the
 * bundled-SDL view) does not declare: user-authored federation directives,
 * {@code @auth}-style guards, anything else the consumer's schema brings
 * along.
 *
 * <p>Sealed over two orthogonal axes. The first ({@link Unavailable} vs.
 * {@link Built}) carries <em>availability</em>: whether the build pipeline
 * has produced a snapshot yet. The second ({@link Built.Current} vs.
 * {@link Built.Previous}) carries <em>freshness</em>: whether the snapshot
 * reflects the user's latest edit or the last successful parse before a
 * regression. Consumers that don't care about freshness switch on the
 * {@link Built} super-permit and read {@link Built#directives()} /
 * {@link Built#typesByName()} uniformly; consumers that care (the
 * unknown-directive validator) switch through to the leaf permits.
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
         * Per-named-type backing projection: the LSP's {@code @field(name:)}
         * arms ({@code FieldCompletions}, {@code Diagnostics},
         * {@code Hovers}) consume this to dispatch on the enclosing GraphQL
         * type's backing shape (record / POJO / jOOQ record / table /
         * unbacked). Keyed by the SDL type name; absent entries mean the
         * classifier produced no record for that name (e.g., the buffer is
         * mid-edit and references a type name the schema does not yet
         * declare).
         */
        Map<String, TypeBackingShape> typesByName();

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

        /**
         * Per-named-type declaration location, keyed by the SDL type name; value is
         * the canonical {@code type}/{@code scalar} declaration's source position
         * (0-based LSP coordinates, as in {@link CompletionData.SourceLocation}).
         * Absent entries (built-in scalars, types declared in the bundled directive
         * source) are not jumpable.
         *
         * <p>The MCP schema view is what still reads this. The language server's
         * goto-definition fallback used to, and now asks the fact store's declaration
         * sites instead, which hold every site a type has rather than the one entry
         * this map reduces them to; the projection retires when its remaining reader
         * does rather than for want of a substrate.
         */
        Map<String, CompletionData.SourceLocation> typeDefinitionLocations();

        default Optional<DirectiveShape> directive(String name) {
            return directives().stream().filter(d -> d.name().equals(name)).findFirst();
        }

        /**
         * Convenience lookup; returns {@link Optional#empty()} when no
         * classifier-produced shape is on file for {@code name}.
         */
        default Optional<TypeBackingShape> typeBacking(String name) {
            return Optional.ofNullable(typesByName().get(name));
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

        /**
         * Convenience lookup; returns {@link Optional#empty()} when no declaration
         * location is on file for {@code name} (built-in scalar, bundled-directive type,
         * or a name the schema does not declare).
         */
        default Optional<CompletionData.SourceLocation> typeDefinitionLocation(String name) {
            return Optional.ofNullable(typeDefinitionLocations().get(name));
        }

        record Current(
            List<DirectiveShape> directives,
            Map<String, TypeBackingShape> typesByName,
            Map<String, FieldClassification> fieldClassificationsByCoord,
            Map<String, TypeClassification> typeClassificationsByName,
            Map<String, CompletionData.SourceLocation> typeDefinitionLocations
        ) implements Built {
            public Current {
                directives = List.copyOf(directives);
                typesByName = Map.copyOf(typesByName);
                fieldClassificationsByCoord = Map.copyOf(fieldClassificationsByCoord);
                typeClassificationsByName = Map.copyOf(typeClassificationsByName);
                typeDefinitionLocations = Map.copyOf(typeDefinitionLocations);
            }

            /**
             * Convenience constructor for callers (LSP unit tests, ad-hoc fixtures) that only
             * populate the directive surface and the type-backing projection. Fills the
             * classification projections and the type-definition-location map with empty maps.
             */
            public Current(
                List<DirectiveShape> directives,
                Map<String, TypeBackingShape> typesByName
            ) {
                this(directives, typesByName, Map.of(), Map.of(), Map.of());
            }

            /**
             * Convenience constructor for callers that populate the classification
             * projections but not the type-definition-location map.
             */
            public Current(
                List<DirectiveShape> directives,
                Map<String, TypeBackingShape> typesByName,
                Map<String, FieldClassification> fieldClassificationsByCoord,
                Map<String, TypeClassification> typeClassificationsByName
            ) {
                this(directives, typesByName,
                    fieldClassificationsByCoord, typeClassificationsByName, Map.of());
            }
        }

        record Previous(
            List<DirectiveShape> directives,
            Map<String, TypeBackingShape> typesByName,
            Map<String, FieldClassification> fieldClassificationsByCoord,
            Map<String, TypeClassification> typeClassificationsByName,
            Map<String, CompletionData.SourceLocation> typeDefinitionLocations
        ) implements Built {
            public Previous {
                directives = List.copyOf(directives);
                typesByName = Map.copyOf(typesByName);
                fieldClassificationsByCoord = Map.copyOf(fieldClassificationsByCoord);
                typeClassificationsByName = Map.copyOf(typeClassificationsByName);
                typeDefinitionLocations = Map.copyOf(typeDefinitionLocations);
            }

            /**
             * Convenience constructor for callers (LSP unit tests, ad-hoc fixtures) that only
             * populate the directive surface and the type-backing projection. Fills the
             * classification projections and the type-definition-location map with empty maps.
             */
            public Previous(
                List<DirectiveShape> directives,
                Map<String, TypeBackingShape> typesByName
            ) {
                this(directives, typesByName, Map.of(), Map.of(), Map.of());
            }

            /**
             * Convenience constructor for callers that populate the classification
             * projections but not the type-definition-location map.
             */
            public Previous(
                List<DirectiveShape> directives,
                Map<String, TypeBackingShape> typesByName,
                Map<String, FieldClassification> fieldClassificationsByCoord,
                Map<String, TypeClassification> typeClassificationsByName
            ) {
                this(directives, typesByName,
                    fieldClassificationsByCoord, typeClassificationsByName, Map.of());
            }
        }
    }

    static LspSchemaSnapshot unavailable() {
        return new Unavailable();
    }
}
