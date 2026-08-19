package no.sikt.graphitron.render;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.KeyProjectionRelation;
import no.sikt.graphitron.javapoet.ClassName;

import java.util.function.Function;

/**
 * What one generated class brings to a projected key read: the graph's projections, and the name that
 * class hosts each {@code decode<Record>} body under. Per class, where {@link ProjectedKeyReads} is
 * per method, and {@link #at} is the step between them.
 *
 * <p>Two things ride together here because both are the host's rather than the command's. The
 * relation is the plan's, handed down the same way a command row is. The decode helper's name is the
 * host's own allocation of its private-static method namespace: a {@code <Type>Fetchers} class may
 * already host a {@code decode<Record>} body for a jOOQ-record-typed input-bean member, and the
 * resolver keeping those names collision-free across schema packages lives with the shell that owns
 * the class. A renderer therefore receives one value and never asks either question.
 *
 * @param projections      the graph's projected {@code argMapping} bindings
 * @param decodeHelperName the host's name for the {@code decode<Record>} body of a decoded record
 *                         class; the host emits that body, this only spells the call
 */
public record ProjectedKeyHost(KeyProjectionRelation projections,
                               Function<ClassName, String> decodeHelperName) {

    /** The sink for the method rendering {@code coordinate}'s bindings. */
    public ProjectedKeyReads at(FieldCoordinates coordinate) {
        return ProjectedKeyReads.at(coordinate, projections, decodeHelperName);
    }

    /**
     * The host of a class no projection reaches: every lookup misses, and asking for a decode helper
     * name is a drift report rather than an answer. The arm for a class whose emitted bindings carry
     * no projection at all, and for the out-of-band emission contexts that hold no plan.
     */
    public static ProjectedKeyHost unprojected() {
        return new ProjectedKeyHost(KeyProjectionRelation.empty(), recordClass -> {
            throw new IllegalStateException(
                "a projected key read reached a class with no decode-helper host: "
                + recordClass.simpleName() + "; the plan's reachability check and this class's"
                + " projection host have drifted");
        });
    }
}
