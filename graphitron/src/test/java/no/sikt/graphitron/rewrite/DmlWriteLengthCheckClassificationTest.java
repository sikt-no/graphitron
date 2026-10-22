package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.lint.LintRule;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnWriteBound;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.LengthCheck;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.WrittenCell;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Pipeline-tier coverage of the column-length check on DML carriers: which written cells
 * {@link ColumnWriteBound} marks {@link ColumnWriteBound.Length}, what
 * {@link OperationMember.Write.Dml#writtenCells()} reads off each write plan, how
 * {@link no.sikt.graphitron.rewrite.model.DmlWriteField#lengthCheck()} combines that with the error
 * channel (and its {@link LengthCheck.Sink}), and the {@link LintRule#DML_WRITE_LENGTH_UNROUTED}
 * advisory the {@link LengthCheck.Unrouted} arm raises. The generated check itself is exercised at the execution tier against PostgreSQL.
 */
@PipelineTier
class DmlWriteLengthCheckClassificationTest {

    private static final String VALIDATION_ERROR = """
        type FilmInvalid @error(handlers: [{handler: VALIDATION}]) { path: [String!]! message: String! }
        union FilmError = FilmInvalid
        """;
    private static final String DATABASE_ERROR = """
        type FilmDbError @error(handlers: [{handler: DATABASE, sqlState: "22001"}]) { path: [String!]! message: String! }
        union FilmError = FilmDbError
        """;

    private static GraphitronSchema insertFilm(String errorTypes, String errorsField, String inputBody) {
        return TestSchemaHelper.buildSchema(errorTypes + """
            type Film @table(name: "film") { title: String }
            input FilmInput { %s }
            type FilmPayload { film: Film %s }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): FilmPayload @mutation(typeName: INSERT, table: "film") }
            """.formatted(inputBody, errorsField));
    }

    private static final String FILM_INSERT_INPUT =
        "title: String! languageId: Int! @field(name: \"language_id\") description: String";

    private static List<BuildWarning.LintFinding> lengthFindings(GraphitronSchema schema) {
        return schema.warnings().stream()
            .filter(w -> w instanceof BuildWarning.LintFinding lf && lf.rule() == LintRule.DML_WRITE_LENGTH_UNROUTED)
            .map(w -> (BuildWarning.LintFinding) w)
            .toList();
    }

    private static List<String> paths(List<WrittenCell> cells) {
        return cells.stream().map(c -> String.join(".", c.inputPath())).toList();
    }

    @Test
    void insertWithValidationMember_checksTheBoundedCell() {
        var schema = insertFilm(VALIDATION_ERROR, "errors: [FilmError]", FILM_INSERT_INPUT);
        var f = recordField(schema, "createFilm");

        var title = f.write().writtenCells().stream()
            .filter(c -> c.inputPath().equals(List.of("title"))).findFirst().orElseThrow();
        assertThat(title.bound()).isEqualTo(new ColumnWriteBound.Length(255));
        assertThat(title.column().sqlName()).isEqualTo("title");
        // An int column and a text column (no declared length) carry no bound.
        assertThat(f.write().writtenCells()).filteredOn(c -> !c.inputPath().equals(List.of("title")))
            .extracting(WrittenCell::bound).containsOnly(ColumnWriteBound.Unbounded.INSTANCE);
        assertThat(f.lengthCheck()).isInstanceOfSatisfying(LengthCheck.Checked.class, c -> {
            assertThat(c.bounded()).containsExactly(title);
            assertThat(c.sink()).isEqualTo(LengthCheck.Sink.CHANNEL);
        });
        assertThat(lengthFindings(schema)).isEmpty();
    }

    @Test
    void insertWithoutValidationMember_isUnroutedAndWarnsOnceAtTheField() {
        var schema = insertFilm(DATABASE_ERROR, "errors: [FilmError]", FILM_INSERT_INPUT);
        var f = recordField(schema, "createFilm");

        assertThat(f.lengthCheck()).isInstanceOfSatisfying(LengthCheck.Unrouted.class,
            u -> assertThat(paths(u.bounded())).containsExactly("title"));
        assertThat(lengthFindings(schema)).singleElement().satisfies(lf -> {
            assertThat(lf.location()).isEqualTo(f.location());
            assertThat(lf.message())
                .contains("Mutation.createFilm", "film.title 255", "{handler: VALIDATION}", "<recordValidation>");
        });
    }

    @Test
    void payloadWithoutErrorsField_isCheckedTopLevelAndSilent() {
        var schema = insertFilm("", "", FILM_INSERT_INPUT);
        var f = recordField(schema, "createFilm");

        assertThat(f.lengthCheck()).isInstanceOfSatisfying(LengthCheck.Checked.class, c -> {
            assertThat(paths(c.bounded())).containsExactly("title");
            assertThat(c.sink()).isEqualTo(LengthCheck.Sink.TOP_LEVEL);
        });
        assertThat(lengthFindings(schema)).isEmpty();
    }

    @Test
    void directReturnInsertAndUpdate_areCheckedTopLevel_directReturnDelete_isNone() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId title: String }
            input FilmInput { %s }
            input FilmUpdateInput { filmId: Int! @field(name: "film_id") title: String }
            input FilmDeleteInput { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation {
                createFilm(in: FilmInput!): Film @mutation(typeName: INSERT)
                updateFilm(in: FilmUpdateInput!): Film @mutation(typeName: UPDATE)
                deleteFilm(in: FilmDeleteInput!): ID @mutation(typeName: DELETE, table: "film")
            }
            """.formatted(FILM_INSERT_INPUT));

        for (var name : List.of("createFilm", "updateFilm")) {
            var f = (MutationField.DmlTableField) classified(schema, name);
            assertThat(f.errorChannel()).as(name).isEmpty();
            assertThat(f.lengthCheck()).as(name).isInstanceOfSatisfying(LengthCheck.Checked.class, c -> {
                assertThat(paths(c.bounded())).containsExactly("title");
                assertThat(c.sink()).isEqualTo(LengthCheck.Sink.TOP_LEVEL);
            });
        }
        var delete = (MutationField.DmlTableField) classified(schema, "deleteFilm");
        assertThat(delete.lengthCheck()).isEqualTo(LengthCheck.None.INSTANCE);
        assertThat(lengthFindings(schema)).isEmpty();
    }

    @Test
    void update_writesTheSetCellsAndNotTheKey() {
        var schema = TestSchemaHelper.buildSchema(VALIDATION_ERROR + """
            type Film @table(name: "film") { title: String }
            input FilmUpdateInput { filmId: Int! @field(name: "film_id") title: String }
            type FilmPayload { film: Film errors: [FilmError] }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmUpdateInput!): FilmPayload @mutation(typeName: UPDATE, table: "film") }
            """);
        var f = recordField(schema, "updateFilm");

        assertThat(paths(f.write().writtenCells())).containsExactly("title");
        assertThat(f.write().whereKeyColumns()).extracting(k -> k.sdlFieldName()).containsExactly("filmId");
        assertThat(f.lengthCheck()).isInstanceOf(LengthCheck.Checked.class);
    }

    @Test
    void updateSetColumn_carriesTheBoundDecidedOnItsCarrier() {
        // SetColumn never decides a bound (UpdateRowsWalkerTest pins that it copies its carrier's),
        // so a Length here is the one BuildContext.writeBound minted on the title carrier.
        var schema = TestSchemaHelper.buildSchema(VALIDATION_ERROR + """
            type Film @table(name: "film") { title: String }
            input FilmUpdateInput { filmId: Int! @field(name: "film_id") title: String }
            type FilmPayload { film: Film errors: [FilmError] }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmUpdateInput!): FilmPayload @mutation(typeName: UPDATE, table: "film") }
            """);
        var f = recordField(schema, "updateFilm");
        var update = (OperationMember.Write.Update) f.write();

        assertThat(update.updateRows().setColumns()).singleElement()
            .satisfies(sc -> assertThat(sc.bound()).isEqualTo(new ColumnWriteBound.Length(255)));
    }

    @Test
    void delete_writesNothing() {
        var schema = TestSchemaHelper.buildSchema(DATABASE_ERROR + """
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId title: String }
            input FilmDeleteInput { filmId: Int! @field(name: "film_id") }
            type FilmPayload { id: ID @nodeId(typeName: "Film") errors: [FilmError] }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmDeleteInput!): FilmPayload @mutation(typeName: DELETE, table: "film") }
            """);
        var f = recordField(schema, "deleteFilm");

        assertThat(f.write().writtenCells()).isEmpty();
        assertThat(f.lengthCheck()).isEqualTo(LengthCheck.None.INSTANCE);
        assertThat(lengthFindings(schema)).isEmpty();
    }

    @Test
    void nestedLeaf_keepsItsBoundAndItsPath_onInsertAndUpdate() {
        var schema = TestSchemaHelper.buildSchema(VALIDATION_ERROR + """
            type Film @table(name: "film") { title: String }
            input FilmNames { title: String }
            input FilmInput { names: FilmNames languageId: Int! @field(name: "language_id") }
            input FilmUpdateInput { filmId: Int! @field(name: "film_id") names: FilmNames }
            type FilmPayload { film: Film errors: [FilmError] }
            type Query { x: String }
            type Mutation {
                createFilm(in: FilmInput!): FilmPayload @mutation(typeName: INSERT, table: "film")
                updateFilm(in: FilmUpdateInput!): FilmPayload @mutation(typeName: UPDATE, table: "film")
            }
            """);
        for (var name : List.of("createFilm", "updateFilm")) {
            var f = recordField(schema, name);
            assertThat(f.lengthCheck()).as(name).isInstanceOfSatisfying(LengthCheck.Checked.class, c ->
                assertThat(c.bounded()).singleElement().satisfies(cell -> {
                    assertThat(cell.inputPath()).containsExactly("names", "title");
                    assertThat(cell.bound()).isEqualTo(new ColumnWriteBound.Length(255));
                }));
        }
        var update = (OperationMember.Write.Update)
            (recordField(schema, "updateFilm")).write();
        assertThat(update.updateRows().setColumns()).singleElement()
            .satisfies(sc -> assertThat(sc.extraction()).isInstanceOf(CallSiteExtraction.NestedInputField.class));
    }

    @Test
    void bulkCarrier_derivesTheSameCheckAsItsSingleRowTwin() {
        var schema = TestSchemaHelper.buildSchema(VALIDATION_ERROR + """
            type Film @table(name: "film") { title: String }
            input FilmInput { %s }
            type FilmPayload { film: Film errors: [FilmError] }
            type FilmsPayload { films: [Film!] errors: [FilmError] }
            type Query { x: String }
            type Mutation {
                createFilm(in: FilmInput!): FilmPayload @mutation(typeName: INSERT, table: "film")
                createFilms(in: [FilmInput!]!): FilmsPayload @mutation(typeName: INSERT, table: "film")
            }
            """.formatted(FILM_INSERT_INPUT));
        var single = recordField(schema, "createFilm");
        var bulk = (MutationField.MutationBulkDmlRecordField) classified(schema, "createFilms");

        assertThat(bulk.lengthCheck()).isInstanceOfSatisfying(LengthCheck.Checked.class,
                c -> assertThat(c.sink()).isEqualTo(LengthCheck.Sink.CHANNEL))
            .isEqualTo(single.lengthCheck());
    }

    @Test
    void enumAndNodeIdCells_areUnbounded_plainEmptyPathReference_isBounded() {
        // Every written cell below lands on a varchar column; only the ones whose raw wire value is
        // the column's own text may be length-checked.
        var schema = TestSchemaHelper.buildSchema("""
            enum TextRating { G PG }
            type KeyedNode implements Node @table(name: "keyed_node") @node { id: ID! @nodeId label: String }
            type XlatParent implements Node @table(name: "xlat_parent") @node { id: ID! @nodeId name: String }
            type Rolle implements Node @table(name: "rolle") @node { id: ID! @nodeId }
            type EmneLink @table(name: "emne_link") { code: String @field(name: "link_code") }
            type Film @table(name: "film") { title: String }
            input XlatParentInput { id: ID! altKey: String! @field(name: "alt_key") name: String! }
            input KeyedSameTableInput { key: ID! @nodeId(typeName: "KeyedNode") label: String! }
            input EmneLinkInput { rolle: ID! @nodeId(typeName: "Rolle") }
            input FilmInput {
                rating: TextRating @field(name: "text_rating")
                title: String! @reference(path: [])
                languageId: Int! @field(name: "language_id")
            }
            type Query { x: String }
            type Mutation {
                createImplicit(in: XlatParentInput!): XlatParent @mutation(typeName: INSERT)
                createSameTable(in: KeyedSameTableInput!): KeyedNode @mutation(typeName: INSERT)
                createLink(in: EmneLinkInput!): EmneLink @mutation(typeName: INSERT)
                createFilm(in: FilmInput!): Film @mutation(typeName: INSERT)
            }
            """);

        var unbounded = ColumnWriteBound.Unbounded.INSTANCE;
        assertThat(boundsOf(schema, "createImplicit")).containsExactly(
            entry(List.of("id"), unbounded),
            entry(List.of("altKey"), new ColumnWriteBound.Length(50)),
            entry(List.of("name"), new ColumnWriteBound.Length(50)));
        assertThat(boundsOf(schema, "createSameTable")).containsExactly(
            entry(List.of("key"), unbounded), entry(List.of("label"), new ColumnWriteBound.Length(64)));
        assertThat(boundsOf(schema, "createLink")).containsExactly(entry(List.of("rolle"), unbounded));
        assertThat(boundsOf(schema, "createFilm")).containsExactly(
            entry(List.of("rating"), unbounded),
            entry(List.of("title"), new ColumnWriteBound.Length(255)),
            entry(List.of("languageId"), unbounded));

        // Each case above is the arm it names: the decoded ones are NodeId extractions on a column
        // carrier (implicit, same-table) and on a Local reference carrier (FK target), the enum is
        // a Direct column carrier, and the bounded reference is the plain empty-path one.
        assertThat(inputOf(schema, "createImplicit", "id")).isInstanceOfSatisfying(InputField.ColumnBackedField.class,
            c -> assertThat(c.extraction()).isInstanceOf(CallSiteExtraction.ThrowOnMismatch.class));
        assertThat(inputOf(schema, "createSameTable", "key")).isInstanceOfSatisfying(InputField.ColumnBackedField.class,
            c -> assertThat(c.extraction()).isInstanceOf(CallSiteExtraction.ThrowOnMismatch.class));
        assertThat(inputOf(schema, "createLink", "rolle")).isInstanceOfSatisfying(
            InputField.ColumnBackedReferenceField.class, r -> {
                assertThat(r.extraction()).isInstanceOf(CallSiteExtraction.ThrowOnMismatch.class);
                assertThat(r.binding()).isInstanceOf(no.sikt.graphitron.rewrite.model.FilterBinding.Local.class);
            });
        assertThat(inputOf(schema, "createFilm", "rating")).isInstanceOfSatisfying(InputField.ColumnBackedField.class,
            c -> assertThat(c.extraction()).isInstanceOf(CallSiteExtraction.Direct.class));
        assertThat(inputOf(schema, "createFilm", "title")).isInstanceOfSatisfying(
            InputField.ColumnBackedReferenceField.class, r -> assertThat(r.joinPath()).isEmpty());
    }

    private static InputField inputOf(GraphitronSchema schema, String mutation, String field) {
        return ((OperationMember.Write.Insert) dmlWrite(schema, mutation)).input().fields().stream()
            .filter(i -> i.name().equals(field)).findFirst().orElseThrow();
    }

    private static GraphitronField classified(GraphitronSchema schema, String field) {
        GraphitronField f = schema.field("Mutation", field);
        if (f instanceof GraphitronField.UnclassifiedField u) {
            throw new AssertionError(field + " did not classify: " + u.rejection().message());
        }
        return f;
    }

    private static MutationField.MutationDmlRecordField recordField(GraphitronSchema schema, String field) {
        return (MutationField.MutationDmlRecordField) classified(schema, field);
    }

    private static OperationMember.Write.Dml dmlWrite(GraphitronSchema schema, String field) {
        return ((MutationField.DmlTableField) classified(schema, field)).write();
    }

    private static java.util.Map<List<String>, ColumnWriteBound> boundsOf(GraphitronSchema schema, String field) {
        var out = new java.util.LinkedHashMap<List<String>, ColumnWriteBound>();
        for (var cell : dmlWrite(schema, field).writtenCells()) {
            out.put(cell.inputPath(), cell.bound());
        }
        return out;
    }
}
