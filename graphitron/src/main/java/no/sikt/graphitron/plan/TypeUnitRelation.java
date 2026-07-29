package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.TypeUnitCommand;
import no.sikt.graphitron.command.UnitRef;

import java.util.HashSet;
import java.util.List;

/**
 * The type-keyed command relation: every per-type unit this run emits, one row per
 * {@code (typeName, arm)} key (enforced here; two rows of one kind for one type is a producer
 * bug, not a merge). Consumers read through the narrowing accessors so no renderer takes the
 * sealed root and downcasts; the shell folds each kind's rows through its family's renderer and
 * lands the units at the committed refs, where the write step's two-directional unit-set check
 * is the per-family enforcer.
 */
public record TypeUnitRelation(List<TypeUnitCommand> rows) {

    public TypeUnitRelation {
        rows = List.copyOf(rows);
        var seen = new HashSet<String>();
        for (var row : rows) {
            if (!seen.add(row.getClass().getSimpleName() + ":" + row.typeName())) {
                throw new IllegalArgumentException(
                    "the type-unit relation is keyed by (typeName, arm); duplicate row for type '"
                    + row.typeName() + "' and kind " + row.getClass().getSimpleName());
            }
        }
    }

    /** The input-record rows, in producer order (sorted by type name for deterministic output). */
    public List<TypeUnitCommand.InputRecordUnit> inputRecords() {
        return rows.stream()
            .filter(r -> r instanceof TypeUnitCommand.InputRecordUnit)
            .map(r -> (TypeUnitCommand.InputRecordUnit) r)
            .toList();
    }

    /** The input-record rows' committed refs, the write step's expected unit set. */
    public List<UnitRef> inputRecordUnits() {
        return inputRecords().stream().map(TypeUnitCommand.InputRecordUnit::unit).toList();
    }

    /** The plain fetchers rows, in producer order. */
    public List<TypeUnitCommand.FetchersUnit> fetchers() {
        return rows.stream()
            .filter(r -> r instanceof TypeUnitCommand.FetchersUnit)
            .map(r -> (TypeUnitCommand.FetchersUnit) r)
            .toList();
    }

    /** The connection fetchers-pair rows, in producer order. */
    public List<TypeUnitCommand.ConnectionFetchersUnit> connectionFetchers() {
        return rows.stream()
            .filter(r -> r instanceof TypeUnitCommand.ConnectionFetchersUnit)
            .map(r -> (TypeUnitCommand.ConnectionFetchersUnit) r)
            .toList();
    }

    /**
     * The fetchers family's committed refs (plain rows plus both refs of every connection
     * pair), the write step's expected unit set for the one fetchers fold.
     */
    public List<UnitRef> fetchersUnits() {
        var refs = new java.util.ArrayList<UnitRef>();
        fetchers().forEach(r -> refs.add(r.unit()));
        connectionFetchers().forEach(r -> {
            refs.add(r.connection());
            refs.add(r.edge());
        });
        return List.copyOf(refs);
    }
}
