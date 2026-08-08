-- The graphitron fact schema: the base relations the generator's capture loads fill.
--
-- This resource is the single source of the model. jOOQ codegen boots an in-memory H2 store
-- from this file at build time and generates the compile-time surface from its live metadata;
-- the run-time bootstrap executes the same file to open the store a generator run captures
-- into. Nothing generated from it is committed, and no persisted state of record exists, so
-- editing this file and following the compiler is the whole schema-change procedure.
--
-- Conventions (the roadmap item that introduced the schema states them in full): snake_case
-- throughout; natural, composite, identity-carrying keys; a FOREIGN KEY only where the walk
-- writes the child while standing on the parent, never on a reference the author spells by
-- name; every table and column carries a COMMENT ON so INFORMATION_SCHEMA and the generated
-- Javadoc are self-describing; closed taxonomies are CHECK constraints; VARCHAR is unbounded;
-- source order is an explicit ordinal column.
--
-- Picking a prefix for a new relation. Five families, each named for whose vocabulary the row
-- is written in, never for its reader or its role. graphql_ is reserved for generic GraphQL: a
-- row any SDL reader could produce from the document without knowing graphitron exists, which
-- is every declaration, every directive definition, and every directive application including
-- graphitron's own. graphitron_ is what graphitron makes of that document: the decoded
-- directives, and the provenance of the rows macro expansion mints. sql_ is what the consumer's
-- database declares, read through jOOQ's generated model; jvm_ is what the classfiles on the
-- compile classpath declare. Naming a family for its reader (jooq_) or a presumed role
-- (extension_) is what these two replace: jOOQ defines neither table nor column nor foreign
-- key, and an ObjectMapper on the classpath extends nothing yet still earns a row. store_ is
-- the store's own record of what it read and what it was built from, the one family whose rows
-- are not a transcription of anything outside.
--
-- The SDL strata stack, graphql_ under graphitron_ under a third name, intent_, held in
-- reserve. A graphitron_ row is still a transcription: it says what a directive application
-- spelled, in graphitron's vocabulary instead of the document's. intent_ is for what gets
-- derived on top of that, once something resolves and combines those readings into what the
-- generator will actually do. No relation here fills that layer, and none should acquire the
-- prefix by drifting into it; a new derived stratum is its own change.
--
-- The graphql_ family is therefore a total transcription, with no hole where graphitron's
-- namespace was. Whether an application survives into the emitted schema is a namespace query
-- over graphql_directive at emission time, not something capture decides by choosing a table,
-- and a directive that is both re-emitted and decoded (federation's @key) is simply a row in
-- each family rather than a special case.

-- ==== SDL existence facts =========================================================
-- One row per element the SDL declares. Capture is total: built-in scalars, @oneOf, federation
-- definitions arriving via @link, and user-authored directives are ordinary rows. Source
-- positions follow the 1-based graphql-java convention and are NULL only for engine-provided
-- elements no SDL line declares (built-in scalars). Elements contributed by the bundled
-- directives.graphqls are stamped with that resource name as source_name (for a type, the
-- stamp sits on its declaration rows); consumers wanting user-authored declarations filter on
-- it, as CatalogBuilder.projectTypeDefinitionLocations does today.
CREATE TABLE graphql_type (
  type_name     VARCHAR NOT NULL,
  kind          VARCHAR NOT NULL,
  description   VARCHAR,
  PRIMARY KEY (type_name),
  CHECK (kind IN ('OBJECT', 'INTERFACE', 'UNION', 'ENUM', 'INPUT_OBJECT', 'SCALAR'))
);
COMMENT ON TABLE graphql_type IS 'A named type is declared or extended in the schema; this row is the name''s existence, written by capture from whichever site it meets first (macro- contributed sites included), and graphql_type_declaration carries every site. The declared-or-extended reading is load-bearing: it is what makes the site rows'' FK structural (capture writes this row before any site row), and on a base-less extension chain (an author error a detection reports) the row still exists, anchored by the extension sites.';
COMMENT ON COLUMN graphql_type.type_name IS 'the GraphQL type name; the coordinate every other SDL fact hangs off';
COMMENT ON COLUMN graphql_type.kind IS 'the first declaration site''s form in merge order (the base definition''s, on a well-formed schema)';
COMMENT ON COLUMN graphql_type.description IS 'SDL description string; net-new as a persisted fact (today read live off retained graphql-java objects). Extensions cannot carry descriptions, so this is the base definition''s when one exists';

CREATE TABLE graphql_type_declaration (
  type_name     VARCHAR NOT NULL,
  source_name   VARCHAR NOT NULL,
  source_line   INT     NOT NULL,
  source_column INT     NOT NULL,
  merge_ordinal INT     NOT NULL,
  is_extension  BOOLEAN NOT NULL,
  kind          VARCHAR NOT NULL,
  PRIMARY KEY (type_name, source_name, source_line, source_column),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  CHECK (kind IN ('OBJECT', 'INTERFACE', 'UNION', 'ENUM', 'INPUT_OBJECT', 'SCALAR'))
);
COMMENT ON TABLE graphql_type_declaration IS 'A declaration site of a type: the base definition or one extension. All five extension kinds are live today, so a type''s effective shape may be assembled from several files; this relation records who contributed what and indexes the incremental-refresh unit ("which types does this file touch"). Engine-provided types (built-in scalars) have no declaration rows.';
COMMENT ON COLUMN graphql_type_declaration.type_name IS 'the type this site declares or extends';
COMMENT ON COLUMN graphql_type_declaration.source_name IS 'the site''s file; a site is a syntactic occurrence, so its location is its identity';
COMMENT ON COLUMN graphql_type_declaration.source_line IS 'line of the site, 1-based';
COMMENT ON COLUMN graphql_type_declaration.source_column IS 'in the key because a line does not identify a site: two extensions of one type can share a line in minified SDL';
COMMENT ON COLUMN graphql_type_declaration.merge_ordinal IS 'capture-assigned position in merge order: the base definition, then extensions in document order; on a base-less chain the first extension holds 0. Dense per type (a gate), and the order behind every element ordinal';
COMMENT ON COLUMN graphql_type_declaration.is_extension IS 'FALSE exactly at merge_ordinal 0 on a well-formed schema; a base-less extension chain is an author error a detection reports, never a constraint';
COMMENT ON COLUMN graphql_type_declaration.kind IS 'the declaration form written at this site; a mismatch against the type row''s kind is a detection';

CREATE TABLE graphql_field (
  type_name           VARCHAR NOT NULL,
  field_name          VARCHAR NOT NULL,
  ordinal             INT     NOT NULL,
  declaration_line    INT     NOT NULL,
  declaration_column  INT     NOT NULL,
  type_sdl          VARCHAR NOT NULL,
  named_type        VARCHAR NOT NULL,
  non_null          BOOLEAN NOT NULL,
  is_list           BOOLEAN NOT NULL,
  item_non_null     BOOLEAN,
  default_value_sdl VARCHAR,
  description       VARCHAR,
  source_name       VARCHAR NOT NULL,
  source_line       INT,
  source_column     INT,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column),
  CHECK (is_list OR item_non_null IS NULL)
);
COMMENT ON TABLE graphql_field IS 'A field exists at a coordinate. OBJECT and INTERFACE parents make it an output field, INPUT_OBJECT parents an input field; the join decides.';
COMMENT ON COLUMN graphql_field.type_name IS 'owning type';
COMMENT ON COLUMN graphql_field.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphql_field.ordinal IS 'order in the effective type: base declaration, then extensions in document order (capture merges them from the registry)';
COMMENT ON COLUMN graphql_field.declaration_line IS 'the contributing declaration site, keyed with this row''s own source_name (an authored row sits lexically inside its site; a synthesized row shares its synthesized site''s inherited position)';
COMMENT ON COLUMN graphql_field.declaration_column IS 'the site key''s fourth part, as on graphql_type_declaration';
COMMENT ON COLUMN graphql_field.type_sdl IS 'the rendered type expression, e.g. ''[Film!]!''; authoritative for wrapping fidelity';
COMMENT ON COLUMN graphql_field.named_type IS 'the named type the expression bottoms out in; author-spelled, no FK, integrity is a detection';
COMMENT ON COLUMN graphql_field.non_null IS 'outermost non-null wrapper present';
COMMENT ON COLUMN graphql_field.is_list IS 'a list wrapper is present';
COMMENT ON COLUMN graphql_field.item_non_null IS 'item-level non-null when is_list; NULL otherwise';
COMMENT ON COLUMN graphql_field.default_value_sdl IS 'rendered default value; input-object fields only';
COMMENT ON COLUMN graphql_field.description IS 'SDL description string, when the author wrote one';
COMMENT ON COLUMN graphql_field.source_name IS 'every field row comes from an SDL site (built-in scalars declare none), and a NULL here would silently disable the site FK under MATCH SIMPLE';
COMMENT ON COLUMN graphql_field.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphql_field.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphql_argument (
  type_name         VARCHAR NOT NULL,
  field_name        VARCHAR NOT NULL,
  argument_name     VARCHAR NOT NULL,
  ordinal           INT     NOT NULL,
  type_sdl          VARCHAR NOT NULL,
  named_type        VARCHAR NOT NULL,
  non_null          BOOLEAN NOT NULL,
  is_list           BOOLEAN NOT NULL,
  item_non_null     BOOLEAN,
  default_value_sdl VARCHAR,
  description       VARCHAR,
  source_name       VARCHAR,
  source_line       INT,
  source_column     INT,
  PRIMARY KEY (type_name, field_name, argument_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name),
  CHECK (is_list OR item_non_null IS NULL)
);
COMMENT ON TABLE graphql_argument IS 'An argument exists on a field. Net-new coordinate: today arguments are classified per-field and mostly projected away, with no location kept.';
COMMENT ON COLUMN graphql_argument.type_name IS 'owning type of the field the argument sits on';
COMMENT ON COLUMN graphql_argument.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphql_argument.argument_name IS 'the argument name within the owning field';
COMMENT ON COLUMN graphql_argument.ordinal IS 'declaration order within the field';
COMMENT ON COLUMN graphql_argument.type_sdl IS 'rendered type expression, as on graphql_field';
COMMENT ON COLUMN graphql_argument.named_type IS 'the named type the expression bottoms out in; author-spelled, no FK';
COMMENT ON COLUMN graphql_argument.non_null IS 'outermost non-null wrapper present';
COMMENT ON COLUMN graphql_argument.is_list IS 'a list wrapper is present';
COMMENT ON COLUMN graphql_argument.item_non_null IS 'item-level non-null when is_list; NULL otherwise';
COMMENT ON COLUMN graphql_argument.default_value_sdl IS 'rendered default value, when declared';
COMMENT ON COLUMN graphql_argument.description IS 'SDL description string, when the author wrote one';
COMMENT ON COLUMN graphql_argument.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphql_argument.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphql_argument.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphql_enum_value (
  type_name           VARCHAR NOT NULL,
  value_name          VARCHAR NOT NULL,
  ordinal             INT     NOT NULL,
  declaration_line    INT     NOT NULL,
  declaration_column  INT     NOT NULL,
  description         VARCHAR,
  source_name         VARCHAR NOT NULL,
  source_line         INT,
  source_column       INT,
  PRIMARY KEY (type_name, value_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphql_enum_value IS 'An enum declares a value. Net-new coordinate; deprecation is not a column because @deprecated is an ordinary applied directive.';
COMMENT ON COLUMN graphql_enum_value.type_name IS 'the owning ENUM type';
COMMENT ON COLUMN graphql_enum_value.value_name IS 'the enum value name within the owning enum type';
COMMENT ON COLUMN graphql_enum_value.ordinal IS 'order in the effective enum: base declaration, then extensions';
COMMENT ON COLUMN graphql_enum_value.declaration_line IS 'the contributing site, as on graphql_field';
COMMENT ON COLUMN graphql_enum_value.declaration_column IS 'column of the contributing declaration site, the site key''s fourth part';
COMMENT ON COLUMN graphql_enum_value.description IS 'SDL description string, when the author wrote one';
COMMENT ON COLUMN graphql_enum_value.source_name IS 'NOT NULL for the same reason as on graphql_field: half of the site FK';
COMMENT ON COLUMN graphql_enum_value.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphql_enum_value.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphql_union_member (
  union_name          VARCHAR NOT NULL,
  member_type_name    VARCHAR NOT NULL,
  ordinal             INT     NOT NULL,
  declaration_line    INT     NOT NULL,
  declaration_column  INT     NOT NULL,
  source_name         VARCHAR NOT NULL,
  source_line         INT,
  source_column       INT,
  PRIMARY KEY (union_name, member_type_name),
  FOREIGN KEY (union_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (union_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphql_union_member IS 'A union lists a member type.';
COMMENT ON COLUMN graphql_union_member.union_name IS 'the UNION type listing the member';
COMMENT ON COLUMN graphql_union_member.member_type_name IS 'the member type as the union spelled it; author-spelled, no FK';
COMMENT ON COLUMN graphql_union_member.ordinal IS 'position in the effective member list';
COMMENT ON COLUMN graphql_union_member.declaration_line IS 'the contributing site, as on graphql_field';
COMMENT ON COLUMN graphql_union_member.declaration_column IS 'column of the contributing declaration site, the site key''s fourth part';
COMMENT ON COLUMN graphql_union_member.source_name IS 'position of the member token itself; NOT NULL as on graphql_field';
COMMENT ON COLUMN graphql_union_member.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphql_union_member.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphql_implements (
  type_name           VARCHAR NOT NULL,
  interface_name      VARCHAR NOT NULL,
  declaration_line    INT     NOT NULL,
  declaration_column  INT     NOT NULL,
  source_name         VARCHAR NOT NULL,
  source_line         INT,
  source_column       INT,
  PRIMARY KEY (type_name, interface_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphql_implements IS 'A type declares that it implements an interface. Stored in declaration direction; today''s model keeps only the inverted interface-to-participants list and reads this edge live off graphql-java.';
COMMENT ON COLUMN graphql_implements.type_name IS 'the implementing OBJECT or INTERFACE';
COMMENT ON COLUMN graphql_implements.interface_name IS 'the interface as the implementing type spelled it; author-spelled, no FK';
COMMENT ON COLUMN graphql_implements.declaration_line IS 'the contributing site, as on graphql_field';
COMMENT ON COLUMN graphql_implements.declaration_column IS 'column of the contributing declaration site, the site key''s fourth part';
COMMENT ON COLUMN graphql_implements.source_name IS 'position of the interface token itself; NOT NULL as on graphql_field';
COMMENT ON COLUMN graphql_implements.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphql_implements.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphql_root_operation (
  operation     VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (operation),
  CHECK (operation IN ('QUERY', 'MUTATION', 'SUBSCRIPTION'))
);
COMMENT ON TABLE graphql_root_operation IS 'The schema definition names a root operation type. These rows are the seeds the reachability derivation grows from. The binding is an author-spelled reference, so its dangling case mints a located diagnostic; the position columns are what it locates from. (A double binding cannot reach capture: a schema extension re-binding an operation throws at parse.)';
COMMENT ON COLUMN graphql_root_operation.operation IS 'which root slot';
COMMENT ON COLUMN graphql_root_operation.type_name IS 'the object type serving it';
COMMENT ON COLUMN graphql_root_operation.source_name IS 'position of the binding inside the schema { } block; all three NULL exactly when the binding is the name-convention default no SDL line spells';
COMMENT ON COLUMN graphql_root_operation.source_line IS 'line of the binding; NULL with the siblings when the binding is the name-convention default';
COMMENT ON COLUMN graphql_root_operation.source_column IS 'column of the binding; NULL with the siblings when the binding is the name-convention default';

CREATE TABLE graphql_duplicate_declaration (
  source_name   VARCHAR NOT NULL,
  source_line   INT     NOT NULL,
  source_column INT     NOT NULL,
  element_kind  VARCHAR NOT NULL,
  coordinate    VARCHAR NOT NULL,
  value_sdl     VARCHAR NOT NULL,
  PRIMARY KEY (source_name, source_line, source_column),
  CHECK (element_kind IN ('TYPE', 'FIELD', 'ARGUMENT', 'ENUM_VALUE',
                          'UNION_MEMBER', 'IMPLEMENTS', 'DIRECTIVE_APPLICATION',
                          'DIRECTIVE_LOCATION', 'DIRECTIVE_ARGUMENT'))
);
COMMENT ON TABLE graphql_duplicate_declaration IS 'The duplicate-declaration overflow, sibling of the semantic stratum''s undecoded-argument relation. The registry retains element-level duplicates without error (a field declared twice in one body or re-declared by an extension, a repeated argument, enum value, union member, or implements entry, a second application of a single-application graphitron directive, a repeated location or formal argument in a directive definition), so every element-level natural key in this schema is author-reachable. Capture is first-wins in merge order; the losing occurrence records here, rendered and located, so no authored text is lost and the duplicate-declaration detection has its row. Empty while assembly runs upstream (assembly rejects these schemas first). A second base definition, of a type or of a directive, is the duplication family the registry itself rejects at parse, so the TYPE kind is reachable only through the LSP''s per-file fragment path.';
COMMENT ON COLUMN graphql_duplicate_declaration.source_name IS 'the losing occurrence''s own position identifies the row';
COMMENT ON COLUMN graphql_duplicate_declaration.source_line IS 'line of the losing occurrence';
COMMENT ON COLUMN graphql_duplicate_declaration.source_column IS 'column of the losing occurrence';
COMMENT ON COLUMN graphql_duplicate_declaration.element_kind IS 'which family''s natural key collided';
COMMENT ON COLUMN graphql_duplicate_declaration.coordinate IS 'the colliding key, rendered (e.g. ''Q.title'')';
COMMENT ON COLUMN graphql_duplicate_declaration.value_sdl IS 'the losing occurrence as written, rendered from the AST; children ride inside it, so a losing field keeps its arguments';


-- ==== Directive definitions =======================================================
-- The definition side of the directive surface: what a directive is, where it may sit, what
-- arguments it declares. Capture is total over the registry, so user-authored, spec built-in,
-- federation-imported, and graphitron's own bundled definitions are all rows. An emitter
-- re-declares the first three and strips the fourth, and it tells them apart the same way
-- anything else does, by reading source_name; the family does not encode the answer.
-- Totality is what makes every application's directive_name resolve to a definition, so
-- reading a repeatable flag or an argument default is one join rather than a namespace case.
CREATE TABLE graphql_directive (
  directive_name VARCHAR NOT NULL,
  repeatable     BOOLEAN NOT NULL,
  description    VARCHAR,
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  PRIMARY KEY (directive_name)
);
COMMENT ON TABLE graphql_directive IS 'A directive is defined.';
COMMENT ON COLUMN graphql_directive.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_directive.repeatable IS 'whether the definition says ''repeatable''; governs the ordinal on applications';
COMMENT ON COLUMN graphql_directive.description IS 'SDL description string, when the author wrote one';
COMMENT ON COLUMN graphql_directive.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphql_directive.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphql_directive.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphql_directive_location (
  directive_name VARCHAR NOT NULL,
  location       VARCHAR NOT NULL,
  PRIMARY KEY (directive_name, location),
  FOREIGN KEY (directive_name) REFERENCES graphql_directive (directive_name)
);
COMMENT ON TABLE graphql_directive_location IS 'A directive definition names a permitted location.';
COMMENT ON COLUMN graphql_directive_location.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_directive_location.location IS 'introspection location name, e.g. FIELD_DEFINITION, INPUT_FIELD_DEFINITION';

CREATE TABLE graphql_directive_argument (
  directive_name    VARCHAR NOT NULL,
  argument_name     VARCHAR NOT NULL,
  ordinal           INT     NOT NULL,
  type_sdl          VARCHAR NOT NULL,
  named_type        VARCHAR NOT NULL,
  non_null          BOOLEAN NOT NULL,
  is_list           BOOLEAN NOT NULL,
  item_non_null     BOOLEAN,
  default_value_sdl VARCHAR,
  description       VARCHAR,
  source_name       VARCHAR,
  source_line       INT,
  source_column     INT,
  PRIMARY KEY (directive_name, argument_name),
  FOREIGN KEY (directive_name) REFERENCES graphql_directive (directive_name),
  CHECK (is_list OR item_non_null IS NULL)
);
COMMENT ON TABLE graphql_directive_argument IS 'A directive definition declares a formal argument. Carries the same wrapping decode as graphql_field, so list-ness of a directive argument is a column read, not a string parse.';
COMMENT ON COLUMN graphql_directive_argument.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_directive_argument.argument_name IS 'the argument name within the owning field';
COMMENT ON COLUMN graphql_directive_argument.ordinal IS 'declaration order in the definition';
COMMENT ON COLUMN graphql_directive_argument.type_sdl IS 'rendered argument type, e.g. ''[ReferenceElement!]!''';
COMMENT ON COLUMN graphql_directive_argument.named_type IS 'the named type the expression bottoms out in; author-spelled, no FK';
COMMENT ON COLUMN graphql_directive_argument.non_null IS 'outermost non-null wrapper present';
COMMENT ON COLUMN graphql_directive_argument.is_list IS 'a list wrapper is present';
COMMENT ON COLUMN graphql_directive_argument.item_non_null IS 'item-level non-null when is_list; NULL otherwise';
COMMENT ON COLUMN graphql_directive_argument.default_value_sdl IS 'rendered default; the value an application inherits when it omits the argument';
COMMENT ON COLUMN graphql_directive_argument.description IS 'SDL description string, when the author wrote one';
COMMENT ON COLUMN graphql_directive_argument.source_name IS 'position of the formal argument in the definition';
COMMENT ON COLUMN graphql_directive_argument.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphql_directive_argument.source_column IS 'source column, 1-based per the graphql-java convention';


-- ==== Directive applications ======================================================
-- One row per application the author wrote, one child row per argument the author passed.
-- Values are the rendered SDL literal, so an application is legible here without knowing what
-- the directive means. Capture is total: graphitron's own applications are rows like any
-- other, and the ones that carry meaning additionally get a decoded row in the graphitron_
-- family. A directive that is both re-emitted and decoded (federation's @key) is just an
-- application with both projections rather than a special case.
CREATE TABLE graphql_schema_directive (
  directive_name VARCHAR NOT NULL,
  ordinal        INT     NOT NULL,
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  PRIMARY KEY (directive_name, ordinal)
);
COMMENT ON TABLE graphql_schema_directive IS 'A directive is applied to the schema definition (@link lives here).';
COMMENT ON COLUMN graphql_schema_directive.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_schema_directive.ordinal IS '0 unless the directive is repeatable; repeats number in document order';
COMMENT ON COLUMN graphql_schema_directive.source_name IS 'position of the application site';
COMMENT ON COLUMN graphql_schema_directive.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphql_schema_directive.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphql_schema_directive_arg (
  directive_name          VARCHAR NOT NULL,
  ordinal                 INT     NOT NULL,
  directive_argument_name VARCHAR NOT NULL,
  value_sdl               VARCHAR NOT NULL,
  PRIMARY KEY (directive_name, ordinal, directive_argument_name),
  FOREIGN KEY (directive_name, ordinal)
    REFERENCES graphql_schema_directive (directive_name, ordinal)
);
COMMENT ON TABLE graphql_schema_directive_arg IS 'An argument the author passed to a schema-level application.';
COMMENT ON COLUMN graphql_schema_directive_arg.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_schema_directive_arg.ordinal IS 'the owning application''s ordinal';
COMMENT ON COLUMN graphql_schema_directive_arg.directive_argument_name IS 'the definition''s formal argument this value binds';
COMMENT ON COLUMN graphql_schema_directive_arg.value_sdl IS 'the value as written, rendered from the AST; omitted arguments are absent rows';

CREATE TABLE graphql_type_directive (
  type_name           VARCHAR NOT NULL,
  directive_name      VARCHAR NOT NULL,
  ordinal             INT     NOT NULL,
  declaration_line    INT     NOT NULL,
  declaration_column  INT     NOT NULL,
  source_name         VARCHAR NOT NULL,
  source_line         INT,
  source_column       INT,
  PRIMARY KEY (type_name, directive_name, ordinal),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphql_type_directive IS 'A directive is applied to a type (OBJECT, INTERFACE, UNION, ENUM, INPUT_OBJECT, or SCALAR; the parent kind is a join away).';
COMMENT ON COLUMN graphql_type_directive.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphql_type_directive.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_type_directive.ordinal IS 'as on graphql_schema_directive; federation''s @key repeats here';
COMMENT ON COLUMN graphql_type_directive.declaration_line IS 'the applying site (extensions apply type directives too); a synthesized @key hangs off the type''s causing authored site, per its own provenance relation below';
COMMENT ON COLUMN graphql_type_directive.declaration_column IS 'column of the contributing declaration site, the site key''s fourth part';
COMMENT ON COLUMN graphql_type_directive.source_name IS 'NOT NULL as on graphql_field: half of the site FK';
COMMENT ON COLUMN graphql_type_directive.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphql_type_directive.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphql_type_directive_arg (
  type_name               VARCHAR NOT NULL,
  directive_name          VARCHAR NOT NULL,
  ordinal                 INT     NOT NULL,
  directive_argument_name VARCHAR NOT NULL,
  value_sdl               VARCHAR NOT NULL,
  PRIMARY KEY (type_name, directive_name, ordinal, directive_argument_name),
  FOREIGN KEY (type_name, directive_name, ordinal)
    REFERENCES graphql_type_directive (type_name, directive_name, ordinal)
);
COMMENT ON TABLE graphql_type_directive_arg IS 'An argument the author passed to a type-level application.';
COMMENT ON COLUMN graphql_type_directive_arg.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphql_type_directive_arg.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_type_directive_arg.ordinal IS 'the owning application''s ordinal';
COMMENT ON COLUMN graphql_type_directive_arg.directive_argument_name IS 'the definition''s formal argument this value binds';
COMMENT ON COLUMN graphql_type_directive_arg.value_sdl IS 'the value as written, rendered from the AST';

CREATE TABLE graphql_field_directive (
  type_name      VARCHAR NOT NULL,
  field_name     VARCHAR NOT NULL,
  directive_name VARCHAR NOT NULL,
  ordinal        INT     NOT NULL,
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  PRIMARY KEY (type_name, field_name, directive_name, ordinal),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);
COMMENT ON TABLE graphql_field_directive IS 'A directive is applied to a field (output or input-object; the parent type''s kind decides which SDL location this was).';
COMMENT ON COLUMN graphql_field_directive.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphql_field_directive.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphql_field_directive.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_field_directive.ordinal IS '0 unless the directive is repeatable; repeats number in document order';
COMMENT ON COLUMN graphql_field_directive.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphql_field_directive.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphql_field_directive.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphql_field_directive_arg (
  type_name               VARCHAR NOT NULL,
  field_name              VARCHAR NOT NULL,
  directive_name          VARCHAR NOT NULL,
  ordinal                 INT     NOT NULL,
  directive_argument_name VARCHAR NOT NULL,
  value_sdl               VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, directive_name, ordinal, directive_argument_name),
  FOREIGN KEY (type_name, field_name, directive_name, ordinal)
    REFERENCES graphql_field_directive (type_name, field_name, directive_name, ordinal)
);
COMMENT ON TABLE graphql_field_directive_arg IS 'An argument the author passed to a field-level application.';
COMMENT ON COLUMN graphql_field_directive_arg.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphql_field_directive_arg.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphql_field_directive_arg.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_field_directive_arg.ordinal IS 'the owning application''s ordinal';
COMMENT ON COLUMN graphql_field_directive_arg.directive_argument_name IS 'the definition''s formal argument this value binds';
COMMENT ON COLUMN graphql_field_directive_arg.value_sdl IS 'the value as written, rendered from the AST';

CREATE TABLE graphql_argument_directive (
  type_name      VARCHAR NOT NULL,
  field_name     VARCHAR NOT NULL,
  argument_name  VARCHAR NOT NULL,
  directive_name VARCHAR NOT NULL,
  ordinal        INT     NOT NULL,
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  PRIMARY KEY (type_name, field_name, argument_name, directive_name, ordinal),
  FOREIGN KEY (type_name, field_name, argument_name)
    REFERENCES graphql_argument (type_name, field_name, argument_name)
);
COMMENT ON TABLE graphql_argument_directive IS 'A directive is applied to a field argument (ARGUMENT_DEFINITION site).';
COMMENT ON COLUMN graphql_argument_directive.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphql_argument_directive.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphql_argument_directive.argument_name IS 'the SDL argument the directive sits on';
COMMENT ON COLUMN graphql_argument_directive.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_argument_directive.ordinal IS 'as on graphql_field_directive';
COMMENT ON COLUMN graphql_argument_directive.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphql_argument_directive.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphql_argument_directive.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphql_argument_directive_arg (
  type_name               VARCHAR NOT NULL,
  field_name              VARCHAR NOT NULL,
  argument_name           VARCHAR NOT NULL,
  directive_name          VARCHAR NOT NULL,
  ordinal                 INT     NOT NULL,
  directive_argument_name VARCHAR NOT NULL,
  value_sdl               VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, argument_name, directive_name, ordinal, directive_argument_name),
  FOREIGN KEY (type_name, field_name, argument_name, directive_name, ordinal)
    REFERENCES graphql_argument_directive (type_name, field_name, argument_name, directive_name, ordinal)
);
COMMENT ON TABLE graphql_argument_directive_arg IS 'An argument the author passed to an argument-level application.';
COMMENT ON COLUMN graphql_argument_directive_arg.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphql_argument_directive_arg.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphql_argument_directive_arg.argument_name IS 'the argument name within the owning field';
COMMENT ON COLUMN graphql_argument_directive_arg.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_argument_directive_arg.ordinal IS 'the owning application''s ordinal';
COMMENT ON COLUMN graphql_argument_directive_arg.directive_argument_name IS 'the definition''s formal argument this value binds';
COMMENT ON COLUMN graphql_argument_directive_arg.value_sdl IS 'the value as written, rendered from the AST';

CREATE TABLE graphql_enum_value_directive (
  type_name      VARCHAR NOT NULL,
  value_name     VARCHAR NOT NULL,
  directive_name VARCHAR NOT NULL,
  ordinal        INT     NOT NULL,
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  PRIMARY KEY (type_name, value_name, directive_name, ordinal),
  FOREIGN KEY (type_name, value_name) REFERENCES graphql_enum_value (type_name, value_name)
);
COMMENT ON TABLE graphql_enum_value_directive IS 'A directive is applied to an enum value (@deprecated lives here, and so does the graphitron enum-value inventory, which is additionally decoded).';
COMMENT ON COLUMN graphql_enum_value_directive.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphql_enum_value_directive.value_name IS 'the enum value name within the owning enum type';
COMMENT ON COLUMN graphql_enum_value_directive.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_enum_value_directive.ordinal IS 'as on graphql_schema_directive';
COMMENT ON COLUMN graphql_enum_value_directive.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphql_enum_value_directive.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphql_enum_value_directive.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphql_enum_value_directive_arg (
  type_name               VARCHAR NOT NULL,
  value_name              VARCHAR NOT NULL,
  directive_name          VARCHAR NOT NULL,
  ordinal                 INT     NOT NULL,
  directive_argument_name VARCHAR NOT NULL,
  value_sdl               VARCHAR NOT NULL,
  PRIMARY KEY (type_name, value_name, directive_name, ordinal, directive_argument_name),
  FOREIGN KEY (type_name, value_name, directive_name, ordinal)
    REFERENCES graphql_enum_value_directive (type_name, value_name, directive_name, ordinal)
);
COMMENT ON TABLE graphql_enum_value_directive_arg IS 'An argument the author passed to an enum-value application.';
COMMENT ON COLUMN graphql_enum_value_directive_arg.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphql_enum_value_directive_arg.value_name IS 'the enum value name within the owning enum type';
COMMENT ON COLUMN graphql_enum_value_directive_arg.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_enum_value_directive_arg.ordinal IS 'the owning application''s ordinal';
COMMENT ON COLUMN graphql_enum_value_directive_arg.directive_argument_name IS 'the definition''s formal argument this value binds';
COMMENT ON COLUMN graphql_enum_value_directive_arg.value_sdl IS 'the value as written, rendered from the AST';

CREATE VIEW graphql_directive_site AS
SELECT 'SCHEMA' AS site_kind, CAST(NULL AS VARCHAR) AS type_name,
       CAST(NULL AS VARCHAR) AS member_name, CAST(NULL AS VARCHAR) AS argument_name,
       directive_name, ordinal, source_name, source_line, source_column
  FROM graphql_schema_directive
UNION ALL
SELECT 'TYPE', type_name, NULL, NULL,
       directive_name, ordinal, source_name, source_line, source_column
  FROM graphql_type_directive
UNION ALL
SELECT 'FIELD', type_name, field_name, NULL,
       directive_name, ordinal, source_name, source_line, source_column
  FROM graphql_field_directive
UNION ALL
SELECT 'ARGUMENT', type_name, field_name, argument_name,
       directive_name, ordinal, source_name, source_line, source_column
  FROM graphql_argument_directive
UNION ALL
SELECT 'ENUM_VALUE', type_name, value_name, NULL,
       directive_name, ordinal, source_name, source_line, source_column
  FROM graphql_enum_value_directive;
COMMENT ON VIEW graphql_directive_site IS 'The one view the DDL ships: every application regardless of site, so a consumer that wants "all applications of @x" reads one relation.';
COMMENT ON COLUMN graphql_directive_site.site_kind IS 'which element family the application sits on; the arm this row came from';
COMMENT ON COLUMN graphql_directive_site.type_name IS 'the owning type, NULL on the schema-level arm';
COMMENT ON COLUMN graphql_directive_site.member_name IS 'the field or enum value the application sits on, NULL where the site has none';
COMMENT ON COLUMN graphql_directive_site.argument_name IS 'the field argument the application sits on, NULL where the site has none';
COMMENT ON COLUMN graphql_directive_site.directive_name IS 'the applied directive name, without the leading @';
COMMENT ON COLUMN graphql_directive_site.ordinal IS '0 unless the directive is repeatable; repeats number in document order';
COMMENT ON COLUMN graphql_directive_site.source_name IS 'the SDL file the application was captured from';
COMMENT ON COLUMN graphql_directive_site.source_line IS 'source line of the application, 1-based';
COMMENT ON COLUMN graphql_directive_site.source_column IS 'source column of the application, 1-based';


-- ==== Semantic stratum: the decoded graphitron and federation inventory ===========
CREATE TABLE graphitron_table (
  type_name        VARCHAR NOT NULL,
  source_name      VARCHAR NOT NULL,
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  table_ref        VARCHAR,
  PRIMARY KEY (type_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphitron_table IS '@table on a type: the author binds the type to a database table. On an INPUT_OBJECT the application is captured like any other; the ignored-and- warned status of that site is a detection.';
COMMENT ON COLUMN graphitron_table.type_name IS 'the OBJECT, INPUT_OBJECT, or INTERFACE carrying @table';
COMMENT ON COLUMN graphitron_table.source_name IS 'the applying declaration site (keyed with the line and column below); doubles as the file of the position columns';
COMMENT ON COLUMN graphitron_table.declaration_line IS 'line of the applying declaration site';
COMMENT ON COLUMN graphitron_table.declaration_column IS 'column of the applying declaration site';
COMMENT ON COLUMN graphitron_table.source_line IS 'the application''s own position';
COMMENT ON COLUMN graphitron_table.source_column IS 'the application''s own column';
COMMENT ON COLUMN graphitron_table.table_ref IS 'the name argument as written (may carry a schema qualifier); NULL when omitted, the type-name fallback is a derivation';

CREATE TABLE graphitron_field_binding (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  name_ref      VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);
COMMENT ON TABLE graphitron_field_binding IS '@field on an output or input-object field: the slot''s bound name. A column, a Java accessor, or a Java member depending on the backing, which is classification''s business; the $source / $errors sigil forms are stored as written, their recognition being a prefix test SQL can express.';
COMMENT ON COLUMN graphitron_field_binding.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_field_binding.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_field_binding.source_name IS 'the application''s own position, here and below';
COMMENT ON COLUMN graphitron_field_binding.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_field_binding.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_field_binding.name_ref IS 'the name argument as written';

CREATE TABLE graphitron_argument_binding (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  name_ref      VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, argument_name),
  FOREIGN KEY (type_name, field_name, argument_name)
    REFERENCES graphql_argument (type_name, field_name, argument_name)
);
COMMENT ON TABLE graphitron_argument_binding IS '@field on an argument: the filter argument''s bound column.';
COMMENT ON COLUMN graphitron_argument_binding.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_argument_binding.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_argument_binding.argument_name IS 'the argument name within the owning field';
COMMENT ON COLUMN graphitron_argument_binding.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_argument_binding.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_argument_binding.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_argument_binding.name_ref IS 'the name argument as written';

CREATE TABLE graphitron_enum_value_binding (
  type_name     VARCHAR NOT NULL,
  value_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  name_ref      VARCHAR NOT NULL,
  PRIMARY KEY (type_name, value_name),
  FOREIGN KEY (type_name, value_name) REFERENCES graphql_enum_value (type_name, value_name)
);
COMMENT ON TABLE graphitron_enum_value_binding IS '@field on an enum value: the database string (or Java constant) the value maps to. The pivot vocabulary decode reads this relation too.';
COMMENT ON COLUMN graphitron_enum_value_binding.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_enum_value_binding.value_name IS 'the enum value name within the owning enum type';
COMMENT ON COLUMN graphitron_enum_value_binding.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_enum_value_binding.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_enum_value_binding.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_enum_value_binding.name_ref IS 'the name argument as written';

CREATE TABLE graphitron_scalar_type (
  type_name        VARCHAR NOT NULL,
  source_name      VARCHAR NOT NULL,
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  scalar_ref       VARCHAR NOT NULL,
  PRIMARY KEY (type_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphitron_scalar_type IS '@scalarType on a scalar: the Java constant backing it. Under registry capture the application is read like any other; the SDL pre-pass the current consumer needs (assembly strips directives off spec built-in redeclarations) dies with the assembled source.';
COMMENT ON COLUMN graphitron_scalar_type.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_scalar_type.source_name IS 'half of the site FK, so NOT NULL; a graphitron application always has an SDL position';
COMMENT ON COLUMN graphitron_scalar_type.declaration_line IS 'line of the contributing declaration site, keyed with source_name';
COMMENT ON COLUMN graphitron_scalar_type.declaration_column IS 'column of the contributing declaration site, the site key''s fourth part';
COMMENT ON COLUMN graphitron_scalar_type.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_scalar_type.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_scalar_type.scalar_ref IS 'the fully-qualified Java constant reference as written';

CREATE TABLE graphitron_enum (
  type_name        VARCHAR NOT NULL,
  source_name      VARCHAR NOT NULL,
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  class_name       VARCHAR,
  method           VARCHAR,
  arg_mapping      VARCHAR,
  PRIMARY KEY (type_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphitron_enum IS '@enum on an enum type. The full ExternalCodeReference is captured as written, though today only arg_mapping is consumed (to reject a non-blank value; the Java binding is derived by reflection and the per-value mapping comes from graphitron_enum_value_binding).';
COMMENT ON COLUMN graphitron_enum.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_enum.source_name IS 'half of the site FK, so NOT NULL; a graphitron application always has an SDL position';
COMMENT ON COLUMN graphitron_enum.declaration_line IS 'line of the contributing declaration site, keyed with source_name';
COMMENT ON COLUMN graphitron_enum.declaration_column IS 'column of the contributing declaration site, the site key''s fourth part';
COMMENT ON COLUMN graphitron_enum.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_enum.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_enum.class_name IS 'enumReference.className as written';
COMMENT ON COLUMN graphitron_enum.method IS 'the Java method name as written';
COMMENT ON COLUMN graphitron_enum.arg_mapping IS 'structurally inert here; raw column only, no pair child';

CREATE TABLE graphitron_field_condition (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  class_name    VARCHAR,
  method        VARCHAR,
  arg_mapping   VARCHAR,
  override      BOOLEAN,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);
COMMENT ON TABLE graphitron_field_condition IS '@condition on a field or input field (shared coordinate; the parent kind decides which SDL site this was).';
COMMENT ON COLUMN graphitron_field_condition.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_field_condition.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_field_condition.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_field_condition.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_field_condition.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_field_condition.class_name IS 'ExternalCodeReference.className as written';
COMMENT ON COLUMN graphitron_field_condition.method IS 'ExternalCodeReference.method as written';
COMMENT ON COLUMN graphitron_field_condition.arg_mapping IS 'ExternalCodeReference.argMapping as written; the pair child below is its decode, the type_sdl-plus-decode pattern';
COMMENT ON COLUMN graphitron_field_condition.override IS 'as written; NULL when omitted (the FALSE default is derivable)';

CREATE TABLE graphitron_field_condition_context_arg (
  type_name  VARCHAR NOT NULL,
  field_name VARCHAR NOT NULL,
  position   INT     NOT NULL,
  name       VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, position),
  FOREIGN KEY (type_name, field_name)
    REFERENCES graphitron_field_condition (type_name, field_name)
);
COMMENT ON TABLE graphitron_field_condition_context_arg IS 'An ordered context argument of a field-site @condition.';
COMMENT ON COLUMN graphitron_field_condition_context_arg.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_field_condition_context_arg.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_field_condition_context_arg.position IS '0-based position in the contextArguments list';
COMMENT ON COLUMN graphitron_field_condition_context_arg.name IS 'the context argument name as written';

CREATE TABLE graphitron_field_condition_arg_mapping_pair (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, position),
  FOREIGN KEY (type_name, field_name)
    REFERENCES graphitron_field_condition (type_name, field_name)
);
COMMENT ON TABLE graphitron_field_condition_arg_mapping_pair IS 'An ordered pair of a field-site @condition''s argMapping. Position-keyed so an author''s duplicate parameter survives for the duplicate detection.';
COMMENT ON COLUMN graphitron_field_condition_arg_mapping_pair.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_field_condition_arg_mapping_pair.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_field_condition_arg_mapping_pair.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_field_condition_arg_mapping_pair.param_name IS 'the Java parameter (left side)';
COMMENT ON COLUMN graphitron_field_condition_arg_mapping_pair.argument_path IS 'the right side as written: a GraphQL argument name or dotted input path';

CREATE TABLE graphitron_argument_condition (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  class_name    VARCHAR,
  method        VARCHAR,
  arg_mapping   VARCHAR,
  override      BOOLEAN,
  PRIMARY KEY (type_name, field_name, argument_name),
  FOREIGN KEY (type_name, field_name, argument_name)
    REFERENCES graphql_argument (type_name, field_name, argument_name)
);
COMMENT ON TABLE graphitron_argument_condition IS '@condition on an argument: the same decode over the three-part coordinate.';
COMMENT ON COLUMN graphitron_argument_condition.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_argument_condition.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_argument_condition.argument_name IS 'the argument name within the owning field';
COMMENT ON COLUMN graphitron_argument_condition.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_argument_condition.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_argument_condition.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_argument_condition.class_name IS 'the fully-qualified Java class name as written';
COMMENT ON COLUMN graphitron_argument_condition.method IS 'the Java method name as written';
COMMENT ON COLUMN graphitron_argument_condition.arg_mapping IS 'the argMapping string as written; the pair child is its decode';
COMMENT ON COLUMN graphitron_argument_condition.override IS 'as written; NULL when omitted (the FALSE default is derivable)';

CREATE TABLE graphitron_argument_condition_context_arg (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  position      INT     NOT NULL,
  name          VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, argument_name, position),
  FOREIGN KEY (type_name, field_name, argument_name)
    REFERENCES graphitron_argument_condition (type_name, field_name, argument_name)
);
COMMENT ON TABLE graphitron_argument_condition_context_arg IS 'An ordered context argument of an argument-site @condition.';
COMMENT ON COLUMN graphitron_argument_condition_context_arg.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_argument_condition_context_arg.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_argument_condition_context_arg.argument_name IS 'the argument name within the owning field';
COMMENT ON COLUMN graphitron_argument_condition_context_arg.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_argument_condition_context_arg.name IS 'the context argument name as written';

CREATE TABLE graphitron_argument_condition_arg_mapping_pair (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, argument_name, position),
  FOREIGN KEY (type_name, field_name, argument_name)
    REFERENCES graphitron_argument_condition (type_name, field_name, argument_name)
);
COMMENT ON TABLE graphitron_argument_condition_arg_mapping_pair IS 'An ordered pair of an argument-site @condition''s argMapping. Position-keyed so an author''s duplicate parameter survives for the duplicate detection.';
COMMENT ON COLUMN graphitron_argument_condition_arg_mapping_pair.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_argument_condition_arg_mapping_pair.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_argument_condition_arg_mapping_pair.argument_name IS 'the argument name within the owning field';
COMMENT ON COLUMN graphitron_argument_condition_arg_mapping_pair.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_argument_condition_arg_mapping_pair.param_name IS 'the Java or routine parameter (left side of the pair)';
COMMENT ON COLUMN graphitron_argument_condition_arg_mapping_pair.argument_path IS 'the right side as written: a GraphQL argument name or dotted input path';

CREATE TABLE graphitron_field_reference (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, field_name, ordinal),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);
COMMENT ON TABLE graphitron_field_reference IS '@reference on a field or input field: one row per application, because an application is a fact of its own. An empty path means FK auto-discovery between the endpoints, and the rule that every application in a multi-application chain must carry an element is per-application; both are invisible in a flat concatenated chain. The effective chain the consumers read is the steps ordered by (ordinal, position), and the written-order interleaving with @routine applications on the same field is an ORDER BY over the two relations'' source positions.';
COMMENT ON COLUMN graphitron_field_reference.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_field_reference.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_field_reference.ordinal IS 'repeatable; document order';
COMMENT ON COLUMN graphitron_field_reference.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_field_reference.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_field_reference.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphitron_field_reference_step (
  type_name   VARCHAR NOT NULL,
  field_name  VARCHAR NOT NULL,
  ordinal     INT     NOT NULL,
  position    INT     NOT NULL,
  table_ref   VARCHAR,
  key_ref     VARCHAR,
  class_name  VARCHAR,
  method      VARCHAR,
  arg_mapping VARCHAR,
  PRIMARY KEY (type_name, field_name, ordinal, position),
  FOREIGN KEY (type_name, field_name, ordinal)
    REFERENCES graphitron_field_reference (type_name, field_name, ordinal)
);
COMMENT ON TABLE graphitron_field_reference_step IS 'An ordered path element of one @reference application; the step''s ExternalCodeReference condition flattens in place.';
COMMENT ON COLUMN graphitron_field_reference_step.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_field_reference_step.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_field_reference_step.ordinal IS 'the owning @reference application''s ordinal';
COMMENT ON COLUMN graphitron_field_reference_step.position IS '0-based within the application''s path';
COMMENT ON COLUMN graphitron_field_reference_step.table_ref IS 'ReferenceElement.table as written';
COMMENT ON COLUMN graphitron_field_reference_step.key_ref IS 'ReferenceElement.key as written (may carry a schema qualifier)';
COMMENT ON COLUMN graphitron_field_reference_step.class_name IS 'the fully-qualified Java class name as written';
COMMENT ON COLUMN graphitron_field_reference_step.method IS 'the Java method name as written';
COMMENT ON COLUMN graphitron_field_reference_step.arg_mapping IS 'the argMapping string as written; the pair child is its decode';

CREATE TABLE graphitron_field_reference_step_arg_mapping_pair (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  step_position INT     NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, ordinal, step_position, position),
  FOREIGN KEY (type_name, field_name, ordinal, step_position)
    REFERENCES graphitron_field_reference_step (type_name, field_name, ordinal, position)
);
COMMENT ON TABLE graphitron_field_reference_step_arg_mapping_pair IS 'An ordered pair of a step condition''s argMapping.';
COMMENT ON COLUMN graphitron_field_reference_step_arg_mapping_pair.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_field_reference_step_arg_mapping_pair.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_field_reference_step_arg_mapping_pair.ordinal IS 'the owning @reference application''s ordinal';
COMMENT ON COLUMN graphitron_field_reference_step_arg_mapping_pair.step_position IS '0-based position of the owning step within its application''s path';
COMMENT ON COLUMN graphitron_field_reference_step_arg_mapping_pair.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_field_reference_step_arg_mapping_pair.param_name IS 'the Java or routine parameter (left side of the pair)';
COMMENT ON COLUMN graphitron_field_reference_step_arg_mapping_pair.argument_path IS 'the right side as written: a GraphQL argument name or dotted input path';

CREATE TABLE graphitron_argument_reference (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, field_name, argument_name, ordinal),
  FOREIGN KEY (type_name, field_name, argument_name)
    REFERENCES graphql_argument (type_name, field_name, argument_name)
);
COMMENT ON TABLE graphitron_argument_reference IS '@reference on an argument: the same family over the three-part coordinate.';
COMMENT ON COLUMN graphitron_argument_reference.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_argument_reference.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_argument_reference.argument_name IS 'the argument name within the owning field';
COMMENT ON COLUMN graphitron_argument_reference.ordinal IS 'capture-assigned position in document order';
COMMENT ON COLUMN graphitron_argument_reference.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_argument_reference.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_argument_reference.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphitron_argument_reference_step (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  position      INT     NOT NULL,
  table_ref     VARCHAR,
  key_ref       VARCHAR,
  class_name    VARCHAR,
  method        VARCHAR,
  arg_mapping   VARCHAR,
  PRIMARY KEY (type_name, field_name, argument_name, ordinal, position),
  FOREIGN KEY (type_name, field_name, argument_name, ordinal)
    REFERENCES graphitron_argument_reference (type_name, field_name, argument_name, ordinal)
);
COMMENT ON TABLE graphitron_argument_reference_step IS 'An ordered path element of one argument-site @reference application; the step''s ExternalCodeReference condition flattens in place.';
COMMENT ON COLUMN graphitron_argument_reference_step.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_argument_reference_step.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_argument_reference_step.argument_name IS 'the argument name within the owning field';
COMMENT ON COLUMN graphitron_argument_reference_step.ordinal IS 'the owning @reference application''s ordinal';
COMMENT ON COLUMN graphitron_argument_reference_step.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_argument_reference_step.table_ref IS 'the table name as written (may carry a schema qualifier)';
COMMENT ON COLUMN graphitron_argument_reference_step.key_ref IS 'the constraint name as written (may carry a schema qualifier)';
COMMENT ON COLUMN graphitron_argument_reference_step.class_name IS 'the fully-qualified Java class name as written';
COMMENT ON COLUMN graphitron_argument_reference_step.method IS 'the Java method name as written';
COMMENT ON COLUMN graphitron_argument_reference_step.arg_mapping IS 'the argMapping string as written; the pair child is its decode';

CREATE TABLE graphitron_argument_reference_step_arg_mapping_pair (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  step_position INT     NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, argument_name, ordinal, step_position, position),
  FOREIGN KEY (type_name, field_name, argument_name, ordinal, step_position)
    REFERENCES graphitron_argument_reference_step (type_name, field_name, argument_name, ordinal, position)
);
COMMENT ON TABLE graphitron_argument_reference_step_arg_mapping_pair IS 'An ordered pair of an argument-site @reference step condition''s argMapping.';
COMMENT ON COLUMN graphitron_argument_reference_step_arg_mapping_pair.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_argument_reference_step_arg_mapping_pair.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_argument_reference_step_arg_mapping_pair.argument_name IS 'the argument name within the owning field';
COMMENT ON COLUMN graphitron_argument_reference_step_arg_mapping_pair.ordinal IS 'the owning @reference application''s ordinal';
COMMENT ON COLUMN graphitron_argument_reference_step_arg_mapping_pair.step_position IS '0-based position of the owning step within its application''s path';
COMMENT ON COLUMN graphitron_argument_reference_step_arg_mapping_pair.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_argument_reference_step_arg_mapping_pair.param_name IS 'the Java or routine parameter (left side of the pair)';
COMMENT ON COLUMN graphitron_argument_reference_step_arg_mapping_pair.argument_path IS 'the right side as written: a GraphQL argument name or dotted input path';

CREATE TABLE graphitron_reference_for (
  type_name            VARCHAR NOT NULL,
  field_name           VARCHAR NOT NULL,
  ordinal              INT     NOT NULL,
  source_name          VARCHAR,
  source_line          INT,
  source_column        INT,
  participant_type_ref VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, ordinal),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);
COMMENT ON TABLE graphitron_reference_for IS '@referenceFor on a field: an explicit join path for one participant of a multi-table interface or union child. Keyed by ordinal per the repeatable rule; the consumption-side keying by participant makes a repeated participant a detection, never a collision.';
COMMENT ON COLUMN graphitron_reference_for.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_reference_for.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_reference_for.ordinal IS 'capture-assigned position in document order';
COMMENT ON COLUMN graphitron_reference_for.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_reference_for.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_reference_for.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_reference_for.participant_type_ref IS 'the type argument as written; author-spelled, no FK';

CREATE TABLE graphitron_reference_for_step (
  type_name   VARCHAR NOT NULL,
  field_name  VARCHAR NOT NULL,
  ordinal     INT     NOT NULL,
  position    INT     NOT NULL,
  table_ref   VARCHAR,
  key_ref     VARCHAR,
  class_name  VARCHAR,
  method      VARCHAR,
  arg_mapping VARCHAR,
  PRIMARY KEY (type_name, field_name, ordinal, position),
  FOREIGN KEY (type_name, field_name, ordinal)
    REFERENCES graphitron_reference_for (type_name, field_name, ordinal)
);
COMMENT ON TABLE graphitron_reference_for_step IS 'An ordered path element of one @referenceFor application: the participant''s complete path from the parent''s table, read as the same element grammar as @reference.';
COMMENT ON COLUMN graphitron_reference_for_step.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_reference_for_step.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_reference_for_step.ordinal IS 'the owning @referenceFor application''s ordinal';
COMMENT ON COLUMN graphitron_reference_for_step.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_reference_for_step.table_ref IS 'the table name as written (may carry a schema qualifier)';
COMMENT ON COLUMN graphitron_reference_for_step.key_ref IS 'the constraint name as written (may carry a schema qualifier)';
COMMENT ON COLUMN graphitron_reference_for_step.class_name IS 'the fully-qualified Java class name as written';
COMMENT ON COLUMN graphitron_reference_for_step.method IS 'the Java method name as written';
COMMENT ON COLUMN graphitron_reference_for_step.arg_mapping IS 'the argMapping string as written; the pair child is its decode';

CREATE TABLE graphitron_reference_for_step_arg_mapping_pair (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  step_position INT     NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, ordinal, step_position, position),
  FOREIGN KEY (type_name, field_name, ordinal, step_position)
    REFERENCES graphitron_reference_for_step (type_name, field_name, ordinal, position)
);
COMMENT ON TABLE graphitron_reference_for_step_arg_mapping_pair IS 'An ordered pair of a @referenceFor step condition''s argMapping.';
COMMENT ON COLUMN graphitron_reference_for_step_arg_mapping_pair.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_reference_for_step_arg_mapping_pair.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_reference_for_step_arg_mapping_pair.ordinal IS 'the owning @referenceFor application''s ordinal';
COMMENT ON COLUMN graphitron_reference_for_step_arg_mapping_pair.step_position IS '0-based position of the owning step within its application''s path';
COMMENT ON COLUMN graphitron_reference_for_step_arg_mapping_pair.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_reference_for_step_arg_mapping_pair.param_name IS 'the Java or routine parameter (left side of the pair)';
COMMENT ON COLUMN graphitron_reference_for_step_arg_mapping_pair.argument_path IS 'the right side as written: a GraphQL argument name or dotted input path';

CREATE TABLE graphitron_service (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  class_name    VARCHAR,
  method        VARCHAR,
  arg_mapping   VARCHAR,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);
COMMENT ON TABLE graphitron_service IS '@service on a field: the external service reference.';
COMMENT ON COLUMN graphitron_service.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_service.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_service.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_service.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_service.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_service.class_name IS 'the fully-qualified Java class name as written';
COMMENT ON COLUMN graphitron_service.method IS 'the Java method name as written';
COMMENT ON COLUMN graphitron_service.arg_mapping IS 'the argMapping string as written; the pair child is its decode';

CREATE TABLE graphitron_service_context_arg (
  type_name  VARCHAR NOT NULL,
  field_name VARCHAR NOT NULL,
  position   INT     NOT NULL,
  name       VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, position),
  FOREIGN KEY (type_name, field_name) REFERENCES graphitron_service (type_name, field_name)
);
COMMENT ON TABLE graphitron_service_context_arg IS 'An ordered contextArguments entry of a @service application; the value is supplied on the GraphQLContext at run time.';
COMMENT ON COLUMN graphitron_service_context_arg.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_service_context_arg.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_service_context_arg.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_service_context_arg.name IS 'the context argument name as written';

CREATE TABLE graphitron_service_arg_mapping_pair (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, position),
  FOREIGN KEY (type_name, field_name) REFERENCES graphitron_service (type_name, field_name)
);
COMMENT ON TABLE graphitron_service_arg_mapping_pair IS 'An ordered pair of a @service''s argMapping, binding a Java method parameter to a GraphQL argument.';
COMMENT ON COLUMN graphitron_service_arg_mapping_pair.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_service_arg_mapping_pair.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_service_arg_mapping_pair.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_service_arg_mapping_pair.param_name IS 'the Java or routine parameter (left side of the pair)';
COMMENT ON COLUMN graphitron_service_arg_mapping_pair.argument_path IS 'the right side as written: a GraphQL argument name or dotted input path';

