package no.sikt.graphitron.rewrite.model;

import java.util.Map;

/**
 * The entities a graph resolves by selecting a row from their table, keyed by type name: the
 * {@code entitiesByType} input the schema build is handed rather than derives. Read from the store by
 * {@link no.sikt.graphitron.rewrite.schema.federation.StoreEntityResolutions}; a build with no store
 * behind it states {@link #none()}, and then plans no entity dispatch, no entity tenant routing and no
 * {@code Query.node} resolution.
 */
public record EntityResolutions(Map<String, EntityResolution> byType) {

    public EntityResolutions {
        byType = Map.copyOf(byType);
    }

    /** No entities: the input of a build no store stands behind. */
    public static EntityResolutions none() {
        return new EntityResolutions(Map.of());
    }
}
