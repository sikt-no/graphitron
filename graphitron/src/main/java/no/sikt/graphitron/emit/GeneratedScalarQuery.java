package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.tables.GraphitronOccurrenceInputType;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Record4;
import org.jooq.Records;
import org.jooq.Select;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ASSERT_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ASSERT_TYPE_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_GENERATED_SCALAR_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_ARGUMENT_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_INPUT_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SCALAR_CLASS;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_APPLICATION;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;

/**
 * {@link GeneratedScalarEmitter}'s query: every scalar of the graph Graphitron generates itself, with
 * its kind. A scalar is generated where {@code graphitron_scalar_class} answers it from
 * {@code @generatedScalar}, and its kind is that application's entry row; a scalar carrying both
 * {@code @scalarType} and {@code @generatedScalar} has no class row, so it is not here, and the graph
 * never reaches an emitter anyway, the two claims being a stored defect.
 *
 * <p>The walk reads the names too: it classifies a generated scalar from them rather than deciding
 * one, and its schema class leaves their registration to the emitted unit.
 *
 * <p>{@link #decodes} reads the other half, what a call decodes: each argument or input-field
 * occurrence of a generated scalar with its kind, the names from its argument down to it, and the
 * requirements its {@code @assertType}s state, read as condition trees off
 * {@code graphitron_assert_type} and {@code graphitron_assert_type_node}.
 *
 * <p>Cost: one row per generated scalar, small by construction; {@link #decodes} one statement,
 * one row per occurrence of one with its path and its requirement nodes nested in it. A reader with
 * its own statement selects {@link #decodeAt} in it instead, which costs it no statement.
 */
public final class GeneratedScalarQuery {

    private GeneratedScalarQuery() {}