CREATE TABLE graphitron_external_field (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  class_name    VARCHAR,
  method        VARCHAR,
  arg_mapping   VARCHAR,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);
COMMENT ON TABLE graphitron_external_field IS '@externalField on a field: the static jOOQ-Field method. The omitted-method fallback (the field name) is a derivation; arg_mapping is inert here (raw column only, its rejection is presence-triggered).';
COMMENT ON COLUMN graphitron_external_field.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_external_field.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_external_field.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_external_field.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_external_field.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_external_field.class_name IS 'the fully-qualified Java class name as written';
COMMENT ON COLUMN graphitron_external_field.method IS 'the Java method name as written';
COMMENT ON COLUMN graphitron_external_field.arg_mapping IS 'the argMapping string as written; the pair child is its decode';

CREATE TABLE graphitron_source_row (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  class_name    VARCHAR NOT NULL,
  method        VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);
COMMENT ON TABLE graphitron_source_row IS '@sourceRow on a field: the parent-side join-key lifter. Flat arguments by declaration, not an ExternalCodeReference.';
COMMENT ON COLUMN graphitron_source_row.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_source_row.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_source_row.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_source_row.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_source_row.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_source_row.class_name IS 'the lifter class as written';
COMMENT ON COLUMN graphitron_source_row.method IS 'the static lifter method name as written';

