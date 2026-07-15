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
 * {@link KeyAlternative.NodeId} alternative for {@code @node} types). The runtime dispatcher
 * selects the most-specific resolvable alternative whose required fields are a subset of the
 * representation's keys; ties broken by alternative size, then declaration order.
 *
 * <p>The resolved NodeId wire prefix (the {@code @node(typeId:)} value, defaulted to the type
 * name at classify time) is not held here: it lives on the type's {@link KeyAlternative.NodeId}
 * alternative as {@code expectedTypeId}, the single slot the dispatcher passes into
 * {@code NodeIdEncoder.decodeValues}. A {@code @node} type always has exactly one such
 * alternative; a {@code @key}-only type has none.
 */
public record EntityResolution(
    String typeName,
    TableRef table,
    List<KeyAlternative> alternatives
) {}
