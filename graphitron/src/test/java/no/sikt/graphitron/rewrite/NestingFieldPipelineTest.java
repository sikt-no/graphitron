package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.schema.FetcherRegistrationsEmitter;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ChildField.NestingField;
import no.sikt.graphitron.rewrite.model.Presence;
import no.sikt.graphitron.rewrite.model.Presence.NullContract;
import no.sikt.graphitron.command.CallWrap;
import no.sikt.graphitron.command.Contribution;
import no.sikt.graphitron.command.PresenceProbe;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * SDL → fetcher-registration pipeline tests for {@link NestingField}. Covers the three
 * emission sides affected by nesting:
 * <ul>
 *   <li>Outer type's {@code registerFetchers(codeRegistry)} body — {@code Film.details} wired as a
 *       passthrough lambda (lambda kind).</li>
 *   <li>{@code Film.$project(...)} — switch arm for {@code details} recurses into nested column
 *       names.</li>
 *   <li>Nested type's {@code registerFetchers(codeRegistry)} body — one entry per nested type in
 *       the {@link FetcherRegistrationsEmitter} output, keyed by the nested type's name.</li>
 * </ul>
 */
@PipelineTier
class NestingFieldPipelineTest {

    private static final String SCALAR_NESTING_SDL = """
        type Film @table(name: "film") { details: FilmDetails }
        type FilmDetails { title: String }
        type Query { film: Film }
        """;

    @Test
    void outerFetcherRegistration_wiresNestingFieldAsLightFetcher() {
        // The source-passthrough NestingField read (return source) is reified onto
        // FilmFetchers and registered wrapped in LightFetcher (COLUMN_FETCHER kind), not an
        // inline lambda.
        var bodies = fetcherBodies(SCALAR_NESTING_SDL);
        assertThat(TypeSpecAssertions.wiringFor(bodies, "Film", "details"))
            .contains(TypeSpecAssertions.DataFetcherKind.COLUMN_FETCHER);
    }

    @Test
    void outerTypeClass_fieldsSwitchProjectsNestingArm() {
        var filmType = findType("Film", SCALAR_NESTING_SDL);
        assertThat(TypeSpecAssertions.hasFieldsArm(filmType, "details")).isTrue();
    }

    @Test
    void nestedUnit_projectsTheNestedScalar() {
        // Nesting types are projection units of their own, keyed (anchor, typeName) and
        // anchor-prefixed; the anchor's arm splices the nested unit's $project rather than
        // inlining the nested switch.
        var nestedUnit = findType("FilmFilmDetails", SCALAR_NESTING_SDL);
        assertThat(TypeSpecAssertions.hasFieldsArm(nestedUnit, "title")).isTrue();
    }

    @Test
    void fetcherRegistration_emitsOneBodyPerNestedType() {
        var bodies = fetcherBodies(SCALAR_NESTING_SDL);
        assertThat(bodies).containsKey("FilmDetails");
        assertThat(TypeSpecAssertions.wiringFor(bodies, "FilmDetails", "title"))
            .contains(TypeSpecAssertions.DataFetcherKind.COLUMN_FETCHER);
    }

    @Test
    void fetcherRegistration_noNestingField_noNestedTypeBody() {
        var bodies = fetcherBodies("""
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """);
        assertThat(bodies).doesNotContainKey("FilmDetails");
    }

    @Test
    void fetcherRegistration_multiLevelNesting_emitsBodyForEveryNestedType() {
        var bodies = fetcherBodies("""
            type Film @table(name: "film") { details: FilmDetails }
            type FilmDetails { title: String, meta: FilmMeta }
            type FilmMeta { title: String }
            type Query { film: Film }
            """);
        assertThat(bodies.keySet()).contains("FilmDetails", "FilmMeta");
    }

