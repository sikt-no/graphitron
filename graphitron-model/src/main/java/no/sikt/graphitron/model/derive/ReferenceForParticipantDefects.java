package no.sikt.graphitron.model.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import org.jooq.DSLContext;
import org.jooq.Table;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_REFERENCE_FOR_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_POLY_MEMBER;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_ELEMENT;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_DOMAIN;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.inline;
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
 * <p>Both populations are narrowed to what the generator intends to classify: the input-field
 * population to applications whose input field has at least one occurrence, the argument population
 * to a coordinate whose owning type is in the classification domain. An input type nothing consumes
 * fails no build, which is what keeps this from reporting on SDL the generator never reads.
 *
 * <p>Output-field applications are excluded structurally: the input-field arm reads the
 * {@code INPUT_FIELD} occurrences of the application's own field, so an application on an object or
 * interface field matches none and never enters the population.
 *
 * <p>Both arms read the applications as written, off {@code graphql_directive_application} and
 * {@code graphitron_ast_reference_for_entry}, and anti-join the consumers that offer the named
 * participant: an application no consumer anywhere offers its participant to is the name that
 * matched nothing anywhere. The two population gates stay with each arm.
 */
public final class ReferenceForParticipantDefects {

    private ReferenceForParticipantDefects() {}

    /** Every relation this component's statements name. */
    public static final Set<Table<?>> READS = Set.of(GRAPHITRON_FIELD_TARGET, GRAPHITRON_FIELD,
        GRAPHQL_POLY_MEMBER, GRAPHITRON_TABLETYPE, GRAPHITRON_OCCURRENCE, INTENT_TYPE_DOMAIN,
        GRAPHQL_DIRECTIVE_APPLICATION, GRAPHQL_FIELD_ELEMENT, GRAPHQL_ARGUMENT_ELEMENT,
        GRAPHITRON_AST_REFERENCE_FOR_ENTRY);

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
     * The input-field arm: the application's input field is reached somewhere, and no consumer of it
     * offers a table-bound participant by that name.
     */
    private static List<Defect> inputFieldDefects(DSLContext dsl, String graphName) {
        var ap = GRAPHQL_DIRECTIVE_APPLICATION;
        var f = GRAPHQL_FIELD_ELEMENT;
        var r = GRAPHITRON_AST_REFERENCE_FOR_ENTRY;
        var o = GRAPHITRON_OCCURRENCE;
        var reached = GRAPHITRON_OCCURRENCE.as("reached");
        var consumer = GRAPHITRON_OCCURRENCE.as("consumer");
        var cf = GRAPHITRON_FIELD;
        var ft = GRAPHITRON_FIELD_TARGET;
        var m = GRAPHQL_POLY_MEMBER;
        var bt = GRAPHITRON_TABLETYPE;
        var cb = GRAPHITRON_TABLETYPE.as("container_binding");
        return dsl.select(f.TYPE_NAME, f.FIELD_NAME, r.PARTICIPANT_TYPE_REF, ap.SOURCE_NAME,
                ap.SOURCE_LINE, ap.SOURCE_COLUMN)
            .from(ap)
            .join(f).on(f.GRAPH_NAME.eq(ap.GRAPH_NAME), f.COORDINATE.eq(ap.COORDINATE))
            .join(r).on(r.GRAPH_NAME.eq(ap.GRAPH_NAME), r.SOURCE_NAME.eq(ap.SOURCE_NAME),
                r.SOURCE_LINE.eq(ap.SOURCE_LINE), r.SOURCE_COLUMN.eq(ap.SOURCE_COLUMN))
            .where(ap.GRAPH_NAME.eq(graphName), ap.DIRECTIVE_NAME.eq("referenceFor"),
                // Reached somewhere: an input field nothing reaches is outside what the build classifies.
                exists(selectOne().from(o)
                    .where(o.GRAPH_NAME.eq(graphName), o.KIND.eq(inline("INPUT_FIELD")),
                        o.ELEMENT_COORDINATE.eq(ap.COORDINATE))),
                // And no consumer of it offers the participant it names.
                notExists(selectOne().from(reached)
                    .join(consumer).on(consumer.GRAPH_NAME.eq(reached.GRAPH_NAME),
                        consumer.COORDINATE.eq(reached.FIELD_COORDINATE))
                    .join(cf).on(cf.GRAPH_NAME.eq(consumer.GRAPH_NAME),
                        cf.COORDINATE.eq(consumer.ELEMENT_COORDINATE))
                    .join(ft).on(ft.GRAPH_NAME.eq(cf.GRAPH_NAME), ft.TYPE_NAME.eq(cf.TYPE_NAME),
                        ft.FIELD_NAME.eq(cf.FIELD_NAME))
                    .join(m).on(m.GRAPH_NAME.eq(ft.GRAPH_NAME), m.CONTAINER_NAME.eq(ft.TARGET_TYPE_NAME),
                        m.MEMBER_TYPE_NAME.eq(r.PARTICIPANT_TYPE_REF))
                    .join(bt).on(bt.GRAPH_NAME.eq(m.GRAPH_NAME), bt.TYPE_NAME.eq(m.MEMBER_TYPE_NAME))
                    .where(reached.GRAPH_NAME.eq(graphName), reached.KIND.eq(inline("INPUT_FIELD")),
                        reached.ELEMENT_COORDINATE.eq(ap.COORDINATE))
                    .andNotExists(selectOne().from(cb)
                        .where(cb.GRAPH_NAME.eq(ft.GRAPH_NAME), cb.TYPE_NAME.eq(ft.TARGET_TYPE_NAME)))))
            .orderBy(f.TYPE_NAME, f.FIELD_NAME, ap.ORDINAL)
            .fetch(row -> {
                String coordinate = row.value1() + "." + row.value2();
                var consumers = consumersOf(dsl, graphName, row.value1(), row.value2());
                return new Defect(coordinate, row.value3(),
                    inputFieldRejection(row.value3(), consumers),
                    location(row.value4(), row.value5(), row.value6()));
            });
    }