CREATE TABLE graphitron_connection (
  type_name           VARCHAR NOT NULL,
  field_name          VARCHAR NOT NULL,
  source_name         VARCHAR,
  source_line         INT,
  source_column       INT,
  default_first_value INT,
  connection_name     VARCHAR,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);
COMMENT ON TABLE graphitron_connection IS '@asConnection on a field: the macro''s spec, as authored. The expansion''s output is provenance-marked rows in the graphql_ tables, below.';
COMMENT ON COLUMN graphitron_connection.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_connection.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_connection.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_connection.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_connection.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_connection.default_first_value IS 'as written; NULL when omitted';
COMMENT ON COLUMN graphitron_connection.connection_name IS 'the deprecated shared-type override, as written; honoured by the expansion, deprecation is a lint detection';

CREATE TABLE graphitron_facet (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);
COMMENT ON TABLE graphitron_facet IS '@asFacet on an input field: a marker; the bound column comes from graphitron_field_binding, and every misuse arm is a detection.';
COMMENT ON COLUMN graphitron_facet.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_facet.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_facet.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_facet.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_facet.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphitron_order_by (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, field_name, argument_name),
  FOREIGN KEY (type_name, field_name, argument_name)
    REFERENCES graphql_argument (type_name, field_name, argument_name)
);
COMMENT ON TABLE graphitron_order_by IS '@orderBy on an argument: a marker; the input shape rules are detections.';
COMMENT ON COLUMN graphitron_order_by.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_order_by.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_order_by.argument_name IS 'the argument name within the owning field';
COMMENT ON COLUMN graphitron_order_by.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_order_by.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_order_by.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphitron_order (
  type_name     VARCHAR NOT NULL,
  value_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  index_ref     VARCHAR,
  primary_key   BOOLEAN,
  PRIMARY KEY (type_name, value_name),
  FOREIGN KEY (type_name, value_name) REFERENCES graphql_enum_value (type_name, value_name)
);
COMMENT ON TABLE graphitron_order IS '@order on an enum value: a sorting specification. The exactly-one-of rule over index, fields, and primaryKey is a detection.';
COMMENT ON COLUMN graphitron_order.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_order.value_name IS 'the enum value name within the owning enum type';
COMMENT ON COLUMN graphitron_order.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_order.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_order.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_order.index_ref IS 'database index name as written';
COMMENT ON COLUMN graphitron_order.primary_key IS 'as written; NULL when omitted';

