package no.sikt.graphitron.model.derive;

import org.jooq.CommonTableExpression;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.DataType;
import org.jooq.Field;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Select;
import org.jooq.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.denseRank;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.partitionBy;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.SQLDataType.INTEGER;

/**
 * The walk all three {@code @reference} target relations are: the recursion that follows an
 * element's arrival into the next element's departure, the ranking over it, the two arities beside
 * every row and the closing arm filter.
 *
 * <p>One builder rather than three spellings, and the reason is that the schema's own reason for
 * tolerating the duplication does not apply here. Two hop views carry the same body arm for arm
 * because SQL cannot parameterize a coordinate; Java can, so a third caller of one rule is a
 * parameter rather than a copy, and the hazards below are stated once where an implementer meets
 * them instead of once per relation with no enforcer between them.
 *
 * <p>What differs between the three callers is the coordinate: which columns identify an element,
 * which of them the hop carries and which the seed hands down, and what the arities are taken over.
 * What does not differ is everything in this class.
 */
public final class ReferenceStepWalk {

    /**
     * The columns every walk carries forward out of its hop relation unchanged, in the order it
     * carries them. A chain's columns are its caller's coordinate prefix followed by these.
     */
    public static final List<String> HOP_COLUMNS = List.of(
        "position", "via", "key_matched_by",
        "from_source_name", "from_schema", "from_table",
        "to_source_name", "to_schema", "to_table", "constraint_name", "fk_on_from");

    /** The three columns a keyless arm does not have, a route no foreign key identifies. */
    private static final Set<String> KEYED_ONLY =
        Set.of("key_matched_by", "constraint_name", "fk_on_from");

    /**
     * One walk's coordinate.
     *
     * @param prefix   the columns identifying an element, in the order the chain carries them,
     *                 ahead of {@link #HOP_COLUMNS}
     * @param carried  the prefix columns the recursive term takes from the accumulated chain rather
     *                 than from the hop, which is how a departure the seed resolved is handed down
     *                 a walk whose hop does not carry it
     * @param hop      the relation the recursion joins, one row per locally expressible hop
     * @param seed     the walk's first elements, projecting {@code prefix} then
     *                 {@link #HOP_COLUMNS}, in that order and with those types
     */
    public record Coordinate(List<String> prefix, List<String> carried, Table<?> hop,
                             Select<? extends Record> seed) {

        public Coordinate {
            prefix = List.copyOf(prefix);
            carried = List.copyOf(carried);
        }

        /** The chain's own columns, in the order every statement here carries them. */
        List<String> chainColumns() {
            var columns = new ArrayList<>(prefix);
            columns.addAll(HOP_COLUMNS);
            return columns;
        }

        /** The prefix columns the hop itself carries, which the recursive term joins on. */
        List<String> joined() {
            return prefix.stream().filter(column -> !carried.contains(column)).toList();
        }

        /** What both arities are taken over: the element's whole coordinate, position included. */
        List<String> partition() {
            var columns = new ArrayList<>(prefix);
            columns.add("position");
            return columns;
        }

        /** A chain column's type, read off the seed that projects it. */
        DataType<?> typeOf(String column) {
            return seed.field(chainColumns().indexOf(column)).getDataType();
        }
    }

    private ReferenceStepWalk() {}

    /**
     * The counted walk with one arm taken out of it, in the shape that arm's table stores: the
     * keyed arm projects every chain column, the keyless one drops the three a route with no
     * foreign key does not have, and both close with the two arities.
     *
     * <p>One text for both arms, so the two statements filling one relation's two tables cannot
     * drift apart about what the chain is or what the arities are over.
     */
    public static Select<? extends Record> walked(DSLContext dsl, Coordinate coordinate,
                                                  boolean keyedArm) {
        Name chain = name("chain");
        var counted = counted(dsl, coordinate, chain).asTable("counted");
        List<Field<?>> projected = new ArrayList<>();
        for (String column : coordinate.chainColumns()) {
            if (!keyedArm && KEYED_ONLY.contains(column)) {
                continue;
            }
            projected.add(counted.field(column));
        }
        projected.add(counted.field("targets"));
        projected.add(counted.field("candidates"));

        var arm = counted.field("via", String.class).in(keyedArm
            ? List.of("KEY", "TABLE") : List.of("NAME_MATCH", "CONDITION"));
        return dsl.withRecursive(chainOf(dsl, coordinate, chain))
            .select(projected)
            .from(counted)
            .where(arm);
    }

    /**
     * The ranked chain with both arities beside every row, each over the element's whole partition
     * and both arms.
     *
     * <p>A level of its own rather than columns of the level above it, and that is the whole point
     * of the nesting: a {@code WHERE} is evaluated before the window functions of the
     * {@code SELECT} it sits in, so an arm filter beside these two would count the arm where the
     * relation means to count the element. Filtering outside the ranking is not enough on its own,
     * because the ranking is not where the arities are computed.
     */
    private static Select<? extends Record> counted(DSLContext dsl, Coordinate coordinate,
                                                    Name chain) {
        var ranked = ranked(dsl, coordinate, chain).asTable("ranked");
        List<Field<?>> projected = new ArrayList<>();
        coordinate.chainColumns().forEach(column -> projected.add(ranked.field(column)));
        List<Field<?>> partition = new ArrayList<>();
        coordinate.partition().forEach(column -> partition.add(ranked.field(column)));
        projected.add(org.jooq.impl.DSL.max(ranked.field("target_rank", Integer.class))
            .over(partitionBy(partition)).cast(INTEGER).as("targets"));
        projected.add(count().over(partitionBy(partition)).cast(INTEGER).as("candidates"));
        return dsl.select(projected).from(ranked);
    }