    @Test
    void fetcherRegistration_sharedNestedType_emittedOnlyOnce() {
        var bodies = fetcherBodies("""
            type Film @table(name: "film") { details: FilmDetails }
            type FilmList @table(name: "film") { details: FilmDetails }
            type FilmDetails { title: String }
            type Query { film: Film }
            """);
        assertThat(bodies).containsKey("FilmDetails");
    }

    private static final String SPLIT_NESTING_SDL = """
        type Actor @table(name: "actor") { name: String }
        type FilmInfo {
            cast: [Actor!]! @splitQuery
                @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
        }
        type Film @table(name: "film") { info: FilmInfo }
        type Query { film: Film }
        """;

    @Test
    void fetcherRegistration_nestedSplitField_referencesNestedFetchersClass() {
        var bodies = fetcherBodies(SPLIT_NESTING_SDL);
        assertThat(bodies).containsKey("FilmInfo");
        assertThat(TypeSpecAssertions.wiringFor(bodies, "FilmInfo", "cast"))
            .contains(TypeSpecAssertions.DataFetcherKind.METHOD_REFERENCE);
        assertThat(bodies.get("FilmInfo").toString()).contains("FilmInfoFetchers");
    }

    @Test
    void fetcherRegistration_nestedSplitField_inlineLeavesInSameTypeStillWireInline() {
        var bodies = fetcherBodies("""
            type Actor @table(name: "actor") { name: String }
            type FilmInfo {
                title: String
                cast: [Actor!]! @splitQuery
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
            }
            type Film @table(name: "film") { info: FilmInfo }
            type Query { film: Film }
            """);
        assertThat(TypeSpecAssertions.wiringFor(bodies, "FilmInfo", "title"))
            .contains(TypeSpecAssertions.DataFetcherKind.COLUMN_FETCHER);
        assertThat(TypeSpecAssertions.wiringFor(bodies, "FilmInfo", "cast"))
            .contains(TypeSpecAssertions.DataFetcherKind.METHOD_REFERENCE);
    }

    @Test
    void typeClass_nestedSplitField_projectsOuterParentSourceKeyColumn() {
        // Film.info.cast's SourceKey column (FILM.FILM_ID) is projected by the "cast" arm on
        // the nested unit FilmFilmInfo, whose $project shares the anchor's table context; the
        // anchor's splice lands it in Film's select list whenever cast is selected. Without the
        // arm, the fixture still compiles; the emitted
        // DSL.row(((Record) env.getSource()).get(Tables.FILM.FILM_ID)) then reads a field the
        // SELECT omitted, which jOOQ rejects at request time.
        var nestedUnit = findType("FilmFilmInfo", SPLIT_NESTING_SDL);
        assertThat(TypeSpecAssertions.armProjectsColumn(nestedUnit, "cast", "FILM_ID")).isTrue();
    }

    // ===== Null contract =====

    private static final String RELEASE_SDL = """
        type Film @table(name: "film") {
            release: FilmRelease
            releaseRequired: FilmRelease!
        }
        type FilmRelease {
            year: Int @field(name: "RELEASE_YEAR")
            minutes: Int @field(name: "LENGTH")
        }
        type Query { film: Film }
        """;

    @Test
    void nullableWrapper_collapsesOverOneLeafPerColumn() {
        var nf = (NestingField) TestSchemaHelper.buildSchema(RELEASE_SDL).field("Film", "release");
        assertThat(contractOf(RELEASE_SDL, "Film", "release")).isInstanceOfSatisfying(NullContract.CollapsesWhenAbsent.class,
            c -> assertThat(c.columnLeaves())
                .extracting(l -> l.columns().stream().map(col -> col.sqlName()).toList())
                .containsExactly(List.of("release_year"), List.of("length")));
        assertThat(nf.parentRowColumns()).extracting(c -> c.sqlName()).containsExactly("release_year", "length");
    }

    @Test
    void nonNullWrapper_isAlwaysAnObject() {
        assertThat(contractOf(RELEASE_SDL, "Film", "releaseRequired")).isInstanceOfSatisfying(NullContract.AlwaysObject.class,
            c -> assertThat(c.reason()).isInstanceOf(NullContract.Reason.NonNull.class));
    }

