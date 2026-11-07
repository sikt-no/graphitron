package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Records;

import java.util.List;
import java.util.Objects;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PARTICIPANT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;

/**
 * The polymorphic lift's query: every root service producer whose target is a union or interface
 * with members on tables of their own, each member with its table and key. The call hands back
 * records of any of the members' tables, and each is lifted onto the rows of its own table, which
 * its record's class tells apart.
 *
 * <p>Participants told apart by a discriminating column, a single-table interface, are the
 * discriminated lift's, which reads one table for all of them.
 */
public final class PolymorphicLiftQuery {

    private PolymorphicLiftQuery() {}

    /** One member a record may be: its type, its table and the table's primary key, in key order. */
    public record Participant(String typeName, LiftQuery.Table table, List<LiftQuery.KeyColumn> key) {
        public Participant {
            Objects.requireNonNull(typeName, "typeName");
            Objects.requireNonNull(table, "table");
            key = List.copyOf(key);
        }
    }

    /** One polymorphic producer: its coordinate, whether it lifts a list, and its participants. */
    public record Row(String coordinate, boolean list, List<Participant> participants) {
        public Row {
            Objects.requireNonNull(coordinate, "coordinate");
            participants = List.copyOf(participants);
        }
    }

    /** The graph's root polymorphic service producers, by coordinate, their participants by type. */
    public static List<Row> read(StoreHandle store) {
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var r = GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
        var pt = GRAPHITRON_OCCURRENCE_PARTICIPANT;
        var t = SQL_TABLE;
        var s = SQL_SCHEMA;
        var participants = multiset(
            select(pt.PARTICIPANT_TYPE, t.TABLE_NAME, t.CLASS_FQN, s.TABLES_CLASS_FQN, t.JOOQ_NAME, t.RECORD_CLASS_FQN,
                LiftQuery.key(t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME))
                .from(pt)
                .join(t).on(t.SOURCE_NAME.eq(pt.TABLE_SOURCE_NAME), t.TABLE_SCHEMA.eq(pt.TABLE_SCHEMA),
                    t.TABLE_NAME.eq(pt.TABLE_NAME))
                .join(s).on(s.SOURCE_NAME.eq(t.SOURCE_NAME), s.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
                .where(pt.GRAPH_NAME.eq(p.GRAPH_NAME), pt.COORDINATE.eq(p.COORDINATE), pt.RECOGNIZED_BY.eq("TABLE"))
                .orderBy(pt.PARTICIPANT_TYPE))
            .convertFrom(rs -> rs.map(rec -> new Participant(rec.value1(),
                new LiftQuery.Table(rec.value2(), rec.value3(), rec.value4(), rec.value5(), rec.value6()),
                rec.value7())));
        return store.dsl()
            .select(p.COORDINATE, r.SHAPE, participants)
            .from(p)
            .join(r).on(r.GRAPH_NAME.eq(p.GRAPH_NAME), r.COORDINATE.eq(p.COORDINATE))
            .where(p.GRAPH_NAME.eq(store.graphName()), p.KIND.eq("SERVICE"), p.START.eq("ROOT"),
                r.SHAPE.in("SINGLE_RECORD", "RECORD_LIST"))
            .andExists(select(pt.COORDINATE).from(pt)
                .where(pt.GRAPH_NAME.eq(p.GRAPH_NAME), pt.COORDINATE.eq(p.COORDINATE), pt.RECOGNIZED_BY.eq("TABLE")))
            .orderBy(p.COORDINATE)
            .fetch(Records.mapping((coordinate, shape, members) ->
                new Row(coordinate, "RECORD_LIST".equals(shape), members)));
    }
}
