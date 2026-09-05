package no.sikt.graphitron.model.derive;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_CONFLICT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_ELEMENT;
import static no.sikt.graphitron.model.catalog.SchemaCoordinateSyntax.argumentCoordinate;
import static no.sikt.graphitron.model.catalog.SchemaCoordinateSyntax.fieldCoordinate;
import static no.sikt.graphitron.model.catalog.SchemaCoordinateSyntax.typeCoordinate;
import static org.jooq.impl.DSL.castNull;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

/**
 * The capture-cadence writer of the element family the generator emits: {@code graphitron_element}
 * and the three anchors under it.
 *
 * <p>Each anchor is the transcription and the mint resolved against each other, and the resolution
 * is two statements per grain with an anti-join in each. The minted arm takes the rows that replace,
 * plus the rows that yield where the transcription holds no such coordinate; the transcription's arm
 * takes the rows no replacing mint covers. Both exclusions are anti-joins rather than insert order,
 * so the precedence is readable in the statement and neither arm depends on running second.
 *
 * <p>A relation keyed at a coordinate the expansion minted has nowhere to point in the transcription,
 * so it points here; and this being a table rather than a view is what makes it a key's target at
 * all.
 *
 * <p>Written in parent order, the supertype first and the argument last, because each anchor's
 * foreign keys are the family's own. Every statement is restricted to one graph and lands on the key
 * it already holds, so a caller may derive as often as it likes: a coordinate already anchored takes
 * the payload this pass computed and a new one is inserted beside it.
 *
 * <p>Upserting rather than clearing and refilling, which is a correctness point and not a
 * performance one. Relations key into these anchors with {@code ON DELETE CASCADE}, so emptying one
 * takes their rows with it, and a re-derive that cleared first would wipe a classification domain or
 * a navigation nothing in this class refills. Clearing a graph outright is the refresh's business
 * and it does it in the right order.
 *
 * <p>The minted arms are {@code DISTINCT}, because shared machinery is minted once per carrier and
 * every carrier states the whole of it, so the readings collapse. Where they do not collapse the
 * applications disagree, and neither of them wins: {@link #conflicts} is where that is found, and
 * every arm withholds what it finds.
 */
public final class ElementAnchors {

    private ElementAnchors() {}

    private static final String REPLACE = "REPLACE";

    /** Derives the graph's element anchors; see the class javadoc. */
    public static void derive(DSLContext dsl, String graphName) {
        // First, because every arm below withholds what this finds.
        conflicts(dsl, graphName);
        elements(dsl, graphName);
        types(dsl, graphName);
        fields(dsl, graphName);
        arguments(dsl, graphName);
    }

