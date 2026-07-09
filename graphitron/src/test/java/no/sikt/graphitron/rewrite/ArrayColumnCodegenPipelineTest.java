package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * R446 regression pin: code generation must not crash on an array-typed column that flows through
 * the {@code SourceKey.Wrap.TableRecord} key-extraction path.
 *
 * <p>{@code ArrayHolder} maps the {@code array_holder} table, whose row carries a
 * {@code flags boolean[]} column (jOOQ {@code Field<Boolean[]>}, whose {@code getType().getName()}
 * is the binary descriptor {@code [Ljava.lang.Boolean;}). Its {@code rank} child is a typed-record
 * {@code @service} ({@code getArrayHolderRankByRecord(Set<ArrayHolderRecord>)}), so its key wrap is
 * {@code SourceKey.Wrap.TableRecord}: the generated service datafetcher's key extraction
 * reconstructs the full {@code array_holder} row per column via {@code TableRef.allColumns()}
 * ({@code GeneratorUtils.buildKeyExtraction}, the R436 arm). Before the catalog-boundary type-lift
 * that reconstruction called {@code ClassName.bestGuess("[Ljava.lang.Boolean;")} while building the
 * emitted {@code $T.class} argument and aborted with
 * {@code IllegalArgumentException: couldn't make a guess for [Ljava.lang.Boolean;}.
 *
 * <p>The fetcher generator ({@code TypeFetcherGenerator}, not the type-class generator) is the one
 * that reaches {@code buildKeyExtraction}, so this drives it directly. The assertion is behavioural
 * (generation completes and emits the fetcher class), not a code-string match on the emitted
 * {@code Boolean[].class} argument; the emitted-string form is pinned instead by the
 * {@code ArrayColumnTypeDecodeTest} boundary decode (columnType renders {@code java.lang.Boolean[]},
 * never the raw descriptor).
 */
@PipelineTier
class ArrayColumnCodegenPipelineTest {

    private static final String SDL = """
        type ArrayHolder @table(name: "array_holder") {
            id: Int @field(name: "id")
            rank: Int @service(
                service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getArrayHolderRankByRecord"}
            )
        }
        type Query { arrayHolder: ArrayHolder }
        """;

    @Test
    void tableRecordKeyExtraction_overArrayColumnRow_emitsWithoutBestGuessCrash() {
        assertThatCode(() ->
            TypeFetcherGenerator.generate(TestSchemaHelper.buildSchema(SDL), DEFAULT_OUTPUT_PACKAGE))
            .as("full-row key reconstruction over an array-typed column must not crash ClassName.bestGuess")
            .doesNotThrowAnyException();

        var fetchers = TypeFetcherGenerator.generate(TestSchemaHelper.buildSchema(SDL), DEFAULT_OUTPUT_PACKAGE);
        assertThat(fetchers)
            .as("the ArrayHolder datafetcher class emits despite the parent row carrying array columns")
            .anyMatch(t -> t.name().contains("ArrayHolder"));
    }
}
