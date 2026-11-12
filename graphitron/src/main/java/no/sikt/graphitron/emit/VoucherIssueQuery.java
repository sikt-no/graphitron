package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.read.StoreHandle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ISSUE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_SCALAR_TYPE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_VOUCHER_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_VOUCHER_COMPONENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_VOUCHER_MEMBER;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.selectOne;

/**
 * {@link VoucherIssueEmitter}'s query, and the projection's: every {@code @issue} field the store
 * admits, with its scope and lifetime, the table its row is read from, and each value its voucher
 * carries, the absorbed field it is named after, the column it is read from, the scalar constant
 * that serializes it and whether it is a claim or part of the lookup.
 *
 * <p>Two statements at two grains: the issuing fields, and the components. Nothing here decides
 * anything: the members, their columns and their scalars are the voucher stage's rows, a claim is
 * its claim row, and a graph with any refused part never reaches an emitter. The scalar constant is
 * a built-in's on {@code graphql.Scalars} or the one a scalar's {@code @scalarType} names, the two
 * ways a scalar is bound.
 *
 * <p>Cost: the population is the graph's {@code @issue} fields and their members, small by
 * construction.
 */
public final class VoucherIssueQuery {

    private VoucherIssueQuery() {}

    /** The {@code graphql.Scalars} constant of each built-in scalar, the one table of them here. */
    private static final Map<String, String> BUILT_IN_CONSTANTS = Map.of(
        "ID", "GraphQLID", "String", "GraphQLString", "Int", "GraphQLInt",
        "Float", "GraphQLFloat", "Boolean", "GraphQLBoolean");

    /** The table an issuing row is read from, as the generated catalog names it. */
    public record Table(String constantsClassName, String javaFieldName) {
        public Table {
            Objects.requireNonNull(constantsClassName, "constantsClassName");
            Objects.requireNonNull(javaFieldName, "javaFieldName");
        }
    }

    /**
     * The constant a component's value is serialized by: {@code ownerClassName} is a binary class
     * name, {@code fieldName} the {@code public static GraphQLScalarType} on it.
     */
    public record ScalarConstant(String ownerClassName, String fieldName) {
        public ScalarConstant {
            Objects.requireNonNull(ownerClassName, "ownerClassName");
            Objects.requireNonNull(fieldName, "fieldName");
        }
    }

    /** One value a voucher carries: the absorbed field's name, its column, its scalar, and its part. */
    public record Component(String fieldName, ColumnRef column, ScalarConstant scalar, boolean claim) {
        public Component {
            Objects.requireNonNull(fieldName, "fieldName");
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(scalar, "scalar");
        }
    }

    /** One issuing field: its type and name, scope and lifetime, table, and components in name order. */
    public record Issue(String typeName, String fieldName, String scope, Duration ttl, Table table,
                        List<Component> components) {
        public Issue {
            Objects.requireNonNull(typeName, "typeName");
            Objects.requireNonNull(fieldName, "fieldName");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(ttl, "ttl");
            Objects.requireNonNull(table, "table");
            components = List.copyOf(components);
        }

        /** The field's coordinate, {@code Type.field}. */
        public String coordinate() {
            return typeName + "." + fieldName;
        }

        /** The columns the row must carry for the voucher to be signed, in component order. */
        public List<ColumnRef> columns() {
            return components.stream().map(Component::column).toList();
        }
    }

    /** The issuing fields, by coordinate. */
    public record Rows(Map<String, Issue> byCoordinate) {
        public Rows {
            byCoordinate = Map.copyOf(byCoordinate);
        }

        public static Rows empty() {
            return new Rows(Map.of());
        }

        public Optional<Issue> issue(String coordinate) {
            return Optional.ofNullable(byCoordinate.get(coordinate));
        }
    }

