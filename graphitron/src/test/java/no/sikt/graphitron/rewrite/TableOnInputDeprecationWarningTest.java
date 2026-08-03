package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.DmlWriteField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deprecation window for {@code @table} on an {@code input} type. The directive is accepted,
 * <em>ignored</em>, and reported as a per-usage non-fatal advisory
 * ({@code GraphitronSchemaBuilder.emitTableOnInputDeprecationWarnings}); the type's verdict is the
 * plain one it would get without the directive.
 *
 * <p>No input is carved out: DELETE ({@code @mutation(table:)}), INSERT and UPDATE (return-derived,
 * or {@code @mutation(table:)} for an encoded return), and filter-only inputs all have
 * field-relative resolution paths, so the warning fires on every author-written {@code @table}
 * input and names the per-verb replacement. The message states the directive had no effect: an
 * input whose {@code @table} named a table other than the one its consumer resolves is the case
 * where accept-and-ignore diverges from the author's intent, and this wording is the whole
 * mitigation.
 *
 * <p>The equivalence half of the contract (a {@code @table} input classifies and binds exactly as
 * its directiveless twin, top-level and nested) lives in
 * {@link no.sikt.graphitron.rewrite.generators.JooqRecordServiceParamPipelineTest} and
 * {@link TableOnInputIgnoredNestingTest}.
 */
@PipelineTier
class TableOnInputDeprecationWarningTest {

    private static final String IGNORED_FRAGMENT = "was ignored";
    private static final String DEPRECATION_FRAGMENT = "will be rejected in a future release";

    /**
     * The default Sakila catalog is plain jOOQ-generated and carries no {@code __NODE_TYPE_ID}
     * metadata, so an encoded-ID INSERT return there rejects at classify (no {@code @node} encoder
     * to wire). The {@code nodeidfixture} catalog hand-instruments {@code Bar} with the node
     * metadata, mirroring {@link MutationDmlNodeIdClassificationTest}; the encoded-return arms use
     * it.
     */
    private static final RewriteContext NODEID_CTX = new RewriteContext(
        List.of(),
        Path.of(""),
        Path.of(""),
        "fake.code.generated",
        "no.sikt.graphitron.rewrite.nodeidfixture"
    );

    @Test
    void projectedTableReturnInsert_warnsOnInput_namingReturnDerivation() {
        // The return type carries @table, so the write target is derived from it; the input's
        // @table contributed nothing and warns, naming the return-derived replacement.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """);

        assertThat(schema.type("FilmInput"))
            .as("the type's verdict is the plain one; the directive decides nothing")
            .isNotInstanceOf(GraphitronType.UnclassifiedType.class);
        assertThat(schema.warnings())
            .filteredOn(w -> w.message().contains("FilmInput") && w.message().contains(IGNORED_FRAGMENT))
            .as("projected @table-return INSERT input must earn the deprecation advisory")
            .singleElement()
            .satisfies(w -> {
                assertThat(w.message())
                    .as("the message says the directive was ignored, announces the future "
                        + "rejection, and names the INSERT return-derived fix")
                    .contains("`@table` on input type 'FilmInput'")
                    .contains(DEPRECATION_FRAGMENT)
                    .contains("@mutation(typeName: INSERT)")
                    .contains("derived from the field's return type");
                assertThat(w.location())
                    .as("the advisory carries the input type's source location, which is what "
                        + "lets the LSP render it as a squiggle")
                    .isNotNull();
            });
    }