    /**
     * The argument arm: the argument's own field returns no polymorphic type with a table-bound
     * participant by this name. Narrowed to the classification domain the way the sibling
     * {@code @nodeId} families are, the refused coordinate's owning type carrying the field.
     */
    private static List<Defect> argumentDefects(DSLContext dsl, String graphName) {
        var ap = GRAPHQL_DIRECTIVE_APPLICATION;
        var g = GRAPHQL_ARGUMENT_ELEMENT;
        var r = GRAPHITRON_AST_REFERENCE_FOR_ENTRY;
        var d = INTENT_TYPE_DOMAIN;
        var ft = GRAPHITRON_FIELD_TARGET;
        var m = GRAPHQL_POLY_MEMBER;
        var bt = GRAPHITRON_TABLETYPE;
        var cb = GRAPHITRON_TABLETYPE.as("container_binding");
        return dsl.select(g.TYPE_NAME, g.FIELD_NAME, g.ARGUMENT_NAME, r.PARTICIPANT_TYPE_REF,
                ap.SOURCE_NAME, ap.SOURCE_LINE, ap.SOURCE_COLUMN)
            .from(ap)
            .join(g).on(g.GRAPH_NAME.eq(ap.GRAPH_NAME), g.COORDINATE.eq(ap.COORDINATE))
            .join(r).on(r.GRAPH_NAME.eq(ap.GRAPH_NAME), r.SOURCE_NAME.eq(ap.SOURCE_NAME),
                r.SOURCE_LINE.eq(ap.SOURCE_LINE), r.SOURCE_COLUMN.eq(ap.SOURCE_COLUMN))
            .where(ap.GRAPH_NAME.eq(graphName), ap.DIRECTIVE_NAME.eq("referenceFor"),
                exists(selectOne().from(d)
                    .where(d.GRAPH_NAME.eq(graphName), d.TYPE_NAME.eq(g.TYPE_NAME))),
                notExists(selectOne().from(ft)
                    .join(m).on(m.GRAPH_NAME.eq(ft.GRAPH_NAME), m.CONTAINER_NAME.eq(ft.TARGET_TYPE_NAME),
                        m.MEMBER_TYPE_NAME.eq(r.PARTICIPANT_TYPE_REF))
                    .join(bt).on(bt.GRAPH_NAME.eq(m.GRAPH_NAME), bt.TYPE_NAME.eq(m.MEMBER_TYPE_NAME))
                    .where(ft.GRAPH_NAME.eq(graphName), ft.TYPE_NAME.eq(g.TYPE_NAME),
                        ft.FIELD_NAME.eq(g.FIELD_NAME))
                    .andNotExists(selectOne().from(cb)
                        .where(cb.GRAPH_NAME.eq(ft.GRAPH_NAME), cb.TYPE_NAME.eq(ft.TARGET_TYPE_NAME)))))
            .orderBy(g.TYPE_NAME, g.FIELD_NAME, g.ARGUMENT_NAME, ap.ORDINAL)
            .fetch(row -> {
                String coordinate = row.value1() + "." + row.value2();
                var participants = participantsOf(dsl, graphName, row.value1(), row.value2());
                return new Defect(coordinate, row.value4(),
                    argumentRejection(row.value3(), row.value4(), participants),
                    location(row.value5(), row.value6(), row.value7()));
            });
    }