    public static Rows read(StoreHandle store) {
        var d = GRAPHQL_DIRECTIVE_APPLICATION;
        var e = GRAPHITRON_AST_ISSUE_ENTRY;
        var dsl = store.dsl();
        var issues = dsl.select(d.COORDINATE, e.SCOPE, e.TTL)
            .from(d)
            .join(e).on(e.GRAPH_NAME.eq(d.GRAPH_NAME), e.SOURCE_NAME.eq(d.SOURCE_NAME),
                e.SOURCE_LINE.eq(d.SOURCE_LINE), e.SOURCE_COLUMN.eq(d.SOURCE_COLUMN))
            .where(d.GRAPH_NAME.eq(store.graphName()), d.DIRECTIVE_NAME.eq("issue"))
            .orderBy(d.COORDINATE)
            .fetch();

        var c = GRAPHITRON_VOUCHER_COMPONENT;
        var m = GRAPHITRON_VOUCHER_MEMBER;
        var col = SQL_COLUMN;
        var t = SQL_TABLE;
        var s = SQL_SCHEMA;
        var cl = GRAPHITRON_VOUCHER_CLAIM;
        var sd = GRAPHQL_DIRECTIVE_APPLICATION.as("sd");
        var se = GRAPHITRON_AST_SCALAR_TYPE_ENTRY;
        var claim = exists(selectOne().from(cl)
            .where(cl.GRAPH_NAME.eq(c.GRAPH_NAME), cl.TYPE_NAME.eq(c.TYPE_NAME), cl.FIELD_NAME.eq(c.FIELD_NAME)));
        var components = dsl.select(m.TYPE_NAME, m.ISSUING_FIELD, c.FIELD_NAME, col.COLUMN_NAME, col.JOOQ_NAME,
                col.BINDING_TYPE, col.DATABASE_TYPE, t.JOOQ_NAME, s.TABLES_CLASS_FQN, c.SCALAR_NAME,
                se.SCALAR_REF_CLASS_PART, se.SCALAR_REF_FIELD_PART, org.jooq.impl.DSL.field(claim))
            .from(c)
            .join(m).on(m.GRAPH_NAME.eq(c.GRAPH_NAME), m.TYPE_NAME.eq(c.TYPE_NAME), m.FIELD_NAME.eq(c.FIELD_NAME))
            .join(col).on(col.SOURCE_NAME.eq(c.TABLE_SOURCE_NAME), col.TABLE_SCHEMA.eq(c.TABLE_SCHEMA),
                col.TABLE_NAME.eq(c.TABLE_NAME), col.COLUMN_NAME.eq(c.COLUMN_NAME))
            .join(t).on(t.SOURCE_NAME.eq(c.TABLE_SOURCE_NAME), t.TABLE_SCHEMA.eq(c.TABLE_SCHEMA),
                t.TABLE_NAME.eq(c.TABLE_NAME))
            .join(s).on(s.SOURCE_NAME.eq(c.TABLE_SOURCE_NAME), s.TABLE_SCHEMA.eq(c.TABLE_SCHEMA))
            .leftJoin(sd).on(sd.GRAPH_NAME.eq(c.GRAPH_NAME), sd.COORDINATE.eq(c.SCALAR_NAME),
                sd.DIRECTIVE_NAME.eq("scalarType"), sd.ORDINAL.eq(0))
            .leftJoin(se).on(se.GRAPH_NAME.eq(sd.GRAPH_NAME), se.SOURCE_NAME.eq(sd.SOURCE_NAME),
                se.SOURCE_LINE.eq(sd.SOURCE_LINE), se.SOURCE_COLUMN.eq(sd.SOURCE_COLUMN))
            .where(c.GRAPH_NAME.eq(store.graphName()))
            .orderBy(m.TYPE_NAME, m.ISSUING_FIELD, c.FIELD_NAME)
            .fetch();

        var componentsByIssue = new LinkedHashMap<String, List<Component>>();
        var tableByIssue = new LinkedHashMap<String, Table>();
        for (var r : components) {
            String coordinate = r.get(m.TYPE_NAME) + "." + r.get(m.ISSUING_FIELD);
            String scalarName = r.get(c.SCALAR_NAME);
            String builtIn = BUILT_IN_CONSTANTS.get(scalarName);
            ScalarConstant scalar = builtIn != null
                ? new ScalarConstant("graphql.Scalars", builtIn)
                : r.get(se.SCALAR_REF_CLASS_PART) == null ? null
                : new ScalarConstant(r.get(se.SCALAR_REF_CLASS_PART), r.get(se.SCALAR_REF_FIELD_PART));
            if (scalar == null) {
                throw new IllegalStateException("the voucher component " + r.get(m.TYPE_NAME) + "."
                    + r.get(c.FIELD_NAME) + " has a scalar, " + scalarName
                    + ", bound neither as a built-in nor by @scalarType");
            }
            componentsByIssue.computeIfAbsent(coordinate, k -> new ArrayList<>()).add(new Component(
                r.get(c.FIELD_NAME),
                new ColumnRef(r.get(col.COLUMN_NAME), r.get(col.JOOQ_NAME), r.get(col.BINDING_TYPE),
                    r.get(col.DATABASE_TYPE)),
                scalar, Boolean.TRUE.equals(r.get(12, Boolean.class))));
            tableByIssue.putIfAbsent(coordinate, new Table(r.get(s.TABLES_CLASS_FQN), r.get(t.JOOQ_NAME)));
        }

        var byCoordinate = new LinkedHashMap<String, Issue>();
        for (var r : issues) {
            String coordinate = r.get(d.COORDINATE);
            var table = tableByIssue.get(coordinate);
            if (table == null) {
                continue;
            }
            int dot = coordinate.indexOf('.');
            byCoordinate.put(coordinate, new Issue(coordinate.substring(0, dot), coordinate.substring(dot + 1),
                r.get(e.SCOPE), Duration.parse(r.get(e.TTL)), table, componentsByIssue.get(coordinate)));
        }
        return new Rows(byCoordinate);
    }
}
