package no.sikt.graphitron.example.server;

import jakarta.inject.Singleton;
import no.sikt.graphql.NodeIdStrategy;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.jooq.Field;
import org.jooq.SelectField;

@Singleton
public class SubNodeIdStrategy extends NodeIdStrategy {
}
