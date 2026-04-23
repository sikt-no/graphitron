package no.sikt.graphitron.rewrite.platformidfixture;

import java.util.List;

import no.sikt.graphitron.rewrite.platformidfixture.tables.Bar;
import no.sikt.graphitron.rewrite.platformidfixture.tables.MalformedBar;
import no.sikt.graphitron.rewrite.platformidfixture.tables.Qux;

import org.jooq.Catalog;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SchemaImpl;

@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })
public class Public extends SchemaImpl {

    private static final long serialVersionUID = 1L;

    public static final Public PUBLIC = new Public();

    public final Bar BAR = Bar.BAR;
    public final MalformedBar MALFORMED_BAR = MalformedBar.MALFORMED_BAR;
    public final Qux QUX = Qux.QUX;

    private Public() {
        super(DSL.name("public"), null, DSL.comment("platform-id test fixture schema"));
    }

    @Override
    public Catalog getCatalog() {
        return DefaultCatalog.DEFAULT_CATALOG;
    }

    @Override
    public final List<Table<?>> getTables() {
        return List.of(Bar.BAR, MalformedBar.MALFORMED_BAR, Qux.QUX);
    }
}
