package no.sikt.graphitron.model.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import org.jooq.DSLContext;

import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_REFERENCE_FOR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_REFERENCE_FOR;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_PARTICIPANT_SCOPE_TABLE;
import static no.sikt.graphitron.model.Tables.INTENT_INPUT_OCCURRENCE_PATH;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_DOMAIN;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;

/**
 * The {@code @referenceFor} {@code type:} spelling checked against every consumer's participant set,
 * for the two coordinates whose consumer is not the coordinate itself.
 *
 * <p>At an output field the check is local and already made where the routes resolve: the field's own
 * named type holds the participants, so a spelling naming none of them is one field's error and
 * {@code FieldBuilder} rejects it there. At a {@code @nodeId} filter <em>input field</em> it is not.
 * The application is keyed by the input type and its field, while which participants exist is a fact
 * of each consuming query, and one input type may be consumed by two queries whose participant sets
 * differ. So validity is two-layered, and only the upper layer can live here:
 *
 * <ul>
 *   <li><b>Per use site</b>, in the classifier: an application whose {@code type:} names a
 *       participant of that consumer's return type selects that participant's route, and one naming
 *       no participant there is <em>inert</em>, exactly as an unnamed participant keeps automatic
 *       discovery. Rejecting per use site would key validity two grains finer than the fact, and its
 *       only remedy would be forking an input type so two SDL sites hand-maintain duplicate path
 *       expressions.</li>
 *   <li><b>Whole-schema</b>, here: an application whose {@code type:} names no participant at
 *       <em>any</em> consumer of the input type is a typo, and inertness alone would swallow it
 *       silently at every site. This is the one altitude that sees every consumer at once.</li>
 * </ul>
 *
 * <p>The argument coordinate rides the same class for the same vocabulary rather than the same
 * reason: an argument's consumer <em>is</em> the field it sits on, so its check is as local as the
 * output field's. It is here because the remedy prose and the two arms below are the same ones, and
 * two sites spelling them would be the first step towards two answers.
 *
 * <p>Both populations are narrowed to what the generator intends to classify, on
 * {@link NodeIdDecodeDefects}'s terms: the input-field population to applications whose input type
 * has at least one occurrence under some use site, the argument population to a coordinate whose
 * owning type is in the classification domain. An input type nothing consumes fails no build, which
 * is what keeps this from reporting on SDL the generator never reads.
 *
 * <p>Output-field applications are excluded structurally rather than by a kind test: an occurrence
 * path's leaf is always an input object type, so an application on an object or interface field
 * matches no occurrence and never enters the population.
 */
public final class ReferenceForParticipantDefects {

    private ReferenceForParticipantDefects() {}

    /**
     * The detection pass's typed product: one entry per application whose participant spelling
     * matched nothing anywhere. {@link #violations()} is the error stream; the entries stay beside it
     * so a consumer wanting the coordinates has them without re-parsing a message.
     */
    public record Detection(List<Defect> defects) {

        public Detection {
            defects = List.copyOf(defects);
        }

        /** The empty detection, for callers running capture without the detection pass. */
        public static Detection empty() {
            return new Detection(List.of());
        }

        /** Every violation the detection minted, in coordinate order. */
        public List<ValidationError> violations() {
            return defects.stream()
                .map(d -> ValidationError.forField(d.coordinate(), d.rejection(), d.location()))
                .toList();
        }
    }

    /**
     * One unmatched application: the coordinate the error attaches to, the participant name the
     * author wrote, and the typed rejection. The spelling rides along because a consumer grouping
     * refusals by the name that matched nothing would otherwise recover it from prose.
     */
    public record Defect(String coordinate, String participantTypeRef,
                         Rejection rejection, SourceLocation location) {}

    /**
     * Projects every unmatched {@code @referenceFor} application over {@code graphName}'s emitted
     * partition. Empty for a graph whose every application names a participant some consumer has.
     */
    public static Detection detect(DSLContext dsl, String graphName) {
        var defects = new java.util.ArrayList<Defect>();
        defects.addAll(inputFieldDefects(dsl, graphName));
        defects.addAll(argumentDefects(dsl, graphName));
        return new Detection(defects);
    }

