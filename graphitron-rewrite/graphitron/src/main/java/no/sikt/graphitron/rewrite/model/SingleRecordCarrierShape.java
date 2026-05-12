package no.sikt.graphitron.rewrite.model;

import java.util.List;
import java.util.Optional;

/**
 * The resolved shape of a single-record carrier — a plain SDL Object whose fields each classify
 * into a {@link CarrierFieldRole} permit. Today (R141) the admitted permits are exactly
 * {@link CarrierFieldRole.DataChannel} and {@link CarrierFieldRole.ErrorChannelRole}; future
 * Backlog items add permits (and tighten the compact-constructor invariant) as new sibling-field
 * shapes are admitted.
 *
 * <p>{@link #roles} is the closed enumeration produced by
 * {@code BuildContext.tryResolveSingleRecordCarrier}'s unified walk. The compact constructor
 * enforces:
 * <ul>
 *   <li>exactly one {@link CarrierFieldRole.DataChannel};</li>
 *   <li>at most one {@link CarrierFieldRole.ErrorChannelRole};</li>
 *   <li>distinct {@link CarrierFieldRole#fieldName()} values across roles.</li>
 * </ul>
 *
 * <p>Helper accessors {@link #data()} and {@link #errorChannel()} pull the two permits used
 * pervasively by emitters; consumers that need the full closed list iterate {@link #roles}
 * directly via a sealed switch (the {@code CarrierFieldRoleCoverageTest} build-time audit pins
 * exhaustive consumer dispatch).
 */
public record SingleRecordCarrierShape(
    String carrierTypeName,
    List<CarrierFieldRole> roles
) {

    public SingleRecordCarrierShape {
        roles = List.copyOf(roles);
        long dataChannels = roles.stream().filter(r -> r instanceof CarrierFieldRole.DataChannel).count();
        if (dataChannels != 1) {
            throw new IllegalArgumentException(
                "SingleRecordCarrierShape '" + carrierTypeName
                + "' must carry exactly one DataChannel; got " + dataChannels);
        }
        long errorChannels = roles.stream().filter(r -> r instanceof CarrierFieldRole.ErrorChannelRole).count();
        if (errorChannels > 1) {
            throw new IllegalArgumentException(
                "SingleRecordCarrierShape '" + carrierTypeName
                + "' must carry at most one ErrorChannelRole; got " + errorChannels);
        }
        long distinct = roles.stream().map(CarrierFieldRole::fieldName).distinct().count();
        if (distinct != roles.size()) {
            throw new IllegalArgumentException(
                "SingleRecordCarrierShape '" + carrierTypeName
                + "' roles must have distinct field names");
        }
    }

    /** The carrier's single {@link CarrierFieldRole.DataChannel} (always present by invariant). */
    public CarrierFieldRole.DataChannel data() {
        for (var role : roles) {
            if (role instanceof CarrierFieldRole.DataChannel d) return d;
        }
        throw new IllegalStateException("invariant violated: no DataChannel on '" + carrierTypeName + "'");
    }

    /** The carrier's optional {@link CarrierFieldRole.ErrorChannelRole}. */
    public Optional<CarrierFieldRole.ErrorChannelRole> errorChannel() {
        for (var role : roles) {
            if (role instanceof CarrierFieldRole.ErrorChannelRole e) return Optional.of(e);
        }
        return Optional.empty();
    }
}