CREATE TABLE graphitron_order_field (
  type_name  VARCHAR NOT NULL,
  value_name VARCHAR NOT NULL,
  position   INT     NOT NULL,
  name_ref   VARCHAR NOT NULL,
  collate    VARCHAR,
  direction  VARCHAR,
  PRIMARY KEY (type_name, value_name, position),
  FOREIGN KEY (type_name, value_name) REFERENCES graphitron_order (type_name, value_name)
);
COMMENT ON TABLE graphitron_order_field IS 'An ordered FieldSort entry of an @order.';
COMMENT ON COLUMN graphitron_order_field.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_order_field.value_name IS 'the enum value name within the owning enum type';
COMMENT ON COLUMN graphitron_order_field.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_order_field.name_ref IS 'FieldSort.name, a column reference as written';
COMMENT ON COLUMN graphitron_order_field.collate IS 'the collation as written, when declared';
COMMENT ON COLUMN graphitron_order_field.direction IS 'as written; author-spelled enum literal, open column';

CREATE TABLE graphitron_index (
  type_name     VARCHAR NOT NULL,
  value_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  index_ref     VARCHAR,
  PRIMARY KEY (type_name, value_name),
  FOREIGN KEY (type_name, value_name) REFERENCES graphql_enum_value (type_name, value_name)
);
COMMENT ON TABLE graphitron_index IS '@index on an enum value: the deprecated alias of @order(index:), still honoured when @order is absent; the deprecation is a lint detection.';
COMMENT ON COLUMN graphitron_index.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_index.value_name IS 'the enum value name within the owning enum type';
COMMENT ON COLUMN graphitron_index.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_index.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_index.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_index.index_ref IS 'the name argument, which the declaration leaves optional';

