package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.ColumnRef;
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

/**
 * Unit coverage for {@link ServiceCatalog#reflectServiceMethod} parameter classification
 * and {@link ServiceCatalog#reflectTableMethod} / {@link ServiceCatalog#reflectServiceMethod}
 * strict-return-type validation. Exercises the reflection path in isolation with synthetic
 * {@link TestServiceStub} / {@link TestTableMethodStub} methods; the classifier does not
 * read {@code BuildContext.schema} or {@code BuildContext.catalog}, so both may be
 * {@code null} here.
 */
@UnitTier
class ServiceCatalogTest {

    private static final String STUB_CLASS = "no.sikt.graphitron.rewrite.TestServiceStub";
    private static final String TABLE_METHOD_STUB_CLASS = "no.sikt.graphitron.rewrite.TestTableMethodStub";

    private static final ClassName FILM_RECORD = ClassName.get(
        "no.sikt.graphitron.rewrite.test.jooq.tables.records", "FilmRecord");
    private static final ClassName LANGUAGE_RECORD = ClassName.get(
        "no.sikt.graphitron.rewrite.test.jooq.tables.records", "LanguageRecord");
    private static final ClassName JOOQ_RESULT = ClassName.get("org.jooq", "Result");
    private static final ClassName FILM_TABLE_CLASS = ClassName.get(
        "no.sikt.graphitron.rewrite.test.jooq.tables", "Film");
    private static final ClassName LANGUAGE_TABLE_CLASS = ClassName.get(
        "no.sikt.graphitron.rewrite.test.jooq.tables", "Language");

    private static ServiceCatalog newCatalog() {
        return new ServiceCatalog(new BuildContext(null, null, stubRewriteContext()));
    }

    /**
     * Minimal {@link RewriteContext} for unit-tier classifier tests that don't need real schema
     * inputs or output paths. The 6-arg overload defaults {@code classpathRoots} to the empty
     * list and {@code codegenLoader} to the current thread's context classloader; in a JUnit
     * JVM that's the system classloader, which is exactly what bare two-arg
     * {@code Class.forName(name)} used to resolve through.
     */
    private static RewriteContext stubRewriteContext() {
        return new RewriteContext(
            java.util.List.of(),
            java.nio.file.Path.of("."),
            java.nio.file.Path.of("."),
            "unused",
            "unused",
            Map.of());
    }

    /** Test-side shorthand: wrap a raw Java-target → GraphQL-arg map as an {@link ArgBindingMap}. */
    private static ArgBindingMap bindings(Map<String, String> map) {
        var byJavaName = new java.util.LinkedHashMap<String, PathExpr>();
        map.forEach((k, v) -> byJavaName.put(k, PathExpr.head(v)));
        return new ArgBindingMap(byJavaName);
    }

