package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No emit-library vocabulary in the model, installed as an allowlisted guard: a fact holding a
 * {@code TypeName} is comparable only through the renderer's equality, and a model type carrying
 * a {@code CodeBlock} is output the core already rendered. The allowlist below enumerates the
 * model files that import {@code no.sikt.graphitron.javapoet} today, which converts a growing
 * surface into a shrinking one: a file outside the list gaining the import fails here, and a
 * listed file shedding its last emit import must leave the list in the same commit, so the list
 * is the migration dial and emptying it is the enforcer.
 *
 * <p>{@code BodyParam} is the proof the model does not need the vocabulary: a sealed hierarchy of
 * pure records with no emit import at all. The target shape for the entries below is the same,
 * FQCN strings (or refs over them) in the model, {@code ClassName.get} at the renderer.
 *
 * <p>For the new packages the same rule is structural rather than allowlisted:
 * {@link PackageImportDirectionTest} bans the emit library from {@code command} and {@code plan}
 * outright.
 */
@UnitTier
class ModelEmitVocabularyGuardTest {

    private static final String EMIT_IMPORT_PREFIX = "import no.sikt.graphitron.javapoet.";

    /**
     * Model files importing the emit library, file names relative to
     * {@code rewrite/model/}. Shrink-only: remove an entry when its file sheds the import;
     * never add one.
     */
    private static final Set<String> KNOWN_OFFENDERS = Set.of(
        "AccessorRef.java",
        "CallParam.java",
        "CallSiteExtraction.java",
        "ChildField.java",
        "ConditionFilter.java",
        "ConflictSite.java",
        "DefaultedSlot.java",
        "DomainReturnType.java",
        "ErrorChannel.java",
        "HelperRef.java",
        "InputRecordShape.java",
        "LifterRef.java",
        "MappingEntry.java",
        "MethodRef.java",
        "OutputField.java",
        "ResolvedContextArg.java",
        "RoutineRef.java",
        "RowsMethodShape.java",
        "ScalarResolution.java",
        "ServiceMethodCall.java",
        "SourceKey.java",
        "TenantScopes.java",
        "TypeNames.java",
        "ValueShape.java"
    );

    @Test
    void modelFilesImportingTheEmitLibraryAreExactlyTheKnownOffenders() throws IOException {
        Path modelRoot = GuardScope.locateRepoRoot()
            .resolve("graphitron/src/main/java/no/sikt/graphitron/rewrite/model");
        var importing = new TreeSet<String>();
        var scanned = new ArrayList<Path>();
        try (Stream<Path> files = Files.walk(modelRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned.add(file);
                boolean importsEmit = Files.readAllLines(file).stream()
                    .anyMatch(line -> line.startsWith(EMIT_IMPORT_PREFIX));
                if (importsEmit) {
                    importing.add(modelRoot.relativize(file).toString());
                }
            }
        }

        var newOffenders = new TreeSet<>(importing);
        newOffenders.removeAll(KNOWN_OFFENDERS);
        assertThat(newOffenders)
            .as("model files importing the emit library that are not on the allowlist; keep the "
                + "vocabulary out of the model (FQCN strings in the model, ClassName.get at the "
                + "renderer) instead of widening the list")
            .isEmpty();

        var cleaned = new TreeSet<>(KNOWN_OFFENDERS);
        cleaned.removeAll(importing);
        assertThat(cleaned)
            .as("allowlist entries whose file no longer imports the emit library; remove them so "
                + "the dial keeps shrinking")
            .isEmpty();

        assertThat(scanned.size())
            .as("model sources scanned (walk must not be vacuous)")
            .isGreaterThan(100);
    }
}