CREATE TABLE graphitron_default_order (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  index_ref     VARCHAR,
  primary_key   BOOLEAN,
  direction     VARCHAR,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);
COMMENT ON TABLE graphitron_default_order IS '@defaultOrder on a field: the same specification shape plus the directive-level direction that serves as the per-entry fallback.';
COMMENT ON COLUMN graphitron_default_order.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_default_order.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_default_order.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_default_order.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_default_order.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_default_order.index_ref IS 'the database index name as written';
COMMENT ON COLUMN graphitron_default_order.primary_key IS 'as written; NULL when omitted';
COMMENT ON COLUMN graphitron_default_order.direction IS 'as written; open column, the ASC default is a derivation';

CREATE TABLE graphitron_default_order_field (
  type_name  VARCHAR NOT NULL,
  field_name VARCHAR NOT NULL,
  position   INT     NOT NULL,
  name_ref   VARCHAR NOT NULL,
  collate    VARCHAR,
  direction  VARCHAR,
  PRIMARY KEY (type_name, field_name, position),
  FOREIGN KEY (type_name, field_name) REFERENCES graphitron_default_order (type_name, field_name)
);
COMMENT ON TABLE graphitron_default_order_field IS 'An ordered FieldSort entry of a @defaultOrder.';
COMMENT ON COLUMN graphitron_default_order_field.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_default_order_field.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_default_order_field.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_default_order_field.name_ref IS 'the name argument as written';
COMMENT ON COLUMN graphitron_default_order_field.collate IS 'the collation as written, when declared';
COMMENT ON COLUMN graphitron_default_order_field.direction IS 'the sort direction as written; author-spelled enum literal, open column';

CREATE TABLE graphitron_mutation (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  operation     VARCHAR NOT NULL,
  multi_row     BOOLEAN,
  table_ref     VARCHAR,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);
COMMENT ON TABLE graphitron_mutation IS '@mutation on a field: the DML statement spec.';
COMMENT ON COLUMN graphitron_mutation.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_mutation.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_mutation.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_mutation.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_mutation.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_mutation.operation IS 'the typeName argument as written (INSERT / UPDATE / DELETE / UPSERT); open column per the enum-literal rule';
COMMENT ON COLUMN graphitron_mutation.multi_row IS 'as written; NULL when omitted';
COMMENT ON COLUMN graphitron_mutation.table_ref IS 'the DELETE write target as written';

