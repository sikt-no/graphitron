package no.sikt.graphitron.render;

import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.plan.GeneratedUnits;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ColumnValuesClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.TableAliasesClassGenerator;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.Condition;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConditionUnitRenderer}'s classes, compiled strictly and called: each filtered field's method
 * at the address its callers name, a participant's where the field's rows are told apart by their
 * tables, and beside a faceted field's method its base and each facet's own. Read over the
 * {@code scenarios/condition-participant} and {@code scenarios/connection-shared-facets} fact
 * documents; what each method selects is the execution tests' beside {@link no.sikt.graphitron.emit.ConditionEmitter}.
 */
@UnitTier
class ConditionUnitRendererTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PARTICIPANTS = "participants";
    private static final String FACETS = "facets";

    private static final List<EmittedCodeHarness> harnesses = new ArrayList<>();
    private static Map<String, Method> participants;
    private static Map<String, Method> facets;

    @BeforeAll
    static void render() throws Exception {
        participants = compile(PARTICIPANTS, "scenarios/condition-participant");
        facets = compile(FACETS, "scenarios/connection-shared-facets");
    }

    /** The document's conditions classes and the runtime they call, compiled; the query root's methods. */
    private static Map<String, Method> compile(String pkg, String document) throws Exception {
        var units = FACTS.read(document, graph -> ConditionUnitRenderer.render(graph.handle(), pkg,
            no.sikt.graphitron.rewrite.GraphQLRewriteGenerator.conditionAddresses(new GeneratedUnits(pkg))));
        var dir = Files.createTempDirectory("conditions");
        var files = new ArrayList<Path>();
        for (var unit : units) {
            files.add(JavaFile.builder(unit.ref().packageName(), unit.spec()).build().writeToPath(dir));
        }
        for (TypeSpec spec : NodeIdEncoderClassGenerator.generate(List.of(), pkg, List.of())) {
            files.add(JavaFile.builder(pkg + ".util", spec).build().writeToPath(dir));
        }
        for (TypeSpec spec : TableAliasesClassGenerator.generate(pkg)) {
            files.add(JavaFile.builder(pkg + ".util", spec).build().writeToPath(dir));
        }
        for (TypeSpec spec : ColumnValuesClassGenerator.generate()) {
            files.add(JavaFile.builder(pkg + ".util", spec).build().writeToPath(dir));
        }
        for (TypeSpec spec : GraphitronClientExceptionClassGenerator.generate()) {
            files.add(JavaFile.builder(pkg + ".schema", spec).build().writeToPath(dir));
        }
        var harness = EmittedCodeHarness.compileFilesStrictly(files);
        harnesses.add(harness);
        var ref = new GeneratedUnits(pkg).conditions("Query");
        var out = new HashMap<String, Method>();
        for (var m : harness.load(ref.packageName() + "." + ref.simpleName()).getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) out.put(m.getName(), m);
        }
        return out;
    }

    @AfterAll
    static void close() {
        harnesses.forEach(EmittedCodeHarness::close);
    }

    @Test
    void aFieldOverSeveralTablesHasOneMethodPerParticipant() {
        var units = new GeneratedUnits(PARTICIPANTS);
        assertThat(participants.keySet()).contains(
            units.participantConditionMethod("Query", "occupantById", "Customer").methodName(),
            units.participantConditionMethod("Query", "occupantById", "Staff").methodName(),
            units.participantConditionMethod("Query", "stockByLanguage", "StockFilm").methodName(),
            units.participantConditionMethod("Query", "stockByLanguage", "StockInventory").methodName())
            .doesNotContain(units.conditionMethod("Query", "occupantById").methodName());
    }

    @Test
    void aParticipantsMethodTakesThatParticipantsTable() {
        var method = participants.get(new GeneratedUnits(PARTICIPANTS)
            .participantConditionMethod("Query", "occupantById", "Staff").methodName());
        assertThat(method.getParameterTypes()).containsExactly(
            no.sikt.graphitron.rewrite.test.jooq.public_.tables.Staff.class, Map.class);
    }

    @Test
    void aFacetedFieldHasItsMethodItsBaseAndEachFacetsOwn() {
        var units = new GeneratedUnits(FACETS);
        assertThat(facets.keySet()).contains(
            units.conditionMethod("Query", "filmsById").methodName(),
            units.facetBaseConditionMethod("Query", "filmsById").methodName(),
            units.facetConditionMethod("Query", "filmsById", "originalLanguageId").methodName());
    }

    @Test
    void aFacetsOwnFilterWithNoValueNarrowsNothing() throws Exception {
        var own = facets.get(new GeneratedUnits(FACETS)
            .facetConditionMethod("Query", "filmsById", "originalLanguageId").methodName());
        assertThat((Condition) own.invoke(null, Tables.FILM, Map.of())).isEqualTo(DSL.noCondition());
    }
}
