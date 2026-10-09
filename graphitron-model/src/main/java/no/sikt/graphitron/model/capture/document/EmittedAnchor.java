package no.sikt.graphitron.model.capture.document;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION_ARG_AUTHORED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION_ARG_CONFIGURED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION_ARG_MINTED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION_AUTHORED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION_CONFIGURED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION_MINTED;
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
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_MINTED_CANDIDATE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_MINTED_CANDIDATE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_CONFLICT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_MINTED_CANDIDATE;
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
 * The anchor phase of macro expansion: the schema elements the generator emits, which are what the
 * author declared unioned with what {@code graphitron_connection_carrier} minted.
 *
 * <p>A phase rather than a stage beside the expansion, on the shape the other gatherers already
 * have: each transcribes, then anchors what it transcribed, the anchoring living in a class of its
 * own beside the transcription and reached through the gatherer's own {@code anchor}. Half the
 * input here is the authored transcription and that does not make it the transcription's: the
 * emitted population exists because expansion adds coordinates no document declares, and without
 * the mint these relations would be a copy of the {@code graphql_} anchors under another name.
 *
 * <p>The anchor is the union of named sets, one view per population, so what a set holds and what
 * admits it are readable without reading the others. It also owns its own lifetime: every row
 * carries the reading that wrote it, and the phase ends by dropping this graph's coordinates
 * carrying any other instant, children first. A relation whose lifetime is a property of whatever
 * pass runs around it cannot move to another pass.
 */
public final class EmittedAnchor {

    private EmittedAnchor() {}