    /**
     * The input-field arm: the application's input type is consumed somewhere, and no consumer of it
     * has a participant by that name.
     */
    private static List<Defect> inputFieldDefects(DSLContext dsl, String graphName) {
        var r = GRAPHITRON_REFERENCE_FOR;
        var o = INTENT_INPUT_OCCURRENCE_PATH;
        var ps = INTENT_FIELD_PARTICIPANT_SCOPE_TABLE;
        return dsl.selectFrom(r)
            .where(r.GRAPH_NAME.eq(graphName),
                // Consumed somewhere: an input type nothing reads is outside what the build classifies.
                exists(selectOne().from(o)
                    .where(o.GRAPH_NAME.eq(graphName), o.LEAF_NAMED_TYPE.eq(r.TYPE_NAME))),
                // And no consumer of it holds a participant by this name.
                notExists(selectOne().from(o)
                    .join(ps).on(ps.GRAPH_NAME.eq(o.GRAPH_NAME),
                                 ps.TYPE_NAME.eq(o.ROOT_TYPE_NAME),
                                 ps.FIELD_NAME.eq(o.ROOT_FIELD_NAME),
                                 ps.MEMBER_TYPE_NAME.eq(r.PARTICIPANT_TYPE_REF))
                    .where(o.GRAPH_NAME.eq(graphName), o.LEAF_NAMED_TYPE.eq(r.TYPE_NAME))))
            .orderBy(r.TYPE_NAME, r.FIELD_NAME, r.ORDINAL)
            .fetch(row -> {
                String coordinate = row.getTypeName() + "." + row.getFieldName();
                var consumers = consumersOf(dsl, graphName, row.getTypeName());
                return new Defect(coordinate, row.getParticipantTypeRef(),
                    inputFieldRejection(row.getParticipantTypeRef(), consumers),
                    location(row.getSourceName(), row.getSourceLine(), row.getSourceColumn()));
            });
    }

    /**
     * The argument arm: the argument's own field returns no polymorphic type with a participant by
     * this name. Narrowed to the classification domain the way the sibling {@code @nodeId} families
     * are, the refused coordinate's owning type carrying the field.
     */
    private static List<Defect> argumentDefects(DSLContext dsl, String graphName) {
        var a = GRAPHITRON_ARGUMENT_REFERENCE_FOR;
        var ps = INTENT_FIELD_PARTICIPANT_SCOPE_TABLE;
        var d = INTENT_TYPE_DOMAIN;
        return dsl.selectFrom(a)
            .where(a.GRAPH_NAME.eq(graphName),
                exists(selectOne().from(d)
                    .where(d.GRAPH_NAME.eq(graphName), d.TYPE_NAME.eq(a.TYPE_NAME))),
                notExists(selectOne().from(ps)
                    .where(ps.GRAPH_NAME.eq(graphName),
                           ps.TYPE_NAME.eq(a.TYPE_NAME),
                           ps.FIELD_NAME.eq(a.FIELD_NAME),
                           ps.MEMBER_TYPE_NAME.eq(a.PARTICIPANT_TYPE_REF))))
            .orderBy(a.TYPE_NAME, a.FIELD_NAME, a.ARGUMENT_NAME, a.ORDINAL)
            .fetch(row -> {
                String coordinate = row.getTypeName() + "." + row.getFieldName();
                var participants = participantsOf(dsl, graphName, row.getTypeName(), row.getFieldName());
                return new Defect(coordinate, row.getParticipantTypeRef(),
                    argumentRejection(row.getArgumentName(), row.getParticipantTypeRef(), participants),
                    location(row.getSourceName(), row.getSourceLine(), row.getSourceColumn()));
            });
    }

    /** One consuming coordinate of an input type, with the participant names it offers. */
    private record Consumer(String coordinate, List<String> participants) {}