    @Test
    void encodedIdReturnInsert_warnsOnInput_namingMutationTableArg() {
        // The return is an encoded ID (no @table on the return), so @mutation(table:) is the
        // field-relative replacement the message must name.
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node { id: ID! @nodeId name: String }
            input BarInput @table(name: "bar") { name: String }
            type Query { x: String }
            type Mutation { createBar(in: BarInput!): ID @mutation(typeName: INSERT, table: "bar") }
            """, NODEID_CTX);

        assertThat(writeArmOf(schema, "createBar"))
            .as("sanity: the encoded-ID INSERT still classifies as a DML insert write")
            .isInstanceOf(OperationMember.Write.Insert.class);
        assertThat(schema.warnings())
            .filteredOn(w -> w.message().contains("BarInput") && w.message().contains(IGNORED_FRAGMENT))
            .as("encoded-ID INSERT input warns, naming @mutation(table:)")
            .singleElement()
            .satisfies(w -> assertThat(w.message())
                .contains("@mutation(typeName: INSERT)")
                .contains("@mutation(table:"));
    }

    @Test
    void deleteConsumedInput_warnsNamingMutationTableArg() {
        // DELETE has a field-relative write-target path (@mutation(table:)), so its inputs warn and
        // the advisory names the replacement explicitly.
        var schema = TestSchemaHelper.buildSchema("""
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId filmId: Int! @field(name: "film_id") }
            input FilmDeleteInput @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmDeleteInput!): ID @mutation(typeName: DELETE, table: "film") }
            """);

        assertThat(writeArmOf(schema, "deleteFilm"))
            .as("sanity: the ID-return DELETE classifies as a DML delete write")
            .isInstanceOf(OperationMember.Write.Delete.class);
        assertThat(schema.warnings())
            .filteredOn(w -> w.message().contains("FilmDeleteInput") && w.message().contains(IGNORED_FRAGMENT))
            .as("DELETE-consumed input warns, naming @mutation(table:) as the replacement")
            .singleElement()
            .satisfies(w -> assertThat(w.message())
                .contains("@mutation(typeName: DELETE)")
                .contains("@mutation(table:"));
    }

    @Test
    void updateConsumedInput_warnsNamingReturnDerivation() {
        // UPDATE's write target derives from the @table return, the third per-verb wording arm.
        // The input covers the film PK so the walker's PK-or-UK identification succeeds.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") title: String }
            type Query { x: String }
            input FilmUpdateInput @table(name: "film") {
              filmId: Int! @field(name: "film_id")
              title: String @field(name: "title")
            }
            type Mutation { updateFilm(in: FilmUpdateInput!): Film @mutation(typeName: UPDATE) }
            """);

        assertThat(writeArmOf(schema, "updateFilm"))
            .as("sanity: the return-derived UPDATE classifies as a DML update write")
            .isInstanceOf(OperationMember.Write.Update.class);
        assertThat(schema.warnings())
            .filteredOn(w -> w.message().contains("FilmUpdateInput") && w.message().contains(IGNORED_FRAGMENT))
            .as("UPDATE-consumed input warns, naming the return-derived replacement")
            .singleElement()
            .satisfies(w -> assertThat(w.message())
                .contains("@mutation(typeName: UPDATE)")
                .contains("derived from the field's return type"));
    }

    @Test
    void filterOnlyInput_warnsNamingNoReplacementDirective() {
        // No mutation consumer at all: the fourth wording arm. Nothing replaces the directive,
        // because a filter input's fields already resolve against the consuming query field.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter @table(name: "film") { title: String }
            type Query { films(filter: FilmFilter): [Film!]! }
            """);

        assertThat(schema.type("FilmFilter"))
            .as("a filter input carrying @table classifies plain, exactly as its twin would")
            .isInstanceOf(GraphitronType.PojoInputType.class);
        assertThat(schema.warnings())
            .filteredOn(w -> w.message().contains("FilmFilter") && w.message().contains(IGNORED_FRAGMENT))
            .singleElement()
            .satisfies(w -> assertThat(w.message())
                .as("the filter wording names no replacement directive")
                .contains("resolve against each consuming field's table")
                .doesNotContain("@mutation(typeName:"));
    }

    @Test
    void inputReusedAcrossConsumers_warnsOncePerInputNotPerConsumer() {
        // The advisory is keyed to the declaring input type, so consumer count neither multiplies
        // nor suppresses it. This is why the pass runs over the schema's input types rather than
        // from buildInputType, which a memoizing look-ahead reaches an unpredictable number of
        // times.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input Shared @table(name: "film") { title: String }
            type Query {
                filmsA(filter: Shared): [Film!]!
                filmsB(filter: Shared): [Film!]!
            }
            """);

        assertThat(schema.warnings())
            .filteredOn(w -> w.message().contains("Shared") && w.message().contains(IGNORED_FRAGMENT))
            .as("two consumers, one advisory")
            .hasSize(1);
    }

    @Test
    void unknownTableName_warnsAndIsOtherwiseInert() {
        // The name: argument is never read, so an unresolvable table changes nothing: no rejection,
        // no resolution failure, the same advisory. This is the case the "was ignored" wording
        // exists for; the author's declared table and the resolved one need not agree, and nothing
        // but the message tells them so.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter @table(name: "no_such_table") { title: String }
            type Query { films(filter: FilmFilter): [Film!]! }
            """);

        assertThat(schema.type("FilmFilter"))
            .as("an unresolvable table name is inert, not a rejection")
            .isInstanceOf(GraphitronType.PojoInputType.class);
        assertThat(schema.diagnostics())
            .as("and it raises no build diagnostic")
            .isEmpty();
        assertThat(schema.warnings())
            .filteredOn(w -> w.message().contains("FilmFilter") && w.message().contains(IGNORED_FRAGMENT))
            .hasSize(1);
    }

    @Test
    void tableNamingADifferentTableThanTheConsumer_warnsAndResolvesAgainstTheConsumer() {
        // The intent-divergence case stated plainly: the input declares "actor" while its consumer
        // resolves "film". The declared table loses, silently but for the advisory, so the fields
        // bind against film.title rather than failing to find it on actor.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter @table(name: "actor") { title: String @field(name: "title") }
            type Query { films(filter: FilmFilter): [Film!]! }
            """);

        assertThat(schema.diagnostics())
            .as("the consumer's table decides; the declared one is not consulted, so no "
                + "unresolvable-column diagnostic fires")
            .isEmpty();
        assertThat(schema.warnings())
            .filteredOn(w -> w.message().contains("FilmFilter") && w.message().contains(IGNORED_FRAGMENT))
            .as("the advisory is the only signal that the declared table was discarded")
            .hasSize(1);
    }

    @Test
    void multipleUsagesAcrossEveryVerbAndNesting_buildWithWarningsAndNoErrors() {
        // The whole point of reopening the window, asserted as one schema: the shape that hit a
        // wall of type-level rejections at once must now build. Six @table-carrying inputs across
        // filter, INSERT, UPDATE, DELETE, and a nested grouping input, and nothing removed.
        //
        // Asserted over the type and field registries rather than diagnostics(): a field-level
        // rejection lands as an UnclassifiedField and leaves diagnostics() empty at this stage, so
        // a diagnostics-only check would pass over exactly the failure this is guarding.
        var schema = TestSchemaHelper.buildSchema("""
            type Film implements Node @table(name: "film") @node {
              id: ID! @nodeId
              filmId: Int! @field(name: "film_id")
              title: String
            }
            type Actor @table(name: "actor") { actorId: Int! @field(name: "actor_id") }
            input FilmFilter @table(name: "film") { title: String @field(name: "title") }
            input ActorFilter @table(name: "actor") { actorId: Int @field(name: "actor_id") }
            input FilmInsert @table(name: "film") { title: String @field(name: "title") }
            input FilmUpdate @table(name: "film") {
              filmId: Int! @field(name: "film_id")
              title: String @field(name: "title")
            }
            input FilmDelete @table(name: "film") { filmId: Int! @field(name: "film_id") }
            input NestedGroupInput @table(name: "actor") { title: String @field(name: "title") }
            input FilmWithNested { nested: NestedGroupInput }
            type Query {
              films(filter: FilmFilter): [Film!]!
              actors(filter: ActorFilter): [Actor!]!
              filmsNested(filter: FilmWithNested): [Film!]!
            }
            type Mutation {
              createFilm(in: FilmInsert!): Film @mutation(typeName: INSERT)
              updateFilm(in: FilmUpdate!): Film @mutation(typeName: UPDATE)
              deleteFilm(in: FilmDelete!): ID @mutation(typeName: DELETE, table: "film")
            }
            """);

        assertThat(schema.diagnostics()).isEmpty();
        assertThat(schema.types().values())
            .as("no type rejected")
            .noneMatch(t -> t instanceof GraphitronType.UnclassifiedType);
        assertThat(schema.fields().values())
            .as("and no consuming field rejected either")
            .noneMatch(f -> f instanceof no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField);

        assertThat(schema.warnings())
            .filteredOn(w -> w.message().contains(IGNORED_FRAGMENT))
            .as("one advisory per @table-carrying input, all four wordings represented")
            .hasSize(6);
        assertThat(schema.warnings()).anyMatch(w -> w.message().contains("FilmDelete")
            && w.message().contains("@mutation(typeName: DELETE)"));
        assertThat(schema.warnings()).anyMatch(w -> w.message().contains("FilmInsert")
            && w.message().contains("@mutation(typeName: INSERT)"));
        assertThat(schema.warnings()).anyMatch(w -> w.message().contains("FilmUpdate")
            && w.message().contains("@mutation(typeName: UPDATE)"));
        assertThat(schema.warnings()).anyMatch(w -> w.message().contains("NestedGroupInput")
            && w.message().contains("resolve against each consuming field's table"));
    }

    /** The DML write arm the named Mutation field carries, so a verb assertion names the verb. */
    private static OperationMember.Write.Dml writeArmOf(GraphitronSchema schema, String fieldName) {
        var field = schema.field("Mutation", fieldName);
        assertThat(field).isInstanceOf(DmlWriteField.class);
        return ((DmlWriteField) field).write();
    }
}
