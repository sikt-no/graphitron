package no.sikt.graphitron.rewrite.walker;

import graphql.language.SourceLocation;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.ArgConditionRef;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.DeleteRows;
import no.sikt.graphitron.rewrite.model.DeleteRowsError;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.MatchedKey;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WalkerResult;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R266 unit coverage for {@link DeleteRowsWalker}: the PK-or-UK identification and the
 * every-column-is-WHERE projection over already-classified {@link InputField} permits, plus the
 * {@code multiRow} → {@link DeleteRows.Broadcast} arm. Uses the same real fixture catalogs as
 * {@code UpdateRowsWalkerTest} so the jOOQ {@code getPrimaryKey()} / {@code getKeys()} metadata is
 * genuine:
 * <ul>
 *   <li>{@code public} ({@link #PUBLIC}): {@code film} (single PK {@code film_id}, no UK),
 *       {@code film_actor} (composite PK {@code (actor_id, film_id)}), {@code film_list}
 *       (keyless).</li>
 *   <li>{@code nodeidfixture} ({@link #NODE_FIXTURE}): {@code parent_node} (PK {@code pk_id} plus a
 *       non-PK {@code UNIQUE} on {@code alt_key}), {@code bar} (composite PK
 *       {@code (id_1, id_2)}).</li>
 * </ul>
 *
 * <p>The contrast with UPDATE is the load-bearing point: DELETE has no SET partition, so every
 * admitted column is a WHERE filter ({@link DeleteRows#whereColumns()}), a PK-only input admits
 * (no {@code NoSetFields}), a composite carrier never straddles (no {@code MixedCarrierKeyMembership}),
 * and {@code multiRow} turns an under-keyed input into a {@link DeleteRows.Broadcast} instead of a
 * rejection.
 */
@UnitTier
class DeleteRowsWalkerTest {

    private static final String NODE_FIXTURE = "no.sikt.graphitron.rewrite.nodeidfixture";

    private final DeleteRowsWalker walker = new DeleteRowsWalker();
    private final JooqCatalog PUBLIC = new JooqCatalog(DEFAULT_JOOQ_PACKAGE);
    private final JooqCatalog NODE_FIXTURE_CATALOG = new JooqCatalog(NODE_FIXTURE);

    @Test
    void pkMatch_withExtraColumns_everyColumnLandsInWhere() {
        var result = walker.walk(null, table("film"), List.of(
            columnField("filmId", col(PUBLIC, "film", "film_id")),
            columnField("title", col(PUBLIC, "film", "title")),
            columnField("description", col(PUBLIC, "film", "description"))
        ), PUBLIC, false, "input");

        var carrier = (DeleteRows.Identified) ok(result);
        assertThat(carrier.matchedKey()).isInstanceOf(MatchedKey.PrimaryKey.class);
        assertThat(sqlNames(carrier.matchedKey().columns())).containsExactly("film_id");
        // Unlike UPDATE, the non-key columns are not partitioned into SET — they are additional
        // ANDed WHERE predicates, so whereColumns is every admitted input column.
        assertThat(carrier.whereColumns()).extracting(k -> k.targetColumn().sqlName())
            .containsExactlyInAnyOrder("film_id", "title", "description");
    }

    @Test
    void pkOnlyInput_admits_noNoSetFieldsAnalogue() {
        // A PK-only DELETE is the canonical single-row delete; UPDATE would reject this as
        // NoSetFields, but DELETE has no SET clause so it admits with the PK as the only filter.
        var result = walker.walk(null, table("film"), List.of(
            columnField("filmId", col(PUBLIC, "film", "film_id"))
        ), PUBLIC, false, "input");

        var carrier = (DeleteRows.Identified) ok(result);
        assertThat(carrier.matchedKey()).isInstanceOf(MatchedKey.PrimaryKey.class);
        assertThat(carrier.whereColumns()).extracting(k -> k.targetColumn().sqlName()).containsExactly("film_id");
    }

    @Test
    void ukMatch_pkNotCovered_succeedsWithUniqueKey() {
        var result = walker.walk(null, table("parent_node"), List.of(
            columnField("altKey", col(NODE_FIXTURE_CATALOG, "parent_node", "alt_key"))
        ), NODE_FIXTURE_CATALOG, false, "input");

        var carrier = (DeleteRows.Identified) ok(result);
        assertThat(carrier.matchedKey()).isInstanceOf(MatchedKey.UniqueKey.class);
        assertThat(sqlNames(carrier.matchedKey().columns())).containsExactly("alt_key");
        assertThat(carrier.whereColumns()).extracting(k -> k.targetColumn().sqlName()).containsExactly("alt_key");
    }

    @Test
    void pkPreferredTiebreaker_bothCovered_pkWins() {
        var result = walker.walk(null, table("parent_node"), List.of(
            columnField("pkId", col(NODE_FIXTURE_CATALOG, "parent_node", "pk_id")),
            columnField("altKey", col(NODE_FIXTURE_CATALOG, "parent_node", "alt_key"))
        ), NODE_FIXTURE_CATALOG, false, "input");

        var carrier = (DeleteRows.Identified) ok(result);
        assertThat(carrier.matchedKey()).isInstanceOf(MatchedKey.PrimaryKey.class);
        assertThat(sqlNames(carrier.matchedKey().columns())).containsExactly("pk_id");
        // alt_key is an extra WHERE predicate, not a SET column.
        assertThat(carrier.whereColumns()).extracting(k -> k.targetColumn().sqlName())
            .containsExactlyInAnyOrder("pk_id", "alt_key");
    }

    @Test
    void compositePkMatch_throughCompositeNodeIdField_succeeds() {
        var result = walker.walk(null, table("bar"), List.of(
            compositeColumnField("ref", List.of(
                col(NODE_FIXTURE_CATALOG, "bar", "id_1"),
                col(NODE_FIXTURE_CATALOG, "bar", "id_2")))
        ), NODE_FIXTURE_CATALOG, false, "input");

        var carrier = (DeleteRows.Identified) ok(result);
        assertThat(carrier.matchedKey()).isInstanceOf(MatchedKey.PrimaryKey.class);
        assertThat(sqlNames(carrier.matchedKey().columns())).containsExactlyInAnyOrder("id_1", "id_2");
        // One SDL field produced two WHERE columns sharing the same sdlFieldName.
        assertThat(carrier.whereColumns()).hasSize(2);
        assertThat(carrier.whereColumns()).extracting(k -> k.sdlFieldName()).containsOnly("ref");
    }

    @Test
    void noKeyCovered_notMultiRow_rejectsWithNoUniqueKeyCoverage() {
        var result = walker.walk(null, table("film"), List.of(
            columnField("title", col(PUBLIC, "film", "title")),
            columnField("description", col(PUBLIC, "film", "description"))
        ), PUBLIC, false, "input");

        var err = only(result);
        assertThat(err).isInstanceOf(DeleteRowsError.NoUniqueKeyCoverage.class);
        var coverage = (DeleteRowsError.NoUniqueKeyCoverage) err;
        assertThat(coverage.table()).isEqualTo("film");
        assertThat(coverage.candidateKeys()).isNotEmpty();
        assertThat(coverage.message()).contains("multiRow: true");
    }

    @Test
    void noKeyCovered_multiRow_succeedsWithBroadcast() {
        var result = walker.walk(null, table("film"), List.of(
            columnField("releaseYear", col(PUBLIC, "film", "release_year"))
        ), PUBLIC, true, "input");

        var carrier = ok(result);
        assertThat(carrier).isInstanceOf(DeleteRows.Broadcast.class);
        assertThat(carrier.whereColumns()).extracting(k -> k.targetColumn().sqlName()).containsExactly("release_year");
    }

    @Test
    void keyCovered_multiRow_isMoot_returnsIdentified() {
        // multiRow is an opt-in to broadcast; a covered key already proves single-row, so the
        // walker prefers Identified regardless of the flag.
        var result = walker.walk(null, table("film"), List.of(
            columnField("filmId", col(PUBLIC, "film", "film_id"))
        ), PUBLIC, true, "input");

        assertThat(ok(result)).isInstanceOf(DeleteRows.Identified.class);
    }

    @Test
    void tableWithNoKeys_notMultiRow_rejectsWithEmptyCandidates() {
        var result = walker.walk(null, table("film_list"), List.of(
            columnField("title", col(PUBLIC, "film_list", "title")),
            columnField("category", col(PUBLIC, "film_list", "category"))
        ), PUBLIC, false, "input");

        var err = only(result);
        assertThat(err).isInstanceOf(DeleteRowsError.NoUniqueKeyCoverage.class);
        assertThat(((DeleteRowsError.NoUniqueKeyCoverage) err).candidateKeys()).isEmpty();
    }

    @Test
    void tableWithNoKeys_multiRow_succeedsWithBroadcast() {
        var result = walker.walk(null, table("film_list"), List.of(
            columnField("title", col(PUBLIC, "film_list", "title"))
        ), PUBLIC, true, "input");

        assertThat(ok(result)).isInstanceOf(DeleteRows.Broadcast.class);
    }

    @Test
    void overrideConditionField_rejectsWithOverrideConditionNotSupported() {
        var loc = new SourceLocation(7, 3);
        var result = walker.walk(null, table("film"), List.of(
            unboundFieldAt("syntheticName", true, loc)
        ), PUBLIC, false, "input");

        var err = only(result);
        assertThat(err).isInstanceOf(DeleteRowsError.OverrideConditionNotSupported.class);
        var override = (DeleteRowsError.OverrideConditionNotSupported) err;
        assertThat(override.fieldName()).isEqualTo("syntheticName");
        assertThat(override.conditionLocation()).isEqualTo(loc);
    }

    @Test
    void unsupportedShapes_collectedAcrossLoopWithoutShortCircuit() {
        var result = walker.walk(null, table("film"), List.of(
            listNestingField("nested"),
            unboundField("orphan", false)
        ), PUBLIC, false, "input");

        var errors = errors(result);
        assertThat(errors).hasSize(2);
        assertThat(errors).allMatch(e -> e instanceof DeleteRowsError.UnsupportedInputFieldShape);
        assertThat(errors).extracting(e -> ((DeleteRowsError.UnsupportedInputFieldShape) e).fieldName())
            .containsExactlyInAnyOrder("nested", "orphan");
    }

    @Test
    void emptyBroadcast_rejectedByCompactConstructor() {
        // The walker never produces this (graphql-java rejects empty input types at parse time), but
        // the carrier's compact constructor makes "unfiltered DELETE" unrepresentable on the type.
        assertThatThrownBy(() -> new DeleteRows.Broadcast(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unfiltered DELETE");
    }

    // --- result helpers ---

    private static DeleteRows ok(WalkerResult<DeleteRows> r) {
        assertThat(r).isInstanceOf(WalkerResult.Ok.class);
        return ((WalkerResult.Ok<DeleteRows>) r).carrier();
    }

    private static List<Rejection.AuthorError> errors(WalkerResult<DeleteRows> r) {
        assertThat(r).isInstanceOf(WalkerResult.Err.class);
        return ((WalkerResult.Err<DeleteRows>) r).errors();
    }

    private static Rejection.AuthorError only(WalkerResult<DeleteRows> r) {
        var errors = errors(r);
        assertThat(errors).hasSize(1);
        return errors.getFirst();
    }

    private static List<String> sqlNames(List<ColumnRef> columns) {
        return columns.stream().map(ColumnRef::sqlName).toList();
    }

    // --- fixture builders (shared shape with UpdateRowsWalkerTest) ---

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
            new HelperRef.Decode(ClassName.get("fixture", "Enc"), "decode", columns, "Type"));
    }

    private static SourceLocation loc() {
        return new SourceLocation(1, 1);
    }
}
