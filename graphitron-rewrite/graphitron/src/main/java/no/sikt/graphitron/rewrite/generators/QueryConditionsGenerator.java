package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.AnnotationSpec;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.FkTargetConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            String simpleName = rootType.name() + CLASS_NAME_SUFFIX;
            var classBuilder = TypeSpec.classBuilder(simpleName).addModifiers(Modifier.PUBLIC);
            // One registry per emitted class: composite-key NodeId decode helpers registered by any
            // condition method on this root type are deduplicated and drained as private static
            // helpers alongside the public condition methods. collectInto co-locates construct and
            // drain so the lift can never be silently dropped.
            CompositeDecodeHelperRegistry.collectInto(classBuilder, registry -> {
                for (var field : schema.fieldsOf(rootType.name())) {
                    if (field instanceof QueryField.QueryTableField qtf) {
                        methods.add(buildConditionMethod(qtf.name(), qtf.returnType(), qtf.filters(), outputPackage, registry));
                    } else if (field instanceof QueryField.QueryTableInterfaceField qtif) {
                        methods.add(buildConditionMethod(qtif.name(), qtif.returnType(), qtif.filters(), outputPackage, registry));
                    }
                }
                methods.forEach(classBuilder::addMethod);
            });
            if (methods.isEmpty()) continue;
            out.add(classBuilder.build());
        }
        return out;
    }

    /** Method name as referenced from the fetcher body: {@code <fieldName>Condition}. */
    public static String conditionMethodName(String fieldName) {
        return fieldName + "Condition";
    }

    /**
     * Returns the per-method outer-arg lift map: {@code outerArgName → localName} for each
     * {@link CallSiteExtraction.NestedInputField#outerArgName()} referenced by ≥2 callParams
     * across all of the method's filters. The local name is
     * {@code <camelCaseOuterArg>Map}; the suffix prevents collision with JooqConvert lifts
     * ({@code <name>Keys}) and with method parameters ({@code table}, {@code env}). Returns an
     * insertion-ordered map so the emitted local declarations follow first-occurrence order.
     */
    static Map<String, String> computeLiftedOuters(List<WhereFilter> filters) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var filter : filters) {
            for (var param : filter.callParams()) {
                if (param.extraction() instanceof CallSiteExtraction.NestedInputField nif) {
                    counts.merge(nif.outerArgName(), 1, Integer::sum);
                }
            }
        }
        var lifted = new LinkedHashMap<String, String>();
        for (var e : counts.entrySet()) {
            if (e.getValue() >= 2) {
                lifted.put(e.getKey(), toCamelCase(e.getKey()) + "Map");
            }
        }
        return lifted;
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

        // A list-typed nested-input filter arg extracts as `(List<X>) map.get(key)`, where the
        // map value is statically Object, so the cast is inherently unchecked (unlike the
        // `<T> T env.getArgument(...)` sites, which carry their type through inference). Stamp the
        // suppression at this method, the narrowest enclosing member, only when such a param is
        // present.
        boolean needsUncheckedSuppression = filters.stream()
            .flatMap(f -> f.callParams().stream())
            .anyMatch(p -> p.list() && p.extraction() instanceof CallSiteExtraction.NestedInputField);
        if (needsUncheckedSuppression) {
            builder.addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                .addMember("value", "$S", "unchecked")
                .build());
        }

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

        // Lift any NestedInputField outer arg referenced ≥2 times across all filters into a single
        // typed Map<?, ?> local; per-arg expressions then start from that local instead of
        // re-binding `map1` per call. Lift naming is `<camelCaseOuterArg>Map` to avoid colliding
        // with method parameters (`table`, `env`) or with JooqConvert lifts (`<name>Keys`).
        var liftedOuters = computeLiftedOuters(filters);
        for (var entry : liftedOuters.entrySet()) {
            builder.addStatement("$T<?, ?> $L = env.getArgument($S) instanceof $T<?, ?> map ? map : null",
                Map.class, entry.getValue(), entry.getKey(), Map.class);
        }

        // Reduce the noCondition()-and chain when there's nothing to compose. Zero filters land
        // as `return DSL.noCondition()`; one filter folds to a direct return; two or more keep
        // the seeded chain.
        // ContextArg ParamSources can reach this callsite via @condition(contextArguments: [...])
        // — the classifier lifts those to ParamSource.Context, and MethodRef.callParams() converts
        // them to CallSiteExtraction.ContextArg in the filter's call params. ArgCallEmitter then
        // emits graphitronContext(env).getContextArgument(env, name) into the QueryConditions
        // class, which today has no graphitronContext helper — generated source fails to compile
        // for users of the feature. Closing this gap requires giving QueryConditionsGenerator its
        // own EmissionContext and emitting the helper when requested. Until then, the throwaway
        // ctx here records into a context nothing drains.
        var ctx = new TypeFetcherEmissionContext();

        // R330: pre-declare one aliased jOOQ table local per FK-target join hop. The correlated
        // EXISTS that each FkTargetConditionFilter emits references its target alias by name, but
        // filter terms compose as expressions and cannot introduce locals themselves — so they are
        // declared up front, like the JooqConvert/liftedOuters lifts above. The per-filter index
        // keeps both the Java local names and the SQL alias strings unique within the method.
        var fkTargetAliases = new IdentityHashMap<WhereFilter, List<String>>();
        int fkTargetIndex = 0;
        for (var filter : filters) {
            if (filter instanceof FkTargetConditionFilter fk) {
                var path = fk.joinPath();
                var hopAliases = new ArrayList<String>(path.size());
                for (int i = 0; i < path.size(); i++) {
                    var ht = (JoinStep.HasTargetTable) path.get(i);
                    String varName = "fkt" + fkTargetIndex + "_" + i;
                    builder.addStatement("$T $L = $T.$L.as($S)",
                        ht.targetTable().tableClass(), varName,
                        ht.targetTable().constantsClass(), ht.targetTable().javaFieldName(), varName);
                    hopAliases.add(varName);
                }
                fkTargetAliases.put(filter, hopAliases);
                fkTargetIndex++;
            }
        }

        if (filters.isEmpty()) {
            builder.addStatement("return $T.noCondition()", DSL);
        } else if (filters.size() == 1) {
            builder.addStatement("return $L",
                emitFilterTerm(ctx, filters.get(0), registry, liftedOuters, fkTargetAliases));
        } else {
            builder.addStatement("$T condition = $T.noCondition()", CONDITION, DSL);
            for (var filter : filters) {
                builder.addStatement("condition = condition.and($L)",
                    emitFilterTerm(ctx, filter, registry, liftedOuters, fkTargetAliases));
            }
            builder.addStatement("return condition");
        }
        return builder.build();
    }

    /**
     * Emits one composed WHERE term for a single filter. The root-table arm (plain
     * {@link WhereFilter}) hands the developer method the method's {@code table} local as today;
     * the FK-target arm ({@link FkTargetConditionFilter}, R330) emits a correlated {@code EXISTS}
     * that hands the method an alias for the FK-target table instead.
     */
    private static CodeBlock emitFilterTerm(TypeFetcherEmissionContext ctx, WhereFilter filter,
            CompositeDecodeHelperRegistry registry, Map<String, String> liftedOuters,
            Map<WhereFilter, List<String>> fkTargetAliases) {
        if (filter instanceof FkTargetConditionFilter fk) {
            return emitFkTargetExists(ctx, fk, registry, liftedOuters, fkTargetAliases.get(fk));
        }
        var callArgs = ArgCallEmitter.buildCallArgs(ctx, filter.callParams(), filter.className(), "table", registry, liftedOuters);
        return CodeBlock.of("$T.$L($L)",
            ClassName.bestGuess(filter.className()), filter.methodName(), callArgs);
    }

    /**
     * Emits the correlated {@code EXISTS} for an FK-target {@code @nodeId} override condition
     * (R330): {@code DSL.exists(DSL.selectOne().from(X).where(<correlation>.and(<method>(X, args))))}.
     * The {@code <correlation>} ties the first hop's target alias to the parent {@code table} local;
     * multi-hop paths walk the FK chain back from the terminal target alias (mirroring
     * {@link InlineColumnReferenceFieldEmitter}). The developer method is called against the
     * terminal (FK-target) alias rather than the root {@code table}.
     */
    private static CodeBlock emitFkTargetExists(TypeFetcherEmissionContext ctx,
            FkTargetConditionFilter fk, CompositeDecodeHelperRegistry registry,
            Map<String, String> liftedOuters, List<String> hopAliases) {
        var path = fk.joinPath();
        String terminalAlias = hopAliases.get(hopAliases.size() - 1);

        var sel = CodeBlock.builder();
        sel.add("$T.selectOne()", DSL);
        sel.add("\n        .from($L)", terminalAlias);
        for (int i = path.size() - 1; i >= 1; i--) {
            var bridging = path.get(i);
            String prevAlias = hopAliases.get(i - 1);
            if (bridging instanceof JoinStep.FkJoin fkHop) {
                sel.add("\n        .join($L).onKey($T.$L)",
                    prevAlias, fkHop.fk().keysClass(), fkHop.fk().constantName());
            } else {
                throw new IllegalStateException(
                    "FK-target @nodeId override join hop " + i + " on '" + fk.methodName()
                    + "' is not an FkJoin (" + bridging.getClass().getSimpleName()
                    + "); the validator must reject unresolved FK-target overrides before emission");
            }
        }
        if (!(path.get(0) instanceof JoinStep.FkJoin firstHop)) {
            throw new IllegalStateException(
                "FK-target @nodeId override first hop on '" + fk.methodName()
                + "' is not an FkJoin; the validator must reject unresolved FK-target overrides before emission");
        }
        var correlation = JoinPathEmitter.emitCorrelationWhere(firstHop, hopAliases.get(0), "table");
        var callArgs = ArgCallEmitter.buildCallArgs(ctx, fk.callParams(), fk.className(), terminalAlias, registry, liftedOuters);
        var devCall = CodeBlock.of("$T.$L($L)", ClassName.bestGuess(fk.className()), fk.methodName(), callArgs);
        sel.add("\n        .where($L.and($L))", correlation, devCall);

        return CodeBlock.of("$T.exists($L)", DSL, sel.build());
    }
}
