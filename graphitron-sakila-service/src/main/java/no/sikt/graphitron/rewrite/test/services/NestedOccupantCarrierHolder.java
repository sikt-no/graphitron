package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.AddressRecord;

/**
 * Compilation-tier fixture: the photo-negative of {@link AddressOccupantCarrier}. Where that
 * carrier is deliberately <em>top-level</em> (so its binary name has no {@code $} segment), this
 * carrier is a <em>nested</em> record ({@code Carrier} enclosed in this holder), so its binary name
 * is {@code NestedOccupantCarrierHolder$Carrier}.
 *
 * <p>Returned by {@link NestedOccupantCarrierService#byId}, so the SDL {@code NestedOccupantCarrier}
 * type binds as a {@code PojoResultType} whose {@code fqClassName} carries the {@code $}-qualified
 * binary name. The single-cardinality polymorphic {@code firstOccupant} child derives its hub from
 * the {@code address()} accessor via {@code derivePolymorphicHubSource} (producer #2), which builds
 * the {@code AccessorRef}'s parent-backing {@code ClassName}. Previously that {@code ClassName} was
 * built with {@code ClassName.bestGuess(binaryName)}, which does not split on {@code $}, so the emitted cast
 * spelled {@code ((pkg.NestedOccupantCarrierHolder$Carrier) env.getSource()).address()} and failed
 * {@code javac}. The boundary now resolves the enclosing structure via
 * {@code ClassName.get(Class)}, so the cast spells {@code NestedOccupantCarrierHolder.Carrier} and
 * compiles.
 */
public final class NestedOccupantCarrierHolder {

    private NestedOccupantCarrierHolder() {}

    /** Nested record carrier holding the hub {@link AddressRecord}; binary name has a {@code $}. */
    public record Carrier(AddressRecord address) {}
}
