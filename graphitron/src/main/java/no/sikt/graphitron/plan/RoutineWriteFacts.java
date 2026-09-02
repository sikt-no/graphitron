package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.CatalogColumn;
import no.sikt.graphitron.command.CatalogTable;
import no.sikt.graphitron.command.JoinBasis;
import no.sikt.graphitron.command.JoinCondition;
import no.sikt.graphitron.command.KeyPair;
import no.sikt.graphitron.command.ReservedAliases;
import no.sikt.graphitron.command.RoutineCall;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.tables.IntentFieldRoutineMethod;
import no.sikt.graphitron.model.tables.IntentMutationRoutineSeat;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Records;
import org.jooq.Select;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.INTENT_CARRIER_DATA_FIELD;
import static no.sikt.graphitron.model.Tables.INTENT_CARRIER_ROUTINE_HOP;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_CHAIN_NODE;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_ROUTINE_METHOD;
import static no.sikt.graphitron.model.Tables.INTENT_FOREIGN_KEY_COLUMN_PAIR;
import static no.sikt.graphitron.model.Tables.INTENT_MUTATION_ROUTINE_SEAT;
import static no.sikt.graphitron.model.Tables.INTENT_NAME_MATCHED_KEY_PAIR;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE_PARAMETER;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.when;

/**
 * What the store states about a graph's {@code @routine}-writing mutation coordinates: the
 * routine-write command relation's whole catalog input, read as facts rather than assembled from a
 * walk over a classified schema.
 *
 * <p>Three statements at three grains, which is the rule and not a tuning: a coordinate, a chain
 * hop's column pairing and a carrier's captured pairing are three sentences about three keys. The
 * coordinate statement drives from {@code intent_mutation_routine_seat}, where membership lives, and
 * carries the routine's arguments and the chain's hops as correlated {@code MULTISET}s, each
 * belonging to exactly one coordinate. The two pairings are their own statements paired back on keys
 * the store itself declares rather than nested a level deeper, because each is a union over two
 * relations and the union reads once here instead of twice inside a projection.
 *
 * <p>Nothing here decides anything. Membership is the seat relation's verdict, the chain's shape is
 * the node relation's, a pairing is the pair relations'. What this class adds is the narrowing into
 * the command tier's vocabulary and one minted name, the chain hop's table alias, whose formula has
 * one home in {@link ReservedAliases}.
 *
 * <p>Every catalog name a row carries is captured rather than derived: a table's generated class
 * from {@code sql_table.class_fqn}, its schema's constants class from
 * {@code sql_schema.tables_class_fqn} and the {@code Keys} class beside it, a foreign key's
 * generated constant from {@code sql_constraint.jooq_name}. That is what lets these rows be built
 * with neither the emit library nor a live catalog, which is the property the plan tier is shaped
 * around.
 *
 * <p><b>Cost.</b> The hop {@code MULTISET} correlates {@code intent_field_chain_node}, a recursive
 * walk, once per admitted coordinate. That population is the graph's routine-writing mutation
 * fields, which is small by construction; a reader tempted to hang a second correlation of that
 * relation off a wider driving row should read the derived-reads rule in
 * {@code docs/architecture/explanation/fact-model.adoc} first.
 */
public final class RoutineWriteFacts {

    /** The seat a coordinate's routine write sits in, as {@code intent_mutation_routine_seat} states it. */
    public enum Seat {
        /** The write re-reads its committed row by walking the field's own {@code @reference} chain. */
        CHAIN,
        /** The write returns a payload carrier whose data field re-reads by name-matched key. */
        CARRIER
    }

    /**
     * One hop of a chain re-read, after the routine node the chain departs from.
     *
     * <p>{@code seq} is the store's own position in the chain, counting the routine node as zero, so
     * the first {@code @reference} hop is one. The alias is minted from it rather than carried,
     * every hop of one field sharing the field's name and differing in the index.
     */
    public record Hop(int seq, String alias, CatalogTable table, JoinBasis on, JoinCondition filter) {

