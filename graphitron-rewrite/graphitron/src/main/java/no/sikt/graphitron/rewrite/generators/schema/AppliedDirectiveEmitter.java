package no.sikt.graphitron.rewrite.generators.schema;

import graphql.GraphQLContext;
import graphql.execution.ValuesResolver;
import graphql.language.AstPrinter;
import graphql.language.Value;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.rewrite.ScalarTypeResolver;
import no.sikt.graphitron.rewrite.generators.util.SchemaDirectiveRegistry;
import no.sikt.graphitron.rewrite.model.ScalarResolution;

import javax.lang.model.element.Modifier;
import java.util.Locale;

/**
 * Emits a {@code private static GraphQLAppliedDirective <name>()} factory method per survivor
 * applied directive on a {@link GraphQLDirectiveContainer} or schema. Per-type emitters
 * ({@link ObjectTypeGenerator}, {@link InputTypeGenerator}, {@link EnumTypeGenerator}) and
 * {@link GraphitronSchemaClassGenerator} register the methods on their enclosing class via
 * {@link HelperMethodSink} and then call them by name from the builder-statement body.
 *
 * <p>Filtering uses {@link SchemaDirectiveRegistry#isSurvivor(String)}: generator-only
 * directives (Graphitron's own build-time directives) are skipped; everything else survives.
 */
public final class AppliedDirectiveEmitter {

    private static final ClassName APPLIED_DIRECTIVE =
        ClassName.get("graphql.schema", "GraphQLAppliedDirective");
    private static final ClassName APPLIED_DIRECTIVE_ARG =
        ClassName.get("graphql.schema", "GraphQLAppliedDirectiveArgument");
    private static final ClassName SCALARS =
        ClassName.get("graphql", "Scalars");
    private static final ClassName CN_NON_NULL =
        ClassName.get("graphql.schema", "GraphQLNonNull");
    private static final ClassName CN_LIST =
        ClassName.get("graphql.schema", "GraphQLList");
    private static final ClassName CN_TYPE_REF =
        ClassName.get("graphql.schema", "GraphQLTypeReference");
    private static final ClassName PARSER =
        ClassName.get("graphql.parser", "Parser");

    private AppliedDirectiveEmitter() {}

    /**
     * Allocates one helper method per survivor applied directive on {@code container} via
     * {@code sink} and writes {@code <builderVar>.withAppliedDirective(<helperName>());}
     * statements onto {@code body}. Callers pass the local builder variable that the
     * surrounding statement-shaped body holds (e.g. {@code "b"} inside a {@code type()}
     * method).
     */
    static void emitApplications(CodeBlock.Builder body, String builderVar,
                                  GraphQLDirectiveContainer container, HelperMethodSink sink) {
        for (var applied : container.getAppliedDirectives()) {
            if (!SchemaDirectiveRegistry.isSurvivor(applied.getName())) continue;
            String helper = sink.addAppliedDirective(applied);
            body.addStatement("$L.withAppliedDirective($L())", builderVar, helper);
        }
    }

    /**
     * Allocates one helper method per survivor application sitting on the schema definition
     * itself ({@link GraphQLSchema#getSchemaAppliedDirectives()}) and writes a single
     * {@code <builderVar>.withSchemaAppliedDirectives(java.util.List.of(<helperName>(), ...))}
     * statement onto {@code body}. Used to propagate the consumer's
     * {@code extend schema @link(url: ..., import: [...])} into the generated runtime build.
     *
     * <p>Single-statement form is intentional: {@link GraphQLSchema.Builder#withSchemaAppliedDirectives}
     * takes one {@code List<GraphQLAppliedDirective>}, not one application per builder call.
     * Chain depth on this statement is bounded; the per-application chains live inside the
     * helper bodies the sink allocates.
     */
    static void emitSchemaApplications(CodeBlock.Builder body, String builderVar,
                                        GraphQLSchema schema, HelperMethodSink sink) {
        var applications = schema.getSchemaAppliedDirectives().stream()
            .filter(a -> SchemaDirectiveRegistry.isSurvivor(a.getName()))
            .toList();
        if (applications.isEmpty()) return;

        var args = CodeBlock.builder();
        for (int i = 0; i < applications.size(); i++) {
            if (i > 0) args.add(", ");
            String helper = sink.addAppliedDirective(applications.get(i));
            args.add("$L()", helper);
        }
        body.addStatement("$L.withSchemaAppliedDirectives($T.of($L))",
            builderVar, ClassName.get("java.util", "List"), args.build());
    }

