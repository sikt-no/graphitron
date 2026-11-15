package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.FactGraphs;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static no.sikt.graphitron.model.Tables.META_STAGE_RULE;
import static no.sikt.graphitron.model.Tables.META_STAGE_WRITE_KIND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.table;

/**
 * Every stage-written table holds exactly the rows the view stating its rule computes, on every
 * store the fact documents' captures filled.
 *
 * <p>The oracle a conversion rests on. A stage moves who evaluates a rule and when, and its whole
 * safety argument is that it moves nothing else; {@code EXCEPT} in both directions between the
 * table and the rule view is that argument as a query. It is a standing assertion rather than a
 * one-commit check because the rule survives the conversion: as long as both relations exist the
 * comparison is runnable, which is the property that keeping the rule a stored view buys and
 * moving its text into the stage would spend.
 *
 * <p>Both directions, because they fail differently and each on its own is half an oracle. Rows in
 * the table and not in the rule are rows a previous capture left behind, which is what an appending
 * stage or a mis-scoped {@code DELETE} produces; rows in the rule and not in the table are rows the
 * stage did not write, which is what a predicate in the wrong place produces.
 *
 * <p>Over the whole population of fact documents ({@link FactGraphs}), since the agreement is an
 * invariant of any captured graph: every scenario's final reading is compared, a stepped scenario's
 * included, so a row an earlier step left behind is seen, the configured-tag reading among them,
 * which {@code scenarios/configured-tag} states. The non-vacuity cases say the population did its job:
 * a comparison between two empty relations passes while asserting nothing, and one over a single
 * arm passes while asserting a fraction of the rule.
 */
