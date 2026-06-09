package no.sikt.graphitron.rewrite.schema.federation;

import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;
import no.sikt.graphitron.rewrite.model.KeyAlternative.KeyShape;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

/**
 * Classify-time behavior of {@link EntityResolutionBuilder}: how {@code @key} directives
 * (and the implicit {@code @node}-to-{@code @key} synthesis) translate into
 * {@link no.sikt.graphitron.rewrite.model.EntityResolution} entries on the
 * {@code entitiesByType} sidecar.
 *
 * <p>Tests use real tables from the {@code TestConfiguration} jOOQ catalog (e.g.
 * {@code customer}, {@code film}). They declare {@code @key} inline since
 * {@code TestSchemaHelper} does not run {@link KeyNodeSynthesiser} (that lives in
 * {@code GraphQLRewriteGenerator.loadAttributedRegistry}, which the helper bypasses).
 * The {@link EntityResolutionBuilder} also synthesises a NODE_ID alternative for every
 * NodeType regardless of {@code @link} presence, so {@code @node} types in test SDLs
 * still get an entity entry.
 */
@UnitTier
class EntityResolutionBuilderTest {

    private static final String FEDERATION_DIRECTIVES = """
        directive @key(fields: String!, resolvable: Boolean = true) repeatable on OBJECT | INTERFACE
        """;

    @Test
    void nodeType_alwaysGetsNodeIdAlternative_evenWithoutFederation() {
        var schema = TestSchemaHelper.buildSchema("""
            type Query { customer: Customer }
            type Customer implements Node @table(name: "customer") @node {
                id: ID! @nodeId
            }
            """);
        var resolution = schema.entityResolution("Customer");
        assertThat(resolution).as("@node type always gets an entity entry").isNotNull();
        assertThat(resolution.alternatives()).hasSize(1);
        var alt = resolution.alternatives().get(0);
        assertThat(alt.shape()).isEqualTo(KeyShape.NODE_ID);
        assertThat(alt.requiredFields()).containsExactly("id");
        assertThat(alt.resolvable()).isTrue();
        assertThat(resolution.nodeTypeId())
            .as("nodeTypeId carries the @node(typeId:) value (default: type name)")
            .isEqualTo("Customer");
    }

    @Test
    void nodeType_withExplicitTypeId_carriesItInResolution() {
        var schema = TestSchemaHelper.buildSchema("""
            type Query { customer: Customer }
            type Customer implements Node @table(name: "customer") @node(typeId: "C") {
                id: ID! @nodeId
            }
            """);
        var resolution = schema.entityResolution("Customer");
        assertThat(resolution).isNotNull();
        assertThat(resolution.nodeTypeId())
            .as("explicit @node(typeId:) flows into nodeTypeId; dispatcher passes this to NodeIdEncoder.decodeValues")
            .isEqualTo("C");
    }

