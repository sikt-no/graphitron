package no.sikt.graphitron.rewrite.platformidfixture;

import no.sikt.graphitron.rewrite.platformidfixture.tables.Bar;
import no.sikt.graphitron.rewrite.platformidfixture.tables.MalformedBar;
import no.sikt.graphitron.rewrite.platformidfixture.tables.Qux;

/**
 * Convenience accessor matching the stock jOOQ generator output. {@code JooqCatalog} reflects on
 * the {@code Tables} class in the schema's package to enumerate public static {@code Table} fields.
 */
public class Tables {

    public static final Bar BAR = Bar.BAR;
    public static final MalformedBar MALFORMED_BAR = MalformedBar.MALFORMED_BAR;
    public static final Qux QUX = Qux.QUX;
}
