package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R405 pipeline tier: SDL → classified model → generated {@code TypeSpec} for a {@code @service} field
 * returning a single-table discriminated interface (interface + two {@code @discriminator} implementers,
 * one carrying a cross-table {@code @reference} field). Asserts the model classifies to the new variant
 * and the generated fetcher projects {@code __discriminator__}, a discriminator-gated cross-table
 * {@code LEFT JOIN}, and sources its WHERE from a by-PK row-value {@code IN} off the service records.
 */
@PipelineTier
class ServiceTableInterfaceReturnPipelineTest {

    private static final String SDL = """
        interface Content @table(name: "content") @discriminate(on: "CONTENT_TYPE") {
          contentId: Int! @field(name: "CONTENT_ID")
          title: String! @field(name: "TITLE")
        }
        type FilmContent implements Content @table(name: "content") @discriminator(value: "FILM") {
          contentId: Int! @field(name: "CONTENT_ID")
          title: String! @field(name: "TITLE")
          rating: String @reference(path: [{key: "content_film_id_fkey"}]) @field(name: "RATING")
        }
        type ShortContent implements Content @table(name: "content") @discriminator(value: "SHORT") {
          contentId: Int! @field(name: "CONTENT_ID")
          title: String! @field(name: "TITLE")
          description: String @field(name: "SHORT_DESCRIPTION")
        }
        type Query {
          contentSearch: [Content!]!
            @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getContents"})
        }
        type Mutation {
          contentSearchMutation: [Content!]!
            @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getContents"})
        }
        """;

    @Test
    void classifiesToServiceTableInterfaceVariant() {
        var schema = TestSchemaHelper.buildSchema(SDL);
        assertThat(schema.field("Query", "contentSearch"))
            .isInstanceOf(QueryField.QueryServiceTableInterfaceField.class);
        assertThat(schema.field("Mutation", "contentSearchMutation"))
            .isInstanceOf(MutationField.MutationServiceTableInterfaceField.class);
    }

    @Test
    void queryFetcher_projectsDiscriminatorCrossTableJoinAndByPkWhere() {
        var body = queryFetcherBody("contentSearch");
        // The discriminated TypeResolver routes off the synthetic __discriminator__ alias.
        assertThat(body).contains("__discriminator__");
        // The discriminator IN filter restricts to known participant values.
        assertThat(body).contains("\"FILM\"").contains("\"SHORT\"");
        // FilmContent.rating rides a discriminator-gated cross-table LEFT JOIN.
        assertThat(body).contains("leftJoin");
        // The WHERE source is a by-PK row-value IN off the service-returned records, not an FK correlation.
        assertThat(body).contains("pkRows").contains(".in(");
        // The service developer method is invoked.
        assertThat(body).contains("getContents");
    }

    @Test
    void mutationFetcher_projectsDiscriminatorAndByPkWhere() {
        var body = mutationFetcherBody("contentSearchMutation");
        assertThat(body).contains("__discriminator__");
        assertThat(body).contains("pkRows").contains(".in(");
        assertThat(body).contains("getContents");
    }

    private String queryFetcherBody(String field) {
        return method(findSpec("QueryFetchers"), field).code().toString();
    }

    private String mutationFetcherBody(String field) {
        return method(findSpec("MutationFetchers"), field).code().toString();
    }

    private TypeSpec findSpec(String className) {
        return TypeFetcherGenerator.generate(TestSchemaHelper.buildSchema(SDL), DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Class not found: " + className));
    }

    private MethodSpec method(TypeSpec spec, String name) {
        return spec.methodSpecs().stream()
            .filter(m -> m.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Method not found: " + name));
    }
}
