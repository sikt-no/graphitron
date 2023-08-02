package no.fellesstudentsystem.graphitron;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphitron.conditions.*;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.enums.KjonnTest;
import no.fellesstudentsystem.kjerneapi.tables.*;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

public class GraphQLGeneratorQueryTest extends TestCommon {
    public static final String SRC_TEST_RESOURCES_PATH = "query";

    private final Map<String, Class<?>> enums = Map.of("KJONN_TEST", KjonnTest.class);
    private final Map<String, Method> conditions = Map.ofEntries(
            new AbstractMap.SimpleEntry<>("TEST_EMNE_KODE", EmneTestConditions.class.getMethod("emneKode",Emne.class, String.class)),
            new AbstractMap.SimpleEntry<>("TEST_EMNE_KODER", EmneTestConditions.class.getMethod("emneKoder", Emne.class, List.class)),
            new AbstractMap.SimpleEntry<>("TEST_EMNE_ALL", EmneTestConditions.class.getMethod("emneAll", Emne.class, String.class, List.class)),
            new AbstractMap.SimpleEntry<>("TEST_EMNE_INPUT_ALL", EmneTestConditions.class.getMethod("emneInputAll", Emne.class, String.class, String.class, String.class)),
            new AbstractMap.SimpleEntry<>("TEST_STUDENT_INSTITUSJON_EQUALS", StudentTestConditions.class.getMethod("studentEierinstitusjon", Student.class, Institusjon.class)),
            new AbstractMap.SimpleEntry<>("TEST_PERMISJON_STUDIERETT", PermisjonTestConditions.class.getMethod("permisjonStudierettJoin", Permisjon.class, Studierett.class)),
            new AbstractMap.SimpleEntry<>("TEST_PERSON_TELEFON_PRIVAT", PersonTelefonTestConditions.class.getMethod("personTelefonPrivat", Person.class, PersonTelefon.class)),
            new AbstractMap.SimpleEntry<>("TEST_PERSON_TELEFON_MOBIL", PersonTelefonTestConditions.class.getMethod("personTelefonMobil", Person.class, PersonTelefon.class)),
            new AbstractMap.SimpleEntry<>("TEST_TERMIN", TerminTestConditions.class.getMethod("terminer", Termin.class, String.class)),
            new AbstractMap.SimpleEntry<>("TEST_TERMIN_ALL", TerminTestConditions.class.getMethod("terminAll", Termin.class, String.class, Integer.class)),
            new AbstractMap.SimpleEntry<>("TEST_TERMIN_INPUT_ALL", TerminTestConditions.class.getMethod("terminInputAll", Termin.class, Integer.class, String.class, Integer.class))
    );

    public GraphQLGeneratorQueryTest() throws NoSuchMethodException {
        super(SRC_TEST_RESOURCES_PATH);
    }

    @Override
    protected void setProperties() {
        GeneratorConfig.setProperties(
                DEFAULT_SYSTEM_PACKAGE,
                Set.of(),
                tempOutputDirectory.toString(),
                DEFAULT_OUTPUT_PACKAGE,
                DEFAULT_JOOQ_PACKAGE,
                enums,
                conditions,
                Map.of(),
                Map.of()
        );
    }

    private Set<String> getLogMessagesWithLevelWarn() {
        return logWatcher.list.stream()
                .filter(it -> it.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toSet());
    }

