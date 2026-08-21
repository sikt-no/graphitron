package no.sikt.graphitron.sakila.example.app;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins Jakarta REST's root-resource matching for the case a consumer mounting its own GraphQL
 * endpoint under {@code /graphql/{something}} runs into: the templated class wins every request with
 * two or more segments, so the built-in resource's sub-paths stop resolving whatever
 * {@code GraphitronApplication.builtInEndpointEnabled()} says. That is a claim about the container's
 * algorithm across two root resource classes, so it is pinned in a running container rather than
 * asserted in prose.
 *
 * <p>The fixtures are {@code /probe} and {@code /probe/{p}}, which overlap each other and nothing
 * else. They are isomorphic to the real pair on the only input that matters: {@code /probe/} is
 * seven literal characters against {@code /probe}'s six, the same one-character margin
 * {@code /graphql/}'s nine holds over {@code /graphql}'s eight, and the sort key is comparative.
 *
 * <p>Staging the real {@code /graphql} overlap is not available, and its absence is not squeamishness:
 * a {@code @Path} class in test sources is registered from the build-time index for every
 * {@code @QuarkusTest} deployment in this module, and a test profile selects config, alternatives and
 * a build profile without changing the deployment's class set. A fixture at {@code /graphql/{...}}
 * would shadow {@code /graphql/schema} and {@code /graphql/assets/*} for every test in the module and
 * break {@link GraphqlResourceSmokeTest}'s page and asset cases. The toggle half of the question
 * lives in {@link MountedEndpointTest}, which asserts all five built-in routes answer 404 with the
 * toggle off.
 */
@QuarkusTest
@QuarkusTestResource(SmokeTestPostgresResource.class)
@ResourceLock(QuarkusTestLock.KEY)
@ExecutionTier
class OverlappingMountTest {

    /** Why the templated class sorts first, in the terms the real pair would use. */
    private static final String MARGIN =
        "the templated class sorts first on literal-character count "
            + "(/probe/ is 7 against /probe's 6, as /graphql/ is 9 against /graphql's 8)";

    @Test
    @DisplayName("A sub-path the literal class declares is taken by the templated class instead.")
    void templatedClassTakesTheLiteralClassesSubPath() {
        var body = given().when().get("/probe/schema").then().statusCode(200).extract().asString();

        // The real consequence: /graphql/schema reaches the consumer's resource with
        // callingEnvironment = "schema", not the library's SDL endpoint.
        assertThat(body)
            .as("/probe/schema must reach %s with p = \"schema\", not %s's own sub-path, because %s",
                ProbeTemplatedResource.class.getSimpleName(),
                ProbeLiteralResource.class.getSimpleName(), MARGIN)
            .isEqualTo(ProbeTemplatedResource.IDENTITY + ":schema");
    }

    @Test
    @DisplayName("A deeper sub-path goes to the templated class too, with the remainder as its own sub-path.")
    void templatedClassTakesTheDeeperSubPath() {
        var body = given().when().get("/probe/assets/probe.js").then().statusCode(200).extract().asString();

        // The real consequence: the built-in GraphiQL bundle stops resolving, so a consumer mounting
        // under /graphql/{...} has to serve assets/{name} from its own resource.
        assertThat(body)
            .as("/probe/assets/probe.js must reach %s with p = \"assets\" and the rest as its "
                    + "sub-path, not %s's asset method, because %s",
                ProbeTemplatedResource.class.getSimpleName(),
                ProbeLiteralResource.class.getSimpleName(), MARGIN)
            .isEqualTo(ProbeTemplatedResource.IDENTITY + ":assets/probe.js");
    }

    @Test
    @DisplayName("The bare literal path still reaches the literal class: the templated pattern needs a second segment.")
    void barePathStillReachesTheLiteralClass() {
        var body = given().when().get("/probe").then().statusCode(200).extract().asString();

        // The real consequence: bare /graphql still reaches the built-in resource, which is exactly
        // the ungated route builtInEndpointEnabled() exists to close.
        assertThat(body)
            .as("/probe must reach %s: %s cannot match a single segment",
                ProbeLiteralResource.class.getSimpleName(),
                ProbeTemplatedResource.class.getSimpleName())
            .isEqualTo(ProbeLiteralResource.IDENTITY + ":root");
    }
}
