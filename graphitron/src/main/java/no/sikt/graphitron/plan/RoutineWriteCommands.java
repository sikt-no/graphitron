package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.Arity;
import no.sikt.graphitron.command.ErrorDispatch;
import no.sikt.graphitron.command.RoutineWriteCommand;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.TableExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Produces the routine-write command relation ({@link RoutineWriteRelation}): one row per
 * {@code @routine}-writing mutation coordinate, carrying what the coordinate's fetcher entry
 * point emits. Membership has one home, the total switch below: the two routine-write leaves mint
 * a row and every other mutation shape is outside the relation by the fact, with no default arm,
 * so a new mutation leaf is a compile-time decision here rather than a silent non-member.
 *
 * <p>The two arms map one-to-one onto the two leaves, so this producer decides nothing the
 * classifier has not already decided; what it adds is the naming vocabulary (the entry point's
 * own address, the terminus projection unit, the units the {@code catch} arm calls) and the
 * restatement of the error channel as the arm the catch emits. That restatement is the only
 * translation step: a channel is a classification fact carrying the resolved {@code @error} types
 * it was built from, and all a catch arm emits from it is the mappings constant's name.
 */
public final class RoutineWriteCommands {

    private RoutineWriteCommands() {}

    public static RoutineWriteRelation produce(GraphitronSchema schema, String outputPackage) {
        var units = new GeneratedUnits(outputPackage);
        var rows = new ArrayList<RoutineWriteCommand>();
        for (var type : schema.types().values()) {
            for (var field : schema.fieldsOf(type.name())) {
                if (field instanceof MutationField mf) {
                    var row = rowOf(mf, units);
                    if (row != null) {
                        rows.add(row);
                    }
                }
            }
        }
        return new RoutineWriteRelation(rows);
    }

    /**
     * The mutation family's membership-and-production switch, total with no default. Only the two
     * routine-write leaves are members: every other mutation shape writes through DML or delegates
     * to a developer service, and neither emits a routine call.
     */
    private static RoutineWriteCommand rowOf(MutationField field, GeneratedUnits units) {
        return switch (field) {
            case MutationField.MutationRoutineWriteField f -> new RoutineWriteCommand.ChainReread(
                units.fetcherEntryMethod(f.parentTypeName(), f.name()),
                FieldCoordinates.coordinates(f.parentTypeName(), f.name()),
                f.chain(),
                units.typeClass(f.returnType().returnTypeName()),
                f.returnType().wrapper().isList() ? Arity.LIST : Arity.SINGLE,
                // The leaf's channel is structurally absent (a chain-re-reading routine write
                // carries no payload carrier to route into), so the disposition is the router's
                // privacy arm. Derived from the leaf's own slot rather than hard-coded, so a
                // channel appearing there surfaces as a rejected translation, not a dropped fact.
                dispatchFor(f.errorChannel(), units));
            case MutationField.MutationRoutineWriteRecordField f -> new RoutineWriteCommand.CarrierKeys(
                units.fetcherEntryMethod(f.parentTypeName(), f.name()),
                FieldCoordinates.coordinates(f.parentTypeName(), f.name()),
                new TableExpr.RoutineCall(f.routine(), f.routineResultTable()),
                f.capturedPairs(),
                f.targetTable(),
                // The payload data field's SDL wrapper, the only cardinality claim for this shape:
                // jOOQ types every table-valued function as a Table<R>, so the catalog carries no
                // per-call cardinality fact.
                f.dataFieldArrival() == no.sikt.graphitron.rewrite.model.Arity.MANY
                    ? Arity.LIST : Arity.SINGLE,
                dispatchFor(f.errorChannel(), units));
            case MutationField.DmlTableField ignored -> null;
            case MutationField.MutationServiceTableField ignored -> null;
            case MutationField.MutationServiceRecordField ignored -> null;
            case MutationField.MutationServicePolymorphicField ignored -> null;
            case MutationField.MutationServiceTableInterfaceField ignored -> null;
            case MutationField.MutationDmlRecordField ignored -> null;
            case MutationField.MutationBulkDmlRecordField ignored -> null;
        };
    }

    /**
     * The channel restated as what the {@code catch} arm emits. An absent channel is the router's
     * privacy disposition; a {@link ErrorChannel.LocalContext} channel hands the matched throwable
     * back as graphql-java {@code localContext}, and its mappings constant is all the arm needs.
     * The remaining channel arm is unreachable on these two leaves by classification: a routine
     * write's carrier is a directiveless structural payload, which has no developer payload class
     * for the mapped arm to instantiate.
     */
    private static ErrorDispatch dispatchFor(Optional<? extends ErrorChannel> channel, GeneratedUnits units) {
        if (channel.isEmpty()) {
            return new ErrorDispatch.Redacting(units.errorRouter());
        }
        if (channel.get() instanceof ErrorChannel.LocalContext lc) {
            return new ErrorDispatch.LocalContextRouted(units.errorRouter(), units.errorMappings(),
                lc.mappingsConstantName());
        }
        throw new IllegalStateException(
            "a routine-write coordinate carries an error channel this relation cannot state: "
            + channel.get().getClass().getSimpleName() + "; the routine-write carrier is a"
            + " directiveless structural payload, so the classifier produces only the"
            + " localContext-routed channel here");
    }

    /**
     * The relation over a bare field set, for the fetcher generator's nesting-reached fallback,
     * which holds fields but no schema. Mirrors the launcher producer's overload at the same call
     * site: membership stays here rather than the generator asserting that a nesting-reached
     * type's children hold no mutation root field.
     */
    public static RoutineWriteRelation produceWithoutSchema(List<? extends GraphitronField> fields,
            String outputPackage) {
        var units = new GeneratedUnits(outputPackage);
        var rows = new ArrayList<RoutineWriteCommand>();
        for (var field : fields) {
            if (field instanceof MutationField mf) {
                var row = rowOf(mf, units);
                if (row != null) {
                    rows.add(row);
                }
            }
        }
        return new RoutineWriteRelation(rows);
    }
}
