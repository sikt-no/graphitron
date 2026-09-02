package no.sikt.graphitron.lsp;

import graphql.language.InputObjectTypeDefinition;
import graphql.schema.idl.SchemaParser;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import no.sikt.graphitron.model.lint.DeprecationRecognizer;
import no.sikt.graphitron.model.schema.SchemaLoader;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Every coordinate graphitron's shipped {@code directives.graphqls} marks deprecated, in either of
 * the two marker conventions the generator's {@link DeprecationRecognizer} unifies.
 *
 * <p>Test support, and deliberately so. This used to be a method on the vocabulary, which meant the
 * language server carried a deprecation reader on the request path for a question no request ever
 * asked: nothing an editor shows is keyed on it. Its one consumer is
 * {@link SdlActionDriftTest}, whose subject is the shipped file rather than any session, so the
 * reading belongs here alongside the assertion that uses it.
 *
 * <p>Parses the shipped resource with graphql-java rather than reading a captured graph, because
 * deprecation is not a fact capture writes: a {@code @deprecated} application on a directive
 * definition's formal argument has no relation to land in. Reading the file directly is honest about
 * that, where a store-shaped reader would quietly answer for two of the three markers and miss the
 * third. The language server itself neither parses this file nor asks this question.
 */
final class SdlDeprecations {

    private SdlDeprecations() {}

    /** The shipped deprecation markers, as coordinates. */
    static Set<SchemaCoordinate> shipped() {
        var registry = new SchemaParser().parse(SchemaLoader.directivesSdl());
        var recognizer = new DeprecationRecognizer(registry);
        var out = new LinkedHashSet<SchemaCoordinate>();
        for (var directive : registry.getDirectiveDefinitions().values()) {
            var directiveCoord = new SchemaCoordinate.Directive(directive.getName());
            if (recognizer.directiveDeprecation(directive.getName()).isPresent()) {
                out.add(directiveCoord);
            }
            for (var arg : directive.getInputValueDefinitions()) {
                if (recognizer.directiveArgDeprecation(directive.getName(), arg.getName()).isPresent()) {
                    out.add(new SchemaCoordinate.DirectiveArg(directive.getName(), arg.getName()));
                }
            }
        }
        for (var inputType : registry.getTypes(InputObjectTypeDefinition.class)) {
            for (var field : inputType.getInputValueDefinitions()) {
                if (recognizer.inputFieldDeprecation(inputType.getName(), field.getName()).isPresent()) {
                    out.add(new SchemaCoordinate.InputField(inputType.getName(), field.getName()));
                }
            }
        }
        return out;
    }
}
