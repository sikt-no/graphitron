package no.sikt.graphitron.model.rules;

import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stage's rule as SQL text, which is the stage's own implementation and not model: a file
 * {@code <gatherer>/<stage>/<rule>.sql} under {@value #ROOT} on this module's classpath, read by the
 * stage that runs it and by nothing else. The stage runs the text as a derived table, its columns
 * named as the table the stage writes names them, and writes every column explicitly on both sides,
 * the table aliased to read them: {@code insertInto(T, T.F1, ...).select(r.F1, ...)
 * .from(RuleText.table(gatherer, stage, rule))} with {@code r = T.as(RuleText.ALIAS)}. The text runs
 * as written, never parsed into jOOQ's model.
 *
 * <p>A rule not yet moved is still a view in {@code graphitron-model.sql}; the stage reading it names
 * its generated table as before.
 */
public final class RuleText {

    /** The classpath directory the gatherers' folders live in. */
    public static final String ROOT = "/no/sikt/graphitron/model/";

    /** The alias a rule's derived table carries, which {@code T.as(ALIAS)} reads its columns through. */
    public static final String ALIAS = "r";

    private static final ConcurrentHashMap<String, String> TEXTS = new ConcurrentHashMap<>();

    private RuleText() {}

    /**
     * The rule's text, from its stage's folder.
     *
     * @throws IllegalStateException where the stage's folder holds no such file, a stage naming a
     *         rule it has not written being a defect in this module
     */
    public static String of(String gatherer, String stage, String rule) {
        return TEXTS.computeIfAbsent(gatherer + "/" + stage + "/" + rule + ".sql", RuleText::read);
    }

    /**
     * The rule as a derived table under {@link #ALIAS}. The text is bracketed on lines of its own,
     * so a line comment closing it cannot swallow the bracket.
     */
    public static Table<Record> table(String gatherer, String stage, String rule) {
        return DSL.table("(\n" + of(gatherer, stage, rule) + "\n)").as(ALIAS);
    }

    private static String read(String path) {
        try (InputStream in = RuleText.class.getResourceAsStream(ROOT + path)) {
            if (in == null) {
                throw new IllegalStateException("no rule file " + ROOT + path + " on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + ROOT + path, e);
        }
    }
}
