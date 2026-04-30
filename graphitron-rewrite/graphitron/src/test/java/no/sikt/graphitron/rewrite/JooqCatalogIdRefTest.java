package no.sikt.graphitron.rewrite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link JooqCatalog} FK-qualifier helpers used by the {@code @nodeId}
 * input-field classification (the post-R50 column-shaped successors of the retired
 * {@code IdReferenceField}: {@link no.sikt.graphitron.rewrite.model.InputField.ColumnReferenceField}
 * and {@link no.sikt.graphitron.rewrite.model.InputField.CompositeColumnReferenceField}).
 *
 * <p>Sakila-catalog cases ({@code DEFAULT_JOOQ_PACKAGE}) exercise {@link JooqCatalog#findUniqueFkToTable},
 * {@link JooqCatalog#buildQualifierMap}, and {@link JooqCatalog#qualifierForFk} against real FK
 * metadata. Static-helper cases ({@link JooqCatalog#generateRoleName}) use plain {@code List<String>}
 * inputs and need no catalog instance.
 */
class JooqCatalogIdRefTest {

    private static final String NODEID_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.nodeidfixture";
    private static final String IDREF_JOOQ_PACKAGE  = "no.sikt.graphitron.rewrite.idreffixture";

    private static JooqCatalog sakila() {
        return new JooqCatalog(DEFAULT_JOOQ_PACKAGE);
    }

    private static JooqCatalog nodeid() {
        return new JooqCatalog(NODEID_JOOQ_PACKAGE);
    }

    private static JooqCatalog idref() {
        return new JooqCatalog(IDREF_JOOQ_PACKAGE);
    }

    // --- findUniqueFkToTable ---

    @Test
    void findUniqueFkToTable_uniqueFk_returnsConstraintName() {
        var result = sakila().findUniqueFkToTable("inventory", "film");
        assertThat(result).hasValue("inventory_film_id_fkey");
    }

    @Test
    void findUniqueFkToTable_multipleFks_returnsEmpty() {
        // film has two FKs to language: film_language_id_fkey and film_original_language_id_fkey
        var result = sakila().findUniqueFkToTable("film", "language");
        assertThat(result).isEmpty();
    }

    @Test
    void findUniqueFkToTable_noFkToTarget_returnsEmpty() {
        // actor has no FK to language
        var result = sakila().findUniqueFkToTable("actor", "language");
        assertThat(result).isEmpty();
    }

    @Test
    void findUniqueFkToTable_directionality_sourceNotTarget() {
        // film is the FK target of inventory; there is no FK from film to inventory
        var result = sakila().findUniqueFkToTable("film", "inventory");
        assertThat(result).isEmpty();
    }

    // --- buildQualifierMap ---

    @Test
    void buildQualifierMap_containsRawQualifierKey() {
        // inventory_film_id_fkey: source=film_id, target=film_id → HAR role → raw key "film_id"
        var map = sakila().buildQualifierMap("inventory");
        assertThat(map).containsEntry("film_id", "inventory_film_id_fkey");
    }

    @Test
    void buildQualifierMap_containsCamelKey() {
        // qualifier "FilmId" → lowerFirst.toLowerCase() = "filmid"
        var map = sakila().buildQualifierMap("inventory");
        assertThat(map).containsEntry("filmid", "inventory_film_id_fkey");
    }

    @Test
    void buildQualifierMap_containsPluralCamelKey() {
        // plural form: "filmids"
        var map = sakila().buildQualifierMap("inventory");
        assertThat(map).containsEntry("filmids", "inventory_film_id_fkey");
    }

    @Test
    void buildQualifierMap_cachedInstanceIsSame() {
        var catalog = sakila();
        var first = catalog.buildQualifierMap("inventory");
        var second = catalog.buildQualifierMap("inventory");
        assertThat(first).isSameAs(second);
    }

    @Test
    void buildQualifierMap_unknownTable_returnsEmptyMap() {
        var map = sakila().buildQualifierMap("no_such_table");
        assertThat(map).isEmpty();
    }

    @Test
    void buildQualifierMap_nodeidFixture_barToBasQualifierKeys() {
        // bar_id_1_fkey: source=id_1, target=id → role="1" → qualifier "1BazId" → raw "1_baz_id"
        var map = nodeid().buildQualifierMap("bar");
        assertThat(map).containsKey("1_baz_id");
        assertThat(map.get("1_baz_id")).isEqualTo("bar_id_1_fkey");
    }

    // --- qualifierForFk ---

    @Test
    void qualifierForFk_inventoryFilmFk_returnsFilmId() {
        var result = sakila().qualifierForFk("inventory", "inventory_film_id_fkey");
        assertThat(result).hasValue("FilmId");
    }

    @Test
    void qualifierForFk_filmLanguageFk_returnsLanguageId() {
        var result = sakila().qualifierForFk("film", "film_language_id_fkey");
        assertThat(result).hasValue("LanguageId");
    }

    @Test
    void qualifierForFk_wrongSourceTable_returnsEmpty() {
        // inventory_film_id_fkey belongs to inventory, not film
        var result = sakila().qualifierForFk("film", "inventory_film_id_fkey");
        assertThat(result).isEmpty();
    }

    @Test
    void qualifierForFk_nodeidFixture_barToBasReturns1BazId() {
        var result = nodeid().qualifierForFk("bar", "bar_id_1_fkey");
        assertThat(result).hasValue("1BazId");
    }

    // --- generateRoleName (static helper) ---

    @ParameterizedTest
    @CsvSource({
        "language_id, language_id, HAR",   // source col equals target col
        "film_id,     film_id,     HAR",   // HAR: no role prefix needed
        "id_1,        id,          1",     // src.startsWith(tgt) → suffix after separator
        "id_2,        id,          2",     // same pattern, different suffix
    })
    void generateRoleName_representativeShapes(String srcCol, String tgtCol, String expectedRole) {
        var result = JooqCatalog.generateRoleName(List.of(srcCol), List.of(tgtCol));
        assertThat(result).isEqualTo(expectedRole);
    }

    @Test
    void generateRoleName_unrelatedColumns_returnsSrcColumn() {
        // Neither starts with the other → role = src column name
        var result = JooqCatalog.generateRoleName(List.of("foo"), List.of("bar"));
        assertThat(result).isEqualTo("foo");
    }

    // --- idreffixture cases ---

    @Test
    void qualifierForFk_idrefFixture_harRoleReturnsStudieprogramId() {
        // studierett.studieprogram_id → studieprogram.studieprogram_id: HAR role → qualifier "StudieprogramId"
        var result = idref().qualifierForFk("studierett", "studierett_studieprogram_id_fkey");
        assertThat(result).hasValue("StudieprogramId");
    }

    @Test
    void qualifierForFk_idrefFixture_rolePrefixReturnsRegistrarQualifier() {
        // studierett.registrar_studieprogram → studieprogram.studieprogram_id:
        // role = "registrar_studieprogram" (src and tgt are unrelated) →
        // qualifier "RegistrarStudieprogramStudieprogramId"
        var result = idref().qualifierForFk("studierett", "studierett_registrar_studieprogram_fkey");
        assertThat(result).hasValue("RegistrarStudieprogramStudieprogramId");
    }

    @Test
    void buildQualifierMap_idrefFixture_containsRawAndPluralKeys() {
        var map = idref().buildQualifierMap("studierett");
        // FK1 (HAR role): raw key = "studieprogram_id", plural camel = "studieprogramids"
        assertThat(map).containsEntry("studieprogram_id", "studierett_studieprogram_id_fkey");
        assertThat(map).containsEntry("studieprogramids", "studierett_studieprogram_id_fkey");
        // FK2 (role prefix): raw key differs from any source column
        assertThat(map).containsEntry(
            "registrar_studieprogram_studieprogram_id",
            "studierett_registrar_studieprogram_fkey");
    }
}
