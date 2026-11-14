package no.sikt.graphitron.model;

import no.sikt.graphitron.model.derive.ViewReferences;
import no.sikt.graphitron.model.test.ThreadConfinedStore;
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
        List.of("graphql_", "graphitron_", "sql_", "code_");

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
     * <p>A directive application is no longer one of these sets. It has one shape at all five sites
     * it can be written, and it is one relation now, {@code graphql_ast_directive_application_entry},
     * whose site is the entry kind the index gives it and whose parent is the index row's. The
     * union the five site relations asked a reader for is gone rather than written once.
     *
     * <p>One row is a set whose supertype is written, and it is the only one: a union declares who
     * it admits and a type declares which interfaces it answers to, two facts with two declaring
     * ends and two keys, sharing the payload a reader wants when it does not care which it has.
     * {@code graphql_poly_member} is that union, done once as a view rather than in each of the two
     * readers that take either, which is the shape this gate says a reconstruction should become. It
     * appears on the reconstruction roster below for the same reason, and that row is a record of
     * the cure rather than of the defect.
     *
     * <p>The category of one fact written twice is empty, and it emptied by subtraction rather
     * than by anybody writing a supertype, which is what the rows in it always said would happen.
     * Three went. The verdict pair went when {@code graphql_schema_error} was retired for the
     * relation recording all three stages. The class census pair went with the census's classfile
     * relation, which was the successor rather than the incumbent: written and never read, and a successor
     * nothing reads is not stood up beside anything, so it was dropped rather than finished.
     *
     * <p>The arm that replaced the scalar-constant census never became a fourth row either, and the
     * reason is worth keeping now that its incumbent is gone too. {@code code_scalar_constant} was
     * stood up beside the census's scalar-field relation for one change, and this gate refused to see
     * them as one payload under two keys, which is the difference between a census re-read and an
     * arm: the arm admits what a directive may name where the incumbent recorded what a walk
     * resolved. Two populations that are not the same fact counted twice do not become one by
     * being about the same constants, and the incumbent was retired rather than reconciled.
     *
     * <p>One row left the roster in the same change and for the same reason read backwards.
     * The decode's {@code @error} entry, since retired, and the type-site application relation shared a payload only
     * while provenance was counted in it; what they actually shared is the declaration site, which
     * both declared as a foreign key into {@code graphql_type_declaration}. Their supertype was
     * written all along and the columns beside it were hiding that. The application relation has
     * since collapsed onto the coordinate with its four siblings, which took the declaration site
     * off it, so the pair cannot be looked up as it stood.
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
        // An occurrence's binding, whose payload is its kind alone: what each kind takes is its
        // subtype's. A node id's member slot joins it on the same terms: its payload is the kind its
        // supertype pins, and the bound it is keyed by is its own MEMBER binding's, which it
        // references rather than restates. A source claim carries a kind under the rule that claims
        // it, and a claimed table the kind beside the table: what each rule says, from which the
        // source, carrying the rule that took effect beside its kind, is written.
        Set.of("graphitron_occurrence_binding", "graphitron_occurrence_node_id_slot_member",
            "graphitron_occurrence_source_claim", "graphitron_occurrence_source_claim_table"),
        // A call and the methods it could make, whose payload is the call's kind alone: a candidate is
        // stated before the pin that decides which call exists, so it cannot key into the call, and
        // the call keeps the kind its subtypes key on. Two grains, not one fact under two keys.
        Set.of("graphitron_occurrence_call", "graphitron_occurrence_call_candidate"),
        // Two arms of one tenant slot supertype, each naming the binding its slot rests on by an
        // occurrence and a bound: the authored arm a tenant binding, the record arm a column binding.
        Set.of("graphitron_occurrence_tenant_slot_authored", "graphitron_occurrence_tenant_slot_record_column"),
        // How a join path's step joins, by which key it rode: a written foreign key and an inferred
        // one, and a table's own primary key ridden as a self-join and one matched by name from a
        // function's derived table. Siblings by decision, not a supertype owed: which relation holds
        // the row is how a reader tells a written key from an inferred one, and a self-join from a
        // name match. Each carries its key spelled and as the nullable copy the anchor rule asks
        // for, so no one foreign key covers the payload and the gate sees them.
        Set.of("graphitron_join_path_step_inferred_key", "graphitron_join_path_step_key"),
        Set.of("graphitron_join_path_step_matched_key", "graphitron_join_path_step_primary_key"),
        // Two members where there were six. The five that left were the argument relations of the
        // five application sites, and they shared a payload because they were one fact keyed five
        // ways; they are one relation now, keyed at the coordinate, so the sharing they were
        // recorded for is gone rather than discharged. What is left is the pair that genuinely
        // shares one: an argument an author passed and an argument the decode could not read. The
        // emitted population's arguments are the third, on graphitron_element's terms below: the
        // same fact over the generator's coordinates, an authored row carried across beside a
        // minted one, so a supertype would be the two families folded back into one.
        // The configured tags and the carrier directives joined when they became stored: each is a
        // rule's answer kept as a table because H2 evaluates a view again for every row joined to
        // it, so they carry the payload of what they are read from rather than being siblings of
        // it, and a supertype would fold a cache back into its source.
        Set.of("graphitron_carrier_directive", "graphitron_configured_tag",
               "graphitron_directive_application_arg", "graphql_directive_application_arg"),
        // What a key: spelling names, under the two kinds of key it can name: a foreign key, keyed by
        // the constraint, and a table's own primary key, keyed by the table. The shared payload is
        // only which namespace the spelling matched in. No supertype is owed: each relation's
        // foreign key into the catalog (sql_referential_constraint, sql_primary_key) is the integrity
        // rule saying what kind of key its rows are, and a relation over both would trade those for
        // a kind column and a nullable half of its key.
        Set.of("graphitron_path_element_key", "graphitron_path_element_primary_key"),
        // The legacy key-columns array is a second positional list of a table's columns, the same
        // payload as the current one under the same key.
        // The tenant column names one column of a table, as each of these names its columns, keyed
        // by the graph that configures it rather than by a constraint, an index or a key position.
        Set.of("sql_constraint_column", "sql_index_column", "sql_node_key_column",
               "sql_node_legacy_key_column", "graphitron_tenant_column"),
        Set.of("graphitron_element", "graphql_element"),

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
        Set.of("graphitron_ast_field_condition_entry", "graphitron_ast_input_value_condition_entry"),
        // An @enum application records a class, a method and an argMapping, which is what a path's
        // method-call segment records too, at every site and under either directive: it is written
        // the way every method call is, and its own relation is what says the method is a join at
        // that position, which a @condition's method is not. What it would take to collapse them is
        // a supertype at a coordinate neither has, so this set outlives the migration and this row
        // does not.
        Set.of("graphitron_ast_enum_entry", "graphitron_ast_reference_condition_step_entry"),
        // What the step split leaves in place of the two wide relations, one per arm now that both
        // directives and every site write the same arm to one relation. The table arm sits with
        // @table, because naming a table is one fact wherever it is written, and the condition arm
        // with the external-code-reference set above; the key arm shares its payload with nothing
        // and is no set at all.
        Set.of("graphitron_ast_reference_table_step_entry", "graphitron_ast_table_entry"),
        // A link's imports as written and the federated graph's imports, the second derived from the
        // first for the one link that opts the graph in, plus what graphitron applies itself. Decided:
        // the entry is per application, and the anchor per graph.
        Set.of("graphitron_ast_link_import_entry", "graphitron_federation_link_import"),
        // A producer's call stated per declared field and per occurrence: the occurrence producer's
        // write and routine are read off the field-keyed ones through the occurrence's field, and the
        // field-keyed ones leave this roster with the producer family when its readers have moved.
        Set.of("graphitron_occurrence_producer_write", "graphitron_producer_write"),
        Set.of("graphitron_occurrence_producer_routine", "graphitron_producer_routine"),
        // The enum-value site's two sorting pairs, on the terms every pair above is on: an
        // as-written relation beside the resolved one it will be derived into, both coming off this
        // roster together on the day that derivation lands.
        // @node's two list arguments, the current key and the legacy one, decoded as twins.
        Set.of("graphitron_ast_node_keycolumn_entry", "graphitron_ast_node_legacykeycolumn_entry"),
        // The voucher markers' field-set arguments, each parsed on the same terms: both markers'
        // claims and an @accept's key. Siblings by decision: each references its own marker's entry,
        // so a selection cannot hang off an application that is not that marker, which one relation
        // keyed on the bare directive application could not say, and a claim and a key are read by
        // different rules.
        Set.of("graphitron_ast_issue_claim_entry", "graphitron_ast_accept_claim_entry",
            "graphitron_ast_accept_key_entry"),
        // And each resolved: an @issue's claim to the member it names, an @accept's to the field of
        // its type. Siblings by decision: a member is no field and a field no member, so one relation
        // would carry a foreign key null on every row of the other marker.
        Set.of("graphitron_voucher_claim", "graphitron_accept_claim"),
        // One decode and one resolution of it, and the payload is one column because a deprecation
        // carries one thing: the replacement hint. The three anchors this used to name were one
        // relation each for a directive, an argument of one, and a field of an input object, and
        // the argument against collapsing them was that no two share a coordinate and a collapsed
        // relation would need a nullable one beside a column saying which of three things a row is
        // about. That turned out to be wrong in its premise rather than its reasoning: all three
        // spell a coordinate, and the grammar already tells them apart, so the collapsed relation
        // keys on the coordinate with neither a nullable column nor a discriminator.
        Set.of("graphitron_ast_input_value_deprecated_entry", "graphitron_deprecated"),
        // One defect at two grains, decided and not a supertype owed: the string's row is the
        // verdict a position carries, and the entry's row is the specific of each entry under it,
        // tied by a foreign key so the verdict's sweep collects the details. The payload is the
        // same column because a detail is one thing at either grain; folding them would put a
        // nullable position beside a discriminator saying which grain a row is.
        Set.of("graphitron_argmapping_pair_defect", "graphitron_entry_defect"));

    /**
     * Every view that reconstructs a set by unioning its members and naming its payload, as
     * {@code view|member,member}. Each is a supertype this schema owes, and the shape is the same
     * in all of them: an argument-site relation unioned with its field-site twin, because the
     * author may write the same directive at either coordinate and no relation says so once.
     */
    private static final Set<String> RECONSTRUCTIONS = Set.of(
        "graphql_poly_member|graphql_implements_interface,graphql_union_member",
        // The rule graphitron_type_member is stored from: the supertype the poly split owes, written
        // as a table at the expanded schema's grain, so its readers stop reconstructing it.
        "graphitron_type_member_rule|graphql_implements_interface,graphql_union_member",
        // The carriers' tags, authored or configured: the union graphitron_directive_application_arg
        // makes for every coordinate, made here for carriers alone, and stored for the reason the
        // set above gives.
        "graphitron_carrier_directive_rule|graphitron_configured_tag,graphql_directive_application_arg",
        // The voucher stage's refusals, which judge both markers' claims by the rules they share,
        // an empty or repeated claim, and resolve each against its own marker's claim relation. The
        // supertype it reconstructs is the sibling pair recorded above, kept apart by decision.
        "graphitron_voucher_entry_defect_rule|graphitron_ast_accept_claim_entry,graphitron_ast_issue_claim_entry",
        "graphitron_voucher_entry_defect_rule|graphitron_accept_claim,graphitron_voucher_claim");



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
     * carrying part of one is a different fact entirely. The retired {@code @error} decode entry and the type-site
     * application relation shared five columns and both pointed three of them at
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
        ThreadConfinedStore.run(body);
    }
}