CREATE TABLE graphitron_error (
  type_name        VARCHAR NOT NULL,
  source_name      VARCHAR NOT NULL,
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  PRIMARY KEY (type_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphitron_error IS '@error on an object type: presence; the handlers list decodes into the ordered child, and every cross-field handler rule is a detection.';
COMMENT ON COLUMN graphitron_error.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_error.source_name IS 'half of the site FK, so NOT NULL; a graphitron application always has an SDL position';
COMMENT ON COLUMN graphitron_error.declaration_line IS 'line of the contributing declaration site, keyed with source_name';
COMMENT ON COLUMN graphitron_error.declaration_column IS 'column of the contributing declaration site, the site key''s fourth part';
COMMENT ON COLUMN graphitron_error.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_error.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphitron_error_handler (
  type_name   VARCHAR NOT NULL,
  position    INT     NOT NULL,
  handler     VARCHAR NOT NULL,
  class_name  VARCHAR,
  code        VARCHAR,
  sql_state   VARCHAR,
  matches     VARCHAR,
  description VARCHAR,
  PRIMARY KEY (type_name, position),
  FOREIGN KEY (type_name) REFERENCES graphitron_error (type_name)
);
COMMENT ON TABLE graphitron_error_handler IS 'An ordered ErrorHandler of an @error application.';
COMMENT ON COLUMN graphitron_error_handler.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_error_handler.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_error_handler.handler IS 'GENERIC / DATABASE / VALIDATION as written; open column';
COMMENT ON COLUMN graphitron_error_handler.class_name IS 'the exception class as written';
COMMENT ON COLUMN graphitron_error_handler.code IS 'the database error code the handler matches on';
COMMENT ON COLUMN graphitron_error_handler.sql_state IS 'the SQL state code the handler matches on';
COMMENT ON COLUMN graphitron_error_handler.matches IS 'a substring the exception message must contain';
COMMENT ON COLUMN graphitron_error_handler.description IS 'SDL description string, when the author wrote one';

CREATE TABLE graphitron_node (
  type_name        VARCHAR NOT NULL,
  source_name      VARCHAR NOT NULL,
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  type_id          VARCHAR,
  PRIMARY KEY (type_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphitron_node IS '@node on an object type: node identity. The type-name fallback for typeId and the catalog-PK fallback for key columns are derivations; the SDL-versus-jOOQ-metadata precedence rules are detections.';
COMMENT ON COLUMN graphitron_node.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_node.source_name IS 'half of the site FK, so NOT NULL; a graphitron application always has an SDL position';
COMMENT ON COLUMN graphitron_node.declaration_line IS 'line of the contributing declaration site, keyed with source_name';
COMMENT ON COLUMN graphitron_node.declaration_column IS 'column of the contributing declaration site, the site key''s fourth part';
COMMENT ON COLUMN graphitron_node.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_node.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_node.type_id IS 'as written';

CREATE TABLE graphitron_node_key_column (
  type_name  VARCHAR NOT NULL,
  position   INT     NOT NULL,
  column_ref VARCHAR NOT NULL,
  PRIMARY KEY (type_name, position),
  FOREIGN KEY (type_name) REFERENCES graphitron_node (type_name)
);
COMMENT ON TABLE graphitron_node_key_column IS 'An ordered keyColumns entry of an @node.';
COMMENT ON COLUMN graphitron_node_key_column.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_node_key_column.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_node_key_column.column_ref IS 'the key column as written';

CREATE TABLE graphitron_field_node_id (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  node_type_ref VARCHAR,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);
COMMENT ON TABLE graphitron_field_node_id IS '@nodeId on a field or input field.';
COMMENT ON COLUMN graphitron_field_node_id.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_field_node_id.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_field_node_id.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_field_node_id.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_field_node_id.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_field_node_id.node_type_ref IS 'typeName as written; author-spelled type reference, no FK, inference when NULL is a derivation';

CREATE TABLE graphitron_argument_node_id (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  node_type_ref VARCHAR,
  PRIMARY KEY (type_name, field_name, argument_name),
  FOREIGN KEY (type_name, field_name, argument_name)
    REFERENCES graphql_argument (type_name, field_name, argument_name)
);
COMMENT ON TABLE graphitron_argument_node_id IS '@nodeId on an argument.';
COMMENT ON COLUMN graphitron_argument_node_id.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_argument_node_id.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_argument_node_id.argument_name IS 'the argument name within the owning field';
COMMENT ON COLUMN graphitron_argument_node_id.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_argument_node_id.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_argument_node_id.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_argument_node_id.node_type_ref IS 'the typeName argument as written; author-spelled, no FK';

CREATE TABLE graphitron_argument_lookup_key (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, field_name, argument_name),
  FOREIGN KEY (type_name, field_name, argument_name)
    REFERENCES graphql_argument (type_name, field_name, argument_name)
);
COMMENT ON TABLE graphitron_argument_lookup_key IS '@lookupKey on an argument: the live site, a marker.';
COMMENT ON COLUMN graphitron_argument_lookup_key.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_argument_lookup_key.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_argument_lookup_key.argument_name IS 'the argument name within the owning field';
COMMENT ON COLUMN graphitron_argument_lookup_key.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_argument_lookup_key.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_argument_lookup_key.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphitron_field_lookup_key (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);
COMMENT ON TABLE graphitron_field_lookup_key IS '@lookupKey on an input field: the retired site; the sole consumer is the located migration rejection.';
COMMENT ON COLUMN graphitron_field_lookup_key.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_field_lookup_key.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_field_lookup_key.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_field_lookup_key.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_field_lookup_key.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphitron_split_query (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);
COMMENT ON TABLE graphitron_split_query IS '@splitQuery on a field: a marker.';
COMMENT ON COLUMN graphitron_split_query.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_split_query.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_split_query.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_split_query.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_split_query.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphitron_tenant_fan_out (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);
COMMENT ON TABLE graphitron_tenant_fan_out IS '@tenantFanOut on a field: a marker; its many conflict arms are detections.';
COMMENT ON COLUMN graphitron_tenant_fan_out.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_tenant_fan_out.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_tenant_fan_out.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_tenant_fan_out.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_tenant_fan_out.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphitron_pivot (
  type_name      VARCHAR NOT NULL,
  field_name     VARCHAR NOT NULL,
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  on_column      VARCHAR NOT NULL,
  value_column   VARCHAR NOT NULL,
  vocabulary_ref VARCHAR,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);
COMMENT ON TABLE graphitron_pivot IS '@pivot on a field: the aggregate-projection spec.';
COMMENT ON COLUMN graphitron_pivot.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_pivot.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_pivot.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_pivot.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_pivot.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_pivot.on_column IS 'the on: argument, the discriminator column as written';
COMMENT ON COLUMN graphitron_pivot.value_column IS 'the value: argument as written';
COMMENT ON COLUMN graphitron_pivot.vocabulary_ref IS 'names an enum type; author-spelled, no FK';

CREATE TABLE graphitron_routine (
  type_name      VARCHAR NOT NULL,
  field_name     VARCHAR NOT NULL,
  ordinal        INT     NOT NULL,
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  routine_ref    VARCHAR NOT NULL,
  arg_mapping    VARCHAR,
  column_mapping VARCHAR,
  PRIMARY KEY (type_name, field_name, ordinal),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);
COMMENT ON TABLE graphitron_routine IS '@routine on a field: one row per application (repeatable). The table chain interleaves these with graphitron_field_reference rows in written order.';
COMMENT ON COLUMN graphitron_routine.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_routine.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_routine.ordinal IS 'capture-assigned position in document order';
COMMENT ON COLUMN graphitron_routine.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_routine.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_routine.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_routine.routine_ref IS 'the routine name as written (may carry a schema qualifier)';
COMMENT ON COLUMN graphitron_routine.arg_mapping IS 'the argMapping string as written; the pair child is its decode';
COMMENT ON COLUMN graphitron_routine.column_mapping IS 'the columnMapping string as written; the pair child is its decode';

CREATE TABLE graphitron_routine_arg_mapping_pair (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, ordinal, position),
  FOREIGN KEY (type_name, field_name, ordinal)
    REFERENCES graphitron_routine (type_name, field_name, ordinal)
);
COMMENT ON TABLE graphitron_routine_arg_mapping_pair IS 'An ordered pair of a @routine''s argMapping, binding a routine IN parameter to a GraphQL argument.';
COMMENT ON COLUMN graphitron_routine_arg_mapping_pair.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_routine_arg_mapping_pair.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_routine_arg_mapping_pair.ordinal IS 'the owning @routine application''s ordinal';
COMMENT ON COLUMN graphitron_routine_arg_mapping_pair.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_routine_arg_mapping_pair.param_name IS 'the Java or routine parameter (left side of the pair)';
COMMENT ON COLUMN graphitron_routine_arg_mapping_pair.argument_path IS 'the right side as written: a GraphQL argument name or dotted input path';

CREATE TABLE graphitron_routine_column_mapping_pair (
  type_name  VARCHAR NOT NULL,
  field_name VARCHAR NOT NULL,
  ordinal    INT     NOT NULL,
  position   INT     NOT NULL,
  param_name VARCHAR NOT NULL,
  column_ref VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name, ordinal, position),
  FOREIGN KEY (type_name, field_name, ordinal)
    REFERENCES graphitron_routine (type_name, field_name, ordinal)
);
COMMENT ON TABLE graphitron_routine_column_mapping_pair IS 'columnMapping pairs bind routine parameters to previous-node columns; a dotted right side is captured as written and rejected by detection.';
COMMENT ON COLUMN graphitron_routine_column_mapping_pair.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_routine_column_mapping_pair.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_routine_column_mapping_pair.ordinal IS 'the owning @routine application''s ordinal';
COMMENT ON COLUMN graphitron_routine_column_mapping_pair.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_routine_column_mapping_pair.param_name IS 'the Java or routine parameter (left side of the pair)';
COMMENT ON COLUMN graphitron_routine_column_mapping_pair.column_ref IS 'the previous-node column as written; a dotted right side is captured and rejected by detection';

-- @experimental_constructType has no relation, and unlike every other name in this stratum it
-- is not a graphitron directive: its declaration in directives.graphqls is a bug (the census
-- found no consumer anywhere; the declaration's only effect is that emission strips
-- applications, silently swallowing a directive graphitron does not own). Once the stray
-- declaration is removed the name is foreign like any user-authored directive and its
-- applications land in the graphql_ family as fidelity rows, re-emitted verbatim; the store
-- needs no special case for it.
CREATE TABLE graphitron_discriminate (
  type_name        VARCHAR NOT NULL,
  source_name      VARCHAR NOT NULL,
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  on_column        VARCHAR NOT NULL,
  PRIMARY KEY (type_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphitron_discriminate IS '@discriminate on an interface or union: the discriminator column.';
COMMENT ON COLUMN graphitron_discriminate.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_discriminate.source_name IS 'half of the site FK, so NOT NULL; a graphitron application always has an SDL position';
COMMENT ON COLUMN graphitron_discriminate.declaration_line IS 'line of the contributing declaration site, keyed with source_name';
COMMENT ON COLUMN graphitron_discriminate.declaration_column IS 'column of the contributing declaration site, the site key''s fourth part';
COMMENT ON COLUMN graphitron_discriminate.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_discriminate.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_discriminate.on_column IS 'the on: argument as written; catalog resolution is a derivation';

CREATE TABLE graphitron_discriminator (
  type_name           VARCHAR NOT NULL,
  source_name         VARCHAR NOT NULL,
  declaration_line    INT     NOT NULL,
  declaration_column  INT     NOT NULL,
  source_line         INT,
  source_column       INT,
  discriminator_value VARCHAR NOT NULL,
  PRIMARY KEY (type_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphitron_discriminator IS '@discriminator on an object type: the participant''s discriminator value.';
COMMENT ON COLUMN graphitron_discriminator.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_discriminator.source_name IS 'half of the site FK, so NOT NULL';
COMMENT ON COLUMN graphitron_discriminator.declaration_line IS 'line of the contributing declaration site, keyed with source_name';
COMMENT ON COLUMN graphitron_discriminator.declaration_column IS 'column of the contributing declaration site, the site key''s fourth part';
COMMENT ON COLUMN graphitron_discriminator.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_discriminator.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_discriminator.discriminator_value IS 'the value: argument as written (VALUE alone is an H2 reserved word)';

CREATE TABLE graphitron_federation_key (
  type_name        VARCHAR NOT NULL,
  ordinal          INT     NOT NULL,
  source_name      VARCHAR NOT NULL,
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  fields_sdl       VARCHAR NOT NULL,
  resolvable       BOOLEAN,
  PRIMARY KEY (type_name, ordinal),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphitron_federation_key IS 'Federation @key, decoded for consumption (its verbatim twin lives in graphql_type_directive for re-emission; a gate query pins agreement).';
COMMENT ON COLUMN graphitron_federation_key.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_federation_key.ordinal IS '@key is repeatable; document order';
COMMENT ON COLUMN graphitron_federation_key.source_name IS 'the applying declaration site; a synthesized key inherits the causing authored site of the same type, so the reference holds for it too';
COMMENT ON COLUMN graphitron_federation_key.declaration_line IS 'line of the contributing declaration site, keyed with source_name';
COMMENT ON COLUMN graphitron_federation_key.declaration_column IS 'column of the contributing declaration site, the site key''s fourth part';
COMMENT ON COLUMN graphitron_federation_key.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_federation_key.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_federation_key.fields_sdl IS 'the field-set literal as written';
COMMENT ON COLUMN graphitron_federation_key.resolvable IS 'as written; NULL when omitted';

CREATE TABLE graphitron_federation_key_field (
  type_name  VARCHAR NOT NULL,
  ordinal    INT     NOT NULL,
  position   INT     NOT NULL,
  field_path VARCHAR NOT NULL,
  PRIMARY KEY (type_name, ordinal, position),
  FOREIGN KEY (type_name, ordinal)
    REFERENCES graphitron_federation_key (type_name, ordinal)
);
COMMENT ON TABLE graphitron_federation_key_field IS 'An ordered element of a @key field set (the field-set grammar is a parse boundary, so the decode happens at capture). The grammar admits nested selections as dotted paths; that today''s consumer rejects nesting is a detection, not a capture limit.';
COMMENT ON COLUMN graphitron_federation_key_field.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_federation_key_field.ordinal IS 'capture-assigned position in document order';
COMMENT ON COLUMN graphitron_federation_key_field.position IS '0-based within the field set';
COMMENT ON COLUMN graphitron_federation_key_field.field_path IS 'dotted path for nested selections';

CREATE TABLE graphitron_link (
  ordinal       INT     NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  url           VARCHAR,
  PRIMARY KEY (ordinal)
);
COMMENT ON TABLE graphitron_link IS '@link on the schema definition, decoded. All @link applications decode here (the verbatim twin sits in graphql_schema_directive); whether a link is the federation opt-in is a predicate over url, a derivation. @tag and @shareable get no decoded relations: their only readers are the expansion machinery itself, which is the capture walk with the AST in hand, so downstream consumers see them only as fidelity rows for re-emission.';
COMMENT ON COLUMN graphitron_link.ordinal IS '@link is repeatable; document order';
COMMENT ON COLUMN graphitron_link.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_link.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_link.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_link.url IS 'as written';

CREATE TABLE graphitron_link_import (
  link_ordinal INT     NOT NULL,
  position     INT     NOT NULL,
  name         VARCHAR NOT NULL,
  alias        VARCHAR,
  PRIMARY KEY (link_ordinal, position),
  FOREIGN KEY (link_ordinal) REFERENCES graphitron_link (ordinal)
);
COMMENT ON TABLE graphitron_link_import IS 'An ordered import entry of an @link, covering both the string form and the object form.';
COMMENT ON COLUMN graphitron_link_import.link_ordinal IS 'the owning @link application''s ordinal';
COMMENT ON COLUMN graphitron_link_import.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_link_import.name IS 'the imported name (the object form''s name:)';
COMMENT ON COLUMN graphitron_link_import.alias IS 'the object form''s as:, when written';

-- Retired directives: existence only, per the rules above.
--
-- @notGenerated, like @experimental_constructType above, is not a graphitron directive and its
-- declaration in directives.graphqls is a bug, so it gets no relations. Once the stray
-- declaration is removed its applications take the graphql_ fidelity path, and the current
-- hard rejection ("no longer supported") becomes, if it is kept at all, a detection over the
-- directive name in the graphql_ rows; whether to keep steering on a name graphitron does not
-- own is a directive-lifecycle question outside this spec.
CREATE TABLE graphitron_multitable_reference (
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name)
);
COMMENT ON TABLE graphitron_multitable_reference IS '@multitableReference (removed) on a field; routes is never read.';
COMMENT ON COLUMN graphitron_multitable_reference.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_multitable_reference.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_multitable_reference.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_multitable_reference.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_multitable_reference.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphitron_record (
  type_name        VARCHAR NOT NULL,
  source_name      VARCHAR NOT NULL,
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  class_name       VARCHAR,
  PRIMARY KEY (type_name),
  FOREIGN KEY (type_name) REFERENCES graphql_type (type_name),
  FOREIGN KEY (type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphitron_record IS '@record (deprecated, ignored) on an object or input type. class_name is the one payload value a consumer reads: the warning arms compare it against the reflected backing class.';
COMMENT ON COLUMN graphitron_record.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_record.source_name IS 'half of the site FK, so NOT NULL; a graphitron application always has an SDL position';
COMMENT ON COLUMN graphitron_record.declaration_line IS 'line of the contributing declaration site, keyed with source_name';
COMMENT ON COLUMN graphitron_record.declaration_column IS 'column of the contributing declaration site, the site key''s fourth part';
COMMENT ON COLUMN graphitron_record.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_record.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_record.class_name IS 'record.className as written';

CREATE TABLE graphitron_undecoded_argument (
  source_name             VARCHAR NOT NULL,
  source_line             INT     NOT NULL,
  source_column           INT     NOT NULL,
  directive_name          VARCHAR NOT NULL,
  directive_argument_name VARCHAR NOT NULL,
  value_sdl               VARCHAR NOT NULL,
  PRIMARY KEY (source_name, source_line, source_column, directive_name, directive_argument_name)
);
COMMENT ON TABLE graphitron_undecoded_argument IS 'The tolerant-decode overflow: a graphitron application argument whose literal does not fit the declared shape decodes to NULL in its typed column and quarantines its raw text here, so the authored value is never lost and the malformed-literal detection has its row. Empty while assembly runs upstream.';
COMMENT ON COLUMN graphitron_undecoded_argument.source_name IS 'the application''s position identifies the row; authored applications always have one';
COMMENT ON COLUMN graphitron_undecoded_argument.source_line IS 'line of the application carrying the undecodable literal';
COMMENT ON COLUMN graphitron_undecoded_argument.source_column IS 'column of the application carrying the undecodable literal';
COMMENT ON COLUMN graphitron_undecoded_argument.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphitron_undecoded_argument.directive_argument_name IS 'the definition''s formal argument this value binds';
COMMENT ON COLUMN graphitron_undecoded_argument.value_sdl IS 'the literal as written, rendered from the AST';


-- ==== Macro synthesis provenance ==================================================
-- The expansion's own record: which graphql_ rows a macro added, and the authored text where
-- the macro rewrote it. Synthesized rows inherit the causing application's source position;
-- these relations are what say a position means "caused here" rather than "written here".
--
-- The macro domains are closed over the expansions capture can run, which is the expansions whose
-- contribution is a function of one carrier's own declaration. @asConnection qualifies: its element
-- type enters as a name and nothing reads the type that name resolves to. @asFacet does not, which
-- is why no FACET value appears here. Its container's shape reads through the carrier's arguments
-- into the filter input type's fields, so it is an aggregate over the whole schema rather than a
-- local expansion, and it belongs to a derived stratum reading these relations.
CREATE TABLE graphitron_type_declaration_synthesis (
  type_name          VARCHAR NOT NULL,
  source_name        VARCHAR NOT NULL,
  source_line        INT     NOT NULL,
  source_column      INT     NOT NULL,
  macro              VARCHAR NOT NULL,
  carrier_type_name  VARCHAR,
  carrier_field_name VARCHAR,
  PRIMARY KEY (type_name, source_name, source_line, source_column),
  FOREIGN KEY (type_name, source_name, source_line, source_column)
    REFERENCES graphql_type_declaration (type_name, source_name, source_line, source_column),
  CHECK (macro IN ('CONNECTION', 'FEDERATION'))
);
COMMENT ON TABLE graphitron_type_declaration_synthesis IS 'A declaration site was contributed by a macro rather than the author: a definition site when the macro creates the type (Connection, Edge, facet shapes, at merge_ordinal 0), an extension site when it adds members to an existing type (the Query fields federation adds from @link), and an empty extension site when a later carrier touches a shared machinery type (PageInfo), so carrier multiplicity is the site count. Synthesized element rows hang off these sites through the ordinary declaration reference, which is what marks additions without per-element provenance; a type is synthesized exactly when its merge_ordinal-0 site is.';
COMMENT ON COLUMN graphitron_type_declaration_synthesis.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_type_declaration_synthesis.source_name IS 'the causing application''s position, which is the site''s identity';
COMMENT ON COLUMN graphitron_type_declaration_synthesis.source_line IS 'line of the causing application, which is the site''s identity';
COMMENT ON COLUMN graphitron_type_declaration_synthesis.source_column IS 'the site key''s fourth part, as on graphql_type_declaration';
COMMENT ON COLUMN graphitron_type_declaration_synthesis.macro IS 'which expansion contributed the site';
COMMENT ON COLUMN graphitron_type_declaration_synthesis.carrier_type_name IS 'the causing coordinate; NULL for schema-level causes (@link)';
COMMENT ON COLUMN graphitron_type_declaration_synthesis.carrier_field_name IS 'the causing field coordinate; NULL for type- and schema-level causes';

CREATE TABLE graphitron_field_synthesis (
  type_name         VARCHAR NOT NULL,
  field_name        VARCHAR NOT NULL,
  macro             VARCHAR NOT NULL,
  authored_type_sdl VARCHAR NOT NULL,
  PRIMARY KEY (type_name, field_name),
  FOREIGN KEY (type_name, field_name) REFERENCES graphql_field (type_name, field_name),
  CHECK (macro IN ('CONNECTION'))
);
COMMENT ON TABLE graphitron_field_synthesis IS 'A field''s type expression was rewritten by a macro; the authored expression survives here while the field''s graphql_field row holds the effective one.';
COMMENT ON COLUMN graphitron_field_synthesis.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_field_synthesis.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_field_synthesis.macro IS 'which expansion rewrote the type expression';
COMMENT ON COLUMN graphitron_field_synthesis.authored_type_sdl IS 'the type expression as the author wrote it, pre-expansion';

CREATE TABLE graphitron_type_directive_synthesis (
  type_name      VARCHAR NOT NULL,
  directive_name VARCHAR NOT NULL,
  ordinal        INT     NOT NULL,
  macro          VARCHAR NOT NULL,
  PRIMARY KEY (type_name, directive_name, ordinal),
  FOREIGN KEY (type_name, directive_name, ordinal)
    REFERENCES graphql_type_directive (type_name, directive_name, ordinal),
  CHECK (macro IN ('FEDERATION_KEY'))
);
COMMENT ON TABLE graphitron_type_directive_synthesis IS 'A type-level directive application was synthesized rather than authored (federation key synthesis; the application itself sits in graphql_type_directive and graphitron_federation_key like any other, and must re-emit, so provenance is this relation, not exclusion).';
COMMENT ON COLUMN graphitron_type_directive_synthesis.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_type_directive_synthesis.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphitron_type_directive_synthesis.ordinal IS 'capture-assigned position in document order';
COMMENT ON COLUMN graphitron_type_directive_synthesis.macro IS 'which expansion synthesized the application';


-- ==== Store bookkeeping ===========================================================
-- The store's record of what it read. The only family whose rows capture does not transcribe
-- from somewhere else, which is what earns it a name of its own: the vocabulary is the store's
-- metamodel rather than SQL's, the JVM's or GraphQL's, and one mechanism covers all three
-- source kinds because a partition delete is one mechanism whether the source is a schema
-- file, a compile-output directory, or a jar.

CREATE TABLE store_source (
  source_name VARCHAR NOT NULL,
  source_kind VARCHAR NOT NULL,
  stamp       VARCHAR,
  PRIMARY KEY (source_name),
  CHECK (source_kind IN ('SCHEMA_FILE', 'DIRECTORY', 'JAR', 'JOOQ_SCHEMA'))
);
COMMENT ON TABLE store_source IS 'A source the store read. Every base relation is partitionable by the source that produced it: a refresh deletes exactly the rows one source wrote and re-walks it, so a relation unreachable from a source row is one the store can only ever discard wholesale.';
COMMENT ON COLUMN store_source.source_name IS 'the schema file path, the classpath entry path, or the generated package a jOOQ schema lives in, as the reader spelled it';
COMMENT ON COLUMN store_source.source_kind IS 'a closed taxonomy: a schema file, a directory root, a jar, or a generated jOOQ schema package. The last names jOOQ deliberately, unlike the sql_ family: a family is named for whose vocabulary its rows are written in and jOOQ owns none of SQL''s, but a source is named for what it is, and a generated package is jOOQ''s artefact';
COMMENT ON COLUMN store_source.stamp IS 'content hash, so an unchanged source is read once; NULL where there is nothing cheap to hash or nothing worth caching: a directory root changes on every compile, a schema file''s bytes capture never holds (graphql-java hands the walk a source name, not the text), and a jOOQ schema is a package spread across the classpath whose walk is cheap enough not to need one. Also NULL while the source''s rows are being written, and set only once they are all in, so a run that dies mid-load leaves a partition that is re-walked rather than one that claims to be complete';

CREATE TABLE store_stamp (
  singleton         CHAR(1) NOT NULL,
  ddl_hash          VARCHAR NOT NULL,
  generator_version VARCHAR NOT NULL,
  PRIMARY KEY (singleton),
  CHECK (singleton = 'X')
);
COMMENT ON TABLE store_stamp IS 'What this store was built from. At most one row, stated structurally. A persisted store is never state of record: this row decides whether an existing file is intelligible at all, and a mismatch discards and rebuilds it, which is what keeps migrations out of a schema that has none.';
COMMENT ON COLUMN store_stamp.singleton IS 'always ''X''; the CHECK plus the primary key is how a relation says "at most one row" in SQL';
COMMENT ON COLUMN store_stamp.ddl_hash IS 'hash of the DDL resource the store was created from, so any schema edit at all invalidates a persisted file';
COMMENT ON COLUMN store_stamp.generator_version IS 'the capturing generator''s implementation version, or a placeholder where the manifest declares none. It invalidates a persisted file across releases; within one, a capture change that alters row content without touching the DDL is not caught here, and deleting the build directory is the remedy';

-- ==== SQL catalog facts ===========================================================
-- What the consumer's database declares, in SQL's vocabulary. jOOQ's generated model is the
-- reader, not the owner: reading INFORMATION_SCHEMA directly instead would leave every relation
-- name here correct. "Catalog" stays the prose word for what the family is about; only the
-- prefix carries the rule.
CREATE TABLE sql_table (
  table_schema VARCHAR NOT NULL,
  table_name   VARCHAR NOT NULL,
  jooq_name    VARCHAR NOT NULL,
  source_name  VARCHAR NOT NULL,
  description  VARCHAR,
  PRIMARY KEY (table_schema, table_name),
  FOREIGN KEY (source_name) REFERENCES store_source (source_name)
);
COMMENT ON TABLE sql_table IS 'A table exists in the consumer''s catalog. Every table jOOQ''s generated model declares, across every schema it declares; ambiguity of an unqualified @table(name:) is a resolution question and therefore derivation, so capture just records them all.';
COMMENT ON COLUMN sql_table.table_schema IS 'SQL schema the table lives in';
COMMENT ON COLUMN sql_table.table_name IS 'SQL table name';
COMMENT ON COLUMN sql_table.jooq_name IS 'the generated jOOQ Java field name for the table; under a family named for SQL this is the one foreign column, so the prefix marks it rather than leaving a reader to infer it';
COMMENT ON COLUMN sql_table.source_name IS 'the generated package the table''s schema lives in; the partition this row belongs to. The package rather than the classpath entry it was loaded from, because one jar carries every schema a codegen run produced and invalidating the jar would discard them all, while the package is the granularity codegen actually rewrites. Schemas flattened into one package (jOOQ''s outputSchemaToDefault) share a source, which is correct: they are regenerated together';
COMMENT ON COLUMN sql_table.description IS 'the database comment on the table, when present';

CREATE TABLE sql_column (
  table_schema VARCHAR NOT NULL,
  table_name   VARCHAR NOT NULL,
  column_name  VARCHAR NOT NULL,
  ordinal      INT     NOT NULL,
  jooq_name    VARCHAR NOT NULL,
  sql_type     VARCHAR NOT NULL,
  nullable     BOOLEAN NOT NULL,
  description  VARCHAR,
  PRIMARY KEY (table_schema, table_name, column_name),
  FOREIGN KEY (table_schema, table_name) REFERENCES sql_table (table_schema, table_name)
);
COMMENT ON TABLE sql_column IS 'A column exists on a table. The SQL name is the coordinate, which is what the schema''s directives spell; the jOOQ name rides along because the LSP surface is Java-name-centric.';
COMMENT ON COLUMN sql_column.table_schema IS 'SQL schema the table lives in';
COMMENT ON COLUMN sql_column.table_name IS 'SQL table name';
COMMENT ON COLUMN sql_column.column_name IS 'SQL column name';
COMMENT ON COLUMN sql_column.ordinal IS 'column position in the table definition, read from Table.fields() rather than from the reflective field walk, whose order is unspecified';
COMMENT ON COLUMN sql_column.jooq_name IS 'the generated jOOQ Java field name; the one column here written in the reader''s vocabulary rather than SQL''s';
COMMENT ON COLUMN sql_column.sql_type IS 'the column''s SQL type as jOOQ reports it';
COMMENT ON COLUMN sql_column.nullable IS 'whether the column admits NULL';
COMMENT ON COLUMN sql_column.description IS 'the database comment on the column, when present';

CREATE TABLE sql_constraint (
  table_schema    VARCHAR NOT NULL,
  table_name      VARCHAR NOT NULL,
  constraint_name VARCHAR NOT NULL,
  constraint_type VARCHAR NOT NULL,
  PRIMARY KEY (table_schema, table_name, constraint_name),
  FOREIGN KEY (table_schema, table_name) REFERENCES sql_table (table_schema, table_name),
  CHECK (constraint_type IN ('PRIMARY KEY', 'UNIQUE', 'FOREIGN KEY'))
);
COMMENT ON TABLE sql_constraint IS 'A named constraint exists on a table. The supertype: one row per constraint whatever its form, discriminated by constraint_type as the standard''s TABLE_CONSTRAINTS is. Filtered to what jOOQ''s generated model carries: PRIMARY KEY, UNIQUE and FOREIGN KEY. CHECK, NOT NULL and deferrability are absent, and arrive as further type values rather than as new relations.';
COMMENT ON COLUMN sql_constraint.table_schema IS 'SQL schema the table lives in';
COMMENT ON COLUMN sql_constraint.table_name IS 'SQL table name';
COMMENT ON COLUMN sql_constraint.constraint_name IS 'SQL constraint name';
COMMENT ON COLUMN sql_constraint.constraint_type IS 'the standard''s TABLE_CONSTRAINTS vocabulary; the domain is closed over what the catalog walk reads, so a violation is a capture bug';

CREATE TABLE sql_constraint_column (
  table_schema    VARCHAR NOT NULL,
  table_name      VARCHAR NOT NULL,
  constraint_name VARCHAR NOT NULL,
  position        INT     NOT NULL,
  column_name     VARCHAR NOT NULL,
  PRIMARY KEY (table_schema, table_name, constraint_name, position),
  FOREIGN KEY (table_schema, table_name, constraint_name)
    REFERENCES sql_constraint (table_schema, table_name, constraint_name),
  FOREIGN KEY (table_schema, table_name, column_name)
    REFERENCES sql_column (table_schema, table_name, column_name)
);
COMMENT ON TABLE sql_constraint_column IS 'An ordered column of a constraint: the key columns of a primary key or a unique constraint, and the referencing columns of a foreign key, in one relation for all three forms as KEY_COLUMN_USAGE does. A foreign key''s target columns are not here; they are the referenced constraint''s own rows, matched on position.';
COMMENT ON COLUMN sql_constraint_column.table_schema IS 'SQL schema the table lives in';
COMMENT ON COLUMN sql_constraint_column.table_name IS 'SQL table name';
COMMENT ON COLUMN sql_constraint_column.constraint_name IS 'SQL constraint name';
COMMENT ON COLUMN sql_constraint_column.position IS '0-based position in the constraint''s column list';
COMMENT ON COLUMN sql_constraint_column.column_name IS 'SQL column name';

CREATE TABLE sql_primary_key (
  table_schema    VARCHAR NOT NULL,
  table_name      VARCHAR NOT NULL,
  constraint_name VARCHAR NOT NULL,
  PRIMARY KEY (table_schema, table_name),
  FOREIGN KEY (table_schema, table_name, constraint_name)
    REFERENCES sql_constraint (table_schema, table_name, constraint_name)
);
COMMENT ON TABLE sql_primary_key IS 'Table T''s primary key is constraint C. Keyed by the table, because a table has at most one, which is what makes the cardinality structural instead of a gate query over a flag.';
COMMENT ON COLUMN sql_primary_key.table_schema IS 'SQL schema the table lives in';
COMMENT ON COLUMN sql_primary_key.table_name IS 'SQL table name';
COMMENT ON COLUMN sql_primary_key.constraint_name IS 'the name of the PRIMARY KEY constraint in sql_constraint';

CREATE TABLE sql_referential_constraint (
  table_schema               VARCHAR NOT NULL,
  table_name                 VARCHAR NOT NULL,
  constraint_name            VARCHAR NOT NULL,
  referenced_schema          VARCHAR NOT NULL,
  referenced_table           VARCHAR NOT NULL,
  referenced_constraint_name VARCHAR NOT NULL,
  PRIMARY KEY (table_schema, table_name, constraint_name),
  FOREIGN KEY (table_schema, table_name, constraint_name)
    REFERENCES sql_constraint (table_schema, table_name, constraint_name),
  FOREIGN KEY (referenced_schema, referenced_table, referenced_constraint_name)
    REFERENCES sql_constraint (table_schema, table_name, constraint_name)
);
COMMENT ON TABLE sql_referential_constraint IS 'A foreign key references a constraint, the foreign-key-only extension of sql_constraint. Referencing the constraint rather than the table is what SQL declares; the target columns are that constraint''s own sql_constraint_column rows matched on position, which is how both Oracle and the standard resolve them and is guaranteed by SQL semantics, never copied onto the referencing row. Implicit-path inference ("exactly one FK between these two tables") is a derivation over this relation, not a captured fact.';
COMMENT ON COLUMN sql_referential_constraint.table_schema IS 'schema of the declaring table';
COMMENT ON COLUMN sql_referential_constraint.table_name IS 'the declaring (source) table';
COMMENT ON COLUMN sql_referential_constraint.constraint_name IS 'SQL constraint name';
COMMENT ON COLUMN sql_referential_constraint.referenced_schema IS 'schema of the referenced constraint''s table; two thirds of the composite reference, not a denormalisation';
COMMENT ON COLUMN sql_referential_constraint.referenced_table IS 'the referenced constraint''s table';
COMMENT ON COLUMN sql_referential_constraint.referenced_constraint_name IS 'the referenced constraint''s name';

CREATE TABLE sql_index (
  table_schema VARCHAR NOT NULL,
  table_name   VARCHAR NOT NULL,
  index_name   VARCHAR NOT NULL,
  PRIMARY KEY (table_schema, table_name, index_name),
  FOREIGN KEY (table_schema, table_name) REFERENCES sql_table (table_schema, table_name)
);
COMMENT ON TABLE sql_index IS 'An index exists on a table (@order(index:) and @index resolve against it). Filtered: jOOQ''s Table.getIndexes() excludes the indexes backing a primary key or unique constraint, so those are absent here and present in sql_constraint instead. @order(index:) naming a primary key''s index therefore resolves against a documented absence rather than an apparent one.';
COMMENT ON COLUMN sql_index.table_schema IS 'SQL schema the table lives in';
COMMENT ON COLUMN sql_index.table_name IS 'SQL table name';
COMMENT ON COLUMN sql_index.index_name IS 'SQL index name';

CREATE TABLE sql_index_column (
  table_schema VARCHAR NOT NULL,
  table_name   VARCHAR NOT NULL,
  index_name   VARCHAR NOT NULL,
  position     INT     NOT NULL,
  column_name  VARCHAR NOT NULL,
  PRIMARY KEY (table_schema, table_name, index_name, position),
  FOREIGN KEY (table_schema, table_name, index_name)
    REFERENCES sql_index (table_schema, table_name, index_name)
);
COMMENT ON TABLE sql_index_column IS 'An ordered column of an index.';
COMMENT ON COLUMN sql_index_column.table_schema IS 'SQL schema the table lives in';
COMMENT ON COLUMN sql_index_column.table_name IS 'SQL table name';
COMMENT ON COLUMN sql_index_column.index_name IS 'SQL index name';
COMMENT ON COLUMN sql_index_column.position IS '0-based position in the index''s column list';
COMMENT ON COLUMN sql_index_column.column_name IS 'SQL column name';


-- ==== JVM classpath facts =========================================================
-- What the classfiles on the compile classpath declare, in the JVM's vocabulary: classes,
-- methods and their parameters, record components, scalar-type fields. The rows are read by a
-- bytecode-only scan, so nothing here is a class graphitron owns or a role it assigns; a jar
-- class an author may name in @record / @service / @enum / @scalarType earns a row on the same
-- terms as a reactor one. Javadoc and source positions deliberately stay out; those live on the
-- LSP's SourceWalker cadence and are joined at request time, so a .java edit is seen without a
-- generator rebuild.
CREATE TABLE jvm_class (
  class_name  VARCHAR NOT NULL,
  class_kind  VARCHAR NOT NULL,
  source_name VARCHAR NOT NULL,
  PRIMARY KEY (class_name),
  FOREIGN KEY (source_name) REFERENCES store_source (source_name),
  CHECK (class_kind IN ('CLASS', 'INTERFACE', 'ENUM', 'RECORD', 'ANNOTATION'))
);
COMMENT ON TABLE jvm_class IS 'A class exists on the compile classpath, as the codegen loader would resolve it. Filtered: public, non-synthetic, top-level (a simple name containing $ is skipped, so nested classes are absent), and outside the generated jOOQ package. A resolution detection over this relation reads those filters as absence, so they are stated rather than implied.';
COMMENT ON COLUMN jvm_class.class_name IS 'fully qualified binary name';
COMMENT ON COLUMN jvm_class.class_kind IS 'the classfile''s declared form; the domain is closed over classfile shapes, so a violation is a capture bug';
COMMENT ON COLUMN jvm_class.source_name IS 'the classpath entry it was read from; the partition this row belongs to. A class present under more than one entry is captured once, at the entry that comes first in classpath order, which is where a classloader would resolve it';

CREATE TABLE jvm_method (
  class_name        VARCHAR NOT NULL,
  method_name       VARCHAR NOT NULL,
  descriptor        VARCHAR NOT NULL,
  return_type       VARCHAR NOT NULL,
  returns_condition BOOLEAN NOT NULL,
  PRIMARY KEY (class_name, method_name, descriptor),
  FOREIGN KEY (class_name) REFERENCES jvm_class (class_name)
);
COMMENT ON TABLE jvm_method IS 'A public method exists on a class in the census. Filtered: public and non-synthetic, constructors and class initializers excluded.';
COMMENT ON COLUMN jvm_method.class_name IS 'the fully-qualified Java class name as written';
COMMENT ON COLUMN jvm_method.method_name IS 'the method name; not a key on its own, overloads share it';
COMMENT ON COLUMN jvm_method.descriptor IS 'raw JVM descriptor; the overload discriminator that keeps this key natural';
COMMENT ON COLUMN jvm_method.return_type IS 'erased source-form return type';
COMMENT ON COLUMN jvm_method.returns_condition IS 'matched on the un-erased org.jooq.Condition descriptor, so a consumer''s own Condition type does not false-match';

CREATE TABLE jvm_method_parameter (
  class_name     VARCHAR NOT NULL,
  method_name    VARCHAR NOT NULL,
  descriptor     VARCHAR NOT NULL,
  position       INT     NOT NULL,
  parameter_name VARCHAR,
  parameter_type VARCHAR NOT NULL,
  PRIMARY KEY (class_name, method_name, descriptor, position),
  FOREIGN KEY (class_name, method_name, descriptor)
    REFERENCES jvm_method (class_name, method_name, descriptor)
);
COMMENT ON TABLE jvm_method_parameter IS 'An ordered parameter of a captured method. Deliberately no parameter-source column: which ParamSource a parameter binds to is decided per directive application, not per method, so it is a derived relation keyed by the application coordinate and lands with its first consumer.';
COMMENT ON COLUMN jvm_method_parameter.class_name IS 'the fully-qualified Java class name as written';
COMMENT ON COLUMN jvm_method_parameter.method_name IS 'the owning method name';
COMMENT ON COLUMN jvm_method_parameter.descriptor IS 'the owning method''s raw JVM descriptor';
COMMENT ON COLUMN jvm_method_parameter.position IS '0-based parameter position';
COMMENT ON COLUMN jvm_method_parameter.parameter_name IS 'NULL when the consumer compiled without -parameters';
COMMENT ON COLUMN jvm_method_parameter.parameter_type IS 'erased source-form parameter type';

CREATE TABLE jvm_record_component (
  class_name     VARCHAR NOT NULL,
  component_name VARCHAR NOT NULL,
  position       INT     NOT NULL,
  display_type   VARCHAR NOT NULL,
  PRIMARY KEY (class_name, component_name),
  FOREIGN KEY (class_name) REFERENCES jvm_class (class_name)
);
COMMENT ON TABLE jvm_record_component IS 'A record component of a record class in the census, read from the classfile RecordAttribute rather than from any bytecode; backs record-mapping facts.';
COMMENT ON COLUMN jvm_record_component.class_name IS 'the fully-qualified Java class name as written';
COMMENT ON COLUMN jvm_record_component.component_name IS 'the record component name';
COMMENT ON COLUMN jvm_record_component.position IS 'component position in the record header';
COMMENT ON COLUMN jvm_record_component.display_type IS 'erased display form of the component type';

CREATE TABLE jvm_scalar_type_field (
  class_name VARCHAR NOT NULL,
  field_name VARCHAR NOT NULL,
  PRIMARY KEY (class_name, field_name),
  FOREIGN KEY (class_name) REFERENCES jvm_class (class_name)
);
COMMENT ON TABLE jvm_scalar_type_field IS 'A public static field whose declared type is exactly graphql.schema.GraphQLScalarType (backs @scalarType resolution). Filtered by that descriptor, which is why the selector is in the name: a total-sounding jvm_static_field would mislead about the contents. final is deliberately not required, the reflective resolver binding a non-final field just as well, so these are not necessarily constants.';
COMMENT ON COLUMN jvm_scalar_type_field.class_name IS 'the fully-qualified Java class name as written';
COMMENT ON COLUMN jvm_scalar_type_field.field_name IS 'the field name, matched on the exact GraphQLScalarType descriptor';
