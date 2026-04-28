package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * Classify-time entity-resolution metadata for a federation entity type.
 *
 * <p>Every type whose SDL declaration carries an {@code @key} directive (or {@code @node}, which
 * implies {@code @key(fields: "id", resolvable: true)}) is recorded with one of these. Held in
 * the {@code entitiesByType} sidecar on
 * {@link no.sikt.graphitron.rewrite.GraphitronSchema} alongside the existing field maps.
 *
 * <p>{@code alternatives} carries one entry per resolvable {@code @key} (and the synthesised
 * {@code NODE_ID} alternative for {@code @node} types). The runtime dispatcher selects the
 * most-specific resolvable alternative whose required fields are a subset of the
 * representation's keys; ties broken by alternative size, then declaration order.
 */
public record EntityResolution(String typeName, TableRef table, List<KeyAlternative> alternatives) {}
