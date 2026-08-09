package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The input-field fan-in reports one located fact per failure, not one joined sentence at the
 * consuming coordinate. Each failure is minted into the build-time diagnostic channel at the input
 * field that carries it, keeping its typed {@link Rejection} arm, while the consuming field keeps a
 * single rejection stating the consequence.
 *
 * <p>Counts are asserted rather than mere presence throughout. Input fields resolve once per
 * consuming field and the validator walks the classified schema separately, so a cause gaining a
 * second producer shows up only as a count.
 */
@PipelineTier
class InputFieldFanInDiagnosticsTest {

    private static final String FILM = "type Film @table(name: \"film\") { filmId: Int! @field(name: \"film_id\") }\n";

    private static List<ValidationError> diagnosticsFor(GraphitronSchema schema, String coordinate) {
        return schema.diagnostics().stream()
            .filter(d -> coordinate.equals(d.coordinate()))
            .toList();
    }

    @Test
    void twoUnresolvableFieldsOnOneInput_keepBothTypedShapes() {
        // The regression this item exists for: adding a second defect used to cost the first one its
        // type, because the pair could not fit one arm and both flattened into structural prose. The
        // rejection got less structured as the schema got more broken.
        var schema = TestSchemaHelper.buildSchema("""
            input PlainFilter {
              aCol: String @field(name: "no_such_a") @reference(path: [{key: "film_language_id_fkey"}])
              bCol: String @field(name: "no_such_b") @reference(path: [{key: "film_language_id_fkey"}])
            }
            """ + FILM + """
            type Query { films(filter: PlainFilter): [Film!]! }
            """);

        var a = diagnosticsFor(schema, "PlainFilter.aCol");
        var b = diagnosticsFor(schema, "PlainFilter.bCol");
        assertThat(a).hasSize(1);
        assertThat(b).hasSize(1);

        assertThat(a.getFirst().rejection()).isInstanceOf(Rejection.AuthorError.UnknownName.class);
        assertThat(b.getFirst().rejection()).isInstanceOf(Rejection.AuthorError.UnknownName.class);
        assertThat((Rejection.AuthorError.UnknownName) a.getFirst().rejection())
            .satisfies(un -> {
                assertThat(un.attemptKind()).isEqualTo(Rejection.AttemptKind.COLUMN);
                assertThat(un.attempt()).isEqualTo("no_such_a");
                // Candidates come from the path's terminal table, where the column was looked for.
                assertThat(un.candidates()).anyMatch(c -> c.equalsIgnoreCase("name"));
            });
        assertThat(((Rejection.AuthorError.UnknownName) b.getFirst().rejection()).attempt())
            .isEqualTo("no_such_b");

        // The consuming field states the consequence and counts the causes; it does not repeat them.
        var consumer = (UnclassifiedField) schema.field("Query", "films");
        assertThat(consumer.reason())
            .contains("plain input type 'PlainFilter'")
            .contains("2 input fields could not be resolved")
            .doesNotContain("no_such_a");
    }

    @Test
    void perFailureDiagnosticsLandOnTheInputFieldsOwnLine() {
        // The author-visible half of the location move: five broken input fields used to produce one
        // squiggle on the consuming field. Nothing pinned this before.
        String sdl = """
            input PlainFilter {
              aRef: ID @nodeId(typeName: "NoSuchTypeA")
              bRef: ID @nodeId(typeName: "NoSuchTypeB")
            }
            """ + FILM + """
            type Query { films(filter: PlainFilter): [Film!]! }
            """;
        var schema = TestSchemaHelper.buildSchema(sdl);
        int prelude = TestSchemaHelper.preludeLineCount(sdl);

        assertThat(diagnosticsFor(schema, "PlainFilter.aRef").getFirst().location().getLine())
            .isEqualTo(prelude + 2);
        assertThat(diagnosticsFor(schema, "PlainFilter.bRef").getFirst().location().getLine())
            .isEqualTo(prelude + 3);
    }