    @Test
    void nullableWrapper_spliceProjectsTheDemandedColumns() {
        // The check reads every presence column whatever the query selects under the wrapper, so
        // the anchor's splice arm projects them whenever the field is selected.
        var filmType = findType("Film", RELEASE_SDL);
        assertThat(TypeSpecAssertions.armProjectsColumn(filmType, "release", "RELEASE_YEAR")).isTrue();
        assertThat(TypeSpecAssertions.armProjectsColumn(filmType, "release", "LENGTH")).isTrue();
        assertThat(TypeSpecAssertions.armProjectsColumn(filmType, "releaseRequired", "RELEASE_YEAR")).isFalse();
        assertThat(splice(RELEASE_SDL, "Film", "release").probe()).isEmpty();
    }

    private static final String COMPUTED_NESTING_SDL = """
        type Film @table(name: "film") { labels: FilmLabels }
        type FilmLabels {
            rating: String @externalField(reference: {className: "no.sikt.graphitron.rewrite.TestExternalFieldStub", method: "rating"})
            any: String @externalField(reference: {className: "no.sikt.graphitron.rewrite.TestExternalFieldStub", method: "anyTable"})
        }
        type Query { film: Film }
        """;

    @Test
    void wrapperOfComputedFieldsOnly_collapsesThroughNullTermsAlone() {
        // The sis ResultatAlleSprak shape: every field a scalar @externalField over the parent's
        // row. The fetcher reads the probe column exactly when the contract has probed leaves, and
        // checks no column leaf first since there is none.
        assertThat(contractOf(COMPUTED_NESTING_SDL, "Film", "labels")).isInstanceOfSatisfying(
            NullContract.CollapsesWhenAbsent.class, c -> {
                assertThat(c.columnLeaves()).isEmpty();
                assertThat(c.probed()).extracting(l -> ((Presence.PresenceLeaf.Computed) l).field().name())
                    .containsExactly("rating", "any");
            });
        var nf = (NestingField) TestSchemaHelper.buildSchema(COMPUTED_NESTING_SDL).field("Film", "labels");
        assertThat(nf.parentRowColumns()).isEmpty();
        var probe = splice(COMPUTED_NESTING_SDL, "Film", "labels").probe().orElseThrow();
        assertThat(probe.alias()).isEqualTo("__p_Film$labels");
        assertThat(probe.columnLeaves()).isEmpty();
        assertThat(probe.terms())
            .allSatisfy(t -> assertThat(t).isInstanceOf(PresenceProbe.ProbeTerm.NotNull.class))
            .extracting(t -> ((PresenceProbe.ProbeTerm.NotNull) t).method().methodName())
            .containsExactly("rating", "anyTable");
    }

    private static final String LIST_NESTING_SDL = """
        type Actor @table(name: "actor") { firstName: String @field(name: "FIRST_NAME") }
        type FilmInfo {
            year: Int @field(name: "RELEASE_YEAR")
            cast: [Actor!]! @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
        }
        type Film @table(name: "film") { info: FilmInfo }
        type Query { film: Film }
        """;

    @Test
    void argumentFreeListChild_probesForAnyRow() {
        // The phd shape: an object of columns and a list resolves to null when the columns are
        // null and the list is empty, so the list is a probe over its own two-hop path.
        assertThat(contractOf(LIST_NESTING_SDL, "Film", "info")).isInstanceOfSatisfying(NullContract.CollapsesWhenAbsent.class,
            c -> assertThat(c.probed()).singleElement().isInstanceOfSatisfying(no.sikt.graphitron.rewrite.model.Presence.PresenceLeaf.Probe.class, p -> {
                assertThat(p.field()).isInstanceOf(ChildField.TableField.class);
                assertThat(p.joinPath()).hasSize(2);
                assertThat(p.targetColumns()).isEmpty();
            }));
        var probe = splice(LIST_NESTING_SDL, "Film", "info").probe().orElseThrow();
        assertThat(probe.alias()).isEqualTo("__p_Film$info");
        assertThat(probe.columnLeaves()).extracting(l -> l.get(0).sqlName()).containsExactly("release_year");
        assertThat(probe.terms()).singleElement().isInstanceOfSatisfying(no.sikt.graphitron.command.PresenceProbe.ProbeTerm.Exists.class, t -> {
            assertThat(t.reach().size()).isEqualTo(2);
            assertThat(t.filter()).isNull();
            assertThat(t.targetColumns()).isEmpty();
        });
    }

