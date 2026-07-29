package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.command.LaunchSource.DiscriminatedTable.BaseSliceTerm;
import no.sikt.graphitron.command.LaunchSource.DiscriminatedTable.DetailField;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.ParticipantRef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The joined-table participants' field-residence split for one single-table discriminated
 * interface, folded once from the participants' classified fields: which base-resident terms the
 * discriminated query's SELECT list carries (and under which reserved aliases), and which
 * detail-exclusive columns each participant projects behind its gated LEFT JOIN. A post-walk
 * fold (the participants' {@link ChildField} variants must exist, so no walk-time site can
 * compute it) with one formula and five consumers: the launcher producer copies it onto the
 * {@link no.sikt.graphitron.command.LaunchSource.DiscriminatedTable} arm, and the legacy
 * interface-reprojection call sites (the child twin, the service single-table-interface fetcher,
 * the two DML discriminated follow-ups) read it through
 * {@link GraphitronSchema#joinedTableReprojectionOf}.
 *
 * <p>{@link #baseSlice} is a whole-query fact, not a per-participant one: the terms are ordered
 * by participant declaration order then schema field order, and deduplicated first-wins across
 * both term kinds and all participants (one alias namespace), because SELECT-list position and
 * dedup are properties of the one query the interface coordinate runs.
 *
 * <p>{@link #detailFieldsByParticipant} is keyed by participant type name; each list is that
 * participant's detail-exclusive columns in schema field order, never deduplicated across
 * participants (each projects against its own detail alias).
 *
 * <p>{@link #deferrals} carries the shapes that classify but have no reprojection emission: a
 * non-{@link CallSiteCompaction.Direct} (e.g. {@code @nodeId}-encoded) column carrier on a
 * joined-table participant, which the retired assembly silently truncated to its first column.
 * The validator drains these as deferred errors, so the truncation is unreachable.
 */
public record JoinedTableReprojection(
    List<BaseSliceTerm> baseSlice,
    Map<String, List<DetailField>> detailFieldsByParticipant,
    List<Deferral> deferrals
) {

    /** The one instance every non-interface (or schema-free) lookup folds to. */
    public static final JoinedTableReprojection EMPTY =
        new JoinedTableReprojection(List.of(), Map.of(), List.of());

    /** A classified shape with no reprojection emission; drained by the validator as deferred. */
    public record Deferral(String typeName, String fieldName, String message) {}

    public JoinedTableReprojection {
        baseSlice = List.copyOf(baseSlice);
        detailFieldsByParticipant = Map.copyOf(detailFieldsByParticipant);
        deferrals = List.copyOf(deferrals);
    }

    /** This participant's detail-exclusive columns, or an empty list. */
    public List<DetailField> detailFieldsOf(String participantTypeName) {
        return detailFieldsByParticipant.getOrDefault(participantTypeName, List.of());
    }

    /**
     * The fold: reads the interface type's participant set and each joined-table participant's
     * classified fields. {@link #EMPTY} when {@code typeName} does not name a
     * {@link GraphitronType.TableInterfaceType} in {@code schema} (including every schema-free
     * caller, whose hand-built registries carry no participant field classifications either).
     */
    static JoinedTableReprojection of(GraphitronSchema schema, String typeName) {
        if (!(schema.type(typeName) instanceof GraphitronType.TableInterfaceType iface)) {
            return EMPTY;
        }
        var baseSlice = new ArrayList<BaseSliceTerm>();
        var detailFields = new LinkedHashMap<String, List<DetailField>>();
        var deferrals = new ArrayList<Deferral>();
        var seenAliases = new HashSet<String>();
        for (var participant : iface.participants()) {
            if (!(participant instanceof ParticipantRef.JoinedTableBound jtb)) {
                continue;
            }
            var details = new ArrayList<DetailField>();
            for (var f : schema.fieldsOf(jtb.typeName())) {
                if (f instanceof ChildField.ColumnBackedReferenceField crf) {
                    if (!(crf.compaction() instanceof CallSiteCompaction.Direct)) {
                        deferrals.add(deferral(jtb.typeName(), crf.name()));
                    } else if (seenAliases.add(crf.name())) {
                        baseSlice.add(new BaseSliceTerm.InheritedRef(crf.name(), crf.columns().get(0)));
                    }
                } else if (f instanceof ChildField.ColumnBackedField cf) {
                    if (!(cf.compaction() instanceof CallSiteCompaction.Direct)) {
                        deferrals.add(deferral(jtb.typeName(), cf.name()));
                        continue;
                    }
                    var column = cf.columns().get(0);
                    var baseColumn = sharedKeyBaseColumn(jtb, column);
                    if (baseColumn != null) {
                        if (seenAliases.add(column.sqlName())) {
                            baseSlice.add(new BaseSliceTerm.SharedKey(baseColumn, column.sqlName()));
                        }
                    } else {
                        details.add(new DetailField(cf.name(), column));
                    }
                }
            }
            if (!details.isEmpty()) {
                detailFields.put(jtb.typeName(), details);
            }
        }
        return new JoinedTableReprojection(baseSlice, detailFields, deferrals);
    }

    private static Deferral deferral(String typeName, String fieldName) {
        return new Deferral(typeName, fieldName,
            "Field '" + typeName + "." + fieldName + "': a @nodeId (or otherwise non-directly"
            + " projected) column carrier on a joined-table interface participant is not yet"
            + " supported in the discriminated re-projection; expose the raw column or move the"
            + " @nodeId surface off the participant");
    }

    /**
     * For a participant column that is a child-to-parent hop column (a shared-key column present
     * on both base and detail), the paired base-side column (the hop slot's target side);
     * {@code null} when the column is detail-exclusive. The base side may differ in name from
     * the detail side, so the base column projects aliased to the detail column's SQL name.
     */
    private static ColumnRef sharedKeyBaseColumn(ParticipantRef.JoinedTableBound jtb, ColumnRef detailColumn) {
        for (var slot : jtb.childToParentPairs().slots()) {
            if (slot.sourceSide().sqlName().equalsIgnoreCase(detailColumn.sqlName())) {
                return slot.targetSide();
            }
        }
        return null;
    }
}
