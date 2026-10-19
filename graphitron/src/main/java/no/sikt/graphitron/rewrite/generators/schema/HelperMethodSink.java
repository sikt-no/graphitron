package no.sikt.graphitron.rewrite.generators.schema;

import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLScalarType;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.EnumValueSpec;
import no.sikt.graphitron.rewrite.model.GraphitronType;
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
 * <p>Motivation: javac's chained-call attribution recurses on {@code Apply}/{@code Select}
 * nodes, and incremental compile pays the per-frame cost cold. A schema's
 * {@code GraphitronSchema.build()} once emitted one fluent expression whose chain depth
 * scaled with schema element count, eventually overflowing the stack on regen-then-incremental
 * cycles. Each helper this sink allocates becomes a separately-compiled method body whose
 * own chain depth is O(1).
 *
 * <p>Naming convention:
 * <ul>
 *   <li>Applied directives are indexed by emission order ({@code appliedDirective_<n>}). The
 *       same SDL directive can be applied multiple times with different argument literals;
 *       indexing the <em>application</em> rather than the directive name keeps each helper's
 *       contents distinct.</li>
 *   <li>Directive definitions, scalars, field / input-field / enum-value
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

    String addScalar(GraphitronType.ScalarType scalar, GraphQLScalarType form) {
        String name = "scalar_" + sanitise(scalar.name());
        methods.add(buildScalarMethod(name, scalar, form, this));
        return name;
    }

    String addObjectFieldDef(String parentTypeName, GraphQLFieldDefinition field, GraphitronSchema schema) {
        String name = "fieldDef_" + sanitise(field.getName());
        methods.add(ObjectTypeGenerator.buildFieldDefinitionMethod(name, parentTypeName, field, schema, this));
        return name;
    }

    String addInputFieldDef(String parentTypeName, GraphQLInputObjectField field) {
        String name = "inputFieldDef_" + sanitise(field.getName());
        methods.add(InputTypeGenerator.buildFieldDefinitionMethod(name, parentTypeName, field, this));
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

    /**
     * The factory for one name-bound scalar: the head picks where the scalar's name and coercing
     * come from (a copy of the resolved constant, or a fresh scalar under the SDL name borrowing
     * the constant's coercing), the tail carries the SDL declaration, read off {@code form}: its
     * description (replacing a copied constant's own, so the runtime scalar describes itself as the
     * emitted SDL does), its {@code @specifiedBy} URL and its surviving applied directives.
     */
    private static MethodSpec buildScalarMethod(String methodName, GraphitronType.ScalarType scalar,
                                                GraphQLScalarType form, HelperMethodSink sink) {
        var body = CodeBlock.builder();
        switch (scalar.resolution()) {
            case ScalarResolution.Resolved r ->
                body.addStatement("$T.Builder b = $T.newScalar($T.$L)",
                    SCALAR_TYPE, SCALAR_TYPE, r.scalarConstantOwner(), r.scalarConstantField());
            case ScalarResolution.Synthesised s -> {
                body.addStatement("$T.Builder b = $T.newScalar()", SCALAR_TYPE, SCALAR_TYPE);
                body.addStatement("b.name($S)", s.sdlName());
                body.addStatement("b.coercing($T.$L.getCoercing())", s.coercingSourceOwner(), s.coercingSourceField());
            }
        }
        var description = form.getDescription();
        if (description != null && !description.isEmpty()) {
            body.addStatement("b.description($S)", description);
        } else if (scalar.resolution() instanceof ScalarResolution.Resolved) {
            body.addStatement("b.description(null)");
        }
        if (form.getSpecifiedByUrl() != null) {
            body.addStatement("b.specifiedByUrl($S)", form.getSpecifiedByUrl());
        }
        AppliedDirectiveEmitter.emitApplications(body, "b", form, sink);
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
