package no.sikt.graphitron.rewrite.catalog;

import no.sikt.graphitron.rewrite.FieldSourceSigil;

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
 * {@link Built#typesByName()} uniformly; consumers that care (today: the
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
 * Per-carrier projection of the payload data field's name. Keyed by the
         * carrier's SDL type name; value is the SDL field name of the carrier's single
         * data field. Populated only for types whose classifier-side structural DML-payload
         * scan ({@code BuildContext.scanStructuralDmlPayload}) admits; absent for everything
         * else.
         *
         * <p>Backing data for {@link #siteContext(String, String)}; the LSP arms route through
         * that method rather than evaluating the (typeName, fieldName) predicate themselves.
         */
        Map<String, String> payloadDataFieldByType();

        /**
 * Per-field LSP classification projection. Keyed by
         * {@code "ParentType.fieldName"}; value is the {@link FieldClassification} variant
         * the LSP's inlay-hint and hover arms render. Absent entries mean the classifier
         * produced no field for that coordinate (e.g., the buffer is mid-edit and
         * references a field the schema does not yet declare).
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
         * (0-based LSP coordinates, matching every other goto-definition consumer of
         * {@link CompletionData.SourceLocation}). Lets the LSP's intra-schema
         * goto-definition resolve a type reference to its declaration even when the
         * declaring file is not in an open buffer; the open-buffer tree-sitter scan stays
         * authoritative and this is the workspace-wide fallback. Populated from the
         * {@code TypeDefinitionRegistry} in {@code CatalogBuilder.buildSnapshot}; absent
         * entries (built-in scalars, types declared in the bundled directive source) are
         * not jumpable.
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

        /**
 * Site-context classifier at LSP-time. Returns the
         * {@link FieldSourceSigil.SiteContext} arm that
         * {@link FieldSourceSigil#sourceSigilDefinedAt} dispatches on for the
         * {@code (typeName, fieldName)} coordinate. The snapshot is the single source of truth
         * for which coordinate is the payload-data-field admit site today; LSP consumers route
         * through this method rather than reimplementing the predicate against the underlying
         * {@link #payloadDataFieldByType()} map, so a future broadening (a new admit site, a
         * second sigil) flips the sealed answer in one place.
         */
        default FieldSourceSigil.SiteContext siteContext(String typeName, String fieldName) {
            var carrierField = payloadDataFieldByType().get(typeName);
            if (carrierField != null && carrierField.equals(fieldName)) {
                return new FieldSourceSigil.SiteContext.PayloadDataField();
            }
            return new FieldSourceSigil.SiteContext.Other();
        }

        record Current(
            List<DirectiveShape> directives,
            Map<String, TypeBackingShape> typesByName,
            Map<String, String> payloadDataFieldByType,
            Map<String, FieldClassification> fieldClassificationsByCoord,
            Map<String, TypeClassification> typeClassificationsByName,
            Map<String, CompletionData.SourceLocation> typeDefinitionLocations
        ) implements Built {
            public Current {
                directives = List.copyOf(directives);
                typesByName = Map.copyOf(typesByName);
                payloadDataFieldByType = Map.copyOf(payloadDataFieldByType);
                fieldClassificationsByCoord = Map.copyOf(fieldClassificationsByCoord);
                typeClassificationsByName = Map.copyOf(typeClassificationsByName);
                typeDefinitionLocations = Map.copyOf(typeDefinitionLocations);
            }

            /**
             * Convenience constructor for callers (LSP unit tests, ad-hoc fixtures) that only
             * populate the directive surface, type-backing, and payload-data-field projections.
             * Fills the R160 classification projections and the R350 type-definition-location
             * map with empty maps.
             */
            public Current(
                List<DirectiveShape> directives,
                Map<String, TypeBackingShape> typesByName,
                Map<String, String> payloadDataFieldByType
            ) {
                this(directives, typesByName, payloadDataFieldByType, Map.of(), Map.of(), Map.of());
            }

            /**
             * Convenience constructor for callers that populate the R160 classification
             * projections but not the R350 type-definition-location map (the directive /
             * classification fixtures predate goto-definition fallback).
             */
            public Current(
                List<DirectiveShape> directives,
                Map<String, TypeBackingShape> typesByName,
                Map<String, String> payloadDataFieldByType,
                Map<String, FieldClassification> fieldClassificationsByCoord,
                Map<String, TypeClassification> typeClassificationsByName
            ) {
                this(directives, typesByName, payloadDataFieldByType,
                    fieldClassificationsByCoord, typeClassificationsByName, Map.of());
            }
        }

        record Previous(
            List<DirectiveShape> directives,
            Map<String, TypeBackingShape> typesByName,
            Map<String, String> payloadDataFieldByType,
            Map<String, FieldClassification> fieldClassificationsByCoord,
            Map<String, TypeClassification> typeClassificationsByName,
            Map<String, CompletionData.SourceLocation> typeDefinitionLocations
        ) implements Built {
            public Previous {
                directives = List.copyOf(directives);
                typesByName = Map.copyOf(typesByName);
                payloadDataFieldByType = Map.copyOf(payloadDataFieldByType);
                fieldClassificationsByCoord = Map.copyOf(fieldClassificationsByCoord);
                typeClassificationsByName = Map.copyOf(typeClassificationsByName);
                typeDefinitionLocations = Map.copyOf(typeDefinitionLocations);
            }

            /**
             * Convenience constructor for callers (LSP unit tests, ad-hoc fixtures) that only
             * populate the directive surface, type-backing, and payload-data-field projections.
             * Fills the R160 classification projections and the R350 type-definition-location
             * map with empty maps.
             */
            public Previous(
                List<DirectiveShape> directives,
                Map<String, TypeBackingShape> typesByName,
                Map<String, String> payloadDataFieldByType
            ) {
                this(directives, typesByName, payloadDataFieldByType, Map.of(), Map.of(), Map.of());
            }

            /**
             * Convenience constructor for callers that populate the R160 classification
             * projections but not the R350 type-definition-location map.
             */
            public Previous(
                List<DirectiveShape> directives,
                Map<String, TypeBackingShape> typesByName,
                Map<String, String> payloadDataFieldByType,
                Map<String, FieldClassification> fieldClassificationsByCoord,
                Map<String, TypeClassification> typeClassificationsByName
            ) {
                this(directives, typesByName, payloadDataFieldByType,
                    fieldClassificationsByCoord, typeClassificationsByName, Map.of());
            }
        }
    }

    static LspSchemaSnapshot unavailable() {
        return new Unavailable();
    }
}