    private static final String REPLACE = "REPLACE";

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
        // After the sweep, so the sets join only the coordinates this reading kept.
        directives(dsl, graphName);
    }

    /**
     * The directive grain, on {@link #elements}' terms: three sets, what a document states, what the
     * configuration applies and what a macro adds, unioned into the anchor and then their arguments
     * the same way.
     *
     * <p>Cleared and refilled rather than upserted, which the element grains cannot be: nothing keys
     * into these rows but their arguments, which go with them, so emptying the graph's applications
     * takes nothing else, and an application the author removed from a coordinate that still stands
     * has to stop being a row.
     */
    private static void directives(DSLContext dsl, String graphName) {
        var t = GRAPHITRON_DIRECTIVE_APPLICATION;
        dsl.deleteFrom(t).where(t.GRAPH_NAME.eq(graphName)).execute();
        var authored = GRAPHITRON_DIRECTIVE_APPLICATION_AUTHORED;
        var configured = GRAPHITRON_DIRECTIVE_APPLICATION_CONFIGURED;
        var minted = GRAPHITRON_DIRECTIVE_APPLICATION_MINTED;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.COORDINATE, t.DIRECTIVE_NAME, t.ORDINAL, t.ORIGIN,
                t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN)
            .select(dsl
                .select(authored.GRAPH_NAME, authored.COORDINATE, authored.DIRECTIVE_NAME,
                    authored.ORDINAL, authored.ORIGIN, authored.SOURCE_NAME, authored.SOURCE_LINE,
                    authored.SOURCE_COLUMN)
                .from(authored)
                .where(authored.GRAPH_NAME.eq(graphName))
                .unionAll(dsl
                    .select(configured.GRAPH_NAME, configured.COORDINATE, configured.DIRECTIVE_NAME,
                        configured.ORDINAL, configured.ORIGIN, configured.SOURCE_NAME,
                        configured.SOURCE_LINE, configured.SOURCE_COLUMN)
                    .from(configured)
                    .where(configured.GRAPH_NAME.eq(graphName)))
                .unionAll(dsl
                    .select(minted.GRAPH_NAME, minted.COORDINATE, minted.DIRECTIVE_NAME,
                        minted.ORDINAL, minted.ORIGIN, minted.SOURCE_NAME, minted.SOURCE_LINE,
                        minted.SOURCE_COLUMN)
                    .from(minted)
                    .where(minted.GRAPH_NAME.eq(graphName))))
            .execute();
        var a = GRAPHITRON_DIRECTIVE_APPLICATION_ARG;
        var authoredArg = GRAPHITRON_DIRECTIVE_APPLICATION_ARG_AUTHORED;
        var configuredArg = GRAPHITRON_DIRECTIVE_APPLICATION_ARG_CONFIGURED;
        var mintedArg = GRAPHITRON_DIRECTIVE_APPLICATION_ARG_MINTED;
        dsl.insertInto(a)
            .columns(a.GRAPH_NAME, a.COORDINATE, a.DIRECTIVE_NAME, a.ORDINAL,
                a.DIRECTIVE_ARGUMENT_NAME, a.VALUE_SDL)
            .select(dsl
                .select(authoredArg.GRAPH_NAME, authoredArg.COORDINATE, authoredArg.DIRECTIVE_NAME,
                    authoredArg.ORDINAL, authoredArg.DIRECTIVE_ARGUMENT_NAME, authoredArg.VALUE_SDL)
                .from(authoredArg)
                .where(authoredArg.GRAPH_NAME.eq(graphName))
                .unionAll(dsl
                    .select(configuredArg.GRAPH_NAME, configuredArg.COORDINATE,
                        configuredArg.DIRECTIVE_NAME, configuredArg.ORDINAL,
                        configuredArg.DIRECTIVE_ARGUMENT_NAME, configuredArg.VALUE_SDL)
                    .from(configuredArg)
                    .where(configuredArg.GRAPH_NAME.eq(graphName)))
                .unionAll(dsl
                    .select(mintedArg.GRAPH_NAME, mintedArg.COORDINATE, mintedArg.DIRECTIVE_NAME,
                        mintedArg.ORDINAL, mintedArg.DIRECTIVE_ARGUMENT_NAME, mintedArg.VALUE_SDL)
                    .from(mintedArg)
                    .where(mintedArg.GRAPH_NAME.eq(graphName))))
            .execute();
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
     * <p>This is the lifecycle the walk's clear used to stand in for: an element this reading
     * stopped deriving goes here, and its directive applications go with it through their cascade.
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
        contested(dsl, graphName, GRAPHITRON_TYPE_MINTED_CANDIDATE.GRAPH_NAME,
            GRAPHITRON_TYPE_MINTED_CANDIDATE.COORDINATE, GRAPHITRON_TYPE_MINTED_CANDIDATE,
            "NAMED_TYPE");
        contested(dsl, graphName, GRAPHITRON_FIELD_MINTED_CANDIDATE.GRAPH_NAME,
            GRAPHITRON_FIELD_MINTED_CANDIDATE.COORDINATE, GRAPHITRON_FIELD_MINTED_CANDIDATE,
            "FIELD");
        contested(dsl, graphName, GRAPHITRON_ARGUMENT_MINTED_CANDIDATE.GRAPH_NAME,
            GRAPHITRON_ARGUMENT_MINTED_CANDIDATE.COORDINATE, GRAPHITRON_ARGUMENT_MINTED_CANDIDATE,
            "FIELD_ARGUMENT");
    }

    /**
     * Records the coordinates one candidate set states more than one way.
     *
     * <p>A count of distinct payloads rather than of rows: the candidate views distinct their whole
     * projection, so two carriers stating one shared type identically are already one row, and what
     * survives to be counted here is genuine disagreement. Nothing is picked; the coordinate is
     * withheld from the set and a row here says how many ways the schema disagreed with itself.
     */
    private static void contested(DSLContext dsl, String graphName,
                                  org.jooq.Field<String> graphColumn,
                                  org.jooq.Field<String> coordinateColumn,
                                  org.jooq.Table<?> candidates, String elementKind) {
        dsl.insertInto(GRAPHITRON_MINTED_CONFLICT)
            .columns(GRAPHITRON_MINTED_CONFLICT.GRAPH_NAME, GRAPHITRON_MINTED_CONFLICT.COORDINATE,
                GRAPHITRON_MINTED_CONFLICT.ELEMENT_KIND, GRAPHITRON_MINTED_CONFLICT.VARIANTS)
            .select(dsl
                .select(graphColumn, coordinateColumn,
                    org.jooq.impl.DSL.val(elementKind, GRAPHITRON_MINTED_CONFLICT.ELEMENT_KIND),
                    org.jooq.impl.DSL.count().cast(org.jooq.impl.SQLDataType.INTEGER))
                .from(candidates)
                .where(graphColumn.eq(graphName))
                .groupBy(graphColumn, coordinateColumn)
                .having(org.jooq.impl.DSL.count().gt(1)))
            .execute();
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
     * <p>The authored set excludes nothing. That is the minting rule showing up as absence: no arm of
     * the type mint replaces, so an author's declaration always survives, and a view saying so with
     * an exclusion that excludes nothing would read as though something could.
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
            // On the key, not onDuplicateKeyUpdate: with the UNIQUE coordinate beside the key jOOQ
            // renders H2's MERGE ON as key-or-coordinate, which no index serves. The CHECK that
            // the coordinate spells the key keeps the coordinate arm from ever matching alone.
            .onConflict(GRAPHITRON_FIELD.GRAPH_NAME, GRAPHITRON_FIELD.TYPE_NAME,
                GRAPHITRON_FIELD.FIELD_NAME)
            .doUpdate()
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
            // On the key, for the field grain's reason above.
            .onConflict(GRAPHITRON_ARGUMENT.GRAPH_NAME, GRAPHITRON_ARGUMENT.TYPE_NAME,
                GRAPHITRON_ARGUMENT.FIELD_NAME, GRAPHITRON_ARGUMENT.ARGUMENT_NAME)
            .doUpdate()
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