    /**
     * Builds a {@code private static GraphQLAppliedDirective <methodName>()} factory method
     * whose body is statement-flattened: a local builder variable per element, one statement
     * per builder call. Chain depth on every statement is bounded by construction.
     */
    static MethodSpec buildApplicationMethod(String methodName, GraphQLAppliedDirective applied) {
        var body = CodeBlock.builder();
        body.addStatement("$T.Builder b = $T.newDirective()", APPLIED_DIRECTIVE, APPLIED_DIRECTIVE);
        body.addStatement("b.name($S)", applied.getName());

        int argIdx = 0;
        for (var arg : applied.getArguments()) {
            // applied.getArguments() returns one slot per declared argument on the directive,
            // including ones the SDL application did not supply (e.g. @key without resolvable).
            // Omitted args carry InputValueWithState.NOT_SET; valueToLiteral asserts on that
            // state. Skipping the slot lets consumer-side schema build resolve to the
            // directive's declared default.
            if (arg.getArgumentValue().isNotSet()) continue;
            String argVar = "a" + argIdx++;
            body.addStatement("$T.Builder $L = $T.newArgument()",
                APPLIED_DIRECTIVE_ARG, argVar, APPLIED_DIRECTIVE_ARG);
            body.addStatement("$L.name($S)", argVar, arg.getName());
            body.addStatement("$L.type($L)", argVar, emitInputType(arg.getType()));
            body.addStatement("$L.valueLiteral($L)", argVar, emitAstLiteralValue(arg));
            body.addStatement("b.argument($L.build())", argVar);
        }
        body.addStatement("return b.build()");
        return MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(APPLIED_DIRECTIVE)
            .addCode(body.build())
            .build();
    }

    /**
     * Re-renders the argument's value as an AST literal that the consumer-side schema builder
     * passes to {@code valueLiteral(...)}.
     *
     * <p>Federation-jvm reads directive argument values as AST literals (e.g. it casts the
     * {@code resolvable} argument value to {@link graphql.language.BooleanValue}). We must
     * therefore preserve the AST shape, not coerce to a Java primitive via
     * {@code valueProgrammatic}.
     *
     * <p>Delegates the AST shape decision to graphql-java's
     * {@link ValuesResolver#valueToLiteral}, which dispatches through each scalar's
     * {@code Coercing#valueToLiteral}. Custom scalars (federation-namespaced {@code FieldSet},
     * {@code Policy}, {@code Scope}, plus user-defined scalars), {@code Float},
     * {@code BigDecimal}, input objects, and internally-coerced enums all get the right shape
     * without per-type enumeration here. {@link AstPrinter#printAst} then renders the
     * {@link Value}, and the emitted code re-parses it at consumer class-init time via
     * {@link graphql.parser.Parser#parseValue}. Cost: one {@code parseValue} call per
     * applied-directive argument at class-init, negligible against schema build.
     */
    private static CodeBlock emitAstLiteralValue(GraphQLAppliedDirectiveArgument arg) {
        Value<?> ast = ValuesResolver.valueToLiteral(
            arg.getArgumentValue(),
            arg.getType(),
            GraphQLContext.getDefault(),
            Locale.getDefault());
        return CodeBlock.of("$T.parseValue($S)", PARSER, AstPrinter.printAst(ast));
    }

    /**
     * Emits a {@link CodeBlock} that reconstructs a {@link GraphQLInputType} at runtime.
     *
     * <p>Spec built-ins route through {@link ScalarTypeResolver} so the recognition table lives
     * in one place; the emitted reference is {@code Scalars.GraphQLString} etc. Federation-namespace
     * scalars ({@code federation__FieldSet}, {@code link__Import}, ...) emit
     * {@code GraphQLTypeReference.typeRef("<name>")} that resolves at {@code schemaBuilder.build()}
     * time against the synthesised scalar registered by
     * {@link GraphitronSchemaClassGenerator}; this preserves the SDL-declared name on every
     * directive-argument type slot.
     *
     * <p>Other named types (enums, input objects) use {@code GraphQLTypeReference.typeRef("<name>")}
     * since those types are present in the base schema.
     */
    static CodeBlock emitInputType(GraphQLInputType type) {
        if (type == null) return CodeBlock.of("$T.typeRef($S)", CN_TYPE_REF, "String");
        if (type instanceof GraphQLNonNull nn) {
            return CodeBlock.builder()
                .add("$T.nonNull(", CN_NON_NULL)
                .add(emitInputType((GraphQLInputType) nn.getWrappedType()))
                .add(")")
                .build();
        }
        if (type instanceof GraphQLList list) {
            return CodeBlock.builder()
                .add("$T.list(", CN_LIST)
                .add(emitInputType((GraphQLInputType) list.getWrappedType()))
                .add(")")
                .build();
        }
        if (type instanceof GraphQLScalarType scalar) {
            String name = scalar.getName();
            if (ScalarTypeResolver.isSpecBuiltIn(name)
                && ScalarTypeResolver.resolveBuiltIn(name) instanceof ScalarResolution.Resolved r) {
                return CodeBlock.of("$T.$L", r.scalarConstantOwner(), r.scalarConstantField());
            }
            if (ScalarTypeResolver.isFederationNamespaceScalar(name)) {
                return CodeBlock.of("$T.typeRef($S)", CN_TYPE_REF, name);
            }
            // Custom scalars not in the resolver's recognised tables: keep the existing
            // GraphQLString placeholder. Threading consumer-resolved scalars (via @scalarType /
            // convention) through here would require the BuildContext model and is out of Phase 3
            // scope — directive-arg type slots are metadata for the federation transform, not
            // runtime coercion, so the placeholder remains acceptable.
            return CodeBlock.of("$T.GraphQLString", SCALARS);
        }
        if (type instanceof GraphQLNamedType named) {
            return CodeBlock.of("$T.typeRef($S)", CN_TYPE_REF, named.getName());
        }
        return CodeBlock.of("$T.typeRef($S)", CN_TYPE_REF, type.toString());
    }
}
