package no.sikt.graphitron.rewrite.walker;

import graphql.language.SourceLocation;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.ArgConditionRef;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.MatchedKey;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.UpdateRows;
import no.sikt.graphitron.rewrite.model.UpdateRowsError;
import no.sikt.graphitron.rewrite.model.WalkerResult;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R246 unit coverage for {@link UpdateRowsWalker}: the PK-or-UK identification and SET/WHERE
 * partition over already-classified {@link InputField} permits. Uses the real fixture catalogs so
 * the jOOQ {@code getPrimaryKey()} / {@code getKeys()} metadata is genuine:
 * <ul>
 *   <li>{@code public} ({@link #PUBLIC}): {@code film} (single PK {@code film_id}, no UK),
 *       {@code film_actor} (composite PK {@code (actor_id, film_id)}), {@code film_list}
 *       (keyless).</li>
 *   <li>{@code nodeidfixture} ({@link #NODE_FIXTURE}): {@code parent_node} (PK {@code pk_id} plus a
 *       non-PK {@code UNIQUE} on {@code alt_key}), {@code bar} (composite PK
 *       {@code (id_1, id_2)}).</li>
 * </ul>
 */
@UnitTier
class UpdateRowsWalkerTest {

    private static final String NODE_FIXTURE = "no.sikt.graphitron.rewrite.nodeidfixture";

    private final UpdateRowsWalker walker = new UpdateRowsWalker();
    private final JooqCatalog PUBLIC = new JooqCatalog(DEFAULT_JOOQ_PACKAGE);
    private final JooqCatalog NODE_FIXTURE_CATALOG = new JooqCatalog(NODE_FIXTURE);

    @Test
    void pkOnlyMatch_withExtraColumns_succeedsWithExtrasInSet() {
        var result = walker.walk(null, table("film"), List.of(
            columnField("filmId", col(PUBLIC, "film", "film_id")),
            columnField("title", col(PUBLIC, "film", "title")),
            columnField("description", col(PUBLIC, "film", "description"))
        ), PUBLIC, "input");

        var carrier = ok(result);
        assertThat(carrier.matchedKey()).isInstanceOf(MatchedKey.PrimaryKey.class);
        assertThat(sqlNames(carrier.matchedKey().columns())).containsExactly("film_id");
        assertThat(carrier.keyColumns()).extracting(k -> k.targetColumn().sqlName()).containsExactly("film_id");
        assertThat(carrier.setColumns()).extracting(s -> s.targetColumn().sqlName())
            .containsExactlyInAnyOrder("title", "description");
    }

    @Test
    void pkOnlyMatch_noExtraColumns_rejectsWithNoSetFields() {
        var result = walker.walk(null, table("film"), List.of(
            columnField("filmId", col(PUBLIC, "film", "film_id"))
        ), PUBLIC, "input");

        assertThat(only(result)).isInstanceOf(UpdateRowsError.NoSetFields.class);
    }

    @Test
    void ukOnlyMatch_pkNotCovered_succeedsWithUniqueKey() {
        var result = walker.walk(null, table("parent_node"), List.of(
            columnField("altKey", col(NODE_FIXTURE_CATALOG, "parent_node", "alt_key")),
            columnField("name", col(NODE_FIXTURE_CATALOG, "parent_node", "name"))
        ), NODE_FIXTURE_CATALOG, "input");

        var carrier = ok(result);
        assertThat(carrier.matchedKey()).isInstanceOf(MatchedKey.UniqueKey.class);
        assertThat(sqlNames(carrier.matchedKey().columns())).containsExactly("alt_key");
        assertThat(carrier.setColumns()).extracting(s -> s.targetColumn().sqlName()).containsExactly("name");
    }

    @Test
    void pkPreferredTiebreaker_bothCovered_pkWins() {
        var result = walker.walk(null, table("parent_node"), List.of(
            columnField("pkId", col(NODE_FIXTURE_CATALOG, "parent_node", "pk_id")),
            columnField("altKey", col(NODE_FIXTURE_CATALOG, "parent_node", "alt_key")),
            columnField("name", col(NODE_FIXTURE_CATALOG, "parent_node", "name"))
        ), NODE_FIXTURE_CATALOG, "input");

        var carrier = ok(result);
        assertThat(carrier.matchedKey()).isInstanceOf(MatchedKey.PrimaryKey.class);
        assertThat(sqlNames(carrier.matchedKey().columns())).containsExactly("pk_id");
        // alt_key falls outside the matched PK, so it lands in SET alongside name.
        assertThat(carrier.setColumns()).extracting(s -> s.targetColumn().sqlName())
            .containsExactlyInAnyOrder("alt_key", "name");
    }

    @Test
    void noKeyCovered_rejectsWithNoUniqueKeyCoverage() {
        var result = walker.walk(null, table("film"), List.of(
            columnField("title", col(PUBLIC, "film", "title")),
            columnField("description", col(PUBLIC, "film", "description"))
        ), PUBLIC, "input");

        var err = only(result);
        assertThat(err).isInstanceOf(UpdateRowsError.NoUniqueKeyCoverage.class);
        var coverage = (UpdateRowsError.NoUniqueKeyCoverage) err;
        assertThat(coverage.candidateKeys()).isNotEmpty();
        assertThat(coverage.table()).isEqualTo("film");
    }

    @Test
    void compositeReferenceStraddlesKey_rejectsWithMixedCarrierKeyMembership() {
        var result = walker.walk(null, table("film_actor"), List.of(
            // One composite-reference field whose lifted columns straddle the (actor_id, film_id) PK:
            // actor_id is in the key, last_update is not.
            compositeReferenceField("straddle", List.of(
                col(PUBLIC, "film_actor", "actor_id"),
                col(PUBLIC, "film_actor", "last_update"))),
            columnField("filmId", col(PUBLIC, "film_actor", "film_id"))
        ), PUBLIC, "input");

        var err = only(result);
        assertThat(err).isInstanceOf(UpdateRowsError.MixedCarrierKeyMembership.class);
        var mixed = (UpdateRowsError.MixedCarrierKeyMembership) err;
        assertThat(mixed.fieldName()).isEqualTo("straddle");
        assertThat(sqlNames(mixed.columnsInKey())).containsExactly("actor_id");
        assertThat(sqlNames(mixed.columnsOutsideKey())).containsExactly("last_update");
    }

    @Test
    void unsupportedShapes_collectedAcrossLoopWithoutShortCircuit() {
        var result = walker.walk(null, table("film"), List.of(
            listNestingField("nested"),
            unboundField("orphan", false)
        ), PUBLIC, "input");

        var errors = errors(result);
        assertThat(errors).hasSize(2);
        assertThat(errors).allMatch(e -> e instanceof UpdateRowsError.UnsupportedInputFieldShape);
        assertThat(errors).extracting(e -> ((UpdateRowsError.UnsupportedInputFieldShape) e).fieldName())
            .containsExactlyInAnyOrder("nested", "orphan");
    }

    @Test
    void overrideConditionField_rejectsWithOverrideConditionNotSupported() {
        var loc = new SourceLocation(7, 3);
        var result = walker.walk(null, table("film"), List.of(
            unboundFieldAt("syntheticName", true, loc)
        ), PUBLIC, "input");

        var err = only(result);
        assertThat(err).isInstanceOf(UpdateRowsError.OverrideConditionNotSupported.class);
        var override = (UpdateRowsError.OverrideConditionNotSupported) err;
        assertThat(override.fieldName()).isEqualTo("syntheticName");
        assertThat(override.conditionLocation()).isEqualTo(loc);
    }

    @Test
    void tableWithNoKeys_rejectsWithNoUniqueKeyCoverageAndEmptyCandidates() {
        var result = walker.walk(null, table("film_list"), List.of(
            columnField("title", col(PUBLIC, "film_list", "title")),
            columnField("category", col(PUBLIC, "film_list", "category"))
        ), PUBLIC, "input");

        var err = only(result);
        assertThat(err).isInstanceOf(UpdateRowsError.NoUniqueKeyCoverage.class);
        assertThat(((UpdateRowsError.NoUniqueKeyCoverage) err).candidateKeys()).isEmpty();
    }

    @Test
    void compositePkMatch_throughCompositeNodeIdField_succeeds() {
        var result = walker.walk(null, table("bar"), List.of(
            compositeColumnField("ref", List.of(
                col(NODE_FIXTURE_CATALOG, "bar", "id_1"),
                col(NODE_FIXTURE_CATALOG, "bar", "id_2"))),
            columnField("name", col(NODE_FIXTURE_CATALOG, "bar", "name"))
        ), NODE_FIXTURE_CATALOG, "input");

        var carrier = ok(result);
        assertThat(carrier.matchedKey()).isInstanceOf(MatchedKey.PrimaryKey.class);
        assertThat(sqlNames(carrier.matchedKey().columns())).containsExactlyInAnyOrder("id_1", "id_2");
        // One SDL field produced two key columns sharing the same sdlFieldName.
        assertThat(carrier.keyColumns()).hasSize(2);
        assertThat(carrier.keyColumns()).extracting(k -> k.sdlFieldName()).containsOnly("ref");
        assertThat(carrier.setColumns()).extracting(s -> s.targetColumn().sqlName()).containsExactly("name");
    }

    @Test
    void fkReferenceAdmissibility_keyColumnReferenceLandsInWhere_nonKeyInSet() {
        var result = walker.walk(null, table("film"), List.of(
            // FK-reference carrier on the PK column lands in the WHERE half ...
            columnReferenceField("filmRef", List.of(col(PUBLIC, "film", "film_id"))),
            // ... and a reference carrier on a non-key column lands in the SET half.
            columnReferenceField("languageRef", List.of(col(PUBLIC, "film", "language_id")))
        ), PUBLIC, "input");

        var carrier = ok(result);
        assertThat(carrier.keyColumns()).extracting(k -> k.targetColumn().sqlName()).containsExactly("film_id");
        assertThat(carrier.setColumns()).extracting(s -> s.targetColumn().sqlName()).containsExactly("language_id");
    }

    // --- result helpers ---

    private static UpdateRows.Identified ok(WalkerResult<UpdateRows> r) {
        assertThat(r).isInstanceOf(WalkerResult.Ok.class);
        return (UpdateRows.Identified) ((WalkerResult.Ok<UpdateRows>) r).carrier();
    }

    private static List<Rejection.AuthorError> errors(WalkerResult<UpdateRows> r) {
        assertThat(r).isInstanceOf(WalkerResult.Err.class);
        return ((WalkerResult.Err<UpdateRows>) r).errors();
    }

    private static Rejection.AuthorError only(WalkerResult<UpdateRows> r) {
        var errors = errors(r);
        assertThat(errors).hasSize(1);
        return errors.getFirst();
    }

    private static List<String> sqlNames(List<ColumnRef> columns) {
        return columns.stream().map(ColumnRef::sqlName).toList();
    }

    // --- fixture builders ---

    private static TableRef table(String sqlName) {
        var cn = ClassName.get("fixture", "T");
        return new TableRef(sqlName, sqlName.toUpperCase(), cn, cn, cn, List.of());
    }

    private static ColumnRef col(JooqCatalog catalog, String table, String name) {
        var e = catalog.findColumn(table, name).orElseThrow(
            () -> new IllegalStateException("fixture column not found: " + table + "." + name));
        return new ColumnRef(e.sqlName(), e.javaName(), e.columnClass());
    }

    private static InputField.ColumnField columnField(String name, ColumnRef column) {
        return new InputField.ColumnField("In", name, loc(), "Scalar", true, false,
            column, Optional.empty(), new CallSiteExtraction.Direct());
    }

    private static InputField.CompositeColumnField compositeColumnField(String name, List<ColumnRef> columns) {
        return new InputField.CompositeColumnField("In", name, loc(), "ID", true, false,
            columns, Optional.empty(), dummyDecode(columns));
    }

    private static InputField.ColumnReferenceField columnReferenceField(String name, List<ColumnRef> lifted) {
        return new InputField.ColumnReferenceField("In", name, loc(), "ID", true, false,
            lifted.getFirst(), List.of(), lifted, Optional.empty(), new CallSiteExtraction.Direct());
    }

    private static InputField.CompositeColumnReferenceField compositeReferenceField(String name, List<ColumnRef> lifted) {
        return new InputField.CompositeColumnReferenceField("In", name, loc(), "ID", true, false,
            lifted, List.of(), lifted, Optional.empty(), dummyDecode(lifted));
    }

    // R186 admits a plain (non-list) NestingField by flattening it; a list-typed nesting stays
    // unsupported, so this helper builds the list-typed shape for the unsupported-shape coverage.
    private static InputField.NestingField listNestingField(String name) {
        return new InputField.NestingField("In", name, loc(), "Nested", false, true, List.of(), Optional.empty());
    }

    private static InputField.UnboundField unboundField(String name, boolean override) {
        return unboundFieldAt(name, override, loc());
    }

    private static InputField.UnboundField unboundFieldAt(String name, boolean override, SourceLocation location) {
        var condition = override ? Optional.of(new ArgConditionRef(null, true)) : Optional.<ArgConditionRef>empty();
        return new InputField.UnboundField("In", name, location, "String", false, false, condition, null);
    }

    private static CallSiteExtraction.NodeIdDecodeKeys dummyDecode(List<ColumnRef> columns) {
        return new CallSiteExtraction.NodeIdDecodeKeys.SkipMismatchedElement(
            new HelperRef.Decode(ClassName.get("fixture", "Enc"), "decode", columns));
    }

    private static SourceLocation loc() {
        return new SourceLocation(1, 1);
    }
}