    /** The finished chain with each element's arrivals ranked, which is what both counts read. */
    private static Select<? extends Record> ranked(DSLContext dsl, Coordinate coordinate,
                                                   Name chain) {
        Name walked = name("walked");
        List<Field<?>> projected = new ArrayList<>();
        coordinate.chainColumns()
            .forEach(column -> projected.add(chainField(walked, column, coordinate.typeOf(column))));
        List<Field<?>> partition = new ArrayList<>();
        coordinate.partition()
            .forEach(column -> partition.add(chainField(walked, column, coordinate.typeOf(column))));
        projected.add(denseRank().over(partitionBy(partition)
                .orderBy(chainField(walked, "to_source_name", coordinate.typeOf("to_source_name")),
                    chainField(walked, "to_schema", coordinate.typeOf("to_schema")),
                    chainField(walked, "to_table", coordinate.typeOf("to_table"))))
            .as("target_rank"));
        return dsl.select(projected).from(distinctChain(dsl, coordinate, chain).asTable(walked));
    }

    /**
     * The chain as a set, which the recursion does not deliver on its own.
     *
     * <p>An element reached by two routes to one arrival puts that arrival in the chain twice, one
     * row per route, and the recursive term then joins the next element's hops to each of them, so
     * the next position holds every one of its hops once per route that reached its departure. The
     * engine evaluates the recursive {@code UNION} without removing a row the same iteration
     * produced twice, so those copies survive into the result. Every chain column is part of a
     * hop's identity, so a copy is a duplicate and not a second answer: collapsing them here is what
     * lets the keyed arm accept the rows and keeps {@code candidates} a count of routes rather than
     * of the paths that led to them.
     */
    private static Select<? extends Record> distinctChain(DSLContext dsl, Coordinate coordinate,
                                                          Name chain) {
        List<Field<?>> columns = new ArrayList<>();
        coordinate.chainColumns()
            .forEach(column -> columns.add(chainField(chain, column, coordinate.typeOf(column))));
        return dsl.selectDistinct(columns).from(table(chain));
    }

    /**
     * The chain itself: the seed's elements, then every hop whose departure is a reached arrival
     * one position further along.
     */
    private static CommonTableExpression<?> chainOf(DSLContext dsl, Coordinate coordinate,
                                                    Name chain) {
        var previous = table(chain).as("p");
        var step = coordinate.hop().as("step");

        List<Field<?>> projected = new ArrayList<>();
        for (String column : coordinate.chainColumns()) {
            projected.add(coordinate.carried().contains(column)
                ? walkedField(previous, column, coordinate.typeOf(column))
                : hopColumn(step, column));
        }

        Condition on = noCondition();
        for (String column : coordinate.joined()) {
            on = on.and(equal(hopColumn(step, column),
                walkedField(previous, column, coordinate.typeOf(column))));
        }
        on = on.and(equal(hopColumn(step, "position"),
            walkedField(previous, "position", INTEGER).plus(1)));
        for (String part : List.of("source_name", "schema", "table")) {
            on = on.and(equal(hopColumn(step, "from_" + part),
                walkedField(previous, "to_" + part, coordinate.typeOf("to_" + part))));
        }

        var recursive = dsl.select(projected).from(previous).join(step).on(on);
        return chain.fields(coordinate.chainColumns().toArray(String[]::new))
            .as(union(coordinate.seed(), recursive));
    }

    /**
     * The two terms as one walk. Raw here rather than generic because the seed's row type is the
     * caller's and the recursive term's is this builder's, and jOOQ's {@code union} wants them
     * equal; they agree column for column by construction, the seed projecting the chain's columns
     * in the chain's order, which is the contract {@link Coordinate#seed()} states.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Select<?> union(Select<?> seed, Select<?> recursive) {
        return ((Select) seed).union(recursive);
    }

    /**
     * One column of the hop relation, by the name the chain spells it with.
     *
     * <p>Case-folded rather than looked up as given, because the two sides spell a column
     * differently and neither spelling is wrong: the chain names its columns as the schema declares
     * them, in lower case, and a generated table names them as the engine's catalog reports them,
     * in upper. A miss is a defect in a coordinate rather than anything a run can cause, so it
     * throws here instead of returning null into a predicate that would fail three frames later
     * with nothing naming the column.
     */
    private static Field<?> hopColumn(Table<?> hop, String column) {
        Field<?> found = hop.field(column);
        if (found == null) {
            found = hop.field(column.toUpperCase(java.util.Locale.ROOT));
        }
        if (found == null) {
            throw new IllegalStateException("the hop relation " + hop.getName() + " has no column "
                + column + ", which this walk's coordinate carries; its columns are "
                + hop.fieldStream().map(Field::getName).toList());
        }
        return found;
    }

    /** One column of the chain, qualified by the expression's own name. */
    private static Field<?> chainField(Name chain, String column, DataType<?> type) {
        return field(chain.append(column), type);
    }

    /** One column of the accumulated chain, qualified by the alias the recursive term joins it as. */
    private static <T> Field<T> walkedField(Table<?> previous, String column, DataType<T> type) {
        return field(name(previous.getName(), column), type);
    }

    /** Equality between two columns the builder knows only by name. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Condition equal(Field<?> left, Field<?> right) {
        return ((Field) left).eq(right);
    }
}