    @Test
    void listChildTakingArguments_blocksTheCollapse() {
        var sdl = """
            type Actor @table(name: "actor") { firstName: String @field(name: "FIRST_NAME") }
            type FilmInfo {
                year: Int @field(name: "RELEASE_YEAR")
                cast(firstName: String @field(name: "FIRST_NAME")): [Actor!]!
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
            }
            type Film @table(name: "film") { info: FilmInfo }
            type Query { film: Film }
            """;
        assertThat(contractOf(sdl, "Film", "info")).isInstanceOfSatisfying(NullContract.AlwaysObject.class,
            c -> assertThat(c.reason()).isInstanceOfSatisfying(NullContract.Reason.BlockedBy.class,
                b -> assertThat(b.blocker().name()).isEqualTo("cast")));
        var splice = splice(sdl, "Film", "info");
        assertThat(splice.callerDemand()).isEmpty();
        assertThat(splice.probe()).isEmpty();
    }

    @Test
    void splitQueryChild_probesExactlyAsItsInlineForm() {
        assertThat(contractOf(SPLIT_NESTING_SDL, "Film", "info")).isInstanceOfSatisfying(NullContract.CollapsesWhenAbsent.class,
            c -> assertThat(c.probed()).singleElement().isInstanceOfSatisfying(no.sikt.graphitron.rewrite.model.Presence.PresenceLeaf.Probe.class, p -> {
                assertThat(p.field()).isInstanceOf(ChildField.BatchedTableField.class);
                assertThat(p.joinPath()).hasSize(2);
            }));
        assertThat(splice(SPLIT_NESTING_SDL, "Film", "info").probe()).isPresent();
    }

    // ===== Reference leaves =====

    @Test
    void singleFkReference_probesItsTargetsColumnsAndTheSpliceCarriesTheProbe() {
        var sdl = """
            type Language @table(name: "language") { name: String }
            type FilmInfo {
                year: Int @field(name: "RELEASE_YEAR")
                originalLanguage: Language @reference(path: [{key: "film_original_language_id_fkey"}])
            }
            type Film @table(name: "film") { info: FilmInfo }
            type Query { film: Film }
            """;
        var nf = (NestingField) TestSchemaHelper.buildSchema(sdl).field("Film", "info");
        // The join column no longer stands in for the reference: a set column whose target row is
        // missing (the studiesemesterPeriode shape) must still collapse, so the probe asks for a
        // referenced row holding a non-null column of Language.
        assertThat(nf.parentRowColumns()).extracting(c -> c.sqlName()).containsExactly("release_year");
        var probe = splice(sdl, "Film", "info").probe().orElseThrow();
        assertThat(probe.terms()).singleElement().isInstanceOfSatisfying(no.sikt.graphitron.command.PresenceProbe.ProbeTerm.Exists.class, t -> {
            assertThat(t.reach().size()).isEqualTo(1);
            assertThat(t.targetColumns()).extracting(c -> c.sqlName()).containsExactly("name");
        });
    }

