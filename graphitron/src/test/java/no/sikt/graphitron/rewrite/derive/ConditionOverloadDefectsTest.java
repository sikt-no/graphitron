package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.derive.ConditionOverloadDefects;
import no.sikt.graphitron.model.diagnostics.RejectionKind;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The build errors for {@code CONDITION_NO_OVERLOAD_FOR_PAIR} and {@code CONDITION_OVERLOAD_TIE}:
 * real SDL captured into a fact store beside the classpath corpus, and the violations
 * {@link ConditionOverloadDefects} mints from the stored entry defects. Which steps are defects is
 * the fact document {@code field-path-condition}; what stands here is the decode: the field each
 * defect is attributed to, the method and pair its prose names, and the position it points at.
 */
@PipelineTier
class ConditionOverloadDefectsTest {

    private static final String GRAPH = CapturedStore.GRAPH;
    private static final String FIXTURES =
        "no.sikt.graphitron.rewrite.test.conditions.ConditionMethodRouteFixtures";

    @TempDir
    Path tmp;

    private static final String TYPES = """
        type Address @table(name: "address") { addressId: Int @field(name: "address_id") }
        type Query { staffs: [Staff!]! customers: [Customer!]! }
        """;

    /** No declaration takes staff to address: an author error on the field, naming the pair. */
    @Test
    void aStepNoDeclarationTakesIsRejected() {
        var violations = detect(TYPES + """
            type Customer @table(name: "customer") { customerId: Int @field(name: "customer_id") }
            type Staff @table(name: "staff") {
              address: Address @reference(path: [{table: "address", condition: {
                className: "%s", method: "pairOnly"}}])
            }
            """.formatted(FIXTURES));

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
            assertThat(v.coordinate()).isEqualTo("Staff.address");
            assertThat(v.message())
                .contains("'" + FIXTURES + ".pairOnly'")
                .contains("no overload takes staff to address");
            assertThat(v.location().getLine())
                .as("the path element's line, which opens on the field's own fifth line")
                .isEqualTo(5);
        });
    }

    /** Two declarations tie for customer to address: an author error naming both. */
    @Test
    void aStepTwoDeclarationsTieOnIsRejected() {
        var violations = detect(TYPES + """
            type Staff @table(name: "staff") { staffId: Int @field(name: "staff_id") }
            type Customer @table(name: "customer") {
              address: Address @reference(path: [{condition: {className: "%s", method: "ambiguousPair"}}])
            }
            """.formatted(FIXTURES));

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.coordinate()).isEqualTo("Customer.address");
            assertThat(v.message())
                .contains("'" + FIXTURES + ".ambiguousPair'")
                .contains("overloads tie for customer to address: ambiguousPair(Customer, Table<?>),"
                    + " ambiguousPair(Table<?>, Address)");
        });
    }

    /** The exact declaration beside a wildcard one is javac's pick, and no defect. */
    @Test
    void anExactDeclarationBesideAWildcardOneIsAccepted() {
        assertThat(detect(TYPES + """
            type Staff @table(name: "staff") { staffId: Int @field(name: "staff_id") }
            type Customer @table(name: "customer") {
              address: Address @reference(path: [{condition: {className: "%s", method: "wildcardBesideConcrete"}}])
            }
            """.formatted(FIXTURES))).isEmpty();
    }

    /** Captures {@code sdl} beside the classpath corpus and runs the build-error consumer's detection. */
    private List<ValidationError> detect(String sdl) {
        try (var store = CapturedStore.ofCatalogWith(tmp, GRAPH, sdl, CatalogInput.of(testContext()),
                ClasspathCorpus.entries())) {
            return ConditionOverloadDefects.detect(store.dsl(), GRAPH).violations();
        }
    }
}
