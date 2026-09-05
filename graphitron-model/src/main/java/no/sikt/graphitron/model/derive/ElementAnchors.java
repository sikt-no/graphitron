package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_SYNTHESIS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.catalog.SchemaCoordinateSyntax.argumentCoordinate;
import static no.sikt.graphitron.model.catalog.SchemaCoordinateSyntax.fieldCoordinate;
import static no.sikt.graphitron.model.catalog.SchemaCoordinateSyntax.typeCoordinate;
import static org.jooq.impl.DSL.castNull;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

/**
 * The capture-cadence writer of the element family the generator emits: {@code graphitron_element}
 * and the three anchors under it.
 *
 * <p>Each statement is an insert-select over a population already in the store, which is what the
 * anchors are for. A relation keyed at a coordinate the expansion minted has nowhere to point in the
 * transcription, so it points here; and this being a table rather than a view is what makes it a
 * key's target at all.
 *
 * <p>Written in parent order, the supertype first and the argument last, because each anchor's
 * foreign keys are the family's own. Every statement is restricted to one graph and clears that
 * graph first, so a caller may derive as often as it likes and the second call writes what the first
 * one did.
 *
 * <p>Called as a stage of the graphitron gatherer after macro expansion has flushed, because the
 * minted arm is exactly what that stage wrote. Everything else it reads is the SDL crawler's and has
 * flushed long before.
 */
public final class ElementAnchors {

    private ElementAnchors() {}

    /** Clears and re-derives the graph's element anchors; see the class javadoc. */
    public static void derive(DSLContext dsl, String graphName) {
        clear(dsl, graphName);
        elements(dsl, graphName);
        types(dsl, graphName);
        fields(dsl, graphName);
        arguments(dsl, graphName);
    }

    /** Children first, which is the reverse of the order the fills run in. */
    private static void clear(DSLContext dsl, String graphName) {
        dsl.deleteFrom(GRAPHITRON_ARGUMENT)
            .where(GRAPHITRON_ARGUMENT.GRAPH_NAME.eq(graphName)).execute();
        dsl.deleteFrom(GRAPHITRON_FIELD)
            .where(GRAPHITRON_FIELD.GRAPH_NAME.eq(graphName)).execute();
        dsl.deleteFrom(GRAPHITRON_TYPE)
            .where(GRAPHITRON_TYPE.GRAPH_NAME.eq(graphName)).execute();
        dsl.deleteFrom(GRAPHITRON_ELEMENT)
            .where(GRAPHITRON_ELEMENT.GRAPH_NAME.eq(graphName)).execute();
    }

    /**
     * The supertype: every transcribed coordinate this family holds an anchor for, and every
     * coordinate the expansion minted.
     *
     * <p>The enum value is the one transcribed kind left out, and leaving it out here is what makes
     * the narrower CHECK on {@code element_kind} true rather than merely unviolated.
     *
     * <p>The two minted arms need no anti-join against the first. The expansion stands down on a
     * name its author declared, so a minted coordinate is one the transcription does not hold; the
     * primary key is what would say otherwise, and it says it as a capture bug rather than as a
     * silent winner.
     */
    private static void elements(DSLContext dsl, String graphName) {
        dsl.insertInto(GRAPHITRON_ELEMENT)
            .columns(GRAPHITRON_ELEMENT.GRAPH_NAME, GRAPHITRON_ELEMENT.COORDINATE,
                GRAPHITRON_ELEMENT.ELEMENT_KIND)
            .select(dsl
                .select(GRAPHQL_ELEMENT.GRAPH_NAME, GRAPHQL_ELEMENT.COORDINATE,
                    GRAPHQL_ELEMENT.ELEMENT_KIND)
                .from(GRAPHQL_ELEMENT)
                .where(GRAPHQL_ELEMENT.GRAPH_NAME.eq(graphName))
                .and(GRAPHQL_ELEMENT.ELEMENT_KIND.ne(inline("ENUM_VALUE")))
                .unionAll(dsl
                    .select(GRAPHITRON_MINTED_TYPE.GRAPH_NAME,
                        typeCoordinate(GRAPHITRON_MINTED_TYPE.TYPE_NAME),
                        val("NAMED_TYPE"))
                    .from(GRAPHITRON_MINTED_TYPE)
                    .where(GRAPHITRON_MINTED_TYPE.GRAPH_NAME.eq(graphName)))
                .unionAll(dsl
                    .select(GRAPHITRON_MINTED_FIELD.GRAPH_NAME,
                        fieldCoordinate(GRAPHITRON_MINTED_FIELD.TYPE_NAME,
                            GRAPHITRON_MINTED_FIELD.FIELD_NAME),
                        val("FIELD"))
                    .from(GRAPHITRON_MINTED_FIELD)
                    .where(GRAPHITRON_MINTED_FIELD.GRAPH_NAME.eq(graphName))))
            .execute();
    }