    @Test
    void sameInputTypeAgainstOneTable_mintsEachFailureOnce() {
        // Input fields resolve once per consuming field, so this input classifies twice. Both passes
        // build the fact from the input field's own facts, so the two mints are one value and
        // collapse at the mint boundary.
        var schema = TestSchemaHelper.buildSchema("""
            input PlainFilter { aRef: ID @nodeId(typeName: "NoSuchTypeA") }
            """ + FILM + """
            type Query {
              films(filter: PlainFilter): [Film!]!
              moreFilms(filter: PlainFilter): [Film!]!
            }
            """);

        assertThat(diagnosticsFor(schema, "PlainFilter.aRef")).hasSize(1);
        assertThat(schema.field("Query", "films")).isInstanceOf(UnclassifiedField.class);
        assertThat(schema.field("Query", "moreFilms")).isInstanceOf(UnclassifiedField.class);
    }

    @Test
    void sameInputTypeAgainstTwoTables_keepsBothFacts() {
        // The other half of the same property: resolved against different tables the facts are
        // genuinely different (each names the table it failed against), so dedup must not collapse
        // them. Neither actor nor category has an FK to language.
        var schema = TestSchemaHelper.buildSchema("""
            type Language implements Node @table(name: "language") @node { id: ID! @nodeId name: String }
            input PlainFilter { languageIds: [ID!] @nodeId(typeName: "Language") }
            type Actor @table(name: "actor") { firstName: String @field(name: "first_name") }
            type Category @table(name: "category") { name: String }
            type Query {
              actors(filter: PlainFilter): [Actor!]!
              categories(filter: PlainFilter): [Category!]!
            }
            """);

        var minted = diagnosticsFor(schema, "PlainFilter.languageIds");
        assertThat(minted).hasSize(2);
        assertThat(minted).anySatisfy(d -> assertThat(d.message()).contains("'actor'"));
        assertThat(minted).anySatisfy(d -> assertThat(d.message()).contains("'category'"));
    }

    @Test
    void nestedConditionFailure_mintsUnderTheDeclaringTypeAndDedups() {
        // The condition accumulator is threaded through the whole nesting recursion, so a nested
        // field's failure reaches the outermost fold. Minting it under that fold's type would name a
        // coordinate the schema does not have, and would make one fact minted from two consumers two
        // unequal values, which is exactly what the mint-boundary dedup relies on not happening.
        var schema = TestSchemaHelper.buildSchema("""
            input Inner {
              filmId: Int! @field(name: "film_id")
                @condition(condition: {className: "no.sikt.graphitron.rewrite.NoSuchClass", method: "nope"})
            }
            input FilterA { inner: Inner }
            input FilterB { inner: Inner }
            """ + FILM + """
            type Query {
              a(filter: FilterA): [Film!]!
              b(filter: FilterB): [Film!]!
            }
            """);

        assertThat(diagnosticsFor(schema, "Inner.filmId")).hasSize(1);
        assertThat(diagnosticsFor(schema, "FilterA.filmId")).isEmpty();
        assertThat(diagnosticsFor(schema, "FilterB.filmId")).isEmpty();
    }

    @Test
    void nestedFailure_reportsLeafAtLeafAndConsequenceAtEachLevel() {
        // Three coordinates, one fact each, where the fan-in used to render the nested level's
        // already-joined prose inside the outer level's join.
        var schema = TestSchemaHelper.buildSchema("""
            input Inner {
              aCol: String @field(name: "no_such_a") @reference(path: [{key: "film_language_id_fkey"}])
            }
            input PlainFilter { inner: Inner }
            """ + FILM + """
            type Query { films(filter: PlainFilter): [Film!]! }
            """);

        assertThat(diagnosticsFor(schema, "Inner.aCol")).hasSize(1);
        assertThat(diagnosticsFor(schema, "Inner.aCol").getFirst().rejection())
            .isInstanceOf(Rejection.AuthorError.UnknownName.class);

        var nesting = diagnosticsFor(schema, "PlainFilter.inner");
        assertThat(nesting).hasSize(1);
        assertThat(nesting.getFirst().message())
            .contains("nested input type 'Inner' has 1 unresolvable field")
            .doesNotContain("no_such_a");

        assertThat(((UnclassifiedField) schema.field("Query", "films")).reason())
            .contains("1 input field could not be resolved");
    }