    @Test
    void twoHopScalarReference_probesItsColumnOverTheWholePath() {
        var sdl = """
            type AddressInfo {
                country: String @field(name: "COUNTRY")
                    @reference(path: [{key: "address_city_id_fkey"}, {key: "city_country_id_fkey"}])
            }
            type Address @table(name: "address") { info: AddressInfo }
            type Query { address: Address }
            """;
        var nf = (NestingField) TestSchemaHelper.buildSchema(sdl).field("Address", "info");
        assertThat(nf.nestedFields()).singleElement().isInstanceOf(ChildField.ColumnBackedReferenceField.class);
        assertThat(nf.parentRowColumns()).isEmpty();
        var probe = splice(sdl, "Address", "info").probe().orElseThrow();
        assertThat(probe.columnLeaves()).isEmpty();
        assertThat(probe.terms()).singleElement().isInstanceOfSatisfying(no.sikt.graphitron.command.PresenceProbe.ProbeTerm.Exists.class, t -> {
            assertThat(t.reach().size()).isEqualTo(2);
            assertThat(t.targetColumns()).extracting(c -> c.sqlName()).containsExactly("country");
        });
    }

    @Test
    void conditionJoinedReference_probes() {
        var sdl = """
            type Language @table(name: "language") { name: String }
            type FilmInfo {
                spoken: Language @reference(path: [{condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "join"}}])
            }
            type Film @table(name: "film") { info: FilmInfo }
            type Query { film: Film }
            """;
        assertThat(contractOf(sdl, "Film", "info")).isInstanceOfSatisfying(NullContract.CollapsesWhenAbsent.class,
            c -> assertThat(c.probed()).singleElement()
                .isInstanceOfSatisfying(no.sikt.graphitron.rewrite.model.Presence.PresenceLeaf.Probe.class, p -> assertThat(p.field().name()).isEqualTo("spoken")));
    }

    private static final String SHARED_INNER_REFERENCE_SDL = """
        type Film @table(name: "film") { title: String }
        type Inner { film: Film }
        type Wrap { inner: Inner }
        type %s @table(name: "%s") { wrap: Wrap }
        type %s @table(name: "%s") { wrap: Wrap }
        type Query { a: %s b: %s }
        """;

    private static List<String> sharedInnerReferenceErrors(String a, String aTable, String b, String bTable) {
        var schema = TestSchemaHelper.buildSchema(SHARED_INNER_REFERENCE_SDL.formatted(a, aTable, b, bTable, a, b));
        return new GraphitronSchemaValidator().validate(schema).stream()
            .map(ValidationError::message)
            .filter(m -> m.contains("shared across"))
            .toList();
    }

    @Test
    void sharedNestedType_innerReferenceJoinedFromDifferentlyNamedColumns_passes() {
        // One fetcher on the shared Wrap type serves Wrap.inner at both sites and reads its probe
        // alias, which each site's splice projects over that site's own join path; the join
        // columns no longer reach the fetcher, so their names need not agree.
        assertThat(sharedInnerReferenceErrors("Content", "content", "Endorsement", "film_endorsement")).isEmpty();
    }

    @Test
    void sharedNestedType_innerReferenceJoinedFromSameNamedColumns_passes() {
        assertThat(sharedInnerReferenceErrors("Content", "content", "Inventory", "inventory")).isEmpty();
    }

    // ===== Helpers =====

    private static TypeSpec findType(String className, String sdl) {
        return ProjectionRenderTestSupport.renderProjections(TestSchemaHelper.buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Type class not found: " + className));
    }

    private static NullContract contractOf(String sdl, String type, String field) {
        var schema = TestSchemaHelper.buildSchema(sdl);
        return schema.nullContractOf((ChildField) schema.field(type, field));
    }

    private static CallWrap.Splice splice(String sdl, String type, String field) {
        var contribution = ProjectionRenderTestSupport.contribution(TestSchemaHelper.buildSchema(sdl),
            DEFAULT_OUTPUT_PACKAGE, type, field);
        return (CallWrap.Splice) ((Contribution.Call) contribution).wrap();
    }

    private static Map<String, CodeBlock> fetcherBodies(String sdl) {
        return SchemaShapeRenderTestSupport.fetcherRegistrations(TestSchemaHelper.buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE);
    }
}