        public Hop {
            Objects.requireNonNull(alias, "alias");
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(on, "on");
        }
    }

    /**
     * One admitted routine-write coordinate, with everything its emission reads except the names a
     * generated-unit holder mints and the two folds the store does not yet state (the error
     * channel's minted constant and the run's tenant binding).
     *
     * @param typeName       the mutation root type the field is declared on
     * @param fieldName      the field's own name
     * @param seat           which of the two shapes the write takes
     * @param returnTypeName the SDL type the field returns, unwrapped
     * @param listReturn     whether the emitted fetcher delivers many: the field's own wrapper on
     *                       the chain seat and the carrier's data field's on the other, those being
     *                       the two places the cardinality claim actually sits
     * @param call           the routine call the write performs
     * @param hops           the chain hops after the routine node, in chain order; empty on the
     *                       carrier seat, whose re-read takes no chain
     * @param targetTable    the table the carrier's data field re-reads; null on the chain seat,
     *                       where the hops name their own tables
     * @param capturedPairs  the carrier's name-matched pairing, source side on the routine result;
     *                       empty on the chain seat
     */
    public record Row(String typeName, String fieldName, Seat seat, String returnTypeName,
                      boolean listReturn, RoutineCall call, List<Hop> hops,
                      CatalogTable targetTable, List<KeyPair> capturedPairs) {

        public Row {
            hops = List.copyOf(hops);
            capturedPairs = List.copyOf(capturedPairs);
        }
    }

    /**
     * The pass's typed product: every admitted coordinate, in coordinate order. Empty both for a
     * graph that writes through no routine and for a run with no store to read, which the caller
     * tells apart and this does not.
     */
    public record Rows(List<Row> rows) {

        public Rows {
            rows = List.copyOf(rows);
        }

        /** The empty product, for a caller producing a plan with no store behind it. */
        public static Rows empty() {
            return new Rows(List.of());
        }
    }

    private RoutineWriteFacts() {}

    /**
     * Reads every routine-write coordinate the store admits, in coordinate order.
     *
     * <p>A coordinate whose seat verdict is anything but {@code ADMITTED} has no row here, and that
     * is the whole of membership: the refusing verdicts are the store's, each stating why that shape
     * is not a routine write, and nothing is re-decided on this side.
     */
    public static Rows read(StoreHandle store) {
        var hopPairs = hopPairs(store);
        var carrierPairs = carrierPairs(store);
        return new Rows(coordinateRows(store).stream()
            .map(raw -> new Row(raw.typeName(), raw.fieldName(), raw.seat(), raw.returnTypeName(),
                raw.listReturn(), raw.call(),
                raw.hops().stream()
                    .map(hop -> hop.resolve(raw.typeName(), raw.fieldName(), hopPairs))
                    .toList(),
                raw.targetTable(),
                carrierPairs.getOrDefault(new Coordinate(raw.typeName(), raw.fieldName()), List.of())))
            .toList());
    }

    private record Coordinate(String typeName, String fieldName) {}

    private record HopKey(String typeName, String fieldName, int seq) {}

    /** A coordinate as read, its hops still carrying an unresolved pairing. */
    private record RawRow(String typeName, String fieldName, Seat seat, String returnTypeName,
                          boolean listReturn, RoutineCall call, List<RawHop> hops,
                          CatalogTable targetTable) {}