    /** The type grain: the transcription and the mint, a disjoint union. */
    private static void types(DSLContext dsl, String graphName) {
        dsl.insertInto(GRAPHITRON_TYPE)
            .columns(GRAPHITRON_TYPE.GRAPH_NAME, GRAPHITRON_TYPE.TYPE_NAME,
                GRAPHITRON_TYPE.COORDINATE, GRAPHITRON_TYPE.KIND, GRAPHITRON_TYPE.DESCRIPTION)
            .select(dsl
                .select(GRAPHQL_TYPE.GRAPH_NAME, GRAPHQL_TYPE.TYPE_NAME,
                    typeCoordinate(GRAPHQL_TYPE.TYPE_NAME),
                    GRAPHQL_TYPE.KIND, GRAPHQL_TYPE.DESCRIPTION)
                .from(GRAPHQL_TYPE)
                .where(GRAPHQL_TYPE.GRAPH_NAME.eq(graphName))
                .unionAll(dsl
                    .select(GRAPHITRON_MINTED_TYPE.GRAPH_NAME, GRAPHITRON_MINTED_TYPE.TYPE_NAME,
                        typeCoordinate(GRAPHITRON_MINTED_TYPE.TYPE_NAME),
                        GRAPHITRON_MINTED_TYPE.KIND, GRAPHITRON_MINTED_TYPE.DESCRIPTION)
                    .from(GRAPHITRON_MINTED_TYPE)
                    .where(GRAPHITRON_MINTED_TYPE.GRAPH_NAME.eq(graphName))))
            .execute();
    }

    /**
     * The field grain, and the one place the two populations disagree rather than merely differ. A
     * field can be authored at a coordinate whose type expression the expansion then rewrote, so the
     * transcribed arm takes the rewrite where there is one and the author's expression otherwise.
     */
    private static void fields(DSLContext dsl, String graphName) {
        dsl.insertInto(GRAPHITRON_FIELD)
            .columns(GRAPHITRON_FIELD.GRAPH_NAME, GRAPHITRON_FIELD.TYPE_NAME,
                GRAPHITRON_FIELD.FIELD_NAME, GRAPHITRON_FIELD.COORDINATE, GRAPHITRON_FIELD.ORDINAL,
                GRAPHITRON_FIELD.TYPE_SDL, GRAPHITRON_FIELD.NAMED_TYPE, GRAPHITRON_FIELD.NON_NULL,
                GRAPHITRON_FIELD.IS_LIST, GRAPHITRON_FIELD.ITEM_NON_NULL,
                GRAPHITRON_FIELD.DEFAULT_VALUE_SDL, GRAPHITRON_FIELD.DESCRIPTION)
            .select(dsl
                .select(GRAPHQL_FIELD.GRAPH_NAME, GRAPHQL_FIELD.TYPE_NAME, GRAPHQL_FIELD.FIELD_NAME,
                    fieldCoordinate(GRAPHQL_FIELD.TYPE_NAME, GRAPHQL_FIELD.FIELD_NAME),
                    GRAPHQL_FIELD.ORDINAL,
                    coalesce(GRAPHITRON_FIELD_SYNTHESIS.TYPE_SDL, GRAPHQL_FIELD.TYPE_SDL),
                    coalesce(GRAPHITRON_FIELD_SYNTHESIS.NAMED_TYPE, GRAPHQL_FIELD.NAMED_TYPE),
                    coalesce(GRAPHITRON_FIELD_SYNTHESIS.NON_NULL, GRAPHQL_FIELD.NON_NULL),
                    coalesce(GRAPHITRON_FIELD_SYNTHESIS.IS_LIST, GRAPHQL_FIELD.IS_LIST),
                    // Not a COALESCE: a rewrite that is not a list states the item nullness as NULL
                    // and means it, where a COALESCE would fall through to the expression it
                    // replaced. The presence of the synthesis row is the test, not its value.
                    when(GRAPHITRON_FIELD_SYNTHESIS.TYPE_NAME.isNull(), GRAPHQL_FIELD.ITEM_NON_NULL)
                        .otherwise(GRAPHITRON_FIELD_SYNTHESIS.ITEM_NON_NULL),
                    GRAPHQL_FIELD.DEFAULT_VALUE_SDL, GRAPHQL_FIELD.DESCRIPTION)
                .from(GRAPHQL_FIELD)
                .leftJoin(GRAPHITRON_FIELD_SYNTHESIS)
                .on(GRAPHITRON_FIELD_SYNTHESIS.GRAPH_NAME.eq(GRAPHQL_FIELD.GRAPH_NAME))
                .and(GRAPHITRON_FIELD_SYNTHESIS.TYPE_NAME.eq(GRAPHQL_FIELD.TYPE_NAME))
                .and(GRAPHITRON_FIELD_SYNTHESIS.FIELD_NAME.eq(GRAPHQL_FIELD.FIELD_NAME))
                .where(GRAPHQL_FIELD.GRAPH_NAME.eq(graphName))
                .unionAll(dsl
                    .select(GRAPHITRON_MINTED_FIELD.GRAPH_NAME, GRAPHITRON_MINTED_FIELD.TYPE_NAME,
                        GRAPHITRON_MINTED_FIELD.FIELD_NAME,
                        fieldCoordinate(GRAPHITRON_MINTED_FIELD.TYPE_NAME,
                            GRAPHITRON_MINTED_FIELD.FIELD_NAME),
                        GRAPHITRON_MINTED_FIELD.ORDINAL, GRAPHITRON_MINTED_FIELD.TYPE_SDL,
                        GRAPHITRON_MINTED_FIELD.NAMED_TYPE, GRAPHITRON_MINTED_FIELD.NON_NULL,
                        GRAPHITRON_MINTED_FIELD.IS_LIST, GRAPHITRON_MINTED_FIELD.ITEM_NON_NULL,
                        // A minted field is an output field, and no macro writes a default onto
                        // one; the nullness is the population rather than a column not carried.
                        castNull(String.class),
                        GRAPHITRON_MINTED_FIELD.DESCRIPTION)
                    .from(GRAPHITRON_MINTED_FIELD)
                    .where(GRAPHITRON_MINTED_FIELD.GRAPH_NAME.eq(graphName))))
            .execute();
    }

