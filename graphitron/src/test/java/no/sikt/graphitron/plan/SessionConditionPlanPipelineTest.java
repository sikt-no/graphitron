package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.CallWrap;
import no.sikt.graphitron.command.Contribution;
import no.sikt.graphitron.command.ProjectionCommand;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.render.ConditionGlueRenderer;
import no.sikt.graphitron.render.ProjectionUnitRenderer;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plan and render half of a session-reading {@code @condition}: the condition row's glue takes
 * the statement's {@code dsl}, the projection relation marks the units whose {@code $project} must
 * take it (the unit calling the glue and, transitively, every unit calling one of those, across a
 * cycle), and the renderers append the argument for exactly those callees.
 *
 * <p>The schema is a cycle on purpose: {@code Store.customers} carries the session-reading
 * condition and {@code Customer.store} reaches {@code Store} back, so the closure has to terminate
 * on a type reaching itself. {@code Language} reaches neither and keeps its signature.
 */
@PipelineTier
class SessionConditionPlanPipelineTest {

    private static final String SDL = """
        type Store @table(name: "store") {
            storeId: Int! @field(name: "STORE_ID")
            customers: [Customer!]! @condition(condition: {
                className: "no.sikt.graphitron.rewrite.TestConditionStub",
                method: "sessionBoxed",
                argMapping: "brukerId: $session.brukerId"})
        }
        type Customer @table(name: "customer") {
            firstName: String! @field(name: "FIRST_NAME")
            store: Store
        }
        type Language @table(name: "language") {
            name: String @field(name: "NAME")
        }
        type Query { stores: [Store!]! languages: [Language!]! }
        """;

    private static no.sikt.graphitron.rewrite.GraphitronSchema schema() {
        return TestSchemaHelper.buildSchema(SDL);
    }

    private static Set<String> simpleNames(Set<no.sikt.graphitron.command.UnitRef> units) {
        return units.stream().map(no.sikt.graphitron.command.UnitRef::simpleName)
            .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void conditionRow_readsTheSession_andItsGlueTakesDslAfterTheArguments() {
        var schema = schema();
        var conditions = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);

        var row = conditions.rows().stream()
            .filter(r -> r.coordinate().getFieldName().equals("customers")).findFirst().orElseThrow();
        assertThat(row.readsSession()).isTrue();
        assertThat(row.readsRequestContext()).isFalse();

        var glue = ConditionGlueRenderer.render(conditions.rows(), DEFAULT_OUTPUT_PACKAGE,
                no.sikt.graphitron.command.KeyProjectionRelation.empty()).stream()
            .flatMap(t -> t.methodSpecs().stream())
            .filter(m -> m.name().equals(row.glue().methodName())).findFirst().orElseThrow();
        assertThat(glue.parameters()).extracting(p -> p.name()).containsExactly("table", "args", "dsl");
    }

    @Test
    void projectionClosure_marksTheGlueCallerAndItsCallers_andTerminatesOnTheCycle() {
        var schema = schema();
        var conditions = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        var relation = ProjectionCommands.produce(schema, conditions, DEFAULT_OUTPUT_PACKAGE);

        assertThat(simpleNames(relation.dslProjections()))
            .as("Store calls the session-reading glue; Customer calls Store; Language reaches neither")
            .containsExactlyInAnyOrder("Store", "Customer");
        assertThat(relation.rows()).filteredOn(r -> r.unit().simpleName().equals("Language"))
            .singleElement().satisfies(row -> assertThat(row.takesDsl()).isFalse());

        var storeCallsCustomer = relation.rows().stream()
            .filter(r -> r.unit().simpleName().equals("Store"))
            .flatMap(r -> r.contributions().stream())
            .filter(c -> c instanceof Contribution.Call call && call.wrap() instanceof CallWrap.Multiset)
            .map(c -> (Contribution.Call) c).findFirst().orElseThrow();
        assertThat(storeCallsCustomer.calleeTakesDsl()).as("Customer's $project takes dsl").isTrue();
    }

    @Test
    void renderedProjections_takeDslExactlyWhereTheClosureSays() {
        var schema = schema();
        var conditions = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        var relation = ProjectionCommands.produce(schema, conditions, DEFAULT_OUTPUT_PACKAGE);

        List<TypeSpec> units = ProjectionUnitRenderer.render(relation.rows(), DEFAULT_OUTPUT_PACKAGE);
        var byName = units.stream().collect(java.util.stream.Collectors.toMap(TypeSpec::name, t -> t));

        assertThat(project(byName.get("Store")).parameters()).extracting(p -> p.name())
            .containsExactly("grouped", "table", "env", "dsl");
        assertThat(project(byName.get("Customer")).parameters()).extracting(p -> p.name())
            .containsExactly("grouped", "table", "env", "dsl");
        assertThat(project(byName.get("Language")).parameters()).extracting(p -> p.name())
            .containsExactly("grouped", "table", "env");
    }

    private static com.palantir.javapoet.MethodSpec project(TypeSpec unit) {
        return unit.methodSpecs().stream().filter(m -> m.name().equals("$project")).findFirst().orElseThrow();
    }
}