    /**
     * A hop as read: everything but the column pairs, which arrive from the pairing statement keyed
     * by this hop's position in the chain.
     */
    private record RawHop(int seq, String stepVia, String alias, CatalogTable table,
                          String keysClassName, String keyConstantName, JoinCondition filter) {

        /**
         * The hop with its pairing joined in, or the refusal for a hop the store keys by pairs and
         * states none for: a join on no pair re-reads the whole table rather than the row the write
         * committed, so this stops at the mint instead of emitting it.
         */
        Hop resolve(String typeName, String fieldName, Map<HopKey, List<KeyPair>> pairs) {
            var found = pairs.get(new HopKey(typeName, fieldName, seq));
            if (found == null || found.isEmpty()) {
                throw new IllegalStateException(
                    "the routine-write re-read for " + typeName + "." + fieldName + " reaches hop "
                    + seq + " at alias '" + alias + "', resolved through " + stepVia + ", and the store"
                    + " states no column pairing for it; joining on no pair would re-read the whole"
                    + " of " + table.sqlName() + " rather than the row the write committed");
            }
            return new Hop(seq, alias, table, new JoinBasis.ColumnPairs(keying(typeName, fieldName), found),
                filter);
        }

        /**
         * How the hop is keyed, total over the node relation's own resolution vocabulary. A hop
         * resolved through a named key, or through a table whose endpoints one foreign key connects,
         * is keyed by that key; one departing a function result is keyed by name against the
         * arriving primary key, a function declaring no constraint to name.
         */
        private JoinBasis.Keying keying(String typeName, String fieldName) {
            return switch (stepVia) {
                case "KEY", "TABLE" -> {
                    if (keysClassName == null || keyConstantName == null) {
                        throw new IllegalStateException(
                            "hop " + seq + " of " + typeName + "." + fieldName + " joins on a"
                            + " foreign key the generated model publishes no constant for, so the"
                            + " emitted join would name a constant that does not exist");
                    }
                    yield new JoinBasis.Keying.ForeignKey(keysClassName, keyConstantName);
                }
                case "NAME_MATCH" -> new JoinBasis.Keying.NameMatched();
                default -> throw new IllegalStateException(
                    "the chain node relation resolved hop " + seq + " of " + typeName + "."
                    + fieldName + " through '" + stepVia + "', which is outside the vocabulary a"
                    + " post-commit re-read can join on");
            };
        }
    }

    // -------------------------------------------------------------------------------------
    // Statement one: the coordinate grain.
    // -------------------------------------------------------------------------------------