    @Test
    void reflectServiceMethod_dslContextParam_classifiedAsDslContextSource() {
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getByIdWithDsl", bindings(Map.of("id", "id")), Set.of(), List.of(), null);

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
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getWithDsl", bindings(Map.of()), Set.of(), List.of(), null);

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).hasSize(1);
        assertThat(params.get(0).source()).isInstanceOf(ParamSource.DslContext.class);
        assertThat(params).noneMatch(p -> p instanceof MethodRef.Param.Sourced);
    }

    @Test
    void reflectServiceMethod_dslContextParamNameCollidesWithArg_typeWins() {
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilteredWithDsl", bindings(Map.of("filter", "filter")), Set.of(), List.of(), null);

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).hasSize(1);
        assertThat(params.get(0).source()).isInstanceOf(ParamSource.DslContext.class);
    }

    @Test
    void reflectServiceMethod_unrecognisedParam_onChildField_pointsAtArgCtxMismatch() {
        // Non-empty parentPkColumns: child of a table-backed parent. Post-R187 the discriminator
        // is the parameter type axis, not the coordinate: a clearly non-SOURCES-adjacent type
        // (here, {@code Object}) under a non-empty parent PK still gets the arg-mismatch
        // diagnostic, matching the root-coordinate behaviour. SOURCES batching could in principle
        // apply at this coordinate, but the parameter shape rules it out, so the only plausible
        // diagnosis is a name mismatch (or a missing context key).
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getWithUnknown", bindings(Map.of()), Set.of(), filmPk, null);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("does not match any GraphQL argument or context key")
            .contains("available GraphQL arguments: (none)")
            .contains("available context keys: (none)")
            .doesNotContain("unrecognized sources type");
    }

    @Test
    void reflectServiceMethod_nonSourcesPayloadOnChildField_pointsAtArgCtxMismatch() {
        // R187 reproduction: child @service whose key parameter is a proper SOURCES shape
        // (List<Row1<Integer>>) and whose second parameter is a clearly non-SOURCES-adjacent
        // type (LocalDate) whose name does not match any GraphQL argument. The arg-mismatch
        // diagnostic is the one the user can act on (rename the Java parameter or bind via
        // argMapping); the legacy "unrecognized sources type" message described a feature the
        // user never asked for.
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilmsWithLocalDate", bindings(Map.of("dato", "dato")), Set.of(), filmPk, null);

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
        // Empty parentPkColumns: root operation field or DTO-parent child. SOURCES batching
        // cannot apply, so the rejection points at the actual problem (parameter name doesn't
        // match any GraphQL argument or context key) rather than mentioning sources at all.
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getWithUnknown", bindings(Map.of()), Set.of(), List.of(), null);

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
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilmsWithTableRecordSources", bindings(Map.of()), Set.of(), filmPk, null);

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

    @Test
    void reflectServiceMethod_dtoSources_onChildField_rejectedWithLifterDirectiveHint() {
        // Non-empty parentPkColumns: child of a table-backed parent. This is the only context
        // where the lifter-directive hint is genuinely actionable — DataLoader batching applies
        // and the missing piece is a DTO-to-key conversion, the feature roadmap/R1 will add.
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilmsWithDtoSources", bindings(Map.of()), Set.of(), filmPk, null);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("not backed by a jOOQ TableRecord")
            .contains("@sourceRow");
    }

    @Test
    void reflectServiceMethod_dtoSources_onRootField_pointsAtArgCtxMismatch() {
        // Empty parentPkColumns: root operation field. List<DTO> would have been classified as
        // SOURCES, but root fields can't batch — the lifter-directive hint would mislead users
        // who really just have a Java-param-name vs. GraphQL-argument-name mismatch (the most
        // common cause). Surface the name mismatch directly.
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilmsWithDtoSources", bindings(Map.of("inputs", "inputs")), Set.of(), List.of(), null);

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
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilmsWithDtoSources", bindings(Map.of("keys", "keys")), Set.of(), List.of(), null);

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).hasSize(1);
        assertThat(params.get(0).source()).isInstanceOf(ParamSource.Arg.class);
        assertThat(params.get(0).name()).isEqualTo("keys");
    }

    @Test
    void reflectServiceMethod_rootFieldNameMismatch_suggestionMentionsPathExpression() {
        // R84 Phase F floor: when the parameter-mismatch suggestion already prints an argMapping
        // example (i.e. there is at least one available GraphQL arg), it also mentions that the
        // right-hand side may be a dot-path into a nested input field. Discoverability for users
        // adopting Relay-style wrapper inputs without scanning external docs.
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilmsWithDtoSources", bindings(Map.of("inputs", "inputs")), Set.of(), List.of(), null);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("argMapping: \"keys: inputs\"")
            .contains("dot-path into a nested input field")
            .contains("argMapping: \"keys: inputs.<fieldName>\"");
    }

    @Test
    void reflectServiceMethod_rootFieldNameMismatch_unambiguousReachablePath_suggestionIsPrefilled() {
        // R84 Phase F (stretch): when the unmatched Java parameter's type matches exactly one
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

        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getByIdWithDsl", bindings(Map.of("input", "input")),
            Set.of(), List.of(), null, slot);

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

        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getByIdWithDsl", bindings(Map.of("input", "input")),
            Set.of(), List.of(), null, slot);

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

        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getByIdWithDsl", bindings(Map.of("input", "input")),
            Set.of(), List.of(), null, slot);

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
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getByIdWithDsl", bindings(Map.of("input", "input")),
            Set.of(), List.of(), null);

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
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getWithUnknown", bindings(Map.of()), Set.of(), List.of(), null);

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
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getWithUnknown",
            bindings(Map.of("inputs", "inputs", "filter", "filter")), Set.of("tenantId", "locale"), List.of(), null);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("available GraphQL arguments: [filter, inputs]")
            .contains("available context keys: [locale, tenantId]");
    }

    @Test
    void reflectServiceMethod_listOfRecord1Sources_classifiedAsRecordKeyed() {
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilmsWithListOfRecord1Sources", bindings(Map.of()), Set.of(), filmPk, null);

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
    void reflectServiceMethod_compositeKeyTableRecordSources_classifiedAsMappedTableRecordKeyed() {
        // Mirrors the consumer-side regelverk_exp.graphqls case where the @service source
        // is a typed record over a multi-column composite primary key. The classifier must
        // route Set<X> for composite-PK X to MappedTableRecordKeyed (carrying the typed
        // record class), not collapse to MappedRowKeyed which would pin the validator's
        // expected outer return to Map<RowN<...>, V> rather than the developer's
        // Map<X, V>.
        var filmActorPk = List.of(
            new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer"),
            new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilmActorsCompositeKey", bindings(Map.of()), Set.of(), filmActorPk, null);

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
    void reflectServiceMethod_setOfTableRecordSources_classifiedAsMappedTableRecordKeyed() {
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilmsWithSetOfTableRecordSources", bindings(Map.of()), Set.of(), filmPk, null);

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
    void reflectServiceMethod_setOfRow1Sources_classifiedAsMappedRowKeyed() {
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilmsWithSetOfRow1Sources", bindings(Map.of()), Set.of(), filmPk, null);

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
    void reflectServiceMethod_setOfRecord1Sources_classifiedAsMappedRecordKeyed() {
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilmsWithSetOfRecord1Sources", bindings(Map.of()), Set.of(), filmPk, null);

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

    @Test
    void reflectServiceMethod_setOfDtoSources_onChildField_rejectedWithDtoMessage() {
        // Non-empty parentPkColumns: child of a table-backed parent. The Set<DTO> rejection
        // takes the same DTO-message path as List<DTO>, not the generic unrecognized-sources
        // fallback.
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilmsWithSetOfDtoSources", bindings(Map.of()), Set.of(), filmPk, null);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("not backed by a jOOQ TableRecord")
            .doesNotContain("unrecognized sources type");
    }

    // ===== Strict-return-type validation =====

    @Test
    void reflectServiceMethod_nullExpected_skipsValidationCapturesActual() {
        // expectedReturnType=null path: no validation; the captured TypeName on MethodRef.Basic
        // is whatever reflection saw, regardless of shape.
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilms", bindings(Map.of()), Set.of(), List.of(), null);

        assertThat(result.failed()).isFalse();
        assertThat(result.ref().returnType())
            .isEqualTo(ParameterizedTypeName.get(JOOQ_RESULT, FILM_RECORD));
    }

    @Test
    void reflectServiceMethod_matchingParameterizedExpected_succeeds() {
        var expected = ParameterizedTypeName.get(JOOQ_RESULT, FILM_RECORD);
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilms", bindings(Map.of()), Set.of(), List.of(), expected);

        assertThat(result.failed()).isFalse();
        assertThat(result.ref().returnType()).isEqualTo(expected);
    }

    @Test
    void reflectServiceMethod_mismatchedRawClass_failsWithBothNamesInMessage() {
        // Single-cardinality field expects FilmRecord; method returns String — mismatch on the
        // raw outer class.
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "get", bindings(Map.of()), Set.of(), List.of(), FILM_RECORD);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("must return")
            .contains("FilmRecord")
            .contains("String");
    }

    @Test
    void reflectServiceMethod_mismatchedInnerGeneric_failsStructurally() {
        // List-cardinality field expects Result<FilmRecord>; method returns Result<LanguageRecord>.
        // The raw outer Result matches; only the inner type differs. Structural equality must
        // catch this — a raw-class-only check would let it slip through.
        var expected = ParameterizedTypeName.get(JOOQ_RESULT, FILM_RECORD);
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getLanguages", bindings(Map.of()), Set.of(), List.of(), expected);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("FilmRecord")
            .contains("LanguageRecord");
    }

    @Test
    void reflectServiceMethod_mismatchedCardinality_failsListVsSingle() {
        // Field expects FilmRecord (Single); method returns Result<FilmRecord> (List).
        // Same inner class, different outer wrapper.
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilms", bindings(Map.of()), Set.of(), List.of(), FILM_RECORD);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("must return")
            .contains("FilmRecord")
            .contains("Result");
    }

    @Test
    void reflectTableMethod_matchingExpected_succeedsAndCapturesClassName() {
        var result = newCatalog().reflectTableMethod(
            TABLE_METHOD_STUB_CLASS, "getFilm", bindings(Map.of()), Set.of(), FILM_TABLE_CLASS,
            ServiceCatalog.TableSlotPolicy.FORBIDDEN);

        assertThat(result.failed()).isFalse();
        assertThat(result.ref().returnType()).isEqualTo(FILM_TABLE_CLASS);
    }

    @Test
    void reflectTableMethod_mismatchedClass_failsWithBothNamesInMessage() {
        // Method returns Film (the table class) but the field expects Language. The pre-existing
        // Table<?>-returning `get` covers the wider-return-type case; this case pins that the
        // strict check rejects mismatched specific-class returns symmetrically.
        var result = newCatalog().reflectTableMethod(
            TABLE_METHOD_STUB_CLASS, "getFilm", bindings(Map.of()), Set.of(), LANGUAGE_TABLE_CLASS,
            ServiceCatalog.TableSlotPolicy.FORBIDDEN);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("must return the generated jOOQ table class")
            .contains("Language")
            .contains("Film");
    }

    @Test
    void reflectTableMethod_widerReturnType_failsAgainstSpecificExpected() {
        // The legacy Table<?>-returning `get` method violates Invariants §3 when the field
        // expects a specific table class. Pins the rejection path the user is most likely to
        // trip into.
        var result = newCatalog().reflectTableMethod(
            TABLE_METHOD_STUB_CLASS, "get", bindings(Map.of()), Set.of(), FILM_TABLE_CLASS,
            ServiceCatalog.TableSlotPolicy.FORBIDDEN);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("must return the generated jOOQ table class")
            .contains("Film");
    }

    // ===== R53: argMapping override on directive site =====

    @Test
    void reflectServiceMethod_argByJavaName_override_bindsJavaNameToArgName() {
        // Plan §44: the GraphQL arg "input" overrides to bind the Java parameter "inputs".
        // The Java method takes (List<TestDtoStub> inputs, Boolean dryRun). Map the override
        // explicitly: "inputs" → "input"; identity for "dryRun".
        var argByJavaName = new java.util.LinkedHashMap<String, String>();
        argByJavaName.put("inputs", "input");
        argByJavaName.put("dryRun", "dryRun");
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "runWithRenamedInputs", bindings(argByJavaName), Set.of(), List.of(), null);

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).hasSize(2);
        assertThat(params.get(0).name()).isEqualTo("inputs");
        assertThat(params.get(0).source()).isInstanceOf(ParamSource.Arg.class);
        assertThat(((ParamSource.Arg) params.get(0).source()).graphqlArgName()).isEqualTo("input");
        assertThat(params.get(1).name()).isEqualTo("dryRun");
        assertThat(params.get(1).source()).isInstanceOf(ParamSource.Arg.class);
        assertThat(((ParamSource.Arg) params.get(1).source()).graphqlArgName()).isEqualTo("dryRun");
    }

    @Test
    void reflectServiceMethod_argByJavaName_identity_setsGraphqlArgNameToParamName() {
        // No override on either argument. The identity entries put graphqlArgName equal to
        // the Java parameter name on every Arg source — regression guard for the default path.
        var argByJavaName = bindings(Map.of("inputs", "inputs", "dryRun", "dryRun"));
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "runWithRenamedInputs", argByJavaName, Set.of(), List.of(), null);

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).extracting(p -> ((ParamSource.Arg) p.source()).graphqlArgName())
            .containsExactly("inputs", "dryRun");
    }

    @Test
    void reflectServiceMethod_overrideTargetsNonExistentJavaParam_typoGuardFails() {
        // The override map says GraphQL arg "input" binds to Java parameter "missing", but the
        // Java method's parameters are (inputs, dryRun) — "missing" is absent. Plan §54: typo
        // guard rejects with a message naming the directive site, the override target, and the
        // available parameter names.
        var argByJavaName = bindings(Map.of("missing", "input", "dryRun", "dryRun"));
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "runWithRenamedInputs", argByJavaName, Set.of(), List.of(), null);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("argMapping entry 'missing: input'")
            .contains("references Java parameter 'missing'")
            .contains("inputs")
            .contains("dryRun");
    }

    @Test
    void reflectTableMethod_argByJavaName_override_bindsJavaNameToArgName() {
        // @tableMethod variant (FORBIDDEN policy after R43): override targets a non-Table Java
        // parameter. The Java method takes only (String tenantId); the override maps "tenantId"
        // to the GraphQL arg "tenant".
        var argByJavaName = bindings(Map.of("tenantId", "tenant"));
        var result = newCatalog().reflectTableMethod(
            TABLE_METHOD_STUB_CLASS, "getFilmWithContext", argByJavaName, Set.of(), FILM_TABLE_CLASS,
            ServiceCatalog.TableSlotPolicy.FORBIDDEN);

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).hasSize(1);
        assertThat(params.get(0).name()).isEqualTo("tenantId");
        assertThat(params.get(0).source()).isInstanceOf(ParamSource.Arg.class);
        assertThat(((ParamSource.Arg) params.get(0).source()).graphqlArgName()).isEqualTo("tenant");
    }

    @Test
    void reflectTableMethod_tableParamRejected_underForbiddenPolicy() {
        // R43: under FORBIDDEN policy a Table<?> parameter on a @tableMethod method is rejected
        // outright; graphitron derives the target table from the return type and parent-table
        // filtering is @reference's job. The legacy `get(Table<?>)` method exercises this — but
        // the project's TestTableMethodStub no longer declares Table parameters after R43, so
        // we exercise the rejection via TestConditionStub (which still declares Table-leading
        // methods).
        var result = newCatalog().reflectTableMethod(
            "no.sikt.graphitron.rewrite.TestConditionStub", "lifterFieldCondition",
            bindings(Map.of()), Set.of(), null,
            ServiceCatalog.TableSlotPolicy.FORBIDDEN);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("is a Table<?>")
            .contains("must not declare");
    }

    @Test
    void reflectTableMethod_overrideTargetingTableSlot_rejected_underRequiredPolicy() {
        // Under REQUIRED policy (@condition caller), an override that points the Java target at
        // the Table<?> parameter is rejected; the Table<?> slot is reserved.
        var argByJavaName = bindings(Map.of("table", "input"));
        var result = newCatalog().reflectTableMethod(
            "no.sikt.graphitron.rewrite.TestConditionStub", "argCondition", argByJavaName, Set.of(), null,
            ServiceCatalog.TableSlotPolicy.REQUIRED);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("argMapping entry 'table: input'")
            .contains("Table<?>");
    }

    @Test
    void reflectServiceMethod_arityUniqueWithNamedInputObject_infersBindingWithoutArgMapping() {
        // R214 (extension): a Mutation-shaped @service field declares one GraphQL argument
        // whose type is a named input object (no canonical Java scalar mapping). The Java
        // method declares one non-Table / non-DSLContext / non-Context parameter whose name
        // does not match. With exactly one unbound parameter and exactly one unclaimed slot,
        // the arity-unique branch binds them positionally — the long-form "rename or
        // argMapping" diagnostic no longer fires for this shape.
        var inputType = graphql.schema.GraphQLInputObjectType.newInputObject()
            .name("RunInput")
            .field(graphql.schema.GraphQLInputObjectField.newInputObjectField()
                .name("value").type(graphql.Scalars.GraphQLString).build())
            .build();
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("input", inputType);
        var argByJavaName = bindings(Map.of("input", "input"));

        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "runWithInputBeanRenamed", argByJavaName, Set.of(), List.of(), null, slot);

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).hasSize(1);
        assertThat(params.get(0).name()).isEqualTo("payload");
        assertThat(params.get(0).source()).isInstanceOf(ParamSource.Arg.class);
        assertThat(((ParamSource.Arg) params.get(0).source()).graphqlArgName()).isEqualTo("input");
    }

    @Test
    void reflectServiceMethod_arityUnique_scalarParamAgainstNamedInputSlot_defersToDotPathHint() {
        // R214 floor: arity-unique inference does NOT fire when the slot is a named input
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

        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getByIdWithDsl", argByJavaName, Set.of(), List.of(), null, slot);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("argMapping: \"id: input.value\"");
    }

    @Test
    void reflectTableMethod_typeUniqueSignature_infersBindingWithoutArgMapping() {
        // R214: arg-level @condition where the Java parameter name (whatever) does not match
        // the GraphQL argument name (opptaksNavn), but the signature is type-unambiguous —
        // exactly one Table<?> parameter, exactly one String parameter, and the GraphQL slot
        // is a single String. The inference pairs them by type without requiring argMapping.
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("opptaksNavn", graphql.Scalars.GraphQLString);
        var argByJavaName = bindings(Map.of("opptaksNavn", "opptaksNavn"));
        var result = newCatalog().reflectTableMethod(
            "no.sikt.graphitron.rewrite.TestConditionStub", "argConditionTypeUnique",
            argByJavaName, Set.of(), null,
            ServiceCatalog.TableSlotPolicy.REQUIRED, slot);

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).hasSize(2);
        assertThat(params.get(0).source()).isInstanceOf(ParamSource.Table.class);
        assertThat(params.get(1).name()).isEqualTo("whatever");
        assertThat(params.get(1).source()).isInstanceOf(ParamSource.Arg.class);
        assertThat(((ParamSource.Arg) params.get(1).source()).graphqlArgName()).isEqualTo("opptaksNavn");
    }

    @Test
    void reflectTableMethod_typeAmbiguousSignature_fallsBackToNameMatchingDiagnostic() {
        // R214 floor: when more than one Java parameter shares a type with the only slot of
        // that type, the inference treats the pairing as ambiguous and falls back to
        // name-based matching. With two String parameters and one String slot, the
        // second parameter remains unbound and the existing diagnostic fires.
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("first", graphql.Scalars.GraphQLString);
        var argByJavaName = bindings(Map.of("first", "first"));
        var result = newCatalog().reflectTableMethod(
            "no.sikt.graphitron.rewrite.TestConditionStub", "argConditionTwoStrings",
            argByJavaName, Set.of(), null,
            ServiceCatalog.TableSlotPolicy.REQUIRED, slot);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("parameter 'second'")
            .contains("not a GraphQL argument");
    }

    @Test
    void reflectTableMethod_nullExpected_skipsValidation() {
        // Condition-method callers (REQUIRED policy) pass null for the expected class since
        // their return shape is Condition, not a table. Pin that null disables strict validation
        // regardless of the actual return type. Uses a no-arg method on TestTableMethodStub that
        // returns the wider Table<?>; under REQUIRED with no Table param the test would fail the
        // foundTable check, so the test uses FORBIDDEN here — the null-expected branch is shared.
        var result = newCatalog().reflectTableMethod(
            TABLE_METHOD_STUB_CLASS, "get", bindings(Map.of()), Set.of(), null,
            ServiceCatalog.TableSlotPolicy.FORBIDDEN);

        assertThat(result.failed()).isFalse();
        // Captured return is the wider Table<?> raw class; the model still records it faithfully.
        assertThat(result.ref().returnType().toString()).isEqualTo("org.jooq.Table");
    }

    // ===== R12 §4 declared-exception capture =====

    @Test
    void reflectServiceMethod_capturesDeclaredCheckedExceptions() {
        // ServiceCatalog reads Method.getExceptionTypes() and stores the FQNs on
        // MethodRef#declaredExceptions(); the classifier's §4 match check consumes them.
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getThrowingSqlException", bindings(Map.of()), Set.of(), List.of(), null);

        assertThat(result.failed()).isFalse();
        assertThat(result.ref().declaredExceptions())
            .containsExactly("java.sql.SQLException");
    }

    @Test
    void reflectServiceMethod_capturesMultipleDeclaredExceptions_inSourceOrder() {
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getThrowingSqlAndInterrupted", bindings(Map.of()), Set.of(), List.of(), null);

        assertThat(result.failed()).isFalse();
        assertThat(result.ref().declaredExceptions())
            .containsExactly("java.sql.SQLException", "java.lang.InterruptedException");
    }

    @Test
    void reflectServiceMethod_methodWithoutThrows_emptyDeclaredExceptions() {
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "get", bindings(Map.of()), Set.of(), List.of(), null);

        assertThat(result.failed()).isFalse();
        assertThat(result.ref().declaredExceptions()).isEmpty();
    }

    // ===== Instance-method services =====

    @Test
    void reflectServiceMethod_staticMethod_classifiedAsStaticCallShape() {
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "get", bindings(Map.of()), Set.of(), List.of(), null);

        assertThat(result.failed()).isFalse();
        assertThat(((MethodRef.Service) result.ref()).callShape())
            .isInstanceOf(MethodRef.CallShape.Static.class);
    }

    @Test
    void reflectServiceMethod_instanceMethodWithDslContextCtor_classifiedAsInstanceWithDslHolder() {
        // Holder class exposes a public (DSLContext) constructor — matches the legacy
        // generator's `new ServiceName(_iv_transform.getCtx())` pattern. Instance methods
        // on this shape classify as InstanceWithDslHolder.
        var result = newCatalog().reflectServiceMethod(
            "no.sikt.graphitron.rewrite.TestInstanceServiceStub", "getFilm",
            bindings(Map.of()), Set.of(), List.of(), null);

        assertThat(result.failed()).isFalse();
        assertThat(((MethodRef.Service) result.ref()).callShape())
            .isInstanceOf(MethodRef.CallShape.InstanceWithDslHolder.class);
    }

    @Test
    void reflectServiceMethod_instanceMethodWithoutDslContextCtor_rejectedWithActionableMessage() {
        // Holder class has only a no-arg constructor. The emitter has no way to thread a
        // DSLContext into the holder, so the classifier rejects with both options spelled out
        // (make the method static, or add a (DSLContext) constructor).
        var result = newCatalog().reflectServiceMethod(
            "no.sikt.graphitron.rewrite.TestInstanceServiceStubNoCtor", "getFilm",
            bindings(Map.of()), Set.of(), List.of(), null);

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("instance method")
            .contains("no public constructor taking a single org.jooq.DSLContext")
            .contains("make the method static");
    }
}
