package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static no.sikt.graphitron.rewrite.GuardScope.locateRepoRoot;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard: the set the decode empties is the set the decode refills.
 *
 * <p>The decode's relations carry no sweep of their own, so the pass empties this graph's rows
 * before refilling them and the two sets have to be the same one. A relation written and not
 * emptied accumulates every reading's rows forever; a relation emptied and not written here is
 * somebody else's, and emptying it deletes rows the pass has already put there, which reads at
 * every consumer as a corpus that never declared the directive.
 *
 * <p><b>Both halves have happened.</b> The list was first taken from one of the two forms the
 * class writes through and silently missed the other, so five relations accumulated. Widening it
 * to everything the class mentions then swept in three it only claims a coordinate in, whose rows
 * a gatherer running earlier in the pass had already written, and they came out empty. A list
 * spelled by hand cannot say which of the two it is; this guard re-derives it and compares.
 *
 * <p>Source text rather than reflection, because the question is about which call sites exist.
 * A write is {@code newRecord} or {@code marker}; {@link no.sikt.graphitron.model.sink.FactSink}'s
 * {@code claim} is neither, gating a coordinate without writing a row. Nothing here counts
 * entries: what keeps the list honest is that it equals what the class does.
 */
@UnitTier
class DecodeClearScopeGuardTest {

    private static final Path DECODE = Path.of("graphitron-model/src/main/java/no/sikt/graphitron"
        + "/model/capture/graphitron/GraphitronFactCapture.java");
    private static final Path ANCHOR = Path.of("graphitron-model/src/main/java/no/sikt/graphitron"
        + "/model/capture/document/GraphitronAnchor.java");

    /** A floor against a scan that matched nothing and would then agree with any list at all. */
    private static final int MIN_WRITTEN_RELATIONS = 30;

    @Test
    @DisplayName("the decode's clear names exactly the relations the decode writes")
    void theClearedSetEqualsTheWrittenSet() throws IOException {
        String decode = Files.readString(locateRepoRoot().resolve(DECODE));
        String body = stripImports(decode);

        Set<String> written = new LinkedHashSet<>();
        for (String table : namesOf("import static no\\.sikt\\.graphitron\\.model\\.Tables\\.(\\w+);",
                decode)) {
            if (Pattern.compile("newRecord\\(" + table + "\\)|marker\\(" + table + "\\b")
                    .matcher(body).find()) {
                written.add(table);
            }
        }
        assertThat(written)
            .as("relations the decode writes; a scan matching none would agree with any list")
            .hasSizeGreaterThanOrEqualTo(MIN_WRITTEN_RELATIONS);

        // What another gatherer owns and sweeps is not this clear's to empty, whoever mentions it.
        Set<String> swept = namesOf("[A-Z][A-Z0-9_]+",
            between(Files.readString(locateRepoRoot().resolve(ANCHOR)),
                "TABLES_TO_SWEEP =", ");"));
        written.removeAll(swept);

        assertThat(namesOf("[A-Z][A-Z0-9_]+", between(decode, "DECODED = List.of(", ");")))
            .as("the relations GraphitronFactCapture.clear empties, against the ones it writes."
                + " A relation written and not emptied keeps every reading's rows; one emptied and"
                + " not written here belongs to a gatherer that ran earlier and comes out empty.")
            .isEqualTo(written);
    }

    private static String stripImports(String source) {
        return source.replaceAll("(?m)^import .*$", "");
    }

    private static String between(String source, String open, String close) {
        int from = source.indexOf(open);
        assertThat(from).as("the declaration this guard reads: " + open).isNotNegative();
        return source.substring(from + open.length(), source.indexOf(close, from));
    }

    private static Set<String> namesOf(String pattern, String source) {
        var names = new LinkedHashSet<String>();
        Matcher matcher = Pattern.compile(pattern).matcher(source);
        while (matcher.find()) {
            names.add(matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group());
        }
        return names;
    }
}
