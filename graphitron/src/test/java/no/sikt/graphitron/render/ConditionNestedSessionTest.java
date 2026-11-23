package no.sikt.graphitron.render;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.command.SessionAccessors;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.plan.GeneratedUnits;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ColumnValuesClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.TableAliasesClassGenerator;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.jooq.public_.udt.records.SessionHandleRecord;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that a field's condition reached under two parents asks for the session by the field, not by
 * the occurrence: {@code CustomerShelf.customers} is reached as {@code Rental.shelf.customers} and
 * {@code Payment.shelf.customers}, and its {@code @condition} reads {@code $session.principal}, so both
 * occurrences render the one method alike. Read over the {@code scenarios/condition-nested-session}
 * fact document and compiled strictly against a stand-in {@code OwnedConnections} whose member
 * accessor hands back the coordinate it is asked with.
 */
@UnitTier
class ConditionNestedSessionTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PACKAGE = "nestedsession";
    private static final GeneratedUnits UNITS = new GeneratedUnits(PACKAGE);

    private static List<ConditionUnitRenderer.Unit> units;
    private static EmittedCodeHarness harness;

    @BeforeAll
    static void render() throws Exception {
        units = FACTS.read("scenarios/condition-nested-session",
            graph -> ConditionUnitRenderer.render(graph.handle(), PACKAGE,
                no.sikt.graphitron.rewrite.GraphQLRewriteGenerator.conditionAddresses(new GeneratedUnits(PACKAGE))));
        var dir = Files.createTempDirectory("nestedsession");
        var files = new ArrayList<Path>();
        for (var unit : units) {
            files.add(JavaFile.builder(unit.ref().packageName(), unit.spec()).build().writeToPath(dir));
        }
        for (TypeSpec spec : NodeIdEncoderClassGenerator.generate(List.of(), PACKAGE, List.of())) {
            files.add(JavaFile.builder(PACKAGE + ".util", spec).build().writeToPath(dir));
        }
        for (TypeSpec spec : TableAliasesClassGenerator.generate(PACKAGE)) {
            files.add(JavaFile.builder(PACKAGE + ".util", spec).build().writeToPath(dir));
        }
        for (TypeSpec spec : ColumnValuesClassGenerator.generate()) {
            files.add(JavaFile.builder(PACKAGE + ".util", spec).build().writeToPath(dir));
        }
        for (TypeSpec spec : GraphitronClientExceptionClassGenerator.generate()) {
            files.add(JavaFile.builder(PACKAGE + ".schema", spec).build().writeToPath(dir));
        }
        files.add(JavaFile.builder(PACKAGE + ".schema", ownedConnections()).build().writeToPath(dir));
        harness = EmittedCodeHarness.compileFilesStrictly(files);
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    /**
     * The two session reads the conditions class calls: the handle, which this test never reaches,
     * and the principal member, which answers the coordinate it is asked with so the test can read it.
     */
    private static TypeSpec ownedConnections() {
        var dsl = ClassName.get(DSLContext.class);
        return TypeSpec.classBuilder("OwnedConnections")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(MethodSpec.methodBuilder("sessionHandle")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(SessionHandleRecord.class)
                .addParameter(dsl, "dsl")
                .addParameter(String.class, "fieldCoordinate")
                .addStatement("throw new $T(fieldCoordinate)", UnsupportedOperationException.class)
                .build())
            .addMethod(MethodSpec.methodBuilder(SessionAccessors.memberMethod("principal"))
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(String.class)
                .addParameter(dsl, "dsl")
                .addParameter(String.class, "fieldCoordinate")
                .addStatement("return fieldCoordinate")
                .build())
            .build();
    }

    private static List<Method> methods(String name) throws Exception {
        var owner = UNITS.conditions("CustomerShelf");
        return Arrays.stream(harness.load(owner.packageName() + "." + owner.simpleName()).getDeclaredMethods())
            .filter(m -> m.getName().equals(name) && java.lang.reflect.Modifier.isPublic(m.getModifiers()))
            .toList();
    }

    @Test
    void theNestingTypesConditionsClassHasOneMethodForTheField() throws Exception {
        assertThat(units).extracting(unit -> unit.ref().simpleName())
            .containsOnlyOnce(UNITS.conditions("CustomerShelf").simpleName());
        assertThat(methods(UNITS.conditionMethod("CustomerShelf", "customers").methodName())).hasSize(1);
    }

    @Test
    void theSessionIsAskedForByTheField() throws Exception {
        var method = methods(UNITS.conditionMethod("CustomerShelf", "customers").methodName()).getFirst();
        var condition = (Condition) method.invoke(null, Tables.CUSTOMER, Map.of(), null);
        assertThat(condition.toString())
            .contains("'CustomerShelf.customers'")
            .doesNotContain("Rental.shelf")
            .doesNotContain("Payment.shelf");
    }
}
