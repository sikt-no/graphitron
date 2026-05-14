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
         * R159 — per-carrier projection of the carrier-payload data field's name. Keyed by the
         * carrier's SDL type name; value is the SDL field name of the carrier's single
         * {@code DataChannel} role. Populated only for types whose classifier-side carrier walk
         * returns {@code Ok}; absent for everything else.
         *
         * <p>The LSP's {@code @field(name: "$source")} arms (FieldCompletions admit,
         * Diagnostics not-defined-here overlay) consume this to detect "is this site a carrier
         * data field?" without a separate predicate evaluation: parent type name lookup plus
         * field-name equality.
         */
        Map<String, String> carrierDataFieldByType();

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
         * R159 — convenience lookup returning the carrier's data-field name for {@code typeName},
         * or {@link Optional#empty()} when {@code typeName} is not a classified carrier today.
         */
        default Optional<String> carrierDataField(String typeName) {
            return Optional.ofNullable(carrierDataFieldByType().get(typeName));
        }

        record Current(List<DirectiveShape> directives, Map<String, TypeBackingShape> typesByName,
                       Map<String, String> carrierDataFieldByType) implements Built {
            public Current {
                directives = List.copyOf(directives);
                typesByName = Map.copyOf(typesByName);
                carrierDataFieldByType = Map.copyOf(carrierDataFieldByType);
            }
            /** Back-compat overload for callers that don't yet populate the carrier projection. */
            public Current(List<DirectiveShape> directives, Map<String, TypeBackingShape> typesByName) {
                this(directives, typesByName, Map.of());
            }
        }

        record Previous(List<DirectiveShape> directives, Map<String, TypeBackingShape> typesByName,
                        Map<String, String> carrierDataFieldByType) implements Built {
            public Previous {
                directives = List.copyOf(directives);
                typesByName = Map.copyOf(typesByName);
                carrierDataFieldByType = Map.copyOf(carrierDataFieldByType);
            }
            /** Back-compat overload for callers that don't yet populate the carrier projection. */
            public Previous(List<DirectiveShape> directives, Map<String, TypeBackingShape> typesByName) {
                this(directives, typesByName, Map.of());
            }
        }
    }

    static LspSchemaSnapshot unavailable() {
        return new Unavailable();
    }
}
