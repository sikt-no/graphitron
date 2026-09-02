package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.SourceKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.model.diagnostics.ReflectionError;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.diagnostics.ServiceMethodCallError;

/**
 * Unit coverage for the {@code @service} decode and bind phases
 * ({@link ServiceCatalog#decodeServiceMethod}, {@link ServiceCatalog#reduceClaims},
 * {@link ServiceCatalog#bindServiceMethod}) plus {@link ServiceCatalog#reflectTableMethod}'s
 * {@code @condition} binding. Coordinate precedence runs between reduce and bind, in
 * {@link ServiceDirectiveResolver}, and is pinned at the pipeline tier instead.
 * Exercises the reflection path in isolation with synthetic
 * {@link TestServiceStub} / {@link TestConditionStub} methods; the classifier does not
 * read {@code BuildContext.schema} or {@code BuildContext.catalog}, so both may be
 * {@code null} here.
 */
@UnitTier
class ServiceCatalogTest {

    private static final String STUB_CLASS = "no.sikt.graphitron.rewrite.TestServiceStub";

    private static final ClassName FILM_RECORD = ClassName.get(
        "no.sikt.graphitron.rewrite.test.jooq.tables.records", "FilmRecord");
    private static final ClassName LANGUAGE_RECORD = ClassName.get(
        "no.sikt.graphitron.rewrite.test.jooq.tables.records", "LanguageRecord");
    private static final ClassName JOOQ_RESULT = ClassName.get("org.jooq", "Result");

    private static ServiceCatalog newCatalog() {
        return new ServiceCatalog(new BuildContext(null, null, stubRunContext()));
    }

    /**
     * Minimal {@link RunContext} for unit-tier classifier tests that don't need real schema
     * inputs or output paths. The 6-arg overload defaults {@code classpathRoots} to the empty
     * list and {@code codegenLoader} to the current thread's context classloader; in a JUnit
     * JVM that's the system classloader, the same loader bare {@code Class.forName(name)}
     * resolves through.
     */
    private static RunContext stubRunContext() {
        return new RunContext(
            java.util.List.of(),
            java.nio.file.Path.of("."), "ServiceCatalogTest",
            java.nio.file.Path.of("."),
            "unused",
            "unused");
    }

    /**
     * Test-side composition of the three phases {@link ServiceDirectiveResolver} drives around
     * its classify phase: {@link ServiceCatalog#decodeServiceMethod}, then
     * {@link ServiceCatalog#reduceClaims}, then {@link ServiceCatalog#bindServiceMethod}. The
     * catalog no longer offers a fused entry point, and deliberately so: coordinate precedence is
     * decided between reduce and bind, where the catalog cannot see it. What this helper drives is
     * therefore exactly decode-and-bind, which is what these cases are about.
     *
     * <p>{@code batchKeyColumns} is the batch key the classify phase resolved (the key owner's
     * primary key at a child coordinate, empty at the root).
     */
    private static ServiceCatalog.ServiceReflectionResult reflect(ServiceCatalog catalog,
            String className, String methodName, ArgBindingMap argBindings, Set<String> ctxKeys,
            List<ColumnRef> batchKeyColumns) {
        return reflect(catalog, className, methodName, argBindings, ctxKeys, batchKeyColumns, Map.of());
    }

    /** Slot-types-aware form of {@link #reflect(ServiceCatalog, String, String, ArgBindingMap, Set, List)}. */
    private static ServiceCatalog.ServiceReflectionResult reflect(ServiceCatalog catalog,
            String className, String methodName, ArgBindingMap argBindings, Set<String> ctxKeys,
            List<ColumnRef> batchKeyColumns, Map<String, graphql.schema.GraphQLInputType> slotTypes) {
        var decoded = catalog.decodeServiceMethod(className, methodName, ctxKeys);
        if (decoded.failed()) {
            return new ServiceCatalog.ServiceReflectionResult(null, decoded.rejection());
        }
        var claims = catalog.reduceClaims(decoded.signature(), argBindings, ctxKeys, slotTypes);
        // No field definition: these fixtures bind signatures against slot types directly, so no
        // argument's own directives exist to read. The @nodeId slot arm is exercised where a real
        // coordinate carries the directive, at the pipeline tier.
        return catalog.bindServiceMethod(decoded.signature(), claims, argBindings, ctxKeys,
            batchKeyColumns, slotTypes, null);
    }

    /** Test-side shorthand: wrap a raw Java-target → GraphQL-arg map as an {@link ArgBindingMap}. */
    private static ArgBindingMap bindings(Map<String, String> map) {
        var byJavaName = new java.util.LinkedHashMap<String, PathExpr>();
        map.forEach((k, v) -> byJavaName.put(k, PathExpr.head(v)));
        return new ArgBindingMap(byJavaName, java.util.Set.copyOf(map.keySet()));
    }