    /**
     * A coordinate several applications would mint and disagree about, which neither of them gets.
     *
     * <p>Two applications minting one coordinate the same way are the ordinary case: shared
     * machinery is stated whole by every carrier and the readings collapse. Two that disagree are
     * the author's to write, {@code connectionName} naming one connection from two carriers over
     * different element types, so this is not a capture bug and must not be refused as one: capture
     * runs before assembly and for readers that never run it, so throwing would leave an author
     * mid-edit with no store rather than with a store and a diagnostic.
     *
     * <p>Nor may one of them be picked. That would put a shape in the emitted population no
     * application asked for and nothing records. So the coordinate is withheld from every arm below
     * and a row here says why, on {@code intent_authored_claim_conflict}'s terms. What each
     * application would have written is not copied: the minted relations keep it, keyed by the
     * coordinate that coined each.
     *
     * <p>Cleared and refilled rather than upserted, which the anchors themselves cannot be: nothing
     * keys into this relation, so emptying it takes nothing with it, and a conflict an edit resolved
     * has to stop being a row.
     */
    private static void conflicts(DSLContext dsl, String graphName) {
        dsl.deleteFrom(GRAPHITRON_MINTED_CONFLICT)
            .where(GRAPHITRON_MINTED_CONFLICT.GRAPH_NAME.eq(graphName)).execute();

        var types = dsl.selectDistinct(GRAPHITRON_MINTED_TYPE.GRAPH_NAME,
                GRAPHITRON_MINTED_TYPE.TYPE_NAME, GRAPHITRON_MINTED_TYPE.KIND,
                GRAPHITRON_MINTED_TYPE.DESCRIPTION)
            .from(GRAPHITRON_MINTED_TYPE)
            .where(GRAPHITRON_MINTED_TYPE.GRAPH_NAME.eq(graphName))
            .and(mintedTypeTakesEffect())
            .asTable("m");
        dsl.insertInto(GRAPHITRON_MINTED_CONFLICT)
            .columns(GRAPHITRON_MINTED_CONFLICT.GRAPH_NAME, GRAPHITRON_MINTED_CONFLICT.COORDINATE,
                GRAPHITRON_MINTED_CONFLICT.ELEMENT_KIND, GRAPHITRON_MINTED_CONFLICT.VARIANTS)
            .select(dsl
                .select(types.field(GRAPHITRON_MINTED_TYPE.GRAPH_NAME),
                    typeCoordinate(types.field(GRAPHITRON_MINTED_TYPE.TYPE_NAME)),
                    val("NAMED_TYPE"), count())
                .from(types)
                .groupBy(types.field(GRAPHITRON_MINTED_TYPE.GRAPH_NAME),
                    types.field(GRAPHITRON_MINTED_TYPE.TYPE_NAME))
                .having(count().gt(1)))
            .execute();

        var fields = dsl.selectDistinct(GRAPHITRON_MINTED_FIELD.GRAPH_NAME,
                GRAPHITRON_MINTED_FIELD.TYPE_NAME, GRAPHITRON_MINTED_FIELD.FIELD_NAME,
                GRAPHITRON_MINTED_FIELD.ORDINAL, GRAPHITRON_MINTED_FIELD.TYPE_SDL,
                GRAPHITRON_MINTED_FIELD.NAMED_TYPE, GRAPHITRON_MINTED_FIELD.NON_NULL,
                GRAPHITRON_MINTED_FIELD.IS_LIST, GRAPHITRON_MINTED_FIELD.ITEM_NON_NULL,
                GRAPHITRON_MINTED_FIELD.DESCRIPTION)
            .from(GRAPHITRON_MINTED_FIELD)
            .where(GRAPHITRON_MINTED_FIELD.GRAPH_NAME.eq(graphName))
            .and(mintedFieldTakesEffect())
            .asTable("m");
        dsl.insertInto(GRAPHITRON_MINTED_CONFLICT)
            .columns(GRAPHITRON_MINTED_CONFLICT.GRAPH_NAME, GRAPHITRON_MINTED_CONFLICT.COORDINATE,
                GRAPHITRON_MINTED_CONFLICT.ELEMENT_KIND, GRAPHITRON_MINTED_CONFLICT.VARIANTS)
            .select(dsl
                .select(fields.field(GRAPHITRON_MINTED_FIELD.GRAPH_NAME),
                    fieldCoordinate(fields.field(GRAPHITRON_MINTED_FIELD.TYPE_NAME),
                        fields.field(GRAPHITRON_MINTED_FIELD.FIELD_NAME)),
                    val("FIELD"), count())
                .from(fields)
                .groupBy(fields.field(GRAPHITRON_MINTED_FIELD.GRAPH_NAME),
                    fields.field(GRAPHITRON_MINTED_FIELD.TYPE_NAME),
                    fields.field(GRAPHITRON_MINTED_FIELD.FIELD_NAME))
                .having(count().gt(1)))
            .execute();

        var arguments = dsl.selectDistinct(GRAPHITRON_MINTED_ARGUMENT.GRAPH_NAME,
                GRAPHITRON_MINTED_ARGUMENT.TYPE_NAME, GRAPHITRON_MINTED_ARGUMENT.FIELD_NAME,
                GRAPHITRON_MINTED_ARGUMENT.ARGUMENT_NAME, GRAPHITRON_MINTED_ARGUMENT.ORDINAL,
                GRAPHITRON_MINTED_ARGUMENT.TYPE_SDL, GRAPHITRON_MINTED_ARGUMENT.NAMED_TYPE,
                GRAPHITRON_MINTED_ARGUMENT.NON_NULL, GRAPHITRON_MINTED_ARGUMENT.IS_LIST,
                GRAPHITRON_MINTED_ARGUMENT.ITEM_NON_NULL,
                GRAPHITRON_MINTED_ARGUMENT.DEFAULT_VALUE_SDL,
                GRAPHITRON_MINTED_ARGUMENT.DESCRIPTION)
            .from(GRAPHITRON_MINTED_ARGUMENT)
            .where(GRAPHITRON_MINTED_ARGUMENT.GRAPH_NAME.eq(graphName))
            .and(mintedArgumentTakesEffect())
            .asTable("m");
        dsl.insertInto(GRAPHITRON_MINTED_CONFLICT)
            .columns(GRAPHITRON_MINTED_CONFLICT.GRAPH_NAME, GRAPHITRON_MINTED_CONFLICT.COORDINATE,
                GRAPHITRON_MINTED_CONFLICT.ELEMENT_KIND, GRAPHITRON_MINTED_CONFLICT.VARIANTS)
            .select(dsl
                .select(arguments.field(GRAPHITRON_MINTED_ARGUMENT.GRAPH_NAME),
                    argumentCoordinate(arguments.field(GRAPHITRON_MINTED_ARGUMENT.TYPE_NAME),
                        arguments.field(GRAPHITRON_MINTED_ARGUMENT.FIELD_NAME),
                        arguments.field(GRAPHITRON_MINTED_ARGUMENT.ARGUMENT_NAME)),
                    val("FIELD_ARGUMENT"), count())
                .from(arguments)
                .groupBy(arguments.field(GRAPHITRON_MINTED_ARGUMENT.GRAPH_NAME),
                    arguments.field(GRAPHITRON_MINTED_ARGUMENT.TYPE_NAME),
                    arguments.field(GRAPHITRON_MINTED_ARGUMENT.FIELD_NAME),
                    arguments.field(GRAPHITRON_MINTED_ARGUMENT.ARGUMENT_NAME))
                .having(count().gt(1)))
            .execute();
    }

