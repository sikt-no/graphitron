package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.GlueCall;
import no.sikt.graphitron.command.NullCheck;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.Presence;

import java.util.function.Function;

/**
 * Turns a field's {@link Presence.NullContract} into the {@link NullCheck} its single-record
 * launcher renders: an always-object contract into {@link NullCheck.None}, a collapsing one into
 * {@link NullCheck.Collapses} over its column leaves and the probe {@link PresenceProbes} mints.
 */
final class NullChecks {

    private NullChecks() {}

    /**
     * The check of {@code field} under {@code contract}. {@code glueOf} answers a probe leaf's
     * condition glue call, as for {@link PresenceProbes#of}.
     *
     * @throws IllegalStateException for a collapsing contract with no leaves: every field of a
     *         known type is a leaf or blocks, and a type has at least one field, so a leafless
     *         contract is a generator bug, never "always an object"
     */
    static NullCheck of(GraphitronField field, Presence.NullContract contract,
            Function<Presence.PresenceLeaf.Probe, GlueCall> glueOf) {
        return switch (contract) {
            case Presence.NullContract.AlwaysObject ignored -> new NullCheck.None();
            case Presence.NullContract.CollapsesWhenAbsent c -> {
                if (c.leaves().isEmpty()) {
                    throw new IllegalStateException(
                        "Graphitron generator bug (null contract): '" + field.parentTypeName() + "."
                        + field.name() + "' collapses over no presence leaves; its return type's"
                        + " fields were not handed to the presence fold");
                }
                yield new NullCheck.Collapses(
                    c.columnLeaves().stream().map(Presence.PresenceLeaf.Columns::columns).toList(),
                    PresenceProbes.of(field, c, glueOf));
            }
        };
    }
}