    /**
     * Every use site the input type occurs under, each with its own participant names. Distinct on
     * the consuming field rather than on the occurrence path: two paths reaching one input type under
     * one query are one consumer offering one participant set, and naming the field twice would tell
     * the author about their input nesting rather than about their participants.
     */
    private static List<Consumer> consumersOf(DSLContext dsl, String graphName, String inputTypeName) {
        var o = INTENT_INPUT_OCCURRENCE_PATH;
        return dsl.selectDistinct(o.ROOT_TYPE_NAME, o.ROOT_FIELD_NAME)
            .from(o)
            .where(o.GRAPH_NAME.eq(graphName), o.LEAF_NAMED_TYPE.eq(inputTypeName))
            .orderBy(o.ROOT_TYPE_NAME, o.ROOT_FIELD_NAME)
            .fetch(row -> new Consumer(row.value1() + "." + row.value2(),
                participantsOf(dsl, graphName, row.value1(), row.value2())));
    }

    /** The table-bound participant names of one field's polymorphic return type, in container order. */
    private static List<String> participantsOf(DSLContext dsl, String graphName,
                                               String typeName, String fieldName) {
        var ps = INTENT_FIELD_PARTICIPANT_SCOPE_TABLE;
        return dsl.selectDistinct(ps.MEMBER_TYPE_NAME)
            .from(ps)
            .where(ps.GRAPH_NAME.eq(graphName), ps.TYPE_NAME.eq(typeName), ps.FIELD_NAME.eq(fieldName))
            .orderBy(ps.MEMBER_TYPE_NAME)
            .fetch(org.jooq.Record1::value1);
    }

    /**
     * Two arms, on whether any consumer has participants at all. An author whose consumers are all
     * single-table has not mistyped a participant name; they have used the wrong directive, and being
     * shown an empty list of valid names would not say so.
     */
    private static Rejection inputFieldRejection(String participantTypeRef,
                                                 List<Consumer> consumers) {
        boolean anyPolymorphic = consumers.stream().anyMatch(c -> !c.participants().isEmpty());
        if (!anyPolymorphic) {
            return Rejection.structural(
                "@referenceFor names participant '"
                + participantTypeRef + "', but every query consuming this input type returns a single"
                + " table, so there is no participant set for the name to be in ("
                + consumers.stream().map(Consumer::coordinate).collect(Collectors.joining(", "))
                + "). A single stated path is correct at a single-table consumer; use @reference.");
        }
        return Rejection.structural(
            "@referenceFor names participant '"
            + participantTypeRef + "', which is not a table-bound participant at any query consuming"
            + " this input type. Consumers and their participants: "
            + consumers.stream()
                .map(c -> c.coordinate() + " (" + (c.participants().isEmpty()
                    ? "single-table" : String.join(", ", c.participants())) + ")")
                .collect(Collectors.joining("; "))
            + ". An application naming a participant of one consumer and not another is fine and is"
            + " inert where it does not apply; this one applies nowhere.");
    }

    /** The argument arm's prose; one consumer, so one participant list. */
    private static Rejection argumentRejection(String argumentName, String participantTypeRef,
                                               List<String> participants) {
        if (participants.isEmpty()) {
            return Rejection.structural(
                "argument '" + argumentName + "': @referenceFor names participant '"
                + participantTypeRef + "', but this field returns a single table, so there is no"
                + " participant set for the name to be in. Use @reference.");
        }
        return Rejection.structural(
            "argument '" + argumentName + "': @referenceFor names '" + participantTypeRef
            + "', which is not a table-bound participant of the field's return type. Valid"
            + " participant names: " + String.join(", ", participants) + ".");
    }

    /** The store's position columns as a graphql-java location; {@code null} when unpositioned. */
    private static SourceLocation location(String sourceName, Integer line, Integer column) {
        if (line == null || column == null) {
            return null;
        }
        return new SourceLocation(line, column, sourceName);
    }
}