    /** The coordinate is not one the applications minting it disagreed about. */
    private static Condition uncontested(Field<String> graph, Field<String> coordinate) {
        return notExists(selectOne().from(GRAPHITRON_MINTED_CONFLICT)
            .where(GRAPHITRON_MINTED_CONFLICT.GRAPH_NAME.eq(graph))
            .and(GRAPHITRON_MINTED_CONFLICT.COORDINATE.eq(coordinate)));
    }

    /**
     * A minted type takes effect: it replaces, or it yields to nobody. Stated once because the
     * supertype fill and the type fill are the same rule projected two ways.
     */
    private static Condition mintedTypeTakesEffect() {
        return GRAPHITRON_MINTED_TYPE.PRECEDENCE.eq(REPLACE)
            .or(notExists(selectOne().from(GRAPHQL_TYPE_ELEMENT)
                .where(GRAPHQL_TYPE_ELEMENT.GRAPH_NAME.eq(GRAPHITRON_MINTED_TYPE.GRAPH_NAME))
                .and(GRAPHQL_TYPE_ELEMENT.TYPE_NAME.eq(GRAPHITRON_MINTED_TYPE.TYPE_NAME))));
    }

    /** An authored type survives: no mint replaces it. */
    private static Condition authoredTypeSurvives() {
        return notExists(selectOne().from(GRAPHITRON_MINTED_TYPE)
            .where(GRAPHITRON_MINTED_TYPE.GRAPH_NAME.eq(GRAPHQL_TYPE.GRAPH_NAME))
            .and(GRAPHITRON_MINTED_TYPE.TYPE_NAME.eq(GRAPHQL_TYPE.TYPE_NAME))
            .and(GRAPHITRON_MINTED_TYPE.PRECEDENCE.eq(REPLACE)));
    }

