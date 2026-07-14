package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.AnnotationSpec;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
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
 * parameter — see "Helper-locality" in {@code docs/architecture/reference/emitter-conventions.adoc}.
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
            CompositeDecodeHelperRegistry.collectInto(classBuilder, outputPackage, registry -> {
                for (var field : schema.fieldsOf(rootType.name())) {
                    if (field instanceof QueryField.QueryTableField qtf) {
                        methods.add(buildConditionMethod(qtf.name(), qtf.returnType(), qtf.filters(), outputPackage, registry));
                        methods.addAll(buildFacetFragmentMethods(schema, rootType.name(), qtf, outputPackage, registry));
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

    /** R13 Phase 4: the facet base condition (full filter minus every facet's own predicate). */
    public static String facetBaseConditionMethodName(String fieldName) {
        return fieldName + "FacetBaseCondition";
    }

    /** R13 Phase 4: one facet's own predicate, {@code <fieldName>Facet_<inputFieldName>Condition}. */
    public static String facetConditionMethodName(String fieldName, String facetInputFieldName) {
        return fieldName + "Facet_" + facetInputFieldName + "Condition";
    }

    /**
     * R13 Phase 4: the filter-minus-self fragments for a faceted {@code @asConnection} carrier.
     * The generated {@code <field>Condition} folds every filter predicate into one, so the fetcher
     * cannot ask it to skip a facet; these additive siblings reconstruct the split:
     *
     * <ul>
     *   <li>{@code <field>FacetBaseCondition(table, env)} — every filter <em>except</em> the facet
     *       fields' own predicates;</li>
     *   <li>{@code <field>Facet_<g>Condition(table, env)} — facet {@code g}'s own predicate
     *       alone.</li>
     * </ul>
     *
     * <p>Suppression is by argument omission: the fragment calls the same entity-scoped
     * {@code <ReturnType>Conditions} method with a {@code null} literal in a suppressed
     * parameter's slot, which is exactly the absent-input to no-conjunct gate that method already
     * applies (facet bindings are guaranteed nullable — {@code rejectFacetMisuse} rejects non-null
     * facet fields). The value binding stays inside the typed conditions boundary; nothing here
     * rebuilds a predicate from raw {@code env} values.
     *
     * <p>The carrier's {@link GraphitronType.ConnectionType} is resolved through
     * {@link no.sikt.graphitron.rewrite.model.ConnectionNaming#defaultConnectionName};
     * {@code rejectFacetMisuse} rejects faceted carriers using the deprecated
     * {@code connectionName:} override, so the derived name always hits. Returns an empty list for
     * unfaceted carriers, keeping their emitted class byte-identical.
     */
    private static List<MethodSpec> buildFacetFragmentMethods(
            GraphitronSchema schema, String rootTypeName, QueryField.QueryTableField qtf,
            String outputPackage, CompositeDecodeHelperRegistry registry) {
        var entry = schema.types().get(no.sikt.graphitron.rewrite.model.ConnectionNaming
            .defaultConnectionName(rootTypeName, qtf.name()));
        if (!(entry instanceof GraphitronType.ConnectionType conn) || conn.facets().isEmpty()) {
            return List.of();
        }
        var out = new ArrayList<MethodSpec>();
        java.util.function.Predicate<CallParam> isAnyFacetParam =
            p -> conn.facets().stream().anyMatch(f -> isFacetParam(p, f));
        out.add(buildSuppressedConditionMethod(
            facetBaseConditionMethodName(qtf.name()), qtf.returnType(), qtf.filters(),
            outputPackage, registry, isAnyFacetParam, false));
        for (var facet : conn.facets()) {
            // The per-facet fragment retains only this facet's predicate: suppress every other
            // param of the generated filter call, facet or not.
            out.add(buildSuppressedConditionMethod(
                facetConditionMethodName(qtf.name(), facet.inputFieldName()), qtf.returnType(),
                qtf.filters(), outputPackage, registry,
                p -> !isFacetParam(p, facet), true));
        }
        return out;
    }

    /**
     * True when {@code param} is {@code facet}'s own binding: a
     * {@link CallSiteExtraction.NestedInputField} riding the facet's carrier argument
     * ({@code FacetSpec.filterArgName}) whose traversal path is exactly the facet's input field.
     * Matching on the full extraction identity, outer argument included, keeps two legitimate
     * non-facet filters out of the suppression set: a same-named top-level argument, and a
     * same-named field on a sibling input argument (the R13 review's finding 2). Both stay in the
     * base fragment and out of the facet's own fragment.
     */
    private static boolean isFacetParam(CallParam param,
            no.sikt.graphitron.rewrite.model.FacetSpec facet) {
        return param.extraction() instanceof CallSiteExtraction.NestedInputField nif
            && nif.outerArgName().equals(facet.filterArgName())
            && nif.path().equals(List.of(facet.inputFieldName()));
    }

    /**
     * A clone of {@link #buildConditionMethod} that (a) names the method explicitly, (b) replaces
     * every {@link CallParam} matching {@code suppressed} with a {@code null} literal (the typed
     * conditions method's own null gate then drops the conjunct), and (c) when
     * {@code onlyGeneratedFilters}, keeps only {@link no.sikt.graphitron.rewrite.model.GeneratedConditionFilter}
     * terms (a facet's own predicate lives there; field-level {@code @condition} and FK-target
     * terms belong to the base only). Pre-lift locals are computed over retained params only.
     */
    private static MethodSpec buildSuppressedConditionMethod(
            String methodName,
            ReturnTypeRef.TableBoundReturnType returnType,
            List<WhereFilter> allFilters,
            String outputPackage,
            CompositeDecodeHelperRegistry registry,
            java.util.function.Predicate<CallParam> suppressed,
            boolean onlyGeneratedFilters) {
        var filters = onlyGeneratedFilters
            ? allFilters.stream()
                .filter(f -> f instanceof no.sikt.graphitron.rewrite.model.GeneratedConditionFilter)
                .toList()
            : allFilters;
        var tableRef = returnType.table();
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, returnType.returnTypeName(), outputPackage);

        var builder = MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(CONDITION)
            .addParameter(names.jooqTableClass(), "table")
            .addParameter(ENV, "env");

        boolean needsUncheckedSuppression = filters.stream()
            .flatMap(f -> f.callParams().stream())
            .filter(p -> !suppressed.test(p))
            .anyMatch(CallParam::emitsUncheckedCast);
        if (needsUncheckedSuppression) {
            builder.addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                .addMember("value", "$S", "unchecked")
                .build());
        }

        for (var filter : filters) {
            for (var param : filter.callParams()) {
                if (suppressed.test(param)) continue;
                if (param.extraction() instanceof CallSiteExtraction.JooqConvert && param.list()) {
                    builder.addStatement("$T<$T> $L = env.getArgument($S)",
                        LIST, String.class, toCamelCase(param.name()) + "Keys", param.name());
                }
            }
        }

        var liftedOuters = computeLiftedOuters(filters.stream()
            .map(f -> (WhereFilter) new no.sikt.graphitron.rewrite.model.GeneratedConditionFilter(
                f.className(), f.methodName(), tableRef,
                f.callParams().stream().filter(p -> !suppressed.test(p)).toList(),
                List.of()))
            .toList());
        // Declare the lifted locals the retained extraction expressions reference (mirrors
        // buildConditionMethod; omitting this loop was the R13 review's finding 1 — the emitted
        // fragment referenced an undeclared `<outer>Map` local whenever two or more retained
        // params shared an outer arg, failing the consumer's javac).
        for (var entry : liftedOuters.entrySet()) {
            builder.addStatement("$T<?, ?> $L = env.getArgument($S) instanceof $T<?, ?> map ? map : null",
                Map.class, entry.getValue(), entry.getKey(), Map.class);
        }

        var ctx = new TypeFetcherEmissionContext();
        var declarations = CodeBlock.builder();
        var fkTargetAliases = FkTargetConditionEmitter.declareAliases(declarations, filters, "table", false);
        builder.addCode(declarations.build());

        if (filters.isEmpty()) {
            builder.addStatement("return $T.noCondition()", DSL);
        } else if (filters.size() == 1) {
            builder.addStatement("return $L",
                emitPossiblySuppressedTerm(ctx, filters.get(0), registry, liftedOuters, fkTargetAliases, suppressed));
        } else {
            builder.addStatement("$T condition = $T.noCondition()", CONDITION, DSL);
            for (var filter : filters) {
                builder.addStatement("condition = condition.and($L)",
                    emitPossiblySuppressedTerm(ctx, filter, registry, liftedOuters, fkTargetAliases, suppressed));
            }
            builder.addStatement("return condition");
        }
        return builder.build();
    }

    /**
     * Emits one WHERE term with per-param suppression. A term with no suppressed params routes
     * through {@link FkTargetConditionEmitter#emitTerm} unchanged; a
     * {@code GeneratedConditionFilter} carrying suppressed params gets its call rebuilt with
     * {@code null} literals in the suppressed slots. The generated condition methods are never
     * overloaded, so a bare {@code null} argument is unambiguous.
     */
    private static CodeBlock emitPossiblySuppressedTerm(TypeFetcherEmissionContext ctx,
            WhereFilter filter, CompositeDecodeHelperRegistry registry,
            Map<String, String> liftedOuters, Map<WhereFilter, List<String>> fkTargetAliases,
            java.util.function.Predicate<CallParam> suppressed) {
        boolean anySuppressed = filter instanceof no.sikt.graphitron.rewrite.model.GeneratedConditionFilter
            && filter.callParams().stream().anyMatch(suppressed);
        if (!anySuppressed) {
            return FkTargetConditionEmitter.emitTerm(ctx, filter, "table", registry, liftedOuters,
                fkTargetAliases, new ArgumentValueSource.Env());
        }
        var args = CodeBlock.builder();
        args.add("$L", "table");
        for (var param : filter.callParams()) {
            if (suppressed.test(param)) {
                args.add(", null");
            } else {
                args.add(", $L", ArgCallEmitter.buildArgExtraction(ctx, param, filter.className(),
                    "table", registry, liftedOuters, new ArgumentValueSource.Env()));
            }
        }
        return CodeBlock.of("$T.$L($L)",
            ClassName.bestGuess(filter.className()), filter.methodName(), args.build());
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

        // Stamp @SuppressWarnings("unchecked") at this method, the narrowest enclosing member, when
        // any call param's extraction emits an unchecked cast (CallParam.emitsUncheckedCast owns that
        // fact: today a list-typed nested-input filter extracting as `(List<X>) map.get(key)`). The
        // multitable MultiTablePolymorphicEmitter folds over the same model predicate, so the two
        // hosts cannot drift.
        boolean needsUncheckedSuppression = filters.stream()
            .flatMap(f -> f.callParams().stream())
            .anyMatch(CallParam::emitsUncheckedCast);
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

        // R330: pre-declare one aliased jOOQ table local per FK-target join hop, shared with the
        // inline fetcher emitters via FkTargetConditionEmitter. The correlated EXISTS each
        // FkTargetConditionFilter emits references its target alias by name, but filter terms
        // compose as expressions and cannot introduce locals themselves, so they are declared up
        // front like the JooqConvert/liftedOuters lifts above. This is a top-level, non-recursive
        // method, so static SQL aliases suffice.
        var declarations = CodeBlock.builder();
        var fkTargetAliases = FkTargetConditionEmitter.declareAliases(declarations, filters, "table", false);
        builder.addCode(declarations.build());

        if (filters.isEmpty()) {
            builder.addStatement("return $T.noCondition()", DSL);
        } else if (filters.size() == 1) {
            builder.addStatement("return $L",
                FkTargetConditionEmitter.emitTerm(ctx, filters.get(0), "table", registry, liftedOuters, fkTargetAliases, new ArgumentValueSource.Env()));
        } else {
            builder.addStatement("$T condition = $T.noCondition()", CONDITION, DSL);
            for (var filter : filters) {
                builder.addStatement("condition = condition.and($L)",
                    FkTargetConditionEmitter.emitTerm(ctx, filter, "table", registry, liftedOuters, fkTargetAliases, new ArgumentValueSource.Env()));
            }
            builder.addStatement("return condition");
        }
        return builder.build();
    }
}
