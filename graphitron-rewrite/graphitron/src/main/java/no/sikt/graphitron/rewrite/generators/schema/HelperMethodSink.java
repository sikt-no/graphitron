package no.sikt.graphitron.rewrite.generators.schema;

import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.EnumValueSpec;
import no.sikt.graphitron.rewrite.model.ScalarResolution;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Collects {@code private static} factory methods for one emitted class so each non-trivial
 * builder value reaches the class body as a single named call instead of an inline fluent
 * sub-chain. The schema-class assembler and per-type emitters share a sink per class they
 * emit; on the way out they call {@link #contributeTo(TypeSpec.Builder)} once.
 *
 * <p>R254 motivation: javac's chained-call attribution recurses on {@code Apply}/{@code Select}
 * nodes, and incremental compile pays the per-frame cost cold. A schema's
 * {@code GraphitronSchema.build()} pre-R254 emitted one fluent expression whose chain depth
 * scaled with schema element count, eventually overflowing the stack on regen-then-incremental
 * cycles. Each helper this sink allocates becomes a separately-compiled method body whose
 * own chain depth is O(1).
 *
 * <p>Naming follows the spec in {@code roadmap/schema-class-bounded-emission.md}:
 * <ul>
 *   <li>Applied directives are indexed by emission order ({@code appliedDirective_<n>}). The
 *       same SDL directive can be applied multiple times with different argument literals;
 *       indexing the <em>application</em> rather than the directive name keeps each helper's
 *       contents distinct.</li>
 *   <li>Directive definitions, synthesised scalars, field / input-field / enum-value
 *       definitions are name-derived (each unique within its enclosing scope), with
 *       non-identifier characters in the SDL name replaced by {@code _}.</li>
 * </ul>
 */
final class HelperMethodSink {
    private static final Pattern NON_IDENT = Pattern.compile("[^A-Za-z0-9_]");

    private static final ClassName SCALAR_TYPE = ClassName.get("graphql.schema", "GraphQLScalarType");

    private int appliedDirectiveSeq = 0;
    private final List<MethodSpec> methods = new ArrayList<>();

    String addAppliedDirective(GraphQLAppliedDirective applied) {
        String name = "appliedDirective_" + appliedDirectiveSeq++;
        methods.add(AppliedDirectiveEmitter.buildApplicationMethod(name, applied));
        return name;
    }

    String addDirectiveDefinition(GraphQLDirective dir) {
        String name = "directiveDefinition_" + sanitise(dir.getName());
        methods.add(DirectiveDefinitionEmitter.buildDefinitionMethod(name, dir));
        return name;
    }

    String addSynthesisedScalar(ScalarResolution.Synthesised s) {
        String name = "scalar_" + sanitise(s.sdlName());
        methods.add(buildSynthesisedScalarMethod(name, s));
        return name;
    }

    String addObjectFieldDef(String parentTypeName, GraphQLFieldDefinition field, GraphitronSchema schema) {
        String name = "fieldDef_" + sanitise(field.getName());
        methods.add(ObjectTypeGenerator.buildFieldDefinitionMethod(name, parentTypeName, field, schema, this));
        return name;
    }

    String addInputFieldDef(GraphQLInputObjectField field) {
        String name = "inputFieldDef_" + sanitise(field.getName());
        methods.add(InputTypeGenerator.buildFieldDefinitionMethod(name, field, this));
        return name;
    }

    String addEnumValueDef(EnumValueSpec value) {
        String name = "enumValueDef_" + sanitise(value.sdlName());
        methods.add(EnumTypeGenerator.buildValueDefinitionMethod(name, value, this));
        return name;
    }

    void contributeTo(TypeSpec.Builder classBuilder) {
        for (var m : methods) {
            classBuilder.addMethod(m);
        }
    }

    private static MethodSpec buildSynthesisedScalarMethod(String methodName, ScalarResolution.Synthesised s) {
        var body = CodeBlock.builder();
        body.addStatement("$T.Builder b = $T.newScalar()", SCALAR_TYPE, SCALAR_TYPE);
        body.addStatement("b.name($S)", s.sdlName());
        body.addStatement("b.coercing($T.$L.getCoercing())", s.coercingSourceOwner(), s.coercingSourceField());
        body.addStatement("return b.build()");
        return MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(SCALAR_TYPE)
            .addCode(body.build())
            .build();
    }

    private static String sanitise(String sdlName) {
        return NON_IDENT.matcher(sdlName).replaceAll("_");
    }
}
