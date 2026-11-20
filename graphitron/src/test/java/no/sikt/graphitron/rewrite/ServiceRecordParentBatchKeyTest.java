package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ServiceKeySource;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.jooq.ColumnRef;

/**
 * A child {@code @service} field batches against a key its parent can produce, and the {@code Sources}
 * element type names that key. On a {@code @table} parent the element type is a consequence of the
 * coordinate (the key is the parent's own primary key); on a class-backed parent it is the input to
 * it, and the coordinate asks the parent one question with a determinate answer: can you produce a
 * record of that table?
 *
 * <p>Two producers qualify, and they are the class-backed {@link ServiceKeySource} arms: the
 * parent's backing <em>is</em> the declared record, or it exposes exactly one zero-arg accessor
 * returning one. Everything else is refused in the store, each refusal pinned by a fact document:
 * defects/no-key-from-parent, key-row, many-keys-into-one, service-key-off-object,
 * service-sources-not-keys and unbatched-child-service.
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
            .extracting(no.sikt.graphitron.model.jooq.ColumnRef::sqlName)
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
}
