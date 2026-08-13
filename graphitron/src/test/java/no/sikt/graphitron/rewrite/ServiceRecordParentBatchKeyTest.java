package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.ServiceKeySource;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A child {@code @service} field batches against a key its parent can produce, and the {@code Sources}
 * element type names that key. On a {@code @table} parent the element type is a consequence of the
 * coordinate (the key is the parent's own primary key); on a class-backed parent it is the input to
 * it, and the coordinate asks the parent one question with a determinate answer: can you produce a
 * record of that table?
 *
 * <p>Two producers qualify, and they are the two class-backed {@link ServiceKeySource} arms: the
 * parent's backing <em>is</em> the declared record, or it exposes exactly one zero-arg accessor
 * returning one. Everything else is a rejection naming both routes, and each rejection has its own
 * fixture below rather than being asserted as a bare "unclassified".
 *
 * <p>No arm asserts a {@code KeyLift}: these leaves carry none. The {@code @service} path's wrap is
 * authored by the signature rather than derived from a lift, and asserting a lift alongside the
 * service {@code SourceKey} is unsatisfiable, not merely redundant, since
 * {@code KeyLift.checkResidueAgreement} would compare the lift's derived wrap against the stored
 * {@link SourceKey.Wrap.TableRecord}.
 */
@PipelineTier
class ServiceRecordParentBatchKeyTest {

    private static final String SVC = "no.sikt.graphitron.rewrite.generators.TestFilmService";
    private static final String DUMMY = "no.sikt.graphitron.codereferences.dummyreferences.DummyService";

    /**
     * A class-backed parent reachable from the root, its backing class bound by the reflected return
     * type of the {@code @service} producer that mints it.
     */
    private static String classBackedParent(String producer, String parentFields) {
        return """
            type Film @table(name: "film") { title: String }
            type Language @table(name: "language") { name: String @field(name: "name") }
            type Parent {
            %s
            }
            type Query {
                parent: Parent @service(service: {className: "%s", method: "%s"})
            }
            """.formatted(parentFields, DUMMY, producer);
    }

    private static String reasonOf(GraphitronSchema schema, String type, String field) {
        var f = schema.field(type, field);
        assertThat(f)
            .as("field '%s.%s' must be rejected", type, field)
            .isInstanceOf(UnclassifiedField.class);
        return ((UnclassifiedField) f).reason();
    }

    // ===== The two class-backed producers =====

    /**
     * The accessor arm. {@code ServiceKeyPayloads.LanguageKeyed} exposes exactly one zero-arg accessor
     * returning a {@code language} record, and the {@code Set<LanguageRecord>} parameter names that
     * table, so the key columns are {@code language}'s primary key read off the record the accessor
     * returns. The accessor is not named after the SDL field, which is the point: the element type
     * names the key, not the field.
     */
    @Test
    void classBackedParentWithOneTypedAccessor_keysOffThatAccessor() {
        var schema = TestSchemaHelper.buildSchema(classBackedParent("makeLanguageKeyed", """
                title: String
                rank: Int @service(service: {className: "%s", method: "getRankMappedByRecord"})
            """.formatted(SVC)));

        var field = schema.field("Parent", "rank");
        assertThat(field)
            .as("a class-backed parent that can produce the declared key hosts a batched child")
            .isInstanceOf(ChildField.ServiceRecordField.class);
        var srf = (ChildField.ServiceRecordField) field;
        assertThat(srf.keySource())
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(ServiceKeySource.FromAccessor.class))
            .satisfies(acc -> {
                assertThat(acc.keyOwner().tableName()).isEqualTo("language");
                assertThat(acc.accessor().methodName())
                    .as("the sole record-returning accessor supplies the key")
                    .isEqualTo("lang");
                assertThat(acc.accessor().elementClass().simpleName()).isEqualTo("LanguageRecord");
            });
        assertThat(srf.sourceKey().wrap())
            .as("the wrap is authored by the Sources signature, not derived from a lift")
            .isInstanceOf(SourceKey.Wrap.TableRecord.class);
        assertThat(srf.sourceKey().columns())
            .extracting(no.sikt.graphitron.rewrite.model.ColumnRef::sqlName)
            .as("the key columns are the element table's primary key, not the parent's")
            .containsExactly("language_id");
        assertThat(srf.sourceShape())
            .as("a class-backed parent hands the fetcher a producer-held record")
            .isEqualTo(SourceShape.Record);
    }

    /**
     * The held-record arm: the parent's backing class <em>is</em> a {@code language} record, so no
     * author declaration beyond the element type is needed and the key is read straight off
     * {@code env.getSource()}.
     */
    @Test
    void jooqTableRecordParent_keysOffTheRecordItHolds() {
        var schema = TestSchemaHelper.buildSchema("""
            type Parent {
                rank: Int @service(service: {className: "%s", method: "getRankMappedByRecord"})
            }
            type Query {
                parent: Parent
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguage"})
            }
            """.formatted(SVC));

        var field = schema.field("Parent", "rank");
        assertThat(field).isInstanceOf(ChildField.ServiceRecordField.class);
        var srf = (ChildField.ServiceRecordField) field;
        assertThat(srf.keySource())
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(ServiceKeySource.FromHeldRecord.class))
            .satisfies(held -> assertThat(held.keyOwner().tableName()).isEqualTo("language"));
        assertThat(srf.sourceShape()).isEqualTo(SourceShape.Record);
    }

    /**
     * A table-bound return on a class-backed parent mints the other service leaf, so the derivation
     * has to answer for both. The leaf's identity says nothing about the parent kind here: the very
     * same leaf is minted on a {@code @table} parent below.
     */
    @Test
    void classBackedParentWithTableBoundReturn_keysOffTheAccessorToo() {
        var schema = TestSchemaHelper.buildSchema(classBackedParent("makeLanguageKeyed", """
                title: String
                filmsViaService: [Film!]! @service(service: {className: "%s", method: "getFilmsMappedByRecord"})
            """.formatted(SVC)));

        var field = schema.field("Parent", "filmsViaService");
        assertThat(field).isInstanceOf(ChildField.ServiceTableField.class);
        var stf = (ChildField.ServiceTableField) field;
        assertThat(stf.keySource()).isInstanceOf(ServiceKeySource.FromAccessor.class);
        assertThat(stf.sourceShape())
            .as("the table-bound service leaf spans both parent kinds, so it derives too")
            .isEqualTo(SourceShape.Record);
    }

    /** The regression pin for the swap: a {@code @table} parent still reads its own projected row. */
    @Test
    void tableParent_stillKeysOffItsOwnProjectedRow() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") {
                name: String @field(name: "name")
                rank: Int @service(service: {className: "%s", method: "getRankMappedByRecord"})
            }
            type Query { language: Language }
            """.formatted(SVC));

        var field = schema.field("Language", "rank");
        assertThat(field).isInstanceOf(ChildField.ServiceRecordField.class);
        var srf = (ChildField.ServiceRecordField) field;
        assertThat(srf.keySource())
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(ServiceKeySource.FromTableRow.class))
            .satisfies(row -> assertThat(row.keyOwner().tableName()).isEqualTo("language"));
        assertThat(srf.sourceShape()).isEqualTo(SourceShape.Table);
    }

    // ===== The rejections =====

    /** No producer: the backing class has no accessor returning the declared record. */
    @Test
    void classBackedParentThatCannotProduceTheKey_isRejectedNamingBothRoutes() {
        var schema = TestSchemaHelper.buildSchema(classBackedParent("makeNoRecordAccessor", """
                title: String
                rank: Int @service(service: {className: "%s", method: "getRankMappedByRecord"})
            """.formatted(SVC)));

        assertThat(reasonOf(schema, "Parent", "rank"))
            .contains("LanguageRecord", "language", "cannot produce one")
            .contains("expose a zero-arg accessor")
            .contains("only scalar key columns has no route");
    }

    /** More than one accessor returning the declared record: which one produces the key is ambiguous. */
    @Test
    void classBackedParentWithTwoMatchingAccessors_isRejectedNamingBoth() {
        var schema = TestSchemaHelper.buildSchema(classBackedParent("makeTwoLanguageAccessors", """
                rank: Int @service(service: {className: "%s", method: "getRankMappedByRecord"})
            """.formatted(SVC)));

        assertThat(reasonOf(schema, "Parent", "rank"))
            .contains("more than one zero-arg accessor")
            .contains("fallback", "primary")
            .contains("ambiguous");
    }

    /**
     * A list-valued accessor fans one parent out to many keys, which the service contract's
     * {@code Map<Key, Value>} return cannot express. Rejected by name and left to a follow-up that
     * designs the fan-in, rather than silently keying on the first element.
     */
    @Test
    void classBackedParentWithListAccessor_isRejectedByName() {
        var schema = TestSchemaHelper.buildSchema(classBackedParent("makeManyLanguages", """
                rank: Int @service(service: {className: "%s", method: "getRankMappedByRecord"})
            """.formatted(SVC)));

        assertThat(reasonOf(schema, "Parent", "rank"))
            .contains("returns many", "language")
            .contains("one key per parent");
    }

    /**
     * A key owner with no primary key is newly reachable: decoupling the key owner from the parent is
     * exactly what makes it so, and the arm has to reject rather than crash the classifier out of
     * {@code MethodRef.Param.Sourced}'s non-empty-columns invariant. {@code film_list} is the tree's
     * PK-less table, and the diagnostic names the element type the author wrote.
     */
    @Test
    void keyOwnerTableWithoutPrimaryKey_isRejectedNamingTheElementType() {
        var schema = TestSchemaHelper.buildSchema(classBackedParent("makeFilmListKeyed", """
                rank: Int @service(service: {className: "%s", method: "getFilmListRankByRecord"})
            """.formatted(SVC)));

        assertThat(reasonOf(schema, "Parent", "rank"))
            .contains("FilmListRecord", "film_list", "no primary key");
    }

    /**
     * An anonymous key wrap names no table, so a class-backed parent has nothing to read the columns
     * through. The diagnostic must not promise the {@code @sourceRow} route, which is a follow-up.
     */
    @Test
    void anonymousKeyWrapOnClassBackedParent_isRejectedWithoutPromisingARoute() {
        var schema = TestSchemaHelper.buildSchema(classBackedParent("makeLanguageKeyed", """
                rank: Int @service(service: {className: "%s", method: "getRankMapped"})
            """.formatted(SVC)));

        assertThat(reasonOf(schema, "Parent", "rank"))
            .contains("Row/Record batch parameter")
            .contains("Declare the batch key as a jOOQ record class")
            .contains("only scalar key columns has no route");
    }

    // ===== The Sources-less child, on both parent kinds =====

    /**
     * A child {@code @service} with no {@code Sources} parameter has no batch key, and the tree had
     * already decided that is an error rather than a shape: the table-bound leaf's validator rejected
     * it and the launcher threw on the record-shaped one. The rejection now lands at classify time on
     * both parent kinds, which is what makes both key components non-null by construction.
     */
    @Test
    void sourcesLessChildOnTableParent_isRejectedNamingTheMissingParameter() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") {
                name: String @field(name: "name")
                rank(filter: String): Int @service(service: {className: "%s", method: "getConstantRank"})
            }
            type Query { language: Language }
            """.formatted(SVC));

        assertThat(reasonOf(schema, "Language", "rank"))
            .contains("declares no Sources parameter")
            .contains("resolves through a DataLoader")
            .contains("per-parent service call is not emitted");
    }

    /**
     * The record-parent twin, which is also the pin for the return-type-regime flip's residue: this
     * coordinate used to be under the root's strict return-type comparison, and the flip to the
     * batched-child regime would otherwise have left it with no return-type validation at all.
     */
    @Test
    void sourcesLessChildOnClassBackedParent_isRejectedNamingTheMissingParameter() {
        var schema = TestSchemaHelper.buildSchema(classBackedParent("makeLanguageKeyed", """
                rank(filter: String): Int @service(service: {className: "%s", method: "getConstantRank"})
            """.formatted(SVC)));

        assertThat(reasonOf(schema, "Parent", "rank"))
            .contains("declares no Sources parameter");
    }

    /**
     * The over-fire guard on the other side: a table-bound return with no {@code Sources} parameter is
     * rejected too. It used to classify on a class-backed parent and fail validation on a
     * {@code @table} one, so "no key needed here" was never a live reading; both now say the same
     * thing at the same phase.
     */
    @Test
    void sourcesLessTableBoundChildOnClassBackedParent_isRejectedToo() {
        var schema = TestSchemaHelper.buildSchema(classBackedParent("makeLanguageKeyed", """
                language(filter: String): Language @service(service: {className: "%s", method: "getLanguageByFilter"})
            """.formatted(SVC)));

        assertThat(reasonOf(schema, "Parent", "language"))
            .contains("declares no Sources parameter");
    }
}
