package no.sikt.graphitron.rewrite.schema.federation;

import no.sikt.graphitron.rewrite.GraphitronSchemaValidator;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;
import no.sikt.graphitron.rewrite.model.KeyAlternative;
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
 * The {@link EntityResolutionBuilder} also synthesises a {@link KeyAlternative.NodeId}
 * alternative for every NodeType regardless of {@code @link} presence, so {@code @node}
 * types in test SDLs still get an entity entry.
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
        assertThat(alt).isInstanceOf(KeyAlternative.NodeId.class);
        assertThat(alt.requiredFields()).containsExactly("id");
        assertThat(alt.resolvable()).isTrue();
        assertThat(((KeyAlternative.NodeId) alt).expectedTypeId())
            .as("expectedTypeId carries the @node(typeId:) value (default: type name)")
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
        assertThat(resolution.alternatives()).hasSize(1);
        var alt = resolution.alternatives().get(0);
        assertThat(alt).isInstanceOf(KeyAlternative.NodeId.class);
        assertThat(((KeyAlternative.NodeId) alt).expectedTypeId())
            .as("explicit @node(typeId:) flows into expectedTypeId; dispatcher passes this to NodeIdEncoder.decodeValues")
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
        assertThat(alt).isInstanceOf(KeyAlternative.Direct.class);
        assertThat(alt.requiredFields()).containsExactly("languageId");
        assertThat(alt.columns()).hasSize(1);
        assertThat(alt.columns().get(0).sqlName()).isEqualToIgnoringCase("language_id");
        var binding = ((KeyAlternative.Direct) alt).bindings().get(0);
        assertThat(binding.repField()).isEqualTo("languageId");
        assertThat(binding.column().sqlName()).isEqualToIgnoringCase("language_id");
        assertThat(resolution.alternatives())
            .as("a non-NodeType @key type carries no NodeId alternative")
            .noneMatch(a -> a instanceof KeyAlternative.NodeId);
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
        // NodeId synthetic alt (prepended) + 2 Direct alts from the @key directives
        assertThat(resolution.alternatives()).hasSize(3);
        assertThat(resolution.alternatives().get(0))
            .as("NodeId alt is prepended for @node types")
            .isInstanceOf(KeyAlternative.NodeId.class);
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
        assertThat(alt)
            .as("explicit @key(fields: \"id\") on @node still gets a NodeId alternative")
            .isInstanceOf(KeyAlternative.NodeId.class);
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
        assertThat(alt).isInstanceOf(KeyAlternative.Direct.class);
        assertThat(alt.requiredFields()).containsExactly("rentalId", "inventoryId");
        assertThat(alt.columns()).hasSize(2);
        // Direct's requiredFields()/columns() unzip bindings in declaration order.
        var bindings = ((KeyAlternative.Direct) alt).bindings();
        assertThat(bindings).extracting(KeyAlternative.RepBinding::repField)
            .containsExactly("rentalId", "inventoryId");
        assertThat(bindings).extracting(b -> b.column().sqlName().toLowerCase())
            .containsExactly("rental_id", "inventory_id");
    }

    @Test
    void nodeIdAlternative_requiredFieldsIsAlwaysIdSingleton() {
        var schema = TestSchemaHelper.buildSchema("""
            type Query { customer: Customer }
            type Customer implements Node @table(name: "customer") @node {
                id: ID! @nodeId
            }
            """);
        var alt = schema.entityResolution("Customer").alternatives().get(0);
        assertThat(alt).isInstanceOf(KeyAlternative.NodeId.class);
        assertThat(alt.requiredFields())
            .as("NodeId.requiredFields() is the derived constant [id], not stored state")
            .containsExactly("id");
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
    void keyReferencingUnknownField_surfacesValidationDiagnostic() {
        // The federation @key check registers a diagnostic instead of demoting; the
        // type keeps its classified verdict and the validator surfaces the rejection.
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Query { language: Language }
            type Language @table(name: "language") @key(fields: "doesNotExist") {
                languageId: Int @field(name: "language_id")
            }
            """);
        assertThat(schema.type("Language")).isNotInstanceOf(UnclassifiedType.class);
        assertThat(new GraphitronSchemaValidator().validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("Language") && m.contains("doesNotExist"));
    }

    @Test
    void nestedSelectionInKeyFields_surfacesValidationDiagnostic() {
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Query { film: Film }
            type Film @table(name: "film") @key(fields: "language { id }") {
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Language @table(name: "language") {
                id: Int @field(name: "language_id")
            }
            """);
        assertThat(schema.type("Film")).isNotInstanceOf(UnclassifiedType.class);
        assertThat(new GraphitronSchemaValidator().validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("Film") && m.contains("nested selections") && m.contains("language"));
    }

    @Test
    void emptyKeyFields_surfacesValidationDiagnostic() {
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Query { language: Language }
            type Language @table(name: "language") @key(fields: "") {
                languageId: Int @field(name: "language_id")
            }
            """);
        assertThat(schema.type("Language")).isNotInstanceOf(UnclassifiedType.class);
        assertThat(new GraphitronSchemaValidator().validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.startsWith("Type 'Language':"));
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
    void plainObjectTypeWithKey_surfacesValidationDiagnostic() {
        // Federation @key on a non-@table type doesn't make sense — the dispatcher needs
        // a backing table to SELECT from. The Case-B message names the classification and
        // hints at the missing @table directive. Foo is a directiveless object the
        // type pass never classified (absent from the registry, not demoted); the federation check
        // registers the diagnostic the validator surfaces.
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Query { x: Foo }
            type Foo @key(fields: "fooId") {
                fooId: Int
            }
            """);
        assertThat(schema.type("Foo")).isNull();
        assertThat(new GraphitronSchemaValidator().validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("is classified as a plain object type")
                && m.contains("federation entities need a @table directive"));
    }

    @Test
    void keyOnTypeWithUnresolvableTable_preservesUnknownTableRejection() {
        // When a type is already UnclassifiedType from upstream (here: TypeBuilder's
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
        // A @node(keyColumns: [...]) referencing an unknown column makes TypeBuilder
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
        // Case B's record-backed arm — the "missing @table" hint is wrong-by-coincidence
        // because record-backed types intentionally have no @table. Name the kind explicitly so
        // the author sees that @key on a record-backed type is the misuse, not a forgotten @table.
        // FooRec keeps its record-backed verdict; the diagnostic carries the message.
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Query {
                x: FooRec @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            type FooRec @key(fields: "fooId") {
                fooId: Int
            }
            """);
        assertThat(schema.type("FooRec")).isNotInstanceOf(UnclassifiedType.class);
        assertThat(new GraphitronSchemaValidator().validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("is classified as a record-backed type")
                && !m.contains("has no @table directive"));
    }

    @Test
    void resolvableFalseKeyOnRecordType_isAcceptedAsReferenceOnlyStub() {
        // A @key(resolvable: false) declares a reference-only entity stub — this subgraph
        // does not own its resolution and emits no _entities handler, so it needs no backing
        // table. The record-backed type must survive classification (not demote to UnclassifiedType)
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
        // The relaxation only applies when ALL keys are resolvable: false. A type with at
        // least one resolvable @key still needs a backing table for the SELECT path, so the
        // "requires a table-bound type" rejection still fires.
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Query {
                x: FooRec @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            type FooRec @key(fields: "fooId", resolvable: false) @key(fields: "barId") {
                fooId: Int
                barId: Int
            }
            """);
        assertThat(schema.type("FooRec")).isNotInstanceOf(UnclassifiedType.class);
        assertThat(new GraphitronSchemaValidator().validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("is classified as a record-backed type")
                && m.contains("federation entities need a @table directive"));
    }
}