    /**
     * Every admitted coordinate with its call, its hops and, on the carrier seat, the table its data
     * field re-reads.
     *
     * <p>The call surface, the routine's result table and that table's constants class are inner
     * joins: an admitted seat resolves a unique call surface by the verdict's own construction, so a
     * missing row on any of them means the verdict and the resolution disagree, and dropping the
     * coordinate silently is worse than the seat count not matching. The carrier arm's joins are
     * left joins for the opposite reason: a chain-seated coordinate has no carrier hop, and that
     * absence is the shape rather than a gap.
     */
    private static List<RawRow> coordinateRows(StoreHandle store) {
        var s = INTENT_MUTATION_ROUTINE_SEAT;
        var rm = INTENT_FIELD_ROUTINE_METHOD;
        var rt = SQL_TABLE.as("routine_result");
        var rs = SQL_SCHEMA.as("routine_schema");
        var ch = INTENT_CARRIER_ROUTINE_HOP;
        var ct = SQL_TABLE.as("carrier_target");
        var cs = SQL_SCHEMA.as("carrier_schema");
        var cd = INTENT_CARRIER_DATA_FIELD;
        var df = GRAPHQL_FIELD.as("carrier_data_field");
        var mf = GRAPHQL_FIELD.as("mutation_field");
        return store.dsl()
            .select(s.TYPE_NAME, s.FIELD_NAME, s.SEAT, s.RETURN_TYPE_NAME,
                mf.IS_LIST, df.IS_LIST,
                rm.CLASS_NAME, rm.METHOD_NAME,
                rt.TABLE_NAME, rt.JOOQ_NAME, rt.CLASS_FQN, rs.TABLES_CLASS_FQN,
                arguments(s, rm),
                hops(s),
                ct.TABLE_NAME, ct.JOOQ_NAME, ct.CLASS_FQN, cs.TABLES_CLASS_FQN)
            .from(s)
            .join(mf).on(mf.GRAPH_NAME.eq(s.GRAPH_NAME), mf.TYPE_NAME.eq(s.TYPE_NAME),
                mf.FIELD_NAME.eq(s.FIELD_NAME))
            .join(rm).on(rm.GRAPH_NAME.eq(s.GRAPH_NAME), rm.TYPE_NAME.eq(s.TYPE_NAME),
                rm.FIELD_NAME.eq(s.FIELD_NAME), rm.ORDINAL.eq(s.ORDINAL), rm.CANDIDATES.eq(1))
            .join(rt).on(rt.SOURCE_NAME.eq(rm.SOURCE_NAME), rt.TABLE_SCHEMA.eq(rm.TABLE_SCHEMA),
                rt.TABLE_NAME.eq(rm.ROUTINE_NAME))
            .join(rs).on(rs.SOURCE_NAME.eq(rt.SOURCE_NAME), rs.TABLE_SCHEMA.eq(rt.TABLE_SCHEMA))
            .leftJoin(cd).on(cd.GRAPH_NAME.eq(s.GRAPH_NAME), cd.TYPE_NAME.eq(s.RETURN_TYPE_NAME),
                cd.FAMILY.eq("ROUTINE"), cd.DATA_FIELDS.eq(1))
            .leftJoin(df).on(df.GRAPH_NAME.eq(cd.GRAPH_NAME), df.TYPE_NAME.eq(cd.TYPE_NAME),
                df.FIELD_NAME.eq(cd.FIELD_NAME))
            .leftJoin(ch).on(ch.GRAPH_NAME.eq(cd.GRAPH_NAME), ch.TYPE_NAME.eq(cd.TYPE_NAME),
                ch.FIELD_NAME.eq(cd.FIELD_NAME), ch.CANDIDATES.eq(1))
            .leftJoin(ct).on(ct.SOURCE_NAME.eq(ch.TO_SOURCE_NAME),
                ct.TABLE_SCHEMA.eq(ch.TO_SCHEMA), ct.TABLE_NAME.eq(ch.TO_TABLE))
            .leftJoin(cs).on(cs.SOURCE_NAME.eq(ct.SOURCE_NAME), cs.TABLE_SCHEMA.eq(ct.TABLE_SCHEMA))
            .where(s.GRAPH_NAME.eq(store.graphName()), s.VERDICT.eq("ADMITTED"))
            .orderBy(s.TYPE_NAME, s.FIELD_NAME)
            .fetch(RoutineWriteFacts::rawRow);
    }

    private static RawRow rawRow(org.jooq.Record18<String, String, String, String, Boolean, Boolean,
            String, String, String, String, String, String, List<RoutineCall.RoutineArgument>,
            List<RawHop>, String, String, String, String> row) {
        var seat = Seat.valueOf(row.value3());
        boolean chain = seat == Seat.CHAIN;
        return new RawRow(row.value1(), row.value2(), seat, row.value4(),
            Boolean.TRUE.equals(chain ? row.value5() : row.value6()),
            new RoutineCall(row.value7(), row.value8(),
                new CatalogTable(row.value9(), row.value10(), row.value11(), row.value12()),
                row.value13()),
            chain ? row.value14() : List.of(),
            chain ? null : new CatalogTable(row.value15(), row.value16(), row.value17(),
                row.value18()));
    }

