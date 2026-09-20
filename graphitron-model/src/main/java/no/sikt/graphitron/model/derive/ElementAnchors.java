package no.sikt.graphitron.model.derive;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_AUTHORED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_MINTED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ELEMENT_AUTHORED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ELEMENT_MINTED_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ELEMENT_MINTED_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ELEMENT_MINTED_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_AUTHORED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_MINTED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_CONFLICT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_AUTHORED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_MINTED;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_ELEMENT;
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
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.field;
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

    /**
     * Any one coordinate whose application coined this minted element.
     *
     * <p>One rather than all, and not the smallest or the earliest either. Shared machinery is
     * stated whole by every carrier that wants it, so the rows differ in nothing but which carrier
     * wrote them: every candidate is as true as every other, and the first one found says what the
     * column exists to say. Aggregating over the group would read all of them to answer a question
     * the first already answers, and ordering them would invent a precedence the facts do not have.
     *
     * <p>Correlated to the outer row's coordinate rather than its source, which is what makes the
     * value the same for every carrier of one element and so lets the {@code DISTINCT} above
     * collapse them as it did before this column existed.
     */
    private static Field<String> aCoinerOfType() {
        var coiner = GRAPHITRON_MINTED_TYPE.as("coiner");
        return field(select(coiner.SOURCE_COORDINATE).from(coiner)
            .where(coiner.GRAPH_NAME.eq(GRAPHITRON_MINTED_TYPE.GRAPH_NAME))
            .and(coiner.TYPE_NAME.eq(GRAPHITRON_MINTED_TYPE.TYPE_NAME))
            .limit(1));
    }

    /** {@link #aCoinerOfType()} for a minted field. */
    private static Field<String> aCoinerOfField() {
        var coiner = GRAPHITRON_MINTED_FIELD.as("coiner");
        return field(select(coiner.SOURCE_COORDINATE).from(coiner)
            .where(coiner.GRAPH_NAME.eq(GRAPHITRON_MINTED_FIELD.GRAPH_NAME))
            .and(coiner.TYPE_NAME.eq(GRAPHITRON_MINTED_FIELD.TYPE_NAME))
            .and(coiner.FIELD_NAME.eq(GRAPHITRON_MINTED_FIELD.FIELD_NAME))
            .limit(1));
    }

    /** {@link #aCoinerOfType()} for a minted field argument. */
    private static Field<String> aCoinerOfArgument() {
        var coiner = GRAPHITRON_MINTED_ARGUMENT.as("coiner");
        return field(select(coiner.SOURCE_COORDINATE).from(coiner)
            .where(coiner.GRAPH_NAME.eq(GRAPHITRON_MINTED_ARGUMENT.GRAPH_NAME))
            .and(coiner.TYPE_NAME.eq(GRAPHITRON_MINTED_ARGUMENT.TYPE_NAME))
            .and(coiner.FIELD_NAME.eq(GRAPHITRON_MINTED_ARGUMENT.FIELD_NAME))
            .and(coiner.ARGUMENT_NAME.eq(GRAPHITRON_MINTED_ARGUMENT.ARGUMENT_NAME))
            .limit(1));
    }

    /**
     * Derives the graph's element anchors; see the class javadoc.
     *
     * <p>{@code touchedAt} is the reading's own instant, stamped on every row and the thing its
     * sweep tells readings apart by. It is the caller's rather than this method's, on every
     * gatherer's terms: two readings sharing one could not tell each other's rows apart, so what
     * the second stopped finding would stay.
     */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        // First, because every arm below withholds what this finds.
        conflicts(dsl, graphName);
        elements(dsl, graphName, touchedAt);
        types(dsl, graphName);
        fields(dsl, graphName);
        arguments(dsl, graphName);
        sweep(dsl, graphName, touchedAt);
    }

    /**
     * Drops the anchors this reading did not rewrite, which are the elements the corpus stopped
     * emitting.
     *
     * <p>Children before the supertype, because the three subtypes key into it and a delete in the
     * other order would be refused. They need no stamp of their own: a subtype row exists exactly
     * when its supertype row does, so the supertype's is the whole of the question, and the three
     * arms above rewrite every row they keep.
     *
     * <p>This is the half of the lifecycle the walk's clear used to stand in for. The other half is
     * the cascade on {@code source_coordinate}, and the two answer different questions: a
     * contributor disappearing between readings nulls the provenance and leaves the element
     * standing, and an element this reading stopped deriving at all goes here.
     */
    private static void sweep(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var stale = dsl.select(GRAPHITRON_ELEMENT.COORDINATE)
            .from(GRAPHITRON_ELEMENT)
            .where(GRAPHITRON_ELEMENT.GRAPH_NAME.eq(graphName))
            .and(GRAPHITRON_ELEMENT.TOUCHED_AT.ne(touchedAt))
            .fetchSet(GRAPHITRON_ELEMENT.COORDINATE);
        if (stale.isEmpty()) {
            return;
        }
        dsl.deleteFrom(GRAPHITRON_ARGUMENT)
            .where(GRAPHITRON_ARGUMENT.GRAPH_NAME.eq(graphName))
            .and(GRAPHITRON_ARGUMENT.COORDINATE.in(stale)).execute();
        dsl.deleteFrom(GRAPHITRON_FIELD)
            .where(GRAPHITRON_FIELD.GRAPH_NAME.eq(graphName))
            .and(GRAPHITRON_FIELD.COORDINATE.in(stale)).execute();
        dsl.deleteFrom(GRAPHITRON_TYPE)
            .where(GRAPHITRON_TYPE.GRAPH_NAME.eq(graphName))
            .and(GRAPHITRON_TYPE.COORDINATE.in(stale)).execute();
        dsl.deleteFrom(GRAPHITRON_ELEMENT)
            .where(GRAPHITRON_ELEMENT.GRAPH_NAME.eq(graphName))
            .and(GRAPHITRON_ELEMENT.COORDINATE.in(stale)).execute();
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

    /**
     * A minted type takes effect: it fills a name no author declared.
     *
     * <p>No second arm about replacing, though the column that would carry one is here. Every
     * minted type yields, and {@code graphitron_minted_type.precedence}'s {@code CHECK} is what
     * holds the expansions to it, so a disjunct on {@code REPLACE} could not be the reason a row
     * survived. Stated once because the conflict scan and the type set are the same rule projected
     * two ways.
     */
    private static Condition mintedTypeTakesEffect() {
        return notExists(selectOne().from(GRAPHQL_TYPE_ELEMENT)
            .where(GRAPHQL_TYPE_ELEMENT.GRAPH_NAME.eq(GRAPHITRON_MINTED_TYPE.GRAPH_NAME))
            .and(GRAPHQL_TYPE_ELEMENT.TYPE_NAME.eq(GRAPHITRON_MINTED_TYPE.TYPE_NAME)));
    }

    /**
     * A minted field takes effect. Two conditions, and the second is the one the row's own
     * precedence cannot carry.
     *
     * <p>The first is the field's own: it replaces, or it yields to nobody. This is the one grain
     * where both values occur, a rewritten carrier replacing what its author wrote.
     *
     * <p>The second is that a field the macro wrote while minting a type shares that type's fate.
     * An author who declared {@code type PageInfo { foo: String }} collides with the minted type,
     * and the four machinery fields collide with nothing, so the first condition alone would let
     * {@code hasNextPage} land on the author's type and fuse two types nobody asked to merge.
     * Machinery is told from a rewritten carrier by its source: a machinery field shares a source
     * coordinate with a minted type row for its own owning type, where a rewritten
     * {@code Query.films} coined no minted {@code Query}. Nothing here asks what that type row's
     * precedence was, {@link #mintedTypeTakesEffect()} saying why.
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
            .and(exists(selectOne().from(GRAPHQL_TYPE_ELEMENT)
                .where(GRAPHQL_TYPE_ELEMENT.GRAPH_NAME.eq(GRAPHITRON_MINTED_TYPE.GRAPH_NAME))
                .and(GRAPHQL_TYPE_ELEMENT.TYPE_NAME.eq(GRAPHITRON_MINTED_TYPE.TYPE_NAME)))));
        return ownCoordinateIsFree.and(owningTypeLost.not());
    }

    /**
     * A minted argument takes effect, on the type grain's terms and for its reason: every argument
     * minted today yields, and the {@code CHECK} on
     * {@code graphitron_minted_argument.precedence} holds it there.
     *
     * <p>No rule of the field grain's second kind is owed either. Every argument minted today lands
     * on the carrier the directive sits on, and that field survives whether the expansion rewrote
     * it or left it, so there is no owning element whose fate an argument could have to share.
     */
    private static Condition mintedArgumentTakesEffect() {
        return notExists(selectOne().from(GRAPHQL_ARGUMENT_ELEMENT)
            .where(GRAPHQL_ARGUMENT_ELEMENT.GRAPH_NAME.eq(GRAPHITRON_MINTED_ARGUMENT.GRAPH_NAME))
            .and(GRAPHQL_ARGUMENT_ELEMENT.TYPE_NAME.eq(GRAPHITRON_MINTED_ARGUMENT.TYPE_NAME))
            .and(GRAPHQL_ARGUMENT_ELEMENT.FIELD_NAME.eq(GRAPHITRON_MINTED_ARGUMENT.FIELD_NAME))
            .and(GRAPHQL_ARGUMENT_ELEMENT.ARGUMENT_NAME
                .eq(GRAPHITRON_MINTED_ARGUMENT.ARGUMENT_NAME)));
    }


    // ---------------------------------------------------------------- the fills

    /**
     * The anchor is the union of its sets, and nothing else.
     *
     * <p>Each set is a view naming what it contains, so what a population holds and what admits it
     * are readable without reading the other three. This was four arms of one statement with two
     * precedence rules interleaved through them, and the legality of each arm lived in Java
     * predicates rather than anywhere a reader of the schema could find it.
     *
     * <p>{@code UNION ALL} rather than {@code UNION}: the sets are disjoint by construction, every
     * minted set excluding the coordinates the transcription anchors, which is the whole of what
     * the authored set holds, and no two minted sets sharing a coordinate grammar. Deduplicating
     * across them would pay on every capture for a question each view has already settled.
     *
     * <p>What is left here is the instant and the upsert. A coordinate already anchored takes this
     * reading's stamp and provenance, a new one is inserted beside it, and {@link #sweep} removes
     * what this reading did not write.
     *
     * <p>Each arm names its own view's columns rather than looking them up by string. The four
     * views carry one shape, so one helper taking a table would have been shorter; it would also
     * return null for a column whose name it got wrong, and produce a union that compiles and is
     * silently missing a set.
     */
    private static void elements(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var authored = GRAPHITRON_ELEMENT_AUTHORED;
        var mintedType = GRAPHITRON_ELEMENT_MINTED_TYPE;
        var mintedField = GRAPHITRON_ELEMENT_MINTED_FIELD;
        var mintedArgument = GRAPHITRON_ELEMENT_MINTED_ARGUMENT;
        dsl.insertInto(GRAPHITRON_ELEMENT)
            .columns(GRAPHITRON_ELEMENT.GRAPH_NAME, GRAPHITRON_ELEMENT.COORDINATE,
                GRAPHITRON_ELEMENT.ELEMENT_KIND, GRAPHITRON_ELEMENT.TOUCHED_AT)
            .select(dsl
                .select(authored.GRAPH_NAME, authored.COORDINATE, authored.ELEMENT_KIND,
                    val(touchedAt))
                .from(authored)
                .where(authored.GRAPH_NAME.eq(graphName))
                .unionAll(dsl
                    .select(mintedType.GRAPH_NAME, mintedType.COORDINATE,
                        mintedType.ELEMENT_KIND, val(touchedAt))
                    .from(mintedType)
                    .where(mintedType.GRAPH_NAME.eq(graphName)))
                .unionAll(dsl
                    .select(mintedField.GRAPH_NAME, mintedField.COORDINATE,
                        mintedField.ELEMENT_KIND, val(touchedAt))
                    .from(mintedField)
                    .where(mintedField.GRAPH_NAME.eq(graphName)))
                .unionAll(dsl
                    .select(mintedArgument.GRAPH_NAME, mintedArgument.COORDINATE,
                        mintedArgument.ELEMENT_KIND, val(touchedAt))
                    .from(mintedArgument)
                    .where(mintedArgument.GRAPH_NAME.eq(graphName))))
            .onDuplicateKeyUpdate()
            .set(GRAPHITRON_ELEMENT.ELEMENT_KIND, excluded(GRAPHITRON_ELEMENT.ELEMENT_KIND))
            .set(GRAPHITRON_ELEMENT.TOUCHED_AT, excluded(GRAPHITRON_ELEMENT.TOUCHED_AT))
            .execute();
    }

    /**
     * The type grain, on {@link #elements}' terms: two named sets and a union over them.
     *
     * <p>The authored set excludes nothing. That is the {@code CHECK} on
     * {@code graphitron_minted_type.precedence} showing up as absence: where no mint can replace, an
     * author's declaration always survives, and a view saying so with an exclusion that excludes
     * nothing would read as though something could.
     */
    private static void types(DSLContext dsl, String graphName) {
        var authored = GRAPHITRON_TYPE_AUTHORED;
        var minted = GRAPHITRON_TYPE_MINTED;
        dsl.insertInto(GRAPHITRON_TYPE)
            .columns(GRAPHITRON_TYPE.GRAPH_NAME, GRAPHITRON_TYPE.TYPE_NAME,
                GRAPHITRON_TYPE.COORDINATE, GRAPHITRON_TYPE.KIND, GRAPHITRON_TYPE.DESCRIPTION)
            .select(dsl
                .select(authored.GRAPH_NAME, authored.TYPE_NAME, authored.COORDINATE,
                    authored.KIND, authored.DESCRIPTION)
                .from(authored)
                .where(authored.GRAPH_NAME.eq(graphName))
                .unionAll(dsl
                    .select(minted.GRAPH_NAME, minted.TYPE_NAME, minted.COORDINATE,
                        minted.KIND, minted.DESCRIPTION)
                    .from(minted)
                    .where(minted.GRAPH_NAME.eq(graphName))))
            .onDuplicateKeyUpdate()
            .set(GRAPHITRON_TYPE.COORDINATE, excluded(GRAPHITRON_TYPE.COORDINATE))
            .set(GRAPHITRON_TYPE.KIND, excluded(GRAPHITRON_TYPE.KIND))
            .set(GRAPHITRON_TYPE.DESCRIPTION, excluded(GRAPHITRON_TYPE.DESCRIPTION))
            .execute();
    }

    /**
     * The field grain, and the one where the two sets are not simply authored and the rest.
     *
     * <p>A rewritten carrier is in the minted set and out of the authored one, which is what
     * {@code REPLACE} buys and the only grain that spends it. So this is the only place the part
     * sets and the element sets disagree about a coordinate: {@code Query.films} is an authored
     * element and a minted field. The disagreement is why the two families are stated separately
     * rather than one being derived from the other, which would be right five times and silently
     * wrong here.
     */
    private static void fields(DSLContext dsl, String graphName) {
        var authored = GRAPHITRON_FIELD_AUTHORED;
        var minted = GRAPHITRON_FIELD_MINTED;
        dsl.insertInto(GRAPHITRON_FIELD)
            .columns(GRAPHITRON_FIELD.GRAPH_NAME, GRAPHITRON_FIELD.TYPE_NAME,
                GRAPHITRON_FIELD.FIELD_NAME, GRAPHITRON_FIELD.COORDINATE, GRAPHITRON_FIELD.ORDINAL,
                GRAPHITRON_FIELD.TYPE_SDL, GRAPHITRON_FIELD.NAMED_TYPE, GRAPHITRON_FIELD.NON_NULL,
                GRAPHITRON_FIELD.IS_LIST, GRAPHITRON_FIELD.ITEM_NON_NULL,
                GRAPHITRON_FIELD.DEFAULT_VALUE_SDL, GRAPHITRON_FIELD.DESCRIPTION)
            .select(dsl
                .select(authored.GRAPH_NAME, authored.TYPE_NAME, authored.FIELD_NAME,
                    authored.COORDINATE, authored.ORDINAL, authored.TYPE_SDL, authored.NAMED_TYPE,
                    authored.NON_NULL, authored.IS_LIST, authored.ITEM_NON_NULL,
                    authored.DEFAULT_VALUE_SDL, authored.DESCRIPTION)
                .from(authored)
                .where(authored.GRAPH_NAME.eq(graphName))
                .unionAll(dsl
                    .select(minted.GRAPH_NAME, minted.TYPE_NAME, minted.FIELD_NAME,
                        minted.COORDINATE, minted.ORDINAL, minted.TYPE_SDL, minted.NAMED_TYPE,
                        minted.NON_NULL, minted.IS_LIST, minted.ITEM_NON_NULL,
                        minted.DEFAULT_VALUE_SDL, minted.DESCRIPTION)
                    .from(minted)
                    .where(minted.GRAPH_NAME.eq(graphName))))
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

    /** The argument grain, on the two above's terms and with the type grain's empty exclusion. */
    private static void arguments(DSLContext dsl, String graphName) {
        var authored = GRAPHITRON_ARGUMENT_AUTHORED;
        var minted = GRAPHITRON_ARGUMENT_MINTED;
        dsl.insertInto(GRAPHITRON_ARGUMENT)
            .columns(GRAPHITRON_ARGUMENT.GRAPH_NAME, GRAPHITRON_ARGUMENT.TYPE_NAME,
                GRAPHITRON_ARGUMENT.FIELD_NAME, GRAPHITRON_ARGUMENT.ARGUMENT_NAME,
                GRAPHITRON_ARGUMENT.COORDINATE, GRAPHITRON_ARGUMENT.ORDINAL,
                GRAPHITRON_ARGUMENT.TYPE_SDL, GRAPHITRON_ARGUMENT.NAMED_TYPE,
                GRAPHITRON_ARGUMENT.NON_NULL, GRAPHITRON_ARGUMENT.IS_LIST,
                GRAPHITRON_ARGUMENT.ITEM_NON_NULL, GRAPHITRON_ARGUMENT.DEFAULT_VALUE_SDL,
                GRAPHITRON_ARGUMENT.DESCRIPTION)
            .select(dsl
                .select(authored.GRAPH_NAME, authored.TYPE_NAME, authored.FIELD_NAME,
                    authored.ARGUMENT_NAME, authored.COORDINATE, authored.ORDINAL,
                    authored.TYPE_SDL, authored.NAMED_TYPE, authored.NON_NULL, authored.IS_LIST,
                    authored.ITEM_NON_NULL, authored.DEFAULT_VALUE_SDL, authored.DESCRIPTION)
                .from(authored)
                .where(authored.GRAPH_NAME.eq(graphName))
                .unionAll(dsl
                    .select(minted.GRAPH_NAME, minted.TYPE_NAME, minted.FIELD_NAME,
                        minted.ARGUMENT_NAME, minted.COORDINATE, minted.ORDINAL,
                        minted.TYPE_SDL, minted.NAMED_TYPE, minted.NON_NULL, minted.IS_LIST,
                        minted.ITEM_NON_NULL, minted.DEFAULT_VALUE_SDL, minted.DESCRIPTION)
                    .from(minted)
                    .where(minted.GRAPH_NAME.eq(graphName))))
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