    /** One generated scalar: the author's name for it and the {@code GeneratedScalarKind} it is. */
    public record Scalar(String name, String kind) {
        public Scalar {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** The graph's generated scalars, in name order. */
    public record Rows(List<Scalar> scalars) {
        public Rows {
            scalars = List.copyOf(scalars);
        }

        public static Rows empty() {
            return new Rows(List.of());
        }

        /** The generated scalars' names. */
        public Set<String> names() {
            return scalars.stream().map(Scalar::name).collect(Collectors.toUnmodifiableSet());
        }
    }

    public static Rows read(StoreHandle store) {
        var sc = GRAPHITRON_SCALAR_CLASS;
        var da = GRAPHQL_DIRECTIVE_APPLICATION;
        var e = GRAPHITRON_AST_GENERATED_SCALAR_ENTRY;
        var rows = store.dsl()
            .select(sc.TYPE_NAME, e.KIND)
            .from(sc)
            .join(da).on(da.GRAPH_NAME.eq(sc.GRAPH_NAME), da.COORDINATE.eq(sc.TYPE_NAME),
                da.DIRECTIVE_NAME.eq("generatedScalar"), da.ORDINAL.eq(0))
            .join(e).on(e.GRAPH_NAME.eq(da.GRAPH_NAME), e.SOURCE_NAME.eq(da.SOURCE_NAME),
                e.SOURCE_LINE.eq(da.SOURCE_LINE), e.SOURCE_COLUMN.eq(da.SOURCE_COLUMN))
            .where(sc.GRAPH_NAME.eq(store.graphName()), sc.SOURCE.eq("GENERATED"))
            .orderBy(sc.TYPE_NAME)
            .fetch(r -> new Scalar(r.get(sc.TYPE_NAME), r.get(e.KIND)));
        return new Rows(rows);
    }

    /**
     * One condition of an {@code @assertType}, a node of its stored tree: an atom naming a type, or a
     * combinator over the conditions beneath it.
     */
    public sealed interface Condition {

        /** The type is the named type. */
        record Is(String typeName) implements Condition {}

        /** The type implements the named interface, directly or through another interface. */
        record Implements(String interfaceName) implements Condition {}

        /** At least one of the conditions holds. */
        record AnyOf(List<Condition> conditions) implements Condition {
            public AnyOf {
                conditions = List.copyOf(conditions);
            }
        }

        /** Every one of the conditions holds. */
        record AllOf(List<Condition> conditions) implements Condition {
            public AllOf {
                conditions = List.copyOf(conditions);
            }
        }

        /** The condition does not hold. */
        record Not(Condition condition) implements Condition {}
    }

    /** One {@code @assertType}: the component of the value whose type it judges, and the condition. */
    public record Requirement(String component, Condition condition) {
        public Requirement {
            Objects.requireNonNull(component, "component");
            Objects.requireNonNull(condition, "condition");
        }
    }

    /**
     * What a call decodes at one argument or input-field occurrence of a generated scalar: the
     * occurrence, the {@code GeneratedScalarKind} it is decoded as, the names from its argument down to
     * it, which is where the value is read from and how a refusal names it, and the requirements its
     * declaration states, in the order they are written.
     */
    public record Decode(String coordinate, String kind, List<String> path, List<Requirement> requirements) {
        public Decode {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(kind, "kind");
            path = List.copyOf(path);
            requirements = List.copyOf(requirements);
        }
    }

    /** One condition node as stored, before the tree is assembled. */
    private record Node(String path, String parent, String operator, String typeName) {}

    /**
     * Every argument and input-field occurrence of a generated scalar in the graph, by its
     * coordinate: what a call taking one of its components decodes. One statement.
     */
    public static Map<String, Decode> decodes(StoreHandle store) {
        var out = new LinkedHashMap<String, Decode>();
        store.dsl()
            .fetch(decodeRows(it -> it.GRAPH_NAME.eq(store.graphName())))
            .forEach(r -> out.put(r.value1(), new Decode(r.value1(), r.value2(), r.value3(), r.value4())));
        return out;
    }

    /**
     * What a call decodes at the occurrence {@code coordinate} of {@code graph}, as a correlated
     * {@code MULTISET} for a caller's own statement to select: empty unless the occurrence is an
     * argument or input field of a generated scalar. A reader that holds the occurrence's key in a
     * row reads its decode in that row, instead of a statement of its own ahead of it.
     */
    public static Field<Optional<Decode>> decodeAt(Field<String> graph, Field<String> coordinate) {
        return multiset(decodeRows(it -> it.GRAPH_NAME.eq(graph).and(it.COORDINATE.eq(coordinate))))
            .convertFrom(r -> r.stream().findFirst()
                .map(d -> new Decode(d.value1(), d.value2(), d.value3(), d.value4())));
    }

    /**
     * The decode rows where {@code where} holds of the occurrence: its coordinate, its kind, the
     * names from its argument down to it, and its declaration's requirements, the last two as
     * correlated {@code MULTISET}s on the occurrence's and its element's keys. Its tables are
     * aliased, so it correlates cleanly inside a caller's statement that reads the same relations.
     */
    private static Select<Record4<String, String, List<String>, List<Requirement>>> decodeRows(
        Function<GraphitronOccurrenceInputType, org.jooq.Condition> where) {
        var it = GRAPHITRON_OCCURRENCE_INPUT_TYPE.as("gs_it");
        var o = GRAPHITRON_OCCURRENCE.as("gs_o");
        var sc = GRAPHITRON_SCALAR_CLASS.as("gs_sc");
        var da = GRAPHQL_DIRECTIVE_APPLICATION.as("gs_da");
        var e = GRAPHITRON_AST_GENERATED_SCALAR_ENTRY.as("gs_e");
        var st = GRAPHITRON_OCCURRENCE_ARGUMENT_STEP.as("gs_st");
        var path = multiset(select(st.NAME)
                .from(st)
                .where(st.GRAPH_NAME.eq(it.GRAPH_NAME), st.COORDINATE.eq(it.COORDINATE))
                .orderBy(st.DEPTH))
            .convertFrom(r -> r.map(Record1::value1));
        return select(it.COORDINATE, e.KIND, path, requirements(o.GRAPH_NAME, o.ELEMENT_COORDINATE))
            .from(it)
            .join(o).on(o.GRAPH_NAME.eq(it.GRAPH_NAME), o.COORDINATE.eq(it.COORDINATE))
            .join(sc).on(sc.GRAPH_NAME.eq(it.GRAPH_NAME), sc.TYPE_NAME.eq(it.TYPE_NAME), sc.SOURCE.eq("GENERATED"))
            .join(da).on(da.GRAPH_NAME.eq(sc.GRAPH_NAME), da.COORDINATE.eq(sc.TYPE_NAME),
                da.DIRECTIVE_NAME.eq("generatedScalar"), da.ORDINAL.eq(0))
            .join(e).on(e.GRAPH_NAME.eq(da.GRAPH_NAME), e.SOURCE_NAME.eq(da.SOURCE_NAME),
                e.SOURCE_LINE.eq(da.SOURCE_LINE), e.SOURCE_COLUMN.eq(da.SOURCE_COLUMN))
            .where(where.apply(it))
            .orderBy(it.COORDINATE);
    }

    /**
     * The requirements the element {@code element} of {@code graph} declares, in the order they are
     * written: one per {@code @assertType}, its condition tree assembled from its nodes, which are a
     * correlated {@code MULTISET} on the application's key.
     */
    private static Field<List<Requirement>> requirements(Field<String> graph, Field<String> element) {
        var a = GRAPHITRON_ASSERT_TYPE.as("gs_a");
        var n = GRAPHITRON_ASSERT_TYPE_NODE.as("gs_n");
        var nodes = multiset(select(n.NODE_PATH, n.PARENT_PATH, n.OPERATOR, n.TYPE_NAME)
                .from(n)
                .where(n.GRAPH_NAME.eq(a.GRAPH_NAME), n.COORDINATE.eq(a.COORDINATE), n.ORDINAL.eq(a.ORDINAL))
                .orderBy(n.NODE_PATH))
            .convertFrom(r -> r.map(Records.mapping(Node::new)));
        return multiset(select(a.COMPONENT, nodes)
                .from(a)
                .where(a.GRAPH_NAME.eq(graph), a.COORDINATE.eq(element))
                .orderBy(a.ORDINAL))
            .convertFrom(r -> {
                List<Requirement> out = new ArrayList<>();
                for (var row : r) {
                    var tree = row.value2();
                    if (tree.stream().noneMatch(node -> node.parent() == null)) {
                        // A condition setting no field or two is no node: graphql-java refused the
                        // schema, so no graph carrying one reaches an emitter.
                        continue;
                    }
                    out.add(new Requirement(row.value1(), condition("0", tree)));
                }
                return out;
            });
    }

    /** The condition at {@code path} in {@code tree}, with the conditions beneath it. */
    private static Condition condition(String path, List<Node> tree) {
        var node = tree.stream().filter(n -> n.path().equals(path)).findFirst()
            .orElseThrow(() -> new IllegalStateException("an @assertType tree has no node " + path));
        var children = tree.stream().filter(n -> path.equals(n.parent()))
            .map(n -> condition(n.path(), tree)).toList();
        return switch (node.operator()) {
            case "IS" -> new Condition.Is(node.typeName());
            case "IMPLEMENTS" -> new Condition.Implements(node.typeName());
            case "ANY_OF" -> new Condition.AnyOf(children);
            case "ALL_OF" -> new Condition.AllOf(children);
            case "NOT" -> {
                if (children.size() != 1) {
                    throw new IllegalStateException("an @assertType not at " + path + " holds " + children.size()
                        + " conditions");
                }
                yield new Condition.Not(children.getFirst());
            }
            default -> throw new IllegalStateException("no @assertType operator " + node.operator());
        };
    }
}