    /**
     * The argument grain, which for now is the transcription alone. The expansion mints two
     * arguments on a carrier whose author wrote no pagination argument, and the store does not record
     * them yet; when it does, they arrive here as the second arm the two grains above already have.
     */
    private static void arguments(DSLContext dsl, String graphName) {
        dsl.insertInto(GRAPHITRON_ARGUMENT)
            .columns(GRAPHITRON_ARGUMENT.GRAPH_NAME, GRAPHITRON_ARGUMENT.TYPE_NAME,
                GRAPHITRON_ARGUMENT.FIELD_NAME, GRAPHITRON_ARGUMENT.ARGUMENT_NAME,
                GRAPHITRON_ARGUMENT.COORDINATE, GRAPHITRON_ARGUMENT.ORDINAL,
                GRAPHITRON_ARGUMENT.TYPE_SDL, GRAPHITRON_ARGUMENT.NAMED_TYPE,
                GRAPHITRON_ARGUMENT.NON_NULL, GRAPHITRON_ARGUMENT.IS_LIST,
                GRAPHITRON_ARGUMENT.ITEM_NON_NULL, GRAPHITRON_ARGUMENT.DEFAULT_VALUE_SDL,
                GRAPHITRON_ARGUMENT.DESCRIPTION)
            .select(dsl
                .select(GRAPHQL_ARGUMENT.GRAPH_NAME, GRAPHQL_ARGUMENT.TYPE_NAME,
                    GRAPHQL_ARGUMENT.FIELD_NAME, GRAPHQL_ARGUMENT.ARGUMENT_NAME,
                    argumentCoordinate(GRAPHQL_ARGUMENT.TYPE_NAME, GRAPHQL_ARGUMENT.FIELD_NAME,
                        GRAPHQL_ARGUMENT.ARGUMENT_NAME),
                    GRAPHQL_ARGUMENT.ORDINAL, GRAPHQL_ARGUMENT.TYPE_SDL,
                    GRAPHQL_ARGUMENT.NAMED_TYPE, GRAPHQL_ARGUMENT.NON_NULL,
                    GRAPHQL_ARGUMENT.IS_LIST, GRAPHQL_ARGUMENT.ITEM_NON_NULL,
                    GRAPHQL_ARGUMENT.DEFAULT_VALUE_SDL, GRAPHQL_ARGUMENT.DESCRIPTION)
                .from(GRAPHQL_ARGUMENT)
                .where(GRAPHQL_ARGUMENT.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