    @Test
    void conditionFailure_mintsAtTheFieldCarryingTheDirective() {
        // Condition build failures ride the same accumulator and the same mint. The reflect arm keeps
        // its typed identity; the coordinate, not the message, carries where it happened, because
        // those arms treat prefixedWith as a no-op by design.
        var schema = TestSchemaHelper.buildSchema("""
            input PlainFilter {
              filmId: Int! @field(name: "film_id")
                @condition(condition: {className: "no.sikt.graphitron.rewrite.NoSuchClass", method: "nope"})
            }
            """ + FILM + """
            type Query { films(filter: PlainFilter): [Film!]! }
            """);

        var minted = diagnosticsFor(schema, "PlainFilter.filmId");
        assertThat(minted).hasSize(1);
        assertThat(minted.getFirst().rejection())
            .isInstanceOf(no.sikt.graphitron.rewrite.model.ReflectionError.ClassNotLoaded.class);
        assertThat(minted.getFirst().location()).isNotNull();
    }

    /**
     * One producer of {@link InputFieldResolution.Unresolved}, and the arm it is expected to pick.
     * {@code messagePart} selects the producer's own fact at {@code coordinate}: a coordinate can
     * legitimately carry more than one fact (in a cycle it is both a cause and a consequence), so the
     * row names which one it is about.
     */
    private record ProducerCase(String label, String sdl, String coordinate, String messagePart,
                                Class<?> arm, String note) {}

