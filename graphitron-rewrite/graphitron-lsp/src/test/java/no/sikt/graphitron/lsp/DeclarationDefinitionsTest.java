package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.definition.DeclarationDefinitions;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.SourceWalker;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Goto-definition from an SDL declaration name (a type name or a field /
 * input-value name, not a directive argument) to the Java the model bound it to.
 * Dispatches on the enclosing type's {@code TypeBackingShape} and resolves each
 * target through the LSP-owned {@code SourceWalker.Index}, joined by the
 * catalog's structural keys, exactly like {@code Definitions} does for the
 * directive-argument half. Covers one case per backing shape per axis.
 */
class DeclarationDefinitionsTest {

    private static final String TABLE_URI = "file:///fake/jooq/Film.java";
    private static final String RECORD_URI = "file:///fake/dto/FilmDto.java";
    private static final String POJO_URI = "file:///fake/dto/FilmPojo.java";
    private static final String STD_URI = "file:///fake/dto/FilmRecord.java";

    private static final String FILM_FQN = "fake.jooq.tables.Film";
    private static final String RECORD_FQN = "com.example.FilmDto";
    private static final String POJO_FQN = "com.example.FilmPojo";
    private static final String STD_FQN = "com.example.FilmRecord";

    private static final int COLUMN_LINE = 6;
    private static final int COMPONENT_LINE = 2;
    private static final int ACCESSOR_LINE = 9;

    // ---- Type name -> backing class ----

    @Test
    void typeNameOnTableJumpsToTableClass() {
        var file = file("type FilmTable @table(name: \"film\") { title: String }");
        var loc = compute(file, pointAt(file, 0, "FilmTable")).orElseThrow();
        assertThat(loc.getUri()).isEqualTo(TABLE_URI);
        assertThat(loc.getRange().getStart().getLine()).isZero();
    }

    @Test
    void typeNameOnRecordJumpsToBackingClass() {
        var file = file("type FilmRecord { firstName: String }");
        var loc = compute(file, pointAt(file, 0, "FilmRecord")).orElseThrow();
        assertThat(loc.getUri()).isEqualTo(RECORD_URI);
    }

    @Test
    void typeNameOnPojoJumpsToBackingClass() {
        var file = file("type FilmPojo { firstName: String }");
        var loc = compute(file, pointAt(file, 0, "FilmPojo")).orElseThrow();
        assertThat(loc.getUri()).isEqualTo(POJO_URI);
    }

    @Test
    void typeNameOnStandaloneJooqRecordJumpsToBackingClass() {
        var file = file("type FilmStd { value: String }");
        var loc = compute(file, pointAt(file, 0, "FilmStd")).orElseThrow();
        assertThat(loc.getUri()).isEqualTo(STD_URI);
    }

    @Test
    void typeNameKnownButSourceNotIndexedReturnsEmpty() {
        // A reflection-bound type whose backing class is not on a walked root:
        // the SourceAbsent arm is a non-jump, the same contract as the jOOQ half.
        var file = file("type FilmRecord { firstName: String }");
        assertThat(DeclarationDefinitions.compute(
            file, catalog(), SourceWalker.Index.EMPTY, snapshot(), pointAt(file, 0, "FilmRecord")))
            .isEmpty();
    }

    // ---- Field name -> backing member ----

    @Test
    void fieldNameOnTableColumnJumpsToColumn() {
        var file = file("type FilmTable @table(name: \"film\") { title: String }");
        var loc = compute(file, pointAt(file, 0, "title")).orElseThrow();
        assertThat(loc.getUri()).isEqualTo(TABLE_URI);
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(COLUMN_LINE);
    }

    @Test
    void fieldNameWithFieldDirectiveOverrideResolvesColumn() {
        // The bound column is named by @field(name:), not the SDL field name.
        var file = file("type FilmTable @table(name: \"film\") { renamed: String @field(name: \"title\") }");
        var loc = compute(file, pointAt(file, 0, "renamed")).orElseThrow();
        assertThat(loc.getUri()).isEqualTo(TABLE_URI);
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(COLUMN_LINE);
    }

    @Test
    void fieldNameOnPojoJumpsToAccessorMethod() {
        var file = file("type FilmPojo { firstName: String }");
        var loc = compute(file, pointAt(file, 0, "firstName")).orElseThrow();
        assertThat(loc.getUri()).isEqualTo(POJO_URI);
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(ACCESSOR_LINE);
    }

    @Test
    void fieldNameOnRecordComponentJumpsToComponent() {
        // Member-precise: the parse-only walk indexes a record component as a
        // field (the implicit accessor is synthesised later), so the component
        // name is its own field key.
        var file = file("type FilmRecord { firstName: String }");
        var loc = compute(file, pointAt(file, 0, "firstName")).orElseThrow();
        assertThat(loc.getUri()).isEqualTo(RECORD_URI);
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(COMPONENT_LINE);
    }

    @Test
    void fieldNameOnStandaloneJooqRecordDegradesToBackingClass() {
        // No table (no column join) and no member key: degrade to the class.
        var file = file("type FilmStd { value: String }");
        var loc = compute(file, pointAt(file, 0, "value")).orElseThrow();
        assertThat(loc.getUri()).isEqualTo(STD_URI);
    }

