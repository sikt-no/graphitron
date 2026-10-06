package no.sikt.graphitron.rewrite.model;

import java.util.List;
import no.sikt.graphitron.model.jooq.TableRef;

/**
 * Classify-time entity-resolution metadata for a federation entity type.
 *
 * <p>Every type whose SDL declaration carries an {@code @key} directive (or {@code @node}, which
 * synthesises an {@code @key(fields: "id")} unless the consumer declares one) is recorded with
 * one of these. Held in the {@code entitiesByType} sidecar on
 * {@link no.sikt.graphitron.rewrite.GraphitronSchema} alongside the existing field maps.
 *
 * <p>{@code alternatives} carries one entry per {@code @key}, resolvable or not (and the
 * synthesised {@link KeyAlternative.NodeId} alternative for {@code @node} types, which keeps a
 * consumer's explicit {@code resolvable: false}; see
 * {@link no.sikt.graphitron.model.schema.federation.KeyNodeSynthesiser}). The runtime dispatcher
 * skips a non-resolvable alternative and selects the most-specific resolvable one whose required
 * fields are a subset of the representation's keys; ties broken by alternative size, then
 * declaration order. An entity with no resolvable alternative ({@link #resolvable()} is
 * {@code false}) is therefore dispatched by neither {@code _entities} nor {@code Query.node}.
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
) {
    /**
     * Whether this subgraph resolves the entity: true when any alternative is resolvable.
     */
    public boolean resolvable() {
        return alternatives.stream().anyMatch(KeyAlternative::resolvable);
    }
}