    /**
     * The routine's IN parameters in declaration order, each bound to the request value that
     * supplies it.
     *
     * <p>The binding is the author's {@code argMapping} entry for the parameter, or, where the
     * author wrote none, the parameter's own name: a routine call identity-binds every parameter the
     * author did not spell to the field argument of that name, so an absent pair row is a binding
     * rather than a hole. The coalesce is that rule. A parameter that names no argument either way
     * is a rejection the validator makes before a plan is produced, so it cannot arrive here.
     */
    private static Field<List<RoutineCall.RoutineArgument>> arguments(
            IntentMutationRoutineSeat s, IntentFieldRoutineMethod rm) {
        var p = SQL_ROUTINE_PARAMETER;
        var m = GRAPHITRON_ARGMAPPING_ENTRY;
        return multiset(
            select(p.JOOQ_NAME, p.BINDING_TYPE,
                coalesce(
                    field(select(m.ARGUMENT_PATH)
                        .from(m)
                        .where(m.GRAPH_NAME.eq(s.GRAPH_NAME), m.SITE.eq("ROUTINE"),
                            m.TYPE_NAME.eq(s.TYPE_NAME),
                            m.FIELD_NAME.eq(s.FIELD_NAME), m.ORDINAL.eq(s.ORDINAL),
                            m.PARAM_NAME.eq(p.JOOQ_NAME))
                        .orderBy(m.POSITION)
                        .limit(1)),
                    p.JOOQ_NAME))
                .from(p)
                .where(p.SOURCE_NAME.eq(rm.SOURCE_NAME), p.TABLE_SCHEMA.eq(rm.TABLE_SCHEMA),
                    p.ROUTINE_NAME.eq(rm.ROUTINE_NAME))
                .orderBy(p.POSITION))
            .convertFrom(r -> r.map(Records.mapping(RoutineCall.RoutineArgument::new)));
    }