    @Test
    void fieldNameUnknownMemberReturnsEmpty() {
        var file = file("type FilmRecord { ghost: String }");
        assertThat(compute(file, pointAt(file, 0, "ghost"))).isEmpty();
    }

    // ---- No backing / no trigger ----

    @Test
    void noBackingTypeNameReturnsEmpty() {
        var file = file("type Query { films: String }");
        assertThat(compute(file, pointAt(file, 0, "Query"))).isEmpty();
    }

    @Test
    void noBackingFieldNameReturnsEmpty() {
        var file = file("type Query { films: String }");
        assertThat(compute(file, pointAt(file, 0, "films"))).isEmpty();
    }

    @Test
    void cursorOnDirectiveArgumentIsNotADeclarationTrigger() {
        // "film" sits in @table(name:), not on a declaration name, so this
        // provider declines (the directive-arg half owns that coordinate).
        var file = file("type FilmTable @table(name: \"film\") { title: String }");
        assertThat(compute(file, pointAt(file, 0, "film"))).isEmpty();
    }

    @Test
    void unavailableSnapshotReturnsEmpty() {
        var file = file("type FilmRecord { firstName: String }");
        assertThat(DeclarationDefinitions.compute(
            file, catalog(), index(), LspSchemaSnapshot.unavailable(), pointAt(file, 0, "FilmRecord")))
            .isEmpty();
    }

    private static java.util.Optional<org.eclipse.lsp4j.Location> compute(WorkspaceFile file, Point pos) {
        return DeclarationDefinitions.compute(file, catalog(), index(), snapshot(), pos);
    }

    private static CompletionData catalog() {
        var film = new CompletionData.Table(
            "film", "", FILM_FQN,
            List.of(new CompletionData.Column("title", "String", false, "")),
            List.of());
        var getFirstName = new CompletionData.Method("getFirstName", "String", "", List.of());
        var pojoRef = new CompletionData.ExternalReference(
            POJO_FQN, POJO_FQN, "", List.of(getFirstName), List.of());
        return new CompletionData(List.of(film), List.of(), List.of(pojoRef));
    }

    private static SourceWalker.Index index() {
        var tableClass = new SourceWalker.Decl(new CompletionData.SourceLocation(TABLE_URI, 0, 0), "");
        var recordClass = new SourceWalker.Decl(new CompletionData.SourceLocation(RECORD_URI, 0, 0), "");
        var pojoClass = new SourceWalker.Decl(new CompletionData.SourceLocation(POJO_URI, 0, 0), "");
        var stdClass = new SourceWalker.Decl(new CompletionData.SourceLocation(STD_URI, 0, 0), "");
        var titleColumn = new SourceWalker.Decl(new CompletionData.SourceLocation(TABLE_URI, COLUMN_LINE, 4), "");
        var component = new SourceWalker.Decl(new CompletionData.SourceLocation(RECORD_URI, COMPONENT_LINE, 4), "");
        var accessor = new SourceWalker.Decl(new CompletionData.SourceLocation(POJO_URI, ACCESSOR_LINE, 4), "");
        return new SourceWalker.Index(
            Map.of(FILM_FQN, tableClass, RECORD_FQN, recordClass, POJO_FQN, pojoClass, STD_FQN, stdClass),
            Map.of(new SourceWalker.MethodKey(POJO_FQN, "getFirstName", 0), accessor),
            Map.of(
                new SourceWalker.FieldKey(FILM_FQN, "title"), titleColumn,
                new SourceWalker.FieldKey(RECORD_FQN, "firstName"), component),
            Set.of());
    }

    private static LspSchemaSnapshot snapshot() {
        Map<String, TypeBackingShape> types = Map.of(
            "FilmTable", new TypeBackingShape.TableBacking("film"),
            "FilmRecord", new TypeBackingShape.RecordBacking(RECORD_FQN,
                List.of(new TypeBackingShape.MemberSlot("firstName", "String", "firstName"))),
            "FilmPojo", new TypeBackingShape.PojoBacking(POJO_FQN,
                List.of(new TypeBackingShape.MemberSlot("firstName", "String", "getFirstName"))),
            "FilmStd", new TypeBackingShape.JooqRecordBacking.Standalone(STD_FQN),
            "Query", new TypeBackingShape.NoBacking.Root());
        return new LspSchemaSnapshot.Built.Current(List.of(), types, Map.of());
    }

    private static Point pointAt(WorkspaceFile file, int line, String token) {
        String source = new String(file.source(), java.nio.charset.StandardCharsets.UTF_8);
        var lines = source.split("\n");
        int col = lines[line].indexOf(token);
        if (col < 0) {
            throw new AssertionError("token '" + token + "' not on line " + line + ": " + lines[line]);
        }
        return new Point(line, col + Math.max(1, token.length() / 2));
    }

    private static WorkspaceFile file(String source) {
        return new WorkspaceFile(1, source);
    }
}
