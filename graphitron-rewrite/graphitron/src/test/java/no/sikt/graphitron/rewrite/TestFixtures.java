package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.List;

/**
 * Shared factory methods for model objects used across test classes.
 * Reduces the 8-12 arg constructor calls that are duplicated across generator and validation tests.
 * When record components change, only these factories need updating.
 */
public final class TestFixtures {

    private TestFixtures() {}

    // ===== TableRef =====

    public static TableRef filmTable() {
        return new TableRef("film", "FILM", "Film", List.of());
    }

    public static TableRef filmTable(List<ColumnRef> pkColumns) {
        return new TableRef("film", "FILM", "Film", pkColumns);
    }

    public static TableRef filmTableWithPk() {
        return filmTable(List.of(filmIdCol()));
    }

    public static TableRef languageTable() {
        return new TableRef("language", "LANGUAGE", "Language", List.of());
    }

    public static TableRef languageTableWithPk() {
        return new TableRef("language", "LANGUAGE", "Language", List.of(languageIdCol()));
    }

    public static TableRef actorTable() {
        return new TableRef("actor", "ACTOR", "Actor", List.of());
    }

    public static TableRef categoryTable() {
        return new TableRef("category", "CATEGORY", "Category", List.of());
    }

    /** Stub TableRef for join targets (no javaName/className needed). */
    public static TableRef joinTarget(String sqlName) {
        return new TableRef(sqlName, "", "", List.of());
    }

    // ===== ColumnRef =====

    public static ColumnRef filmIdCol() {
        return new ColumnRef("film_id", "FILM_ID", "java.lang.Integer");
    }

    public static ColumnRef languageIdCol() {
        return new ColumnRef("language_id", "LANGUAGE_ID", "java.lang.Integer");
    }

    public static ColumnRef titleCol() {
        return new ColumnRef("title", "TITLE", "java.lang.String");
    }

    public static ColumnRef col(String sqlName, String javaName, String javaType) {
        return new ColumnRef(sqlName, javaName, javaType);
    }

    // ===== ReturnTypeRef =====

    public static ReturnTypeRef.TableBoundReturnType tableBoundFilm(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType("Film", filmTable(), wrapper);
    }

    public static ReturnTypeRef.TableBoundReturnType tableBound(String typeName, TableRef table, FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType(typeName, table, wrapper);
    }

    // ===== FieldWrapper shortcuts =====

    public static FieldWrapper.Single single() {
        return new FieldWrapper.Single(true);
    }

    public static FieldWrapper.List listWrapper() {
        return new FieldWrapper.List(true, true);
    }

    public static FieldWrapper.List nonNullList() {
        return new FieldWrapper.List(false, false);
    }

    // ===== ChildField shortcuts =====

    public static ChildField.ColumnField columnField(String parentType, String name, String columnName,
                                                      String javaName, String columnClass) {
        return new ChildField.ColumnField(parentType, name, null, columnName,
            new ColumnRef(columnName, javaName, columnClass), false);
    }

    public static ChildField.NodeIdField nodeIdField(String parentType, String name, String nodeTypeId, List<ColumnRef> keyColumns) {
        return new ChildField.NodeIdField(parentType, name, null, nodeTypeId, keyColumns);
    }
}