    @Test
    void reflectServiceMethod_dslContextParam_classifiedAsDslContextSource() {
        var result = reflect(newCatalog(),
            STUB_CLASS, "getByIdWithDsl", bindings(Map.of("id", "id")), Set.of(), List.of());

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).hasSize(2);
        assertThat(params.get(0)).isInstanceOf(MethodRef.Param.Typed.class);
        assertThat(params.get(0).source()).isInstanceOf(ParamSource.DslContext.class);
        assertThat(params.get(0).typeName()).isEqualTo("org.jooq.DSLContext");
        assertThat(params.get(1).source()).isInstanceOf(ParamSource.Arg.class);
        assertThat(params.get(1).name()).isEqualTo("id");
    }

    @Test
    void reflectServiceMethod_dslContextOnly_noArgs() {
        var result = reflect(newCatalog(),
            STUB_CLASS, "getWithDsl", bindings(Map.of()), Set.of(), List.of());

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).hasSize(1);
        assertThat(params.get(0).source()).isInstanceOf(ParamSource.DslContext.class);
        assertThat(params).noneMatch(p -> p instanceof MethodRef.Param.Sourced);
    }

    @Test
    void reflectServiceMethod_dslContextParamNameCollidesWithArg_typeWins() {
        var result = reflect(newCatalog(),
            STUB_CLASS, "getFilteredWithDsl", bindings(Map.of("filter", "filter")), Set.of(), List.of());

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).hasSize(1);
        assertThat(params.get(0).source()).isInstanceOf(ParamSource.DslContext.class);
    }

    @Test
    void reflectServiceMethod_unrecognisedParam_onChildField_pointsAtArgCtxMismatch() {
        // Non-empty batchKeyColumns: a child coordinate. The discriminator
        // is the parameter type axis, not the coordinate: a clearly non-SOURCES-adjacent type
        // (here, {@code Object}) under a non-empty parent PK still gets the arg-mismatch
        // diagnostic, matching the root-coordinate behaviour. SOURCES batching could in principle
        // apply at this coordinate, but the parameter shape rules it out, so the only plausible
        // diagnosis is a name mismatch (or a missing context key).
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = reflect(newCatalog(),
            STUB_CLASS, "getWithUnknown", bindings(Map.of()), Set.of(), filmPk);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("does not match any GraphQL argument or context key")
            .contains("available GraphQL arguments: (none)")
            .contains("available context keys: (none)")
            .doesNotContain("unrecognized sources type");
    }

    @Test
    void reflectServiceMethod_nonSourcesPayloadOnChildField_pointsAtArgCtxMismatch() {
        // Child @service whose key parameter is a proper SOURCES shape
        // (List<Row1<Integer>>) and whose second parameter is a clearly non-SOURCES-adjacent
        // type (LocalDate) whose name does not match any GraphQL argument. The arg-mismatch
        // diagnostic is the one the user can act on (rename the Java parameter or bind via
        // argMapping); an "unrecognized sources type" message would describe a feature the user
        // never asked for.
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = reflect(newCatalog(),
            STUB_CLASS, "getFilmsWithLocalDate", bindings(Map.of("dato", "dato")), Set.of(), filmPk);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("parameter 'input'")
            .contains("does not match any GraphQL argument or context key")
            .contains("available GraphQL arguments: [dato]")
            .contains("argMapping")
            .doesNotContain("unrecognized sources type");
    }

    @Test
    void reflectServiceMethod_unrecognisedParam_onRootField_pointsAtArgCtxMismatch() {
        // Empty batchKeyColumns: a root operation field. SOURCES batching
        // cannot apply, so the rejection points at the actual problem (parameter name doesn't
        // match any GraphQL argument or context key) rather than mentioning sources at all.
        var result = reflect(newCatalog(),
            STUB_CLASS, "getWithUnknown", bindings(Map.of()), Set.of(), List.of());

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("does not match any GraphQL argument or context key")
            .contains("available GraphQL arguments: (none)")
            .contains("available context keys: (none)")
            .doesNotContain("sources type");
    }

    @Test
    void reflectServiceMethod_tableRecordSources_classifiedAsTableRecordKeyed() {
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = reflect(newCatalog(),
            STUB_CLASS, "getFilmsWithTableRecordSources", bindings(Map.of()), Set.of(), filmPk);

        assertThat(result.failed()).isFalse();
        var sourced = result.ref().params().stream()
            .filter(p -> p instanceof MethodRef.Param.Sourced)
            .map(p -> (MethodRef.Param.Sourced) p)
            .findFirst()
            .orElseThrow();
        assertThat(sourced.wrap()).isEqualTo(new SourceKey.Wrap.TableRecord(
            ClassName.get(no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord.class)));
        assertThat(sourced.columns()).isEqualTo(filmPk);
        assertThat(sourced.container()).isEqualTo(LoaderRegistration.Container.POSITIONAL_LIST);
    }

    // The DTO-shaped batch parameter at a child coordinate is answered by
    // ServiceDirectiveResolver's classify phase, which owns the "this coordinate batches, and your
    // DTO parameter cannot be its key" verdict; ServiceCoordinatePrecedenceTest pins both container
    // shapes at the pipeline tier. The root arm below stays here: it is binding's own fallback.

    @Test
    void reflectServiceMethod_dtoSources_onRootField_pointsAtArgCtxMismatch() {
        // Empty batchKeyColumns: root operation field. List<DTO> would have been classified as
        // SOURCES, but root fields can't batch — the lifter-directive hint would mislead users
        // who really just have a Java-param-name vs. GraphQL-argument-name mismatch (the most
        // common cause). Surface the name mismatch directly.
        var result = reflect(newCatalog(),
            STUB_CLASS, "getFilmsWithDtoSources", bindings(Map.of("inputs", "inputs")), Set.of(), List.of());

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("parameter 'keys'")
            .contains("does not match any GraphQL argument or context key")
            .contains("available GraphQL arguments: [inputs]")
            .doesNotContain("@sourceRow")
            .doesNotContain("not backed by a jOOQ TableRecord");
    }

    @Test
    void reflectServiceMethod_dtoListMatchingArgName_classifiedAsArg() {
        // Happy path for the common batch-mutation pattern: Mutation with a single
        // List<InputDto> argument whose Java parameter name matches the GraphQL argument
        // name. This is what users hit on root operation fields once the name lines up.
        var result = reflect(newCatalog(),
            STUB_CLASS, "getFilmsWithDtoSources", bindings(Map.of("keys", "keys")), Set.of(), List.of());

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).hasSize(1);
        assertThat(params.get(0).source()).isInstanceOf(ParamSource.Arg.class);
        assertThat(params.get(0).name()).isEqualTo("keys");
    }

    @Test
    void reflectServiceMethod_rootFieldNameMismatch_suggestionMentionsPathExpression() {
        // When the parameter-mismatch suggestion already prints an argMapping
        // example (i.e. there is at least one available GraphQL arg), it also mentions that the
        // right-hand side may be a dot-path into a nested input field. Discoverability for users
        // adopting Relay-style wrapper inputs without scanning external docs.
        var result = reflect(newCatalog(),
            STUB_CLASS, "getFilmsWithDtoSources", bindings(Map.of("inputs", "inputs")), Set.of(), List.of());

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("argMapping: \"keys: inputs\"")
            .contains("dot-path into a nested input field")
            .contains("argMapping: \"keys: inputs.<fieldName>\"");
    }

    @Test
    void reflectServiceMethod_rootFieldNameMismatch_unambiguousReachablePath_suggestionIsPrefilled() {
        // When the unmatched Java parameter's type matches exactly one
        // reachable field under the available slots, the suggestion replaces the generic
        // `<fieldName>` placeholder with the concrete dotted path. Schema authors get a
        // copy-pasteable argMapping example instead of a doc lookup.
        var inputType = graphql.schema.GraphQLInputObjectType.newInputObject()
            .name("InputT")
            .field(graphql.schema.GraphQLInputObjectField.newInputObjectField()
                .name("kvotesporsmalId").type(graphql.Scalars.GraphQLString).build())
            .build();
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("input", inputType);

        var result = reflect(newCatalog(),
            STUB_CLASS, "getByIdWithDsl", bindings(Map.of("input", "input")),
            Set.of(), List.of(), slot);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("argMapping: \"id: input.kvotesporsmalId\"")
            .contains("only field reachable")
            .doesNotContain("<fieldName>");
    }

    @Test
    void reflectServiceMethod_rootFieldNameMismatch_ambiguousReachablePath_falsBackToPlaceholder() {
        // Two scalar fields of the same Java-type at the same depth: ambiguous, no prefill.
        // The floor's `<fieldName>` placeholder still fires so the user sees the path-expression
        // shape but has to fill in which field they meant.
        var inputType = graphql.schema.GraphQLInputObjectType.newInputObject()
            .name("InputT")
            .field(graphql.schema.GraphQLInputObjectField.newInputObjectField()
                .name("idA").type(graphql.Scalars.GraphQLString).build())
            .field(graphql.schema.GraphQLInputObjectField.newInputObjectField()
                .name("idB").type(graphql.Scalars.GraphQLString).build())
            .build();
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("input", inputType);

        var result = reflect(newCatalog(),
            STUB_CLASS, "getByIdWithDsl", bindings(Map.of("input", "input")),
            Set.of(), List.of(), slot);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("argMapping: \"id: input.<fieldName>\"")
            .doesNotContain("only field reachable");
    }

    @Test
    void reflectServiceMethod_rootFieldNameMismatch_typeMismatchOnReachableField_falsBackToPlaceholder() {
        // The slot has one nested scalar field, but its Java type doesn't match the unmatched
        // parameter's Java type — no prefill. The hint still appears with the generic placeholder.
        var inputType = graphql.schema.GraphQLInputObjectType.newInputObject()
            .name("InputT")
            .field(graphql.schema.GraphQLInputObjectField.newInputObjectField()
                .name("count").type(graphql.Scalars.GraphQLInt).build())
            .build();
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("input", inputType);

        var result = reflect(newCatalog(),
            STUB_CLASS, "getByIdWithDsl", bindings(Map.of("input", "input")),
            Set.of(), List.of(), slot);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("argMapping: \"id: input.<fieldName>\"")
            .doesNotContain("only field reachable");
    }

    @Test
    void reflectServiceMethod_rootFieldNameMismatch_noSlotTypes_floorRenders() {
        // 6-arg overload: no slot types passed. The stretch helper sees an empty map and
        // returns null, so the suggestion falls back to the floor placeholder. Pins that the
        // delegating overload doesn't accidentally suppress the path-expression hint.
        var result = reflect(newCatalog(),
            STUB_CLASS, "getByIdWithDsl", bindings(Map.of("input", "input")),
            Set.of(), List.of());

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("argMapping: \"id: input.<fieldName>\"")
            .doesNotContain("only field reachable");
    }

    @Test
    void reflectServiceMethod_rootFieldNameMismatch_noArgs_doesNotMentionPathExpression() {
        // The path-expression hint only fires when there is at least one available GraphQL
        // argument to point at — the no-args branch already steers the user toward adding an
        // argument or a context key, where dot-paths aren't applicable.
        var result = reflect(newCatalog(),
            STUB_CLASS, "getWithUnknown", bindings(Map.of()), Set.of(), List.of());

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("does not match any GraphQL argument or context key")
            .contains("this field declares no GraphQL arguments")
            .doesNotContain("dot-path");
    }

    @Test
    void reflectServiceMethod_rootFieldNameMismatch_listsAvailableNamesSorted() {
        // The error message lists both the available GraphQL argument names and context keys,
        // sorted, so users can spot typos. Multiple names exercise the join formatter.
        var result = reflect(newCatalog(),
            STUB_CLASS, "getWithUnknown",
            bindings(Map.of("inputs", "inputs", "filter", "filter")), Set.of("tenantId", "locale"), List.of());

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("available GraphQL arguments: [filter, inputs]")
            .contains("available context keys: [locale, tenantId]");
    }

    @Test
    void reflectServiceMethod_listOfRecord1Sources_classifiedAsRecordKeyed() {
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = reflect(newCatalog(),
            STUB_CLASS, "getFilmsWithListOfRecord1Sources", bindings(Map.of()), Set.of(), filmPk);

        assertThat(result.failed()).isFalse();
        var sourced = result.ref().params().stream()
            .filter(p -> p instanceof MethodRef.Param.Sourced)
            .map(p -> (MethodRef.Param.Sourced) p)
            .findFirst()
            .orElseThrow();
        assertThat(sourced.wrap()).isEqualTo(new SourceKey.Wrap.Record());
        assertThat(sourced.columns()).isEqualTo(filmPk);
        assertThat(sourced.container()).isEqualTo(LoaderRegistration.Container.POSITIONAL_LIST);
    }

    @Test
    void reflectServiceMethod_compositeKeyTableRecordSources_classifiedAsWrapTableRecord() {
        // Mirrors the consumer-side regelverk_exp.graphqls case where the @service source
        // is a typed record over a multi-column composite primary key. The classifier must
        // route Set<X> for composite-PK X to the Wrap.TableRecord source shape (carrying the typed
        // record class), not collapse to Wrap.Row which would pin the validator's
        // expected outer return to Map<RowN<...>, V> rather than the developer's
        // Map<X, V>.
        var filmActorPk = List.of(
            new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer"),
            new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = reflect(newCatalog(),
            STUB_CLASS, "getFilmActorsCompositeKey", bindings(Map.of()), Set.of(), filmActorPk);

        assertThat(result.failed()).isFalse();
        var sourced = result.ref().params().stream()
            .filter(p -> p instanceof MethodRef.Param.Sourced)
            .map(p -> (MethodRef.Param.Sourced) p)
            .findFirst()
            .orElseThrow();
        assertThat(sourced.wrap()).isEqualTo(new SourceKey.Wrap.TableRecord(
            ClassName.get(no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmActorRecord.class)));
        assertThat(sourced.columns()).isEqualTo(filmActorPk);
        assertThat(sourced.container()).isEqualTo(LoaderRegistration.Container.MAPPED_SET);
    }

    @Test
    void reflectServiceMethod_setOfTableRecordSources_classifiedAsWrapTableRecord() {
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = reflect(newCatalog(),
            STUB_CLASS, "getFilmsWithSetOfTableRecordSources", bindings(Map.of()), Set.of(), filmPk);

        assertThat(result.failed()).isFalse();
        var sourced = result.ref().params().stream()
            .filter(p -> p instanceof MethodRef.Param.Sourced)
            .map(p -> (MethodRef.Param.Sourced) p)
            .findFirst()
            .orElseThrow();
        assertThat(sourced.wrap()).isEqualTo(new SourceKey.Wrap.TableRecord(
            ClassName.get(no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord.class)));
        assertThat(sourced.columns()).isEqualTo(filmPk);
        assertThat(sourced.container()).isEqualTo(LoaderRegistration.Container.MAPPED_SET);
    }

    @Test
    void reflectServiceMethod_setOfRow1Sources_classifiedAsWrapRow() {
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = reflect(newCatalog(),
            STUB_CLASS, "getFilmsWithSetOfRow1Sources", bindings(Map.of()), Set.of(), filmPk);

        assertThat(result.failed()).isFalse();
        var sourced = result.ref().params().stream()
            .filter(p -> p instanceof MethodRef.Param.Sourced)
            .map(p -> (MethodRef.Param.Sourced) p)
            .findFirst()
            .orElseThrow();
        assertThat(sourced.wrap()).isEqualTo(new SourceKey.Wrap.Row());
        assertThat(sourced.columns()).isEqualTo(filmPk);
        assertThat(sourced.container()).isEqualTo(LoaderRegistration.Container.MAPPED_SET);
    }

    @Test
    void reflectServiceMethod_setOfRecord1Sources_classifiedAsWrapRecord() {
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = reflect(newCatalog(),
            STUB_CLASS, "getFilmsWithSetOfRecord1Sources", bindings(Map.of()), Set.of(), filmPk);

        assertThat(result.failed()).isFalse();
        var sourced = result.ref().params().stream()
            .filter(p -> p instanceof MethodRef.Param.Sourced)
            .map(p -> (MethodRef.Param.Sourced) p)
            .findFirst()
            .orElseThrow();
        assertThat(sourced.wrap()).isEqualTo(new SourceKey.Wrap.Record());
        assertThat(sourced.columns()).isEqualTo(filmPk);
        assertThat(sourced.container()).isEqualTo(LoaderRegistration.Container.MAPPED_SET);
    }

    // ===== Decoded return type =====
    //
    // The strict comparison against the field's expected shape is the resolver's classify phase,
    // not the catalog's: it needs the coordinate (which regime applies) and the SDL return type,
    // neither of which the catalog sees. What decode owns, and what these pin, is that the
    // captured TypeName is the method's exact parameterised return type, which is what the
    // comparison and the emitters both read. The mismatch messages themselves are pinned at the
    // pipeline tier (GraphitronSchemaBuilderTest, ServiceRootFetcherPipelineTest).

    @Test
    void decodeServiceMethod_capturesParameterisedReturnTypeExactly() {
        var decoded = newCatalog().decodeServiceMethod(STUB_CLASS, "getFilms", Set.of());

        assertThat(decoded.failed()).isFalse();
        assertThat(decoded.signature().returnType())
            .isEqualTo(ParameterizedTypeName.get(JOOQ_RESULT, FILM_RECORD));
    }

    @Test
    void decodeServiceMethod_capturedReturnTypeSurvivesOntoTheMethodRef() {
        // Bind hands the decoded return type through untouched, so an emitter declaring the
        // developer's own return type and the classify-phase comparison read the same value.
        var result = reflect(newCatalog(),
            STUB_CLASS, "getFilms", bindings(Map.of()), Set.of(), List.of());

        assertThat(result.failed()).isFalse();
        assertThat(result.ref().returnType())
            .isEqualTo(ParameterizedTypeName.get(JOOQ_RESULT, FILM_RECORD));
    }

    @Test
    void decodeServiceMethod_distinguishesInnerGenericAndCardinality() {
        // The three shapes the strict comparison has to tell apart: a different raw class, the
        // same raw class over a different inner type, and the same inner type under a different
        // container. Structural inequality is what makes each of those a mismatch downstream.
        var catalog = newCatalog();
        var single = catalog.decodeServiceMethod(STUB_CLASS, "get", Set.of()).signature().returnType();
        var films = catalog.decodeServiceMethod(STUB_CLASS, "getFilms", Set.of()).signature().returnType();
        var languages = catalog.decodeServiceMethod(STUB_CLASS, "getLanguages", Set.of()).signature().returnType();

        assertThat(single).isNotEqualTo(FILM_RECORD);
        assertThat(films).isNotEqualTo(FILM_RECORD);
        assertThat(films).isEqualTo(ParameterizedTypeName.get(JOOQ_RESULT, FILM_RECORD));
        assertThat(languages).isEqualTo(ParameterizedTypeName.get(JOOQ_RESULT, LANGUAGE_RECORD));
        assertThat(languages).isNotEqualTo(films);
    }

    // ===== argMapping override on directive site =====

    @Test
    void reflectServiceMethod_argByJavaName_override_bindsJavaNameToArgName() {
        // The GraphQL arg "input" overrides to bind the Java parameter "inputs". The Java
        // method takes (List<TestDtoStub> inputs, Boolean dryRun). Map the override
        // explicitly: "inputs" → "input"; identity for "dryRun".
        var argByJavaName = new java.util.LinkedHashMap<String, String>();
        argByJavaName.put("inputs", "input");
        argByJavaName.put("dryRun", "dryRun");
        var result = reflect(newCatalog(),
            STUB_CLASS, "runWithRenamedInputs", bindings(argByJavaName), Set.of(), List.of());

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).hasSize(2);
        assertThat(params.get(0).name()).isEqualTo("inputs");
        assertThat(params.get(0).source()).isInstanceOf(ParamSource.Arg.class);
        assertThat(((ParamSource.Arg) params.get(0).source()).path().headName()).isEqualTo("input");
        assertThat(params.get(1).name()).isEqualTo("dryRun");
        assertThat(params.get(1).source()).isInstanceOf(ParamSource.Arg.class);
        assertThat(((ParamSource.Arg) params.get(1).source()).path().headName()).isEqualTo("dryRun");
    }

    @Test
    void reflectServiceMethod_argByJavaName_identity_setsGraphqlArgNameToParamName() {
        // No override on either argument. The identity entries put the path head equal to
        // the Java parameter name on every Arg source — regression guard for the default path.
        var argByJavaName = bindings(Map.of("inputs", "inputs", "dryRun", "dryRun"));
        var result = reflect(newCatalog(),
            STUB_CLASS, "runWithRenamedInputs", argByJavaName, Set.of(), List.of());

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).extracting(p -> ((ParamSource.Arg) p.source()).path().headName())
            .containsExactly("inputs", "dryRun");
    }

    @Test
    void reflectServiceMethod_overrideTargetsNonExistentJavaParam_typoGuardFails() {
        // The override map says GraphQL arg "input" binds to Java parameter "missing", but the
        // Java method's parameters are (inputs, dryRun); "missing" is absent. The typo guard
        // rejects with a message naming the directive site, the override target, and the
        // available parameter names.
        var argByJavaName = bindings(Map.of("missing", "input", "dryRun", "dryRun"));
        var result = reflect(newCatalog(),
            STUB_CLASS, "runWithRenamedInputs", argByJavaName, Set.of(), List.of());

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("argMapping entry 'missing: input'")
            .contains("references Java parameter 'missing'")
            .contains("inputs")
            .contains("dryRun");
    }

    @Test
    void reflectTableMethod_overrideTargetingTableSlot_rejected() {
        // An override that points the Java target at the Table<?> parameter is rejected; the
        // Table<?> slot is reserved.
        var argByJavaName = bindings(Map.of("table", "input"));
        var result = newCatalog().reflectTableMethod(
            "no.sikt.graphitron.rewrite.TestConditionStub", "argCondition", argByJavaName, Set.of());

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("argMapping entry 'table: input'")
            .contains("Table<?>");
    }

    @Test
    void reflectServiceMethod_arityUniqueWithNamedInputObject_infersBindingWithoutArgMapping() {
        // A Mutation-shaped @service field declares one GraphQL argument
        // whose type is a named input object (no canonical Java scalar mapping). The Java
        // method declares one non-Table / non-DSLContext / non-Context parameter whose name
        // does not match. With exactly one unbound parameter and exactly one unclaimed slot,
        // the arity-unique branch binds them positionally rather than firing the long-form
        // "rename or argMapping" diagnostic.
        var inputType = graphql.schema.GraphQLInputObjectType.newInputObject()
            .name("RunInput")
            .field(graphql.schema.GraphQLInputObjectField.newInputObjectField()
                .name("value").type(graphql.Scalars.GraphQLString).build())
            .build();
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("input", inputType);
        var argByJavaName = bindings(Map.of("input", "input"));

        var result = reflect(newCatalog(),
            STUB_CLASS, "runWithInputBeanRenamed", argByJavaName, Set.of(), List.of(), slot);

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).hasSize(1);
        assertThat(params.get(0).name()).isEqualTo("payload");
        assertThat(params.get(0).source()).isInstanceOf(ParamSource.Arg.class);
        assertThat(((ParamSource.Arg) params.get(0).source()).path().headName()).isEqualTo("input");
    }

    @Test
    void reflectServiceMethod_arityUnique_consumerScalarParam_routesThroughClassifier() {
        // The arity-unique gate must treat consumer-defined scalars symmetrically with
        // spec built-ins. With a Decimal -> BigDecimal scalar carried in the scalar fixed
        // point (BuildContext.scalarVerdicts, the seam mapToJavaTypeName and the arity-unique
        // gate read), a BigDecimal parameter against a named input object slot defers to the
        // dot-path hint the same way a String parameter does — proving the predicate routes
        // through the model's scalar classification rather than a hard-coded allow-list.
        var ctx = new BuildContext(null, null, stubRunContext());
        var decimalScalar = graphql.schema.GraphQLScalarType.newScalar()
            .name("Decimal").coercing(graphql.schema.GraphQLScalarType.newScalar()
                .name("_").coercing(new graphql.schema.Coercing<Object, Object>() {}).build().getCoercing())
            .build();
        ctx.scalarVerdicts = Map.of("Decimal", new no.sikt.graphitron.rewrite.model.GraphitronType.ScalarType(
            "Decimal",
            new graphql.language.SourceLocation(1, 1),
            new no.sikt.graphitron.rewrite.model.ScalarResolution.Resolved(
                ClassName.get(java.math.BigDecimal.class),
                ClassName.bestGuess("dummy.Owner"),
                "DECIMAL"),
            decimalScalar));

        var inputType = graphql.schema.GraphQLInputObjectType.newInputObject()
            .name("PriceHolder")
            .field(graphql.schema.GraphQLInputObjectField.newInputObjectField()
                .name("amount").type(decimalScalar).build())
            .build();
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("input", inputType);
        var argByJavaName = bindings(Map.of("input", "input"));

        var result = reflect(new ServiceCatalog(ctx),
            STUB_CLASS, "getByPrice", argByJavaName, Set.of(), List.of(), slot);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("parameter 'price'")
            .contains("does not match any GraphQL argument");
    }

    @Test
    void reflectServiceMethod_arityUnique_listParamMatchingNestedListField_yieldsAsAmbiguous() {
        // The arity-unique branch must also check for
        // reachable nested matches of the parameter's Java type, not only the type-unique
        // branch. Here a single List<Integer> parameter sits against a single named input
        // object slot whose nested [Int!]! field maps to the same Java type. Without the
        // guard, arity-unique would silently bind the list to the wrapper; with the guard,
        // inference yields and the existing unambiguousReachablePath suggestion surfaces
        // the dot-path alternative.
        var inputType = graphql.schema.GraphQLInputObjectType.newInputObject()
            .name("IdsHolder")
            .field(graphql.schema.GraphQLInputObjectField.newInputObjectField()
                .name("values")
                .type(graphql.schema.GraphQLList.list(
                    graphql.schema.GraphQLNonNull.nonNull(graphql.Scalars.GraphQLInt)))
                .build())
            .build();
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("input", inputType);
        var argByJavaName = bindings(Map.of("input", "input"));

        var result = reflect(newCatalog(),
            STUB_CLASS, "requestByIds", argByJavaName, Set.of(), List.of(), slot);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("parameter 'requestedIds'")
            .contains("argMapping: \"requestedIds: input.values\"");
    }

    @Test
    void reflectServiceMethod_typeUniqueTopLevelPlusNameMatchedNested_R355BindsNestedByName() {
        // Type-unique inference YIELDs here so the depth-1 name search can resolve the binding.
        // The param `filmId` matches the top-level slot `id` by type only (name mismatch), but
        // matches the nested `input.filmId` by BOTH name and type. The type-unique branch yields
        // (a reachable nested match exists), then the depth-1 name search binds `filmId` to
        // `input.filmId`, the same binding the rejection diagnostic proposes as
        // `argMapping: "filmId: input.filmId"` when inference cannot fire.
        var filmInput = graphql.schema.GraphQLInputObjectType.newInputObject()
            .name("FilmInput")
            .field(graphql.schema.GraphQLInputObjectField.newInputObjectField()
                .name("filmId").type(graphql.Scalars.GraphQLID).build())
            .build();
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("id", graphql.Scalars.GraphQLID);
        slot.put("input", filmInput);
        var argByJavaName = bindings(Map.of("id", "id", "input", "input"));

        var result = reflect(newCatalog(),
            STUB_CLASS, "getByFilmId", argByJavaName, Set.of(), List.of(), slot);

        assertThat(result.failed()).isFalse();
        var param = result.ref().params().stream()
            .filter(p -> p.source() instanceof ParamSource.Arg)
            .findFirst().orElseThrow();
        assertThat(param.name()).isEqualTo("filmId");
        assertThat(((ParamSource.Arg) param.source()).path())
            .isEqualTo(PathExpr.step(PathExpr.head("input"), "filmId", false));
    }

    @Test
    void reflectServiceMethod_arityUnique_scalarParamAgainstNamedInputSlot_defersToDotPathHint() {
        // Arity-unique inference does NOT fire when the slot is a named input
        // object AND the Java parameter is a canonical scalar (String / Integer / Double /
        // Boolean). The developer almost always wants a dot-path binding into a nested field
        // in this shape, and the existing unambiguousReachablePath suggestion is the
        // appropriate fix-it. Asserts that the diagnostic still surfaces under this gate.
        var inputType = graphql.schema.GraphQLInputObjectType.newInputObject()
            .name("ScalarHolder")
            .field(graphql.schema.GraphQLInputObjectField.newInputObjectField()
                .name("value").type(graphql.Scalars.GraphQLString).build())
            .build();
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("input", inputType);
        var argByJavaName = bindings(Map.of("input", "input"));

        var result = reflect(newCatalog(),
            STUB_CLASS, "getByIdWithDsl", argByJavaName, Set.of(), List.of(), slot);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("argMapping: \"id: input.value\"");
    }

    @Test
    void reflectTableMethod_typeUniqueSignature_infersBindingWithoutArgMapping() {
        // Arg-level @condition where the Java parameter name (whatever) does not match
        // the GraphQL argument name (opptaksNavn), but the signature is type-unambiguous —
        // exactly one Table<?> parameter, exactly one String parameter, and the GraphQL slot
        // is a single String. The inference pairs them by type without requiring argMapping.
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("opptaksNavn", graphql.Scalars.GraphQLString);
        var argByJavaName = bindings(Map.of("opptaksNavn", "opptaksNavn"));
        var result = newCatalog().reflectTableMethod(
            "no.sikt.graphitron.rewrite.TestConditionStub", "argConditionTypeUnique",
            argByJavaName, Set.of(), slot);

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).hasSize(2);
        assertThat(params.get(0).source()).isInstanceOf(ParamSource.Table.class);
        assertThat(params.get(1).name()).isEqualTo("whatever");
        assertThat(params.get(1).source()).isInstanceOf(ParamSource.Arg.class);
        assertThat(((ParamSource.Arg) params.get(1).source()).path().headName()).isEqualTo("opptaksNavn");
    }

    @Test
    void reflectTableMethod_typeAmbiguousSignature_fallsBackToNameMatchingDiagnostic() {
        // When more than one Java parameter shares a type with the only slot of
        // that type, the inference treats the pairing as ambiguous and falls back to
        // name-based matching. With two String parameters and one String slot, the
        // second parameter remains unbound and the existing diagnostic fires.
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("first", graphql.Scalars.GraphQLString);
        var argByJavaName = bindings(Map.of("first", "first"));
        var result = newCatalog().reflectTableMethod(
            "no.sikt.graphitron.rewrite.TestConditionStub", "argConditionTwoStrings",
            argByJavaName, Set.of(), slot);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("parameter 'second'")
            .contains("not a GraphQL argument");
    }

    // ===== name-based depth-1 nested-field inference =====

    /** Builds an input-object slot type with one field of the given GraphQL type. */
    private static graphql.schema.GraphQLInputObjectType inputObject(String name, String fieldName,
            graphql.schema.GraphQLInputType fieldType) {
        return graphql.schema.GraphQLInputObjectType.newInputObject()
            .name(name)
            .field(graphql.schema.GraphQLInputObjectField.newInputObjectField()
                .name(fieldName).type(fieldType).build())
            .build();
    }

    @Test
    void inferNestedFieldByName_singleMatchingScalarField_returnsDepth1Step() {
        // One unclaimed input-object slot with a direct field whose name AND mapped Java type
        // match the unbound parameter → the depth-1 Step a hand-written
        // argMapping: "fra: range.fra" would produce, with liftsList=false for a scalar leaf.
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("range", inputObject("VerdiRange", "fra", graphql.Scalars.GraphQLInt));

        var result = newCatalog().inferNestedFieldByName(
            "fra", "java.lang.Integer", List.of("range"), slot);

        assertThat(result).isEqualTo(PathExpr.step(PathExpr.head("range"), "fra", false));
    }

    @Test
    void inferNestedFieldByName_listShapedField_computesLiftsListTrue() {
        // A [Int] field binds a List<Integer> parameter; the Step's liftsList flag is computed
        // via ArgBindingMap.isListShaped (true), byte-identical to the explicit-argMapping Step.
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("range", inputObject("VerdiRange", "verdier",
            graphql.schema.GraphQLList.list(graphql.Scalars.GraphQLInt)));

        var result = newCatalog().inferNestedFieldByName(
            "verdier", "java.util.List<java.lang.Integer>", List.of("range"), slot);

        assertThat(result).isEqualTo(PathExpr.step(PathExpr.head("range"), "verdier", true));
    }

    @Test
    void inferNestedFieldByName_nameMatchesButTypeDiffers_returnsNull() {
        // The slot has a field named like the parameter, but its mapped Java type (Integer) does
        // not equal the parameter's (String) — name+type must both match, so no binding.
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("range", inputObject("VerdiRange", "fra", graphql.Scalars.GraphQLInt));

        var result = newCatalog().inferNestedFieldByName(
            "fra", "java.lang.String", List.of("range"), slot);

        assertThat(result).isNull();
    }

    @Test
    void inferNestedFieldByName_twoSlotsEachWithMatchingField_returnsNullAsAmbiguous() {
        // Two unclaimed input-object slots each carry a field named (and typed) like the
        // parameter → two candidates across slots → ambiguous → null, so the per-parameter
        // rejection / suggestion still fires.
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("rangeA", inputObject("VerdiRangeA", "fra", graphql.Scalars.GraphQLInt));
        slot.put("rangeB", inputObject("VerdiRangeB", "fra", graphql.Scalars.GraphQLInt));

        var result = newCatalog().inferNestedFieldByName(
            "fra", "java.lang.Integer", List.of("rangeA", "rangeB"), slot);

        assertThat(result).isNull();
    }

    // ===== Declared-exception capture =====

    @Test
    void reflectServiceMethod_capturesDeclaredCheckedExceptions() {
        // ServiceCatalog reads Method.getExceptionTypes() and stores the FQNs on
        // MethodRef#declaredExceptions(); the classifier's match check consumes them.
        var result = reflect(newCatalog(),
            STUB_CLASS, "getThrowingSqlException", bindings(Map.of()), Set.of(), List.of());

        assertThat(result.failed()).isFalse();
        assertThat(result.ref().declaredExceptions())
            .containsExactly("java.sql.SQLException");
    }

    @Test
    void reflectServiceMethod_capturesMultipleDeclaredExceptions_inSourceOrder() {
        var result = reflect(newCatalog(),
            STUB_CLASS, "getThrowingSqlAndInterrupted", bindings(Map.of()), Set.of(), List.of());

        assertThat(result.failed()).isFalse();
        assertThat(result.ref().declaredExceptions())
            .containsExactly("java.sql.SQLException", "java.lang.InterruptedException");
    }

    @Test
    void reflectServiceMethod_methodWithoutThrows_emptyDeclaredExceptions() {
        var result = reflect(newCatalog(),
            STUB_CLASS, "get", bindings(Map.of()), Set.of(), List.of());

        assertThat(result.failed()).isFalse();
        assertThat(result.ref().declaredExceptions()).isEmpty();
    }

    // ===== Instance-method services =====

    @Test
    void reflectServiceMethod_staticMethod_classifiedAsStaticCallShape() {
        var result = reflect(newCatalog(),
            STUB_CLASS, "get", bindings(Map.of()), Set.of(), List.of());

        assertThat(result.failed()).isFalse();
        assertThat(((MethodRef.Service) result.ref()).callShape())
            .isInstanceOf(MethodRef.CallShape.Static.class);
    }

    @Test
    void reflectServiceMethod_instanceMethodWithDslContextCtor_classifiedAsInstanceWithDslHolder() {
        // Holder class exposes a public (DSLContext) constructor — matches the legacy
        // generator's `new ServiceName(_iv_transform.getCtx())` pattern. Instance methods
        // on this shape classify as InstanceWithDslHolder.
        var result = reflect(newCatalog(),
            "no.sikt.graphitron.rewrite.TestInstanceServiceStub", "getFilm",
            bindings(Map.of()), Set.of(), List.of());

        assertThat(result.failed()).isFalse();
        assertThat(((MethodRef.Service) result.ref()).callShape())
            .isInstanceOf(MethodRef.CallShape.InstanceWithDslHolder.class);
    }

    @Test
    void reflectServiceMethod_instanceMethodUnbindableCtor_rejectedWithActionableMessage() {
        // Holder class's only public constructor takes a parameter that is neither a DSLContext
        // nor a declared context key, so no constructor is bindable (the holder rule admits any
        // all-bindable constructor, not just a (DSLContext)-only one). The classifier rejects with the
        // typed InstanceHolderUnconstructible arm spelling out both options.
        var result = reflect(newCatalog(),
            "no.sikt.graphitron.rewrite.TestInstanceServiceStubUnbindableCtor", "getFilm",
            bindings(Map.of()), Set.of(), List.of());

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection())
            .isInstanceOf(no.sikt.graphitron.model.diagnostics.ServiceMethodCallError.InstanceHolderUnconstructible.class);
        assertThat(result.rejection().message())
            .contains("instance method")
            .contains("no public constructor whose")
            .contains("DSLContext or a declared context argument")
            .contains("make the method static");
    }

    @Test
    void reflectServiceMethod_instanceMethodNoArgCtor_admittedWithEmptyCtorParams() {
        // A no-arg public constructor is trivially all-bindable, so an instance @service on
        // such a holder now resolves (it emits `new Service().method(...)`); the call shape carries
        // no ctor parameters.
        var result = reflect(newCatalog(),
            "no.sikt.graphitron.rewrite.TestInstanceServiceStubNoCtor", "getFilm",
            bindings(Map.of()), Set.of(), List.of());

        assertThat(result.failed()).isFalse();
        var callShape = ((MethodRef.Service) result.ref()).callShape();
        assertThat(callShape).isInstanceOf(MethodRef.CallShape.InstanceWithDslHolder.class);
        assertThat(((MethodRef.CallShape.InstanceWithDslHolder) callShape).ctorParams()).isEmpty();
    }

    @Test
    void reflectServiceMethod_instanceMethodDslAndContextCtor_resolvesCtorParamSources() {
        // A (DSLContext, tenantId) constructor resolves when tenantId is a declared context
        // key. The holder's ctor parameter sources are carried on the call shape in order: a
        // DSLContext slot then a context binding.
        var result = reflect(newCatalog(),
            "no.sikt.graphitron.rewrite.TestInstanceServiceStubMultiArgCtor", "getFilm",
            bindings(Map.of()), Set.of("tenantId"), List.of());

        assertThat(result.failed()).isFalse();
        var callShape = ((MethodRef.CallShape.InstanceWithDslHolder)
            ((MethodRef.Service) result.ref()).callShape());
        assertThat(callShape.ctorParams()).hasSize(2);
        assertThat(callShape.ctorParams().get(0).source()).isInstanceOf(ParamSource.DslContext.class);
        assertThat(callShape.ctorParams().get(1).source()).isInstanceOf(ParamSource.Context.class);
        assertThat(callShape.ctorParams().get(1).name()).isEqualTo("tenantId");
    }

    // ===== shared reflection-intrinsic typed arms =====

    @Test
    void reflectServiceMethod_classNotLoaded_producesTypedReflectionError() {
        var result = reflect(newCatalog(),
            "com.example.DoesNotExist", "get", bindings(Map.of()), Set.of(), List.of());

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection())
            .isInstanceOf(no.sikt.graphitron.model.diagnostics.ReflectionError.ClassNotLoaded.class);
        assertThat(result.rejection().message()).contains("could not be loaded");
    }

    // ReflectionError.ReturnTypeMismatch is minted by the resolver's classify phase, which needs
    // the coordinate and the SDL return type; its typed arm is pinned end-to-end by
    // ServiceRootFetcherPipelineTest.serviceWithMismatchedReturnType_surfacesAsValidationError.

    @Test
    void reflectServiceMethod_overloadedMethod_producesAmbiguousMethod() {
        // TestServiceStub declares two methods named getOverloaded (arity 0 and 1). The @service
        // coordinate admits exactly one declaration, so any second one lands the NameShared axis;
        // admission on the binding shape is the @condition coordinate's rule and not this one's.
        var result = reflect(newCatalog(),
            STUB_CLASS, "getOverloaded", bindings(Map.of()), Set.of(), List.of());

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection())
            .isInstanceOf(no.sikt.graphitron.model.diagnostics.ReflectionError.AmbiguousMethod.class);
        var ambiguous =
            (no.sikt.graphitron.model.diagnostics.ReflectionError.AmbiguousMethod) result.rejection();
        assertThat(ambiguous.ambiguity())
            .isEqualTo(new no.sikt.graphitron.model.diagnostics.ReflectionError.AmbiguousMethod
                .Ambiguity.NameShared());
        assertThat(ambiguous.candidateSignatures())
            .as("the declarations arrive as rendered signatures, not as bare arities")
            .hasSize(2)
            .allSatisfy(signature -> assertThat(signature).startsWith("getOverloaded("));
    }
}