    @Test
    void tableType_withExplicitKey_getsDirectAlternative() {
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Query { language: Language }
            type Language @table(name: "language") @key(fields: "languageId") {
                languageId: Int @field(name: "language_id")
                name: String
            }
            """);
        var resolution = schema.entityResolution("Language");
        assertThat(resolution).isNotNull();
        assertThat(resolution.alternatives()).hasSize(1);
        var alt = resolution.alternatives().get(0);
        assertThat(alt.shape()).isEqualTo(KeyShape.DIRECT);
        assertThat(alt.requiredFields()).containsExactly("languageId");
        assertThat(alt.columns()).hasSize(1);
        assertThat(alt.columns().get(0).sqlName()).isEqualToIgnoringCase("language_id");
        assertThat(resolution.nodeTypeId())
            .as("non-NodeType has no nodeTypeId")
            .isNull();
    }

    @Test
    void multipleKeyDirectives_produceMultipleAlternatives() {
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Query { film: Film }
            type Film implements Node @table(name: "film") @node @key(fields: "filmId") @key(fields: "title") {
                id: ID! @nodeId
                filmId: Int @field(name: "film_id")
                title: String
            }
            """);
        var resolution = schema.entityResolution("Film");
        assertThat(resolution).isNotNull();
        // NODE_ID synthetic alt (prepended) + 2 DIRECT alts from the @key directives
        assertThat(resolution.alternatives()).hasSize(3);
        assertThat(resolution.alternatives().get(0).shape())
            .as("NODE_ID alt is prepended for @node types")
            .isEqualTo(KeyShape.NODE_ID);
        assertThat(resolution.alternatives())
            .extracting(a -> a.requiredFields().toString())
            .containsExactly("[id]", "[filmId]", "[title]");
    }

    @Test
    void nodeTypeWithExplicitIdKey_dedupes() {
        // When a @node type also carries an explicit @key(fields: "id"), the explicit one
        // produces the NODE_ID alternative (via buildAlternative's id-field check) and we
        // do NOT prepend a synthetic one. Plus carries through the consumer's resolvable
        // value if set.
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Query { customer: Customer }
            type Customer implements Node @table(name: "customer") @node @key(fields: "id", resolvable: false) {
                id: ID! @nodeId
            }
            """);
        var resolution = schema.entityResolution("Customer");
        assertThat(resolution).isNotNull();
        assertThat(resolution.alternatives())
            .as("@node + explicit @key(fields: \"id\") dedups to one alternative")
            .hasSize(1);
        var alt = resolution.alternatives().get(0);
        assertThat(alt.shape())
            .as("explicit @key(fields: \"id\") on @node still gets NODE_ID shape")
            .isEqualTo(KeyShape.NODE_ID);
        assertThat(alt.resolvable())
            .as("consumer's resolvable: false carries through, dispatcher will skip this alt")
            .isFalse();
    }

    @Test
    void compoundKey_producesAlternativeWithMultipleColumns() {
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Query { rental: Rental }
            type Rental @table(name: "rental") @key(fields: "rentalId inventoryId") {
                rentalId: Int @field(name: "rental_id")
                inventoryId: Int @field(name: "inventory_id")
            }
            """);
        var resolution = schema.entityResolution("Rental");
        assertThat(resolution).isNotNull();
        assertThat(resolution.alternatives()).hasSize(1);
        var alt = resolution.alternatives().get(0);
        assertThat(alt.shape()).isEqualTo(KeyShape.DIRECT);
        assertThat(alt.requiredFields()).containsExactly("rentalId", "inventoryId");
        assertThat(alt.columns()).hasSize(2);
    }

    @Test
    void keyNotResolvable_carriesResolvableFalseThrough() {
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Query { language: Language }
            type Language @table(name: "language") @key(fields: "languageId", resolvable: false) {
                languageId: Int @field(name: "language_id")
            }
            """);
        var resolution = schema.entityResolution("Language");
        assertThat(resolution).isNotNull();
        assertThat(resolution.alternatives()).hasSize(1);
        assertThat(resolution.alternatives().get(0).resolvable())
            .as("@key(resolvable: false) carries through; dispatcher will skip this alt at match time")
            .isFalse();
    }

    @Test
    void keyReferencingUnknownField_demotesToUnclassifiedType() {
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Query { language: Language }
            type Language @table(name: "language") @key(fields: "doesNotExist") {
                languageId: Int @field(name: "language_id")
            }
            """);
        assertThat(schema.type("Language")).isInstanceOf(UnclassifiedType.class);
        var unclassified = (UnclassifiedType) schema.type("Language");
        assertThat(unclassified.reason()).contains("doesNotExist");
    }

    @Test
    void nestedSelectionInKeyFields_demotesToUnclassifiedType() {
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Query { film: Film }
            type Film @table(name: "film") @key(fields: "language { id }") {
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Language @table(name: "language") {
                id: Int @field(name: "language_id")
            }
            """);
        assertThat(schema.type("Film")).isInstanceOf(UnclassifiedType.class);
        var unclassified = (UnclassifiedType) schema.type("Film");
        assertThat(unclassified.reason())
            .contains("nested selections")
            .contains("language");
    }

    @Test
    void emptyKeyFields_demotesToUnclassifiedType() {
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Query { language: Language }
            type Language @table(name: "language") @key(fields: "") {
                languageId: Int @field(name: "language_id")
            }
            """);
        assertThat(schema.type("Language")).isInstanceOf(UnclassifiedType.class);
    }

    @Test
    void typeWithoutKeyOrNode_hasNoEntityResolution() {
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Query { language: Language }
            type Language @table(name: "language") {
                languageId: Int @field(name: "language_id")
            }
            """);
        assertThat(schema.entityResolution("Language")).isNull();
    }

    @Test
    void plainObjectTypeWithKey_demotesToUnclassifiedType() {
        // Federation @key on a non-@table type doesn't make sense — the dispatcher needs
        // a backing table to SELECT from. The Case-B message names the classification and
        // hints at the missing @table directive.
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Query { x: Foo }
            type Foo @key(fields: "fooId") {
                fooId: Int
            }
            """);
        assertThat(schema.type("Foo")).isInstanceOf(UnclassifiedType.class);
        var unclassified = (UnclassifiedType) schema.type("Foo");
        assertThat(unclassified.reason())
            .contains("is classified as a plain object type")
            .contains("federation entities need a @table directive");
    }

    @Test
    void keyOnTypeWithUnresolvableTable_preservesUnknownTableRejection() {
        // R176: when a type is already UnclassifiedType from upstream (here: TypeBuilder's
        // unknownTableRejection), EntityResolutionBuilder must pass it through unchanged
        // rather than overwriting it with a misleading "no @table directive" message.
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Query { x: T }
            type T implements Node @key(fields: "id") @node @table(name: "no_such_table") {
                id: ID! @nodeId
            }
            """);
        assertThat(schema.type("T")).isInstanceOf(UnclassifiedType.class);
        var unclassified = (UnclassifiedType) schema.type("T");
        assertThat(unclassified.reason())
            .contains("could not be resolved in the jOOQ catalog")
            .doesNotContain("has no @table directive");
    }

    @Test
    void keyOnNodeTypeWithUnresolvableKeyColumn_preservesUnresolvedColumnRejection() {
        // R176: a @node(keyColumns: [...]) referencing an unknown column makes TypeBuilder
        // produce an UnclassifiedType carrying the key-column-error message. The downstream
        // EntityResolutionBuilder must not overwrite that with its own guess.
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Query { customer: Customer }
            type Customer implements Node @key(fields: "id") @node(keyColumns: ["definitely_not_a_column"]) @table(name: "customer") {
                id: ID! @nodeId
            }
            """);
        assertThat(schema.type("Customer")).isInstanceOf(UnclassifiedType.class);
        var unclassified = (UnclassifiedType) schema.type("Customer");
        assertThat(unclassified.reason())
            .contains("key column 'definitely_not_a_column' in @node could not be resolved")
            .doesNotContain("has no @table directive");
    }

    @Test
    void keyOnRecordType_namesRecordKindInMessage() {
        // R176: Case B's @record arm — the "missing @table" hint is wrong-by-coincidence
        // because @record types intentionally have no @table. Name the kind explicitly so
        // the author sees that @key on @record is the misuse, not a forgotten @table.
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Query {
                x: FooRec @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            type FooRec @key(fields: "fooId") {
                fooId: Int
            }
            """);
        assertThat(schema.type("FooRec")).isInstanceOf(UnclassifiedType.class);
        var unclassified = (UnclassifiedType) schema.type("FooRec");
        assertThat(unclassified.reason())
            .contains("is classified as a @record type")
            .doesNotContain("has no @table directive");
    }

    @Test
    void resolvableFalseKeyOnRecordType_isAcceptedAsReferenceOnlyStub() {
        // R286: a @key(resolvable: false) declares a reference-only entity stub — this subgraph
        // does not own its resolution and emits no _entities handler, so it needs no backing
        // table. The @record type must survive classification (not demote to UnclassifiedType)
        // and produce no EntityResolution (the dispatcher never sees it).
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Query {
                x: FooRec @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            type FooRec @key(fields: "fooId", resolvable: false) {
                fooId: Int
            }
            """);
        assertThat(schema.type("FooRec"))
            .as("reference-only stub stays classified, not demoted")
            .isNotInstanceOf(UnclassifiedType.class);
        assertThat(schema.entityResolution("FooRec"))
            .as("no entity resolution is built for a reference-only stub")
            .isNull();
    }

    @Test
    void mixedResolvableAndNonResolvableKeysOnRecordType_stillRejects() {
        // R286: the relaxation only applies when ALL keys are resolvable: false. A type with at
        // least one resolvable @key still needs a backing table for the SELECT path, so the
        // R176 diagnostic stays.
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Query {
                x: FooRec @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            type FooRec @key(fields: "fooId", resolvable: false) @key(fields: "barId") {
                fooId: Int
                barId: Int
            }
            """);
        assertThat(schema.type("FooRec")).isInstanceOf(UnclassifiedType.class);
        var unclassified = (UnclassifiedType) schema.type("FooRec");
        assertThat(unclassified.reason())
            .contains("is classified as a @record type")
            .contains("federation entities need a @table directive");
    }

    @Test
    void resolvableFalseKeyViaExplicitRecordDirective_isAcceptedAsReferenceOnlyStub() {
        // R286 repro of the reported case: an explicit @record(record:{className:...}) directive
        // (not a service-bound record) carrying @key(resolvable: false), referenced only as a
        // federation stub (no operation returns it). Must survive classification, no demote.
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Query { ping: String }
            type URegOrganisasjon @key(fields: "sakId", resolvable: false)
                @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.R88AccessorFixtures$BareNameRecord"}) {
                sakId: String @field(name: "sakId")
            }
            """);
        // An orphan stub (no local field returns it) is left absent from the registry: not a
        // table-bound type, but crucially not demoted to an UnclassifiedType (which would surface
        // the author error). graphql-java still carries it in the assembled schema, so it is
        // emitted to the subgraph SDL for the supergraph composer.
        var stub = schema.type("URegOrganisasjon");
        assertThat(stub == null || !(stub instanceof UnclassifiedType))
            .as("orphan reference-only stub is left unclassified, not rejected (was: %s)", stub)
            .isTrue();
        assertThat(schema.entityResolution("URegOrganisasjon")).isNull();
    }
}
