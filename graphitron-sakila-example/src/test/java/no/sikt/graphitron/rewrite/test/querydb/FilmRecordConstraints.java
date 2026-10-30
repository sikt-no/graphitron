package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
import org.hibernate.validator.cfg.defs.NotBlankDef;
import org.hibernate.validator.cfg.defs.NotNullDef;
import org.hibernate.validator.cfg.defs.NullDef;
import org.hibernate.validator.cfg.defs.SizeDef;
import org.hibernate.validator.spi.cfg.ConstraintMappingContributor;

/**
 * Constraints mapped onto the generated {@link FilmRecord}'s getters, the way a consumer attaches
 * constraints to jOOQ records it does not write by hand. Stands in for jOOQ's
 * {@code <validationAnnotations>}, which the example's catalog build does not enable: the
 * {@code @Size(max = 255)} on {@code title} is what that option generates from film.title's
 * {@code varchar(255)}. {@code @Null} on {@code filmId} is there only to show that a key decoded from
 * a node id is not checked. Registered through {@code META-INF/validation.xml}, which the default
 * {@code Validator} the generated {@code GraphitronContext} bootstraps reads.
 */
public final class FilmRecordConstraints implements ConstraintMappingContributor {

    @Override
    public void createConstraintMappings(ConstraintMappingBuilder builder) {
        builder.addConstraintMapping()
            .type(FilmRecord.class)
                .getter("title")
                    .constraint(new NotBlankDef())
                    .constraint(new SizeDef().max(255))
                .getter("description")
                    .constraint(new NotNullDef())
                .getter("filmId")
                    .constraint(new NullDef());
    }
}
