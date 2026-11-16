package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.Public;

import java.util.List;
import java.util.Locale;

/**
 * The as-written half of the {@code graphitron_} family, by name. The schema that writes a row into
 * every one of them is the fact document {@code defects/entry-family}, its two files under
 * {@code shapes/entry-family}; {@code EntryFamilyCoverageTest} holds it to doing so.
 */
public final class EntryFamilyFixture {

    private EntryFamilyFixture() {}

    /**
     * The as-written half of the {@code graphitron_} family: every relation the directive decode
     * writes, which is every relation whose rows are a function of one document and nothing else.
     *
     * <p>Read off the generated model by suffix rather than listed. The list this replaced was a
     * stand-in for a name: it had to be edited whenever the family grew, and a relation added to
     * neither half slipped quietly out of every gate that read it. Now the name is the
     * classification, which is the whole point of the suffix and is what a reader looking for the
     * anti-join between the two halves needs anyway. What no longer has a mechanical statement here
     * is that a new decode relation gets named right, and that is deliberately somewhere else:
     * {@code EntryNamingGuardTest} holds the decode to naming only suffixed relations, and the
     * declaration pass will say it a second time by giving each relation a declared owner.
     *
     * <p>Only this half is offered. Its complement is every other {@code graphitron_} relation, and
     * that is a bag rather than a half: the stages write most of it, but the
     * {@code graphitron_argmapping_match} view is written by nothing at all, and a suffix cannot
     * tell those apart. A caller wanting the resolved half wants a declared owner, which the
     * declaration pass gives it and a name never could.
     */
    public static List<String> entryRelations() {
        return Public.PUBLIC.getTables().stream()
            .map(table -> table.getName().toLowerCase(Locale.ROOT))
            .filter(name -> name.startsWith("graphitron_") && name.endsWith("_entry"))
            .sorted()
            .toList();
    }
}