    /**
     * A minted field takes effect. Two conditions, and the second is the one the row's own
     * precedence cannot carry.
     *
     * <p>The first is the field's own: it replaces, or it yields to nobody. The second is that a
     * field the macro wrote while minting a type shares that type's fate. An author who declared
     * {@code type PageInfo { foo: String }} collides with the minted type, and the four machinery
     * fields collide with nothing, so the first condition alone would let {@code hasNextPage} land on
     * the author's type and fuse two types nobody asked to merge. Machinery is told from a rewritten
     * carrier by its source: a machinery field shares a source coordinate with a minted type row for
     * its own owning type, where a rewritten {@code Query.films} coined no minted {@code Query}.
     */
    private static Condition mintedFieldTakesEffect() {
        Condition ownCoordinateIsFree = GRAPHITRON_MINTED_FIELD.PRECEDENCE.eq(REPLACE)
            .or(notExists(selectOne().from(GRAPHQL_FIELD_ELEMENT)
                .where(GRAPHQL_FIELD_ELEMENT.GRAPH_NAME.eq(GRAPHITRON_MINTED_FIELD.GRAPH_NAME))
                .and(GRAPHQL_FIELD_ELEMENT.TYPE_NAME.eq(GRAPHITRON_MINTED_FIELD.TYPE_NAME))
                .and(GRAPHQL_FIELD_ELEMENT.FIELD_NAME.eq(GRAPHITRON_MINTED_FIELD.FIELD_NAME))));
        Condition owningTypeLost = exists(selectOne().from(GRAPHITRON_MINTED_TYPE)
            .where(GRAPHITRON_MINTED_TYPE.GRAPH_NAME.eq(GRAPHITRON_MINTED_FIELD.GRAPH_NAME))
            .and(GRAPHITRON_MINTED_TYPE.SOURCE_COORDINATE
                .eq(GRAPHITRON_MINTED_FIELD.SOURCE_COORDINATE))
            .and(GRAPHITRON_MINTED_TYPE.TYPE_NAME.eq(GRAPHITRON_MINTED_FIELD.TYPE_NAME))
            .and(GRAPHITRON_MINTED_TYPE.PRECEDENCE.ne(REPLACE))
            .and(exists(selectOne().from(GRAPHQL_TYPE_ELEMENT)
                .where(GRAPHQL_TYPE_ELEMENT.GRAPH_NAME.eq(GRAPHITRON_MINTED_TYPE.GRAPH_NAME))
                .and(GRAPHQL_TYPE_ELEMENT.TYPE_NAME.eq(GRAPHITRON_MINTED_TYPE.TYPE_NAME)))));
        return ownCoordinateIsFree.and(owningTypeLost.not());
    }

    /** An authored field survives: no mint replaces it. */
    private static Condition authoredFieldSurvives() {
        return notExists(selectOne().from(GRAPHITRON_MINTED_FIELD)
            .where(GRAPHITRON_MINTED_FIELD.GRAPH_NAME.eq(GRAPHQL_FIELD.GRAPH_NAME))
            .and(GRAPHITRON_MINTED_FIELD.TYPE_NAME.eq(GRAPHQL_FIELD.TYPE_NAME))
            .and(GRAPHITRON_MINTED_FIELD.FIELD_NAME.eq(GRAPHQL_FIELD.FIELD_NAME))
            .and(GRAPHITRON_MINTED_FIELD.PRECEDENCE.eq(REPLACE)));
    }

    /**
     * A minted argument takes effect, on the field grain's first condition alone. No second one is
     * owed: every argument minted today lands on the carrier the directive sits on, and that field
     * survives whether the expansion rewrote it or left it, so there is no owning element whose fate
     * an argument could have to share.
     */
    private static Condition mintedArgumentTakesEffect() {
        return GRAPHITRON_MINTED_ARGUMENT.PRECEDENCE.eq(REPLACE)
            .or(notExists(selectOne().from(GRAPHQL_ARGUMENT_ELEMENT)
                .where(GRAPHQL_ARGUMENT_ELEMENT.GRAPH_NAME
                    .eq(GRAPHITRON_MINTED_ARGUMENT.GRAPH_NAME))
                .and(GRAPHQL_ARGUMENT_ELEMENT.TYPE_NAME.eq(GRAPHITRON_MINTED_ARGUMENT.TYPE_NAME))
                .and(GRAPHQL_ARGUMENT_ELEMENT.FIELD_NAME.eq(GRAPHITRON_MINTED_ARGUMENT.FIELD_NAME))
                .and(GRAPHQL_ARGUMENT_ELEMENT.ARGUMENT_NAME
                    .eq(GRAPHITRON_MINTED_ARGUMENT.ARGUMENT_NAME))));
    }

