package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.GlueCall;
import no.sikt.graphitron.command.PresenceProbe;
import no.sikt.graphitron.command.ReachPath;
import no.sikt.graphitron.command.ReservedAliases;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.Presence;

import java.util.Optional;
import java.util.function.Function;

/**
 * Mints a field's {@link PresenceProbe} from its {@link Presence.NullContract}: one probe term per
 * {@link Presence.PresenceLeaf.Probe} leaf, its reach narrowed from the leaf's join path and its
 * filter the coordinate's own condition glue call. Shared by the projection producer (the
 * nesting, same-row and inline multiset carriers) and the launcher producer (the batched rows
 * method's source), which resolve glue calls differently but must mint the same probe.
 */
final class PresenceProbes {

    private PresenceProbes() {}

    /**
     * The probe of {@code field} under {@code contract}, or empty when the contract has no probe
     * leaves. {@code glueOf} answers a probe leaf's condition glue call: the probed field's
     * coordinate method over the leaf's filters, asked only when it has some.
     */
    static Optional<PresenceProbe> of(ChildField field, Presence.NullContract contract,
            Function<Presence.PresenceLeaf.Probe, GlueCall> glueOf) {
        var probes = contract.probes();
        if (probes.isEmpty()) {
            return Optional.empty();
        }
        var terms = probes.stream()
            .map(p -> new PresenceProbe.ProbeTerm(
                ReachPath.narrow(p.joinPath(), "the presence probe of " + p.field().qualifiedName()),
                p.filters().isEmpty() ? null : glueOf.apply(p),
                p.targetColumns()))
            .toList();
        var columnLeaves = contract.columnLeaves().stream()
            .map(Presence.PresenceLeaf.Columns::columns)
            .toList();
        return Optional.of(new PresenceProbe(
            ReservedAliases.presenceProbe(field.parentTypeName(), field.name()), columnLeaves, terms));
    }
}
