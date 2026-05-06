package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.parsing.DirectiveDefinitions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Pins the directive vocabulary registry. The eight
 * {@code ExternalCodeReference} bindings are R93's primary client of
 * the registry; locking them down here means a hand-edit that loses
 * a binding (or adds one without lighting up its consumers) is a
 * loud test failure rather than a silent regression in completion /
 * diagnostic coverage.
 */
class DirectiveDefinitionsTest {

    @Test
    void argsByInputType_returnsAllEightExternalCodeReferenceBindings() {
        var bindings = DirectiveDefinitions.argsByInputType("ExternalCodeReference");

        assertThat(bindings).extracting(
            DirectiveDefinitions.InputTypeBinding::directive,
            DirectiveDefinitions.InputTypeBinding::argName,
            DirectiveDefinitions.InputTypeBinding::nestedPath
        ).containsExactlyInAnyOrder(
            tuple("externalField", "reference", false),
            tuple("enum", "enumReference", false),
            tuple("service", "service", false),
            tuple("tableMethod", "tableMethodReference", false),
            tuple("record", "record", false),
            tuple("batchKeyLifter", "lifter", false),
            tuple("condition", "condition", false),
            tuple("reference", "condition", true)
        );
    }

    @Test
    void argsByInputType_returnsEmptyForUnknownType() {
        assertThat(DirectiveDefinitions.argsByInputType("NoSuchType")).isEmpty();
    }

    @Test
    void byName_findsServiceDirective() {
        var def = DirectiveDefinitions.byName("service").orElseThrow();

        assertThat(def.name()).isEqualTo("service");
        assertThat(def.args()).extracting(DirectiveDefinitions.ArgDef::name)
            .containsExactly("service", "contextArguments");
    }

    @Test
    void byName_findsReferenceWithNestedConditionBinding() {
        var def = DirectiveDefinitions.byName("reference").orElseThrow();

        assertThat(def.args()).hasSize(1);
        var arg = def.args().get(0);
        assertThat(arg.name()).isEqualTo("condition");
        assertThat(arg.inputType()).isEqualTo("ExternalCodeReference");
        assertThat(arg.nestedPath()).isTrue();
    }

    @Test
    void byName_returnsEmptyForUnknownDirective() {
        assertThat(DirectiveDefinitions.byName("nope")).isEmpty();
    }

    @Test
    void all_isNonEmptyAndIncludesEveryEcrBindingDirective() {
        var allNames = DirectiveDefinitions.all().stream()
            .map(DirectiveDefinitions.DirectiveDef::name)
            .toList();

        assertThat(allNames).contains(
            "externalField", "enum", "service", "tableMethod",
            "record", "batchKeyLifter", "condition", "reference"
        );
    }
}