    /** An authored argument survives: no mint replaces it. */
    private static Condition authoredArgumentSurvives() {
        return notExists(selectOne().from(GRAPHITRON_MINTED_ARGUMENT)
            .where(GRAPHITRON_MINTED_ARGUMENT.GRAPH_NAME.eq(GRAPHQL_ARGUMENT.GRAPH_NAME))
            .and(GRAPHITRON_MINTED_ARGUMENT.TYPE_NAME.eq(GRAPHQL_ARGUMENT.TYPE_NAME))
            .and(GRAPHITRON_MINTED_ARGUMENT.FIELD_NAME.eq(GRAPHQL_ARGUMENT.FIELD_NAME))
            .and(GRAPHITRON_MINTED_ARGUMENT.ARGUMENT_NAME.eq(GRAPHQL_ARGUMENT.ARGUMENT_NAME))
            .and(GRAPHITRON_MINTED_ARGUMENT.PRECEDENCE.eq(REPLACE)));
    }

    // ---------------------------------------------------------------- the fills

    /**
     * The supertype: every coordinate the three anchors below will hold, under the same conditions
     * they hold it. The conditions are shared rather than restated, so the supertype cannot admit a
     * coordinate no anchor claims or refuse one an anchor needs.
     *
     * <p>The transcribed arm is one statement over {@code graphql_element} rather than three over
     * its subtypes, and that is what keeps the element kind exact: the specification's split between
     * a field and an input field is settled at the transcription's own write, from the parent's kind
     * at the moment the walk is in its body, and copying the column is how it survives here. Nothing
     * is anti-joined out of it, an authored coordinate a mint replaces staying at that same
     * coordinate. The enum value is the one kind left out, and leaving it out is what makes the
     * narrower CHECK on {@code element_kind} true rather than merely unviolated.
     *
     * <p>The minted arms add what the transcription does not hold, and each writes FIELD or
     * FIELD_ARGUMENT outright. A minted type is an OBJECT and a rewritten carrier sits on the type
     * whose field carried the directive, so no macro today puts a field into an input object; the
     * day one does, this is where the kind has to start being derived.
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
                    .selectDistinct(GRAPHITRON_MINTED_TYPE.GRAPH_NAME,
                        typeCoordinate(GRAPHITRON_MINTED_TYPE.TYPE_NAME), val("NAMED_TYPE"))
                    .from(GRAPHITRON_MINTED_TYPE)
                    .where(GRAPHITRON_MINTED_TYPE.GRAPH_NAME.eq(graphName))
                    .and(mintedTypeTakesEffect())
                    .and(uncontested(GRAPHITRON_MINTED_TYPE.GRAPH_NAME,
                        typeCoordinate(GRAPHITRON_MINTED_TYPE.TYPE_NAME)))
                    .and(notExists(selectOne().from(GRAPHQL_TYPE_ELEMENT)
                        .where(GRAPHQL_TYPE_ELEMENT.GRAPH_NAME
                            .eq(GRAPHITRON_MINTED_TYPE.GRAPH_NAME))
                        .and(GRAPHQL_TYPE_ELEMENT.TYPE_NAME
                            .eq(GRAPHITRON_MINTED_TYPE.TYPE_NAME)))))
                .unionAll(dsl
                    .selectDistinct(GRAPHITRON_MINTED_FIELD.GRAPH_NAME,
                        fieldCoordinate(GRAPHITRON_MINTED_FIELD.TYPE_NAME,
                            GRAPHITRON_MINTED_FIELD.FIELD_NAME),
                        val("FIELD"))
                    .from(GRAPHITRON_MINTED_FIELD)
                    .where(GRAPHITRON_MINTED_FIELD.GRAPH_NAME.eq(graphName))
                    .and(mintedFieldTakesEffect())
                    .and(uncontested(GRAPHITRON_MINTED_FIELD.GRAPH_NAME,
                        fieldCoordinate(GRAPHITRON_MINTED_FIELD.TYPE_NAME,
                            GRAPHITRON_MINTED_FIELD.FIELD_NAME)))
                    .and(notExists(selectOne().from(GRAPHQL_FIELD_ELEMENT)
                        .where(GRAPHQL_FIELD_ELEMENT.GRAPH_NAME
                            .eq(GRAPHITRON_MINTED_FIELD.GRAPH_NAME))
                        .and(GRAPHQL_FIELD_ELEMENT.TYPE_NAME
                            .eq(GRAPHITRON_MINTED_FIELD.TYPE_NAME))
                        .and(GRAPHQL_FIELD_ELEMENT.FIELD_NAME
                            .eq(GRAPHITRON_MINTED_FIELD.FIELD_NAME)))))
                .unionAll(dsl
                    .selectDistinct(GRAPHITRON_MINTED_ARGUMENT.GRAPH_NAME,
                        argumentCoordinate(GRAPHITRON_MINTED_ARGUMENT.TYPE_NAME,
                            GRAPHITRON_MINTED_ARGUMENT.FIELD_NAME,
                            GRAPHITRON_MINTED_ARGUMENT.ARGUMENT_NAME),
                        val("FIELD_ARGUMENT"))
                    .from(GRAPHITRON_MINTED_ARGUMENT)
                    .where(GRAPHITRON_MINTED_ARGUMENT.GRAPH_NAME.eq(graphName))
                    .and(mintedArgumentTakesEffect())
                    .and(uncontested(GRAPHITRON_MINTED_ARGUMENT.GRAPH_NAME,
                        argumentCoordinate(GRAPHITRON_MINTED_ARGUMENT.TYPE_NAME,
                            GRAPHITRON_MINTED_ARGUMENT.FIELD_NAME,
                            GRAPHITRON_MINTED_ARGUMENT.ARGUMENT_NAME)))
                    .and(notExists(selectOne().from(GRAPHQL_ARGUMENT_ELEMENT)
                        .where(GRAPHQL_ARGUMENT_ELEMENT.GRAPH_NAME
                            .eq(GRAPHITRON_MINTED_ARGUMENT.GRAPH_NAME))
                        .and(GRAPHQL_ARGUMENT_ELEMENT.TYPE_NAME
                            .eq(GRAPHITRON_MINTED_ARGUMENT.TYPE_NAME))
                        .and(GRAPHQL_ARGUMENT_ELEMENT.FIELD_NAME
                            .eq(GRAPHITRON_MINTED_ARGUMENT.FIELD_NAME))
                        .and(GRAPHQL_ARGUMENT_ELEMENT.ARGUMENT_NAME
                            .eq(GRAPHITRON_MINTED_ARGUMENT.ARGUMENT_NAME))))))
            .onDuplicateKeyUpdate()
            .set(GRAPHITRON_ELEMENT.ELEMENT_KIND, excluded(GRAPHITRON_ELEMENT.ELEMENT_KIND))
            .execute();
    }

    /**
     * The type grain.
     */
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
                .and(authoredTypeSurvives())
                .unionAll(dsl
                    .selectDistinct(GRAPHITRON_MINTED_TYPE.GRAPH_NAME,
                        GRAPHITRON_MINTED_TYPE.TYPE_NAME,
                        typeCoordinate(GRAPHITRON_MINTED_TYPE.TYPE_NAME),
                        GRAPHITRON_MINTED_TYPE.KIND, GRAPHITRON_MINTED_TYPE.DESCRIPTION)
                    .from(GRAPHITRON_MINTED_TYPE)
                    .where(GRAPHITRON_MINTED_TYPE.GRAPH_NAME.eq(graphName))
                    .and(mintedTypeTakesEffect())
                    .and(uncontested(GRAPHITRON_MINTED_TYPE.GRAPH_NAME,
                        typeCoordinate(GRAPHITRON_MINTED_TYPE.TYPE_NAME)))))
            .onDuplicateKeyUpdate()
            .set(GRAPHITRON_TYPE.COORDINATE, excluded(GRAPHITRON_TYPE.COORDINATE))
            .set(GRAPHITRON_TYPE.KIND, excluded(GRAPHITRON_TYPE.KIND))
            .set(GRAPHITRON_TYPE.DESCRIPTION, excluded(GRAPHITRON_TYPE.DESCRIPTION))
            .execute();
    }

    /**
     * The field grain, and the one place the two populations disagree rather than merely differ. A
     * field can be authored at a coordinate whose type expression the expansion then rewrote, and
     * that rewrite is a minted row at the field's own coordinate carrying its whole row, so the two
     * arms are exclusive and nothing coalesces.
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
                    GRAPHQL_FIELD.ORDINAL, GRAPHQL_FIELD.TYPE_SDL, GRAPHQL_FIELD.NAMED_TYPE,
                    GRAPHQL_FIELD.NON_NULL, GRAPHQL_FIELD.IS_LIST, GRAPHQL_FIELD.ITEM_NON_NULL,
                    GRAPHQL_FIELD.DEFAULT_VALUE_SDL, GRAPHQL_FIELD.DESCRIPTION)
                .from(GRAPHQL_FIELD)
                .where(GRAPHQL_FIELD.GRAPH_NAME.eq(graphName))
                .and(authoredFieldSurvives())
                .unionAll(dsl
                    .selectDistinct(GRAPHITRON_MINTED_FIELD.GRAPH_NAME,
                        GRAPHITRON_MINTED_FIELD.TYPE_NAME, GRAPHITRON_MINTED_FIELD.FIELD_NAME,
                        fieldCoordinate(GRAPHITRON_MINTED_FIELD.TYPE_NAME,
                            GRAPHITRON_MINTED_FIELD.FIELD_NAME),
                        GRAPHITRON_MINTED_FIELD.ORDINAL, GRAPHITRON_MINTED_FIELD.TYPE_SDL,
                        GRAPHITRON_MINTED_FIELD.NAMED_TYPE, GRAPHITRON_MINTED_FIELD.NON_NULL,
                        GRAPHITRON_MINTED_FIELD.IS_LIST, GRAPHITRON_MINTED_FIELD.ITEM_NON_NULL,
                        // No macro writes a default onto an output field, and every field minted or
                        // rewritten today is one; the nullness is the population rather than a
                        // column the minted relation declined to carry.
                        castNull(String.class),
                        GRAPHITRON_MINTED_FIELD.DESCRIPTION)
                    .from(GRAPHITRON_MINTED_FIELD)
                    .where(GRAPHITRON_MINTED_FIELD.GRAPH_NAME.eq(graphName))
                    .and(mintedFieldTakesEffect())
                    .and(uncontested(GRAPHITRON_MINTED_FIELD.GRAPH_NAME,
                        fieldCoordinate(GRAPHITRON_MINTED_FIELD.TYPE_NAME,
                            GRAPHITRON_MINTED_FIELD.FIELD_NAME)))))
            .onDuplicateKeyUpdate()
            .set(GRAPHITRON_FIELD.COORDINATE, excluded(GRAPHITRON_FIELD.COORDINATE))
            .set(GRAPHITRON_FIELD.ORDINAL, excluded(GRAPHITRON_FIELD.ORDINAL))
            .set(GRAPHITRON_FIELD.TYPE_SDL, excluded(GRAPHITRON_FIELD.TYPE_SDL))
            .set(GRAPHITRON_FIELD.NAMED_TYPE, excluded(GRAPHITRON_FIELD.NAMED_TYPE))
            .set(GRAPHITRON_FIELD.NON_NULL, excluded(GRAPHITRON_FIELD.NON_NULL))
            .set(GRAPHITRON_FIELD.IS_LIST, excluded(GRAPHITRON_FIELD.IS_LIST))
            .set(GRAPHITRON_FIELD.ITEM_NON_NULL, excluded(GRAPHITRON_FIELD.ITEM_NON_NULL))
            .set(GRAPHITRON_FIELD.DEFAULT_VALUE_SDL, excluded(GRAPHITRON_FIELD.DEFAULT_VALUE_SDL))
            .set(GRAPHITRON_FIELD.DESCRIPTION, excluded(GRAPHITRON_FIELD.DESCRIPTION))
            .execute();
    }

    /** The argument grain, on the two above's terms. */
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
                .where(GRAPHQL_ARGUMENT.GRAPH_NAME.eq(graphName))
                .and(authoredArgumentSurvives())
                .unionAll(dsl
                    .selectDistinct(GRAPHITRON_MINTED_ARGUMENT.GRAPH_NAME,
                        GRAPHITRON_MINTED_ARGUMENT.TYPE_NAME,
                        GRAPHITRON_MINTED_ARGUMENT.FIELD_NAME,
                        GRAPHITRON_MINTED_ARGUMENT.ARGUMENT_NAME,
                        argumentCoordinate(GRAPHITRON_MINTED_ARGUMENT.TYPE_NAME,
                            GRAPHITRON_MINTED_ARGUMENT.FIELD_NAME,
                            GRAPHITRON_MINTED_ARGUMENT.ARGUMENT_NAME),
                        GRAPHITRON_MINTED_ARGUMENT.ORDINAL, GRAPHITRON_MINTED_ARGUMENT.TYPE_SDL,
                        GRAPHITRON_MINTED_ARGUMENT.NAMED_TYPE, GRAPHITRON_MINTED_ARGUMENT.NON_NULL,
                        GRAPHITRON_MINTED_ARGUMENT.IS_LIST,
                        GRAPHITRON_MINTED_ARGUMENT.ITEM_NON_NULL,
                        GRAPHITRON_MINTED_ARGUMENT.DEFAULT_VALUE_SDL,
                        GRAPHITRON_MINTED_ARGUMENT.DESCRIPTION)
                    .from(GRAPHITRON_MINTED_ARGUMENT)
                    .where(GRAPHITRON_MINTED_ARGUMENT.GRAPH_NAME.eq(graphName))
                    .and(mintedArgumentTakesEffect())
                    .and(uncontested(GRAPHITRON_MINTED_ARGUMENT.GRAPH_NAME,
                        argumentCoordinate(GRAPHITRON_MINTED_ARGUMENT.TYPE_NAME,
                            GRAPHITRON_MINTED_ARGUMENT.FIELD_NAME,
                            GRAPHITRON_MINTED_ARGUMENT.ARGUMENT_NAME)))))
            .onDuplicateKeyUpdate()
            .set(GRAPHITRON_ARGUMENT.COORDINATE, excluded(GRAPHITRON_ARGUMENT.COORDINATE))
            .set(GRAPHITRON_ARGUMENT.ORDINAL, excluded(GRAPHITRON_ARGUMENT.ORDINAL))
            .set(GRAPHITRON_ARGUMENT.TYPE_SDL, excluded(GRAPHITRON_ARGUMENT.TYPE_SDL))
            .set(GRAPHITRON_ARGUMENT.NAMED_TYPE, excluded(GRAPHITRON_ARGUMENT.NAMED_TYPE))
            .set(GRAPHITRON_ARGUMENT.NON_NULL, excluded(GRAPHITRON_ARGUMENT.NON_NULL))
            .set(GRAPHITRON_ARGUMENT.IS_LIST, excluded(GRAPHITRON_ARGUMENT.IS_LIST))
            .set(GRAPHITRON_ARGUMENT.ITEM_NON_NULL, excluded(GRAPHITRON_ARGUMENT.ITEM_NON_NULL))
            .set(GRAPHITRON_ARGUMENT.DEFAULT_VALUE_SDL,
                excluded(GRAPHITRON_ARGUMENT.DEFAULT_VALUE_SDL))
            .set(GRAPHITRON_ARGUMENT.DESCRIPTION, excluded(GRAPHITRON_ARGUMENT.DESCRIPTION))
            .execute();
    }
}
