package no.sikt.graphitron.model;

import no.sikt.graphitron.model.derive.ViewReferences;
import no.sikt.graphitron.model.test.FactStores;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * Where one fact is spelled by several capture relations and no relation names it once.
 *
 * <p>A subtype set is a group of capture tables carrying the same payload under different keys:
 * each row says the same thing about a different kind of site, and the key is the only thing that
 * differs. Such a set has a supertype whether or not the schema declares one, and where it does
 * not, every reader that wants the shared fact reconstructs it by {@code UNION}. That
 * reconstruction is the defect this gate is about. It costs a reader an evaluation per arm, it
 * cannot be indexed, and it silently omits an arm the day an eleventh sibling arrives, which is a
 * fault no constraint can express because there is nothing declared for a constraint to sit on.
 *
 * <p>The set roster and the reconstruction roster are both pinned by equality in both directions,
 * on the same reasoning the read-cost gate pins its own: a set that has gained a member and a
 * reconstruction that has appeared are the failures this exists to catch, and a set that has lost
 * one or a reconstruction that has gone is a roster row somebody now has to delete, which is how
 * the day the work lands gets noticed rather than absorbed.
 *
 * <h2>What counts as a set, and why the rule is not a list</h2>
 *
 * <p>Grouping on the payload alone would report two large sets that are not sets at all. Every
 * captured row carries where it was read from, so {@link #PROVENANCE} is shared by everything that
 * shares nothing, and the coordinate relations carry no payload whatever. Both fall out on a rule
 * rather than by exemption: a set's payload must be non-empty and must hold at least one column
 * that is not provenance. Sharing only where you came from is not sharing a fact.
 *
 * <p>Membership alone does not confirm a reconstruction either, which is the trap a coarser scan
 * falls into: sets overlap, so a view unioning several relations for a column they happen to share
 * gets credited with reconstructing a set whose attributes it never names. A site is confirmed
 * here only when the view unions at least two members <em>and</em> names at least one of the set's
 * own payload columns. That is still name matching over a stored definition rather than proof the
 * column is projected from the unioned arms, which is why the roster is pinned rather than merely
 * counted.
 *
 * <p>The threshold is two members and not three. Three is what a scan needs when provenance is
 * still in the payload, because at two the noise swamps the signal; with provenance ruled out, two
 * is the honest threshold, and it is where this schema's remaining reconstructions live. Every row
 * of {@link #RECONSTRUCTIONS} unions an argument-site relation with its field-site twin, which is
 * one fact spelled at two coordinates and the same defect the larger sets had before their
 * supertypes were written.
 */
class SupertypeSignatureGateTest {

    /** Where a captured row was read from. Shared by every capture relation, so it is not a fact. */
    private static final Set<String> PROVENANCE = Set.of("SOURCE_NAME", "SOURCE_LINE", "SOURCE_COLUMN");

    /** The families a capture walk writes. A derived relation is not a candidate to be one of these. */
    private static final List<String> CAPTURE_FAMILIES =
        List.of("graphql_", "graphitron_", "sql_", "jvm_");

    /**
     * Every group of capture tables sharing a payload, by the members of the group. A set here is
     * a supertype the schema has not declared, and each is a decision somebody has taken or owes:
     * the reference-step four and the directive-argument six are recorded in this item's plan as
     * deliberately unconverted, the {@code sql_} column groups are reference lists rather than
     * subtypes, and the argument-site twins are the tranche the reconstruction roster below
     * measures.
     */
    private static final Set<Set<String>> SUBTYPE_SETS = Set.of(
        Set.of("graphitron_undecoded_argument", "graphql_argument_directive_arg",
               "graphql_enum_value_directive_arg", "graphql_field_directive_arg",
               "graphql_schema_directive_arg", "graphql_type_directive_arg"),
        Set.of("graphitron_argument_reference_for_step", "graphitron_argument_reference_step",
               "graphitron_field_reference_step", "graphitron_reference_for_step"),
        Set.of("sql_constraint_column", "sql_index_column", "sql_node_key_column"),
        Set.of("graphitron_argument_condition_context_arg", "graphitron_field_condition_context_arg",
               "graphitron_service_context_arg"),
        Set.of("graphitron_argument_condition", "graphitron_field_condition"),
        Set.of("graphitron_external_field", "graphitron_service"),
        Set.of("graphitron_default_order_field", "graphitron_order_field"),
        Set.of("graphitron_error", "graphql_type_directive"),
        Set.of("graphitron_argument_binding", "graphitron_field_binding"),
        Set.of("graphitron_argument_node_id", "graphitron_field_node_id"),
        Set.of("graphitron_argument_reference_for", "graphitron_reference_for"));

    /**
     * Every view that reconstructs a set by unioning its members and naming its payload, as
     * {@code view|member,member}. Each is a supertype this schema owes, and the shape is the same
     * in all of them: an argument-site relation unioned with its field-site twin, because the
     * author may write the same directive at either coordinate and no relation says so once.
     *
     * <p>The context-argument row's set has a third member the reconstruction deliberately does not
     * union, {@code graphitron_service_context_arg}: a condition parameter's roles are a different
     * rule from a service parameter's. Its supertype therefore belongs at capture, keyed on the site
     * a reader filters by, rather than as a union in a derived view.
     */
    private static final Set<String> RECONSTRUCTIONS = Set.of(
        "intent_argument_filter_role|graphitron_argument_condition,graphitron_field_condition",
        "intent_condition_context_parameter|graphitron_argument_condition_context_arg,graphitron_field_condition_context_arg",
        "intent_condition_method_route|graphitron_argument_reference_step,graphitron_field_reference_step",
        "intent_condition_method_route_defect|graphitron_argument_reference_step,graphitron_field_reference_step",
        "intent_condition_param_decode|graphitron_argument_condition,graphitron_field_condition",
        "intent_field_demand_rule|graphitron_external_field,graphitron_service",
        "intent_field_exemption_rule|graphitron_external_field,graphitron_service",
        "intent_field_producer_reference|graphitron_external_field,graphitron_service",
        "intent_input_occurrence_override|graphitron_argument_condition,graphitron_field_condition",
        "intent_node_id_instruction_live|graphitron_argument_node_id,graphitron_field_node_id",
        "intent_resolved_node_key_column|sql_constraint_column,sql_node_key_column");

    @Test
    @DisplayName("the capture tables sharing a payload are exactly the recorded subtype sets")
    void theSubtypeSetsAreExactlyTheRecordedOnes() {
        withStore(dsl -> assertThat(undeclaredSets(dsl))
            .as("groups of capture tables carrying the same payload under different keys."
                + " Equality both ways: a set that has gained a member is a sibling nobody"
                + " decided about, and a set that has lost one is a roster row to delete")
            .containsExactlyInAnyOrderElementsOf(SUBTYPE_SETS));
    }

    @Test
    @DisplayName("the views reconstructing a subtype set are exactly the recorded ones")
    void theReconstructionsAreExactlyTheRecordedOnes() {
        withStore(dsl -> assertThat(reconstructions(dsl))
            .as("views unioning members of a subtype set and naming its payload. Equality both"
                + " ways: a new one is a supertype somebody skipped writing, and a missing one"
                + " is the day a supertype landed and this row has to go")
            .containsExactlyInAnyOrderElementsOf(RECONSTRUCTIONS));
    }

    /**
     * No set of three or more members is reconstructed anywhere, which is the state the larger
     * supertypes were written to reach. Asserted empty rather than by roster: this is the claim
     * that the wide reconstructions are gone, and a roster would let one back in quietly.
     */
    @Test
    @DisplayName("no view reconstructs a subtype set from three or more of its members")
    void noWideReconstructionSurvives() {
        withStore(dsl -> assertThat(reconstructions(dsl).stream()
                .filter(row -> row.split("\\|")[1].split(",").length >= 3).toList())
            .as("views unioning three or more members of one subtype set")
            .isEmpty());
    }

    /**
     * The subtype sets whose supertype nobody has written, which is what the roster records: a set
     * whose whole payload is a foreign key into one relation every member points at has its
     * supertype already, and belongs on no roster of what the schema owes.
     *
     * <p>Read off the payload rather than off any list of relations. A set groups because its
     * members carry the same columns outside their own keys, so the converted shape is those very
     * columns, all of them, referencing one relation: the members agree on a spelling and the
     * database holds them to it. The whole payload and not merely some of it, because a reference
     * carrying part of one is a different fact entirely. graphitron_error and
     * graphql_type_directive share five columns and both point three of them at
     * graphql_type_declaration, which is where each was written and not what each is a kind of.
     */
    private static Collection<Set<String>> undeclaredSets(DSLContext dsl) {
        return undeclaredSubtypeSets(dsl).values();
    }

    /**
     * The subtype sets that have no supertype, keyed by payload as {@link #subtypeSets} keys them.
     * A set whose whole payload is a foreign key into one common relation has its supertype already
     * and is not one of these.
     *
     * <p>Both tests read this rather than the raw grouping, and the reconstruction one is why the
     * distinction has to be made here rather than at each caller. A view unioning members of a set
     * that has a supertype is not a supertype somebody skipped writing, because nobody skipped it:
     * it is a projection of a hierarchy that exists, taken because the supertype deliberately does
     * not carry what the reader wants. The coordinate family is the worked example. Its four
     * members share {@code graphql_coordinate}, and a reader holding a coordinate and wanting the
     * field it sits on still has to union the two members that sit on one, because the other two do
     * not and carrying the columns up would put two nullnesses on the supertype to serve half its
     * subtypes. Counting that as debt would put a row on the roster that nobody can ever discharge,
     * which is worse than not counting it: the roster is how a later author decides what to build.
     */
    private static Map<String, Set<String>> undeclaredSubtypeSets(DSLContext dsl) {
        var undeclared = new TreeMap<String, Set<String>>();
        for (var group : subtypeSets(dsl).entrySet()) {
            var payload = Set.of(group.getKey().split(","));
            Set<String> shared = null;
            for (String member : group.getValue()) {
                var referenced = referencedByWholePayload(dsl, member, payload);
                if (shared == null) {
                    shared = referenced;
                } else {
                    shared.retainAll(referenced);
                }
            }
            if (shared == null || shared.isEmpty()) {
                undeclared.put(group.getKey(), group.getValue());
            }
        }
        return undeclared;
    }

    /** The relations {@code relation} references by a foreign key naming every payload column. */
    private static Set<String> referencedByWholePayload(DSLContext dsl, String relation,
                                                        Set<String> payload) {
        var columnsPerConstraint = new TreeMap<String, Set<String>>();
        var parentPerConstraint = new TreeMap<String, String>();
        for (var row : dsl
                .select(field(name("c", "CONSTRAINT_NAME"), String.class),
                        field(name("k", "COLUMN_NAME"), String.class),
                        field(name("p", "TABLE_NAME"), String.class))
                .from(table(name("INFORMATION_SCHEMA", "TABLE_CONSTRAINTS")).as("c"))
                .join(table(name("INFORMATION_SCHEMA", "KEY_COLUMN_USAGE")).as("k"))
                .on(field(name("k", "CONSTRAINT_NAME"), String.class)
                    .eq(field(name("c", "CONSTRAINT_NAME"), String.class)))
                .join(table(name("INFORMATION_SCHEMA", "REFERENTIAL_CONSTRAINTS")).as("r"))
                .on(field(name("r", "CONSTRAINT_NAME"), String.class)
                    .eq(field(name("c", "CONSTRAINT_NAME"), String.class)))
                .join(table(name("INFORMATION_SCHEMA", "TABLE_CONSTRAINTS")).as("p"))
                .on(field(name("p", "CONSTRAINT_NAME"), String.class)
                    .eq(field(name("r", "UNIQUE_CONSTRAINT_NAME"), String.class)))
                .where(field(name("c", "TABLE_SCHEMA"), String.class).eq("PUBLIC"))
                .and(field(name("c", "TABLE_NAME"), String.class).equalIgnoreCase(relation))
                .and(field(name("c", "CONSTRAINT_TYPE"), String.class).eq("FOREIGN KEY"))
                .fetch()) {
            columnsPerConstraint
                .computeIfAbsent(row.value1(), constraint -> new TreeSet<>())
                .add(row.value2());
            parentPerConstraint.put(row.value1(), row.value3().toLowerCase(Locale.ROOT));
        }
        var referenced = new TreeSet<String>();
        for (var constraint : columnsPerConstraint.entrySet()) {
            if (constraint.getValue().containsAll(payload)) {
                referenced.add(parentPerConstraint.get(constraint.getKey()));
            }
        }
        return referenced;
    }

    /** Capture tables grouped by their payload, keyed by that payload for a readable failure. */
    private static Map<String, Set<String>> subtypeSets(DSLContext dsl) {
        var byPayload = new TreeMap<String, Set<String>>();
        for (String relation : captureTables(dsl)) {
            List<String> payload = payloadOf(dsl, relation);
            if (payload.isEmpty() || PROVENANCE.containsAll(payload)) {
                continue;
            }
            byPayload.computeIfAbsent(String.join(",", payload), key -> new TreeSet<>()).add(relation);
        }
        byPayload.values().removeIf(members -> members.size() < 2);
        return byPayload;
    }

    /**
     * The confirmed reconstruction sites. Membership comes from {@link ViewReferences}, so an
     * alias sharing a relation's name is not counted as a read of it; the union and the attribute
     * naming come from the stored definition, which is what the engine will actually inline.
     */
    private static Set<String> reconstructions(DSLContext dsl) {
        var found = new TreeSet<String>();
        var sets = undeclaredSubtypeSets(dsl);
        for (String view : views(dsl)) {
            String definition = definitionOf(dsl, view);
            if (definition == null || !definition.toUpperCase(Locale.ROOT).contains("UNION")) {
                continue;
            }
            Set<String> read = ViewReferences.relationsReadBy(dsl, view).stream()
                .map(relation -> relation.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
            for (var entry : sets.entrySet()) {
                var named = new TreeSet<>(entry.getValue());
                named.retainAll(read);
                if (named.size() < 2 || !namesAPayloadColumn(definition, entry.getKey())) {
                    continue;
                }
                found.add(view + "|" + String.join(",", named));
            }
        }
        return found;
    }

    private static boolean namesAPayloadColumn(String definition, String payload) {
        for (String column : payload.split(",")) {
            if (PROVENANCE.contains(column)) {
                continue;
            }
            if (Pattern.compile("\\b" + Pattern.quote(column) + "\\b", Pattern.CASE_INSENSITIVE)
                    .matcher(definition).find()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> captureTables(DSLContext dsl) {
        return dsl.select(field(name("TABLE_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "TABLES")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_TYPE"), String.class).eq("BASE TABLE"))
            .fetch(0, String.class).stream()
            .map(relation -> relation.toLowerCase(Locale.ROOT))
            .filter(relation -> CAPTURE_FAMILIES.stream().anyMatch(relation::startsWith))
            .sorted()
            .toList();
    }

    private static List<String> views(DSLContext dsl) {
        return dsl.select(field(name("TABLE_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "TABLES")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_TYPE"), String.class).eq("VIEW"))
            .fetch(0, String.class).stream()
            .map(view -> view.toLowerCase(Locale.ROOT))
            .sorted()
            .toList();
    }

    /** A relation's columns outside its own primary key, sorted so the grouping key is stable. */
    private static List<String> payloadOf(DSLContext dsl, String relation) {
        Set<String> key = new TreeSet<>(dsl.select(field(name("k", "COLUMN_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "TABLE_CONSTRAINTS")).as("c"))
            .join(table(name("INFORMATION_SCHEMA", "KEY_COLUMN_USAGE")).as("k"))
            .on(field(name("c", "CONSTRAINT_NAME"), String.class)
                .eq(field(name("k", "CONSTRAINT_NAME"), String.class)))
            .where(field(name("c", "TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("c", "TABLE_NAME"), String.class).equalIgnoreCase(relation))
            .and(field(name("c", "CONSTRAINT_TYPE"), String.class).eq("PRIMARY KEY"))
            .fetch(0, String.class));
        return dsl.select(field(name("COLUMN_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "COLUMNS")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_NAME"), String.class).equalIgnoreCase(relation))
            .fetch(0, String.class).stream()
            .filter(column -> !key.contains(column))
            .sorted()
            .toList();
    }

    private static String definitionOf(DSLContext dsl, String view) {
        return dsl.select(field(name("VIEW_DEFINITION"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "VIEWS")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_NAME"), String.class).equalIgnoreCase(view))
            .fetchAny(0, String.class);
    }

    private static void withStore(Consumer<DSLContext> body) {
        try (var store = FactStores.inMemory()) {
            body.accept(store.dsl());
        }
    }
}