    @Test
    void generate_usingAllDefinedDirectives_shouldGenerateResolversForAllSupportedTypesOfJoins() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("allDefinedDirectivesInUse");
    }

    @Test //jOOQ' støtter maks 22 type-safe records. Flere enn 22 er støttet, men uten type safety.
    void generate_whenTypeHasMoreThan22Fields_shouldGenerateValidResolver() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("moreThan22Fields");
    }

    @Test
    void generate_manualResolver_shouldNotGenerateAnyResolvers() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("manualResolver");
    }

    @Test
    void generate_whenMixOfOptionalAndRequiredFieldsOnRequiredLeafNode_shouldGenerateQueryThatIncludesOneRequiredField() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("mixOfOptionalAndRequiredFields");
    }

    @Test
    void generate_queryWithPagination_shouldCreateQueryResolverWithPaginationSupport() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithPagination");
    }

    @Test
    void generate_queryWithResolverPagination_shouldCreateResolverWithPaginationOnResolver() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithResolverPagination");
    }

    @Test
    void generate_queryWithPaginationFullRelayBoilerplate_shouldCreateQueryResolverWithPaginationSupport() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithPaginationRelayBoilerplate", "queryWithPagination");
    }

    @Test
    void generate_queryWithMultipleAndOptionalArguments_shouldCreateQueryResolverThatHandlesMultipleAndOptionalArguments() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithMultipleArguments");
    }

    @Test
    void generate_queryWithArguments_shouldUseCorrectPathForWhere() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("correctWhereConditionPathUsage");
    }

    @Test
    void generate_queryWithInputTypes_shouldCreateQueryResolverThatHandlesInputTypes() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithInputTypeArguments");
    }

    @Test
    void generate_queryWithConditions_shouldCreateQueriesWithExtraConditions() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithConditions");
    }

    @Test
    void generate_queryWithConditions_shouldCreateQueriesWithEnumConditionInputs() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithEnumConditions");
    }

    @Test
    void generate_referenceGivenKey_shouldJoinTables() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("referenceGivenKey");
    }

    @Test
    void generate_referenceGivenConditionOnDirectJoin_shouldApplyCondition() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("referenceGivenConditionOnDirectJoin");
    }

    @Test
    void generate_referenceViaTables_shouldCreateJoinViaTables() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("referenceViaTables");
    }

    @Disabled("not supported yet")
    @Test
    void generate_referenceViaTablesBackwards_shouldCreateJoinViaTablesBackwards() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("referenceViaTablesBackwards");
    }

    @Test
    void generate_referenceGivenConditionViaTables_shouldCreateJoinViaTablesThenCondition() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("referenceGivenConditionViaTables");
    }

    @Test
    void generate_referenceViaTableWithCondition_shouldCreateJoinViaTableWithCondition() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("referenceViaTableWithCondition");
    }

    @Test
    void generate_referenceGivenKeyViaTables_shouldCreateJoinViaTablesThenKey() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("referenceGivenKeyViaTables");
    }

    @Test
    void generate_referenceGivenKeyViaTableWithConditionAndTableWithKey_shouldCreateJoinViaTablesThenKey() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("referenceGivenKeyViaTableWithConditionAndTableWithKey");
    }

    @Test
    void generate_whenMultipleReferencesForSameType_shouldCreateUniqueAliases() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("multipleAliasesForSameType");
    }

    @Test
    void generate_queryWithoutPagination_shouldCreateQueryAndQueryResolverWithoutPaginationSupport() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithoutPagination");
    }

    @Test
    void generate_queryThatReturnsInterface_shouldCreateResolver() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryReturningInterface");
        assertThat(getLogMessagesWithLevelWarn()).isEmpty();
    }

    @Test
        /* "meta test" that verifies that all directives defined in the production graphql schema are also used
         and thus tested in the most exhaustive test schema.
         */
    void allDefinedDirectivesAreInUseAndTested() throws IOException {
        String testSchemaPath = getSourceTestPath() + "allDefinedDirectivesInUse/schema.graphqls";
        String testSchema = String.join("\n", TestCommon.readFileAsStrings(Paths.get(testSchemaPath)));

        var mutationDirectives = Set.of("service", "record", "mutationType", "error");
        Stream
                .of(GenerationDirective.values())  // Changed test to only check directives used for code generation.
                .map(GenerationDirective::getName)
                .filter(it -> !mutationDirectives.contains(it)) // Skip new mutation stuff, those are tested separately.
                .forEach(directiveName -> assertThat(testSchema).contains(directiveName));
    }
}
