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
 * <p>Grouping on every non-key column would report two large sets that are not sets at all. Every
 * captured row carries where it was read from and when the reading ran, so {@link #PROVENANCE} is
 * shared by everything that shares nothing, and the element relations carry no fact whatever. Both
 * fall out on a rule rather than by exemption: provenance is not part of a payload, and a set's
 * payload must be non-empty. Sharing only where you came from, or only that you were read, is not
 * sharing a fact.
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

    /**
     * Where a captured row was read from and when the reading ran. Shared by every capture relation,
     * so it is not a fact about any of them.
     *
     * <p>{@code TOUCHED_AT} sits here for the reason the three positions do. It records the reading
     * rather than the row, every relation a mark and sweep governs carries it for that one purpose,
     * and two relations agreeing that they were both read are not two relations sharing a payload.
     * Counting it would report a set every time a family gained a sweep.
     */
    private static final Set<String> PROVENANCE =
        Set.of("SOURCE_NAME", "SOURCE_LINE", "SOURCE_COLUMN", "TOUCHED_AT");

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
     *
     * <p>Two rows are neither a supertype owed nor a reference list, and both come from the element
     * family the generator emits. {@code graphitron_element} and {@code graphql_element} carry one
     * column apiece and it is the same column, because the second is the first's population widened
     * by what macro expansion minted: two populations of one shape, which is the whole point of the
     * pair, where a supertype over them would say an element exists in one graph twice.
     * {@code graphitron_field} and {@code graphitron_argument} share a payload for a reason the
     * specification already gives: a field and a field argument both carry a type expression, its
     * four decomposed wrappers, a default and a description, and the specification declares no
     * element they are both kinds of. The supertype they do share is written, and it is
     * {@code graphitron_element}, which carries what the two have in common at the grain they have
     * it in common at: the coordinate and the kind. Lifting the type expression up there would put
     * eight nullable columns on a supertype whose type rows can never fill them.
     *
     * <p>One of these sets is chosen, and the payload is identical because the node is: a directive
     * application has one shape at all five sites it can be written. What differs is the parent,
     * which is a different relation at each site, so one relation per site is what lets a row name
     * its parent by key where a relation per node kind could only carry a position. The union the
     * set asks a reader for is the anchors' work and happens once into a relation rather than in
     * every reader, which is the cost this gate is really about.
     *
     * <p>One row is a set whose supertype is written, and it is the only one: a union declares who
     * it admits and a type declares which interfaces it answers to, two facts with two declaring
     * ends and two keys, sharing the payload a reader wants when it does not care which it has.
     * {@code graphql_poly_member} is that union, done once as a view rather than in each of the two
     * readers that take either, which is the shape this gate says a reconstruction should become. It
     * appears on the reconstruction roster below for the same reason, and that row is a record of
     * the cure rather than of the defect.
     *
     * <p>Two rows are one fact written twice while a successor is being stood up beside its
     * incumbent, and each goes when the incumbent does rather than when somebody writes a supertype.
     * {@code jvm_class} and {@code jvm_classfile}, with their supertype twins, are the class census
     * read two ways. Naming them here is not an exemption: a supertype over the pair would be the
     * wrong answer, because it is not two kinds of one thing but one thing counted twice, and the
     * fix is the subtraction that is already planned. It was three rows until the verdict pair went,
     * and that one is the worked example of the sentence above: {@code graphql_schema_error}
     * recorded per relation what {@code graphql_schema_problem} records per stage, and when the
     * incumbent was retired the row left this roster rather than earning a supertype.
     *
     * <p>One row left the roster in the same change and for the same reason read backwards.
     * {@code graphitron_error_entry} and {@code graphql_type_directive} shared a payload only while
     * provenance was counted in it; what they actually share is the declaration site, which both
     * declare as a foreign key into {@code graphql_type_declaration}. Their supertype was written
     * all along and the columns beside it were hiding that.
     *
     * <p>Worth recording how they became visible, because it says something about the rule above
     * rather than about them. Each pair was invisible while one side carried a mark-and-sweep stamp
     * the other did not, the stamp differing their payloads and so keeping them apart. Excluding
     * provenance from the payload is what let the gate see that the two say the same thing, which is
     * the gate working rather than the gate being widened.
     *
     * <p>The input-value site's decode joined six of these sets rather than adding any of its own,
     * and that is the split being right rather than the roster growing. A directive the definition
     * admits at both sites decodes to the same payload at each, so every one of its rows lands in
     * the set its field-site twin was already in; the two path-step relations join the four already
     * there for the same reason, an element being one input type both directives spell. Nothing
     * about a decode differs by site except the relation it hangs from, which is the key, and a key
     * is what this gate subtracts before it compares.
     *
     * <p>The input-value three were such a set until each began spelling its own coordinate. They
     * are the same node to the parser still, but a coordinate is spelled from the row's ancestors
     * and the three sites have different ones: an input field names the declaration it sits in, a
     * field argument names both the field and the declaration, and a directive argument names
     * neither of those and is not an element the corpus keys by coordinate at all. The signatures
     * differ because the thing each row can say about itself differs, which is the split doing its
     * job rather than drifting.
     */
    private static final Set<Set<String>> SUBTYPE_SETS = Set.of(
        Set.of("graphql_implements_interface", "graphql_union_member"),
        Set.of("jvm_class", "jvm_classfile"),
        Set.of("jvm_class_supertype", "jvm_classfile_supertype"),
        Set.of("graphql_ast_enum_value_directive_entry", "graphql_ast_field_directive_entry",
               "graphql_ast_input_value_directive_entry", "graphql_ast_schema_directive_entry",
               "graphql_ast_type_directive_entry"),
        Set.of("graphitron_undecoded_argument_entry", "graphql_argument_directive_arg",
               "graphql_enum_value_directive_arg", "graphql_field_directive_arg",
               "graphql_schema_directive_arg", "graphql_type_directive_arg"),
        Set.of("graphitron_argument_reference_for_step_entry", "graphitron_argument_reference_step_entry",
               "graphitron_field_reference_step_entry", "graphitron_reference_for_step_entry"),
        Set.of("sql_constraint_column", "sql_index_column", "sql_node_key_column"),
        Set.of("graphitron_argument_binding_entry", "graphitron_field_binding_entry"),
        Set.of("graphitron_element", "graphql_element"),
        Set.of("graphitron_argument", "graphitron_field"),

        // The rest of this roster is the decode migration, and it reads as one thing rather than
        // as eleven. A position-keyed entry relation and the coordinate-keyed relation it will be
        // derived into carry the same payload by construction: that is what makes the derivation a
        // SELECT with nothing decided in it, and it is exactly what this gate reports. So each set
        // below pairs an as-written relation with the resolved one beside it, or gathers several
        // that already shared a payload before either arrived, and none of them is a supertype the
        // schema owes: the day the anchors are derived from the entries the old writer goes, the
        // resolved member of each pair goes with it, and the row comes off this roster. Until then
        // the roster is the ledger of what that commit has to delete, which is why the pairs are
        // spelled out here rather than exempted by a rule over the name.
        Set.of("graphitron_argument_condition_context_arg_entry",
               "graphitron_ast_field_condition_context_arg_entry",
               "graphitron_ast_input_value_condition_context_arg_entry",
               "graphitron_ast_service_context_arg_entry",
               "graphitron_field_condition_context_arg_entry",
               "graphitron_service_context_arg_entry"),
        Set.of("graphitron_argument_condition_entry", "graphitron_ast_field_condition_entry",
               "graphitron_ast_input_value_condition_entry", "graphitron_field_condition_entry"),
        // Seven, and they arrived by being stamped: an @enum application records a class, a method
        // and an argMapping, which is what a service and an external field record too, at either
        // stratum. The two newest are the condition arm of a path element, which the step split put
        // here: an element correlating by an external method records the same three things, and
        // under the wide step row that was invisible because the row also carried six catalog
        // columns. What it would take to collapse them is a supertype at a coordinate none of them
        // has, so the entry half of this set outlives the migration and this row does not.
        Set.of("graphitron_ast_enum_entry", "graphitron_ast_external_field_entry",
               "graphitron_ast_input_value_reference_condition_step_entry",
               "graphitron_ast_input_value_reference_for_condition_step_entry",
               "graphitron_ast_service_entry", "graphitron_external_field_entry",
               "graphitron_service_entry"),
        Set.of("graphitron_ast_default_order_field_entry", "graphitron_default_order_field_entry",
               "graphitron_order_field_entry"),
        Set.of("graphitron_argument_node_id_entry", "graphitron_ast_field_node_id_entry",
               "graphitron_ast_input_value_node_id_entry", "graphitron_field_node_id_entry"),
        Set.of("graphitron_argument_reference_for_entry",
               "graphitron_ast_field_reference_for_entry",
               "graphitron_ast_input_value_reference_for_entry", "graphitron_reference_for_entry"),
        // The two path-carrying directives spell an element identically, so their step relations
        // share a payload at the entry stratum exactly as their anchors already do on the row
        // above this roster's decode block. The anchor pair is told apart from this one only by
        // the folded columns the anchors carry for meeting a catalog name.
        Set.of("graphitron_ast_field_reference_for_step_entry",
               "graphitron_ast_field_reference_step_entry"),
        // What the input-value site's step split leaves in its place, and the grouping is the split
        // being right rather than a roster growing. Each arm now sits with the relations that say
        // the same thing it says: the key arm is its own pair, the table arm joins @table because
        // naming a table is one fact wherever it is written, and the condition arm joins the
        // external-code-reference set above. None of that was visible while one row carried all
        // three, which is the wide shape's real cost.
        Set.of("graphitron_ast_input_value_reference_for_key_step_entry",
               "graphitron_ast_input_value_reference_key_step_entry"),
        Set.of("graphitron_ast_input_value_reference_for_table_step_entry",
               "graphitron_ast_input_value_reference_table_step_entry",
               "graphitron_ast_table_entry"),
        Set.of("graphitron_ast_connection_entry", "graphitron_connection_entry"),
        Set.of("graphitron_ast_default_order_entry", "graphitron_default_order_entry"),
        // The field-site binding entry pairs with the enum-value anchor rather than with the field
        // anchor, which carries a folded twin of its one column and so shares no payload with
        // anything. A pair the migration dissolves all the same, the enum-value site's own entry
        // being what replaces the member that stays.
        Set.of("graphitron_ast_field_binding_entry", "graphitron_ast_input_value_binding_entry",
               "graphitron_enum_value_binding_entry"),
        Set.of("graphitron_ast_pivot_entry", "graphitron_pivot_entry"));

    /**
     * Every view that reconstructs a set by unioning its members and naming its payload, as
     * {@code view|member,member}. Each is a supertype this schema owes, and the shape is the same
     * in all of them: an argument-site relation unioned with its field-site twin, because the
     * author may write the same directive at either coordinate and no relation says so once.
     *
     * <p>The context-argument row's set has a third member the reconstruction deliberately does not
     * union, {@code graphitron_service_context_arg_entry}: a condition parameter's roles are a different
     * rule from a service parameter's. Its supertype therefore belongs at capture, keyed on the site
     * a reader filters by, rather than as a union in a derived view.
     *
     * <p>{@code intent_reference_for_application} is the newest row and the one worth reading as an
     * improvement rather than as a regression. Its two arms were previously spelled inside one
     * reader's {@code WHERE} clause, twice, so the reconstruction existed and this gate could not
     * see it; naming the resolution as a relation is what made it visible here, and what a second
     * reader now joins instead of respelling. The supertype it owes is the same one every other row
     * owes: {@code @referenceFor} written at an argument and at a field is one fact at two
     * coordinates, and the set is on the roster above with nothing declared over it.
     */
    private static final Set<String> RECONSTRUCTIONS = Set.of(
        "graphql_poly_member|graphql_implements_interface,graphql_union_member",
        // The union the poly split leaves behind, spelled once here rather than at each reader.
        // Over the two base relations and not over graphql_poly_member, which says the same thing:
        // that view is for readers outside this store, and going through it would put a second view
        // body in every detection component that asks this question.
        "intent_poly_member|graphql_implements_interface,graphql_union_member",
        "intent_argument_filter_role|graphitron_argument_condition_entry,graphitron_field_condition_entry",
        "intent_condition_context_parameter|graphitron_argument_condition_context_arg_entry,graphitron_field_condition_context_arg_entry",
        "intent_condition_method_route|graphitron_argument_reference_step_entry,graphitron_field_reference_step_entry",
        "intent_condition_method_route_defect|graphitron_argument_reference_step_entry,graphitron_field_reference_step_entry",
        "intent_condition_param_decode|graphitron_argument_condition_entry,graphitron_field_condition_entry",
        "intent_field_demand_rule|graphitron_external_field_entry,graphitron_service_entry",
        "intent_field_exemption_rule|graphitron_external_field_entry,graphitron_service_entry",
        "intent_field_producer_reference|graphitron_external_field_entry,graphitron_service_entry",
        "intent_input_occurrence_override|graphitron_argument_condition_entry,graphitron_field_condition_entry",
        "intent_node_id_instruction_live|graphitron_argument_node_id_entry,graphitron_field_node_id_entry",
        "intent_reference_for_application|graphitron_argument_reference_for_entry,graphitron_reference_for_entry",
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
     * carrying part of one is a different fact entirely. graphitron_error_entry and
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
     * not carry what the reader wants. The element family is the worked example. Its four
     * members share {@code graphql_element}, and a reader holding a coordinate and wanting the
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
            if (payload.isEmpty()) {
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

    /**
     * A relation's facts: its columns outside its own primary key and outside {@link #PROVENANCE},
     * sorted so the grouping key is stable.
     *
     * <p>Provenance is subtracted here rather than tested for afterwards, and the difference is not
     * cosmetic. A set whose whole payload is a foreign key into one relation is a set whose
     * supertype is already written, which is how the four key-to-coordinate maps resolve against
     * {@code graphql_element}. Leaving a column every swept relation carries in the payload hides
     * that recognition behind it, so a family would appear to owe a supertype on the day it gained
     * a mark and sweep. Where a row came from and when it was read are bookkeeping in the same
     * sense, and neither is a fact the relation states.
     */
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
            .filter(column -> !PROVENANCE.contains(column))
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
