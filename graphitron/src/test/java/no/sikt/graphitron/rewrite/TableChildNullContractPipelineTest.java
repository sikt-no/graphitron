package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.command.CallWrap;
import no.sikt.graphitron.command.Contribution;
import no.sikt.graphitron.command.LaunchSource;
import no.sikt.graphitron.command.PresenceProbe;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.plan.ConditionCommands;
import no.sikt.graphitron.plan.LauncherCommands;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.Presence;
import no.sikt.graphitron.rewrite.model.Presence.NullContract;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The null contract of a single-object child with a table of its own, inline ({@code TableField})
 * and {@code @splitQuery} ({@code BatchedTableField}), classified from SDL: the contract its return
 * type's fields give it, and the presence columns and probe its carrier receives, the inline
 * multiset arm's for the one and the batched rows method's correlated source for the other.
 * Assertions are on the model and the commands, not on generated method bodies.
 */
@PipelineTier
class TableChildNullContractPipelineTest {

    private static final String SDL = """
        type Actor @table(name: "actor") { firstName: String @field(name: "FIRST_NAME") }
        type Language @table(name: "language") {
            name: String
            films: [Film!]! @reference(path: [{key: "film_language_id_fkey"}])
        }
        type Film @table(name: "film") {
            title: String
            language: Language @reference(path: [{key: "film_language_id_fkey"}])
            languageSplit: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            languageRequired: Language! @reference(path: [{key: "film_language_id_fkey"}])
            actors: [Actor!]! @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
        }
        type Query { films: [Film!]! }
        """;

    private static NullContract contractOf(GraphitronSchema schema, String field) {
        return schema.nullContractOf((ChildField) schema.field("Film", field));
    }

    @Test
    void inlineAndSplitQueryChild_collapseOverTheirReturnTypesLeaves() {
        var schema = TestSchemaHelper.buildSchema(SDL);
        var inline = contractOf(schema, "language");
        assertThat(inline).isInstanceOfSatisfying(NullContract.CollapsesWhenAbsent.class, c -> {
            assertThat(c.presenceColumns()).extracting(col -> col.sqlName()).containsExactly("name");
            assertThat(c.probed()).singleElement()
                .isInstanceOfSatisfying(no.sikt.graphitron.rewrite.model.Presence.PresenceLeaf.Probe.class, p -> assertThat(p.field().name()).isEqualTo("films"));
        });
        // @splitQuery is a delivery choice: the same type, the same contract.
        assertThat(contractOf(schema, "languageSplit")).isEqualTo(inline);
    }

    @Test
    void nonNullChild_isAlwaysAnObject() {
        assertThat(contractOf(TestSchemaHelper.buildSchema(SDL), "languageRequired"))
            .isEqualTo(new NullContract.AlwaysObject(new NullContract.Reason.NonNull()));
    }

    @Test
    void inlineChild_multisetArmCarriesThePresenceColumnsAndProbe() {
        var multiset = multiset(TestSchemaHelper.buildSchema(SDL), "language");
        assertThat(multiset.presence()).extracting(c -> c.sqlName()).containsExactly("name");
        assertThat(multiset.probe()).hasValueSatisfying(p -> {
            assertThat(p.alias()).isEqualTo("__p_Film$language");
            assertThat(p.columnLeaves()).singleElement()
                .satisfies(l -> assertThat(l).extracting(c -> c.sqlName()).containsExactly("name"));
            assertThat(p.terms()).singleElement().isInstanceOfSatisfying(no.sikt.graphitron.command.PresenceProbe.ProbeTerm.Exists.class, t -> {
                assertThat(t.reach().size()).isEqualTo(1);
                assertThat(t.targetColumns()).isEmpty();
            });
        });
    }

    @Test
    void nonNullAndListChildren_carryNoPresence() {
        var schema = TestSchemaHelper.buildSchema(SDL);
        var required = multiset(schema, "languageRequired");
        assertThat(required.presence()).isEmpty();
        assertThat(required.probe()).isEmpty();
        var list = multiset(schema, "actors");
        assertThat(list.presence()).isEmpty();
        assertThat(list.probe()).isEmpty();
    }

