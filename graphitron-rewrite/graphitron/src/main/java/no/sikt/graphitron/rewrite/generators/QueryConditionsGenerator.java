package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.CONDITION;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.DSL;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.ENV;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.LIST;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.toCamelCase;

/**
 * Generates one {@code <RootType>Conditions.java} per root operation type with at least
 * one {@link QueryField.QueryTableField} — today that means {@code QueryConditions.java}
 * alongside the eventual {@code MutationConditions.java}.
 *
 * <p>The class holds one {@code <fieldName>Condition(Table, DataFetchingEnvironment)} static
 * method per {@link QueryField.QueryTableField} on that root type. Each method seeds a
 * {@code DSL.noCondition()}, extracts filter args from {@link graphql.schema.DataFetchingEnvironment},
 * composes every entity-scoped condition-method call (e.g. {@code FilmConditions.filmsCondition})
 * into the seed, and returns the composed {@code Condition}.
 *
 * <p><b>Layering.</b> {@code TypeConditionsGenerator} emits entity-scoped pure-function
 * classes ({@code FilmConditions}, {@code LanguageConditions}) that take typed argument
 * values and return {@code Condition}s — with no dependency on {@code graphql-java}.
 * {@code QueryConditionsGenerator} emits the env-aware shim layer on top: its generated
 * methods do the {@code env.getArgument(...)} extraction, enum coercion, and composition.
 * Fetcher bodies collapse to {@code QueryConditions.<name>Condition(<tableLocal>, env)}.
 *
 * <p>Emitted {@code <fieldName>Condition} helpers take the aliased {@code Table} as a
 * parameter — see "Helper-locality" in {@code docs/rewrite-design-principles.md}.
 */
public class QueryConditionsGenerator {

    private QueryConditionsGenerator() {}

    public static final String CLASS_NAME_SUFFIX = "Conditions";

    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage) {
        var out = new ArrayList<TypeSpec>();
        for (var type : schema.types().values()) {
            if (!(type instanceof GraphitronType.RootType rootType)) continue;
            var methods = new ArrayList<MethodSpec>();
            // One registry per emitted class: composite-key NodeId decode helpers registered
            // by any condition method on this root type are deduplicated and emitted as private
            // static helpers alongside the public condition methods.
            var registry = new CompositeDecodeHelperRegistry();
            for (var field : schema.fieldsOf(rootType.name())) {
                if (field instanceof QueryField.QueryTableField qtf) {
                    methods.add(buildConditionMethod(qtf.name(), qtf.returnType(), qtf.filters(), outputPackage, registry));
                } else if (field instanceof QueryField.QueryTableInterfaceField qtif) {
                    methods.add(buildConditionMethod(qtif.name(), qtif.returnType(), qtif.filters(), outputPackage, registry));
                }
            }
            if (methods.isEmpty()) continue;
            String simpleName = rootType.name() + CLASS_NAME_SUFFIX;
            var classBuilder = TypeSpec.classBuilder(simpleName).addModifiers(Modifier.PUBLIC);
            methods.forEach(classBuilder::addMethod);
            registry.emit().forEach(classBuilder::addMethod);
            out.add(classBuilder.build());
        }
        return out;
    }

    /** Method name as referenced from the fetcher body: {@code <fieldName>Condition}. */
    public static String conditionMethodName(String fieldName) {
        return fieldName + "Condition";
    }

    private static MethodSpec buildConditionMethod(
            String fieldName,
            ReturnTypeRef.TableBoundReturnType returnType,
            List<WhereFilter> filters,
            String outputPackage,
            CompositeDecodeHelperRegistry registry) {
        var tableRef = returnType.table();
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, returnType.returnTypeName(), outputPackage);
        var jooqTableClass = names.jooqTableClass();

        var builder = MethodSpec.methodBuilder(conditionMethodName(fieldName))
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(CONDITION)
            .addParameter(jooqTableClass, "table")
            .addParameter(ENV, "env");

        // Pre-lift any JooqConvert+list arg into a local before the (possibly reduced) body —
        // the extraction expression references it as `<name>Keys` when building call args.
        for (var filter : filters) {
            for (var param : filter.callParams()) {
                if (param.extraction() instanceof CallSiteExtraction.JooqConvert && param.list()) {
                    builder.addStatement("$T<$T> $L = env.getArgument($S)",
                        LIST, String.class, toCamelCase(param.name()) + "Keys", param.name());
                }
            }
        }

        // Reduce the noCondition()-and chain when there's nothing to compose. Zero filters land
        // as `return DSL.noCondition()`; one filter folds to a direct return; two or more keep
        // the seeded chain.
        // ContextArg ParamSources can reach this callsite via @condition(contextArguments: [...])
        // — the classifier lifts those to ParamSource.Context, and MethodRef.callParams() converts
        // them to CallSiteExtraction.ContextArg in the filter's call params. ArgCallEmitter then
        // emits graphitronContext(env).getContextArgument(env, name) into the QueryConditions
        // class, which today has no graphitronContext helper — generated source fails to compile
        // for users of the feature. R85 closes this gap by giving QueryConditionsGenerator its own
        // EmissionContext and emitting the helper when requested. Until then, the throwaway ctx
        // here records into a context nothing drains.
        var ctx = new TypeFetcherEmissionContext();
        if (filters.isEmpty()) {
            builder.addStatement("return $T.noCondition()", DSL);
        } else if (filters.size() == 1) {
            var only = filters.get(0);
            var callArgs = ArgCallEmitter.buildCallArgs(ctx, only.callParams(), only.className(), "table", registry);
            builder.addStatement("return $T.$L($L)",
                ClassName.bestGuess(only.className()), only.methodName(), callArgs);
        } else {
            builder.addStatement("$T condition = $T.noCondition()", CONDITION, DSL);
            for (var filter : filters) {
                var callArgs = ArgCallEmitter.buildCallArgs(ctx, filter.callParams(), filter.className(), "table", registry);
                builder.addStatement("condition = condition.and($T.$L($L))",
                    ClassName.bestGuess(filter.className()), filter.methodName(), callArgs);
            }
            builder.addStatement("return condition");
        }
        return builder.build();
    }
}
