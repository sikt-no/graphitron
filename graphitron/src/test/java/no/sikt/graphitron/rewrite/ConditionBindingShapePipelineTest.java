package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The binding shape a {@code @condition}'s same-named declarations must agree on, and what the model
 * carries once they do. Same-named declarations are one {@code @condition} target when they agree on
 * the binding shape: same parameter count; position by position either {@code Table}-assignable
 * everywhere or identical in name and declared type everywhere; same static-ness, return type and
 * declared {@code throws} clause. Nothing else has to agree, because nothing else reaches emitted
 * code: the glue's table parameter is typed from the coordinate, so the emitted call site is
 * identical for every member of such a set and the consumer's javac performs the overload selection.
 *
 * <p>Pipeline tier because the facts under assertion need a real catalog: a table slot's decided
 * answer is a catalog lookup on each declared type, which the unit tier's table-free
 * {@code BuildContext} cannot give.
 *
 * <p>The per-participant lowering of an admitted set, and the parity of the refusal across
 * coordinates, live in {@code MultiTableFilterLoweringTest} beside the rest of the multitable filter
 * material; what is here is the shape rule itself and the slot facts admission decides.
 */
@PipelineTier
class ConditionBindingShapePipelineTest {

    private static final String STUB = "no.sikt.graphitron.rewrite.TestConditionStub";

    @Test
    void anAdmittedSetsTableSlotCarriesEveryDeclarationsTable() {
        // Two declarations differing only in their table slots. The reflected shape is deterministic
        // because it is folded from the whole set rather than picked from one declaration, so the
        // case may assert the slot directly: its decided answer names both admitted tables, in
        // declaration order, and nothing downstream has to re-decode a type name to learn them.
        var schema = TestSchemaHelper.buildSchema("""
            type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
            type Staff @table(name: "staff") { firstName: String @field(name: "first_name") }
            union Occupant = Customer | Staff
            type Query {
                occupants(firstName: String @field(name: "first_name") @condition(condition: {
                    className: "%s", method: "occupantNameOverload"})): [Occupant!]!
            }
            """.formatted(STUB));
        var field = schema.field("Query", "occupants");
        assertThat(field).isInstanceOf(QueryField.QueryUnionField.class);
        var union = (QueryField.QueryUnionField) field;
        for (var pf : union.participantFilters()) {
            var cf = conditionFilter(pf.filters());
            assertThat(cf.methodName()).isEqualTo("occupantNameOverload");
            var slot = (ParamSource.Table) cf.params().get(0).source();
            assertThat(slot.slot())
                .as("both declared table types resolve, so the slot is bound rather than wildcard")
                .isInstanceOf(ParamSource.Table.TableSlot.Bound.class);
            assertThat(((ParamSource.Table.TableSlot.Bound) slot.slot()).tableRefs())
                .extracting(TableRef::tableName)
                .containsExactlyInAnyOrder("customer", "staff");
            assertThat(cf.params().get(1).name()).isEqualTo("firstName");
            assertThat(cf.params().get(1).source()).isInstanceOf(ParamSource.Arg.class);
        }
    }

    @Test
    void aWildcardTableSlotIsDecidedAsSuchRatherThanLeftToATypeNameDecode() {
        // The single-method form, for the arm the concrete set contrasts with: one Table<?> slot
        // names no table, so the slot's decided answer is the wildcard arm and every reader that
        // used to spell the wildcard string predicate for itself now reads the same value.
        var schema = TestSchemaHelper.buildSchema("""
            type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
            type Query {
                customers(firstName: String @field(name: "first_name") @condition(condition: {
                    className: "%s", method: "occupantsFirstName"})): [Customer!]!
            }
            """.formatted(STUB));
        var field = schema.field("Query", "customers");
        assertThat(field).isNotInstanceOf(UnclassifiedField.class);
        var cf = conditionFilter(((QueryField.QueryTableField) field).filters());
        assertThat(((ParamSource.Table) cf.params().get(0).source()).slot())
            .isInstanceOf(ParamSource.Table.TableSlot.Wildcard.class);
    }

    @Test
    void aTableSlotNamedAfterAFieldArgumentDoesNotClaimTheArgumentsSlot() {
        // A table parameter named after the field's only argument. A table slot never claims a
        // GraphQL slot, so the argument stays unclaimed and type-based inference binds `kriterier`
        // to it. Reading the table slot's name as a claim left the slot claimed, inference returned
        // early, and `kriterier` fell through to "not a GraphQL argument and not a context key".
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter { title: String @field(name: "title") }
            type Query {
                films(film: FilmFilter): [Film!]! @condition(condition: {
                    className: "%s", method: "tableSlotNamedLikeAnArgument"})
            }
            """.formatted(STUB));
        var field = schema.field("Query", "films");
        assertThat(field)
            .as("the rejection would read: parameter 'kriterier' is not a GraphQL argument")
            .isNotInstanceOf(UnclassifiedField.class);
        var cf = conditionFilter(((QueryField.QueryTableField) field).filters());
        assertThat(cf.params().get(0).name())
            .as("the slot keeps the author's name; it is simply invisible as a binding target")
            .isEqualTo("film");
        assertThat(cf.params().get(1).name()).isEqualTo("kriterier");
        assertThat(cf.params().get(1).source())
            .as("the filter bean binds to the argument the table slot was named after")
            .isInstanceOf(ParamSource.Arg.class);
        assertThat(((ParamSource.Arg) cf.params().get(1).source()).path().headName())
            .isEqualTo("film");
    }

    /** The developer {@code @condition} in a field's filter list; fails the case when absent. */
    private static ConditionFilter conditionFilter(List<WhereFilter> filters) {
        return filters.stream()
            .filter(f -> f instanceof ConditionFilter)
            .map(f -> (ConditionFilter) f)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no developer @condition in " + filters));
    }
}