    /**
     * The honest record of which input-field producers carry a typed arm and which are still
     * {@link Rejection.AuthorError.Structural}, one row per producer reachable from the default
     * fixture catalog. A row moving off {@code Structural} is progress and must be recorded here;
     * a row moving onto it is a regression this test fails on.
     *
     * <p>The producers not reachable from this catalog are the FK-target key-mismatch case and the
     * NodeId decode-helper failures, which need {@code KjerneJooqGenerator} node metadata;
     * {@code NodeIdPipelineTest} and
     * {@code NodeInferencePipelineTest} cover those against the fixture context, including the
     * key-mismatch case's {@link Rejection.Deferred} arm.
     */
    private static List<ProducerCase> producerPartition() {
        return List.of(
            new ProducerCase("@reference column miss",
                """
                input PlainFilter {
                  aCol: String @field(name: "no_such_a") @reference(path: [{key: "film_language_id_fkey"}])
                }
                """ + FILM + "type Query { films(filter: PlainFilter): [Film!]! }\n",
                "PlainFilter.aCol", "no column 'no_such_a' reachable",
                Rejection.AuthorError.UnknownName.class, null),

            new ProducerCase("retired @notGenerated",
                """
                input Inner { hidden: String @notGenerated }
                input PlainFilter { inner: Inner }
                """ + FILM + "type Query { films(filter: PlainFilter): [Film!]! }\n",
                "Inner.hidden", "@notGenerated is no longer supported",
                Rejection.InvalidSchema.DirectiveConflict.class, null),

            new ProducerCase("@condition reflection failure",
                """
                input PlainFilter {
                  filmId: Int! @field(name: "film_id")
                    @condition(condition: {className: "no.sikt.graphitron.rewrite.NoSuchClass", method: "nope"})
                }
                """ + FILM + "type Query { films(filter: PlainFilter): [Film!]! }\n",
                "PlainFilter.filmId", "could not be loaded",
                no.sikt.graphitron.rewrite.model.ReflectionError.ClassNotLoaded.class, null),

            new ProducerCase("repeated @reference",
                """
                input PlainFilter {
                  district: String
                    @reference(path: [{table: "address"}])
                    @reference(path: [{table: "address"}])
                }
                type Customer @table(name: "customer") { customerId: Int! @field(name: "customer_id") }
                type Query { customers(filter: PlainFilter): [Customer!]! }
                """,
                "PlainFilter.district", "repeated @reference on an input field",
                Rejection.AuthorError.Structural.class,
                "genuinely structural: no name is looked up against a closed set"),

            new ProducerCase("unresolvable @reference path",
                """
                input PlainFilter {
                  aCol: String @field(name: "name") @reference(path: [{table: "language"}])
                }
                """ + FILM + "type Query { films(filter: PlainFilter): [Film!]! }\n",
                "PlainFilter.aCol", "multiple foreign keys found",
                Rejection.AuthorError.Structural.class,
                "boundary wrap: ParsedPath still reports prose"),

            new ProducerCase("@nodeId(typeName:) naming no type",
                """
                input PlainFilter { aRef: ID @nodeId(typeName: "NoSuchTypeA") }
                """ + FILM + "type Query { films(filter: PlainFilter): [Film!]! }\n",
                "PlainFilter.aRef", "does not exist in the schema",
                Rejection.AuthorError.Structural.class,
                "the arm is NodeIdLeafResolver's to type, not this path's"),

            new ProducerCase("nesting consequence",
                """
                input Inner {
                  aCol: String @field(name: "no_such_a") @reference(path: [{key: "film_language_id_fkey"}])
                }
                input PlainFilter { inner: Inner }
                """ + FILM + "type Query { films(filter: PlainFilter): [Film!]! }\n",
                "PlainFilter.inner", "nested input type 'Inner' has 1 unresolvable field",
                Rejection.AuthorError.Structural.class,
                "a consequence, not a cause: the causes are minted at the nested fields"),

            new ProducerCase("circular nesting",
                """
                input A { b: B }
                input B { a: A }
                """ + FILM + "type Query { films(filter: A): [Film!]! }\n",
                "A.b", "circular input type reference detected",
                Rejection.AuthorError.Structural.class,
                "genuinely structural: a cycle names nothing to look up"));
    }

    @Test
    void everyReachableProducerMintsItsDeclaredArm() {
        for (var c : producerPartition()) {
            var minted = diagnosticsFor(TestSchemaHelper.buildSchema(c.sdl()), c.coordinate()).stream()
                .filter(d -> d.message().contains(c.messagePart()))
                .toList();
            assertThat(minted)
                .as("%s mints exactly one diagnostic at %s", c.label(), c.coordinate())
                .hasSize(1);
            assertThat(minted.getFirst().rejection())
                .as("%s (%s)", c.label(), c.note() == null ? "typed" : c.note())
                .isInstanceOf(c.arm());
        }
    }

    @Test
    void directiveConflict_listsOnlyDirectivesTheAuthorApplied() {
        // The contract the retired-directive convergence makes load-bearing: every name in
        // DirectiveConflict.directives is applied at the rejection's own declaration, so a consumer
        // counting rejections per directive counts causes and never a remedy the author did not
        // write. The @asConnection-on-inline-TableField site used to list the absent @splitQuery.
        var schema = TestSchemaHelper.buildSchema("""
            type Customer @table(name: "customer") { firstName: String }
            type Store @table(name: "store") {
              customers: [Customer!]! @asConnection @defaultOrder(primaryKey: true)
            }
            type Query { store: Store }
            """);

        var field = (UnclassifiedField) schema.field("Store", "customers");
        var conflict = (Rejection.InvalidSchema.DirectiveConflict) field.rejection();
        assertThat(conflict.directives()).containsExactly("asConnection");
        assertThat(conflict.reason()).contains("add @splitQuery");
        conflict.directives().forEach(d ->
            assertThat(field.definition().hasAppliedDirective(d))
                .as("directive '%s' is applied at the rejection's declaration", d)
                .isTrue());
    }
}