    /**
     * The chain's hops after its routine node, in chain order, each with its arriving table, its
     * keying and its {@code condition:} filter.
     *
     * <p>Sequence one and up: the node relation numbers the routine node zero, and re-invoking that
     * node after the commit would re-execute the write, so it is not a hop of this re-read at all.
     * That is the same rule the command tier states structurally by spelling no lateral join.
     *
     * <p>The {@code Keys} class comes from whichever endpoint declares the foreign key, which is
     * what {@code fk_on_from} says, and the constant name from that constraint's own row. A hop the
     * generated model publishes no constant for arrives with nulls and is refused where the row is
     * minted rather than emitted as a reference to a constant that does not exist.
     */
    private static Field<List<RawHop>> hops(IntentMutationRoutineSeat s) {
        var n = INTENT_FIELD_CHAIN_NODE;
        var t = SQL_TABLE.as("hop_target");
        var ts = SQL_SCHEMA.as("hop_target_schema");
        var k = SQL_CONSTRAINT.as("hop_key");
        var ks = SQL_SCHEMA.as("hop_key_schema");
        var st = GRAPHITRON_FIELD_REFERENCE_STEP;
        return multiset(
            select(n.SEQ, n.STEP_VIA, t.TABLE_NAME, t.JOOQ_NAME, t.CLASS_FQN, ts.TABLES_CLASS_FQN,
                ks.KEYS_CLASS_FQN, k.JOOQ_NAME, st.CLASS_NAME, st.METHOD, n.FIELD_NAME)
                .from(n)
                .join(t).on(t.SOURCE_NAME.eq(n.TO_SOURCE_NAME), t.TABLE_SCHEMA.eq(n.TO_SCHEMA),
                    t.TABLE_NAME.eq(n.TO_TABLE))
                .join(ts).on(ts.SOURCE_NAME.eq(t.SOURCE_NAME), ts.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
                .leftJoin(k).on(
                    k.SOURCE_NAME.eq(keySide(n.FK_ON_FROM, n.FROM_SOURCE_NAME, n.TO_SOURCE_NAME)),
                    k.TABLE_SCHEMA.eq(keySide(n.FK_ON_FROM, n.FROM_SCHEMA, n.TO_SCHEMA)),
                    k.TABLE_NAME.eq(keySide(n.FK_ON_FROM, n.FROM_TABLE, n.TO_TABLE)),
                    k.CONSTRAINT_NAME.eq(n.CONSTRAINT_NAME))
                .leftJoin(ks).on(ks.SOURCE_NAME.eq(k.SOURCE_NAME),
                    ks.TABLE_SCHEMA.eq(k.TABLE_SCHEMA))
                .leftJoin(st).on(st.GRAPH_NAME.eq(n.GRAPH_NAME), st.TYPE_NAME.eq(n.TYPE_NAME),
                    st.FIELD_NAME.eq(n.FIELD_NAME), st.ORDINAL.eq(n.ORDINAL),
                    st.POSITION.eq(n.POSITION))
                .where(n.GRAPH_NAME.eq(s.GRAPH_NAME), n.TYPE_NAME.eq(s.TYPE_NAME),
                    n.FIELD_NAME.eq(s.FIELD_NAME), n.SEQ.ge(1), n.CANDIDATES.eq(1))
                .orderBy(n.SEQ))
            .convertFrom(r -> r.map(RoutineWriteFacts::rawHop));
    }

    private static RawHop rawHop(Record row) {
        return new RawHop(row.get(INTENT_FIELD_CHAIN_NODE.SEQ), row.get(INTENT_FIELD_CHAIN_NODE.STEP_VIA),
            ReservedAliases.chainHop(row.get(INTENT_FIELD_CHAIN_NODE.FIELD_NAME),
                row.get(INTENT_FIELD_CHAIN_NODE.SEQ)),
            new CatalogTable(row.get(2, String.class), row.get(3, String.class),
                row.get(4, String.class), row.get(5, String.class)),
            row.get(6, String.class), row.get(7, String.class),
            condition(row.get(8, String.class), row.get(9, String.class)));
    }

    /** The endpoint that declares the hop's foreign key: the departing table's triple, or the arriving one's. */
    private static Field<String> keySide(Field<Boolean> fkOnFrom, Field<String> onFrom,
                                         Field<String> onTo) {
        return when(fkOnFrom.isTrue(), onFrom).otherwise(onTo);
    }

    // -------------------------------------------------------------------------------------
    // Statement two: the chain hop's column pairing.
    // -------------------------------------------------------------------------------------

    /**
     * Every admitted chain hop's ordered column pairing, at the pairing's own grain.
     *
     * <p>One statement over two keyings, because a pairing is one sentence however it was reached: a
     * foreign key states it positionally, a hop departing a function result states it by name
     * against the arriving primary key. The union is here rather than at two call sites for the
     * reason the pair relations themselves give for not carrying each other's rows: a pairing is
     * reachable from the triples a hop already has, and which relation answers is the hop's
     * resolution rather than a second fact.
     *
     * <p>Source side and target side are the departing and the arriving table's columns. Which of a
     * foreign key's two column lists is which depends on the endpoint that declares it, the same
     * {@code fk_on_from} the key constant is reached through.
     */
    private static Map<HopKey, List<KeyPair>> hopPairs(StoreHandle store) {
        var n = INTENT_FIELD_CHAIN_NODE;
        var fk = INTENT_FOREIGN_KEY_COLUMN_PAIR;
        var nm = INTENT_NAME_MATCHED_KEY_PAIR;
        var src = SQL_COLUMN.as("source_column");
        var tgt = SQL_COLUMN.as("target_column");
        var byKey = new LinkedHashMap<HopKey, List<KeyPair>>();
        store.dsl()
            .select(n.TYPE_NAME, n.FIELD_NAME, n.SEQ, fk.POSITION,
                src.COLUMN_NAME, src.JOOQ_NAME, src.BINDING_TYPE,
                tgt.COLUMN_NAME, tgt.JOOQ_NAME, tgt.BINDING_TYPE)
            .from(n)
            .join(fk).on(
                fk.SOURCE_NAME.eq(keySide(n.FK_ON_FROM, n.FROM_SOURCE_NAME, n.TO_SOURCE_NAME)),
                fk.TABLE_SCHEMA.eq(keySide(n.FK_ON_FROM, n.FROM_SCHEMA, n.TO_SCHEMA)),
                fk.TABLE_NAME.eq(keySide(n.FK_ON_FROM, n.FROM_TABLE, n.TO_TABLE)),
                fk.CONSTRAINT_NAME.eq(n.CONSTRAINT_NAME))
            .join(src).on(src.SOURCE_NAME.eq(n.FROM_SOURCE_NAME),
                src.TABLE_SCHEMA.eq(n.FROM_SCHEMA), src.TABLE_NAME.eq(n.FROM_TABLE),
                src.COLUMN_NAME.eq(keySide(n.FK_ON_FROM, fk.COLUMN_NAME, fk.REFERENCED_COLUMN_NAME)))
            .join(tgt).on(tgt.SOURCE_NAME.eq(n.TO_SOURCE_NAME),
                tgt.TABLE_SCHEMA.eq(n.TO_SCHEMA), tgt.TABLE_NAME.eq(n.TO_TABLE),
                tgt.COLUMN_NAME.eq(keySide(n.FK_ON_FROM, fk.REFERENCED_COLUMN_NAME, fk.COLUMN_NAME)))
            .where(n.GRAPH_NAME.eq(store.graphName()), n.SEQ.ge(1), n.CANDIDATES.eq(1),
                n.STEP_VIA.in("KEY", "TABLE"),
                org.jooq.impl.DSL.row(n.TYPE_NAME, n.FIELD_NAME).in(admittedCoordinates(store)))
            .unionAll(store.dsl()
                .select(n.TYPE_NAME, n.FIELD_NAME, n.SEQ, nm.POSITION,
                    src.COLUMN_NAME, src.JOOQ_NAME, src.BINDING_TYPE,
                    tgt.COLUMN_NAME, tgt.JOOQ_NAME, tgt.BINDING_TYPE)
                .from(n)
                .join(nm).on(nm.FROM_SOURCE_NAME.eq(n.FROM_SOURCE_NAME),
                    nm.FROM_SCHEMA.eq(n.FROM_SCHEMA), nm.FROM_TABLE.eq(n.FROM_TABLE),
                    nm.TO_SOURCE_NAME.eq(n.TO_SOURCE_NAME), nm.TO_SCHEMA.eq(n.TO_SCHEMA),
                    nm.TO_TABLE.eq(n.TO_TABLE), nm.UNMATCHED_COLUMNS.eq(0))
                .join(src).on(src.SOURCE_NAME.eq(n.FROM_SOURCE_NAME),
                    src.TABLE_SCHEMA.eq(n.FROM_SCHEMA), src.TABLE_NAME.eq(n.FROM_TABLE),
                    src.COLUMN_NAME.eq(nm.FROM_COLUMN))
                .join(tgt).on(tgt.SOURCE_NAME.eq(n.TO_SOURCE_NAME),
                    tgt.TABLE_SCHEMA.eq(n.TO_SCHEMA), tgt.TABLE_NAME.eq(n.TO_TABLE),
                    tgt.COLUMN_NAME.eq(nm.TO_COLUMN))
                .where(n.GRAPH_NAME.eq(store.graphName()), n.SEQ.ge(1), n.CANDIDATES.eq(1),
                    n.STEP_VIA.eq("NAME_MATCH"),
                    org.jooq.impl.DSL.row(n.TYPE_NAME, n.FIELD_NAME).in(admittedCoordinates(store))))
            .orderBy(1, 2, 3, 4)
            .forEach(row -> byKey
                .computeIfAbsent(new HopKey(row.value1(), row.value2(), row.value3()),
                    key -> new ArrayList<>())
                .add(pair(row.value5(), row.value6(), row.value7(),
                    row.value8(), row.value9(), row.value10())));
        return byKey;
    }

    /**
     * The coordinates the seat relation admits, as a semi-join rather than a join: the pairing reads
     * are narrowed to what this pass will emit, and a coordinate admitted twice is impossible on a
     * relation keyed by it, so the shape is a filter and never a multiplier.
     */
    private static Select<org.jooq.Record2<String, String>> admittedCoordinates(StoreHandle store) {
        var s = INTENT_MUTATION_ROUTINE_SEAT;
        return select(s.TYPE_NAME, s.FIELD_NAME)
            .from(s)
            .where(s.GRAPH_NAME.eq(store.graphName()), s.VERDICT.eq("ADMITTED"));
    }

    // -------------------------------------------------------------------------------------
    // Statement three: the carrier's captured pairing.
    // -------------------------------------------------------------------------------------

    /**
     * The carrier seat's captured key pairing, at the pairing's own grain: the routine result's
     * column of each name beside the arriving table's primary-key column of that name, in key order.
     *
     * <p>A total pairing is demanded ({@code unmatched_columns = 0}), which is the carrier's own rule
     * rather than a narrowing made here: a capture missing one column of the key filters on a
     * partial key, and the re-read then returns a row the write did not commit.
     */
    private static Map<Coordinate, List<KeyPair>> carrierPairs(StoreHandle store) {
        var s = INTENT_MUTATION_ROUTINE_SEAT;
        var cd = INTENT_CARRIER_DATA_FIELD;
        var ch = INTENT_CARRIER_ROUTINE_HOP;
        var nm = INTENT_NAME_MATCHED_KEY_PAIR;
        var src = SQL_COLUMN.as("source_column");
        var tgt = SQL_COLUMN.as("target_column");
        var byKey = new LinkedHashMap<Coordinate, List<KeyPair>>();
        store.dsl()
            .select(s.TYPE_NAME, s.FIELD_NAME,
                src.COLUMN_NAME, src.JOOQ_NAME, src.BINDING_TYPE,
                tgt.COLUMN_NAME, tgt.JOOQ_NAME, tgt.BINDING_TYPE)
            .from(s)
            .join(cd).on(cd.GRAPH_NAME.eq(s.GRAPH_NAME), cd.TYPE_NAME.eq(s.RETURN_TYPE_NAME),
                cd.FAMILY.eq("ROUTINE"), cd.DATA_FIELDS.eq(1))
            .join(ch).on(ch.GRAPH_NAME.eq(cd.GRAPH_NAME), ch.TYPE_NAME.eq(cd.TYPE_NAME),
                ch.FIELD_NAME.eq(cd.FIELD_NAME), ch.CANDIDATES.eq(1))
            .join(nm).on(nm.FROM_SOURCE_NAME.eq(ch.FROM_SOURCE_NAME),
                nm.FROM_SCHEMA.eq(ch.FROM_SCHEMA), nm.FROM_TABLE.eq(ch.FROM_TABLE),
                nm.TO_SOURCE_NAME.eq(ch.TO_SOURCE_NAME), nm.TO_SCHEMA.eq(ch.TO_SCHEMA),
                nm.TO_TABLE.eq(ch.TO_TABLE), nm.UNMATCHED_COLUMNS.eq(0))
            .join(src).on(src.SOURCE_NAME.eq(ch.FROM_SOURCE_NAME),
                src.TABLE_SCHEMA.eq(ch.FROM_SCHEMA), src.TABLE_NAME.eq(ch.FROM_TABLE),
                src.COLUMN_NAME.eq(nm.FROM_COLUMN))
            .join(tgt).on(tgt.SOURCE_NAME.eq(ch.TO_SOURCE_NAME),
                tgt.TABLE_SCHEMA.eq(ch.TO_SCHEMA), tgt.TABLE_NAME.eq(ch.TO_TABLE),
                tgt.COLUMN_NAME.eq(nm.TO_COLUMN))
            .where(s.GRAPH_NAME.eq(store.graphName()), s.VERDICT.eq("ADMITTED"),
                s.SEAT.eq("CARRIER"))
            .orderBy(s.TYPE_NAME, s.FIELD_NAME, nm.POSITION)
            .forEach(row -> byKey
                .computeIfAbsent(new Coordinate(row.value1(), row.value2()),
                    key -> new ArrayList<>())
                .add(pair(row.value3(), row.value4(), row.value5(),
                    row.value6(), row.value7(), row.value8())));
        return byKey;
    }

    private static KeyPair pair(String sourceSql, String sourceJava, String sourceType,
                                String targetSql, String targetJava, String targetType) {
        return new KeyPair(new CatalogColumn(sourceSql, sourceJava, sourceType),
            new CatalogColumn(targetSql, targetJava, targetType));
    }

    /** Null in, null out: an absent {@code condition:} is an absent filter, never a blank one. */
    private static JoinCondition condition(String className, String methodName) {
        return className == null || methodName == null ? null
            : new JoinCondition(className, methodName);
    }
}
