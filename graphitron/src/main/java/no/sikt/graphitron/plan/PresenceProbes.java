package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.GlueCall;
import no.sikt.graphitron.command.PresenceProbe;
import no.sikt.graphitron.command.ReachPath;
import no.sikt.graphitron.command.ReservedAliases;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.Presence;

import java.util.Optional;
import java.util.function.Function;

/**
 * Mints a field's {@link PresenceProbe} from its {@link Presence.NullContract}: one probe term per
 * {@link Presence.PresenceLeaf.Probed} leaf. A {@link Presence.PresenceLeaf.Probe} becomes an
 * existence test, its reach narrowed from the leaf's join path and its filter the coordinate's own
 * condition glue call; a {@link Presence.PresenceLeaf.Computed} becomes a null test of the
 * field's {@code @externalField} expression. Shared by the projection producer (the
 * nesting, same-row and inline multiset carriers) and the launcher producer (the batched rows
 * method's source and the root table read's {@link no.sikt.graphitron.command.NullCheck}), which
 * resolve glue calls differently but must mint the same probe.
 */
final class PresenceProbes {

    private PresenceProbes() {}

    /**
     * The probe of {@code field} under {@code contract}, or empty when the contract has no
     * {@link Presence.NullContract#probed probed} leaves. {@code glueOf} answers a probe leaf's
     * condition glue call: the probed field's coordinate method over the leaf's filters, asked
     * only when it has some.
     */
    static Optional<PresenceProbe> of(GraphitronField field, Presence.NullContract contract,
            Function<Presence.PresenceLeaf.Probe, GlueCall> glueOf) {
        var probed = contract.probed();
        if (probed.isEmpty()) {
            return Optional.empty();
        }
        var terms = probed.stream()
            .map(leaf -> switch (leaf) {
                case Presence.PresenceLeaf.Probe p -> (PresenceProbe.ProbeTerm) new PresenceProbe.ProbeTerm.Exists(
                    ReachPath.narrow(p.joinPath(), "the presence probe of " + p.field().qualifiedName()),
                    p.filters().isEmpty() ? null : glueOf.apply(p),
                    p.targetColumns());
                case Presence.PresenceLeaf.Computed c -> new PresenceProbe.ProbeTerm.NotNull(c.field().method());
            })
            .toList();
        var columnLeaves = contract.columnLeaves().stream()
            .map(Presence.PresenceLeaf.Columns::columns)
            .toList();
        return Optional.of(new PresenceProbe(
            ReservedAliases.presenceProbe(field.parentTypeName(), field.name()), columnLeaves, terms));
    }
}