    @Test
    void splitQueryChild_correlatedSourceCarriesThePresenceColumnsAndProbe() {
        var schema = TestSchemaHelper.buildSchema(SDL);
        var conditions = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        var row = LauncherCommands.produce(schema, conditions, DEFAULT_OUTPUT_PACKAGE)
            .rowFor("Film", "languageSplit").orElseThrow();
        var chain = (LaunchSource.CorrelatedChain) row.source();
        assertThat(chain.presence()).extracting(c -> c.sqlName()).containsExactly("name");
        assertThat(chain.probe()).hasValueSatisfying(p -> {
            assertThat(p.alias()).isEqualTo("__p_Film$languageSplit");
            assertThat(p.terms()).singleElement().isInstanceOfSatisfying(no.sikt.graphitron.command.PresenceProbe.ProbeTerm.Exists.class, t -> assertThat(t.reach().size()).isEqualTo(1));
        });
    }

    @Test
    void sharedNestedType_presenceDecidedDifferentlyAtTwoSites_rejects() {
        // Under Content, Inner.film is a reference and probes; under Film it reads Film's own row
        // and blocks. One fetcher on Wrap cannot read a probe only one site projects; the
        // nested-shape check rejects the pair before the presence comparison, which backstops it.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String wrap: Wrap }
            type Inner { film: Film }
            type Wrap { inner: Inner }
            type Content @table(name: "content") { wrap: Wrap }
            type Query { a: Content b: Film }
            """);
        assertThat(new GraphitronSchemaValidator().validate(schema).stream().map(ValidationError::message))
            .anySatisfy(m -> assertThat(m).contains("shared across 'Content' and 'Film'"));
    }

    private static final String COMPUTED_SDL = """
        type RatedFilm @table(name: "film") {
            title: String
            rating: String @externalField(reference: {className: "no.sikt.graphitron.rewrite.TestExternalFieldStub", method: "rating"})
        }
        type Inventory @table(name: "inventory") {
            film: RatedFilm @reference(path: [{key: "inventory_film_id_fkey"}])
            filmSplit: RatedFilm @splitQuery @reference(path: [{key: "inventory_film_id_fkey"}])
        }
        type Query { inventories: [Inventory!]! }
        """;

    @Test
    void childHoldingAColumnAndAComputedField_bothCarriersCarryTheColumnAndTheNullTerm() {
        var schema = TestSchemaHelper.buildSchema(COMPUTED_SDL);
        var contract = schema.nullContractOf((ChildField) schema.field("Inventory", "film"));
        assertThat(contract).isInstanceOfSatisfying(NullContract.CollapsesWhenAbsent.class, c -> {
            assertThat(c.presenceColumns()).extracting(col -> col.sqlName()).containsExactly("title");
            assertThat(c.probed()).singleElement().isInstanceOfSatisfying(Presence.PresenceLeaf.Computed.class,
                l -> assertThat(l.field().name()).isEqualTo("rating"));
        });

        var multiset = (CallWrap.Multiset) ((Contribution.Call) ProjectionRenderTestSupport.contribution(
            schema, DEFAULT_OUTPUT_PACKAGE, "Inventory", "film")).wrap();
        assertThat(multiset.presence()).extracting(c -> c.sqlName()).containsExactly("title");
        assertThat(multiset.probe()).hasValueSatisfying(p -> {
            assertThat(p.alias()).isEqualTo("__p_Inventory$film");
            assertThat(p.columnLeaves()).singleElement()
                .satisfies(l -> assertThat(l).extracting(c -> c.sqlName()).containsExactly("title"));
            assertThat(p.terms()).singleElement().isInstanceOfSatisfying(PresenceProbe.ProbeTerm.NotNull.class,
                t -> assertThat(t.method().methodName()).isEqualTo("rating"));
        });

        var conditions = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        var chain = (LaunchSource.CorrelatedChain) LauncherCommands.produce(schema, conditions, DEFAULT_OUTPUT_PACKAGE)
            .rowFor("Inventory", "filmSplit").orElseThrow().source();
        assertThat(chain.presence()).extracting(c -> c.sqlName()).containsExactly("title");
        assertThat(chain.probe()).hasValueSatisfying(p -> {
            assertThat(p.alias()).isEqualTo("__p_Inventory$filmSplit");
            assertThat(p.terms()).singleElement().isInstanceOfSatisfying(PresenceProbe.ProbeTerm.NotNull.class,
                t -> assertThat(t.method().methodName()).isEqualTo("rating"));
        });
    }

    private static CallWrap.Multiset multiset(GraphitronSchema schema, String field) {
        var contribution = ProjectionRenderTestSupport.contribution(schema, DEFAULT_OUTPUT_PACKAGE, "Film", field);
        return (CallWrap.Multiset) ((Contribution.Call) contribution).wrap();
    }
}
