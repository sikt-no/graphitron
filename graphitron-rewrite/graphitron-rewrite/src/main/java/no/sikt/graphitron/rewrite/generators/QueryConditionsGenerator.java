package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.QueryField;

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
 */
public class QueryConditionsGenerator {

    private QueryConditionsGenerator() {}

    public static final String CLASS_NAME_SUFFIX = "Conditions";

    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage, String jooqPackage) {
        var out = new ArrayList<TypeSpec>();
        for (var type : schema.types().values()) {
            if (!(type instanceof GraphitronType.RootType rootType)) continue;
            var methods = new ArrayList<MethodSpec>();
            for (var field : schema.fieldsOf(rootType.name())) {
                if (field instanceof QueryField.QueryTableField qtf) {
                    methods.add(buildConditionMethod(qtf, outputPackage, jooqPackage));
                }
            }
            if (methods.isEmpty()) continue;
            String simpleName = rootType.name() + CLASS_NAME_SUFFIX;
            var classBuilder = TypeSpec.classBuilder(simpleName).addModifiers(Modifier.PUBLIC);
            methods.forEach(classBuilder::addMethod);
            out.add(classBuilder.build());
        }
        return out;
    }

    /** Method name as referenced from the fetcher body: {@code <fieldName>Condition}. */
    public static String conditionMethodName(String fieldName) {
        return fieldName + "Condition";
    }

    private static MethodSpec buildConditionMethod(QueryField.QueryTableField qtf, String outputPackage, String jooqPackage) {
        var tableRef = qtf.returnType().table();
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, qtf.returnType().returnTypeName(), outputPackage, jooqPackage);
        var jooqTableClass = names.jooqTableClass();

        var builder = MethodSpec.methodBuilder(conditionMethodName(qtf.name()))
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(CONDITION)
            .addParameter(jooqTableClass, "table")
            .addParameter(ENV, "env");

        builder.addStatement("$T condition = $T.noCondition()", CONDITION, DSL);
        for (var filter : qtf.filters()) {
            // Pre-lift any JooqConvert+list arg into a local; the extraction expression
            // references it as `<name>Keys` when building the call args below.
            for (var param : filter.callParams()) {
                if (param.extraction() instanceof CallSiteExtraction.JooqConvert && param.list()) {
                    builder.addStatement("$T<$T> $L = env.getArgument($S)",
                        LIST, String.class, toCamelCase(param.name()) + "Keys", param.name());
                }
            }
            var callArgs = ArgCallEmitter.buildCallArgs(filter.callParams(), filter.className(), "table");
            builder.addStatement("condition = condition.and($T.$L($L))",
                ClassName.bestGuess(filter.className()), filter.methodName(), callArgs);
        }
        builder.addStatement("return condition");
        return builder.build();
    }
}