    /** One consuming coordinate of an input type, with the participant names it offers. */
    private record Consumer(String coordinate, List<String> participants) {}

    /**
     * Every declared field the input field is reached under, each with its own participant names.
     * Distinct on the declared field rather than on the occurrence: two occurrences of one input field
     * under one query are one consumer offering one participant set, and naming the field twice would
     * tell the author about their input nesting rather than about their participants.
     */
    private static List<Consumer> consumersOf(DSLContext dsl, String graphName, String inputTypeName,
                                              String inputFieldName) {
        var o = GRAPHITRON_OCCURRENCE;
        var fo = GRAPHITRON_OCCURRENCE.as("fo");
        var cf = GRAPHITRON_FIELD;
        return dsl.selectDistinct(cf.TYPE_NAME, cf.FIELD_NAME)
            .from(o)
            .join(fo).on(fo.GRAPH_NAME.eq(o.GRAPH_NAME), fo.COORDINATE.eq(o.FIELD_COORDINATE))
            .join(cf).on(cf.GRAPH_NAME.eq(fo.GRAPH_NAME), cf.COORDINATE.eq(fo.ELEMENT_COORDINATE))
            .where(o.GRAPH_NAME.eq(graphName), o.KIND.eq(inline("INPUT_FIELD")),
                o.ELEMENT_COORDINATE.eq(inputTypeName + "." + inputFieldName))
            .orderBy(cf.TYPE_NAME, cf.FIELD_NAME)
            .fetch(row -> new Consumer(row.value1() + "." + row.value2(),
                participantsOf(dsl, graphName, row.value1(), row.value2())));
    }

    /** The table-bound participant names of one field's polymorphic return type, in container order. */
    private static List<String> participantsOf(DSLContext dsl, String graphName,
                                               String typeName, String fieldName) {
        var ft = GRAPHITRON_FIELD_TARGET;
        var m = GRAPHQL_POLY_MEMBER;
        var bt = GRAPHITRON_TABLETYPE;
        var cb = GRAPHITRON_TABLETYPE.as("cb");
        return dsl.selectDistinct(m.MEMBER_TYPE_NAME)
            .from(ft)
            .join(m).on(m.GRAPH_NAME.eq(ft.GRAPH_NAME), m.CONTAINER_NAME.eq(ft.TARGET_TYPE_NAME))
            .join(bt).on(bt.GRAPH_NAME.eq(m.GRAPH_NAME), bt.TYPE_NAME.eq(m.MEMBER_TYPE_NAME))
            .where(ft.GRAPH_NAME.eq(graphName), ft.TYPE_NAME.eq(typeName), ft.FIELD_NAME.eq(fieldName))
            .andNotExists(selectOne().from(cb)
                .where(cb.GRAPH_NAME.eq(ft.GRAPH_NAME), cb.TYPE_NAME.eq(ft.TARGET_TYPE_NAME)))
            .orderBy(m.MEMBER_TYPE_NAME)
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