public class StageAnswerAgreementTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();


    /**
     * One stage-written table and the rule views its stages insert it from, as {@code meta_stage_rule}
     * states them: the table holds exactly what their rules compute together. Several views writing
     * one table, a supertype's rule arms or two stages' halves of a relation split by kind, are
     * compared as their union, each view's rows being the table's.
     */
    public record Stage(String target, List<String> ruleViews) {}

    private static final String ONE_RELATION = "A rule view's rows are exactly one relation's";

    private static final String WALK = "The join path steps are their rule's rows";

    /**
     * Rule views whose stage inserts only part of their rows into a relation, Java choosing them,
     * keyed relation then view, each with the node whose debt it is: the table's rows are still each
     * one its rules compute, but not every row of the view is the table's. The list only shrinks.
     */
    public static final Map<String, String> FILTERED_IN_JAVA = Map.of(
        "graphitron_occurrence_connection graphitron_occurrence_connection_rule", ONE_RELATION,
        "graphitron_occurrence_connection_part graphitron_occurrence_connection_rule", ONE_RELATION,
        "graphitron_occurrence graphitron_occurrence_step_rule", ONE_RELATION,
        // The fills of the method a call pinned, by a join in Java; an ambiguous overload pins none.
        "graphitron_occurrence_call_parameter graphitron_occurrence_condition_parameter_rule", ONE_RELATION,
        // The claims of the needs the walk reaches.
        "graphitron_join_path_claimed graphitron_join_path_claimed_rule", WALK);

    /**
     * Relations the walk writes beside the rule view stating them, so the two disagree both ways:
     * debt of {@value #WALK}, which closes as the walk dissolves; the list only shrinks.
     */
    public static final Set<String> WALK_WRITTEN = Set.of("graphitron_join_path_step");

    /**
     * Every stage-written table and its rule views, read from the store's own statement of them, so
     * a stage taking a rule view of its own is compared the day its row lands.
     */
    public static List<Stage> stages(DSLContext dsl) {
        var byTarget = new TreeMap<String, List<String>>();
        dsl.select(META_STAGE_RULE.RELATION_NAME, META_STAGE_RULE.RULE_VIEW).from(META_STAGE_RULE)
            .orderBy(META_STAGE_RULE.RELATION_NAME, META_STAGE_RULE.RULE_VIEW)
            .fetch()
            .forEach(r -> byTarget.computeIfAbsent(r.value1(), x -> new ArrayList<>()).add(r.value2()));
        return byTarget.entrySet().stream()
            .map(e -> new Stage(e.getKey(), List.copyOf(new TreeSet<>(e.getValue()))))
            .toList();
    }

    /** One stage's rule view inserting {@code graphitron_entry_defect}. */
    public record DefectRule(String stage, String view) {}

    /** The defect rule views and the stages raising from them, as {@code meta_stage_rule} states them. */
    public static List<DefectRule> defectRules(DSLContext dsl) {
        return dsl.select(META_STAGE_RULE.STAGE_NAME, META_STAGE_RULE.RULE_VIEW).from(META_STAGE_RULE)
            .where(META_STAGE_RULE.RELATION_NAME.eq("graphitron_entry_defect"))
            .orderBy(META_STAGE_RULE.STAGE_NAME)
            .fetch(r -> new DefectRule(r.value1(), r.value2()));
    }

    /**
     * The stages that insert {@code target} from a rule view, where its rows name the stage that wrote
     * them: the defects, raised beside the walk, which writes its own as it goes and has no view.
     */
    private static Set<String> ruleStages(DSLContext dsl, String target) {
        return new TreeSet<>(dsl.selectDistinct(META_STAGE_RULE.STAGE_NAME).from(META_STAGE_RULE)
            .where(META_STAGE_RULE.RELATION_NAME.eq(target)).fetch(META_STAGE_RULE.STAGE_NAME));
    }

    @Test
    @DisplayName("every stage-written table holds exactly its rules' rows, both directions")
    void everyStageAgreesWithTheRuleItInsertsFrom() {
        var disagreements = new ArrayList<String>();
        // The listed entries that still disagree somewhere; one that agrees everywhere is stale.
        var stillDisagreeing = new HashSet<String>();
        eachReading((graphs, dsl) -> {
            for (Stage stage : stages(dsl)) {
                boolean walked = WALK_WRITTEN.contains(stage.target());
                var columns = sharedColumns(dsl, stage);
                var held = held(dsl, stage, columns);
                for (var graph : graphs) {
                    for (var view : stage.ruleViews()) {
                        String pair = stage.target() + " " + view;
                        var rows = dsl.selectFrom(computed(dsl, stage.target(), view, columns, graph)
                            .except(held.apply(graph))).fetch();
                        if (walked || FILTERED_IN_JAVA.containsKey(pair)) {
                            if (rows.isNotEmpty()) {
                                stillDisagreeing.add(walked ? stage.target() : pair);
                            }
                            continue;
                        }
                        rows.forEach(row -> disagreements.add(view + " computes a row "
                            + stage.target() + " does not hold (" + graph + "): " + row));
                    }
                    var union = stage.ruleViews().stream()
                        .<org.jooq.Select<org.jooq.Record>>map(view -> computed(dsl, stage.target(), view, columns, graph))
                        .reduce((left, right) -> left.union(right)).orElseThrow();
                    var extra = dsl.selectFrom(held.apply(graph).except(union)).fetch();
                    if (walked) {
                        if (extra.isNotEmpty()) {
                            stillDisagreeing.add(stage.target());
                        }
                        continue;
                    }
                    extra.forEach(row -> disagreements.add(stage.target() + " holds a row no rule"
                        + " of " + stage.ruleViews() + " computes (" + graph + "): " + row));
                }
            }
        });
        assertThat(disagreements)
            .as("a table's row its rules do not compute is a previous capture's row the stage did"
                + " not clear, or one written twice; a rule's row its table lacks is one the stage"
                + " did not write")
            .isEmpty();
        var stale = new TreeSet<String>(FILTERED_IN_JAVA.keySet());
        stale.addAll(WALK_WRITTEN);
        stale.removeAll(stillDisagreeing);
        assertThat(stale)
            .as("listed debt that now agrees with its rules everywhere: remove this entry, the lists only"
                + " shrink")
            .isEmpty();
    }

    /**
     * Every stage's rule view yields rows somewhere, so no agreement above is between two empty
     * relations. The floor every conversion owes and the one a new stage cannot land without meeting:
     * a rule no document reaches wants a document that reaches it.
     */
    @Test
    @DisplayName("every stage's rule view yields rows")
    void noStageIsComparedOverAnEmptyRelation() {
        var views = new TreeSet<String>();
        var populated = new HashSet<String>();
        eachReading((graphs, dsl) -> {
            stages(dsl).forEach(stage -> views.addAll(stage.ruleViews()));
            for (var view : views) {
                for (var graph : graphs) {
                    if (populated.contains(view)) {
                        break;
                    }
                    if (dsl.fetchExists(table(name(view.toUpperCase())), inGraph(dsl, view, graph))) {
                        populated.add(view);
                    }
                }
            }
        });
        assertThat(views.stream().filter(view -> !populated.contains(view)).toList())
            .as("stage rules no fact document reaches; add a document that reaches one")
            .isEmpty();
    }

    /**
     * Each rule view a stage inserts a shared relation from yields only the kinds
     * {@code meta_stage_write_kind} declares for that stage, over every store the fact documents
     * fill: the declaration the stage roster gate exempts a read by, held to what the stage writes.
     * A view no document reaches is named too, since it would pass while asserting nothing.
     */
    @Test
    @DisplayName("every rule view a stage inserts a shared relation from yields only the stage's declared kinds")
    void everyStageWritesOnlyTheKindsItDeclares() {
        var rules = new ArrayList<org.jooq.Record3<String, String, String>>();
        var declared = new TreeMap<String, Set<String>>();
        var columns = new TreeMap<String, String>();
        var observed = new TreeMap<String, Set<String>>();
        var stating = new TreeSet<String>();
        eachReading((graphs, dsl) -> {
            if (rules.isEmpty()) {
                rules.addAll(dsl.select(META_STAGE_RULE.STAGE_NAME, META_STAGE_RULE.RELATION_NAME,
                    META_STAGE_RULE.RULE_VIEW).from(META_STAGE_RULE).fetch());
                dsl.selectFrom(META_STAGE_WRITE_KIND).fetch().forEach(k -> {
                    var key = k.getStageName() + " " + k.getRelationName();
                    declared.computeIfAbsent(key, x -> new TreeSet<>()).add(k.getKind());
                    columns.put(key, k.getKindColumn());
                });
            }
            for (var rule : rules) {
                var column = columns.get(rule.value1() + " " + rule.value2());
                // A view of a relation split by kind that states no kind column is split by what it
                // joins, which its stage filters on, and is held to the kinds where that is stated.
                if (column == null || columns(dsl, rule.value3()).stream()
                        .noneMatch(c -> c.getName().equalsIgnoreCase(column))) {
                    continue;
                }
                stating.add(rule.value3());
                var kinds = observed.computeIfAbsent(rule.value3(), x -> new TreeSet<>());
                for (var graph : graphs) {
                    kinds.addAll(dsl.selectDistinct(field(name(column.toUpperCase()), String.class))
                        .from(table(name(rule.value3().toUpperCase()))).where(inGraph(dsl, rule.value3(), graph))
                        .fetch(0, String.class));
                }
            }
        });
        var violations = new ArrayList<String>();
        for (var rule : rules) {
            if (!stating.contains(rule.value3())) {
                continue;
            }
            // A view several stages insert one relation from, each filtering its own kinds, yields
            // the kinds they declare together.
            var together = new TreeSet<String>();
            var sharers = new TreeSet<String>();
            for (var other : rules) {
                if (other.value2().equals(rule.value2()) && other.value3().equals(rule.value3())) {
                    together.addAll(declared.getOrDefault(other.value1() + " " + other.value2(), Set.of()));
                    sharers.add(other.value1());
                }
            }
            violations.addAll(undeclared(String.join(" and ", sharers), rule.value2(), rule.value3(),
                observed.getOrDefault(rule.value3(), Set.of()), together));
        }
        assertThat(violations)
            .as("a stage writing a kind it does not declare reads past another stage's rows unrefused;"
                + " declare the kind, or move the rows to the stage declaring it")
            .isEmpty();
    }

    @Test
    @DisplayName("a view yielding a kind its stage does not declare is named")
    void aKindTheStageDoesNotDeclareIsNamed() {
        assertThat(undeclared("occurrence-bindings", "graphitron_occurrence_binding",
                "graphitron_occurrence_binding_table_rule", Set.of("TABLE"), Set.of("COLUMN", "NODE_ID")))
            .containsExactly("graphitron_occurrence_binding_table_rule yields TABLE, which occurrence-bindings"
                + " does not declare for graphitron_occurrence_binding");
        assertThat(undeclared("occurrence-bindings", "graphitron_occurrence_binding",
                "graphitron_occurrence_binding_table_rule", Set.of(), Set.of("TABLE")))
            .containsExactly("graphitron_occurrence_binding_table_rule yields no rows in any fact document");
        assertThat(undeclared("occurrence-bindings", "graphitron_occurrence_binding",
                "graphitron_occurrence_binding_table_rule", Set.of("TABLE"), Set.of("TABLE")))
            .isEmpty();
    }

    /**
     * What a view's {@code observed} kinds say against the kinds its stage {@code declared} for the
     * relation: each kind it yields and the stage does not declare, or that it yields nothing.
     */
    private static List<String> undeclared(String stage, String relation, String view, Set<String> observed,
                                           Set<String> declared) {
        if (observed.isEmpty()) {
            return List.of(view + " yields no rows in any fact document");
        }
        return observed.stream()
            .filter(kind -> !declared.contains(kind))
            .map(kind -> view + " yields " + kind + ", which " + stage + " does not declare for " + relation)
            .toList();
    }

    /**
     * Runs {@code body} over each store of the population with the graphs it holds, every document
     * captured first. A comparison runs per graph, as a stage does: a rule view evaluated over a
     * whole store at once holds every graph's intermediate rows on the heap. Graphs are the inner
     * loop so one statement's text repeats with only the graph bound, and the session's plan cache
     * keeps it planned.
     */
    private void eachReading(java.util.function.BiConsumer<Set<String>, DSLContext> body) {
        FACTS.warm(FACTS.paths());
        FACTS.eachStore((dsl, graphs) -> {
            body.accept(graphs, dsl);
            return null;
        });
    }

    /**
     * The columns the table and every rule view writing it carry: what they are compared over. A
     * table marking and sweeping carries the reading's instant beside what the rules state, and the
     * instant is the writer's, not the rules', so it is no part of whether they agree; a supertype
     * holding several rules' rows carries the rule's key and not its payload, which its subtype
     * compares.
     */
    private static List<org.jooq.Field<Object>> sharedColumns(DSLContext dsl, Stage stage) {
        var shared = new ArrayList<>(columns(dsl, stage.target()).stream().map(org.jooq.Field::getName).toList());
        for (var view : stage.ruleViews()) {
            var names = columns(dsl, view).stream().map(org.jooq.Field::getName).toList();
            shared.retainAll(names);
        }
        return shared.stream().map(column -> field(name(column))).toList();
    }

    /**
     * The table's rows for a graph, over {@code columns}: where its rows name the stage that wrote
     * them, only those the rule views' stages wrote.
     */
    private static java.util.function.Function<String, org.jooq.Select<org.jooq.Record>> held(
            DSLContext dsl, Stage stage, List<org.jooq.Field<Object>> columns) {
        // A stage column naming meta_stage rows, as the defects' does; graphitron_claim's names the
        // writing class instead, and is compared whole.
        boolean staged = columns(dsl, stage.target()).stream().anyMatch(c -> c.getName().equals("STAGE"))
            && dsl.fetchExists(dsl.selectOne().from(table(name(stage.target().toUpperCase())))
                .where(field(name("STAGE"), String.class).in(
                    dsl.select(no.sikt.graphitron.model.Tables.META_STAGE.STAGE_NAME)
                        .from(no.sikt.graphitron.model.Tables.META_STAGE))));
        var stages = staged ? ruleStages(dsl, stage.target()) : Set.<String>of();
        return graph -> select(columns).from(table(name(stage.target().toUpperCase())))
            .where(inGraph(dsl, stage.target(), graph))
            .and(staged ? field(name("STAGE"), String.class).in(stages) : org.jooq.impl.DSL.noCondition());
    }

    private static org.jooq.Select<org.jooq.Record> computed(DSLContext dsl, String target, String view,
                                                            List<org.jooq.Field<Object>> columns, String graph) {
        return select(columns).from(table(name(view.toUpperCase()))).where(inGraph(dsl, view, graph))
            .and(projection(dsl, target, columns));
    }

    /**
     * The rows of a rule view its table can hold: those whose values are set in every compared column
     * the table declares {@code NOT NULL}. A subtype's payload is such a column, so a subtype written
     * from its supertype's rule holds the rule's rows carrying that payload: a projection the schema
     * states, not a choice the writer makes, and read here as the schema states it.
     */
    public static org.jooq.Condition projection(DSLContext dsl, String target,
                                                List<org.jooq.Field<Object>> columns) {
        var required = new TreeSet<>(dsl.select(field(name("COLUMN_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "COLUMNS")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_NAME"), String.class).eq(target.toUpperCase()))
            .and(field(name("IS_NULLABLE"), String.class).eq("NO"))
            .fetch(0, String.class));
        return columns.stream()
            .filter(column -> required.contains(column.getName()))
            .map(org.jooq.Field::isNotNull)
            .reduce(org.jooq.impl.DSL.noCondition(), org.jooq.Condition::and);
    }

    /**
     * The relation's rows of {@code graph}, where it is partitioned by graph; a relation of a corpus
     * read once for every graph, the code family's, holds no graph and is compared whole.
     */
    private static org.jooq.Condition inGraph(DSLContext dsl, String relation, String graph) {
        return columns(dsl, relation).stream().anyMatch(c -> c.getName().equals("GRAPH_NAME"))
            ? field(name("GRAPH_NAME"), String.class).eq(graph)
            : org.jooq.impl.DSL.noCondition();
    }

    /** The rule's own columns, in its order: what a comparison with its table is over. */
    private static final Map<String, List<org.jooq.Field<Object>>> COLUMNS =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static List<org.jooq.Field<Object>> columns(DSLContext dsl, String rule) {
        return COLUMNS.computeIfAbsent(rule, ignored -> dsl.select(field(name("COLUMN_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "COLUMNS")))
            .where(field(name("TABLE_NAME"), String.class).eq(rule.toUpperCase()))
            .orderBy(field(name("ORDINAL_POSITION")))
            .fetch(0, String.class).stream()
            .map(column -> field(name(column)))
            .toList());
    }
}
