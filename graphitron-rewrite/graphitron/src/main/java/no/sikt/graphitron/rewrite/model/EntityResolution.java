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
 *
 * <p>{@code nodeTypeId} is the {@code @node(typeId:)} value when the type is a
 * {@link no.sikt.graphitron.rewrite.model.GraphitronType.NodeType}; defaults to the type name
 * when the directive is omitted, per the documented {@code @node} contract. {@code null} when
 * the type carries {@code @key} but is not a {@code @node} (no NodeId-decode path applies).
 * Used by the dispatcher to pass the right {@code expectedTypeId} into
 * {@code NodeIdEncoder.decodeValues} for {@link KeyAlternative.KeyShape#NODE_ID} alternatives.
 */
public record EntityResolution(
    String typeName,
    TableRef table,
    List<KeyAlternative> alternatives,
    String nodeTypeId
) {}
