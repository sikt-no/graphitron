-- The graphitron fact schema: the base relations the generator's capture loads and the
-- post-capture oracle writers fill.
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
-- source order is an explicit ordinal column. Comment prose, and the meta_ rows' definition
-- and reason text, is AsciiDoc (inline subset only), interpolated verbatim into the generated
-- schema reference; the renderability gate beside the coverage gate holds that line.
--
-- Picking a prefix for a new relation: a family is named for whose vocabulary its rows are
-- written in, never for its reader or its role. The family roster, each family's charter and
-- the placement of the deliberately prefix-less relations are authored rows in the meta_
-- views at the tail of this file, closed against the observed relations by the schema gates
-- and rendered into the generated schema reference, so the roster cannot drift from the file
-- the way a prose enumeration in this header once did.
--
-- Cadence is its own axis, orthogonal to the vocabulary a prefix names. A family whose writer
-- runs after capture (javac_ is the first) has its own writer on that writer's cadence, and
-- capture clears the run's own graph partition of such a family before regenerating, because
-- its rows describe an emitted tree the run is about to replace.

-- ==== Store bookkeeping ===========================================================
-- The store's own family: the record of what it read, what it was built from, and which graphs
-- it holds. Not a transcription of the consumer's schema, database or classpath, which is what
-- the other four prefixes are named for; the recipe relations below hold configuration the run
-- held in hand, which is the discriminator that keeps them in this family. One partition-delete
-- mechanism covers all three source kinds because a partition delete is one mechanism whether
-- the source is a schema file, a compile-output directory, or a jar. First in the file because
-- store_graph anchors the graph partition every SDL relation's key leads with.

CREATE TABLE store_graph (
  graph_name       VARCHAR NOT NULL,
  base_dir         VARCHAR NOT NULL,
  build_file_path  VARCHAR,
  build_file_stamp VARCHAR,
  last_captured    TIMESTAMP NOT NULL,
  PRIMARY KEY (graph_name)
);
COMMENT ON TABLE store_graph IS 'A graph the store holds: the anchor of the graph_name partition dimension, one row per module ever captured into this store. Two discriminators keep the DDL''s FK conventions readable against this relation. First, why the SDL roots carry an FK here while the SDL-to-store_source FK was declined: the graph is ambient before the walk begins and NOT NULL on every row, while the source rows are a summary collected last and nullable at schema-level sites, so the FK doctrine admits one and not the other. Second: any derivation joining an SDL fact to a catalog or classpath fact (graphitron_service''s class name against jvm_class, graphitron_table''s table reference against sql_table) is underdetermined in a shared store until a membership relation says which sources are the joining graph''s; store_graph_source below is that relation, landed with its first consumer (the inferred claim view), and such a join scopes its catalog side through it.';
COMMENT ON COLUMN store_graph.graph_name IS 'the graph''s configured name (the Maven <graphName> parameter, defaulting to the module''s artifactId); the value every partitioned key leads with';
COMMENT ON COLUMN store_graph.base_dir IS 'the capturing run''s base directory, absolute and normalized. NOT NULL and deliberately not grouped with the nullable build-identity pair: every run has a directory (RewriteContext requires basedir of every caller) even when it has no build file, and this column is what the ownership check reads when a run''s graph_name is already recorded against a different directory';
COMMENT ON COLUMN store_graph.build_file_path IS 'the module''s build file (its pom), absolute and normalized; NULL on a programmatic run with no build file';
COMMENT ON COLUMN store_graph.build_file_stamp IS 'content hash of the build file, the graph''s build identity: the remembered recipe is trusted only while the build file still hashes to this, and a mismatch marks the recipe possibly stale until the module''s own next build repairs it';
COMMENT ON COLUMN store_graph.last_captured IS 'when this graph''s own run last captured it; the age half of the age/currency distinction, and the bookkeeping a future eviction surface reads';

CREATE TABLE store_graph_schema_input (
  graph_name       VARCHAR NOT NULL,
  ordinal          INT     NOT NULL,
  kind             VARCHAR NOT NULL,
  entry_value      VARCHAR NOT NULL,
  tag              VARCHAR,
  description_note VARCHAR,
  PRIMARY KEY (graph_name, ordinal),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name),
  CHECK (kind IN ('pattern', 'file', 'named'))
);
COMMENT ON TABLE store_graph_schema_input IS 'The graph''s SDL recipe, one row per resolved recipe entry: configuration the run held in hand, written fresh by every run, never a derivation over captured rows. It records what the read-set never can: how to find the graph''s schema files, including ones that do not exist yet, so a currency check can re-expand the globs over base_dir without building the module. One discriminated relation rather than one per kind, because the ordinal is the recipe''s spine and splitting the relations would shatter the one ordering key. A reader of another graph''s recipe rows is maintenance machinery and counts as such exactly while it writes no conclusions outside the store_ family; these rows never join the cross-graph consumer read surface, whose enumeration axis is store_graph and store_graph_supergraph and whose payload axis is the SDL-derived families only.';
COMMENT ON COLUMN store_graph_schema_input.graph_name IS 'the owning graph''s partition, anchored by store_graph';
COMMENT ON COLUMN store_graph_schema_input.ordinal IS 'entry position in the resolved configuration, document order';
COMMENT ON COLUMN store_graph_schema_input.kind IS 'a closed taxonomy of what entry_value holds: a glob pattern a build resolved, a literal file a programmatic caller handed over, or a literal bare label. The two literal kinds transcribe which door the entry''s source came through, so a replay recovers the arm from the row instead of re-asking the filesystem a question about a stored string';
COMMENT ON COLUMN store_graph_schema_input.entry_value IS 'the entry as configured: an include pattern in the recipe''s one glob dialect (SchemaRecipe owns the expansion) when kind is pattern, and the source''s canonical rendering when it is file or named';
COMMENT ON COLUMN store_graph_schema_input.tag IS 'the entry''s tag, when configured; not optional fidelity, since the tag applier runs above the capture cut and a replay without it would mint different rows than the graph''s own build';
COMMENT ON COLUMN store_graph_schema_input.description_note IS 'the entry''s description note, when configured; kept for the same replay-fidelity reason as tag';

CREATE TABLE store_graph_schema_extension (
  graph_name VARCHAR NOT NULL,
  ordinal    INT     NOT NULL,
  extension  VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, ordinal),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
COMMENT ON TABLE store_graph_schema_extension IS 'The recipe''s effective schema-file-extension filter, one row per accepted extension. A per-run set rather than a per-binding one, which is why it sits beside the bindings rather than under them.';
COMMENT ON COLUMN store_graph_schema_extension.graph_name IS 'the owning graph''s partition, anchored by store_graph';
COMMENT ON COLUMN store_graph_schema_extension.ordinal IS 'stable position in the resolved set, for faithful replay';
COMMENT ON COLUMN store_graph_schema_extension.extension IS 'an accepted schema-file extension including the leading dot, as configured';

CREATE TABLE store_graph_supergraph (
  graph_name       VARCHAR NOT NULL,
  supergraph_name  VARCHAR NOT NULL,
  PRIMARY KEY (graph_name),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
COMMENT ON TABLE store_graph_supergraph IS 'Which supergraph a graph declared itself a subgraph of: the graph''s own declaration of its <supergraph> parameter, minted and cleared by the graph''s own run like every other graph-keyed row. What it asserts is grouping, not federation. Declaring membership does not make a graph federated and is not policed against the SDL''s opt-in, which graphitron_link already records as a predicate over the @link url; the grouping is deliberately usable before any federation SDL lands, since a subgraph under development may declare its home before its first @key is written. Only graphs with a declared supergraph are registered, so the row''s presence is the fact and a standalone graph has no row; a nullable column on the anchor would be the field every construction site may leave null, which this store spells structurally instead. Three absences collapse deliberately, because every reader''s safe answer is the same "not a peer": a graph whose author declared nothing, a programmatic run that was never asked, and a graph whose anchor a diagnostics preamble minted before capture ran. Deliberately not a store_supergraph entity relation: no single run would mint or may clear such a row, and StoreRefresh derives the ownership-scoped clear set from the presence of a graph_name column, so the supergraph exists here as a value graphs declare and never as an entity anything owns. Single-valued by the (graph_name) key; if federation practice''s multi-supergraph publication ever has to be admitted, the widening is the key growing to (graph_name, supergraph_name), which costs a store-stamp roll rather than a data migration. This relation and store_graph are the whole of the cross-graph consumer read surface''s enumeration axis; nothing else configuration-shaped joins it, and what a surface reads about a peer stays SDL-derived.';
COMMENT ON COLUMN store_graph_supergraph.graph_name IS 'the declaring graph''s partition, anchored by store_graph; also the key, which is where the single-valued claim is enforced structurally';
COMMENT ON COLUMN store_graph_supergraph.supergraph_name IS 'the declared supergraph''s name, as the <supergraph> parameter spelled it, with an empty element collapsed to absent by the decode rather than stored blank. Paired with graph_name it is the store''s rendering of the addressing federation already uses, which is why <graphName>''s own documentation speaks of the subgraph''s published name. A graph''s peers are the graphs this relation joins to over this column, a self-join between non-null values, so two standalone graphs never group by accident and two supergraphs in one workspace store coexist mutually invisible';

-- The rest of the configuration family. Every parameter the build supplied is transcribed, because a
-- run that has exited cannot be asked again and the reader served is the one with no build to run at
-- all: a sibling module's configuration, a non-Maven entry point, a maintenance surface answering
-- questions about a cold graph. On the Maven path there is no parse to skip, Maven having injected
-- the parameters before any mojo ran, so that is deliberately not the claim.
--
-- Four rules shape the rows, and none of them is decided per parameter. Grain: relations group by
-- joint presence and joint meaning, with named typed columns and never a (parameter, value) pair,
-- which is the line between a relation and a key-value bag wearing a relation's clothes. Structured
-- values decompose to typed columns rather than rendering to a string a later reader has to re-parse.
-- A sealed parameter's relations follow the seal, discriminating the arm, so a reader cannot spell a
-- combination the value type's constructor refuses. And absence is structural throughout: what the
-- run effectively used is transcribed, a parameter with no default has no default to record so its
-- absence is the missing row, and no nullable column conflates "configured nothing" with "not asked".
-- Whether an author typed a value is a pom fact rather than a run fact and stays recoverable from
-- the file store_graph.build_file_stamp already stamps, so no authored flag carries it.

CREATE TABLE store_graph_output (
  graph_name       VARCHAR NOT NULL,
  output_package   VARCHAR NOT NULL,
  jooq_package     VARCHAR NOT NULL,
  output_directory VARCHAR NOT NULL,
  PRIMARY KEY (graph_name),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
COMMENT ON TABLE store_graph_output IS 'Where a generating run wrote: the three output coordinates travelling together, because they are present together on any generating run and jointly answer one question. A validate-only run has no row rather than a row carrying the inert package sentinel the validate goal substitutes to satisfy the context''s non-null contract: that sentinel is the run''s own admission that it had no output coordinates, and transcribing it would mint the derived fact that can disagree.';
COMMENT ON COLUMN store_graph_output.graph_name IS 'the owning graph''s partition, anchored by store_graph';
COMMENT ON COLUMN store_graph_output.output_package IS 'the root Java package generation wrote under, from <outputPackage>';
COMMENT ON COLUMN store_graph_output.jooq_package IS 'the root Java package of the consumer''s jOOQ-generated catalog, from <jooqPackage>; what every @table and @field was resolved against';
COMMENT ON COLUMN store_graph_output.output_directory IS 'the directory generation wrote sources into, absolute and normalized, from <outputDirectory> resolved against the base directory';

CREATE TABLE store_graph_tenant_column (
  graph_name  VARCHAR NOT NULL,
  column_name VARCHAR NOT NULL,
  PRIMARY KEY (graph_name),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
COMMENT ON TABLE store_graph_tenant_column IS 'The database-per-tenant column declaration, from <tenantColumn>. Single-valued, optional and run-owned, so its own graph-keyed relation whose row presence is the fact; a single-tenant build has no row. Deliberately not a column beside store_graph''s base_dir and last_captured, even though it is single-valued and run-owned too: it is generation payload rather than a fact about how the partition groups or where it lives, and beside the anchor it would be the first brick of the key-value bag.';
COMMENT ON COLUMN store_graph_tenant_column.graph_name IS 'the declaring graph''s partition, anchored by store_graph';
COMMENT ON COLUMN store_graph_tenant_column.column_name IS 'the column name as configured; matched against catalog columns the way column lookups match, Java name first then SQL name, both case-insensitively';

CREATE TABLE store_graph_lint_disabled_rule (
  graph_name VARCHAR NOT NULL,
  rule_id    VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, rule_id),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
COMMENT ON TABLE store_graph_lint_disabled_rule IS 'The <lint><disabledRules> half, one row per silenced rule id. Decomposed rather than rendered: a rendered block would be a string a later reader has to re-parse, which is the shape the recipe''s source names exist to remove, and permitting a rendered form per parameter would reintroduce the untyped default door. The two <lint> halves are a genuine conjunction (LintConfig is a plain record of both) but they are not the same shape, which is why they are two relations rather than one discriminated one: this half is a Set and takes no ordinal, its sibling is a List and takes one, and forcing them together would need a nullable ordinal.';
COMMENT ON COLUMN store_graph_lint_disabled_rule.graph_name IS 'the owning graph''s partition, anchored by store_graph';
COMMENT ON COLUMN store_graph_lint_disabled_rule.rule_id IS 'the disabled rule''s id as configured; the value is the key, there being no position to record. An ordinal here would record the JVM''s iteration order over a Set and call it a position';

CREATE TABLE store_graph_lint_excluded_type (
  graph_name   VARCHAR NOT NULL,
  ordinal      INT     NOT NULL,
  type_pattern VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, ordinal),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
COMMENT ON TABLE store_graph_lint_excluded_type IS 'The <lint><excludedTypes> half, one row per type-name glob excluded from the SDL lint engine. Ordinal-keyed because the configured value is a List and its order is the author''s, which is the grain rule''s "an ordinal only where the source is genuinely ordered".';
COMMENT ON COLUMN store_graph_lint_excluded_type.graph_name IS 'the owning graph''s partition, anchored by store_graph';
COMMENT ON COLUMN store_graph_lint_excluded_type.ordinal IS 'position in the configured list, document order';
COMMENT ON COLUMN store_graph_lint_excluded_type.type_pattern IS 'the type-name glob as configured';

CREATE TABLE store_graph_session_mount (
  graph_name   VARCHAR NOT NULL,
  mount_method VARCHAR NOT NULL,
  PRIMARY KEY (graph_name),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
COMMENT ON TABLE store_graph_session_mount IS 'The <sessionState> <mount> reference: the consumer''s static Java method that mounts identity on each acquired connection, as authored. Row presence is the fact, per the family''s absence rule: no row means no identity is mounted. The primary key on graph_name alone makes "at most one mount per graph" structural. Only the authored string lands here; the reflected signature is a build-time model fact, never stored back into this provenance family.';
COMMENT ON COLUMN store_graph_session_mount.graph_name IS 'the configuring graph''s partition, anchored by store_graph';
COMMENT ON COLUMN store_graph_session_mount.mount_method IS 'the mounting method as authored, fqcn#method, from <mount>';

CREATE TABLE store_graph_session_unmount (
  graph_name     VARCHAR NOT NULL,
  unmount_method VARCHAR NOT NULL,
  PRIMARY KEY (graph_name),
  FOREIGN KEY (graph_name) REFERENCES store_graph_session_mount (graph_name)
);
COMMENT ON TABLE store_graph_session_unmount IS 'The optional <unmount> reference beside the mount. Row presence is the fact: no row means the supported mount-only configuration (the next request''s mount overwrites wholesale), which the reconciler admits without ceremony, and the foreign key to store_graph_session_mount is the "unmount without mount is a defect" rule made structural.';
COMMENT ON COLUMN store_graph_session_unmount.graph_name IS 'the configuring graph''s partition, anchored by store_graph_session_mount';
COMMENT ON COLUMN store_graph_session_unmount.unmount_method IS 'the unmounting method as authored, fqcn#method, from <unmount>';

CREATE TABLE store_source (
  source_name VARCHAR NOT NULL,
  source_kind VARCHAR NOT NULL,
  stamp       VARCHAR,
  last_seen   TIMESTAMP NOT NULL,
  PRIMARY KEY (source_name),
  CHECK (source_kind IN ('SCHEMA_FILE', 'DIRECTORY', 'JAR', 'JOOQ_SCHEMA'))
);
CREATE TABLE store_graph_source (
  graph_name  VARCHAR NOT NULL,
  source_name VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, source_name),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name),
  FOREIGN KEY (source_name) REFERENCES store_source (source_name)
);
COMMENT ON TABLE store_graph_source IS 'The membership relation store_graph''s comment defers to: which sources are the joining graph''s. One row per source the graph''s run actually read, every kind alike (schema files, jOOQ schema packages, classpath entries), because kind is an axis on store_source and a kind-filtered membership would make completeness a function of which consumers had shipped, leaving a reader unable to tell "not this graph''s" from "kind not captured yet". What the run read, not configuration: the recipe rows above hold patterns the run held in hand, including files that do not exist yet, while a row here names a source the walk met. Graph-keyed, so a warm capture clears and rewrites exactly its own graph''s rows. Any derivation joining a graph-keyed fact to a source-keyed one (the column-match claim view is the first) scopes its catalog side through this relation, which is what keeps one graph''s resolution from seeing a sibling module''s tables in a shared store.';
COMMENT ON COLUMN store_graph_source.graph_name IS 'the member graph, anchored by store_graph';
COMMENT ON COLUMN store_graph_source.source_name IS 'a source the graph''s run read, anchored by store_source; the scan''s hand-built stand-ins record against the empty source name like their class rows do';
COMMENT ON TABLE store_source IS 'A source the store read, store-global rather than graph-keyed: it can say what a file hashed to, never which graph read it. Every base relation is partitionable by the source that produced it: a refresh deletes exactly the rows one source wrote and re-walks it, so a relation unreachable from a source row is one the store can only ever discard wholesale.';
COMMENT ON COLUMN store_source.source_name IS 'the schema file path, the classpath entry path, or the generated package a jOOQ schema lives in, as the reader spelled it';
COMMENT ON COLUMN store_source.source_kind IS 'a closed taxonomy: a schema file, a directory root, a jar, or a generated jOOQ schema package. The last names jOOQ deliberately, unlike the sql_ family: a family is named for whose vocabulary its rows are written in and jOOQ owns none of SQL''s, but a source is named for what it is, and a generated package is jOOQ''s artefact';
COMMENT ON COLUMN store_source.stamp IS 'content hash, so an unchanged source is read once and a currency check can re-hash a cold graph''s files without building its module. Schema files are stamped at capture time (one file re-read per schema file, priced against exactly that reader); NULL where nothing resolves to a regular file to hash: a directory root changes on every compile, the bundled directives.graphqls is a resource name, a programmatic caller may hand a bare name, and a jOOQ schema is a package spread across the classpath whose walk is cheap enough not to need one. Also NULL while the source''s rows are being written, and set only once they are all in, so a run that dies mid-load leaves a partition that is re-walked rather than one that claims to be complete';
COMMENT ON COLUMN store_source.last_seen IS 'when a run last named this source in its input set; the age half of the age/currency distinction, and the bookkeeping a future eviction surface reads';

CREATE TABLE store_stamp (
  singleton         CHAR(1) NOT NULL,
  ddl_hash          VARCHAR NOT NULL,
  generator_version VARCHAR NOT NULL,
  PRIMARY KEY (singleton),
  CHECK (singleton = 'X')
);
COMMENT ON TABLE store_stamp IS 'What this store was built from. At most one row, stated structurally. A persisted store is never state of record: this row decides whether an existing file is intelligible at all. The same stamp names the store''s directory segment, so a mismatching file is normally never even opened; meeting one here means it was moved or damaged by hand, and the store falls back to an in-memory one and leaves the file alone.';
COMMENT ON COLUMN store_stamp.singleton IS 'always ''X''; the CHECK plus the primary key is how a relation says "at most one row" in SQL';
COMMENT ON COLUMN store_stamp.ddl_hash IS 'hash of the DDL resource the store was created from, so any schema edit at all invalidates a persisted file';
COMMENT ON COLUMN store_stamp.generator_version IS 'the capturing generator''s implementation version, or a placeholder where the manifest declares none. It invalidates a persisted file across releases; within one, a capture change that alters row content without touching the DDL is not caught here, and deleting the store''s cache directory is the remedy';

-- ==== SDL existence facts =========================================================
-- One row per element the SDL declares. Capture is total: built-in scalars, @oneOf, federation
-- definitions arriving via @link, and user-authored directives are ordinary rows. Source
-- positions follow the 1-based graphql-java convention and are NULL only for engine-provided
-- elements no SDL line declares (built-in scalars). Elements contributed by the bundled
-- directives.graphqls are stamped with that resource name as source_name (for a type, the
-- stamp sits on its declaration rows); consumers wanting user-authored declarations filter on
-- it, as CatalogBuilder.projectTypeDefinitionLocations does today.
CREATE TABLE graphql_type (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  kind          VARCHAR NOT NULL,
  description   VARCHAR,
  PRIMARY KEY (graph_name, type_name),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name),
  CHECK (kind IN ('OBJECT', 'INTERFACE', 'UNION', 'ENUM', 'INPUT_OBJECT', 'SCALAR'))
);
COMMENT ON TABLE graphql_type IS 'A named type is declared or extended in the schema; this row is the name''s existence, written by capture from whichever site it meets first (macro- contributed sites included), and graphql_type_declaration carries every site. The declared-or-extended reading is load-bearing: it is what makes the site rows'' FK structural (capture writes this row before any site row), and on a base-less extension chain (an author error a detection reports) the row still exists, anchored by the extension sites.';
COMMENT ON COLUMN graphql_type.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphql_type.type_name IS 'the GraphQL type name; the coordinate every other SDL fact hangs off';
COMMENT ON COLUMN graphql_type.kind IS 'the first declaration site''s form in merge order (the base definition''s, on a well-formed schema)';
COMMENT ON COLUMN graphql_type.description IS 'SDL description string; net-new as a persisted fact (today read live off retained graphql-java objects). Extensions cannot carry descriptions, so this is the base definition''s when one exists';

CREATE TABLE graphql_type_declaration (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  source_name   VARCHAR NOT NULL,
  source_line   INT     NOT NULL,
  source_column INT     NOT NULL,
  merge_ordinal INT     NOT NULL,
  is_extension  BOOLEAN NOT NULL,
  kind          VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, source_name, source_line, source_column),
  FOREIGN KEY (graph_name, type_name) REFERENCES graphql_type (graph_name, type_name),
  CHECK (kind IN ('OBJECT', 'INTERFACE', 'UNION', 'ENUM', 'INPUT_OBJECT', 'SCALAR'))
);
COMMENT ON TABLE graphql_type_declaration IS 'A declaration site of a type: the base definition or one extension. All five extension kinds are live today, so a type''s effective shape may be assembled from several files; this relation records who contributed what and indexes the incremental-refresh unit ("which types does this file touch"). Engine-provided types (built-in scalars) have no declaration rows.';
COMMENT ON COLUMN graphql_type_declaration.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphql_type_declaration.type_name IS 'the type this site declares or extends';
COMMENT ON COLUMN graphql_type_declaration.source_name IS 'the site''s file; a site is a syntactic occurrence, so its location is its identity';
COMMENT ON COLUMN graphql_type_declaration.source_line IS 'line of the site, 1-based';
COMMENT ON COLUMN graphql_type_declaration.source_column IS 'in the key because a line does not identify a site: two extensions of one type can share a line in minified SDL';
COMMENT ON COLUMN graphql_type_declaration.merge_ordinal IS 'capture-assigned position in merge order: the base definition, then extensions in document order; on a base-less chain the first extension holds 0. Dense per type (a gate), and the order behind every element ordinal';
COMMENT ON COLUMN graphql_type_declaration.is_extension IS 'FALSE exactly at merge_ordinal 0 on a well-formed schema; a base-less extension chain is an author error a detection reports, never a constraint';
COMMENT ON COLUMN graphql_type_declaration.kind IS 'the declaration form written at this site; a mismatch against the type row''s kind is a detection';

CREATE TABLE graphql_field (
  graph_name          VARCHAR NOT NULL,
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
  PRIMARY KEY (graph_name, type_name, field_name),
  FOREIGN KEY (graph_name, type_name) REFERENCES graphql_type (graph_name, type_name),
  FOREIGN KEY (graph_name, type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (graph_name, type_name, source_name, source_line, source_column),
  CHECK (is_list OR item_non_null IS NULL)
);
COMMENT ON TABLE graphql_field IS 'A field exists at a coordinate. OBJECT and INTERFACE parents make it an output field, INPUT_OBJECT parents an input field; the join decides.';
COMMENT ON COLUMN graphql_field.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
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
  graph_name        VARCHAR NOT NULL,
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
  PRIMARY KEY (graph_name, type_name, field_name, argument_name),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphql_field (graph_name, type_name, field_name),
  CHECK (is_list OR item_non_null IS NULL)
);
COMMENT ON TABLE graphql_argument IS 'An argument exists on a field. Net-new coordinate: today arguments are classified per-field and mostly projected away, with no location kept.';
COMMENT ON COLUMN graphql_argument.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
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
  graph_name          VARCHAR NOT NULL,
  type_name           VARCHAR NOT NULL,
  value_name          VARCHAR NOT NULL,
  ordinal             INT     NOT NULL,
  declaration_line    INT     NOT NULL,
  declaration_column  INT     NOT NULL,
  description         VARCHAR,
  source_name         VARCHAR NOT NULL,
  source_line         INT,
  source_column       INT,
  PRIMARY KEY (graph_name, type_name, value_name),
  FOREIGN KEY (graph_name, type_name) REFERENCES graphql_type (graph_name, type_name),
  FOREIGN KEY (graph_name, type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (graph_name, type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphql_enum_value IS 'An enum declares a value. Net-new coordinate; deprecation is not a column because @deprecated is an ordinary applied directive.';
COMMENT ON COLUMN graphql_enum_value.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
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
  graph_name          VARCHAR NOT NULL,
  union_name          VARCHAR NOT NULL,
  member_type_name    VARCHAR NOT NULL,
  ordinal             INT     NOT NULL,
  declaration_line    INT     NOT NULL,
  declaration_column  INT     NOT NULL,
  source_name         VARCHAR NOT NULL,
  source_line         INT,
  source_column       INT,
  PRIMARY KEY (graph_name, union_name, member_type_name),
  FOREIGN KEY (graph_name, union_name) REFERENCES graphql_type (graph_name, type_name),
  FOREIGN KEY (graph_name, union_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (graph_name, type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphql_union_member IS 'A union lists a member type.';
COMMENT ON COLUMN graphql_union_member.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphql_union_member.union_name IS 'the UNION type listing the member';
COMMENT ON COLUMN graphql_union_member.member_type_name IS 'the member type as the union spelled it; author-spelled, no FK';
COMMENT ON COLUMN graphql_union_member.ordinal IS 'position in the effective member list';
COMMENT ON COLUMN graphql_union_member.declaration_line IS 'the contributing site, as on graphql_field';
COMMENT ON COLUMN graphql_union_member.declaration_column IS 'column of the contributing declaration site, the site key''s fourth part';
COMMENT ON COLUMN graphql_union_member.source_name IS 'position of the member token itself; NOT NULL as on graphql_field';
COMMENT ON COLUMN graphql_union_member.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphql_union_member.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphql_implements (
  graph_name          VARCHAR NOT NULL,
  type_name           VARCHAR NOT NULL,
  interface_name      VARCHAR NOT NULL,
  declaration_line    INT     NOT NULL,
  declaration_column  INT     NOT NULL,
  source_name         VARCHAR NOT NULL,
  source_line         INT,
  source_column       INT,
  PRIMARY KEY (graph_name, type_name, interface_name),
  FOREIGN KEY (graph_name, type_name) REFERENCES graphql_type (graph_name, type_name),
  FOREIGN KEY (graph_name, type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (graph_name, type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphql_implements IS 'A type declares that it implements an interface. Stored in declaration direction; today''s model keeps only the inverted interface-to-participants list and reads this edge live off graphql-java.';
COMMENT ON COLUMN graphql_implements.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphql_implements.type_name IS 'the implementing OBJECT or INTERFACE';
COMMENT ON COLUMN graphql_implements.interface_name IS 'the interface as the implementing type spelled it; author-spelled, no FK';
COMMENT ON COLUMN graphql_implements.declaration_line IS 'the contributing site, as on graphql_field';
COMMENT ON COLUMN graphql_implements.declaration_column IS 'column of the contributing declaration site, the site key''s fourth part';
COMMENT ON COLUMN graphql_implements.source_name IS 'position of the interface token itself; NOT NULL as on graphql_field';
COMMENT ON COLUMN graphql_implements.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphql_implements.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphql_root_operation (
  graph_name    VARCHAR NOT NULL,
  operation     VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (graph_name, operation),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name),
  CHECK (operation IN ('QUERY', 'MUTATION', 'SUBSCRIPTION'))
);
COMMENT ON TABLE graphql_root_operation IS 'The schema definition names a root operation type. These rows are the seeds the reachability derivation grows from. The binding is an author-spelled reference, so its dangling case mints a located diagnostic; the position columns are what it locates from. (A double binding cannot reach capture: a schema extension re-binding an operation throws at parse.)';
COMMENT ON COLUMN graphql_root_operation.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphql_root_operation.operation IS 'which root slot';
COMMENT ON COLUMN graphql_root_operation.type_name IS 'the object type serving it';
COMMENT ON COLUMN graphql_root_operation.source_name IS 'position of the binding inside the schema { } block; all three NULL exactly when the binding is the name-convention default no SDL line spells';
COMMENT ON COLUMN graphql_root_operation.source_line IS 'line of the binding; NULL with the siblings when the binding is the name-convention default';
COMMENT ON COLUMN graphql_root_operation.source_column IS 'column of the binding; NULL with the siblings when the binding is the name-convention default';

CREATE TABLE graphql_duplicate_declaration (
  graph_name    VARCHAR NOT NULL,
  source_name   VARCHAR NOT NULL,
  source_line   INT     NOT NULL,
  source_column INT     NOT NULL,
  element_kind  VARCHAR NOT NULL,
  coordinate    VARCHAR NOT NULL,
  value_sdl     VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, source_name, source_line, source_column),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name),
  CHECK (element_kind IN ('TYPE', 'FIELD', 'ARGUMENT', 'ENUM_VALUE',
                          'UNION_MEMBER', 'IMPLEMENTS', 'DIRECTIVE_APPLICATION',
                          'DIRECTIVE_LOCATION', 'DIRECTIVE_ARGUMENT'))
);
COMMENT ON TABLE graphql_duplicate_declaration IS 'The duplicate-declaration overflow, sibling of the semantic stratum''s undecoded-argument relation. The registry retains element-level duplicates without error (a field declared twice in one body or re-declared by an extension, a repeated argument, enum value, union member, or implements entry, a second application of a single-application graphitron directive, a repeated location or formal argument in a directive definition), so every element-level natural key in this schema is author-reachable. Capture is first-wins in merge order; the losing occurrence records here, rendered and located, so no authored text is lost and the duplicate-declaration detection has its row. The element-level kinds became reachable when capture stopped being conditional on the document assembling: assembly does reject these schemas (a twice-declared field is a NonUniqueNameError), but its refusal is now a row in graphql_schema_error rather than an abort, so the same pass captures both the verdict and the retained duplicate this relation holds. A second base definition, of a type or of a directive, is refused one stage earlier, by the registry, whose first-wins admission keeps the winner and reports the loser as a verdict without offering its declaration to capture; the TYPE kind is therefore still reachable only through the LSP''s per-file fragment path, now because the losing declaration never reaches the walk rather than because the registry throws.';
COMMENT ON COLUMN graphql_duplicate_declaration.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
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
  graph_name     VARCHAR NOT NULL,
  directive_name VARCHAR NOT NULL,
  repeatable     BOOLEAN NOT NULL,
  description    VARCHAR,
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  PRIMARY KEY (graph_name, directive_name),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
COMMENT ON TABLE graphql_directive IS 'A directive is defined.';
COMMENT ON COLUMN graphql_directive.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphql_directive.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_directive.repeatable IS 'whether the definition says ''repeatable''; governs the ordinal on applications';
COMMENT ON COLUMN graphql_directive.description IS 'SDL description string, when the author wrote one';
COMMENT ON COLUMN graphql_directive.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphql_directive.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphql_directive.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphql_directive_location (
  graph_name     VARCHAR NOT NULL,
  directive_name VARCHAR NOT NULL,
  location       VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, directive_name, location),
  FOREIGN KEY (graph_name, directive_name) REFERENCES graphql_directive (graph_name, directive_name)
);
COMMENT ON TABLE graphql_directive_location IS 'A directive definition names a permitted location.';
COMMENT ON COLUMN graphql_directive_location.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphql_directive_location.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_directive_location.location IS 'introspection location name, e.g. FIELD_DEFINITION, INPUT_FIELD_DEFINITION';

CREATE TABLE graphql_directive_argument (
  graph_name        VARCHAR NOT NULL,
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
  PRIMARY KEY (graph_name, directive_name, argument_name),
  FOREIGN KEY (graph_name, directive_name) REFERENCES graphql_directive (graph_name, directive_name),
  CHECK (is_list OR item_non_null IS NULL)
);
COMMENT ON TABLE graphql_directive_argument IS 'A directive definition declares a formal argument. Carries the same wrapping decode as graphql_field, so list-ness of a directive argument is a column read, not a string parse.';
COMMENT ON COLUMN graphql_directive_argument.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
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
  graph_name     VARCHAR NOT NULL,
  directive_name VARCHAR NOT NULL,
  ordinal        INT     NOT NULL,
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  PRIMARY KEY (graph_name, directive_name, ordinal),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
COMMENT ON TABLE graphql_schema_directive IS 'A directive is applied to the schema definition (@link lives here).';
COMMENT ON COLUMN graphql_schema_directive.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphql_schema_directive.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_schema_directive.ordinal IS '0 unless the directive is repeatable; repeats number in document order';
COMMENT ON COLUMN graphql_schema_directive.source_name IS 'position of the application site';
COMMENT ON COLUMN graphql_schema_directive.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphql_schema_directive.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphql_schema_directive_arg (
  graph_name              VARCHAR NOT NULL,
  directive_name          VARCHAR NOT NULL,
  ordinal                 INT     NOT NULL,
  directive_argument_name VARCHAR NOT NULL,
  value_sdl               VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, directive_name, ordinal, directive_argument_name),
  FOREIGN KEY (graph_name, directive_name, ordinal)
    REFERENCES graphql_schema_directive (graph_name, directive_name, ordinal)
);
COMMENT ON TABLE graphql_schema_directive_arg IS 'An argument the author passed to a schema-level application.';
COMMENT ON COLUMN graphql_schema_directive_arg.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphql_schema_directive_arg.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_schema_directive_arg.ordinal IS 'the owning application''s ordinal';
COMMENT ON COLUMN graphql_schema_directive_arg.directive_argument_name IS 'the definition''s formal argument this value binds';
COMMENT ON COLUMN graphql_schema_directive_arg.value_sdl IS 'the value as written, rendered from the AST; omitted arguments are absent rows';

CREATE TABLE graphql_type_directive (
  graph_name          VARCHAR NOT NULL,
  type_name           VARCHAR NOT NULL,
  directive_name      VARCHAR NOT NULL,
  ordinal             INT     NOT NULL,
  declaration_line    INT     NOT NULL,
  declaration_column  INT     NOT NULL,
  source_name         VARCHAR NOT NULL,
  source_line         INT,
  source_column       INT,
  PRIMARY KEY (graph_name, type_name, directive_name, ordinal),
  FOREIGN KEY (graph_name, type_name) REFERENCES graphql_type (graph_name, type_name),
  FOREIGN KEY (graph_name, type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (graph_name, type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphql_type_directive IS 'A directive is applied to a type (OBJECT, INTERFACE, UNION, ENUM, INPUT_OBJECT, or SCALAR; the parent kind is a join away).';
COMMENT ON COLUMN graphql_type_directive.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphql_type_directive.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphql_type_directive.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_type_directive.ordinal IS 'as on graphql_schema_directive; federation''s @key repeats here';
COMMENT ON COLUMN graphql_type_directive.declaration_line IS 'the applying site (extensions apply type directives too); a synthesized @key hangs off the type''s causing authored site, per its own provenance relation below';
COMMENT ON COLUMN graphql_type_directive.declaration_column IS 'column of the contributing declaration site, the site key''s fourth part';
COMMENT ON COLUMN graphql_type_directive.source_name IS 'NOT NULL as on graphql_field: half of the site FK';
COMMENT ON COLUMN graphql_type_directive.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphql_type_directive.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphql_type_directive_arg (
  graph_name              VARCHAR NOT NULL,
  type_name               VARCHAR NOT NULL,
  directive_name          VARCHAR NOT NULL,
  ordinal                 INT     NOT NULL,
  directive_argument_name VARCHAR NOT NULL,
  value_sdl               VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, directive_name, ordinal, directive_argument_name),
  FOREIGN KEY (graph_name, type_name, directive_name, ordinal)
    REFERENCES graphql_type_directive (graph_name, type_name, directive_name, ordinal)
);
COMMENT ON TABLE graphql_type_directive_arg IS 'An argument the author passed to a type-level application.';
COMMENT ON COLUMN graphql_type_directive_arg.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphql_type_directive_arg.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphql_type_directive_arg.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_type_directive_arg.ordinal IS 'the owning application''s ordinal';
COMMENT ON COLUMN graphql_type_directive_arg.directive_argument_name IS 'the definition''s formal argument this value binds';
COMMENT ON COLUMN graphql_type_directive_arg.value_sdl IS 'the value as written, rendered from the AST';

CREATE TABLE graphql_field_directive (
  graph_name     VARCHAR NOT NULL,
  type_name      VARCHAR NOT NULL,
  field_name     VARCHAR NOT NULL,
  directive_name VARCHAR NOT NULL,
  ordinal        INT     NOT NULL,
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  PRIMARY KEY (graph_name, type_name, field_name, directive_name, ordinal),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphql_field (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphql_field_directive IS 'A directive is applied to a field (output or input-object; the parent type''s kind decides which SDL location this was).';
COMMENT ON COLUMN graphql_field_directive.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphql_field_directive.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphql_field_directive.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphql_field_directive.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_field_directive.ordinal IS '0 unless the directive is repeatable; repeats number in document order';
COMMENT ON COLUMN graphql_field_directive.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphql_field_directive.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphql_field_directive.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphql_field_directive_arg (
  graph_name              VARCHAR NOT NULL,
  type_name               VARCHAR NOT NULL,
  field_name              VARCHAR NOT NULL,
  directive_name          VARCHAR NOT NULL,
  ordinal                 INT     NOT NULL,
  directive_argument_name VARCHAR NOT NULL,
  value_sdl               VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, field_name, directive_name, ordinal, directive_argument_name),
  FOREIGN KEY (graph_name, type_name, field_name, directive_name, ordinal)
    REFERENCES graphql_field_directive (graph_name, type_name, field_name, directive_name, ordinal)
);
COMMENT ON TABLE graphql_field_directive_arg IS 'An argument the author passed to a field-level application.';
COMMENT ON COLUMN graphql_field_directive_arg.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphql_field_directive_arg.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphql_field_directive_arg.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphql_field_directive_arg.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_field_directive_arg.ordinal IS 'the owning application''s ordinal';
COMMENT ON COLUMN graphql_field_directive_arg.directive_argument_name IS 'the definition''s formal argument this value binds';
COMMENT ON COLUMN graphql_field_directive_arg.value_sdl IS 'the value as written, rendered from the AST';

CREATE TABLE graphql_argument_directive (
  graph_name     VARCHAR NOT NULL,
  type_name      VARCHAR NOT NULL,
  field_name     VARCHAR NOT NULL,
  argument_name  VARCHAR NOT NULL,
  directive_name VARCHAR NOT NULL,
  ordinal        INT     NOT NULL,
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  PRIMARY KEY (graph_name, type_name, field_name, argument_name, directive_name, ordinal),
  FOREIGN KEY (graph_name, type_name, field_name, argument_name)
    REFERENCES graphql_argument (graph_name, type_name, field_name, argument_name)
);
COMMENT ON TABLE graphql_argument_directive IS 'A directive is applied to a field argument (ARGUMENT_DEFINITION site).';
COMMENT ON COLUMN graphql_argument_directive.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphql_argument_directive.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphql_argument_directive.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphql_argument_directive.argument_name IS 'the SDL argument the directive sits on';
COMMENT ON COLUMN graphql_argument_directive.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_argument_directive.ordinal IS 'as on graphql_field_directive';
COMMENT ON COLUMN graphql_argument_directive.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphql_argument_directive.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphql_argument_directive.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphql_argument_directive_arg (
  graph_name              VARCHAR NOT NULL,
  type_name               VARCHAR NOT NULL,
  field_name              VARCHAR NOT NULL,
  argument_name           VARCHAR NOT NULL,
  directive_name          VARCHAR NOT NULL,
  ordinal                 INT     NOT NULL,
  directive_argument_name VARCHAR NOT NULL,
  value_sdl               VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, field_name, argument_name, directive_name, ordinal, directive_argument_name),
  FOREIGN KEY (graph_name, type_name, field_name, argument_name, directive_name, ordinal)
    REFERENCES graphql_argument_directive (graph_name, type_name, field_name, argument_name, directive_name, ordinal)
);
COMMENT ON TABLE graphql_argument_directive_arg IS 'An argument the author passed to an argument-level application.';
COMMENT ON COLUMN graphql_argument_directive_arg.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphql_argument_directive_arg.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphql_argument_directive_arg.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphql_argument_directive_arg.argument_name IS 'the argument name within the owning field';
COMMENT ON COLUMN graphql_argument_directive_arg.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_argument_directive_arg.ordinal IS 'the owning application''s ordinal';
COMMENT ON COLUMN graphql_argument_directive_arg.directive_argument_name IS 'the definition''s formal argument this value binds';
COMMENT ON COLUMN graphql_argument_directive_arg.value_sdl IS 'the value as written, rendered from the AST';

CREATE TABLE graphql_enum_value_directive (
  graph_name     VARCHAR NOT NULL,
  type_name      VARCHAR NOT NULL,
  value_name     VARCHAR NOT NULL,
  directive_name VARCHAR NOT NULL,
  ordinal        INT     NOT NULL,
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  PRIMARY KEY (graph_name, type_name, value_name, directive_name, ordinal),
  FOREIGN KEY (graph_name, type_name, value_name) REFERENCES graphql_enum_value (graph_name, type_name, value_name)
);
COMMENT ON TABLE graphql_enum_value_directive IS 'A directive is applied to an enum value (@deprecated lives here, and so does the graphitron enum-value inventory, which is additionally decoded).';
COMMENT ON COLUMN graphql_enum_value_directive.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphql_enum_value_directive.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphql_enum_value_directive.value_name IS 'the enum value name within the owning enum type';
COMMENT ON COLUMN graphql_enum_value_directive.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_enum_value_directive.ordinal IS 'as on graphql_schema_directive';
COMMENT ON COLUMN graphql_enum_value_directive.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphql_enum_value_directive.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphql_enum_value_directive.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphql_enum_value_directive_arg (
  graph_name              VARCHAR NOT NULL,
  type_name               VARCHAR NOT NULL,
  value_name              VARCHAR NOT NULL,
  directive_name          VARCHAR NOT NULL,
  ordinal                 INT     NOT NULL,
  directive_argument_name VARCHAR NOT NULL,
  value_sdl               VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, value_name, directive_name, ordinal, directive_argument_name),
  FOREIGN KEY (graph_name, type_name, value_name, directive_name, ordinal)
    REFERENCES graphql_enum_value_directive (graph_name, type_name, value_name, directive_name, ordinal)
);
COMMENT ON TABLE graphql_enum_value_directive_arg IS 'An argument the author passed to an enum-value application.';
COMMENT ON COLUMN graphql_enum_value_directive_arg.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphql_enum_value_directive_arg.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphql_enum_value_directive_arg.value_name IS 'the enum value name within the owning enum type';
COMMENT ON COLUMN graphql_enum_value_directive_arg.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphql_enum_value_directive_arg.ordinal IS 'the owning application''s ordinal';
COMMENT ON COLUMN graphql_enum_value_directive_arg.directive_argument_name IS 'the definition''s formal argument this value binds';
COMMENT ON COLUMN graphql_enum_value_directive_arg.value_sdl IS 'the value as written, rendered from the AST';

CREATE VIEW graphql_directive_site AS
SELECT graph_name, 'SCHEMA' AS site_kind, CAST(NULL AS VARCHAR) AS type_name,
       CAST(NULL AS VARCHAR) AS member_name, CAST(NULL AS VARCHAR) AS argument_name,
       directive_name, ordinal, source_name, source_line, source_column
  FROM graphql_schema_directive
UNION ALL
SELECT graph_name, 'TYPE', type_name, NULL, NULL,
       directive_name, ordinal, source_name, source_line, source_column
  FROM graphql_type_directive
UNION ALL
SELECT graph_name, 'FIELD', type_name, field_name, NULL,
       directive_name, ordinal, source_name, source_line, source_column
  FROM graphql_field_directive
UNION ALL
SELECT graph_name, 'ARGUMENT', type_name, field_name, argument_name,
       directive_name, ordinal, source_name, source_line, source_column
  FROM graphql_argument_directive
UNION ALL
SELECT graph_name, 'ENUM_VALUE', type_name, value_name, NULL,
       directive_name, ordinal, source_name, source_line, source_column
  FROM graphql_enum_value_directive;
COMMENT ON VIEW graphql_directive_site IS 'The one view the DDL ships: every application regardless of site, so a consumer that wants "all applications of @x" reads one relation.';
COMMENT ON COLUMN graphql_directive_site.graph_name IS 'the owning graph''s partition, carried through from every arm''s base relation';
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
  graph_name       VARCHAR NOT NULL,
  type_name        VARCHAR NOT NULL,
  source_name      VARCHAR NOT NULL,
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  table_ref        VARCHAR,
  PRIMARY KEY (graph_name, type_name),
  FOREIGN KEY (graph_name, type_name) REFERENCES graphql_type (graph_name, type_name),
  FOREIGN KEY (graph_name, type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (graph_name, type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphitron_table IS '@table on a type: the author binds the type to a database table. On an INPUT_OBJECT the application is captured like any other; the ignored-and- warned status of that site is a detection.';
COMMENT ON COLUMN graphitron_table.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_table.type_name IS 'the OBJECT, INPUT_OBJECT, or INTERFACE carrying @table';
COMMENT ON COLUMN graphitron_table.source_name IS 'the applying declaration site (keyed with the line and column below); doubles as the file of the position columns';
COMMENT ON COLUMN graphitron_table.declaration_line IS 'line of the applying declaration site';
COMMENT ON COLUMN graphitron_table.declaration_column IS 'column of the applying declaration site';
COMMENT ON COLUMN graphitron_table.source_line IS 'the application''s own position';
COMMENT ON COLUMN graphitron_table.source_column IS 'the application''s own column';
COMMENT ON COLUMN graphitron_table.table_ref IS 'the name argument as written (may carry a schema qualifier); NULL when omitted, the type-name fallback is a derivation';

CREATE TABLE graphitron_field_binding (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  name_ref      VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, field_name),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphql_field (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_field_binding IS '@field on an output or input-object field: the slot''s bound name. A column, a Java accessor, or a Java member depending on the backing, which is classification''s business; the $source / $errors sigil forms are stored as written, their recognition being a prefix test SQL can express.';
COMMENT ON COLUMN graphitron_field_binding.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_field_binding.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_field_binding.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_field_binding.source_name IS 'the application''s own position, here and below';
COMMENT ON COLUMN graphitron_field_binding.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_field_binding.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_field_binding.name_ref IS 'the name argument as written';

CREATE TABLE graphitron_argument_binding (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  name_ref      VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, field_name, argument_name),
  FOREIGN KEY (graph_name, type_name, field_name, argument_name)
    REFERENCES graphql_argument (graph_name, type_name, field_name, argument_name)
);
COMMENT ON TABLE graphitron_argument_binding IS '@field on an argument: the filter argument''s bound column.';
COMMENT ON COLUMN graphitron_argument_binding.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_argument_binding.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_argument_binding.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_argument_binding.argument_name IS 'the argument name within the owning field';
COMMENT ON COLUMN graphitron_argument_binding.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_argument_binding.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_argument_binding.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_argument_binding.name_ref IS 'the name argument as written';

CREATE TABLE graphitron_enum_value_binding (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  value_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  name_ref      VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, value_name),
  FOREIGN KEY (graph_name, type_name, value_name) REFERENCES graphql_enum_value (graph_name, type_name, value_name)
);
COMMENT ON TABLE graphitron_enum_value_binding IS '@field on an enum value: the database string (or Java constant) the value maps to. The pivot vocabulary decode reads this relation too.';
COMMENT ON COLUMN graphitron_enum_value_binding.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_enum_value_binding.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_enum_value_binding.value_name IS 'the enum value name within the owning enum type';
COMMENT ON COLUMN graphitron_enum_value_binding.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_enum_value_binding.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_enum_value_binding.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_enum_value_binding.name_ref IS 'the name argument as written';

CREATE TABLE graphitron_scalar_type (
  graph_name       VARCHAR NOT NULL,
  type_name        VARCHAR NOT NULL,
  source_name      VARCHAR NOT NULL,
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  scalar_ref       VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name),
  FOREIGN KEY (graph_name, type_name) REFERENCES graphql_type (graph_name, type_name),
  FOREIGN KEY (graph_name, type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (graph_name, type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphitron_scalar_type IS '@scalarType on a scalar: the Java constant backing it. Under registry capture the application is read like any other; the SDL pre-pass the current consumer needs (assembly strips directives off spec built-in redeclarations) dies with the assembled source.';
COMMENT ON COLUMN graphitron_scalar_type.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_scalar_type.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_scalar_type.source_name IS 'half of the site FK, so NOT NULL; a graphitron application always has an SDL position';
COMMENT ON COLUMN graphitron_scalar_type.declaration_line IS 'line of the contributing declaration site, keyed with source_name';
COMMENT ON COLUMN graphitron_scalar_type.declaration_column IS 'column of the contributing declaration site, the site key''s fourth part';
COMMENT ON COLUMN graphitron_scalar_type.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_scalar_type.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_scalar_type.scalar_ref IS 'the fully-qualified Java constant reference as written';

CREATE TABLE graphitron_enum (
  graph_name       VARCHAR NOT NULL,
  type_name        VARCHAR NOT NULL,
  source_name      VARCHAR NOT NULL,
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  class_name       VARCHAR,
  method           VARCHAR,
  arg_mapping      VARCHAR,
  PRIMARY KEY (graph_name, type_name),
  FOREIGN KEY (graph_name, type_name) REFERENCES graphql_type (graph_name, type_name),
  FOREIGN KEY (graph_name, type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (graph_name, type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphitron_enum IS '@enum on an enum type. The full ExternalCodeReference is captured as written, though today only arg_mapping is consumed (to reject a non-blank value; the Java binding is derived by reflection and the per-value mapping comes from graphitron_enum_value_binding).';
COMMENT ON COLUMN graphitron_enum.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
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
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  class_name    VARCHAR,
  method        VARCHAR,
  arg_mapping   VARCHAR,
  override      BOOLEAN,
  PRIMARY KEY (graph_name, type_name, field_name),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphql_field (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_field_condition IS '@condition on a field or input field (shared coordinate; the parent kind decides which SDL site this was).';
COMMENT ON COLUMN graphitron_field_condition.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
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
  graph_name VARCHAR NOT NULL,
  type_name  VARCHAR NOT NULL,
  field_name VARCHAR NOT NULL,
  position   INT     NOT NULL,
  name       VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, field_name, position),
  FOREIGN KEY (graph_name, type_name, field_name)
    REFERENCES graphitron_field_condition (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_field_condition_context_arg IS 'An ordered context argument of a field-site @condition.';
COMMENT ON COLUMN graphitron_field_condition_context_arg.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_field_condition_context_arg.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_field_condition_context_arg.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_field_condition_context_arg.position IS '0-based position in the contextArguments list';
COMMENT ON COLUMN graphitron_field_condition_context_arg.name IS 'the context argument name as written';

CREATE TABLE graphitron_field_condition_arg_mapping_pair (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, field_name, position),
  FOREIGN KEY (graph_name, type_name, field_name)
    REFERENCES graphitron_field_condition (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_field_condition_arg_mapping_pair IS 'An ordered pair of a field-site @condition''s argMapping. Position-keyed so an author''s duplicate parameter survives for the duplicate detection.';
COMMENT ON COLUMN graphitron_field_condition_arg_mapping_pair.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_field_condition_arg_mapping_pair.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_field_condition_arg_mapping_pair.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_field_condition_arg_mapping_pair.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_field_condition_arg_mapping_pair.param_name IS 'the Java parameter (left side)';
COMMENT ON COLUMN graphitron_field_condition_arg_mapping_pair.argument_path IS 'the right side as written: a GraphQL argument name or dotted input path';

CREATE TABLE graphitron_argument_condition (
  graph_name    VARCHAR NOT NULL,
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
  PRIMARY KEY (graph_name, type_name, field_name, argument_name),
  FOREIGN KEY (graph_name, type_name, field_name, argument_name)
    REFERENCES graphql_argument (graph_name, type_name, field_name, argument_name)
);
COMMENT ON TABLE graphitron_argument_condition IS '@condition on an argument: the same decode over the three-part coordinate.';
COMMENT ON COLUMN graphitron_argument_condition.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
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
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  position      INT     NOT NULL,
  name          VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, field_name, argument_name, position),
  FOREIGN KEY (graph_name, type_name, field_name, argument_name)
    REFERENCES graphitron_argument_condition (graph_name, type_name, field_name, argument_name)
);
COMMENT ON TABLE graphitron_argument_condition_context_arg IS 'An ordered context argument of an argument-site @condition.';
COMMENT ON COLUMN graphitron_argument_condition_context_arg.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_argument_condition_context_arg.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_argument_condition_context_arg.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_argument_condition_context_arg.argument_name IS 'the argument name within the owning field';
COMMENT ON COLUMN graphitron_argument_condition_context_arg.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_argument_condition_context_arg.name IS 'the context argument name as written';

CREATE TABLE graphitron_argument_condition_arg_mapping_pair (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, field_name, argument_name, position),
  FOREIGN KEY (graph_name, type_name, field_name, argument_name)
    REFERENCES graphitron_argument_condition (graph_name, type_name, field_name, argument_name)
);
COMMENT ON TABLE graphitron_argument_condition_arg_mapping_pair IS 'An ordered pair of an argument-site @condition''s argMapping. Position-keyed so an author''s duplicate parameter survives for the duplicate detection.';
COMMENT ON COLUMN graphitron_argument_condition_arg_mapping_pair.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_argument_condition_arg_mapping_pair.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_argument_condition_arg_mapping_pair.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_argument_condition_arg_mapping_pair.argument_name IS 'the argument name within the owning field';
COMMENT ON COLUMN graphitron_argument_condition_arg_mapping_pair.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_argument_condition_arg_mapping_pair.param_name IS 'the Java or routine parameter (left side of the pair)';
COMMENT ON COLUMN graphitron_argument_condition_arg_mapping_pair.argument_path IS 'the right side as written: a GraphQL argument name or dotted input path';

CREATE TABLE graphitron_field_reference (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (graph_name, type_name, field_name, ordinal),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphql_field (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_field_reference IS '@reference on a field or input field: one row per application, because an application is a fact of its own. An empty path means FK auto-discovery between the endpoints, and the rule that every application in a multi-application chain must carry an element is per-application; both are invisible in a flat concatenated chain. The effective chain the consumers read is the steps ordered by (ordinal, position), and the written-order interleaving with @routine applications on the same field is an ORDER BY over the two relations'' source positions.';
COMMENT ON COLUMN graphitron_field_reference.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_field_reference.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_field_reference.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_field_reference.ordinal IS 'repeatable; document order';
COMMENT ON COLUMN graphitron_field_reference.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_field_reference.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_field_reference.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphitron_field_reference_step (
  graph_name  VARCHAR NOT NULL,
  type_name   VARCHAR NOT NULL,
  field_name  VARCHAR NOT NULL,
  ordinal     INT     NOT NULL,
  position    INT     NOT NULL,
  table_ref   VARCHAR,
  key_ref     VARCHAR,
  class_name  VARCHAR,
  method      VARCHAR,
  arg_mapping VARCHAR,
  PRIMARY KEY (graph_name, type_name, field_name, ordinal, position),
  FOREIGN KEY (graph_name, type_name, field_name, ordinal)
    REFERENCES graphitron_field_reference (graph_name, type_name, field_name, ordinal)
);
COMMENT ON TABLE graphitron_field_reference_step IS 'An ordered path element of one @reference application; the step''s ExternalCodeReference condition flattens in place.';
COMMENT ON COLUMN graphitron_field_reference_step.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
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
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  step_position INT     NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, field_name, ordinal, step_position, position),
  FOREIGN KEY (graph_name, type_name, field_name, ordinal, step_position)
    REFERENCES graphitron_field_reference_step (graph_name, type_name, field_name, ordinal, position)
);
COMMENT ON TABLE graphitron_field_reference_step_arg_mapping_pair IS 'An ordered pair of a step condition''s argMapping.';
COMMENT ON COLUMN graphitron_field_reference_step_arg_mapping_pair.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_field_reference_step_arg_mapping_pair.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_field_reference_step_arg_mapping_pair.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_field_reference_step_arg_mapping_pair.ordinal IS 'the owning @reference application''s ordinal';
COMMENT ON COLUMN graphitron_field_reference_step_arg_mapping_pair.step_position IS '0-based position of the owning step within its application''s path';
COMMENT ON COLUMN graphitron_field_reference_step_arg_mapping_pair.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_field_reference_step_arg_mapping_pair.param_name IS 'the Java or routine parameter (left side of the pair)';
COMMENT ON COLUMN graphitron_field_reference_step_arg_mapping_pair.argument_path IS 'the right side as written: a GraphQL argument name or dotted input path';

CREATE TABLE graphitron_argument_reference (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (graph_name, type_name, field_name, argument_name, ordinal),
  FOREIGN KEY (graph_name, type_name, field_name, argument_name)
    REFERENCES graphql_argument (graph_name, type_name, field_name, argument_name)
);
COMMENT ON TABLE graphitron_argument_reference IS '@reference on an argument: the same family over the three-part coordinate.';
COMMENT ON COLUMN graphitron_argument_reference.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_argument_reference.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_argument_reference.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_argument_reference.argument_name IS 'the argument name within the owning field';
COMMENT ON COLUMN graphitron_argument_reference.ordinal IS 'capture-assigned position in document order';
COMMENT ON COLUMN graphitron_argument_reference.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_argument_reference.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_argument_reference.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphitron_argument_reference_step (
  graph_name    VARCHAR NOT NULL,
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
  PRIMARY KEY (graph_name, type_name, field_name, argument_name, ordinal, position),
  FOREIGN KEY (graph_name, type_name, field_name, argument_name, ordinal)
    REFERENCES graphitron_argument_reference (graph_name, type_name, field_name, argument_name, ordinal)
);
COMMENT ON TABLE graphitron_argument_reference_step IS 'An ordered path element of one argument-site @reference application; the step''s ExternalCodeReference condition flattens in place.';
COMMENT ON COLUMN graphitron_argument_reference_step.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
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
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  step_position INT     NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, field_name, argument_name, ordinal, step_position, position),
  FOREIGN KEY (graph_name, type_name, field_name, argument_name, ordinal, step_position)
    REFERENCES graphitron_argument_reference_step (graph_name, type_name, field_name, argument_name, ordinal, position)
);
COMMENT ON TABLE graphitron_argument_reference_step_arg_mapping_pair IS 'An ordered pair of an argument-site @reference step condition''s argMapping.';
COMMENT ON COLUMN graphitron_argument_reference_step_arg_mapping_pair.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_argument_reference_step_arg_mapping_pair.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_argument_reference_step_arg_mapping_pair.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_argument_reference_step_arg_mapping_pair.argument_name IS 'the argument name within the owning field';
COMMENT ON COLUMN graphitron_argument_reference_step_arg_mapping_pair.ordinal IS 'the owning @reference application''s ordinal';
COMMENT ON COLUMN graphitron_argument_reference_step_arg_mapping_pair.step_position IS '0-based position of the owning step within its application''s path';
COMMENT ON COLUMN graphitron_argument_reference_step_arg_mapping_pair.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_argument_reference_step_arg_mapping_pair.param_name IS 'the Java or routine parameter (left side of the pair)';
COMMENT ON COLUMN graphitron_argument_reference_step_arg_mapping_pair.argument_path IS 'the right side as written: a GraphQL argument name or dotted input path';

CREATE TABLE graphitron_reference_for (
  graph_name           VARCHAR NOT NULL,
  type_name            VARCHAR NOT NULL,
  field_name           VARCHAR NOT NULL,
  ordinal              INT     NOT NULL,
  source_name          VARCHAR,
  source_line          INT,
  source_column        INT,
  participant_type_ref VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, field_name, ordinal),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphql_field (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_reference_for IS '@referenceFor on a field: an explicit join path for one participant of a multi-table interface or union child. Keyed by ordinal per the repeatable rule; the consumption-side keying by participant makes a repeated participant a detection, never a collision.';
COMMENT ON COLUMN graphitron_reference_for.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_reference_for.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_reference_for.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_reference_for.ordinal IS 'capture-assigned position in document order';
COMMENT ON COLUMN graphitron_reference_for.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_reference_for.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_reference_for.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_reference_for.participant_type_ref IS 'the type argument as written; author-spelled, no FK';

CREATE TABLE graphitron_reference_for_step (
  graph_name  VARCHAR NOT NULL,
  type_name   VARCHAR NOT NULL,
  field_name  VARCHAR NOT NULL,
  ordinal     INT     NOT NULL,
  position    INT     NOT NULL,
  table_ref   VARCHAR,
  key_ref     VARCHAR,
  class_name  VARCHAR,
  method      VARCHAR,
  arg_mapping VARCHAR,
  PRIMARY KEY (graph_name, type_name, field_name, ordinal, position),
  FOREIGN KEY (graph_name, type_name, field_name, ordinal)
    REFERENCES graphitron_reference_for (graph_name, type_name, field_name, ordinal)
);
COMMENT ON TABLE graphitron_reference_for_step IS 'An ordered path element of one @referenceFor application: the participant''s complete path from the parent''s table, read as the same element grammar as @reference.';
COMMENT ON COLUMN graphitron_reference_for_step.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
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
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  step_position INT     NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, field_name, ordinal, step_position, position),
  FOREIGN KEY (graph_name, type_name, field_name, ordinal, step_position)
    REFERENCES graphitron_reference_for_step (graph_name, type_name, field_name, ordinal, position)
);
COMMENT ON TABLE graphitron_reference_for_step_arg_mapping_pair IS 'An ordered pair of a @referenceFor step condition''s argMapping.';
COMMENT ON COLUMN graphitron_reference_for_step_arg_mapping_pair.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_reference_for_step_arg_mapping_pair.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_reference_for_step_arg_mapping_pair.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_reference_for_step_arg_mapping_pair.ordinal IS 'the owning @referenceFor application''s ordinal';
COMMENT ON COLUMN graphitron_reference_for_step_arg_mapping_pair.step_position IS '0-based position of the owning step within its application''s path';
COMMENT ON COLUMN graphitron_reference_for_step_arg_mapping_pair.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_reference_for_step_arg_mapping_pair.param_name IS 'the Java or routine parameter (left side of the pair)';
COMMENT ON COLUMN graphitron_reference_for_step_arg_mapping_pair.argument_path IS 'the right side as written: a GraphQL argument name or dotted input path';

CREATE TABLE graphitron_service (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  class_name    VARCHAR,
  method        VARCHAR,
  arg_mapping   VARCHAR,
  PRIMARY KEY (graph_name, type_name, field_name),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphql_field (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_service IS '@service on a field: the external service reference.';
COMMENT ON COLUMN graphitron_service.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_service.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_service.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_service.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_service.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_service.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_service.class_name IS 'the fully-qualified Java class name as written';
COMMENT ON COLUMN graphitron_service.method IS 'the Java method name as written';
COMMENT ON COLUMN graphitron_service.arg_mapping IS 'the argMapping string as written; the pair child is its decode';

CREATE TABLE graphitron_service_context_arg (
  graph_name VARCHAR NOT NULL,
  type_name  VARCHAR NOT NULL,
  field_name VARCHAR NOT NULL,
  position   INT     NOT NULL,
  name       VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, field_name, position),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphitron_service (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_service_context_arg IS 'An ordered contextArguments entry of a @service application; the value is supplied on the GraphQLContext at run time.';
COMMENT ON COLUMN graphitron_service_context_arg.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_service_context_arg.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_service_context_arg.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_service_context_arg.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_service_context_arg.name IS 'the context argument name as written';

CREATE TABLE graphitron_service_arg_mapping_pair (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, field_name, position),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphitron_service (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_service_arg_mapping_pair IS 'An ordered pair of a @service''s argMapping, binding a Java method parameter to a GraphQL argument.';
COMMENT ON COLUMN graphitron_service_arg_mapping_pair.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_service_arg_mapping_pair.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_service_arg_mapping_pair.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_service_arg_mapping_pair.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_service_arg_mapping_pair.param_name IS 'the Java or routine parameter (left side of the pair)';
COMMENT ON COLUMN graphitron_service_arg_mapping_pair.argument_path IS 'the right side as written: a GraphQL argument name or dotted input path';

CREATE TABLE graphitron_service_arg_mapping_sigil (
  graph_name VARCHAR NOT NULL,
  type_name  VARCHAR NOT NULL,
  field_name VARCHAR NOT NULL,
  position   INT     NOT NULL,
  param_name VARCHAR NOT NULL,
  sigil      VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, field_name, position),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphitron_service (graph_name, type_name, field_name),
  CHECK (sigil IN ('$session'))
);
COMMENT ON TABLE graphitron_service_arg_mapping_sigil IS 'A sigil entry of a @service''s argMapping, the sibling of graphitron_service_arg_mapping_pair for entries whose right-hand side is a recognized sigil rather than an argument path. A recognized sigil is a decode decision carried as a fact, not a string left for readers to re-peek: it must not land in the pair relation (whose argument_path is a closed two-alternative statement feeding dangling-author-reference detection) and must not quarantine as graphitron_undecoded_argument (a valid literal is not malformed overflow). The lifting happens before tokenization, through the same sigil owner the build-side parse uses, so the two sides cannot drift on what a sigil is.';
COMMENT ON COLUMN graphitron_service_arg_mapping_sigil.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_service_arg_mapping_sigil.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_service_arg_mapping_sigil.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_service_arg_mapping_sigil.position IS '0-based position among the argMapping''s sigil entries, document order';
COMMENT ON COLUMN graphitron_service_arg_mapping_sigil.param_name IS 'the Java parameter the sigil binds (left side of the entry)';
COMMENT ON COLUMN graphitron_service_arg_mapping_sigil.sigil IS 'the recognized sigil literal, a closed taxonomy: $session binds the parameter to the session handle the <sessionState> mount returned';

CREATE TABLE graphitron_external_field (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  class_name    VARCHAR,
  method        VARCHAR,
  arg_mapping   VARCHAR,
  PRIMARY KEY (graph_name, type_name, field_name),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphql_field (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_external_field IS '@externalField on a field: the static jOOQ-Field method. The omitted-method fallback (the field name) is a derivation; arg_mapping is inert here (raw column only, its rejection is presence-triggered).';
COMMENT ON COLUMN graphitron_external_field.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_external_field.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_external_field.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_external_field.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_external_field.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_external_field.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_external_field.class_name IS 'the fully-qualified Java class name as written';
COMMENT ON COLUMN graphitron_external_field.method IS 'the Java method name as written';
COMMENT ON COLUMN graphitron_external_field.arg_mapping IS 'the argMapping string as written; the pair child is its decode';

CREATE TABLE graphitron_source_row (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  class_name    VARCHAR NOT NULL,
  method        VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, field_name),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphql_field (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_source_row IS '@sourceRow on a field: the parent-side key producer, a join key on a join-resolved field and a batch key on an @service one. Flat arguments by declaration, not an ExternalCodeReference.';
COMMENT ON COLUMN graphitron_source_row.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_source_row.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_source_row.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_source_row.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_source_row.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_source_row.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_source_row.class_name IS 'the lifter class as written';
COMMENT ON COLUMN graphitron_source_row.method IS 'the static lifter method name as written';

CREATE TABLE graphitron_connection (
  graph_name          VARCHAR NOT NULL,
  type_name           VARCHAR NOT NULL,
  field_name          VARCHAR NOT NULL,
  source_name         VARCHAR,
  source_line         INT,
  source_column       INT,
  default_first_value INT,
  connection_name     VARCHAR,
  PRIMARY KEY (graph_name, type_name, field_name),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphql_field (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_connection IS '@asConnection on a field: the macro''s spec, as authored. The expansion''s output is provenance-marked rows in the graphql_ tables, below.';
COMMENT ON COLUMN graphitron_connection.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_connection.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_connection.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_connection.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_connection.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_connection.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_connection.default_first_value IS 'as written; NULL when omitted';
COMMENT ON COLUMN graphitron_connection.connection_name IS 'the deprecated shared-type override, as written; honoured by the expansion, deprecation is a lint detection';

CREATE TABLE graphitron_facet (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (graph_name, type_name, field_name),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphql_field (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_facet IS '@asFacet on an input field: a marker; the bound column comes from graphitron_field_binding, and every misuse arm is a detection.';
COMMENT ON COLUMN graphitron_facet.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_facet.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_facet.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_facet.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_facet.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_facet.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphitron_order_by (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (graph_name, type_name, field_name, argument_name),
  FOREIGN KEY (graph_name, type_name, field_name, argument_name)
    REFERENCES graphql_argument (graph_name, type_name, field_name, argument_name)
);
COMMENT ON TABLE graphitron_order_by IS '@orderBy on an argument: a marker; the input shape rules are detections.';
COMMENT ON COLUMN graphitron_order_by.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_order_by.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_order_by.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_order_by.argument_name IS 'the argument name within the owning field';
COMMENT ON COLUMN graphitron_order_by.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_order_by.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_order_by.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphitron_order (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  value_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  index_ref     VARCHAR,
  primary_key   BOOLEAN,
  PRIMARY KEY (graph_name, type_name, value_name),
  FOREIGN KEY (graph_name, type_name, value_name) REFERENCES graphql_enum_value (graph_name, type_name, value_name)
);
COMMENT ON TABLE graphitron_order IS '@order on an enum value: a sorting specification. The exactly-one-of rule over index, fields, and primaryKey is a detection.';
COMMENT ON COLUMN graphitron_order.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_order.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_order.value_name IS 'the enum value name within the owning enum type';
COMMENT ON COLUMN graphitron_order.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_order.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_order.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_order.index_ref IS 'database index name as written';
COMMENT ON COLUMN graphitron_order.primary_key IS 'as written; NULL when omitted';

CREATE TABLE graphitron_order_field (
  graph_name VARCHAR NOT NULL,
  type_name  VARCHAR NOT NULL,
  value_name VARCHAR NOT NULL,
  position   INT     NOT NULL,
  name_ref   VARCHAR NOT NULL,
  collate    VARCHAR,
  direction  VARCHAR,
  PRIMARY KEY (graph_name, type_name, value_name, position),
  FOREIGN KEY (graph_name, type_name, value_name) REFERENCES graphitron_order (graph_name, type_name, value_name)
);
COMMENT ON TABLE graphitron_order_field IS 'An ordered FieldSort entry of an @order.';
COMMENT ON COLUMN graphitron_order_field.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_order_field.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_order_field.value_name IS 'the enum value name within the owning enum type';
COMMENT ON COLUMN graphitron_order_field.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_order_field.name_ref IS 'FieldSort.name, a column reference as written';
COMMENT ON COLUMN graphitron_order_field.collate IS 'the collation as written, when declared';
COMMENT ON COLUMN graphitron_order_field.direction IS 'as written; author-spelled enum literal, open column';

CREATE TABLE graphitron_index (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  value_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  index_ref     VARCHAR,
  PRIMARY KEY (graph_name, type_name, value_name),
  FOREIGN KEY (graph_name, type_name, value_name) REFERENCES graphql_enum_value (graph_name, type_name, value_name)
);
COMMENT ON TABLE graphitron_index IS '@index on an enum value: the deprecated alias of @order(index:), still honoured when @order is absent; the deprecation is a lint detection.';
COMMENT ON COLUMN graphitron_index.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_index.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_index.value_name IS 'the enum value name within the owning enum type';
COMMENT ON COLUMN graphitron_index.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_index.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_index.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_index.index_ref IS 'the name argument, which the declaration leaves optional';

CREATE TABLE graphitron_default_order (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  index_ref     VARCHAR,
  primary_key   BOOLEAN,
  direction     VARCHAR,
  PRIMARY KEY (graph_name, type_name, field_name),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphql_field (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_default_order IS '@defaultOrder on a field: the same specification shape plus the directive-level direction that serves as the per-entry fallback.';
COMMENT ON COLUMN graphitron_default_order.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_default_order.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_default_order.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_default_order.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_default_order.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_default_order.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_default_order.index_ref IS 'the database index name as written';
COMMENT ON COLUMN graphitron_default_order.primary_key IS 'as written; NULL when omitted';
COMMENT ON COLUMN graphitron_default_order.direction IS 'as written; open column, the ASC default is a derivation';

CREATE TABLE graphitron_default_order_field (
  graph_name VARCHAR NOT NULL,
  type_name  VARCHAR NOT NULL,
  field_name VARCHAR NOT NULL,
  position   INT     NOT NULL,
  name_ref   VARCHAR NOT NULL,
  collate    VARCHAR,
  direction  VARCHAR,
  PRIMARY KEY (graph_name, type_name, field_name, position),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphitron_default_order (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_default_order_field IS 'An ordered FieldSort entry of a @defaultOrder.';
COMMENT ON COLUMN graphitron_default_order_field.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_default_order_field.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_default_order_field.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_default_order_field.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_default_order_field.name_ref IS 'the name argument as written';
COMMENT ON COLUMN graphitron_default_order_field.collate IS 'the collation as written, when declared';
COMMENT ON COLUMN graphitron_default_order_field.direction IS 'the sort direction as written; author-spelled enum literal, open column';

CREATE TABLE graphitron_mutation (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  operation     VARCHAR NOT NULL,
  multi_row     BOOLEAN,
  table_ref     VARCHAR,
  PRIMARY KEY (graph_name, type_name, field_name),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphql_field (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_mutation IS '@mutation on a field: the DML statement spec.';
COMMENT ON COLUMN graphitron_mutation.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_mutation.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_mutation.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_mutation.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_mutation.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_mutation.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_mutation.operation IS 'the typeName argument as written (INSERT / UPDATE / DELETE / UPSERT); open column per the enum-literal rule';
COMMENT ON COLUMN graphitron_mutation.multi_row IS 'as written; NULL when omitted';
COMMENT ON COLUMN graphitron_mutation.table_ref IS 'the DELETE write target as written';

CREATE TABLE graphitron_error (
  graph_name       VARCHAR NOT NULL,
  type_name        VARCHAR NOT NULL,
  source_name      VARCHAR NOT NULL,
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  PRIMARY KEY (graph_name, type_name),
  FOREIGN KEY (graph_name, type_name) REFERENCES graphql_type (graph_name, type_name),
  FOREIGN KEY (graph_name, type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (graph_name, type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphitron_error IS '@error on an object type: presence; the handlers list decodes into the ordered child, and every cross-field handler rule is a detection.';
COMMENT ON COLUMN graphitron_error.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_error.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_error.source_name IS 'half of the site FK, so NOT NULL; a graphitron application always has an SDL position';
COMMENT ON COLUMN graphitron_error.declaration_line IS 'line of the contributing declaration site, keyed with source_name';
COMMENT ON COLUMN graphitron_error.declaration_column IS 'column of the contributing declaration site, the site key''s fourth part';
COMMENT ON COLUMN graphitron_error.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_error.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphitron_error_handler (
  graph_name  VARCHAR NOT NULL,
  type_name   VARCHAR NOT NULL,
  position    INT     NOT NULL,
  handler     VARCHAR NOT NULL,
  class_name  VARCHAR,
  code        VARCHAR,
  sql_state   VARCHAR,
  matches     VARCHAR,
  description VARCHAR,
  PRIMARY KEY (graph_name, type_name, position),
  FOREIGN KEY (graph_name, type_name) REFERENCES graphitron_error (graph_name, type_name)
);
COMMENT ON TABLE graphitron_error_handler IS 'An ordered ErrorHandler of an @error application.';
COMMENT ON COLUMN graphitron_error_handler.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_error_handler.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_error_handler.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_error_handler.handler IS 'GENERIC / DATABASE / VALIDATION as written; open column';
COMMENT ON COLUMN graphitron_error_handler.class_name IS 'the exception class as written';
COMMENT ON COLUMN graphitron_error_handler.code IS 'the database error code the handler matches on';
COMMENT ON COLUMN graphitron_error_handler.sql_state IS 'the SQL state code the handler matches on';
COMMENT ON COLUMN graphitron_error_handler.matches IS 'a substring the exception message must contain';
COMMENT ON COLUMN graphitron_error_handler.description IS 'SDL description string, when the author wrote one';

CREATE TABLE graphitron_node (
  graph_name       VARCHAR NOT NULL,
  type_name        VARCHAR NOT NULL,
  source_name      VARCHAR NOT NULL,
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  type_id          VARCHAR,
  PRIMARY KEY (graph_name, type_name),
  FOREIGN KEY (graph_name, type_name) REFERENCES graphql_type (graph_name, type_name),
  FOREIGN KEY (graph_name, type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (graph_name, type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphitron_node IS '@node on an object type: node identity. The type-name fallback for typeId and the catalog-PK fallback for key columns are derivations; the SDL-versus-jOOQ-metadata precedence rules are detections.';
COMMENT ON COLUMN graphitron_node.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_node.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_node.source_name IS 'half of the site FK, so NOT NULL; a graphitron application always has an SDL position';
COMMENT ON COLUMN graphitron_node.declaration_line IS 'line of the contributing declaration site, keyed with source_name';
COMMENT ON COLUMN graphitron_node.declaration_column IS 'column of the contributing declaration site, the site key''s fourth part';
COMMENT ON COLUMN graphitron_node.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_node.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_node.type_id IS 'as written';

CREATE TABLE graphitron_node_key_column (
  graph_name VARCHAR NOT NULL,
  type_name  VARCHAR NOT NULL,
  position   INT     NOT NULL,
  column_ref VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, position),
  FOREIGN KEY (graph_name, type_name) REFERENCES graphitron_node (graph_name, type_name)
);
COMMENT ON TABLE graphitron_node_key_column IS 'An ordered keyColumns entry of an @node.';
COMMENT ON COLUMN graphitron_node_key_column.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_node_key_column.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_node_key_column.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_node_key_column.column_ref IS 'the key column as written';

CREATE TABLE graphitron_field_node_id (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  node_type_ref VARCHAR,
  PRIMARY KEY (graph_name, type_name, field_name),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphql_field (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_field_node_id IS '@nodeId on a field or input field.';
COMMENT ON COLUMN graphitron_field_node_id.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_field_node_id.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_field_node_id.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_field_node_id.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_field_node_id.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_field_node_id.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_field_node_id.node_type_ref IS 'typeName as written; author-spelled type reference, no FK, inference when NULL is a derivation';

CREATE TABLE graphitron_argument_node_id (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  node_type_ref VARCHAR,
  PRIMARY KEY (graph_name, type_name, field_name, argument_name),
  FOREIGN KEY (graph_name, type_name, field_name, argument_name)
    REFERENCES graphql_argument (graph_name, type_name, field_name, argument_name)
);
COMMENT ON TABLE graphitron_argument_node_id IS '@nodeId on an argument.';
COMMENT ON COLUMN graphitron_argument_node_id.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_argument_node_id.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_argument_node_id.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_argument_node_id.argument_name IS 'the argument name within the owning field';
COMMENT ON COLUMN graphitron_argument_node_id.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_argument_node_id.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_argument_node_id.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_argument_node_id.node_type_ref IS 'the typeName argument as written; author-spelled, no FK';

CREATE TABLE graphitron_argument_lookup_key (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_name VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (graph_name, type_name, field_name, argument_name),
  FOREIGN KEY (graph_name, type_name, field_name, argument_name)
    REFERENCES graphql_argument (graph_name, type_name, field_name, argument_name)
);
COMMENT ON TABLE graphitron_argument_lookup_key IS '@lookupKey on an argument: the live site, a marker.';
COMMENT ON COLUMN graphitron_argument_lookup_key.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_argument_lookup_key.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_argument_lookup_key.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_argument_lookup_key.argument_name IS 'the argument name within the owning field';
COMMENT ON COLUMN graphitron_argument_lookup_key.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_argument_lookup_key.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_argument_lookup_key.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphitron_field_lookup_key (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (graph_name, type_name, field_name),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphql_field (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_field_lookup_key IS '@lookupKey on an input field: the retired site; the sole consumer is the located migration rejection.';
COMMENT ON COLUMN graphitron_field_lookup_key.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_field_lookup_key.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_field_lookup_key.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_field_lookup_key.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_field_lookup_key.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_field_lookup_key.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphitron_split_query (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (graph_name, type_name, field_name),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphql_field (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_split_query IS '@splitQuery on a field: a marker.';
COMMENT ON COLUMN graphitron_split_query.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_split_query.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_split_query.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_split_query.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_split_query.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_split_query.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphitron_tenant_fan_out (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (graph_name, type_name, field_name),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphql_field (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_tenant_fan_out IS '@tenantFanOut on a field: a marker; its many conflict arms are detections.';
COMMENT ON COLUMN graphitron_tenant_fan_out.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_tenant_fan_out.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_tenant_fan_out.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_tenant_fan_out.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_tenant_fan_out.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_tenant_fan_out.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphitron_pivot (
  graph_name     VARCHAR NOT NULL,
  type_name      VARCHAR NOT NULL,
  field_name     VARCHAR NOT NULL,
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  on_column      VARCHAR NOT NULL,
  value_column   VARCHAR NOT NULL,
  vocabulary_ref VARCHAR,
  PRIMARY KEY (graph_name, type_name, field_name),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphql_field (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_pivot IS '@pivot on a field: the aggregate-projection spec.';
COMMENT ON COLUMN graphitron_pivot.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_pivot.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_pivot.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_pivot.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_pivot.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_pivot.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_pivot.on_column IS 'the on: argument, the discriminator column as written';
COMMENT ON COLUMN graphitron_pivot.value_column IS 'the value: argument as written';
COMMENT ON COLUMN graphitron_pivot.vocabulary_ref IS 'names an enum type; author-spelled, no FK';

CREATE TABLE graphitron_routine (
  graph_name     VARCHAR NOT NULL,
  type_name      VARCHAR NOT NULL,
  field_name     VARCHAR NOT NULL,
  ordinal        INT     NOT NULL,
  source_name    VARCHAR,
  source_line    INT,
  source_column  INT,
  routine_ref    VARCHAR NOT NULL,
  arg_mapping    VARCHAR,
  column_mapping VARCHAR,
  PRIMARY KEY (graph_name, type_name, field_name, ordinal),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphql_field (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_routine IS '@routine on a field: one row per application (repeatable). The table chain interleaves these with graphitron_field_reference rows in written order.';
COMMENT ON COLUMN graphitron_routine.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
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
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  position      INT     NOT NULL,
  param_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, field_name, ordinal, position),
  FOREIGN KEY (graph_name, type_name, field_name, ordinal)
    REFERENCES graphitron_routine (graph_name, type_name, field_name, ordinal)
);
COMMENT ON TABLE graphitron_routine_arg_mapping_pair IS 'An ordered pair of a @routine''s argMapping, binding a routine IN parameter to a GraphQL argument.';
COMMENT ON COLUMN graphitron_routine_arg_mapping_pair.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_routine_arg_mapping_pair.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_routine_arg_mapping_pair.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_routine_arg_mapping_pair.ordinal IS 'the owning @routine application''s ordinal';
COMMENT ON COLUMN graphitron_routine_arg_mapping_pair.position IS '0-based position within the owning list';
COMMENT ON COLUMN graphitron_routine_arg_mapping_pair.param_name IS 'the Java or routine parameter (left side of the pair)';
COMMENT ON COLUMN graphitron_routine_arg_mapping_pair.argument_path IS 'the right side as written: a GraphQL argument name or dotted input path';

CREATE TABLE graphitron_routine_column_mapping_pair (
  graph_name VARCHAR NOT NULL,
  type_name  VARCHAR NOT NULL,
  field_name VARCHAR NOT NULL,
  ordinal    INT     NOT NULL,
  position   INT     NOT NULL,
  param_name VARCHAR NOT NULL,
  column_ref VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, field_name, ordinal, position),
  FOREIGN KEY (graph_name, type_name, field_name, ordinal)
    REFERENCES graphitron_routine (graph_name, type_name, field_name, ordinal)
);
COMMENT ON TABLE graphitron_routine_column_mapping_pair IS 'columnMapping pairs bind routine parameters to previous-node columns; a dotted right side is captured as written and rejected by detection.';
COMMENT ON COLUMN graphitron_routine_column_mapping_pair.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
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
  graph_name       VARCHAR NOT NULL,
  type_name        VARCHAR NOT NULL,
  source_name      VARCHAR NOT NULL,
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  on_column        VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name),
  FOREIGN KEY (graph_name, type_name) REFERENCES graphql_type (graph_name, type_name),
  FOREIGN KEY (graph_name, type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (graph_name, type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphitron_discriminate IS '@discriminate on an interface or union: the discriminator column.';
COMMENT ON COLUMN graphitron_discriminate.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_discriminate.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_discriminate.source_name IS 'half of the site FK, so NOT NULL; a graphitron application always has an SDL position';
COMMENT ON COLUMN graphitron_discriminate.declaration_line IS 'line of the contributing declaration site, keyed with source_name';
COMMENT ON COLUMN graphitron_discriminate.declaration_column IS 'column of the contributing declaration site, the site key''s fourth part';
COMMENT ON COLUMN graphitron_discriminate.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_discriminate.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_discriminate.on_column IS 'the on: argument as written; catalog resolution is a derivation';

CREATE TABLE graphitron_discriminator (
  graph_name          VARCHAR NOT NULL,
  type_name           VARCHAR NOT NULL,
  source_name         VARCHAR NOT NULL,
  declaration_line    INT     NOT NULL,
  declaration_column  INT     NOT NULL,
  source_line         INT,
  source_column       INT,
  discriminator_value VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name),
  FOREIGN KEY (graph_name, type_name) REFERENCES graphql_type (graph_name, type_name),
  FOREIGN KEY (graph_name, type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (graph_name, type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphitron_discriminator IS '@discriminator on an object type: the participant''s discriminator value.';
COMMENT ON COLUMN graphitron_discriminator.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_discriminator.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_discriminator.source_name IS 'half of the site FK, so NOT NULL';
COMMENT ON COLUMN graphitron_discriminator.declaration_line IS 'line of the contributing declaration site, keyed with source_name';
COMMENT ON COLUMN graphitron_discriminator.declaration_column IS 'column of the contributing declaration site, the site key''s fourth part';
COMMENT ON COLUMN graphitron_discriminator.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_discriminator.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_discriminator.discriminator_value IS 'the value: argument as written (VALUE alone is an H2 reserved word)';

CREATE TABLE graphitron_federation_key (
  graph_name       VARCHAR NOT NULL,
  type_name        VARCHAR NOT NULL,
  ordinal          INT     NOT NULL,
  source_name      VARCHAR NOT NULL,
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  fields_sdl       VARCHAR NOT NULL,
  resolvable       BOOLEAN,
  PRIMARY KEY (graph_name, type_name, ordinal),
  FOREIGN KEY (graph_name, type_name) REFERENCES graphql_type (graph_name, type_name),
  FOREIGN KEY (graph_name, type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (graph_name, type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphitron_federation_key IS 'Federation @key, decoded for consumption (its verbatim twin lives in graphql_type_directive for re-emission; a gate query pins agreement).';
COMMENT ON COLUMN graphitron_federation_key.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
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
  graph_name VARCHAR NOT NULL,
  type_name  VARCHAR NOT NULL,
  ordinal    INT     NOT NULL,
  position   INT     NOT NULL,
  field_path VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, ordinal, position),
  FOREIGN KEY (graph_name, type_name, ordinal)
    REFERENCES graphitron_federation_key (graph_name, type_name, ordinal)
);
COMMENT ON TABLE graphitron_federation_key_field IS 'An ordered element of a @key field set (the field-set grammar is a parse boundary, so the decode happens at capture). The grammar admits nested selections as dotted paths; that today''s consumer rejects nesting is a detection, not a capture limit.';
COMMENT ON COLUMN graphitron_federation_key_field.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_federation_key_field.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_federation_key_field.ordinal IS 'capture-assigned position in document order';
COMMENT ON COLUMN graphitron_federation_key_field.position IS '0-based within the field set';
COMMENT ON COLUMN graphitron_federation_key_field.field_path IS 'dotted path for nested selections';

CREATE TABLE graphitron_link (
  graph_name    VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  url           VARCHAR,
  PRIMARY KEY (graph_name, ordinal),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
COMMENT ON TABLE graphitron_link IS '@link on the schema definition, decoded. All @link applications decode here (the verbatim twin sits in graphql_schema_directive); whether a link is the federation opt-in is a predicate over url, a derivation. @tag and @shareable get no decoded relations: their only readers are the expansion machinery itself, which is the capture walk with the AST in hand, so downstream consumers see them only as fidelity rows for re-emission.';
COMMENT ON COLUMN graphitron_link.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_link.ordinal IS '@link is repeatable; document order';
COMMENT ON COLUMN graphitron_link.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_link.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_link.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_link.url IS 'as written';

CREATE TABLE graphitron_link_import (
  graph_name   VARCHAR NOT NULL,
  link_ordinal INT     NOT NULL,
  position     INT     NOT NULL,
  name         VARCHAR NOT NULL,
  alias        VARCHAR,
  PRIMARY KEY (graph_name, link_ordinal, position),
  FOREIGN KEY (graph_name, link_ordinal) REFERENCES graphitron_link (graph_name, ordinal)
);
COMMENT ON TABLE graphitron_link_import IS 'An ordered import entry of an @link, covering both the string form and the object form.';
COMMENT ON COLUMN graphitron_link_import.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
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
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (graph_name, type_name, field_name),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphql_field (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_multitable_reference IS '@multitableReference (removed) on a field; routes is never read.';
COMMENT ON COLUMN graphitron_multitable_reference.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_multitable_reference.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_multitable_reference.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_multitable_reference.source_name IS 'the SDL file the row was captured from';
COMMENT ON COLUMN graphitron_multitable_reference.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_multitable_reference.source_column IS 'source column, 1-based per the graphql-java convention';

CREATE TABLE graphitron_record (
  graph_name       VARCHAR NOT NULL,
  type_name        VARCHAR NOT NULL,
  source_name      VARCHAR NOT NULL,
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  class_name       VARCHAR,
  PRIMARY KEY (graph_name, type_name),
  FOREIGN KEY (graph_name, type_name) REFERENCES graphql_type (graph_name, type_name),
  FOREIGN KEY (graph_name, type_name, source_name, declaration_line, declaration_column)
    REFERENCES graphql_type_declaration (graph_name, type_name, source_name, source_line, source_column)
);
COMMENT ON TABLE graphitron_record IS '@record (deprecated, ignored) on an object or input type. class_name is the one payload value a consumer reads: the warning arms compare it against the reflected backing class.';
COMMENT ON COLUMN graphitron_record.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_record.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_record.source_name IS 'half of the site FK, so NOT NULL; a graphitron application always has an SDL position';
COMMENT ON COLUMN graphitron_record.declaration_line IS 'line of the contributing declaration site, keyed with source_name';
COMMENT ON COLUMN graphitron_record.declaration_column IS 'column of the contributing declaration site, the site key''s fourth part';
COMMENT ON COLUMN graphitron_record.source_line IS 'source line, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_record.source_column IS 'source column, 1-based per the graphql-java convention';
COMMENT ON COLUMN graphitron_record.class_name IS 'record.className as written';

CREATE TABLE graphitron_undecoded_argument (
  graph_name              VARCHAR NOT NULL,
  source_name             VARCHAR NOT NULL,
  source_line             INT     NOT NULL,
  source_column           INT     NOT NULL,
  directive_name          VARCHAR NOT NULL,
  directive_argument_name VARCHAR NOT NULL,
  value_sdl               VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, source_name, source_line, source_column, directive_name, directive_argument_name),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
COMMENT ON TABLE graphitron_undecoded_argument IS 'The tolerant-decode overflow: a graphitron application argument whose literal does not fit the declared shape decodes to NULL in its typed column and quarantines its raw text here, so the authored value is never lost and the malformed-literal detection has its row. Empty while assembly runs upstream.';
COMMENT ON COLUMN graphitron_undecoded_argument.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
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
  graph_name         VARCHAR NOT NULL,
  type_name          VARCHAR NOT NULL,
  source_name        VARCHAR NOT NULL,
  source_line        INT     NOT NULL,
  source_column      INT     NOT NULL,
  macro              VARCHAR NOT NULL,
  carrier_type_name  VARCHAR,
  carrier_field_name VARCHAR,
  PRIMARY KEY (graph_name, type_name, source_name, source_line, source_column),
  FOREIGN KEY (graph_name, type_name, source_name, source_line, source_column)
    REFERENCES graphql_type_declaration (graph_name, type_name, source_name, source_line, source_column),
  CHECK (macro IN ('CONNECTION', 'FEDERATION'))
);
COMMENT ON TABLE graphitron_type_declaration_synthesis IS 'A declaration site was contributed by a macro rather than the author: a definition site when the macro creates the type (Connection, Edge, facet shapes, at merge_ordinal 0), an extension site when it adds members to an existing type (the Query fields federation adds from @link), and an empty extension site when a later carrier touches a shared machinery type (PageInfo), so carrier multiplicity is the site count. Synthesized element rows hang off these sites through the ordinary declaration reference, which is what marks additions without per-element provenance; a type is synthesized exactly when its merge_ordinal-0 site is.';
COMMENT ON COLUMN graphitron_type_declaration_synthesis.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_type_declaration_synthesis.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_type_declaration_synthesis.source_name IS 'the causing application''s position, which is the site''s identity';
COMMENT ON COLUMN graphitron_type_declaration_synthesis.source_line IS 'line of the causing application, which is the site''s identity';
COMMENT ON COLUMN graphitron_type_declaration_synthesis.source_column IS 'the site key''s fourth part, as on graphql_type_declaration';
COMMENT ON COLUMN graphitron_type_declaration_synthesis.macro IS 'which expansion contributed the site';
COMMENT ON COLUMN graphitron_type_declaration_synthesis.carrier_type_name IS 'the causing coordinate; NULL for schema-level causes (@link)';
COMMENT ON COLUMN graphitron_type_declaration_synthesis.carrier_field_name IS 'the causing field coordinate; NULL for type- and schema-level causes';

CREATE TABLE graphitron_field_synthesis (
  graph_name        VARCHAR NOT NULL,
  type_name         VARCHAR NOT NULL,
  field_name        VARCHAR NOT NULL,
  macro             VARCHAR NOT NULL,
  authored_type_sdl VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, field_name),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphql_field (graph_name, type_name, field_name),
  CHECK (macro IN ('CONNECTION'))
);
COMMENT ON TABLE graphitron_field_synthesis IS 'A field''s type expression was rewritten by a macro; the authored expression survives here while the field''s graphql_field row holds the effective one.';
COMMENT ON COLUMN graphitron_field_synthesis.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_field_synthesis.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_field_synthesis.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_field_synthesis.macro IS 'which expansion rewrote the type expression';
COMMENT ON COLUMN graphitron_field_synthesis.authored_type_sdl IS 'the type expression as the author wrote it, pre-expansion';

CREATE TABLE graphitron_type_directive_synthesis (
  graph_name     VARCHAR NOT NULL,
  type_name      VARCHAR NOT NULL,
  directive_name VARCHAR NOT NULL,
  ordinal        INT     NOT NULL,
  macro          VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, directive_name, ordinal),
  FOREIGN KEY (graph_name, type_name, directive_name, ordinal)
    REFERENCES graphql_type_directive (graph_name, type_name, directive_name, ordinal),
  CHECK (macro IN ('FEDERATION_KEY'))
);
COMMENT ON TABLE graphitron_type_directive_synthesis IS 'A type-level directive application was synthesized rather than authored (federation key synthesis; the application itself sits in graphql_type_directive and graphitron_federation_key like any other, and must re-emit, so provenance is this relation, not exclusion).';
COMMENT ON COLUMN graphitron_type_directive_synthesis.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_type_directive_synthesis.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_type_directive_synthesis.directive_name IS 'the applied or defined directive name, without the leading @';
COMMENT ON COLUMN graphitron_type_directive_synthesis.ordinal IS 'capture-assigned position in document order';
COMMENT ON COLUMN graphitron_type_directive_synthesis.macro IS 'which expansion synthesized the application';


-- ==== SQL catalog facts ===========================================================
-- What the consumer's database declares, in SQL's vocabulary. jOOQ's generated model is the
-- reader, not the owner: reading INFORMATION_SCHEMA directly instead would leave every relation
-- name here correct. "Catalog" stays the prose word for what the family is about; only the
-- prefix carries the rule.
CREATE TABLE sql_schema (
  source_name    VARCHAR NOT NULL,
  table_schema   VARCHAR NOT NULL,
  keys_class_fqn VARCHAR,
  PRIMARY KEY (source_name, table_schema),
  FOREIGN KEY (source_name) REFERENCES store_source (source_name)
);
COMMENT ON TABLE sql_schema IS 'A schema exists in the consumer''s catalog, and carries the generated artifacts that belong to the schema rather than to any one of its tables. It exists because the Keys class is per schema: hanging its name off sql_table would repeat one value across every table in the schema, which is the repeating group the projection era shipped. Written for every schema the catalog census touches, so a table''s schema is always present.';
COMMENT ON COLUMN sql_schema.source_name IS 'the generated package the schema lives in; the partition this row belongs to and the key''s leading dimension, as on sql_table';
COMMENT ON COLUMN sql_schema.table_schema IS 'SQL schema name; empty string when the generated model declares no schema for its tables, which is the same fallback sql_table applies';
COMMENT ON COLUMN sql_schema.keys_class_fqn IS 'the fully qualified name of the generated Keys class holding this schema''s key constants, resolved by loading it off the codegen classpath rather than by concatenating a configured package with ".Keys". The guess and the fact diverge under multi-schema layouts, where each schema gets its own Keys class in its own package. Null when the generated model carries no Keys class for the schema, which is a fact: a schema with no keys has no constants to name. Goto-definition on @reference(key:) lands in this class, so it is a join key rather than a completion nicety.';

CREATE TABLE sql_table (
  source_name  VARCHAR NOT NULL,
  table_schema VARCHAR NOT NULL,
  table_name   VARCHAR NOT NULL,
  jooq_name    VARCHAR NOT NULL,
  class_fqn    VARCHAR NOT NULL,
  description  VARCHAR,
  PRIMARY KEY (source_name, table_schema, table_name),
  FOREIGN KEY (source_name) REFERENCES store_source (source_name),
  FOREIGN KEY (source_name, table_schema) REFERENCES sql_schema (source_name, table_schema)
);
COMMENT ON TABLE sql_table IS 'A table exists in the consumer''s catalog. Every table jOOQ''s generated model declares, across every schema it declares; ambiguity of an unqualified @table(name:) is a resolution question and therefore derivation, so capture just records them all.';
COMMENT ON COLUMN sql_table.source_name IS 'the generated package the table''s schema lives in; the partition this row belongs to and the key''s leading dimension, so two modules'' catalogs carrying one (schema, table) coordinate coexist instead of the second build clobbering the first. The package rather than the classpath entry it was loaded from, because one jar carries every schema a codegen run produced and invalidating the jar would discard them all, while the package is the granularity codegen actually rewrites. Schemas flattened into one package (jOOQ''s outputSchemaToDefault) share a source, which is correct: they are regenerated together';
COMMENT ON COLUMN sql_table.table_schema IS 'SQL schema the table lives in';
COMMENT ON COLUMN sql_table.table_name IS 'SQL table name';
COMMENT ON COLUMN sql_table.class_fqn IS 'the fully qualified name of the generated jOOQ table class, read off the live Table during the catalog walk. Per table, unlike the Keys class name, which is per schema and lives on sql_schema. Goto-definition on @table(name:) and @field(name:) lands in this class, and jvm_class cannot supply it because that family deliberately excludes the generated jOOQ package, so this is the join key that reaches generated sources at all.';
COMMENT ON COLUMN sql_table.jooq_name IS 'the generated jOOQ Java field name for the table; under a family named for SQL this is the one foreign column, so the prefix marks it rather than leaving a reader to infer it';
COMMENT ON COLUMN sql_table.description IS 'the database comment on the table, when present';

CREATE TABLE sql_column (
  source_name  VARCHAR NOT NULL,
  table_schema VARCHAR NOT NULL,
  table_name   VARCHAR NOT NULL,
  column_name  VARCHAR NOT NULL,
  ordinal      INT     NOT NULL,
  jooq_name    VARCHAR NOT NULL,
  sql_type     VARCHAR NOT NULL,
  binding_type VARCHAR NOT NULL,
  nullable     BOOLEAN NOT NULL,
  description  VARCHAR,
  PRIMARY KEY (source_name, table_schema, table_name, column_name),
  FOREIGN KEY (source_name, table_schema, table_name) REFERENCES sql_table (source_name, table_schema, table_name)
);
COMMENT ON TABLE sql_column IS 'A column exists on a table. The SQL name is the coordinate, which is what the schema''s directives spell; the jOOQ name rides along because the LSP surface is Java-name-centric. A column carries two types, not one: the SQL type the database declares and the Java type jOOQ binds it to. Both are facts about the column and neither derives from the other by any rule the store could apply, since the mapping is the generator''s configured binding.';
COMMENT ON COLUMN sql_column.source_name IS 'the owning partition''s generated-package source, as on sql_table; the key''s leading dimension';
COMMENT ON COLUMN sql_column.table_schema IS 'SQL schema the table lives in';
COMMENT ON COLUMN sql_column.table_name IS 'SQL table name';
COMMENT ON COLUMN sql_column.column_name IS 'SQL column name';
COMMENT ON COLUMN sql_column.ordinal IS 'column position in the table definition, read from Table.fields() rather than from the reflective field walk, whose order is unspecified';
COMMENT ON COLUMN sql_column.jooq_name IS 'the generated jOOQ Java field name; the one column here written in the reader''s vocabulary rather than SQL''s';
COMMENT ON COLUMN sql_column.sql_type IS 'the column''s SQL type as jOOQ reports it';
COMMENT ON COLUMN sql_column.binding_type IS 'the fully qualified Java type jOOQ binds the column to, as Field.getType() reports it; read off the live Field during the catalog walk and unrecoverable afterwards, since nothing outside the codegen classpath can resolve a configured binding. Hover renders it beside the SQL type, which is why a column needs both and why keeping only one of them capped what the editor could say about a column.';
COMMENT ON COLUMN sql_column.nullable IS 'whether the column admits NULL';
COMMENT ON COLUMN sql_column.description IS 'the database comment on the column, when present';

CREATE TABLE sql_constraint (
  source_name     VARCHAR NOT NULL,
  table_schema    VARCHAR NOT NULL,
  table_name      VARCHAR NOT NULL,
  constraint_name VARCHAR NOT NULL,
  constraint_type VARCHAR NOT NULL,
  jooq_name       VARCHAR,
  PRIMARY KEY (source_name, table_schema, table_name, constraint_name),
  FOREIGN KEY (source_name, table_schema, table_name) REFERENCES sql_table (source_name, table_schema, table_name),
  CHECK (constraint_type IN ('PRIMARY KEY', 'UNIQUE', 'FOREIGN KEY'))
);
COMMENT ON TABLE sql_constraint IS 'A named constraint exists on a table. The supertype: one row per constraint whatever its form, discriminated by constraint_type as the standard''s TABLE_CONSTRAINTS is. Filtered to what jOOQ''s generated model carries: PRIMARY KEY, UNIQUE and FOREIGN KEY. CHECK, NOT NULL and deferrability are absent, and arrive as further type values rather than as new relations.';
COMMENT ON COLUMN sql_constraint.jooq_name IS 'the generated Keys-class constant name for this constraint, which is what an author types in @reference(key:). Resolved by reference identity over the Keys class''s fields rather than by any formula over the constraint name, so a name colliding across schemas cannot mis-resolve; that resolution needs the live key on the codegen classpath and is unrecoverable afterwards. Null when the constraint resolves to no constant, which is a fact and not a failure: a generated model need not carry a Keys class, and a key with no constant is one nobody can name. Nullable where sql_table.jooq_name and sql_column.jooq_name are not, because a table and a column always have a generated Java name and a constraint need not.';
COMMENT ON COLUMN sql_constraint.source_name IS 'the owning partition''s generated-package source, as on sql_table; the key''s leading dimension';
COMMENT ON COLUMN sql_constraint.table_schema IS 'SQL schema the table lives in';
COMMENT ON COLUMN sql_constraint.table_name IS 'SQL table name';
COMMENT ON COLUMN sql_constraint.constraint_name IS 'SQL constraint name';
COMMENT ON COLUMN sql_constraint.constraint_type IS 'the standard''s TABLE_CONSTRAINTS vocabulary; the domain is closed over what the catalog walk reads, so a violation is a capture bug';

CREATE TABLE sql_constraint_column (
  source_name     VARCHAR NOT NULL,
  table_schema    VARCHAR NOT NULL,
  table_name      VARCHAR NOT NULL,
  constraint_name VARCHAR NOT NULL,
  position        INT     NOT NULL,
  column_name     VARCHAR NOT NULL,
  PRIMARY KEY (source_name, table_schema, table_name, constraint_name, position),
  FOREIGN KEY (source_name, table_schema, table_name, constraint_name)
    REFERENCES sql_constraint (source_name, table_schema, table_name, constraint_name),
  FOREIGN KEY (source_name, table_schema, table_name, column_name)
    REFERENCES sql_column (source_name, table_schema, table_name, column_name)
);
COMMENT ON TABLE sql_constraint_column IS 'An ordered column of a constraint: the key columns of a primary key or a unique constraint, and the referencing columns of a foreign key, in one relation for all three forms as KEY_COLUMN_USAGE does. A foreign key''s target columns are not here; they are the referenced constraint''s own rows, matched on position.';
COMMENT ON COLUMN sql_constraint_column.source_name IS 'the owning partition''s generated-package source, as on sql_table; the key''s leading dimension';
COMMENT ON COLUMN sql_constraint_column.table_schema IS 'SQL schema the table lives in';
COMMENT ON COLUMN sql_constraint_column.table_name IS 'SQL table name';
COMMENT ON COLUMN sql_constraint_column.constraint_name IS 'SQL constraint name';
COMMENT ON COLUMN sql_constraint_column.position IS '0-based position in the constraint''s column list';
COMMENT ON COLUMN sql_constraint_column.column_name IS 'SQL column name';

CREATE TABLE sql_primary_key (
  source_name     VARCHAR NOT NULL,
  table_schema    VARCHAR NOT NULL,
  table_name      VARCHAR NOT NULL,
  constraint_name VARCHAR NOT NULL,
  PRIMARY KEY (source_name, table_schema, table_name),
  FOREIGN KEY (source_name, table_schema, table_name, constraint_name)
    REFERENCES sql_constraint (source_name, table_schema, table_name, constraint_name)
);
COMMENT ON TABLE sql_primary_key IS 'Table T''s primary key is constraint C. Keyed by the table, because a table has at most one, which is what makes the cardinality structural instead of a gate query over a flag.';
COMMENT ON COLUMN sql_primary_key.source_name IS 'the owning partition''s generated-package source, as on sql_table; the key''s leading dimension';
COMMENT ON COLUMN sql_primary_key.table_schema IS 'SQL schema the table lives in';
COMMENT ON COLUMN sql_primary_key.table_name IS 'SQL table name';
COMMENT ON COLUMN sql_primary_key.constraint_name IS 'the name of the PRIMARY KEY constraint in sql_constraint';

CREATE TABLE sql_referential_constraint (
  source_name                VARCHAR NOT NULL,
  table_schema               VARCHAR NOT NULL,
  table_name                 VARCHAR NOT NULL,
  constraint_name            VARCHAR NOT NULL,
  referenced_source_name     VARCHAR NOT NULL,
  referenced_schema          VARCHAR NOT NULL,
  referenced_table           VARCHAR NOT NULL,
  referenced_constraint_name VARCHAR NOT NULL,
  PRIMARY KEY (source_name, table_schema, table_name, constraint_name),
  FOREIGN KEY (source_name, table_schema, table_name, constraint_name)
    REFERENCES sql_constraint (source_name, table_schema, table_name, constraint_name),
  FOREIGN KEY (referenced_source_name, referenced_schema, referenced_table, referenced_constraint_name)
    REFERENCES sql_constraint (source_name, table_schema, table_name, constraint_name)
);
COMMENT ON TABLE sql_referential_constraint IS 'A foreign key references a constraint, the foreign-key-only extension of sql_constraint. Referencing the constraint rather than the table is what SQL declares; the target columns are that constraint''s own sql_constraint_column rows matched on position, which is how both Oracle and the standard resolve them and is guaranteed by SQL semantics, never copied onto the referencing row. Implicit-path inference ("exactly one FK between these two tables") is a derivation over this relation, not a captured fact.';
COMMENT ON COLUMN sql_referential_constraint.source_name IS 'the owning partition''s generated-package source, as on sql_table; the key''s leading dimension';
COMMENT ON COLUMN sql_referential_constraint.table_schema IS 'schema of the declaring table';
COMMENT ON COLUMN sql_referential_constraint.table_name IS 'the declaring (source) table';
COMMENT ON COLUMN sql_referential_constraint.constraint_name IS 'SQL constraint name';
COMMENT ON COLUMN sql_referential_constraint.referenced_source_name IS 'the referenced constraint''s own generated-package source: equal to source_name for the ordinary in-package reference, different exactly when a foreign key crosses schemas that codegen wrote into different packages, which the multi-schema layout produces. Part of the composite reference, since the constraint''s key leads with its source';
COMMENT ON COLUMN sql_referential_constraint.referenced_schema IS 'schema of the referenced constraint''s table; part of the composite reference, not a denormalisation';
COMMENT ON COLUMN sql_referential_constraint.referenced_table IS 'the referenced constraint''s table';
COMMENT ON COLUMN sql_referential_constraint.referenced_constraint_name IS 'the referenced constraint''s name';

CREATE TABLE sql_index (
  source_name  VARCHAR NOT NULL,
  table_schema VARCHAR NOT NULL,
  table_name   VARCHAR NOT NULL,
  index_name   VARCHAR NOT NULL,
  PRIMARY KEY (source_name, table_schema, table_name, index_name),
  FOREIGN KEY (source_name, table_schema, table_name) REFERENCES sql_table (source_name, table_schema, table_name)
);
COMMENT ON TABLE sql_index IS 'An index exists on a table (@order(index:) and @index resolve against it). Filtered: jOOQ''s Table.getIndexes() excludes the indexes backing a primary key or unique constraint, so those are absent here and present in sql_constraint instead. @order(index:) naming a primary key''s index therefore resolves against a documented absence rather than an apparent one.';
COMMENT ON COLUMN sql_index.source_name IS 'the owning partition''s generated-package source, as on sql_table; the key''s leading dimension';
COMMENT ON COLUMN sql_index.table_schema IS 'SQL schema the table lives in';
COMMENT ON COLUMN sql_index.table_name IS 'SQL table name';
COMMENT ON COLUMN sql_index.index_name IS 'SQL index name';

CREATE TABLE sql_index_column (
  source_name  VARCHAR NOT NULL,
  table_schema VARCHAR NOT NULL,
  table_name   VARCHAR NOT NULL,
  index_name   VARCHAR NOT NULL,
  position     INT     NOT NULL,
  column_name  VARCHAR NOT NULL,
  PRIMARY KEY (source_name, table_schema, table_name, index_name, position),
  FOREIGN KEY (source_name, table_schema, table_name, index_name)
    REFERENCES sql_index (source_name, table_schema, table_name, index_name)
);
COMMENT ON TABLE sql_index_column IS 'An ordered column of an index.';
COMMENT ON COLUMN sql_index_column.source_name IS 'the owning partition''s generated-package source, as on sql_table; the key''s leading dimension';
COMMENT ON COLUMN sql_index_column.table_schema IS 'SQL schema the table lives in';
COMMENT ON COLUMN sql_index_column.table_name IS 'SQL table name';
COMMENT ON COLUMN sql_index_column.index_name IS 'SQL index name';
COMMENT ON COLUMN sql_index_column.position IS '0-based position in the index''s column list';
COMMENT ON COLUMN sql_index_column.column_name IS 'SQL column name';


-- ==== JVM classpath facts =========================================================
-- What the classfiles on the compile classpath declare, in the JVM's vocabulary: classes, the
-- supertypes they name, methods and their parameters, record components, scalar-type fields.
-- The rows are read by a
-- bytecode-only scan, so nothing here is a class graphitron owns or a role it assigns; a jar
-- class an author may name in @record / @service / @enum / @scalarType earns a row on the same
-- terms as a reactor one. Javadoc and source positions deliberately stay out: what a classfile
-- declares lives here, and where a declaration is written and what its doc comment says lives in
-- the java_ family on the source's own cadence, joined by name. That division is why a .java edit
-- moves a position without a generator round, and why the two populations can disagree.
CREATE TABLE jvm_class (
  source_name VARCHAR NOT NULL,
  class_name  VARCHAR NOT NULL,
  class_kind  VARCHAR NOT NULL,
  PRIMARY KEY (source_name, class_name),
  FOREIGN KEY (source_name) REFERENCES store_source (source_name),
  CHECK (class_kind IN ('CLASS', 'INTERFACE', 'ENUM', 'RECORD', 'ANNOTATION'))
);
COMMENT ON TABLE jvm_class IS 'A class exists on the compile classpath, as the codegen loader would resolve it. Filtered: public, non-synthetic, top-level (a simple name containing $ is skipped, so nested classes are absent), and outside the generated jOOQ package. A resolution detection over this relation reads those filters as absence, so they are stated rather than implied.';
COMMENT ON COLUMN jvm_class.source_name IS 'the classpath entry it was read from; the partition this row belongs to and the key''s leading dimension. Within one run a class present under more than one entry is captured once, at the entry that comes first in classpath order, which is where a classloader would resolve it; store-wide, two runs'' entries are two partitions that coexist by design, so one class name may legitimately appear under several sources';
COMMENT ON COLUMN jvm_class.class_name IS 'fully qualified binary name';
COMMENT ON COLUMN jvm_class.class_kind IS 'the classfile''s declared form; the domain is closed over classfile shapes, so a violation is a capture bug';

CREATE TABLE jvm_class_supertype (
  source_name    VARCHAR NOT NULL,
  class_name     VARCHAR NOT NULL,
  supertype_name VARCHAR NOT NULL,
  declared_via   VARCHAR NOT NULL,
  PRIMARY KEY (source_name, class_name, supertype_name),
  FOREIGN KEY (source_name, class_name) REFERENCES jvm_class (source_name, class_name),
  CHECK (declared_via IN ('EXTENDS', 'IMPLEMENTS'))
);
COMMENT ON TABLE jvm_class_supertype IS 'A supertype a class in the census declares: its extends clause and its implements list, as the classfile spells them. This is the relation an assignability closure is taken over, and assignability is the one rule a walk over accessor and return types could not state without a live loader; the classfile declares its own supertypes and the scan simply was not reading them. java.lang.Object is deliberately absent, on the same terms the census states its other filters: the JVM writes it as the superclass of every class that declared no extends clause and of every interface, so a row would assert a declaration the source never made, and the closure would gain an edge every reference type already has. A supertype name outside the census is still a row, and at the end of a chain that is the ordinary case: the scan drops nested classes and the generated jOOQ package, and nothing ships the JDK as a classpath entry, while what a closure needs is the name a classfile declares. The chain terminates at such a name, so a derivation reads a missing hop as not-known-to-be-assignable rather than as not-assignable; org.jooq.Result reaching java.util.List is one hop within the census and resolves, a method declared to return java.util.ArrayList does not. Declaration order of the implements list is not carried, no consumer asking which interface came first.';
COMMENT ON COLUMN jvm_class_supertype.source_name IS 'the declaring class''s classpath entry, as on jvm_class; the key''s leading dimension';
COMMENT ON COLUMN jvm_class_supertype.class_name IS 'the fully-qualified binary name of the declaring class';
COMMENT ON COLUMN jvm_class_supertype.supertype_name IS 'the fully-qualified binary name the classfile declares as the supertype, a nested one spelled with the $ the JVM uses. Deliberately not a foreign key and frequently not a census row at all; see this relation''s comment';
COMMENT ON COLUMN jvm_class_supertype.declared_via IS 'EXTENDS or IMPLEMENTS: which clause of the source declaration the name came from, which is a different question from what the supertype''s own declared form is. Read from the declaring class''s kind rather than from which classfile slot held the name, the JVM storing an interface''s super-interfaces in the same array as a class''s implements list while the source writes them after extends. Carried rather than derived because it is not recoverable: reading it off the supertype''s class_kind needs the supertype to have a census row, and the names that do not are exactly the ones this relation exists to record';

CREATE TABLE jvm_method (
  source_name       VARCHAR NOT NULL,
  class_name        VARCHAR NOT NULL,
  method_name       VARCHAR NOT NULL,
  descriptor        VARCHAR NOT NULL,
  return_type       VARCHAR NOT NULL,
  declared_return_type VARCHAR NOT NULL,
  returns_condition BOOLEAN NOT NULL,
  PRIMARY KEY (source_name, class_name, method_name, descriptor),
  FOREIGN KEY (source_name, class_name) REFERENCES jvm_class (source_name, class_name)
);
COMMENT ON TABLE jvm_method IS 'A public method exists on a class in the census. Filtered: public and non-synthetic, constructors and class initializers excluded. The method''s types are carried in both forms, erased and declared, because neither is a function of the other: erasure maps a type variable to its bound, which the declared form does not name, and the declared form names a container''s element type, which the erasure does not. A surface testing a type''s identity reads the erasure and one spelling a signature for an author reads the declared form.';
COMMENT ON COLUMN jvm_method.source_name IS 'the owning class''s classpath entry, as on jvm_class; the key''s leading dimension';
COMMENT ON COLUMN jvm_method.class_name IS 'the fully-qualified Java class name as written';
COMMENT ON COLUMN jvm_method.method_name IS 'the method name; not a key on its own, overloads share it';
COMMENT ON COLUMN jvm_method.descriptor IS 'raw JVM descriptor; the overload discriminator that keeps this key natural';
COMMENT ON COLUMN jvm_method.return_type IS 'erased source-form return type: what the JVM descriptor carries, package dropped. The form a check on a type''s identity compares against';
COMMENT ON COLUMN jvm_method.declared_return_type IS 'the return type as the source declared it, package dropped and type arguments kept (List<Film>, Field<String>, T). Read from the classfile Signature attribute, and equal to return_type wherever the compiler emitted no attribute, which it does only where erasure loses nothing. Never NULL and never coalesced by a reader: whether a classfile stored the declared form separately is an encoding detail, not a fact about the method, so the census answers the question once. This is the column an accessor walk follows, a container''s element type being exactly what the erasure drops';
COMMENT ON COLUMN jvm_method.returns_condition IS 'matched on the un-erased org.jooq.Condition descriptor, so a consumer''s own Condition type does not false-match';

CREATE TABLE jvm_method_parameter (
  source_name    VARCHAR NOT NULL,
  class_name     VARCHAR NOT NULL,
  method_name    VARCHAR NOT NULL,
  descriptor     VARCHAR NOT NULL,
  position       INT     NOT NULL,
  parameter_name VARCHAR,
  parameter_type VARCHAR NOT NULL,
  declared_parameter_type VARCHAR NOT NULL,
  PRIMARY KEY (source_name, class_name, method_name, descriptor, position),
  FOREIGN KEY (source_name, class_name, method_name, descriptor)
    REFERENCES jvm_method (source_name, class_name, method_name, descriptor)
);
COMMENT ON TABLE jvm_method_parameter IS 'An ordered parameter of a captured method. Deliberately no parameter-source column: which ParamSource a parameter binds to is decided per directive application, not per method, so it is a derived relation keyed by the application coordinate and lands with its first consumer.';
COMMENT ON COLUMN jvm_method_parameter.source_name IS 'the owning class''s classpath entry, as on jvm_class; the key''s leading dimension';
COMMENT ON COLUMN jvm_method_parameter.class_name IS 'the fully-qualified Java class name as written';
COMMENT ON COLUMN jvm_method_parameter.method_name IS 'the owning method name';
COMMENT ON COLUMN jvm_method_parameter.descriptor IS 'the owning method''s raw JVM descriptor';
COMMENT ON COLUMN jvm_method_parameter.position IS '0-based parameter position';
COMMENT ON COLUMN jvm_method_parameter.parameter_name IS 'NULL when the consumer compiled without -parameters';
COMMENT ON COLUMN jvm_method_parameter.parameter_type IS 'erased source-form parameter type, on the same terms as jvm_method.return_type';
COMMENT ON COLUMN jvm_method_parameter.declared_parameter_type IS 'the parameter type as the source declared it, on the same terms as jvm_method.declared_return_type. Falls back to the erasure for every parameter of the method, not just this one, where the signature''s argument list and the descriptor''s differ in length: a compiler-synthesised parameter appears in one and not the other, and pairing by position past that point would name the wrong type';

CREATE TABLE jvm_record_component (
  source_name    VARCHAR NOT NULL,
  class_name     VARCHAR NOT NULL,
  component_name VARCHAR NOT NULL,
  position       INT     NOT NULL,
  display_type   VARCHAR NOT NULL,
  declared_type  VARCHAR NOT NULL,
  PRIMARY KEY (source_name, class_name, component_name),
  FOREIGN KEY (source_name, class_name) REFERENCES jvm_class (source_name, class_name)
);
COMMENT ON TABLE jvm_record_component IS 'A record component of a record class in the census, read from the classfile RecordAttribute rather than from any bytecode; backs record-mapping facts.';
COMMENT ON COLUMN jvm_record_component.source_name IS 'the owning class''s classpath entry, as on jvm_class; the key''s leading dimension';
COMMENT ON COLUMN jvm_record_component.class_name IS 'the fully-qualified Java class name as written';
COMMENT ON COLUMN jvm_record_component.component_name IS 'the record component name';
COMMENT ON COLUMN jvm_record_component.position IS 'component position in the record header';
COMMENT ON COLUMN jvm_record_component.display_type IS 'erased display form of the component type, on the same terms as jvm_method.return_type';
COMMENT ON COLUMN jvm_record_component.declared_type IS 'the component type as the source declared it, on the same terms as jvm_method.declared_return_type. Read from the component''s own Signature attribute rather than from the accessor method the record generates, the component being where the declaration is';

CREATE TABLE jvm_scalar_type_field (
  source_name VARCHAR NOT NULL,
  class_name VARCHAR NOT NULL,
  field_name VARCHAR NOT NULL,
  PRIMARY KEY (source_name, class_name, field_name),
  FOREIGN KEY (source_name, class_name) REFERENCES jvm_class (source_name, class_name)
);
COMMENT ON TABLE jvm_scalar_type_field IS 'A public static field whose declared type is exactly graphql.schema.GraphQLScalarType (backs @scalarType resolution). Filtered by that descriptor, which is why the selector is in the name: a total-sounding jvm_static_field would mislead about the contents. final is deliberately not required, the reflective resolver binding a non-final field just as well, so these are not necessarily constants.';
COMMENT ON COLUMN jvm_scalar_type_field.source_name IS 'the owning class''s classpath entry, as on jvm_class; the key''s leading dimension';
COMMENT ON COLUMN jvm_scalar_type_field.class_name IS 'the fully-qualified Java class name as written';
COMMENT ON COLUMN jvm_scalar_type_field.field_name IS 'the field name, matched on the exact GraphQLScalarType descriptor';

-- ==== Java source declaration facts ===============================================
-- What the consumer's .java sources declare, in the source language's vocabulary: where each
-- class, method and field is written, and what its doc comment says. Its own population beside
-- the jvm_ census rather than columns on it, joined to it by name and never keyed by it. Three
-- facts force that separation. A source parse yields arity where a classfile yields a
-- descriptor, so the two cannot share a method key. The jvm_ census excludes the generated jOOQ
-- package, which is exactly where a jump from @table or @field(name:) has to land, so a family
-- hanging off jvm_class could not answer for the half that matters most. And a .java edit
-- refreshes here without a generator round, so the two populations may legitimately disagree
-- between cadences; no view here asserts they agree, because the skew is real and visible skew
-- beats ambient skew.
-- The family is file-keyed, and its files are deliberately not store_source rows: store_source
-- is a capture round's read set, and a .java file is read by neither the SDL walk nor the
-- classpath scan. java_file carries this family's own freshness bookkeeping instead, which keeps
-- store_source's kind taxonomy closed and its currency scan proportional to what capture reads.
-- Graph scoping, for a query that needs it, happens on the jvm_ or sql_ side of the name join
-- through store_graph_source; this family answers for a file, and a file belongs to whoever
-- compiles it.
CREATE TABLE java_file (
  file        VARCHAR NOT NULL,
  source_root VARCHAR NOT NULL,
  stamp       VARCHAR NOT NULL,
  PRIMARY KEY (file)
);
COMMENT ON TABLE java_file IS 'A .java file whose declarations this store holds, and the stamp they were read at. The family''s refresh unit: one transaction per file, retained when the file still hashes to its stamp and rewritten whole when it does not, so an edit costs one parse rather than a workspace walk. A row exists exactly while the last walk covering the file''s root saw it, so a file deleted under a walked root loses its row and its declarations with it.';
COMMENT ON COLUMN java_file.file IS 'absolute normalised path of the source file; the family''s partition dimension and the grain its refresh runs at. Path form, as store_source spells a schema file, but never a store_source row (see this family''s charter) and never joined to jvm_class.source_name, which names a classpath entry rather than a source file';
COMMENT ON COLUMN java_file.source_root IS 'the walked root the file was reached under, on jvm_class.source_name''s terms: where the row came from, and so the scope whoever put it there owns. A walk prunes the files that left its own roots and leaves a sibling module''s alone. A file reachable under two nested roots is attributed to whichever root reached it first in the walk''s own order, one row either way';
COMMENT ON COLUMN java_file.stamp IS 'content hash of the file as parsed, on ClasspathSources'' terms and for its reasons: modification time is a heuristic that a checkout, a rebase or a container layer defeats. NOT NULL because a file that cannot be read cannot be parsed either, so there is no partially-written partition to record';

CREATE TABLE java_class_declaration (
  file          VARCHAR NOT NULL,
  class_name    VARCHAR NOT NULL,
  source_line   INT     NOT NULL,
  source_column INT     NOT NULL,
  javadoc       VARCHAR,
  PRIMARY KEY (file, class_name),
  FOREIGN KEY (file) REFERENCES java_file (file)
);
COMMENT ON TABLE java_class_declaration IS 'A class, interface, enum, record or annotation declaration written in a source file, at the position the parse read it. Keyed by file and name rather than by name alone: two files declaring one fully-qualified name is malformed Java that a parse still reads, and a relation keyed on the name would have to pick one of them. A nested class earns its own row under the dotted name its declaration chain spells; anonymous and local classes have no name to key on and are absent.';
COMMENT ON COLUMN java_class_declaration.file IS 'the source file the declaration is written in; the family''s partition dimension and the key''s leading column';
COMMENT ON COLUMN java_class_declaration.class_name IS 'the dotted name the declaration spells: the file''s package, then the chain of enclosing class simple names. The join key to jvm_class.class_name, matched by name and by nothing else, the generated jOOQ package being absent there and present here';
COMMENT ON COLUMN java_class_declaration.source_line IS 'line of the declaration, 1-based per the Compiler Tree API''s LineMap; the store holds the parse''s own convention and an editor surface converts to its own. A parse positions every declaration it reads, so -1, that API''s own no-position sentinel, is defensive: it is carried through rather than dropped, because a declaration whose position is missing still has a doc comment worth holding';
COMMENT ON COLUMN java_class_declaration.source_column IS 'column of the declaration, 1-based on the same terms as source_line';
COMMENT ON COLUMN java_class_declaration.javadoc IS 'the declaration''s doc comment as the parse retained it, stripped; NULL where the declaration carries none, absence being a fact rather than an empty string. Display material, never a dimension';

CREATE TABLE java_method_declaration (
  file            VARCHAR NOT NULL,
  class_name      VARCHAR NOT NULL,
  method_name     VARCHAR NOT NULL,
  ordinal         INT     NOT NULL,
  parameter_count INT     NOT NULL,
  source_line     INT     NOT NULL,
  source_column   INT     NOT NULL,
  javadoc         VARCHAR,
  PRIMARY KEY (file, class_name, method_name, ordinal),
  FOREIGN KEY (file, class_name) REFERENCES java_class_declaration (file, class_name)
);
COMMENT ON TABLE java_method_declaration IS 'A method declaration on a declared class: one row per declaration, not one per resolvable name. Overloads are separate rows, so a consumer asking for a name gets as many rows as the class declares and the count is the resolution outcome; that is what replaces an index which dropped colliding keys into a side set of ambiguous ones and kept a first-declaration-wins view beside it. A constructor is a declaration and earns a row under the parse''s own name for it, where jvm_method excludes constructors: the two populations are not required to agree, and this is one of the places they do not.';
COMMENT ON COLUMN java_method_declaration.file IS 'the source file the declaration is written in; the family''s partition dimension and the key''s leading column';
COMMENT ON COLUMN java_method_declaration.class_name IS 'the declaring class, as java_class_declaration spells it';
COMMENT ON COLUMN java_method_declaration.method_name IS 'the declared method name; not a key on its own, overloads sharing it';
COMMENT ON COLUMN java_method_declaration.ordinal IS 'declaration order within one file, class and method name, 0-based: the overload discriminator that keeps this key natural where the classfile side uses a descriptor. It follows the parse''s source order, so it is stable across re-parses of unchanged text and says nothing about which overload a call would bind to';
COMMENT ON COLUMN java_method_declaration.parameter_count IS 'the declared arity, which is what an unattributed parse can know: parameter types resolve to unqualified names as written rather than to the erased types jvm_method_parameter carries, so arity is the part that is a fact and the types are deliberately absent';
COMMENT ON COLUMN java_method_declaration.source_line IS 'line of the declaration, 1-based per the Compiler Tree API''s LineMap; the store holds the parse''s own convention and an editor surface converts to its own. A parse positions every declaration it reads, so -1, that API''s own no-position sentinel, is defensive: it is carried through rather than dropped, because a declaration whose position is missing still has a doc comment worth holding';
COMMENT ON COLUMN java_method_declaration.source_column IS 'column of the declaration, 1-based on the same terms as source_line';
COMMENT ON COLUMN java_method_declaration.javadoc IS 'the declaration''s doc comment as the parse retained it, stripped; NULL where the declaration carries none. Display material, never a dimension';

CREATE TABLE java_field_declaration (
  file          VARCHAR NOT NULL,
  class_name    VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_line   INT     NOT NULL,
  source_column INT     NOT NULL,
  javadoc       VARCHAR,
  PRIMARY KEY (file, class_name, field_name),
  FOREIGN KEY (file, class_name) REFERENCES java_class_declaration (file, class_name)
);
COMMENT ON TABLE java_field_declaration IS 'A field declaration on a declared class: the position and doc comment of a variable whose immediate encloser is a class, so parameters and locals are absent. Enum constants are fields at this grain, and so are the generated jOOQ table classes'' column constants, which is what makes a column''s declaration reachable here at all. A field name is unique within a class, so no ordinal is needed beside it.';
COMMENT ON COLUMN java_field_declaration.file IS 'the source file the declaration is written in; the family''s partition dimension and the key''s leading column';
COMMENT ON COLUMN java_field_declaration.class_name IS 'the declaring class, as java_class_declaration spells it';
COMMENT ON COLUMN java_field_declaration.field_name IS 'the declared field name as written; the Java name, which is what joins to a generated table class''s column constant, never the SQL column name';
COMMENT ON COLUMN java_field_declaration.source_line IS 'line of the declaration, 1-based per the Compiler Tree API''s LineMap; the store holds the parse''s own convention and an editor surface converts to its own. A parse positions every declaration it reads, so -1, that API''s own no-position sentinel, is defensive: it is carried through rather than dropped, because a declaration whose position is missing still has a doc comment worth holding';
COMMENT ON COLUMN java_field_declaration.source_column IS 'column of the declaration, 1-based on the same terms as source_line';
COMMENT ON COLUMN java_field_declaration.javadoc IS 'the declaration''s doc comment as the parse retained it, stripped; NULL where the declaration carries none. Display material, never a dimension';

-- ==== Compile oracle facts ========================================================
-- What the JDK compiler reported about a compile round over the emitted sources, in
-- javax.tools.Diagnostic's vocabulary. The family's writer runs after capture, on the dev
-- loop's compile cadence: in the batch pipeline javac runs in the consumer's own build after
-- the generator exits, so only a dev session ever writes here and a batch run's partition
-- stays empty rather than claiming anything it cannot know. One boundary is this family's own
-- and one-sided: an oracle's transcription is never derived, and a detection over store rows
-- must never acquire an oracle's family, or transcription and derivation blur.
CREATE TABLE javac_diagnostic (
  graph_name    VARCHAR NOT NULL,
  file          VARCHAR NOT NULL,
  line_number   BIGINT  NOT NULL,
  column_number BIGINT  NOT NULL,
  ordinal       INT     NOT NULL,
  kind          VARCHAR NOT NULL,
  code          VARCHAR,
  message       VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, file, line_number, column_number, ordinal),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
COMMENT ON TABLE javac_diagnostic IS 'One javac diagnostic from the latest compile round over a graph''s emitted sources; the round replaces the graph''s rows wholesale, so the relation''s content contract is exactly the published round. Graph-keyed and graph-private: a sibling graph''s compile errors are its internals, not its schema contract, so cross-graph reads never range over this family, and rows exist only between one of the graph''s compile rounds and its next generation (capture clears the graph''s own partition with the rest of its ownership scope). Two key columns transcribe absence as javac''s own sentinels rather than NULL, a primary-key column admitting no NULL: readers compare against the sentinel values, never IS NULL.';
COMMENT ON COLUMN javac_diagnostic.graph_name IS 'the graph whose emitted sources the round compiled; the partition dimension, anchored by store_graph and the scope of every statement the writer issues';
COMMENT ON COLUMN javac_diagnostic.file IS 'canonical file URI of the generated .java javac anchored the diagnostic on (normalised once at the javac boundary so the dimension cannot fork on spelling), or the "(no source)" sentinel where javac reported no source; never NULL, this column being part of the key';
COMMENT ON COLUMN javac_diagnostic.line_number IS 'javac''s 1-based line, or -1 (javax.tools.Diagnostic.NOPOS) where javac reported no position; a sentinel, never NULL, this column being part of the key';
COMMENT ON COLUMN javac_diagnostic.column_number IS 'javac''s 1-based column, on the same NOPOS sentinel terms as line_number';
COMMENT ON COLUMN javac_diagnostic.ordinal IS 'tie-breaker assigned in round order per (graph_name, file, line_number, column_number), so repeated identical diagnostics at one position keep the key natural rather than decoration on a surrogate counter';
COMMENT ON COLUMN javac_diagnostic.kind IS 'javax.tools.Diagnostic.Kind.name(). An open column: a CHECK enumerating an externally owned taxonomy would be a hand-maintained copy of javac''s enum, the closed-CHECK convention covering only taxonomies the model owns';
COMMENT ON COLUMN javac_diagnostic.code IS 'Diagnostic.getCode(), the compiler''s stable diagnostic identifier; NULL exactly where javac returns none, and the typed dimension a display list never had';
COMMENT ON COLUMN javac_diagnostic.message IS 'javac''s own rendered text (root locale); a transcribed fact because the oracle authored it, but display material: never a dimension, never an agreement anchor';

-- ==== Walk reach facts ============================================================
-- What the legacy classification walk registered: the walked model's type and field
-- registries as membership rows, one relation per grain in the claim views' own mould so
-- neither relation carries a column that is NULL by kind. The writer is the capture-and-detect
-- pass, at capture cadence, inside the capture's graph-scoped ownership; a run without the
-- detection pass writes no rows, and the warm refresh clears the graph's partition with the
-- rest of its ownership scope. No foreign key into the graphql_ family on purpose: the writer
-- stands on the walked model, not on captured rows, and the walk's registries legitimately
-- hold coordinates capture spells differently (tombstones included). The rows exist so the
-- conflict detection's domain gate is a join instead of a Java membership test: the walk's
-- reach is narrower than capture's (capture is total, with no reachability pruning), and the
-- exemption populations the demand exemption census recorded never reached a legacy detector,
-- so an ungated detection would move the accept line exactly there. The gate dissolves when
-- the detection reads the resolved demand relation instead of the walk's reach (the gate-flip
-- follow-up's work), which drains these relations; the family retires with the walk whose
-- reach it transcribes.
CREATE TABLE walk_claim_domain_type (
  graph_name VARCHAR NOT NULL,
  type_name  VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
COMMENT ON TABLE walk_claim_domain_type IS 'The type grain of the walk''s claim domain: every type name the classification walk registered, tombstones included. The type-grain conflict detection mints only where a row here says the walk visited; see the family header for the writer, the cadence, and the removal criterion.';
COMMENT ON COLUMN walk_claim_domain_type.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN walk_claim_domain_type.type_name IS 'a type name the walk''s type registry holds; membership is the row''s entire assertion';

CREATE TABLE walk_claim_domain_field (
  graph_name VARCHAR NOT NULL,
  type_name  VARCHAR NOT NULL,
  field_name VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, field_name),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
COMMENT ON TABLE walk_claim_domain_field IS 'The field grain of the walk''s claim domain: every field coordinate the classification walk registered, tombstones included. Deliberately not derived from walk_claim_domain_type: the two registries are independent membership sets (a hand-assembled domain may hold a coordinate whose type the type registry never saw), so the field rows carry their own type_name with no FK into the type grain. See the family header for the writer, the cadence, and the removal criterion.';
COMMENT ON COLUMN walk_claim_domain_field.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN walk_claim_domain_field.type_name IS 'the registered coordinate''s owning type';
COMMENT ON COLUMN walk_claim_domain_field.field_name IS 'the registered coordinate''s field name within the owning type';

-- ==== Derived stratum: claims =====================================================
-- The intent_ family. A claim row says something claims a coordinate for a classification
-- kind: the author's directives (the authored views, one per grain) or the schema's structure
-- joined with catalog facts (the classifier views, one per structural classifier so each
-- carries exactly its own witness columns and none goes nullable by kind). The conflict
-- detection is one grouping query per grain over the authored views (more than one distinct
-- classifier at a coordinate violates), and the per-arm position masks transcribe the
-- classification walk's per-position detector gates so the detection reproduces exactly the
-- sets those detectors saw. intent_resolved_field_claim is the stratum's second layer: the
-- reduction over the claim views, authored winning coordinate-wise. Views, not tables: the
-- rows derive on read from the transcription strata above, so capture writes nothing here and
-- a claim can never drift stale against the applications it is derived from. Underneath the
-- classifier views sit the resolutions they stand on, residents in their own right rather than
-- CTEs inside their first reader: intent_bound_table answers which catalog table a type's @table
-- binds to, which the column-match classifier asks on its way to a claim and an editor asks with
-- no claim in view. Those resolutions layer among themselves on the same rule. intent_spelled_table
-- answers the rule every table name is subject to whatever site wrote it, so the binding view is a
-- keying over it rather than a second copy of it; intent_field_reference_step_hop and
-- intent_field_reference_step_target then split a @reference path into its local element
-- resolutions and the chain that walks them, because only the chain needs recursion and mixing the
-- two would put a copy of every element arm inside the recursive term.
--
-- Each claiming relation contributes a decoded arm, and each claiming directive additionally
-- contributes an undecoded presence arm: the site-family application rows anti-joined against
-- the decoded relation at the coordinate grain, decoded FALSE. The legacy detectors were
-- AST-presence-based while the semantic relations are decode-based, and a decode that declines
-- (a @routine missing its name, a @mutation missing typeName) writes no semantic row; the
-- presence arms keep the claim view presence-faithful exactly there. @lookupKey gets no
-- presence arm: it is an argument-less marker whose decode is total.
--
-- The stratum's second resident group is the demand side: which coordinates require a
-- classification verdict at all, and why the rest are skipped. intent_type_domain is the
-- traversal surface those rules quantify over; the rule views state each demand and exemption
-- rule at the grain it is authored at (every rule shipped so far is a property of the parent
-- type, so the rules are type-keyed and the field grain is a join, legible as a projection);
-- the resolved views are the per-grain reductions that answer with one verdict per coordinate.
-- The rules state the intended model, not the walk's incidental holes: a coordinate the walk
-- silently loses (a DELETE carrier's data field, a renamed root's fields) is demanded here,
-- and the shadow agreement pins the difference as a named population instead of transcribing
-- the defect. Nothing gates on these rows yet; the anti-join of demand against the resolved
-- claim view becoming a build rule is follow-up work.
--
-- The stratum's third resident group is the input occurrence surface: the path relation and
-- its step child at the DDL tail enumerate every occurrence of the input surface under a use
-- site (an occurrence path is its own identity, so this lands ahead of any input-member
-- coordinate work), and intent_input_occurrence_override states the condition cascade's
-- enclosing-override fact as a predicate over path prefixes with its witness kept. The
-- classification walk still evaluates that fact as a boolean threaded through its recursion
-- (capture runs after classification, so the walk cannot read these rows); the shadow
-- agreement binds the two evaluations, and the walk-side re-derivation retires when capture
-- moves ahead of classification.
--
-- A resolution is keyed by whatever its own question is about, which is why not every resident
-- leads with graph_name. intent_class_member_slot asks what member names a class offers, a rule
-- over the classpath census with no graph in it, so it carries the census's key and a graph
-- reaches it through store_graph_source like any other source-keyed fact. Keying it by graph
-- would have made one copy of the answer per graph that reads the class, which is a claim about
-- the graph the rule never makes. The derived stratum is chosen by what produces a row, a rule
-- rather than a transcription, and never by which key the row happens to carry.

CREATE VIEW intent_authored_field_claim
  (graph_name, type_name, field_name, classifier, trigger, decoded,
   source_name, source_line, source_column) AS
WITH RECURSIVE lookup_bearing(graph_name, type_name, path) AS (
  SELECT DISTINCT graph_name, type_name, '/' || type_name || '/'
    FROM graphitron_field_lookup_key
  UNION ALL
  SELECT f.graph_name, f.type_name, b.path || f.type_name || '/'
    FROM graphql_field f
    JOIN graphql_type pt
      ON pt.graph_name = f.graph_name AND pt.type_name = f.type_name AND pt.kind = 'INPUT_OBJECT'
    JOIN lookup_bearing b
      ON b.graph_name = f.graph_name AND b.type_name = f.named_type
   WHERE POSITION('/' || f.type_name || '/' IN b.path) = 0
)
SELECT s.graph_name, s.type_name, s.field_name, 'SERVICE', 'service', TRUE,
       s.source_name, s.source_line, s.source_column
  FROM graphitron_service s
UNION ALL
SELECT d.graph_name, d.type_name, d.field_name, 'SERVICE', 'service', FALSE,
       d.source_name, d.source_line, d.source_column
  FROM graphql_field_directive d
 WHERE d.directive_name = 'service'
   AND NOT EXISTS (SELECT 1 FROM graphitron_service s
                    WHERE s.graph_name = d.graph_name AND s.type_name = d.type_name
                      AND s.field_name = d.field_name)
UNION ALL
SELECT e.graph_name, e.type_name, e.field_name, 'EXTERNAL_FIELD', 'externalField', TRUE,
       e.source_name, e.source_line, e.source_column
  FROM graphitron_external_field e
 WHERE e.type_name NOT IN ('Query', 'Mutation', 'Subscription')
UNION ALL
SELECT d.graph_name, d.type_name, d.field_name, 'EXTERNAL_FIELD', 'externalField', FALSE,
       d.source_name, d.source_line, d.source_column
  FROM graphql_field_directive d
 WHERE d.directive_name = 'externalField'
   AND d.type_name NOT IN ('Query', 'Mutation', 'Subscription')
   AND NOT EXISTS (SELECT 1 FROM graphitron_external_field e
                    WHERE e.graph_name = d.graph_name AND e.type_name = d.type_name
                      AND e.field_name = d.field_name)
UNION ALL
SELECT n.graph_name, n.type_name, n.field_name, 'NODE_ID', 'nodeId', TRUE,
       n.source_name, n.source_line, n.source_column
  FROM graphitron_field_node_id n
 WHERE n.type_name NOT IN ('Query', 'Mutation', 'Subscription')
UNION ALL
SELECT d.graph_name, d.type_name, d.field_name, 'NODE_ID', 'nodeId', FALSE,
       d.source_name, d.source_line, d.source_column
  FROM graphql_field_directive d
 WHERE d.directive_name = 'nodeId'
   AND d.type_name NOT IN ('Query', 'Mutation', 'Subscription')
   AND NOT EXISTS (SELECT 1 FROM graphitron_field_node_id n
                    WHERE n.graph_name = d.graph_name AND n.type_name = d.type_name
                      AND n.field_name = d.field_name)
UNION ALL
SELECT f.graph_name, f.type_name, f.field_name, 'LOOKUP_KEY', 'lookupKey', TRUE,
       direct.source_name, direct.source_line, direct.source_column
  FROM graphql_field f
  LEFT JOIN (SELECT k.graph_name, k.type_name, k.field_name,
                    k.source_name, k.source_line, k.source_column,
                    ROW_NUMBER() OVER (PARTITION BY k.graph_name, k.type_name, k.field_name
                                       ORDER BY a.ordinal) AS rn
               FROM graphitron_argument_lookup_key k
               JOIN graphql_argument a
                 ON a.graph_name = k.graph_name AND a.type_name = k.type_name
                AND a.field_name = k.field_name AND a.argument_name = k.argument_name) direct
    ON direct.graph_name = f.graph_name AND direct.type_name = f.type_name
   AND direct.field_name = f.field_name AND direct.rn = 1
 WHERE f.type_name = 'Query'
   AND (direct.graph_name IS NOT NULL
        OR EXISTS (SELECT 1 FROM graphql_argument a
                    WHERE a.graph_name = f.graph_name AND a.type_name = f.type_name
                      AND a.field_name = f.field_name
                      AND EXISTS (SELECT 1 FROM lookup_bearing b
                                   WHERE b.graph_name = a.graph_name
                                     AND b.type_name = a.named_type)))
UNION ALL
SELECT picked.graph_name, picked.type_name, picked.field_name, 'ROUTINE', 'routine', TRUE,
       picked.source_name, picked.source_line, picked.source_column
  FROM (SELECT r.graph_name, r.type_name, r.field_name,
               r.source_name, r.source_line, r.source_column,
               ROW_NUMBER() OVER (PARTITION BY r.graph_name, r.type_name, r.field_name
                                  ORDER BY r.ordinal) AS rn
          FROM graphitron_routine r) picked
 WHERE picked.rn = 1 AND picked.type_name NOT IN ('Mutation', 'Subscription')
UNION ALL
SELECT picked.graph_name, picked.type_name, picked.field_name, 'ROUTINE', 'routine', FALSE,
       picked.source_name, picked.source_line, picked.source_column
  FROM (SELECT d.graph_name, d.type_name, d.field_name,
               d.source_name, d.source_line, d.source_column,
               ROW_NUMBER() OVER (PARTITION BY d.graph_name, d.type_name, d.field_name
                                  ORDER BY d.ordinal) AS rn
          FROM graphql_field_directive d
         WHERE d.directive_name = 'routine'
           AND NOT EXISTS (SELECT 1 FROM graphitron_routine r
                            WHERE r.graph_name = d.graph_name AND r.type_name = d.type_name
                              AND r.field_name = d.field_name)) picked
 WHERE picked.rn = 1 AND picked.type_name NOT IN ('Mutation', 'Subscription')
UNION ALL
SELECT m.graph_name, m.type_name, m.field_name, 'MUTATION', 'mutation', TRUE,
       m.source_name, m.source_line, m.source_column
  FROM graphitron_mutation m
 WHERE m.type_name = 'Mutation'
UNION ALL
SELECT d.graph_name, d.type_name, d.field_name, 'MUTATION', 'mutation', FALSE,
       d.source_name, d.source_line, d.source_column
  FROM graphql_field_directive d
 WHERE d.directive_name = 'mutation'
   AND d.type_name = 'Mutation'
   AND NOT EXISTS (SELECT 1 FROM graphitron_mutation m
                    WHERE m.graph_name = d.graph_name AND m.type_name = d.type_name
                      AND m.field_name = d.field_name);
COMMENT ON VIEW intent_authored_field_claim IS 'The author''s field-grain classification claims. One arm pair per claiming directive (@service, @externalField, @nodeId, @lookupKey, @routine, @mutation): the decoded arm reads the semantic relation, the presence arm falls back to the raw application where the decode declined. The per-arm type_name masks transcribe the walk''s per-position detector gates: @service claims at every position, @externalField and @nodeId nowhere on a root, @routine not on Mutation or Subscription (a Mutation @routine is the walk''s own typed deferral, never a conflict slot), @lookupKey only on Query, @mutation only on Mutation. The @lookupKey arm fires on the whole argument surface, matching LookupFacts.triggersFor: a directly marked argument, or an argument whose named type is in the transitive lookup-bearing input closure (the recursive path-guarded closure above, seeded from the retired input-field site, so on accepted schemas the recursion never expands). The @routine arms collapse the repeatable ordinal grain to the minimum-ordinal application''s row.';
COMMENT ON COLUMN intent_authored_field_claim.graph_name IS 'the owning graph''s partition, carried through from every arm''s base relation';
COMMENT ON COLUMN intent_authored_field_claim.type_name IS 'the claimed field''s owning type';
COMMENT ON COLUMN intent_authored_field_claim.field_name IS 'the claimed field''s name within the owning type';
COMMENT ON COLUMN intent_authored_field_claim.classifier IS 'the classification kind the claim is for; a closed vocabulary the reading side decodes into a typed value (SERVICE, EXTERNAL_FIELD, NODE_ID, LOOKUP_KEY, ROUTINE, MUTATION), separate from the trigger because a derived claim may have no directive at all';
COMMENT ON COLUMN intent_authored_field_claim.trigger IS 'the claiming directive''s name, without the leading @; what a conflict message names';
COMMENT ON COLUMN intent_authored_field_claim.decoded IS 'TRUE from a semantic-relation arm; FALSE from a presence arm, meaning the application exists but its decode declined';
COMMENT ON COLUMN intent_authored_field_claim.source_name IS 'the claiming application''s own position; NULL on a closure-triggered @lookupKey claim, whose application sits on a remote input field';
COMMENT ON COLUMN intent_authored_field_claim.source_line IS 'source line of the claiming application, 1-based';
COMMENT ON COLUMN intent_authored_field_claim.source_column IS 'source column of the claiming application, 1-based';

CREATE VIEW intent_authored_type_claim
  (graph_name, type_name, classifier, trigger, decoded,
   source_name, source_line, source_column) AS
SELECT t.graph_name, t.type_name, 'TABLE', 'table', TRUE,
       t.source_name, t.source_line, t.source_column
  FROM graphitron_table t
 WHERE t.type_name NOT IN ('Query', 'Mutation', 'Subscription')
UNION ALL
SELECT picked.graph_name, picked.type_name, 'TABLE', 'table', FALSE,
       picked.source_name, picked.source_line, picked.source_column
  FROM (SELECT d.graph_name, d.type_name, d.source_name, d.source_line, d.source_column,
               ROW_NUMBER() OVER (PARTITION BY d.graph_name, d.type_name
                                  ORDER BY d.ordinal) AS rn
          FROM graphql_type_directive d
         WHERE d.directive_name = 'table'
           AND NOT EXISTS (SELECT 1 FROM graphitron_table t
                            WHERE t.graph_name = d.graph_name AND t.type_name = d.type_name)) picked
 WHERE picked.rn = 1 AND picked.type_name NOT IN ('Query', 'Mutation', 'Subscription')
UNION ALL
SELECT e.graph_name, e.type_name, 'ERROR', 'error', TRUE,
       e.source_name, e.source_line, e.source_column
  FROM graphitron_error e
 WHERE e.type_name NOT IN ('Query', 'Mutation', 'Subscription')
UNION ALL
SELECT picked.graph_name, picked.type_name, 'ERROR', 'error', FALSE,
       picked.source_name, picked.source_line, picked.source_column
  FROM (SELECT d.graph_name, d.type_name, d.source_name, d.source_line, d.source_column,
               ROW_NUMBER() OVER (PARTITION BY d.graph_name, d.type_name
                                  ORDER BY d.ordinal) AS rn
          FROM graphql_type_directive d
         WHERE d.directive_name = 'error'
           AND NOT EXISTS (SELECT 1 FROM graphitron_error e
                            WHERE e.graph_name = d.graph_name AND e.type_name = d.type_name)) picked
 WHERE picked.rn = 1 AND picked.type_name NOT IN ('Query', 'Mutation', 'Subscription');
COMMENT ON VIEW intent_authored_type_claim IS 'The author''s type-grain classification claims: @table and @error, decoded arm plus presence fallback each, with the root names masked out (transcribing the walk''s root short-circuit, which classifies a root before any type directive is read). That a conflict here can only occur on an OBJECT is guaranteed upstream by assembly (@error is declared on OBJECT), the same assembly dependency graphitron_undecoded_argument records; a lone @table claim on an INPUT_OBJECT or INTERFACE is an honest single claim that conflicts with nothing. The applications sit at the type grain even when applied on an extension site; the presence arms collapse a base-plus-extension double application to the minimum-ordinal row.';
COMMENT ON COLUMN intent_authored_type_claim.graph_name IS 'the owning graph''s partition, carried through from every arm''s base relation';
COMMENT ON COLUMN intent_authored_type_claim.type_name IS 'the claimed type';
COMMENT ON COLUMN intent_authored_type_claim.classifier IS 'the classification kind the claim is for; a closed vocabulary the reading side decodes into a typed value (TABLE, ERROR), separate from the trigger because a derived claim may have no directive at all';
COMMENT ON COLUMN intent_authored_type_claim.trigger IS 'the claiming directive''s name, without the leading @; what a conflict message names';
COMMENT ON COLUMN intent_authored_type_claim.decoded IS 'TRUE from a semantic-relation arm; FALSE from a presence arm, meaning the application exists but its decode declined';
COMMENT ON COLUMN intent_authored_type_claim.source_name IS 'the claiming application''s own position file';
COMMENT ON COLUMN intent_authored_type_claim.source_line IS 'source line of the claiming application, 1-based';
COMMENT ON COLUMN intent_authored_type_claim.source_column IS 'source column of the claiming application, 1-based';

CREATE VIEW intent_spelled_table
  (graph_name, spelling, table_source_name, table_schema, table_name, candidates) AS
SELECT graph_name, spelling, table_source_name, table_schema, table_name, candidates
  FROM (SELECT s.graph_name, s.spelling, st.source_name AS table_source_name,
               st.table_schema, st.table_name,
               CAST(COUNT(*) OVER (PARTITION BY s.graph_name, s.spelling) AS INT) AS candidates
          FROM (SELECT graph_name, COALESCE(table_ref, type_name) AS spelling
                  FROM graphitron_table
                 UNION
                SELECT graph_name, table_ref FROM graphitron_field_reference_step
                 WHERE table_ref IS NOT NULL
                 UNION
                SELECT graph_name, table_ref FROM graphitron_argument_reference_step
                 WHERE table_ref IS NOT NULL
                 UNION
                SELECT graph_name, table_ref FROM graphitron_reference_for_step
                 WHERE table_ref IS NOT NULL
                 UNION
                SELECT graph_name, table_ref FROM graphitron_mutation
                 WHERE table_ref IS NOT NULL) s
          JOIN store_graph_source m ON m.graph_name = s.graph_name
          JOIN sql_table st ON st.source_name = m.source_name
           AND CASE WHEN POSITION('.' IN s.spelling) > 0
                THEN UPPER(st.table_schema) = UPPER(SUBSTRING(s.spelling
                       FROM 1 FOR POSITION('.' IN s.spelling) - 1))
                 AND UPPER(st.table_name) = UPPER(SUBSTRING(s.spelling
                       FROM POSITION('.' IN s.spelling) + 1))
                ELSE UPPER(st.table_name) = UPPER(s.spelling)
                END) resolved;
COMMENT ON VIEW intent_spelled_table IS 'How a written table name resolves against the catalog census: one row per candidate table, keyed on the spelling itself rather than on any one site that wrote it. A qualified spelling splits on its first dot and binds both halves, an unqualified one matches its table name case-insensitively, and the catalog side scopes through store_graph_source so a sibling graph''s tables never resolve here. Keyed on the spelling because the rule does not vary by site: @table(name:), a @reference path element''s table, its argument-site and @referenceFor siblings, and @mutation''s delete target all name a table the same way, and a resolution with several askers is a relation rather than a subquery repeated in each of them. The population is therefore every spelling this graph authors anywhere, including graphitron_table''s type-name fallback, which is a spelling by the time resolution sees it. Ambiguity is rows, never a decline: a name two schemas both declare is two rows and candidates says so, leaving the reading to the reader.';
COMMENT ON COLUMN intent_spelled_table.graph_name IS 'the owning graph''s partition, carried from the authoring relation';
COMMENT ON COLUMN intent_spelled_table.spelling IS 'the table name as written at some site in this graph, qualifier included where one was written; the key this resolution answers for';
COMMENT ON COLUMN intent_spelled_table.table_source_name IS 'the resolved table''s catalog partition, the first column of the sql_table key this row names';
COMMENT ON COLUMN intent_spelled_table.table_schema IS 'the resolved table''s SQL schema; what tells two candidates of one spelling apart';
COMMENT ON COLUMN intent_spelled_table.table_name IS 'the resolved table''s SQL name. With the two columns above this is sql_table''s full key';
COMMENT ON COLUMN intent_spelled_table.candidates IS 'how many tables the spelling resolves to, this row being one of them; 1 on an unambiguous spelling';

CREATE VIEW intent_bound_table
  (graph_name, type_name, table_source_name, table_schema, table_name, candidates) AS
SELECT t.graph_name, t.type_name,
       sp.table_source_name, sp.table_schema, sp.table_name, sp.candidates
  FROM graphitron_table t
  JOIN intent_spelled_table sp
    ON sp.graph_name = t.graph_name AND sp.spelling = COALESCE(t.table_ref, t.type_name)
 WHERE t.type_name NOT IN ('Query', 'Mutation', 'Subscription');
COMMENT ON VIEW intent_bound_table IS 'Which catalog table an @table-bearing type is bound to: graphitron_table''s reference resolved through intent_spelled_table, one row per candidate table. The reference is the name argument as written, or the type name where the argument was omitted, which is the derivation graphitron_table.table_ref''s own comment defers; how a spelling then meets the census is the spelling view''s rule, stated once there and not restated here. What this view adds over that one is the keying: a type, not a string, which is what every reader of a binding actually holds. The three root names are masked, transcribing the walk''s root short-circuit that classifies a root before any table binding is read, which is the same mask the authored type claims carry. A base derivation rather than a resolved_ reduction: it stands directly on a transcription pair and nothing reduces over sibling views to produce it. Ambiguity is rows, never a decline: two candidates are two rows and the count says so, so a reader can transcribe the walk''s Ambiguous verdict (require candidates = 1, as the column-match classifier does), offer every candidate (as an editor does, since each is a table the author might mean), or report the ambiguity, without any of them re-spelling the resolution. The reference as written and the application''s position are one join back to graphitron_table, which holds both.';
COMMENT ON COLUMN intent_bound_table.graph_name IS 'the owning graph''s partition, carried from graphitron_table';
COMMENT ON COLUMN intent_bound_table.type_name IS 'the @table-bearing type whose binding this row resolves';
COMMENT ON COLUMN intent_bound_table.table_source_name IS 'the resolved table''s catalog partition, the first column of the sql_table key this row names';
COMMENT ON COLUMN intent_bound_table.table_schema IS 'the resolved table''s SQL schema; what tells two candidates of one name apart';
COMMENT ON COLUMN intent_bound_table.table_name IS 'the resolved table''s SQL name. With the two columns above this is sql_table''s full key; the table''s other facts (its jOOQ name, its generated class, its comment) are one join away, per the referenced-side discipline sql_referential_constraint states';
COMMENT ON COLUMN intent_bound_table.candidates IS 'how many tables the reference resolves to, this row being one of them; 1 on an unambiguous binding. Carried through from the spelling view rather than recounted here, and stated as a column rather than left to each reader''s own count, because whether a binding is ambiguous decides the reading (a claim declines, an editor offers every candidate) and a reader that counted for itself would be re-deriving the resolution''s own arity';

CREATE VIEW intent_field_reference_step_hop
  (graph_name, type_name, field_name, ordinal, position, via, key_matched_by,
   from_source_name, from_schema, from_table,
   to_source_name, to_schema, to_table, constraint_name, fk_on_from) AS
SELECT s.graph_name, s.type_name, s.field_name, s.ordinal, s.position, 'KEY',
       CASE WHEN UPPER(c.constraint_name) = UPPER(
                   CASE WHEN POSITION('.' IN s.key_ref) > 0
                        THEN SUBSTRING(s.key_ref FROM POSITION('.' IN s.key_ref) + 1)
                        ELSE s.key_ref END)
            THEN 'SQL_NAME' ELSE 'JOOQ_NAME' END,
       CASE WHEN o.fk_on_from THEN rc.source_name ELSE rc.referenced_source_name END,
       CASE WHEN o.fk_on_from THEN rc.table_schema ELSE rc.referenced_schema END,
       CASE WHEN o.fk_on_from THEN rc.table_name ELSE rc.referenced_table END,
       CASE WHEN o.fk_on_from THEN rc.referenced_source_name ELSE rc.source_name END,
       CASE WHEN o.fk_on_from THEN rc.referenced_schema ELSE rc.table_schema END,
       CASE WHEN o.fk_on_from THEN rc.referenced_table ELSE rc.table_name END,
       rc.constraint_name, o.fk_on_from
  FROM graphitron_field_reference_step s
  JOIN store_graph_source m ON m.graph_name = s.graph_name
  JOIN sql_constraint c ON c.source_name = m.source_name
   AND CASE WHEN POSITION('.' IN s.key_ref) > 0
        THEN UPPER(c.table_schema) = UPPER(SUBSTRING(s.key_ref
               FROM 1 FOR POSITION('.' IN s.key_ref) - 1))
         AND UPPER(c.constraint_name) = UPPER(SUBSTRING(s.key_ref
               FROM POSITION('.' IN s.key_ref) + 1))
        ELSE UPPER(c.constraint_name) = UPPER(s.key_ref)
          OR (UPPER(c.jooq_name) = UPPER(s.key_ref)
              AND NOT EXISTS (SELECT 1
                                FROM sql_constraint c2
                                JOIN store_graph_source m2
                                  ON m2.source_name = c2.source_name
                               WHERE m2.graph_name = s.graph_name
                                 AND UPPER(c2.constraint_name) = UPPER(s.key_ref)))
        END
  JOIN sql_referential_constraint rc
    ON rc.source_name = c.source_name AND rc.table_schema = c.table_schema
   AND rc.table_name = c.table_name AND rc.constraint_name = c.constraint_name
  JOIN (VALUES (TRUE), (FALSE)) o (fk_on_from) ON 1 = 1
 WHERE s.key_ref IS NOT NULL
   AND (o.fk_on_from OR rc.source_name <> rc.referenced_source_name
        OR rc.table_schema <> rc.referenced_schema OR rc.table_name <> rc.referenced_table)
UNION ALL
SELECT s.graph_name, s.type_name, s.field_name, s.ordinal, s.position, 'TABLE', NULL,
       CASE WHEN o.fk_on_from THEN rc.source_name ELSE rc.referenced_source_name END,
       CASE WHEN o.fk_on_from THEN rc.table_schema ELSE rc.referenced_schema END,
       CASE WHEN o.fk_on_from THEN rc.table_name ELSE rc.referenced_table END,
       sp.table_source_name, sp.table_schema, sp.table_name,
       rc.constraint_name, o.fk_on_from
  FROM graphitron_field_reference_step s
  JOIN intent_spelled_table sp
    ON sp.graph_name = s.graph_name AND sp.spelling = s.table_ref
  JOIN (VALUES (TRUE), (FALSE)) o (fk_on_from) ON 1 = 1
  JOIN sql_referential_constraint rc
    ON CASE WHEN o.fk_on_from
         THEN rc.referenced_source_name = sp.table_source_name
          AND rc.referenced_schema = sp.table_schema
          AND rc.referenced_table = sp.table_name
         ELSE rc.source_name = sp.table_source_name
          AND rc.table_schema = sp.table_schema
          AND rc.table_name = sp.table_name
       END
 WHERE s.table_ref IS NOT NULL AND s.key_ref IS NULL
   AND (o.fk_on_from OR rc.source_name <> rc.referenced_source_name
        OR rc.table_schema <> rc.referenced_schema OR rc.table_name <> rc.referenced_table);
COMMENT ON VIEW intent_field_reference_step_hop IS 'One @reference path element''s local resolution: every table-to-table hop the element could express, before anything decides which table the chain has actually arrived at. Both arms of authored navigation are here. A key element resolves its constraint name the way the generator''s resolver does: a leading schema qualifier splits on the first dot and binds hard, an unqualified name matches the SQL constraint name, and only where no SQL constraint in this graph''s sources answers that name does the generated Keys-class constant become eligible, which is the resolver''s namespace precedence rather than a looser match on either. A table element resolves its spelling through intent_spelled_table and pins the arriving side to it, leaving the foreign key to be discovered. Both arms enumerate the hop in both orientations, because a foreign key is a hop in either direction and which one an element means depends on where the chain stands; a self-referential key is one hop and not two, since both orientations land on the same table and the walk''s cardinality hint chooses join columns rather than a destination. Separate from intent_field_reference_step_target because the local resolution has no recursion in it: keeping the two apart is what lets that view''s recursive term be a single join instead of a copy of these arms.';
COMMENT ON COLUMN intent_field_reference_step_hop.graph_name IS 'the owning graph''s partition, carried from graphitron_field_reference_step';
COMMENT ON COLUMN intent_field_reference_step_hop.type_name IS 'the type owning the field the @reference is applied to';
COMMENT ON COLUMN intent_field_reference_step_hop.field_name IS 'the field the @reference is applied to';
COMMENT ON COLUMN intent_field_reference_step_hop.ordinal IS 'the owning @reference application''s ordinal, since the directive is repeatable';
COMMENT ON COLUMN intent_field_reference_step_hop.position IS 'the element''s 0-based position within its application''s path';
COMMENT ON COLUMN intent_field_reference_step_hop.via IS 'which arm resolved the element: KEY where it named a constraint, TABLE where it named a table. The element''s own written form is one join back to graphitron_field_reference_step; this column is the resolution''s reading of it';
COMMENT ON COLUMN intent_field_reference_step_hop.key_matched_by IS 'for a KEY hop, which namespace answered: SQL_NAME (the SQL constraint name) or JOOQ_NAME (the generated Keys constant). NULL on a TABLE hop, which names no constraint. Makes the resolver''s namespace precedence visible data instead of a hidden pick, as the column-match claim''s own tier column does';
COMMENT ON COLUMN intent_field_reference_step_hop.from_source_name IS 'the departing table''s catalog partition, first column of its sql_table key';
COMMENT ON COLUMN intent_field_reference_step_hop.from_schema IS 'the departing table''s SQL schema';
COMMENT ON COLUMN intent_field_reference_step_hop.from_table IS 'the departing table''s SQL name; a candidate departure, not yet a fact about the chain';
COMMENT ON COLUMN intent_field_reference_step_hop.to_source_name IS 'the arriving table''s catalog partition, first column of its sql_table key';
COMMENT ON COLUMN intent_field_reference_step_hop.to_schema IS 'the arriving table''s SQL schema';
COMMENT ON COLUMN intent_field_reference_step_hop.to_table IS 'the arriving table''s SQL name';
COMMENT ON COLUMN intent_field_reference_step_hop.constraint_name IS 'the foreign key the hop joins on, named or discovered. Its own sql_referential_constraint key is this name under whichever endpoint declares it, which fk_on_from says';
COMMENT ON COLUMN intent_field_reference_step_hop.fk_on_from IS 'TRUE when the departing table declares the foreign key, FALSE when the arriving one does; the hop''s direction, and what completes the constraint''s key from the two endpoint triples';

CREATE VIEW intent_field_reference_step_target
  (graph_name, type_name, field_name, ordinal, position, via, key_matched_by,
   from_source_name, from_schema, from_table,
   to_source_name, to_schema, to_table, constraint_name, fk_on_from,
   targets, candidates) AS
WITH RECURSIVE chain (graph_name, type_name, field_name, ordinal, position, via, key_matched_by,
   from_source_name, from_schema, from_table,
   to_source_name, to_schema, to_table, constraint_name, fk_on_from) AS (
  SELECT h.graph_name, h.type_name, h.field_name, h.ordinal, h.position, h.via, h.key_matched_by,
         h.from_source_name, h.from_schema, h.from_table,
         h.to_source_name, h.to_schema, h.to_table, h.constraint_name, h.fk_on_from
    FROM intent_field_reference_step_hop h
    JOIN intent_bound_table bt
      ON bt.graph_name = h.graph_name AND bt.type_name = h.type_name
     AND bt.table_source_name = h.from_source_name AND bt.table_schema = h.from_schema
     AND bt.table_name = h.from_table
   WHERE h.position = 0
  UNION
  SELECT h.graph_name, h.type_name, h.field_name, h.ordinal, h.position, h.via, h.key_matched_by,
         h.from_source_name, h.from_schema, h.from_table,
         h.to_source_name, h.to_schema, h.to_table, h.constraint_name, h.fk_on_from
    FROM chain p
    JOIN intent_field_reference_step_hop h
      ON h.graph_name = p.graph_name AND h.type_name = p.type_name
     AND h.field_name = p.field_name AND h.ordinal = p.ordinal
     AND h.position = p.position + 1
     AND h.from_source_name = p.to_source_name AND h.from_schema = p.to_schema
     AND h.from_table = p.to_table
)
SELECT graph_name, type_name, field_name, ordinal, position, via, key_matched_by,
       from_source_name, from_schema, from_table,
       to_source_name, to_schema, to_table, constraint_name, fk_on_from,
       CAST(MAX(target_rank) OVER (
         PARTITION BY graph_name, type_name, field_name, ordinal, position) AS INT),
       CAST(COUNT(*) OVER (
         PARTITION BY graph_name, type_name, field_name, ordinal, position) AS INT)
  FROM (SELECT c.*, DENSE_RANK() OVER (
                 PARTITION BY c.graph_name, c.type_name, c.field_name, c.ordinal, c.position
                 ORDER BY c.to_source_name, c.to_schema, c.to_table) AS target_rank
          FROM chain c) ranked;
COMMENT ON VIEW intent_field_reference_step_target IS 'Where each element of a field''s @reference path actually lands: the hop view walked from the enclosing type''s table binding, one element at a time, so a row exists only for an element the chain can be shown to reach. Recursive because the arms are sequential and nothing else about them is: an element''s departure is the previous element''s arrival, and only the first element''s departure is known without walking, being the type''s own binding. Two consequences worth stating, both deliberate. An element that resolves to nothing ends the chain, so a path whose second element is fine but whose first names an unknown key contributes no rows at all rather than a row starting from nowhere; that is the walk''s own behaviour and the reason absence here means "not reached", never "resolves to nothing in particular". And an element carrying neither key nor table is not a hop this view knows: a condition-only element takes its target from the condition method''s Java return type, and an omitted path is foreign-key discovery between a parent and a child type, both resolutions this view does not perform and neither of which should be mistaken for its silence. Terminal-element readers project the maximum position per application; the chain has no separate terminal relation because one would be a reduction over this view with a single reader.';
COMMENT ON COLUMN intent_field_reference_step_target.graph_name IS 'the owning graph''s partition, carried from the hop view';
COMMENT ON COLUMN intent_field_reference_step_target.type_name IS 'the type owning the field the @reference is applied to; also the type whose binding started the chain';
COMMENT ON COLUMN intent_field_reference_step_target.field_name IS 'the field the @reference is applied to';
COMMENT ON COLUMN intent_field_reference_step_target.ordinal IS 'the owning @reference application''s ordinal, since the directive is repeatable';
COMMENT ON COLUMN intent_field_reference_step_target.position IS 'the element''s 0-based position within its application''s path; positions are contiguous from 0 up to wherever the chain stopped';
COMMENT ON COLUMN intent_field_reference_step_target.via IS 'which arm resolved the element, as on the hop view: KEY or TABLE';
COMMENT ON COLUMN intent_field_reference_step_target.key_matched_by IS 'for a KEY element, the namespace that answered; NULL on a TABLE element. As on the hop view';
COMMENT ON COLUMN intent_field_reference_step_target.from_source_name IS 'the departing table''s catalog partition; the type''s bound table at position 0, the previous element''s arrival after that';
COMMENT ON COLUMN intent_field_reference_step_target.from_schema IS 'the departing table''s SQL schema';
COMMENT ON COLUMN intent_field_reference_step_target.from_table IS 'the departing table''s SQL name';
COMMENT ON COLUMN intent_field_reference_step_target.to_source_name IS 'the arriving table''s catalog partition, first column of its sql_table key';
COMMENT ON COLUMN intent_field_reference_step_target.to_schema IS 'the arriving table''s SQL schema';
COMMENT ON COLUMN intent_field_reference_step_target.to_table IS 'the arriving table''s SQL name. At the path''s last position this is the table a @field(name:) on the same field resolves its column against';
COMMENT ON COLUMN intent_field_reference_step_target.constraint_name IS 'the foreign key this element joins on, named or discovered';
COMMENT ON COLUMN intent_field_reference_step_target.fk_on_from IS 'TRUE when the departing table declares the foreign key; the element''s direction';
COMMENT ON COLUMN intent_field_reference_step_target.targets IS 'how many distinct tables this element reaches, this row''s arrival being one of them; 1 where the destination is certain. Separate from candidates because the two arities answer different questions and genuinely differ: a table element with three foreign keys connecting the two tables reaches one table by three routes, so a reader that only needs the destination can trust it while a reader that has to render the join cannot';
COMMENT ON COLUMN intent_field_reference_step_target.candidates IS 'how many rows this element resolved to, counting routes and not just destinations; 1 is the walk''s requirement for an expressible hop, and a larger number is what its own "which foreign key did you mean" rejection counts';

CREATE VIEW intent_column_match_claim
  (graph_name, type_name, field_name, classifier, matched_name, matched_by,
   table_source_name, table_schema, table_name, column_name,
   source_name, source_line, source_column) AS
SELECT graph_name, type_name, field_name, 'TABLE_COLUMN', matched_name, matched_by,
       table_source_name, table_schema, table_name, column_name,
       source_name, source_line, source_column
  FROM (SELECT f.graph_name, f.type_name, f.field_name,
               COALESCE(fb.name_ref, f.field_name) AS matched_name,
               CASE WHEN UPPER(c.jooq_name) = UPPER(COALESCE(fb.name_ref, f.field_name))
                    THEN 'JOOQ_NAME' ELSE 'SQL_NAME' END AS matched_by,
               bt.table_source_name, bt.table_schema, bt.table_name,
               c.column_name,
               f.source_name, f.source_line, f.source_column,
               ROW_NUMBER() OVER (
                 PARTITION BY f.graph_name, f.type_name, f.field_name
                 ORDER BY CASE WHEN UPPER(c.jooq_name)
                                    = UPPER(COALESCE(fb.name_ref, f.field_name))
                               THEN 0 ELSE 1 END, c.ordinal) AS rn
          FROM graphql_field f
          JOIN graphql_type leaf
            ON leaf.graph_name = f.graph_name AND leaf.type_name = f.named_type
           AND leaf.kind IN ('SCALAR', 'ENUM')
          JOIN intent_bound_table bt
            ON bt.graph_name = f.graph_name AND bt.type_name = f.type_name
           AND bt.candidates = 1
          LEFT JOIN graphitron_field_binding fb
            ON fb.graph_name = f.graph_name AND fb.type_name = f.type_name
           AND fb.field_name = f.field_name
          JOIN sql_column c
            ON c.source_name = bt.table_source_name AND c.table_schema = bt.table_schema
           AND c.table_name = bt.table_name
           AND (UPPER(c.jooq_name) = UPPER(COALESCE(fb.name_ref, f.field_name))
                OR UPPER(c.column_name) = UPPER(COALESCE(fb.name_ref, f.field_name)))) matched
 WHERE rn = 1;
COMMENT ON VIEW intent_column_match_claim IS 'The column-match structural classifier: a field whose name resolves against its parent''s bound table claims TABLE_COLUMN, no directive involved. One view per structural classifier, so the row''s columns are exactly this classifier''s join witnesses. The reading transcribes the classification walk''s fall-through arm: the field''s named type has kind SCALAR or ENUM, the parent''s table binding resolves to exactly one candidate (the resolution is intent_bound_table''s, joined here at candidates = 1, which is how this arm transcribes the walk''s Ambiguous verdict; distinguishing that decline from a name not in the catalog at all is a future resolution-stratum detection over graphitron_table, not something this view''s absence encodes), and the effective name matches a column, generated-Java-name tier before SQL-name tier, both case-insensitive, collapsed to the first match in tier-then-ordinal order. The effective name is the @field binding where one decoded, else the field name; the arm needs no undecoded presence fallback because a declined @field decode leaves the COALESCE on the field name, which is the walk''s own fallback. Deliberately mask-light: the only exclusion is the three root names, and it arrives through the binding view rather than being restated here, roots classifying before any table binding is read. No parent-kind gate and no directive knowledge: masking against authored claims is the reduction''s job, and the raw structural reading surviving here is what lets a diagnostic say "would classify as a table column; @service overrides it".';
COMMENT ON COLUMN intent_column_match_claim.graph_name IS 'the owning graph''s partition, carried from graphql_field';
COMMENT ON COLUMN intent_column_match_claim.type_name IS 'the claimed field''s owning type';
COMMENT ON COLUMN intent_column_match_claim.field_name IS 'the claimed field''s name within the owning type';
COMMENT ON COLUMN intent_column_match_claim.classifier IS 'always TABLE_COLUMN; stated as a column so claim views union at the classifier grain';
COMMENT ON COLUMN intent_column_match_claim.matched_name IS 'the effective name the classifier resolved: the @field binding where one decoded, else the field name. The classifier''s own product rather than a projection of either input, which is what earns it a column here';
COMMENT ON COLUMN intent_column_match_claim.matched_by IS 'which tier matched: JOOQ_NAME (the generated Java field name) or SQL_NAME. Makes the two-tier precedence visible data instead of a hidden pick';
COMMENT ON COLUMN intent_column_match_claim.table_source_name IS 'witness: the resolved table''s catalog partition, the first column of the sql_column key this row names';
COMMENT ON COLUMN intent_column_match_claim.table_schema IS 'witness: the resolved table''s SQL schema';
COMMENT ON COLUMN intent_column_match_claim.table_name IS 'witness: the resolved table''s SQL name';
COMMENT ON COLUMN intent_column_match_claim.column_name IS 'witness: the matched column''s SQL name. With the three columns above this is sql_column''s full key; the column''s other facts (its jOOQ name, type, nullability) are one join away, per the referenced-side discipline sql_referential_constraint states';
COMMENT ON COLUMN intent_column_match_claim.source_name IS 'the claimed field''s own declaration file; the position a diagnostic would carry';
COMMENT ON COLUMN intent_column_match_claim.source_line IS 'source line of the field declaration, 1-based';
COMMENT ON COLUMN intent_column_match_claim.source_column IS 'source column of the field declaration, 1-based';

CREATE VIEW intent_resolved_field_claim
  (graph_name, type_name, field_name, classifier, tier) AS
SELECT graph_name, type_name, field_name, classifier, 'AUTHORED'
  FROM intent_authored_field_claim
UNION ALL
SELECT i.graph_name, i.type_name, i.field_name, i.classifier, 'INFERRED'
  FROM intent_column_match_claim i
 WHERE NOT EXISTS (SELECT 1 FROM intent_authored_field_claim a
                    WHERE a.graph_name = i.graph_name AND a.type_name = i.type_name
                      AND a.field_name = i.field_name);
COMMENT ON VIEW intent_resolved_field_claim IS 'The field-grain claim resolution: the authored relation unioned with the inferred rows at coordinates the authored relation does not cover. Masking is this join''s job, never a classifier''s guard, and the anti-join sits at the coordinate grain: any authored claim at a coordinate masks every structural reading there, presence arms included, because a directive whose decode declined still diverted the walk. The projection is the claim key plus tier, no trigger, decoded or witness component, so nothing goes nullable by kind; a reader wanting provenance joins the tier''s own view, and tier is a column precisely so that home is a read rather than a hand-maintained classifier-to-relation mapping. The stratum''s second layer, and what a planning reader eventually joins.';
COMMENT ON COLUMN intent_resolved_field_claim.graph_name IS 'the owning graph''s partition, carried from the claim views';
COMMENT ON COLUMN intent_resolved_field_claim.type_name IS 'the claimed field''s owning type';
COMMENT ON COLUMN intent_resolved_field_claim.field_name IS 'the claimed field''s name within the owning type';
COMMENT ON COLUMN intent_resolved_field_claim.classifier IS 'the classification kind the claim is for, the union of the claim views'' closed vocabularies';
COMMENT ON COLUMN intent_resolved_field_claim.tier IS 'AUTHORED from the authored view, INFERRED from a classifier view surviving the anti-join; names which relation carries this claim''s provenance';

CREATE VIEW intent_authored_claim_conflict
  (graph_name, type_name, field_name, verdict, directives, message,
   source_name, source_line, source_column) AS
SELECT g.graph_name, g.type_name, CAST(NULL AS VARCHAR),
       CASE WHEN g.claim_count = 2 AND g.outside_pair = 0 THEN 'DEFERRED' ELSE 'CONFLICT' END,
       g.directives,
       'Type ''' || g.type_name || ''': '
         || CASE WHEN g.claim_count = 2 AND g.outside_pair = 0
                 THEN '@routine with @lookupKey on a root field classifies but does not emit yet'
                 ELSE '@' || REPLACE(g.declared, ',', ', @') || ' are mutually exclusive' END,
       td.source_name, td.source_line, td.source_column
  FROM (SELECT c.graph_name, c.type_name,
               COUNT(*) AS claim_count,
               SUM(CASE WHEN c.classifier IN ('LOOKUP_KEY', 'ROUTINE') THEN 0 ELSE 1 END) AS outside_pair,
               LISTAGG(c.trigger, ',') WITHIN GROUP (ORDER BY c.trigger) AS directives,
               LISTAGG(c.trigger, ',') WITHIN GROUP (ORDER BY CASE c.classifier
                 WHEN 'SERVICE' THEN 0 WHEN 'EXTERNAL_FIELD' THEN 1 WHEN 'NODE_ID' THEN 2
                 WHEN 'LOOKUP_KEY' THEN 3 WHEN 'ROUTINE' THEN 4 WHEN 'MUTATION' THEN 5
                 WHEN 'TABLE' THEN 6 WHEN 'ERROR' THEN 7 END) AS declared
          FROM (SELECT DISTINCT a.graph_name, a.type_name, a.classifier, a.trigger
                  FROM intent_authored_type_claim a
                  JOIN walk_claim_domain_type d
                    ON d.graph_name = a.graph_name AND d.type_name = a.type_name) c
         GROUP BY c.graph_name, c.type_name
        HAVING COUNT(*) >= 2) g
  LEFT JOIN graphql_type_declaration td
    ON td.graph_name = g.graph_name AND td.type_name = g.type_name AND td.merge_ordinal = 0
UNION ALL
SELECT g.graph_name, g.type_name, g.field_name,
       CASE WHEN g.claim_count = 2 AND g.outside_pair = 0 THEN 'DEFERRED' ELSE 'CONFLICT' END,
       g.directives,
       'Field ''' || g.type_name || '.' || g.field_name || ''': '
         || CASE WHEN g.claim_count = 2 AND g.outside_pair = 0
                 THEN '@routine with @lookupKey on a root field classifies but does not emit yet'
                 ELSE '@' || REPLACE(g.declared, ',', ', @') || ' are mutually exclusive' END,
       gf.source_name, gf.source_line, gf.source_column
  FROM (SELECT c.graph_name, c.type_name, c.field_name,
               COUNT(*) AS claim_count,
               SUM(CASE WHEN c.classifier IN ('LOOKUP_KEY', 'ROUTINE') THEN 0 ELSE 1 END) AS outside_pair,
               LISTAGG(c.trigger, ',') WITHIN GROUP (ORDER BY c.trigger) AS directives,
               LISTAGG(c.trigger, ',') WITHIN GROUP (ORDER BY CASE c.classifier
                 WHEN 'SERVICE' THEN 0 WHEN 'EXTERNAL_FIELD' THEN 1 WHEN 'NODE_ID' THEN 2
                 WHEN 'LOOKUP_KEY' THEN 3 WHEN 'ROUTINE' THEN 4 WHEN 'MUTATION' THEN 5
                 WHEN 'TABLE' THEN 6 WHEN 'ERROR' THEN 7 END) AS declared
          FROM (SELECT DISTINCT a.graph_name, a.type_name, a.field_name, a.classifier, a.trigger
                  FROM intent_authored_field_claim a
                  JOIN walk_claim_domain_field d
                    ON d.graph_name = a.graph_name AND d.type_name = a.type_name
                   AND d.field_name = a.field_name) c
         GROUP BY c.graph_name, c.type_name, c.field_name
        HAVING COUNT(*) >= 2) g
  JOIN graphql_field gf
    ON gf.graph_name = g.graph_name AND gf.type_name = g.type_name
   AND gf.field_name = g.field_name;
COMMENT ON VIEW intent_authored_claim_conflict IS 'The authored-claim conflict rule as a resident of the intent_ stratum: one row per violated coordinate, both grains, the store-native pilot of the diagnostics stratum''s derivation arms. A coordinate violates when two or more distinct classifiers claim it and the walk''s reach relation for the grain holds the coordinate (the domain gate as a join against walk_claim_domain_type / walk_claim_domain_field, never against the demand views: the population is the legacy detection''s, and the demand gate-flip is its own follow-up). Two pieces of Java logic live in this SQL and are pinned by the registered agreement anchor (no.sikt.graphitron.rewrite.derive.AuthoredClaimConflictsTest, whose per-fixture expectations are hand-written messages this view does not produce): the routine-plus-lookup carve-out (exactly that claim pair is the recognised-but-unsupported combination and yields DEFERRED instead of CONFLICT), and the message render''s claim order (its LISTAGG''s CASE restates AuthoredClaim''s declaration order, which is the conflict messages'' fixed naming order; the directives column is the sorted canonical render instead, one grouping spelling for every diagnostics arm). Locations join as the legacy mint did: a field violation carries the field''s own declared position, a type violation the type''s base declaration site at merge ordinal 0.';
COMMENT ON COLUMN intent_authored_claim_conflict.graph_name IS 'the owning graph''s partition, carried from the claim views';
COMMENT ON COLUMN intent_authored_claim_conflict.type_name IS 'the violated coordinate''s owning type (the coordinate itself at the type grain)';
COMMENT ON COLUMN intent_authored_claim_conflict.field_name IS 'the violated coordinate''s field name; NULL exactly on type-grain rows, the two-grain union''s key shape (graphql_directive_site''s member_name precedent)';
COMMENT ON COLUMN intent_authored_claim_conflict.verdict IS 'CONFLICT for mutually exclusive claims, DEFERRED for the recognised routine-plus-lookup pair; a closed two-value vocabulary the reduction''s own output type discriminates';
COMMENT ON COLUMN intent_authored_claim_conflict.directives IS 'the canonical claim render for grouping: the claiming directives'' names without the leading @, comma-joined sorted, the one spelling of a directive set every diagnostics dimension shares so claim order can never split a group; consumers wanting the message''s declaration order re-derive it from AuthoredClaim, the order''s one owner';
COMMENT ON COLUMN intent_authored_claim_conflict.message IS 'the violation''s full report message, coordinate prefix included, byte-identical to what the report carries for this family (the agreement anchor pins the spelling); display only, never a dimension';
COMMENT ON COLUMN intent_authored_claim_conflict.source_name IS 'the violated coordinate''s own declaration file (the field''s position at the field grain, the base declaration''s at the type grain); NULL where the declaration carries no position';
COMMENT ON COLUMN intent_authored_claim_conflict.source_line IS 'source line of the violated coordinate''s declaration, 1-based';
COMMENT ON COLUMN intent_authored_claim_conflict.source_column IS 'source column of the violated coordinate''s declaration, 1-based';

CREATE VIEW intent_field_column_table
  (graph_name, type_name, field_name, disposition, basis,
   table_source_name, table_schema, table_name) AS
WITH path_terminal
  (graph_name, type_name, field_name, table_source_name, table_schema, table_name) AS (
  SELECT tg.graph_name, tg.type_name, tg.field_name,
         tg.to_source_name, tg.to_schema, tg.to_table
    FROM intent_field_reference_step_target tg
    JOIN (SELECT graph_name, type_name, field_name, MIN(ordinal) AS ordinal
            FROM graphitron_field_reference_step
           GROUP BY graph_name, type_name, field_name) first_application
      ON first_application.graph_name = tg.graph_name
     AND first_application.type_name = tg.type_name
     AND first_application.field_name = tg.field_name
     AND first_application.ordinal = tg.ordinal
    JOIN (SELECT graph_name, type_name, field_name, ordinal, MAX(position) AS position
            FROM graphitron_field_reference_step
           GROUP BY graph_name, type_name, field_name, ordinal) last_element
      ON last_element.graph_name = tg.graph_name
     AND last_element.type_name = tg.type_name
     AND last_element.field_name = tg.field_name
     AND last_element.ordinal = tg.ordinal
     AND last_element.position = tg.position
   WHERE tg.targets = 1
)
SELECT graph_name, type_name, field_name, disposition, basis,
       table_source_name, table_schema, table_name
  FROM (SELECT arms.graph_name, arms.type_name, arms.field_name, arms.disposition, arms.basis,
               arms.table_source_name, arms.table_schema, arms.table_name,
               ROW_NUMBER() OVER (
                 PARTITION BY arms.graph_name, arms.type_name, arms.field_name
                 ORDER BY arms.precedence) AS rn
          FROM (SELECT c.graph_name, c.type_name, c.field_name,
                       'SILENT' AS disposition, 'CONFLICTED' AS basis,
                       CAST(NULL AS VARCHAR) AS table_source_name,
                       CAST(NULL AS VARCHAR) AS table_schema,
                       CAST(NULL AS VARCHAR) AS table_name,
                       0 AS precedence
                  FROM intent_authored_claim_conflict c
                 WHERE c.field_name IS NOT NULL
                UNION ALL
                SELECT a.graph_name, a.type_name, a.field_name,
                       'SILENT', 'UNRESOLVED_PATH', NULL, NULL, NULL, 1
                  FROM (SELECT DISTINCT graph_name, type_name, field_name
                          FROM graphitron_field_reference_step) a
                 WHERE NOT EXISTS (SELECT 1 FROM path_terminal pt
                                    WHERE pt.graph_name = a.graph_name
                                      AND pt.type_name = a.type_name
                                      AND pt.field_name = a.field_name)
                UNION ALL
                SELECT pt.graph_name, pt.type_name, pt.field_name,
                       'RESOLVE', 'PATH_TERMINAL',
                       pt.table_source_name, pt.table_schema, pt.table_name, 2
                  FROM path_terminal pt
                UNION ALL
                SELECT f.graph_name, f.type_name, f.field_name,
                       'RESOLVE', 'NAMED_TYPE_TABLE',
                       bt.table_source_name, bt.table_schema, bt.table_name, 3
                  FROM graphql_field f
                  LEFT JOIN graphitron_field_synthesis fs
                    ON fs.graph_name = f.graph_name AND fs.type_name = f.type_name
                   AND fs.field_name = f.field_name
                  JOIN graphql_type nt
                    ON nt.graph_name = f.graph_name
                   AND nt.type_name = COALESCE(
                         REPLACE(REPLACE(REPLACE(fs.authored_type_sdl, '[', ''), ']', ''), '!', ''),
                         f.named_type)
                   AND nt.kind = 'OBJECT'
                  JOIN intent_bound_table bt
                    ON bt.graph_name = f.graph_name AND bt.type_name = nt.type_name
                   AND bt.candidates = 1
                 WHERE f.type_name NOT IN ('Query', 'Mutation', 'Subscription')
                   AND NOT EXISTS (SELECT 1 FROM graphitron_field_reference_step s
                                    WHERE s.graph_name = f.graph_name
                                      AND s.type_name = f.type_name
                                      AND s.field_name = f.field_name)
                   AND NOT EXISTS (SELECT 1 FROM intent_authored_field_claim ac
                                    WHERE ac.graph_name = f.graph_name
                                      AND ac.type_name = f.type_name
                                      AND ac.field_name = f.field_name)
                   AND NOT EXISTS (SELECT 1 FROM graphitron_pivot pv
                                    WHERE pv.graph_name = f.graph_name
                                      AND pv.type_name = f.type_name
                                      AND pv.field_name = f.field_name)) arms) picked
 WHERE rn = 1;
COMMENT ON VIEW intent_field_column_table IS 'Which table a column name written at a field''s site resolves against, when that table is not the one the field''s own parent is bound to. The question an editor asks at a @field(name:) or @defaultOrder(fields: [{name:}]) site, and the resolution three LSP arms (completion, hover, the field-member diagnostic) each used to ask a projected per-permit switch. Deliberately narrow: a field whose column names resolve against its parent''s own binding contributes no row, because a reader already holding the parent''s binding needs no relation to tell it so, and stating that case here would make the relation a copy of intent_bound_table keyed one grain down. Absence therefore means "the parent''s own scope answers", which is the reading every consumer already falls back to; only a row overrides it. Two rules produce a table and both are readings of navigation rather than of a directive vocabulary: an authored @reference path resolves to its terminal element''s table, and a field with no path whose named type is itself bound to a table resolves to that table, which is where an ordering column named on a list field lives. Two rules produce silence instead, meaning "no column name resolves here, and the parent''s scope must not stand in": a coordinate whose classification the author has already contested, and an authored path that reaches no single table. The silences are structural, never a reading of the rejection residue: a derivation that asked the residue whether a coordinate had been reported would go quiet the day that family drains, and this relation''s meaning must not depend on where a message currently lives. The path rule reads the first application''s last element, the repeatable directive''s ordinal grain collapsed the way the authored-claim view collapses @routine''s, and it demands the terminal reach exactly one table rather than exactly one row, so an element joining two tables by three keys still names its destination. The named type the rule reads is the one the author wrote, not the one the field currently carries: where a macro rewrote a field''s type expression the authored expression survives on the synthesis relation, and a connection field''s columns are the element type''s rather than the wrapper''s. Taking the named type of that expression is three REPLACEs, list brackets and non-null markers removed, which is what a named type is; reading it there rather than walking the expansion''s own fields means any macro that rewrites a type expression keeps working without this view knowing the shape it expands into. The named-type rule carries the guards that keep it a reading of navigation: a root''s field navigates from no scope of its own, a named type of any kind but OBJECT is an interface, a union or an input and is a different question, an ambiguous binding names no single table, and a field whose classification the author has already claimed does not navigate to its named type at all, that last guard being an anti-join against the authored claims rather than a list of directive names, so it grows as that vocabulary does. @pivot is the one claim the guard names directly, because the claim vocabulary has no arm for it yet and a pivoted field reads its columns from the pivot rather than from the type it names; the explicit guard folds into the anti-join the day that arm lands.';
COMMENT ON COLUMN intent_field_column_table.graph_name IS 'the owning graph''s partition, carried from every arm''s base relation';
COMMENT ON COLUMN intent_field_column_table.type_name IS 'the site''s owning type; the parent whose binding this row overrides';
COMMENT ON COLUMN intent_field_column_table.field_name IS 'the site''s field name within the owning type';
COMMENT ON COLUMN intent_field_column_table.disposition IS 'RESOLVE when the row names a table to resolve column names against, SILENT when it names none and the parent''s scope must not stand in. A closed two-value fork, which is what a consumer switches on; the basis it came from is the next column. Determined by basis rather than independent of it, and carried anyway because the fork is the reading every consumer needs and re-deriving it from a five-value vocabulary at each of them is how the two would drift';
COMMENT ON COLUMN intent_field_column_table.basis IS 'which rule produced this row: PATH_TERMINAL (an authored @reference path''s terminal element), NAMED_TYPE_TABLE (the field''s named type''s own binding), UNRESOLVED_PATH (an authored path reaching no single table), CONFLICTED (the coordinate''s claims are mutually exclusive). A closed vocabulary, and the column that lets a consumer explain its answer and a test pin which rule fired without asserting on the table it happened to reach';
COMMENT ON COLUMN intent_field_column_table.table_source_name IS 'the resolved table''s catalog partition, the first column of the sql_table key this row names; NULL on every SILENT row';
COMMENT ON COLUMN intent_field_column_table.table_schema IS 'the resolved table''s SQL schema; NULL on every SILENT row';
COMMENT ON COLUMN intent_field_column_table.table_name IS 'the resolved table''s SQL name; NULL on every SILENT row. With the two columns above this is sql_table''s full key, so the columns themselves are one join away';

CREATE VIEW intent_field_separate_fetch (graph_name, type_name, field_name, rule) AS
SELECT s.graph_name, s.type_name, s.field_name, 'SPLIT_QUERY'
  FROM graphitron_split_query s
UNION
SELECT t.graph_name, t.type_name, t.field_name, 'TENANT_FAN_OUT'
  FROM graphitron_tenant_fan_out t
UNION
SELECT sv.graph_name, sv.type_name, sv.field_name, 'SERVICE'
  FROM graphitron_service sv
 WHERE NOT EXISTS (SELECT 1 FROM graphql_root_operation r
                    WHERE r.graph_name = sv.graph_name AND r.type_name = sv.type_name)
UNION
SELECT f.graph_name, f.type_name, f.field_name, 'ROOT_OPERATION'
  FROM graphql_field f
  JOIN graphql_root_operation r
    ON r.graph_name = f.graph_name AND r.type_name = f.type_name;
COMMENT ON VIEW intent_field_separate_fetch IS 'Which fields are fetched by a statement of their own rather than projected out of the enclosing SELECT, one rule literal per arm. The question a schema author asks about round-trips: a field with no row here that resolves against its parent''s table costs nothing beyond the parent''s own statement, while a field with one is a second trip to the database. The two marker arms are the delivery-forcing union the table-backed child arm reads (@splitQuery defers the fetch through a DataLoader; @tenantFanOut forces the same boundary because a fanned child runs once per tenant and cannot join into a parent statement running on one source), stated as separate rules rather than one DELIVERY_MARKER because which marker forced the split is what an author reads and the two are written for different reasons. The service arm is the non-root @service contract: the service fetches independently of the parent''s SELECT, which is why the split is required rather than optional there. The root arm is every field of a bound root operation type, whose fetch is the operation''s own entry point and never a projection of anything; keyed by the root operation binding rather than the conventional names, so it states the intended rule the way the demand rules do, today''s walk dispatching on the literal names being the same known difference recorded there. Deliberately absent, and the reason absence is not yet the complement''s claim: the implicit split on a @table-typed field of a class-backed parent, which needs the backing-class resolution the census does not yet carry. Until that arm lands a reader may say a field with a row is separately fetched, and may not say a field without one is inlined.';
COMMENT ON COLUMN intent_field_separate_fetch.graph_name IS 'the owning graph''s partition, carried through from every arm''s base relation';
COMMENT ON COLUMN intent_field_separate_fetch.type_name IS 'the separately fetched field''s owning type';
COMMENT ON COLUMN intent_field_separate_fetch.field_name IS 'the separately fetched field''s name within the owning type';
COMMENT ON COLUMN intent_field_separate_fetch.rule IS 'why the fetch is its own; a closed vocabulary (SPLIT_QUERY, TENANT_FAN_OUT, SERVICE, ROOT_OPERATION) the reading side decodes into a typed value. A coordinate several rules cover is several rows, the arity being the answer rather than a precedence this view picks; each rule''s witnesses live one join away in the arm''s base relation, so no arm''s witness columns go nullable on the others';

CREATE VIEW intent_class_member_slot
  (source_name, class_name, origin, slot_name, display_type, accessor_method_name) AS
SELECT rc.source_name, rc.class_name, 'RECORD_COMPONENT',
       rc.component_name, rc.declared_type, rc.component_name
  FROM jvm_record_component rc
  JOIN jvm_class c
    ON c.source_name = rc.source_name AND c.class_name = rc.class_name
 WHERE c.class_kind = 'RECORD'
UNION ALL
SELECT m.source_name, m.class_name, 'BEAN_ACCESSOR',
       LOWER(SUBSTRING(m.method_name, pfx.prefix_chars + 1, 1))
         || SUBSTRING(m.method_name, pfx.prefix_chars + 2),
       m.declared_return_type, m.method_name
  FROM jvm_method m
  JOIN jvm_class c
    ON c.source_name = m.source_name AND c.class_name = m.class_name
  JOIN (SELECT 'get' AS spelling, 3 AS prefix_chars
        UNION ALL
        SELECT 'is', 2) pfx
    ON LEFT(m.method_name, pfx.prefix_chars) = pfx.spelling
 WHERE c.class_kind <> 'RECORD'
   AND LENGTH(m.method_name) > pfx.prefix_chars
   AND SUBSTRING(m.method_name, pfx.prefix_chars + 1, 1)
         <> LOWER(SUBSTRING(m.method_name, pfx.prefix_chars + 1, 1))
   AND NOT EXISTS (SELECT 1 FROM jvm_method_parameter p
                    WHERE p.source_name = m.source_name
                      AND p.class_name = m.class_name
                      AND p.method_name = m.method_name
                      AND p.descriptor = m.descriptor);
COMMENT ON VIEW intent_class_member_slot IS 'The member names a class offers an SDL author, in the author''s vocabulary rather than the JVM''s: what @field(name:) resolves against on a type whose backing is a class rather than a table. Keyed by the census''s own key, not by a graph: the question is about a class, and a graph reaches it the way it reaches any source-keyed fact, through store_graph_source. A class takes exactly one arm, chosen by its declared form, which is what keeps a slot name unambiguous about where it came from: a record answers with its components, and anything else answers with its bean accessors. The bean rule is the reason this is a relation and not a reader''s loop. It was written in the LSP-facing projection, where it had to be re-run on every build to hand the same list back, and it is a rule over the census rather than a fact about any graph: a public no-argument method whose name is get or is followed by an upper-case letter offers the remainder with its first letter lowered. The two prefixes are joined as data rather than spelled twice, and no arm reads the return type: a method named isTitle returning a String is a slot exactly as the projection made it one, because an author who wrote that name meant that member and a rule that second-guessed the type would hide it. Taking no parameters is read as the absence of parameter rows rather than as a descriptor''s shape, which is the same reading and the one that does not depend on how a descriptor is spelled. Two spellings of one property (getTitle beside isTitle) are two rows, the same two the projection''s list held; a reader wanting one takes the first, and a reader offering candidates offers both. Declaration order is deliberately not carried: the census records a position for a record component and nothing for a method, so an ordered column would be a fact about one arm only, and a reader that wants a stable list orders by name. What this relation does not answer is which class a type is backed by, which is a reflective walk over accessor return types: the census now carries the declared return type beside the erasure, so a list-valued accessor hop names its element type and the hop is followable, and what is still unbuilt is the walk over those hops rather than the fact it would read. Until that lands a reader holds the class name from elsewhere and asks this relation only what the class offers.';
COMMENT ON COLUMN intent_class_member_slot.source_name IS 'the owning class''s classpath entry, carried from jvm_class; the partition a graph reaches through store_graph_source, and the reason one workspace''s modules do not fold their classes into each other''s answers';
COMMENT ON COLUMN intent_class_member_slot.class_name IS 'the fully-qualified binary name of the class offering the slot';
COMMENT ON COLUMN intent_class_member_slot.origin IS 'RECORD_COMPONENT or BEAN_ACCESSOR: which arm produced the row, and the whole of what a consumer needs to say what it found. A function of the class''s declared form rather than of the slot, so every slot of one class carries the same value, and carried per row anyway because the readers that fork on it (a diagnostic naming the member kind, a jump landing on a field rather than a method) hold a slot and not a class kind';
COMMENT ON COLUMN intent_class_member_slot.slot_name IS 'the name an author writes into @field(name:): a record component''s own name, or a bean accessor''s name with its prefix removed and its first letter lowered. Not unique within a class, two accessor spellings of one property being two rows';
COMMENT ON COLUMN intent_class_member_slot.display_type IS 'the member''s type as the source declared it, package-less and with type arguments kept (String, Integer, List<Film>); what a hover shows beside the name. The declared form rather than the erasure because this column exists to be rendered, and an author reading List learns less than one reading List<Film>. The erasure is a join away on the census relation the arm came from, for a reader comparing a type''s identity rather than showing it';
COMMENT ON COLUMN intent_class_member_slot.accessor_method_name IS 'the Java declaration the slot resolves to in source: the accessor method''s own name, which for a record component is the component name. The one column goto-definition reads, and the reason the bean rule''s two directions (a name to a slot, a slot back to its declaration) are stated once here rather than re-derived from slot_name by whoever needs the reverse';

CREATE TABLE intent_type_domain (
  graph_name VARCHAR NOT NULL,
  type_name  VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name),
  FOREIGN KEY (graph_name, type_name) REFERENCES graphql_type (graph_name, type_name)
);
COMMENT ON TABLE intent_type_domain IS 'The classification domain''s type members: every named type, of every kind, the generator''s intended traversal reaches from its seeds. Named for the assertion, not the graph operation, because the seeds are generator policy rather than neutral schema reachability: root operation bindings, @node types, @table types implementing Node (an over-approximation of node inference until the jOOQ node-metadata constants are captured into the classpath family; the shadow agreement asserts the excess is empty), @key carriers, and the argument types of directive definitions that survive into the emitted schema, where the survivor set is bound as a query parameter from the generator''s own directive vocabulary, so this relation''s content is a function of that Java constant and is not self-describing from the DDL alone. Materialized, not a view: the closure over cyclic type graphs has no safe H2 view form (a recursive UNION does not terminate on cycles, and the path-guarded form enumerates simple paths). Written by a capture-cadence derivation writer that clears its own graph partition and re-derives after every flush, per the header''s cadence doctrine, so on any settled store these rows are current for every captured graph.';
COMMENT ON COLUMN intent_type_domain.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN intent_type_domain.type_name IS 'a member of the graph''s classification domain; lands only on captured types, which is what makes the type FK structural';

CREATE VIEW intent_field_demand_rule (graph_name, type_name, rule) AS
SELECT r.graph_name, r.type_name, 'ROOT_OPERATION'
  FROM graphql_root_operation r
  JOIN graphql_type t ON t.graph_name = r.graph_name AND t.type_name = r.type_name
   AND t.kind = 'OBJECT'
UNION
SELECT gt.graph_name, gt.type_name, 'TABLE_TYPE'
  FROM graphitron_table gt
  JOIN graphql_type t ON t.graph_name = gt.graph_name AND t.type_name = gt.type_name
   AND t.kind = 'OBJECT'
 WHERE gt.type_name NOT LIKE '\_%' ESCAPE '\'
UNION
SELECT ge.graph_name, ge.type_name, 'ERROR_TYPE'
  FROM graphitron_error ge
  JOIN graphql_type t ON t.graph_name = ge.graph_name AND t.type_name = ge.type_name
   AND t.kind = 'OBJECT'
 WHERE ge.type_name NOT LIKE '\_%' ESCAPE '\'
UNION
SELECT p.graph_name, p.payload_name, 'PRODUCER_PAYLOAD'
  FROM (SELECT s.graph_name, f.named_type AS payload_name
          FROM graphitron_service s
          JOIN graphql_field f ON f.graph_name = s.graph_name
           AND f.type_name = s.type_name AND f.field_name = s.field_name
         WHERE s.class_name IS NOT NULL AND s.method IS NOT NULL
        UNION
        SELECT e.graph_name, f.named_type
          FROM graphitron_external_field e
          JOIN graphql_field f ON f.graph_name = e.graph_name
           AND f.type_name = e.type_name AND f.field_name = e.field_name
         WHERE e.class_name IS NOT NULL
        UNION
        SELECT m.graph_name, f.named_type
          FROM graphitron_mutation m
          JOIN graphql_field f ON f.graph_name = m.graph_name
           AND f.type_name = m.type_name AND f.field_name = m.field_name) p
  JOIN graphql_type t ON t.graph_name = p.graph_name AND t.type_name = p.payload_name
   AND t.kind = 'OBJECT'
 WHERE p.payload_name NOT LIKE '\_%' ESCAPE '\';
COMMENT ON VIEW intent_field_demand_rule IS 'The types whose fields require a classification verdict, one rule literal per arm. Type-keyed by design: every rule shipped so far is a property of the parent type, so this is the rule''s authored grain and the field grain is a join in the resolved view, legible as a projection rather than materialized into the rule literal. The root arm is keyed by the root operation binding, not the conventional names, so it states the intended rule; today''s walk dispatches on the literal names Query and Mutation, and a renamed root''s fields are a known demanded-but-unregistered population the shadow agreement pins. The producer arm covers the payload types whose producers capture can see (@service and @externalField references that decoded to a class, and every DML @mutation payload); a DELETE payload''s data field is thereby demanded even though today''s walk loses its verdict on every path but the ID-element repayment, which is the other pinned population. The underscore masks transcribe the walk''s short-circuit: an underscore-prefixed type never classifies, whatever it carries, while a root binding is checked before that short-circuit and stays unmasked. Each rule''s witnesses live one join away in the arm''s base relation; this view carries the rule key only, so no arm''s witness columns go nullable on the others.';
COMMENT ON COLUMN intent_field_demand_rule.graph_name IS 'the owning graph''s partition, carried through from every arm''s base relation';
COMMENT ON COLUMN intent_field_demand_rule.type_name IS 'the parent type whose fields the rule demands verdicts for';
COMMENT ON COLUMN intent_field_demand_rule.rule IS 'why the fields are demanded; a closed vocabulary (ROOT_OPERATION, TABLE_TYPE, ERROR_TYPE, PRODUCER_PAYLOAD) the reading side decodes into a typed value';

CREATE VIEW intent_field_exemption_rule (graph_name, type_name, reason) AS
SELECT t.graph_name, t.type_name, 'INTERFACE_TYPE'
  FROM graphql_type t WHERE t.kind = 'INTERFACE'
UNION
SELECT t.graph_name, t.type_name, 'INPUT_TYPE'
  FROM graphql_type t WHERE t.kind = 'INPUT_OBJECT'
UNION
SELECT t.graph_name, t.type_name, 'UNDERSCORE_TYPE'
  FROM graphql_type t
 WHERE t.kind = 'OBJECT' AND t.type_name LIKE '\_%' ESCAPE '\'
UNION
SELECT machinery.graph_name, machinery.type_name, 'CONNECTION_MACHINERY'
  FROM (SELECT ef.graph_name, ef.type_name
          FROM graphql_field ef
          JOIN graphql_type et ON et.graph_name = ef.graph_name
           AND et.type_name = ef.named_type AND et.kind = 'OBJECT'
          JOIN graphql_field nf ON nf.graph_name = ef.graph_name
           AND nf.type_name = ef.named_type AND nf.field_name = 'node'
         WHERE ef.field_name = 'edges'
           AND EXISTS (SELECT 1 FROM graphql_field cf
                        WHERE cf.graph_name = ef.graph_name
                          AND cf.named_type = ef.type_name)
        UNION
        SELECT ef.graph_name, ef.named_type
          FROM graphql_field ef
          JOIN graphql_type et ON et.graph_name = ef.graph_name
           AND et.type_name = ef.named_type AND et.kind = 'OBJECT'
          JOIN graphql_field nf ON nf.graph_name = ef.graph_name
           AND nf.type_name = ef.named_type AND nf.field_name = 'node'
         WHERE ef.field_name = 'edges'
           AND EXISTS (SELECT 1 FROM graphql_field cf
                        WHERE cf.graph_name = ef.graph_name
                          AND cf.named_type = ef.type_name)
        UNION
        SELECT t.graph_name, t.type_name
          FROM graphql_type t
         WHERE t.type_name = 'PageInfo' AND t.kind = 'OBJECT'
           AND (EXISTS (SELECT 1 FROM graphitron_connection c
                         WHERE c.graph_name = t.graph_name)
                OR EXISTS (SELECT 1 FROM graphql_field ef2
                             JOIN graphql_field nf2 ON nf2.graph_name = ef2.graph_name
                              AND nf2.type_name = ef2.named_type AND nf2.field_name = 'node'
                            WHERE ef2.graph_name = t.graph_name
                              AND ef2.field_name = 'edges'))) machinery
UNION
SELECT t.graph_name, t.type_name, 'NESTING_TARGET'
  FROM graphql_type t
 WHERE t.kind = 'OBJECT'
   AND t.type_name NOT LIKE '\_%' ESCAPE '\'
   AND NOT EXISTS (SELECT 1 FROM graphql_root_operation r
                    WHERE r.graph_name = t.graph_name AND r.type_name = t.type_name)
   AND NOT EXISTS (SELECT 1 FROM graphitron_table gt
                    WHERE gt.graph_name = t.graph_name AND gt.type_name = t.type_name)
   AND NOT EXISTS (SELECT 1 FROM graphitron_error ge
                    WHERE ge.graph_name = t.graph_name AND ge.type_name = t.type_name)
   AND NOT EXISTS (SELECT 1 FROM graphitron_service s
                    JOIN graphql_field f ON f.graph_name = s.graph_name
                     AND f.type_name = s.type_name AND f.field_name = s.field_name
                    WHERE s.graph_name = t.graph_name AND f.named_type = t.type_name
                      AND s.class_name IS NOT NULL AND s.method IS NOT NULL)
   AND NOT EXISTS (SELECT 1 FROM graphitron_external_field e
                    JOIN graphql_field f ON f.graph_name = e.graph_name
                     AND f.type_name = e.type_name AND f.field_name = e.field_name
                    WHERE e.graph_name = t.graph_name AND f.named_type = t.type_name
                      AND e.class_name IS NOT NULL)
   AND NOT EXISTS (SELECT 1 FROM graphitron_mutation m
                    JOIN graphql_field f ON f.graph_name = m.graph_name
                     AND f.type_name = m.type_name AND f.field_name = m.field_name
                    WHERE m.graph_name = t.graph_name AND f.named_type = t.type_name);
COMMENT ON VIEW intent_field_exemption_rule IS 'The types whose fields are intentionally not demanded, a reason per arm, type-keyed like the demand rules. Arms are unmasked against each other and against demand, so overlapping readings survive as rows (a structural connection type is also a directiveless object, and both rows are true); one-reason-per-coordinate is the resolved view''s job, per the same masked-reading argument the column-match classifier records. The interface arm is the census''s largest population (no interface''s fields ever classify); the input arm makes the trace-only input coordinates explicit rows; the underscore arm transcribes the walk''s name short-circuit at the field-bearing object kind (interfaces and inputs are already covered by their kind arms); the machinery arm is the structural connection recognition (an object with an edges field whose object element has a node field, reached by some carrier field, plus that shape''s edge type, plus the SDL-declared PageInfo when any promotion would fire), whose fields the connection emitter owns; the nesting-target arm is the walk''s own absence-shaped rule (a plain object with no classifying directive, no root binding and no store-visible producer resolves through its embedding edge, or is an orphan whose rejection surfaces at the referencing field), stated by its own predicate rather than as an anti-join of the demand view, so the two relations state their rules independently and the resolved view owns their meet. Types bound only through the reflection fixed point (accessor chains, record-composite carriers) are deliberately in neither this view nor the demand view; that population is the shadow residue whose store-side closure lands with the binding-walk classifier migration.';
COMMENT ON COLUMN intent_field_exemption_rule.graph_name IS 'the owning graph''s partition, carried through from every arm''s base relation';
COMMENT ON COLUMN intent_field_exemption_rule.type_name IS 'the parent type whose fields the rule exempts';
COMMENT ON COLUMN intent_field_exemption_rule.reason IS 'why the fields are exempt; a closed vocabulary (INTERFACE_TYPE, INPUT_TYPE, UNDERSCORE_TYPE, CONNECTION_MACHINERY, NESTING_TARGET). Named reason, not classifier: classifier is reserved family-wide for classification kinds';

CREATE VIEW intent_type_demand (graph_name, type_name, rule) AS
SELECT r.graph_name, r.type_name, 'ROOT_OPERATION'
  FROM graphql_root_operation r
  JOIN graphql_type t ON t.graph_name = r.graph_name AND t.type_name = r.type_name
   AND t.kind = 'OBJECT'
UNION
SELECT gt.graph_name, gt.type_name, 'TABLE_TYPE'
  FROM graphitron_table gt
  JOIN graphql_type t ON t.graph_name = gt.graph_name AND t.type_name = gt.type_name
   AND t.kind = 'OBJECT'
 WHERE gt.type_name NOT LIKE '\_%' ESCAPE '\'
UNION
SELECT ge.graph_name, ge.type_name, 'ERROR_TYPE'
  FROM graphitron_error ge
  JOIN graphql_type t ON t.graph_name = ge.graph_name AND t.type_name = ge.type_name
   AND t.kind = 'OBJECT'
 WHERE ge.type_name NOT LIKE '\_%' ESCAPE '\'
UNION
SELECT t.graph_name, t.type_name, 'INTERFACE_TYPE'
  FROM graphql_type t
 WHERE t.kind = 'INTERFACE' AND t.type_name NOT LIKE '\_%' ESCAPE '\'
UNION
SELECT t.graph_name, t.type_name, 'UNION_TYPE'
  FROM graphql_type t
 WHERE t.kind = 'UNION' AND t.type_name NOT LIKE '\_%' ESCAPE '\'
UNION
SELECT m.graph_name, m.type_name, 'CONNECTION_MACHINERY'
  FROM intent_field_exemption_rule m
 WHERE m.reason = 'CONNECTION_MACHINERY'
UNION
SELECT d.graph_name, d.type_name, 'PRODUCER_PAYLOAD'
  FROM intent_field_demand_rule d
 WHERE d.rule = 'PRODUCER_PAYLOAD';
COMMENT ON VIEW intent_type_demand IS 'The types that require a type-grain classification verdict. Directly type-grain (no projection involved): the root arm states the intended binding-keyed rule (today''s walk mints RootType for the three literal names, so a renamed root type is a pinned demanded-but-unregistered population, the same hole as its fields); every reachable interface and union classifies at its own visit, so those arms are kind-wide less the underscore short-circuit; connection machinery types are registered by the promotion that recognizes them, so the machinery reading appears here as demand while the same types'' fields are exempt, and the arm reuses the exemption view''s recognition rather than restating it; producer payloads take a carrier or result verdict at their producing edge, reused from the field-rule arm the same way. Directiveless objects with no producer are deliberately absent from both this view and the type exemption: whether such a type ends registered (a nesting target some edge embeds) or absent (an orphan) is decided by the embedding walk and the reflection fixed point, which is the type grain''s shadow residue until those arms migrate. Leaf kinds (scalar, enum, input) are the exemption view''s named deferral rather than absent rows.';
COMMENT ON COLUMN intent_type_demand.graph_name IS 'the owning graph''s partition, carried through from every arm''s base relation';
COMMENT ON COLUMN intent_type_demand.type_name IS 'the type the rule demands a verdict for';
COMMENT ON COLUMN intent_type_demand.rule IS 'why the type is demanded; a closed vocabulary (ROOT_OPERATION, TABLE_TYPE, ERROR_TYPE, INTERFACE_TYPE, UNION_TYPE, CONNECTION_MACHINERY, PRODUCER_PAYLOAD)';

CREATE VIEW intent_type_exemption (graph_name, type_name, reason) AS
SELECT t.graph_name, t.type_name, 'UNDERSCORE_TYPE'
  FROM graphql_type t
 WHERE t.type_name LIKE '\_%' ESCAPE '\'
UNION
SELECT t.graph_name, t.type_name, 'LEAF_KIND_DEFERRED'
  FROM graphql_type t
 WHERE t.kind IN ('SCALAR', 'ENUM', 'INPUT_OBJECT');
COMMENT ON VIEW intent_type_exemption IS 'The types whose type-grain verdict is intentionally not demanded yet. The underscore arm transcribes the walk''s name short-circuit at every kind (a federation-injected _Service or _Entity, and any author-declared underscore type, never classifies). The leaf-kind deferral arm is a bound carried as rows rather than a test-side filter: reachable scalars, enums and input objects do receive verdicts today, but their demand rules belong to those classifiers'' own migration slices, so this arm retires arm-by-arm as they land and the shadow agreement reads the bound as data. Arms are unmasked; the resolved view owns precedence.';
COMMENT ON COLUMN intent_type_exemption.graph_name IS 'the owning graph''s partition, carried from graphql_type';
COMMENT ON COLUMN intent_type_exemption.type_name IS 'the exempted type';
COMMENT ON COLUMN intent_type_exemption.reason IS 'why the type''s verdict is not demanded; a closed vocabulary (UNDERSCORE_TYPE, LEAF_KIND_DEFERRED)';

CREATE VIEW intent_resolved_field_demand (graph_name, type_name, field_name, verdict, rule) AS
SELECT f.graph_name, f.type_name, f.field_name,
       CASE WHEN dm.pr IS NOT NULL THEN 'DEMANDED' ELSE 'EXEMPT' END,
       CASE WHEN dm.pr IS NOT NULL THEN
              CASE dm.pr WHEN 1 THEN 'ROOT_OPERATION' WHEN 2 THEN 'TABLE_TYPE'
                         WHEN 3 THEN 'ERROR_TYPE' ELSE 'PRODUCER_PAYLOAD' END
            ELSE
              CASE ex.pr WHEN 1 THEN 'INTERFACE_TYPE' WHEN 2 THEN 'INPUT_TYPE'
                         WHEN 3 THEN 'UNDERSCORE_TYPE' WHEN 4 THEN 'CONNECTION_MACHINERY'
                         ELSE 'NESTING_TARGET' END
       END
  FROM graphql_field f
  JOIN intent_type_domain dom
    ON dom.graph_name = f.graph_name AND dom.type_name = f.type_name
  JOIN graphql_type t
    ON t.graph_name = f.graph_name AND t.type_name = f.type_name
   AND t.kind IN ('OBJECT', 'INTERFACE', 'INPUT_OBJECT')
  LEFT JOIN (SELECT graph_name, type_name,
                    MIN(CASE rule WHEN 'ROOT_OPERATION' THEN 1 WHEN 'TABLE_TYPE' THEN 2
                                  WHEN 'ERROR_TYPE' THEN 3 ELSE 4 END) AS pr
               FROM intent_field_demand_rule GROUP BY graph_name, type_name) dm
    ON dm.graph_name = f.graph_name AND dm.type_name = f.type_name
  LEFT JOIN (SELECT graph_name, type_name,
                    MIN(CASE reason WHEN 'INTERFACE_TYPE' THEN 1 WHEN 'INPUT_TYPE' THEN 2
                                    WHEN 'UNDERSCORE_TYPE' THEN 3
                                    WHEN 'CONNECTION_MACHINERY' THEN 4 ELSE 5 END) AS pr
               FROM intent_field_exemption_rule GROUP BY graph_name, type_name) ex
    ON ex.graph_name = f.graph_name AND ex.type_name = f.type_name
 WHERE dm.pr IS NOT NULL OR ex.pr IS NOT NULL;
COMMENT ON VIEW intent_resolved_field_demand IS 'The field-grain demand reduction: one verdict per accounted coordinate of the classification domain, over every field-bearing parent kind (input coordinates resolve EXEMPT here rather than falling outside the domain). Demand beats exemption where both relations carry the parent (a @table type shaped like a connection classifies its fields, so the demand reading wins, matching the walk); within each side the rule is the first arm in the vocabularies'' declared order, so the more specific reading names the row (machinery beats the directiveless catch-all). A domain coordinate with neither reading has no row: by construction the nesting-target arm complements the demand arms over plain objects, so absence marks the shadow residue (reflection-bound parents), and the agreement''s coverage gate counts resolved rows against domain coordinates to keep that construction honest. The future demand gate is this view''s DEMANDED rows anti-joined against the resolved claim view; nothing gates on it in shadow.';
COMMENT ON COLUMN intent_resolved_field_demand.graph_name IS 'the owning graph''s partition, carried from the domain';
COMMENT ON COLUMN intent_resolved_field_demand.type_name IS 'the coordinate''s owning type';
COMMENT ON COLUMN intent_resolved_field_demand.field_name IS 'the coordinate''s field name';
COMMENT ON COLUMN intent_resolved_field_demand.verdict IS 'DEMANDED when any demand rule covers the parent, else EXEMPT; a closed two-value vocabulary';
COMMENT ON COLUMN intent_resolved_field_demand.rule IS 'the winning rule or reason literal, drawn from the rule views'' closed vocabularies in their declared precedence order';

CREATE VIEW intent_resolved_type_demand (graph_name, type_name, verdict, rule) AS
SELECT dom.graph_name, dom.type_name,
       CASE WHEN dm.pr IS NOT NULL THEN 'DEMANDED' ELSE 'EXEMPT' END,
       CASE WHEN dm.pr IS NOT NULL THEN
              CASE dm.pr WHEN 1 THEN 'ROOT_OPERATION' WHEN 2 THEN 'TABLE_TYPE'
                         WHEN 3 THEN 'ERROR_TYPE' WHEN 4 THEN 'INTERFACE_TYPE'
                         WHEN 5 THEN 'UNION_TYPE' WHEN 6 THEN 'CONNECTION_MACHINERY'
                         ELSE 'PRODUCER_PAYLOAD' END
            ELSE
              CASE ex.pr WHEN 1 THEN 'UNDERSCORE_TYPE' ELSE 'LEAF_KIND_DEFERRED' END
       END
  FROM intent_type_domain dom
  LEFT JOIN (SELECT graph_name, type_name,
                    MIN(CASE rule WHEN 'ROOT_OPERATION' THEN 1 WHEN 'TABLE_TYPE' THEN 2
                                  WHEN 'ERROR_TYPE' THEN 3 WHEN 'INTERFACE_TYPE' THEN 4
                                  WHEN 'UNION_TYPE' THEN 5 WHEN 'CONNECTION_MACHINERY' THEN 6
                                  ELSE 7 END) AS pr
               FROM intent_type_demand GROUP BY graph_name, type_name) dm
    ON dm.graph_name = dom.graph_name AND dm.type_name = dom.type_name
  LEFT JOIN (SELECT graph_name, type_name,
                    MIN(CASE reason WHEN 'UNDERSCORE_TYPE' THEN 1 ELSE 2 END) AS pr
               FROM intent_type_exemption GROUP BY graph_name, type_name) ex
    ON ex.graph_name = dom.graph_name AND ex.type_name = dom.type_name
 WHERE dm.pr IS NOT NULL OR ex.pr IS NOT NULL;
COMMENT ON VIEW intent_resolved_type_demand IS 'The type-grain demand reduction over the classification domain, mirroring the field-grain reduction: demand beats exemption, first declared arm wins within a side, and a domain member with neither reading has no row, which marks the type grain''s shadow residue (directiveless objects whose registration is decided by the embedding walk and the reflection fixed point). The shadow agreement splits that absent population into its named parts rather than treating it as one structural bucket.';
COMMENT ON COLUMN intent_resolved_type_demand.graph_name IS 'the owning graph''s partition, carried from the domain';
COMMENT ON COLUMN intent_resolved_type_demand.type_name IS 'the domain member the verdict is about';
COMMENT ON COLUMN intent_resolved_type_demand.verdict IS 'DEMANDED when any type demand rule covers the member, else EXEMPT; a closed two-value vocabulary';
COMMENT ON COLUMN intent_resolved_type_demand.rule IS 'the winning rule or reason literal, drawn from the rule views'' closed vocabularies in their declared precedence order';

CREATE TABLE intent_input_occurrence_path (
  graph_name         VARCHAR NOT NULL,
  path               VARCHAR NOT NULL,
  root_type_name     VARCHAR NOT NULL,
  root_field_name    VARCHAR NOT NULL,
  root_argument_name VARCHAR NOT NULL,
  root_input_type    VARCHAR NOT NULL,
  leaf_named_type    VARCHAR NOT NULL,
  depth              INT     NOT NULL,
  PRIMARY KEY (graph_name, path),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name),
  FOREIGN KEY (graph_name, root_type_name, root_field_name, root_argument_name)
    REFERENCES graphql_argument (graph_name, type_name, field_name, argument_name)
);
COMMENT ON TABLE intent_input_occurrence_path IS 'One occurrence of the input surface under a use site: an argument whose named type is an input object, or a nested input field reached from one by descending through input-object-typed fields. The key is the serialized path, <root type>.<root field>(<argument>)[/<input field>...]: an occurrence path is its own identity (no minted coordinate is involved), the relation is re-derived each run so the value key costs nothing, and the step child carries the same data relationally so no consumer parses the key. Every prefix of a path is itself a row. Materialized by a capture-cadence derivation writer for the same reason as intent_type_domain (cyclic input nesting is legal GraphQL and has no safe recursive H2 view form); the expansion stops descending when the leaf type is already visited on the path, which is the classification walk''s own first-visit guard (ClassifyContext.expandingTypes) restated, so the row population equals the recursion tree the build already walks and simple-path enumeration adds no new asymptotic class here.';
COMMENT ON COLUMN intent_input_occurrence_path.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN intent_input_occurrence_path.path IS 'the serialized occurrence path; the value key';
COMMENT ON COLUMN intent_input_occurrence_path.root_type_name IS 'the use site''s owning type';
COMMENT ON COLUMN intent_input_occurrence_path.root_field_name IS 'the use site''s field name within the owning type';
COMMENT ON COLUMN intent_input_occurrence_path.root_argument_name IS 'the argument this occurrence descends from';
COMMENT ON COLUMN intent_input_occurrence_path.root_input_type IS 'the argument''s named input object type, the traversal''s entry type';
COMMENT ON COLUMN intent_input_occurrence_path.leaf_named_type IS 'the named type of the path''s last step (the argument''s own type at depth 0); the expansion descends from here when the type has kind INPUT_OBJECT and is not already visited on the path';
COMMENT ON COLUMN intent_input_occurrence_path.depth IS 'the number of input-field steps below the argument; 0 for the argument occurrence itself, and equal to the highest step ordinal otherwise';

CREATE TABLE intent_input_occurrence_path_step (
  graph_name          VARCHAR NOT NULL,
  path                VARCHAR NOT NULL,
  ordinal             INT     NOT NULL,
  container_type_name VARCHAR NOT NULL,
  field_name          VARCHAR NOT NULL,
  named_type          VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, path, ordinal),
  FOREIGN KEY (graph_name, path) REFERENCES intent_input_occurrence_path (graph_name, path),
  FOREIGN KEY (graph_name, container_type_name, field_name)
    REFERENCES graphql_field (graph_name, type_name, field_name)
);
COMMENT ON TABLE intent_input_occurrence_path_step IS 'The ordinal-keyed decomposition of an occurrence path: one row per input-field step, 1-based, so no consumer parses the serialized key. Homogeneous over input-field steps only: the use-site field and argument are fixed by construction (every path has exactly one of each) and live on the parent row, so no column here is nullable by kind. The row at ordinal = depth is the path''s leaf.';
COMMENT ON COLUMN intent_input_occurrence_path_step.graph_name IS 'the owning graph''s partition, anchored through the parent path; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN intent_input_occurrence_path_step.path IS 'the owning occurrence path';
COMMENT ON COLUMN intent_input_occurrence_path_step.ordinal IS '1-based position of this input-field step below the argument';
COMMENT ON COLUMN intent_input_occurrence_path_step.container_type_name IS 'the input object type this step''s field is declared on';
COMMENT ON COLUMN intent_input_occurrence_path_step.field_name IS 'the input field''s name within its container';
COMMENT ON COLUMN intent_input_occurrence_path_step.named_type IS 'the step''s named type; the type the traversal is in after taking this step';

CREATE VIEW intent_input_occurrence_override
  (graph_name, path, override_type_name, override_field_name, override_argument_name) AS
SELECT graph_name, path, override_type_name, override_field_name, override_argument_name
  FROM (SELECT o.graph_name, o.path, o.override_type_name, o.override_field_name,
               o.override_argument_name,
               ROW_NUMBER() OVER (PARTITION BY o.graph_name, o.path ORDER BY o.nearness DESC) AS rn
          FROM (SELECT p.graph_name, p.path,
                       fc.type_name AS override_type_name, fc.field_name AS override_field_name,
                       CAST(NULL AS VARCHAR) AS override_argument_name, 0 AS nearness
                  FROM intent_input_occurrence_path p
                  JOIN graphitron_field_condition fc
                    ON fc.graph_name = p.graph_name AND fc.type_name = p.root_type_name
                   AND fc.field_name = p.root_field_name AND fc.override = TRUE
                UNION ALL
                SELECT p.graph_name, p.path,
                       ac.type_name, ac.field_name, ac.argument_name, 1
                  FROM intent_input_occurrence_path p
                  JOIN graphitron_argument_condition ac
                    ON ac.graph_name = p.graph_name AND ac.type_name = p.root_type_name
                   AND ac.field_name = p.root_field_name
                   AND ac.argument_name = p.root_argument_name AND ac.override = TRUE
                UNION ALL
                SELECT p.graph_name, p.path,
                       fc.type_name, fc.field_name, CAST(NULL AS VARCHAR), 1 + s.ordinal
                  FROM intent_input_occurrence_path p
                  JOIN intent_input_occurrence_path_step s
                    ON s.graph_name = p.graph_name AND s.path = p.path AND s.ordinal < p.depth
                  JOIN graphitron_field_condition fc
                    ON fc.graph_name = s.graph_name AND fc.type_name = s.container_type_name
                   AND fc.field_name = s.field_name AND fc.override = TRUE) o) w
 WHERE rn = 1;
COMMENT ON VIEW intent_input_occurrence_override IS 'The cascade fact as a predicate over path prefixes: one row per occurrence path with an enclosing @condition(override: true), which in the classification walk is the enclosingOverride boolean threaded through the recursion. A path''s enclosing sites are the use-site field''s own @condition, the argument''s @condition, and the @condition of every step strictly above the leaf (the leaf''s own override is the condition-owned carrier''s fact, not a cascade fact). Absence is the no-override reading, which is what the use-keyed cascade verdict fires on when the leaf is unbound. The witness columns name the nearest enclosing overriding site (deepest step first, then the argument, then the field), the row the admitted-because message and the future fix-it need; a NULL argument name means the witness is a field-site condition row, the witness''s own key shape across the two condition relations.';
COMMENT ON COLUMN intent_input_occurrence_override.graph_name IS 'the owning graph''s partition, carried from the path';
COMMENT ON COLUMN intent_input_occurrence_override.path IS 'the overridden occurrence path';
COMMENT ON COLUMN intent_input_occurrence_override.override_type_name IS 'witness: the overriding @condition site''s owning type (an input object type for a step witness)';
COMMENT ON COLUMN intent_input_occurrence_override.override_field_name IS 'witness: the overriding site''s field name';
COMMENT ON COLUMN intent_input_occurrence_override.override_argument_name IS 'witness: the overriding site''s argument name; NULL when the witness is a field-site condition (graphitron_field_condition''s key shape), non-NULL when it is the argument-site relation''s row';

-- ==== Diagnostics stratum =========================================================
-- Violations as facts: seven arms behind one prefix-less union view (diagnostic, at the
-- section's tail), and nothing reads a base relation of this stratum directly. The arms, and
-- the many-to-one arm-to-source mapping stated so it is reviewed: the store-native pilot
-- (intent_authored_claim_conflict, a derivation with no writer), the rejection_ residue, the
-- lint_ arm, the build_warning_ advisory arm and the two SDL-toolchain arms
-- (graphql_syntax_error, graphql_schema_error) all carry source = 'schema'; only
-- javac_diagnostic carries 'compile'. A new arm grows neither the closed source taxonomy nor
-- the wire vocabulary. The per-vocabulary split keeps severity honest: for rejection rows it
-- is a function of the rejection's kind, for lint rows of the rule, for advisory rows warning
-- by construction, for the SDL-toolchain rows error by construction (a document that will not
-- parse or will not assemble is not a schema), for compile rows javac's independent verdict,
-- and one relation holding any two would give one column two meanings. The loaded relations
-- here write at the dev session's cadence through the session's live store handle (their
-- loaders sit beside the report's producer), every statement graph-scoped, and a batch run's
-- loaded partitions stay empty: honest emptiness in the compile arm's shipped posture, since
-- the only reader is the dev session's MCP server. The two SDL-toolchain arms are the
-- exception to that cadence and write from capture itself, on every pass, which is what makes
-- their emptiness mean "the document was read clean" rather than "nobody has loaded them yet".
-- Absence discipline: schema-side absence is SQL NULL outside the key; the compile arm keeps
-- javac's own sentinels in its base relation and the union view normalises them to the same
-- NULL bucket by comparing against the sentinels, never IS NULL. The SDL-toolchain arms take
-- the schema-side rule instead of the compile arm's, normalising graphql-java's own (-1, -1)
-- unlocated sentinel to NULL at their writer, so one family does not carry two conventions.
-- Location-less rows (whole-build lint findings, coordinate-less rejections) take the
-- javac_diagnostic key convention: an emit-order ordinal under the graph partition, which is
-- also each loaded relation's one key throughout. graphql_syntax_error is the one arm keyed
-- otherwise, on the source it refused, and its own comment argues why.

CREATE TABLE rejection_validation_error (
  graph_name    VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  kind          VARCHAR NOT NULL CHECK (kind IN ('AUTHOR_ERROR', 'INVALID_SCHEMA', 'DEFERRED')),
  variant       VARCHAR NOT NULL,
  lsp_code      VARCHAR,
  attempt_kind  VARCHAR,
  attempt       VARCHAR,
  stub_key      VARCHAR,
  type_name     VARCHAR,
  field_name    VARCHAR,
  message       VARCHAR NOT NULL,
  file          VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (graph_name, ordinal),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
COMMENT ON TABLE rejection_validation_error IS 'The rejection residue: one legacy-walk validation error per row, transcribed in the sealed Rejection hierarchy''s own spellings by the residue loader (the exhaustive-switch site beside the report''s producer), from the walk''s error stream and never the assembled report, so a detection-minted family is structurally absent from this relation''s input. Transitional with a drainage mechanism: a rejection family that acquires its own derivation view arm (intent_authored_claim_conflict is the first) leaves this relation, the drainage declaration in the residue loader''s test enumerates what still routes through, and the relation retires with the sealed hierarchy whose vocabulary it transcribes. Rows replace wholesale per snapshot at the dev session''s cadence; a batch run''s partition stays empty.';
COMMENT ON COLUMN rejection_validation_error.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN rejection_validation_error.ordinal IS 'emit order in the walk''s error stream, 0-based; the key''s tie-breaker, covering coordinate-less and location-less rows on the javac_diagnostic key convention';
COMMENT ON COLUMN rejection_validation_error.kind IS 'RejectionKind.name(): the author-error / invalid-schema / deferred fork; a closed CHECK because the model owns this small projection';
COMMENT ON COLUMN rejection_validation_error.variant IS 'the rejection leaf''s class name with its package stripped, enclosing classes kept (Rejection.AuthorError.UnknownName, UpdateRowsError.NoUniqueKeyCoverage): plain simple names collide across sub-hierarchies and would fuse two families in the dimension that exists to split them. An open column, since a CHECK enumerating sealed-hierarchy leaves would be a hand-maintained second copy of a taxonomy the compiler already enforces';
COMMENT ON COLUMN rejection_validation_error.lsp_code IS 'the stable machine-readable code the leaf declares through its sub-seal''s lspCode(), NULL for the deliberately codeless leaves; the loader''s explicit match over the code-bearing sub-seals fills it, and the membership-binding test pins both readers of the hierarchy to one declaration set';
COMMENT ON COLUMN rejection_validation_error.attempt_kind IS 'AttemptKind.name() on UnknownName rows (which lookup space the name resolution failed in), NULL on every other variant; open for the same reason as variant';
COMMENT ON COLUMN rejection_validation_error.attempt IS 'the name the author wrote, on UnknownName rows; NULL elsewhere';
COMMENT ON COLUMN rejection_validation_error.stub_key IS 'the Deferred row''s stub anchor: the stubbed GraphitronField leaf''s class name with its package stripped (enclosing class kept); NULL on non-deferred rows and on the inline-defer sites whose StubKey names no leaf class, whose rows cluster on message instead';
COMMENT ON COLUMN rejection_validation_error.type_name IS 'the coordinate''s owning type at the DDL''s universal grain; NULL on schema-wide rows. The loader is the one site decoding ValidationError''s coordinate convention (forType / forField) into this pair; when the sealed Coordinate component lands, the loader reads a switch instead and no column changes';
COMMENT ON COLUMN rejection_validation_error.field_name IS 'the coordinate''s field name; NULL on type-level and schema-wide rows, the universal grain''s absent case';
COMMENT ON COLUMN rejection_validation_error.message IS 'the report''s rendered message, coordinate prefix included; a transcribed fact because the walk authored it, but display material: never a dimension, never an agreement anchor, and expected to change text as detections take over rejection families';
COMMENT ON COLUMN rejection_validation_error.file IS 'canonical file URI of the SDL source carrying the location, normalised once at the loader through the report''s canonical-URI site so the file dimension cannot fork on spelling; NULL where the error carries no located source';
COMMENT ON COLUMN rejection_validation_error.source_line IS 'source line of the error''s location, 1-based; NULL where unlocated';
COMMENT ON COLUMN rejection_validation_error.source_column IS 'source column of the error''s location, 1-based; NULL where unlocated';

CREATE TABLE rejection_validation_error_directive (
  graph_name    VARCHAR NOT NULL,
  error_ordinal INT     NOT NULL,
  position      INT     NOT NULL,
  directive     VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, error_ordinal, position),
  FOREIGN KEY (graph_name, error_ordinal) REFERENCES rejection_validation_error (graph_name, ordinal)
);
COMMENT ON TABLE rejection_validation_error_directive IS 'The ordered decode of a DirectiveConflict row''s directives list, one row per named directive in the rejection''s own order (the DDL''s standing pattern for multi-valued decodes). The union view renders the canonical sorted spelling over these rows for the directives dimension; a residue-only mechanism, since the pilot view renders its own aggregate over grouped claim rows.';
COMMENT ON COLUMN rejection_validation_error_directive.graph_name IS 'the owning graph''s partition, carried through the parent error row';
COMMENT ON COLUMN rejection_validation_error_directive.error_ordinal IS 'the parent rejection_validation_error row''s ordinal';
COMMENT ON COLUMN rejection_validation_error_directive.position IS '0-based position in the rejection''s own directives list; order is the minted fact, the sorted render is a projection';
COMMENT ON COLUMN rejection_validation_error_directive.directive IS 'one directive name, without the leading @, exactly as the rejection lists it; every listed directive is applied at the rejection''s own declaration per DirectiveConflict''s contract';

CREATE TABLE lint_finding (
  graph_name    VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  lint_rule     VARCHAR NOT NULL,
  message       VARCHAR NOT NULL,
  file          VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (graph_name, ordinal),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
COMMENT ON TABLE lint_finding IS 'One lint finding per row, in the linter''s own vocabulary. The loader''s input is the suppression-filtered warning list the report is assembled from, never a pre-suppression stream, so rows here are post-suppression survivors exactly as the report and the MCP tool carry them; a loader reading an earlier stream would resurrect disabled findings on the wire. Severity is warning by the rule''s nature and stated only in the union view, since one relation holds one vocabulary. Two shipped producers mint whole-build findings with no SDL coordinate (session-state and dependency-currency advisories), which is what puts this relation on the emit-order key convention.';
COMMENT ON COLUMN lint_finding.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN lint_finding.ordinal IS 'emit order in the suppression-filtered warning list, 0-based; the key''s tie-breaker on the javac_diagnostic convention';
COMMENT ON COLUMN lint_finding.lint_rule IS 'LintRule.id(), the finding''s stable rule identifier; the vocabulary this family is named for';
COMMENT ON COLUMN lint_finding.message IS 'the finding''s rendered message; display material, never a dimension';
COMMENT ON COLUMN lint_finding.file IS 'canonical file URI of the finding''s SDL source, normalised once at the loader; NULL on the whole-build findings that carry no location';
COMMENT ON COLUMN lint_finding.source_line IS 'source line of the finding''s location, 1-based; NULL where unlocated';
COMMENT ON COLUMN lint_finding.source_column IS 'source column of the finding''s location, 1-based; NULL where unlocated';

CREATE TABLE lint_finding_fix (
  graph_name      VARCHAR NOT NULL,
  finding_ordinal INT     NOT NULL,
  description     VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, finding_ordinal),
  FOREIGN KEY (graph_name, finding_ordinal) REFERENCES lint_finding (graph_name, ordinal)
);
COMMENT ON TABLE lint_finding_fix IS 'The correction a rule computed for one of its own findings: a suggestion an editor offers, never a rewrite the build performs. Its own relation rather than a column on lint_finding, because a fix is optional per rule and per site: a description column on the finding would be nullable by kind, and its absence would then mean both "this rule suggests nothing" and "this finding has no fix here". A row is the presence of a fix and the edits are its ordered child. Deliberately not projected onto the diagnostic view, whose every dimension is single-valued at one row per diagnostic where a fix is a list; a reader wanting one joins these two relations on the finding it is offered for.';
COMMENT ON COLUMN lint_finding_fix.graph_name IS 'the owning graph''s partition, carried through the parent finding row';
COMMENT ON COLUMN lint_finding_fix.finding_ordinal IS 'the fixed finding''s ordinal in lint_finding''s emit order';
COMMENT ON COLUMN lint_finding_fix.description IS 'the fix''s rendered title, which an editor shows as the label of the action; display material, never a dimension';

CREATE TABLE lint_finding_fix_edit (
  graph_name      VARCHAR NOT NULL,
  finding_ordinal INT     NOT NULL,
  position        INT     NOT NULL,
  start_line      INT     NOT NULL,
  start_column    INT     NOT NULL,
  end_line        INT     NOT NULL,
  end_column      INT     NOT NULL,
  replacement     VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, finding_ordinal, position),
  FOREIGN KEY (graph_name, finding_ordinal) REFERENCES lint_finding_fix (graph_name, finding_ordinal)
);
COMMENT ON TABLE lint_finding_fix_edit IS 'One text edit of a fix: the half-open range [start, end) becomes the replacement, on the DDL''s standing pattern for an ordered multi-valued decode. The positions are positions in the text the finding was computed against, which is what makes a fix unsafe to apply to a text that has moved since: an edit names a span rather than a declaration, so unlike a coordinate it cannot be re-anchored by resolving the declaration again, and a reader is expected to check the source''s recorded stamp against the text in hand before offering one. An insertion is the zero-width case where start equals end and a deletion the empty replacement; both are stored as written rather than flagged, being readable from the columns.';
COMMENT ON COLUMN lint_finding_fix_edit.graph_name IS 'the owning graph''s partition, carried through the parent fix row';
COMMENT ON COLUMN lint_finding_fix_edit.finding_ordinal IS 'the fixed finding''s ordinal, the parent fix row''s key';
COMMENT ON COLUMN lint_finding_fix_edit.position IS '0-based position within the fix''s own edit list; order is the minted fact, since a rule may write two edits whose spans do not run in source order';
COMMENT ON COLUMN lint_finding_fix_edit.start_line IS 'line of the replaced range''s inclusive start, 1-based per the graphql-java convention the finding''s own location follows';
COMMENT ON COLUMN lint_finding_fix_edit.start_column IS 'column of the replaced range''s inclusive start, 1-based on the same convention';
COMMENT ON COLUMN lint_finding_fix_edit.end_line IS 'line of the replaced range''s exclusive end, 1-based; equal to the start line on an insertion, and on every edit a rule writes today, a token never spanning a line';
COMMENT ON COLUMN lint_finding_fix_edit.end_column IS 'column of the replaced range''s exclusive end, 1-based; equal to the start column on an insertion';
COMMENT ON COLUMN lint_finding_fix_edit.replacement IS 'the text the range becomes, exactly as the rule wrote it; empty on a deletion, which is the honest spelling of replacing a span with nothing';

CREATE TABLE build_warning_no_rule (
  graph_name    VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  message       VARCHAR NOT NULL,
  file          VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (graph_name, ordinal),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
COMMENT ON TABLE build_warning_no_rule IS 'The advisory arm: BuildWarning.NoRule rows, in the sealed warning hierarchy''s own vocabulary; message and location are the arm''s entire component list. Its own relation on two per-arm asymmetries against lint_finding: severity (warning by construction, a third rule beside rejection kind and lint rule) and suppressibility (lint rows are post-suppression survivors keyed by rule id, while advisory rows never met the filter, so one relation holding both would give absence two meanings). This relation has no removal criterion: unlike its three loaded neighbours it is permanent, both shipped producers (the @table-on-input deprecation announcements and the federation compound-key advisory) outliving the walk, so its comment states that fact instead of borrowing a retirement clock it would falsify.';
COMMENT ON COLUMN build_warning_no_rule.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN build_warning_no_rule.ordinal IS 'emit order in the suppression-filtered warning list''s advisory rows, 0-based; the key''s tie-breaker on the javac_diagnostic convention';
COMMENT ON COLUMN build_warning_no_rule.message IS 'the advisory''s rendered message; display material, never a dimension';
COMMENT ON COLUMN build_warning_no_rule.file IS 'canonical file URI of the advisory''s SDL source, normalised once at the loader; NULL where the advisory carries no location';
COMMENT ON COLUMN build_warning_no_rule.source_line IS 'source line of the advisory''s location, 1-based; NULL where unlocated';
COMMENT ON COLUMN build_warning_no_rule.source_column IS 'source column of the advisory''s location, 1-based; NULL where unlocated';

CREATE TABLE graphql_syntax_error (
  graph_name    VARCHAR NOT NULL,
  source_name   VARCHAR NOT NULL,
  message       VARCHAR NOT NULL,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (graph_name, source_name),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
COMMENT ON TABLE graphql_syntax_error IS 'One source the parser refused, per row: the first stage of reading a schema, judged one file at a time. Keyed on the source rather than on an emit-order ordinal, alone among this stratum''s arms, because parsing stops at a source''s first syntax error, so at most one row per source is structural rather than incidental, and the key is then the question a reader actually asks ("does this file parse, and where does it fail") answered as a primary-key lookup. The key also satisfies the store header''s requirement that every base relation be partitionable by the source that produced it, which no ordinal-keyed arm of this stratum can claim; that is a property of the key, not a refresh this store performs, since the SDL families are re-walked wholesale per graph from a parse the pipeline pays for anyway and no schema file reaches the source-partitioned refresh path. A row here is what explains a declaration''s absence from the transcription families without implying the author deleted it, which is the difference between an editor reporting a syntax error and an editor reporting every type in the file as unknown. Rows are written by capture on every pass, so emptiness means every source parsed.';
COMMENT ON COLUMN graphql_syntax_error.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphql_syntax_error.source_name IS 'the refused source, as the reader spelled it (the store_source spelling, not the canonical URI the union view renders); the key''s second dimension, and always known, because the loader is parsing one named source when the parser refuses it';
COMMENT ON COLUMN graphql_syntax_error.message IS 'the refusal''s reason exactly as the parser wrote it; display material, never a dimension. Verbatim rather than the shortened form the build''s exception renders, on the same terms as this stratum''s other message columns: the parser publishes two message shapes, one of which carries its explanation in a trailing clause and the other of which is nothing but that clause, so any mechanical shortening loses the reason on one shape or the other. It does repeat the location in prose, which the stored coordinate columns also carry; that is the rejection arm''s precedent (its message keeps the coordinate prefix too) and it is display material either way. Deliberately not the file-attributed one-liner the build throws: the file is a column here, so prefixing it in would store the same dimension twice and let the two spellings fork';
COMMENT ON COLUMN graphql_syntax_error.source_line IS 'line the parser refused at, 1-based; NULL where it reported no position, which is the rare case of a source whose very first token is unreadable';
COMMENT ON COLUMN graphql_syntax_error.source_column IS 'column the parser refused at, on the same terms as source_line';

CREATE TABLE graphql_schema_error (
  graph_name    VARCHAR NOT NULL,
  ordinal       INT     NOT NULL,
  stage         VARCHAR NOT NULL CHECK (stage IN ('REGISTRY', 'ASSEMBLY')),
  error_class   VARCHAR NOT NULL,
  message       VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  PRIMARY KEY (graph_name, ordinal),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
COMMENT ON TABLE graphql_schema_error IS 'One verdict per row from the two stages that judge the SDL document as a whole, in graphql-java''s own vocabulary. These are the specification''s structural rules, and assembly is the only place they are checked at all: that every named type resolves, that an object satisfies the interfaces it claims, that a directive sits where its definition permits, that the schema has a query root. The stages share this relation because they share a vocabulary, a column set and a grain; splitting them would shatter one ordinal spine to record a difference the stage column already carries. Sibling of graphql_syntax_error rather than one relation with it, because the two refresh at different grains: a syntax refusal belongs to one source and refreshes with it, while a verdict here is a fact about the whole file set that no single source owns and that any change must discard wholesale. Rows are written by capture on every pass, assembly running whether or not the assembled schema is then used for anything, so emptiness means the document was read clean rather than unexamined.';
COMMENT ON COLUMN graphql_schema_error.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphql_schema_error.ordinal IS 'emit order in the stage''s own error list, 0-based, continuing across both stages; the key''s tie-breaker on the javac_diagnostic convention, which this relation needs because a stage reports as many verdicts as it found and several may share a location';
COMMENT ON COLUMN graphql_schema_error.stage IS 'which stage refused, a closed CHECK because the toolchain has exactly these two document-wide stages: REGISTRY for combining every parsed source''s definitions into one registry, ASSEMBLY for assembling that registry. Both populations are live and disjoint by construction: the registry stage refuses the duplicate base declarations (a second type of one name, a second definition of one directive, a second schema block), which in a multi-file workspace are precisely the refusals no single source''s parse could see, and the assembly stage refuses everything else. Deliberately not projected onto the union view: a consumer''s fix does not depend on which stage objected, so putting it on the wire would grow the read vocabulary for no reader. It is kept here because it is the provenance the store''s own reasoning needs, and because it is what makes the duplicate-declaration relation''s claim about assembly running upstream checkable from rows instead of asserted in prose';
COMMENT ON COLUMN graphql_schema_error.error_class IS 'the refusing error''s class name, graphql-java''s own word for what went wrong (MissingTypeError, NonUniqueNameError, DirectiveIllegalLocationError). The only dimension the toolchain publishes: its getErrorType() is uniformly ValidationError across the whole SDL error set and so discriminates nothing. An open column, for the same reason the rejection residue''s variant is open, and rendered onto the union view''s variant column, whose two namespaces cannot collide (a rejection leaf keeps its enclosing classes and is dotted, these are bare)';
COMMENT ON COLUMN graphql_schema_error.message IS 'the verdict''s rendered message; display material, never a dimension. It is also the only place the offending coordinate appears: graphql-java names types and fields in prose rather than in structured fields, and lifting them out by parsing the message would mint a dimension from display material, which is why the coordinate columns this stratum''s other arms carry are absent here';
COMMENT ON COLUMN graphql_schema_error.source_name IS 'the source the verdict points into, as the reader spelled it; NULL where the verdict points nowhere, which is the schema-wide case (a document with no query root names no position). Note the coarseness: graphql-java locates at the enclosing declaration rather than at the offending element, so a field whose type does not resolve is located at its type''s declaration';
COMMENT ON COLUMN graphql_schema_error.source_line IS 'line of the verdict''s location, 1-based; NULL where unlocated, graphql-java''s own (-1, -1) sentinel having been normalised away at the writer';
COMMENT ON COLUMN graphql_schema_error.source_column IS 'column of the verdict''s location, on the same terms as source_line';

-- The canonical file URI spelling, as SQL. The Java site (ValidationReport.canonicalUri) is
-- the declared single home; this alias is its verbatim restatement for the one arm that needs
-- the spelling computed in a view (the pilot arm below reads capture's raw source names, which
-- no loader normalises), and the two spellings are pinned to each other by a parity assertion
-- in the residue loader's test. Inline source, so the DDL stays self-contained.
CREATE ALIAS canonical_uri AS 'String canonicalUri(String sourceName) { if (sourceName == null) return null; try { return java.nio.file.Path.of(sourceName).toUri().toString(); } catch (java.nio.file.InvalidPathException e) { return sourceName; } }';

CREATE VIEW diagnostic
  (graph_name, source, severity, actionable, kind, variant, lsp_code, attempt_kind, attempt,
   stub_key, directives, lint_rule, type_name, field_name, coordinate, file, directory,
   source_line, source_column, message) AS
SELECT r.graph_name, 'schema', 'error', r.kind <> 'DEFERRED', r.kind, r.variant, r.lsp_code,
       r.attempt_kind, r.attempt, r.stub_key,
       (SELECT LISTAGG(d.directive, ',') WITHIN GROUP (ORDER BY d.directive)
          FROM rejection_validation_error_directive d
         WHERE d.graph_name = r.graph_name AND d.error_ordinal = r.ordinal),
       CAST(NULL AS VARCHAR),
       r.type_name, r.field_name,
       CASE WHEN r.type_name IS NULL THEN NULL
            WHEN r.field_name IS NULL THEN r.type_name
            ELSE r.type_name || '.' || r.field_name END,
       r.file,
       REGEXP_REPLACE(r.file, '/[^/]*$', ''),
       r.source_line, r.source_column, r.message
  FROM rejection_validation_error r
UNION ALL
SELECT c.graph_name, 'schema', 'error', c.verdict <> 'DEFERRED',
       CASE WHEN c.verdict = 'DEFERRED' THEN 'DEFERRED' ELSE 'INVALID_SCHEMA' END,
       CASE WHEN c.verdict = 'DEFERRED' THEN 'Rejection.Deferred'
            ELSE 'Rejection.InvalidSchema.DirectiveConflict' END,
       CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR),
       CASE WHEN c.verdict = 'DEFERRED' THEN NULL ELSE c.directives END,
       CAST(NULL AS VARCHAR),
       c.type_name, c.field_name,
       CASE WHEN c.field_name IS NULL THEN c.type_name
            ELSE c.type_name || '.' || c.field_name END,
       canonical_uri(c.source_name),
       REGEXP_REPLACE(canonical_uri(c.source_name), '/[^/]*$', ''),
       c.source_line, c.source_column, c.message
  FROM intent_authored_claim_conflict c
UNION ALL
SELECT l.graph_name, 'schema', 'warning', TRUE,
       CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR),
       CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR),
       CAST(NULL AS VARCHAR), l.lint_rule,
       CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR),
       l.file,
       REGEXP_REPLACE(l.file, '/[^/]*$', ''),
       l.source_line, l.source_column, l.message
  FROM lint_finding l
UNION ALL
SELECT w.graph_name, 'schema', 'warning', TRUE,
       CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR),
       CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR),
       CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR),
       CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR),
       w.file,
       REGEXP_REPLACE(w.file, '/[^/]*$', ''),
       w.source_line, w.source_column, w.message
  FROM build_warning_no_rule w
UNION ALL
SELECT x.graph_name, 'schema', 'error', TRUE,
       CAST(NULL AS VARCHAR), 'InvalidSyntaxException', CAST(NULL AS VARCHAR),
       CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR),
       CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR),
       CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR),
       canonical_uri(x.source_name),
       REGEXP_REPLACE(canonical_uri(x.source_name), '/[^/]*$', ''),
       x.source_line, x.source_column, x.message
  FROM graphql_syntax_error x
UNION ALL
SELECT s.graph_name, 'schema', 'error', TRUE,
       CAST(NULL AS VARCHAR), s.error_class, CAST(NULL AS VARCHAR),
       CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR),
       CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR),
       CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR),
       canonical_uri(s.source_name),
       REGEXP_REPLACE(canonical_uri(s.source_name), '/[^/]*$', ''),
       s.source_line, s.source_column, s.message
  FROM graphql_schema_error s
UNION ALL
SELECT j.graph_name, 'compile',
       CASE WHEN j.kind = 'ERROR' THEN 'error' ELSE 'warning' END,
       TRUE,
       CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR), j.code,
       CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR),
       CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR),
       CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR),
       CASE WHEN j.file = '(no source)' THEN NULL ELSE j.file END,
       CASE WHEN j.file = '(no source)' THEN NULL
            ELSE REGEXP_REPLACE(j.file, '/[^/]*$', '') END,
       CASE WHEN j.line_number = -1 THEN NULL ELSE CAST(j.line_number AS INT) END,
       CASE WHEN j.column_number = -1 THEN NULL ELSE CAST(j.column_number AS INT) END,
       j.message
  FROM javac_diagnostic j;
COMMENT ON VIEW diagnostic IS 'The diagnostics stratum''s one read surface: the union of all seven arms (the rejection residue, the store-native claim-conflict pilot, the lint arm, the advisory arm, the parser and the SDL toolchain''s two document-wide stages, the compile oracle), which the MCP diagnostics tools read and no consumer bypasses. Prefix-less on purpose: a read-side union across vocabularies has no family, and no naming gate says so mechanically, so this comment does. Derived columns live here rather than in the base relations: actionable is the deferred-versus-rest CASE over kind (the same predicate the LSP severity projection documents, pinned by a one-row parity assertion); severity for compile rows mirrors CompileDiagnostic.severity() (ERROR to error, every other javac kind to warning, same parity discipline); coordinate and directory are renderings of the stored pair and the canonical file; the compile arm''s sentinels ("(no source)", -1) normalise to the uniform NULL absent bucket by comparing against the sentinel values, never IS NULL. lsp_code carries the producing oracle''s stable machine code in both namespaces (the rejection sub-seals'' lspCode(), javac''s Diagnostic.getCode()), which cannot collide. Every dimension is single-valued at one row per diagnostic, so group counts sum to the row count; directives renders the canonical sorted spelling in every arm that carries it.';
COMMENT ON COLUMN diagnostic.graph_name IS 'the owning graph''s partition, carried through from every arm; the MCP read site filters to the reading session''s graph';
COMMENT ON COLUMN diagnostic.source IS 'the closed channel taxonomy the shipped tool already speaks: schema for the six validator-side arms, compile for the javac arm';
COMMENT ON COLUMN diagnostic.severity IS 'error or warning, the wire''s closed pair: the rejection arms are error by the build''s own finality, lint and advisory rows warning by construction, compile rows javac''s verdict projected as the record''s severity() spells it';
COMMENT ON COLUMN diagnostic.actionable IS 'the triage headline: FALSE exactly on DEFERRED rows (recognised but not yet generator-supported, a workaround rather than a schema fix), TRUE everywhere else including warnings and compile rows';
COMMENT ON COLUMN diagnostic.kind IS 'RejectionKind.name() on rejection-bearing rows (residue and pilot); NULL on lint, advisory and compile rows, where the three-way fork does not apply';
COMMENT ON COLUMN diagnostic.variant IS 'the producing oracle''s error-class dimension, in whichever of two namespaces minted the row: the rejection leaf''s package-stripped class name on rejection-bearing rows (rejection_validation_error.variant''s spelling rule), and graphql-java''s own error class name on the SDL toolchain''s arms (the constant InvalidSyntaxException on parser rows, since that stage has exactly one way to refuse; graphql_schema_error.error_class on the document-wide arms). The namespaces cannot collide, a rejection leaf keeping its enclosing classes and so always dotted where graphql-java''s are bare, which is the same argument lsp_code makes for carrying two code namespaces in one column. NULL on the lint, advisory and compile arms';
COMMENT ON COLUMN diagnostic.lsp_code IS 'the stable machine code of the row''s producing oracle: the rejection sub-seals'' lspCode() on schema rows that declare one, javac''s Diagnostic.getCode() on compile rows; NULL where neither publishes a code';
COMMENT ON COLUMN diagnostic.attempt_kind IS 'which lookup space a name resolution failed in (AttemptKind.name()), on UnknownName rows only';
COMMENT ON COLUMN diagnostic.attempt IS 'the name the author wrote, on UnknownName rows only';
COMMENT ON COLUMN diagnostic.stub_key IS 'the deferred row''s stubbed-variant anchor; NULL off Deferred rows and on inline-defer sites naming no leaf class';
COMMENT ON COLUMN diagnostic.directives IS 'the directive names identifying a conflict, as one canonical sorted comma-joined value, so claim order can never split a group; NULL off conflict rows';
COMMENT ON COLUMN diagnostic.lint_rule IS 'LintRule.id() on lint rows; NULL elsewhere, including the advisory arm, whose rows are precisely the warnings no rule tags';
COMMENT ON COLUMN diagnostic.type_name IS 'the coordinate''s owning type, the coarse grain of the coordinate axis; NULL on rows carrying no schema coordinate';
COMMENT ON COLUMN diagnostic.field_name IS 'the coordinate''s field name, NULL at the type grain and on coordinate-less rows';
COMMENT ON COLUMN diagnostic.coordinate IS 'the rendered coordinate (a type name or Type.field), computed from the stored pair; the fine grain of the coordinate axis';
COMMENT ON COLUMN diagnostic.file IS 'canonical file URI of the row''s source (SDL file on schema rows, generated .java on compile rows), one spelling across both channels; NULL in the stated absent bucket (whole-build findings, unlocated rejections, javac''s no-source sentinel)';
COMMENT ON COLUMN diagnostic.directory IS 'the file''s directory, the canonical URI truncated at its last segment; the coarse grain of the file axis, NULL exactly where file is';
COMMENT ON COLUMN diagnostic.source_line IS 'the location''s line, 1-based in both channels; NULL in the absent bucket (javac''s NOPOS normalised here)';
COMMENT ON COLUMN diagnostic.source_column IS 'the location''s column, on the same terms as source_line';
COMMENT ON COLUMN diagnostic.message IS 'the row''s rendered message, whichever oracle authored it; display material, never a dimension, never an agreement anchor';

-- ==== Schema self-description (meta_) =============================================
-- The schema describing itself: the family roster, the placement of relations no prefix
-- covers, and the census that closes both against the observed schema. Stated as views over
-- row values rather than tables, so the rows are constants versioned with the DDL by
-- construction: a warm refresh cannot empty what holds no rows of its own, and a run cannot
-- write what the file already states. The uniqueness a table would take from a PRIMARY KEY
-- is gated instead (FactSchemaGateTest), which also closes the roster against the observed
-- relations in both directions; the generated schema reference and the docs drift guard read
-- these relations through the shared catalog reader rather than re-deriving the match.

CREATE VIEW meta_family (prefix, title, ordinal, definition) AS VALUES
  ('store_', 'The store''s own record', 0, 'The store''s own record: what it read, what it was built from, and which graphs it holds. Its rows are never a reading of the consumer''s schema, database or classpath, which is what the transcription families are named for; the graph recipe rows are configuration the run held in hand, which is what keeps them in this family rather than making them a family of their own.'),
  ('graphql_', 'Generic SDL transcription', 1, 'Generic GraphQL: a row any SDL reader could produce from the document without knowing graphitron exists, which is every declaration, every directive definition, and every directive application including graphitron''s own. The family is a total transcription, with no hole where graphitron''s namespace was: whether an application survives into the emitted schema is a namespace query over graphql_directive at emission time, not something capture decides by choosing a table, and a directive that is both re-emitted and decoded (federation''s @key) is simply a row in each family rather than a special case. Two residents are verdicts rather than declarations (graphql_syntax_error, graphql_schema_error: what the SDL toolchain concluded about whether the document is a schema at all), which makes this the one family holding both a transcription and a judgement of the same artifact. They are here on the reader-neutrality test that names the family, since a syntax error and a specification violation are as much things any SDL reader produces from the document as a declaration is, and the alternative was a second family aliasing this one''s subject.'),
  ('graphitron_', 'The decoded graphitron reading', 2, 'What graphitron makes of the SDL document: the decoded directives, and the provenance of the rows macro expansion mints. A row here is still a transcription, not a conclusion: it says what a directive application spelled, in graphitron''s vocabulary instead of the document''s.'),
  ('sql_', 'The consumer database catalog', 3, 'What the consumer''s database declares, read through jOOQ''s generated model. Not jooq_: naming a family for its reader is what this name replaces, because jOOQ defines neither table nor column nor foreign key.'),
  ('jvm_', 'The compile classpath census', 4, 'What the classfiles on the compile classpath declare. Not extension_: naming a family for a presumed role is what this name replaces, because an ObjectMapper on the classpath extends nothing yet still earns a row.'),
  ('java_', 'The consumer''s Java sources', 5, 'What the consumer''s .java sources declare, read by an unattributed parse: where each class, method and field is written, and what its doc comment says. Its own family beside jvm_ rather than columns on it, because the two are separate populations on separate cadences that may legitimately disagree: a source parse yields arity where a classfile yields a descriptor, and the jvm_ census excludes the generated jOOQ package this family has to answer for. Named for the language whose declarations it transcribes, and distinct from javac_, which holds what the compiler concluded about generated sources rather than what a parse read from authored ones.'),
  ('javac_', 'The compile oracle''s verdicts', 6, 'What the JDK compiler reports about the emitted sources, written in javax.tools.Diagnostic''s terms.'),
  ('walk_', 'The legacy walk''s reach', 7, 'What the legacy classification walk registered, transcribed as membership rows in the walk''s own vocabulary (its registries'' reach). Naming the family for the retiring walk gives the name its own retirement clock: when the walk is gone, the family has no referent.'),
  ('intent_', 'Derived intent', 8, 'The SDL strata stack''s third layer, graphql_ under graphitron_ under this name: what gets derived once something resolves and combines those readings into what the generator will actually do. The residents are views plus the materialized derivations whose table comments own why they cannot be views; that changes nothing about the name, since a family is named for whose vocabulary its rows are written in and materialization is not the discriminator. The stratum has two layers, and a new resident picks one deliberately: the base derivations (the authored claim views, one per grain; the structural classifier views, one per classifier so each carries exactly its own witness columns; the resolutions those classifiers stand on, which earn their own relation as soon as a second reader asks them and which layer among themselves on that same rule, a resolution keyed on a written name sitting under the ones keyed on a coordinate; the demand and exemption rule views, stated at the grain their rules are authored at), and the reductions over them (intent_resolved_field_claim and the resolved demand views, the resolution expressions a planning reader joins). No relation should acquire the prefix by drifting into it; each new derived resident is its own change.'),
  ('rejection_', 'The legacy walk''s verdicts', 9, 'The legacy walk''s verdicts, transcribed in the sealed Rejection hierarchy''s own spellings (kind, variant, lsp_code, attempt_kind and stub_key are all that hierarchy''s words) and carrying the same retirement clock as walk_: transitional by construction, drained family by family as detections migrate store-native. Deliberately not validator_, both because that names a role and because the validation phase outlives the hierarchy and may one day want its own name.'),
  ('lint_', 'The linter''s findings', 10, 'The linter''s vocabulary (lint_rule is LintRule.id()), its own family because a lint finding''s severity is a function of its rule, never a rejection kind, and because lint rules are predicates over classified facts that should be free to migrate store-native without contending for another family''s relation.'),
  ('build_warning_', 'The advisory arm', 11, 'The sealed BuildWarning hierarchy''s advisory arm in that hierarchy''s own words (message and location are NoRule''s entire component list), with the arm selector in the relation name per the jvm_scalar_type_field precedent, since the sibling arm lives in lint_. Not graphitron_, whose decoded-directives-and-macro-provenance charter an advisory is neither of, and not walk_, because a family may not be named for its producer and both of the arm''s producers outlive the walk.'),
  ('meta_', 'The schema describing itself', 12, 'The schema''s own description: the family roster, the placement of relations no prefix covers, and the census that closes both against the observed schema. Authored as constant rows stated as views, so the description is versioned with the DDL it describes and can never be refreshed apart from it; not store_, because these rows are a statement of what this file declares, never a record of what a run read.');
COMMENT ON VIEW meta_family IS 'The family roster: one row per relation-name prefix, keyed by the prefix under the schema''s naming discipline (a family is named for whose vocabulary its rows are written in, never for its reader or its role). The definition column carries each family''s charter, migrated out of this file''s header so the roster has one home; the generated schema reference renders one page per row, ordered by ordinal, and the schema gates close the roster against the observed relations in both directions.';
COMMENT ON COLUMN meta_family.prefix IS 'the family''s relation-name prefix, trailing underscore included; the roster''s key, unique by gate since a view carries no PRIMARY KEY, and no prefix may be a prefix of another (gated), which is what lets the census match exactly';
COMMENT ON COLUMN meta_family.title IS 'the family''s rendered page title in the generated schema reference; plain prose, display material only';
COMMENT ON COLUMN meta_family.ordinal IS 'the family''s position in the reference''s page order and index roster, 0-based; unique by gate';
COMMENT ON COLUMN meta_family.definition IS 'the family''s charter: whose vocabulary its rows are written in and why the name is right, rendered as the family page''s preamble';

CREATE VIEW meta_prefixless_relation (relation_name, page, reason) AS VALUES
  ('diagnostic', CAST(NULL AS VARCHAR), 'The diagnostics stratum''s read surface unions five arms across four families'' vocabularies; a read-side union across vocabularies has no family, so it renders on the reference''s index, the one cross-family surface.');
COMMENT ON VIEW meta_prefixless_relation IS 'The placement exemptions: one row per relation deliberately outside every family, in the exemption polarity the schema gates use throughout, so a new prefix-less relation fails the roster gate until an authored row argues it in. The page column places the relation in the generated reference; the reason column carries the no-family argument beside the relation''s own comment.';
COMMENT ON COLUMN meta_prefixless_relation.relation_name IS 'the exempted relation''s name as declared; the exemption''s key, and it must resolve to an observed relation (gated)';
COMMENT ON COLUMN meta_prefixless_relation.page IS 'the family page that renders the relation, a meta_family prefix; NULL in the stated absent bucket: the relation belongs on no family''s page and renders on the reference''s index instead';
COMMENT ON COLUMN meta_prefixless_relation.reason IS 'why no family covers the relation, rendered beside the relation in the reference';

CREATE VIEW meta_relation_family (relation_name, relation_type, prefix, exempted) AS
SELECT LOWER(t.table_name), t.table_type, f.prefix, x.relation_name IS NOT NULL
  FROM INFORMATION_SCHEMA.TABLES t
  LEFT JOIN meta_family f ON LEFT(LOWER(t.table_name), CHAR_LENGTH(f.prefix)) = f.prefix
  LEFT JOIN meta_prefixless_relation x ON LOWER(t.table_name) = x.relation_name
 WHERE t.table_schema = 'PUBLIC';
COMMENT ON VIEW meta_relation_family IS 'The census that closes the roster: every relation in the schema, its kind, the family whose prefix covers it, and whether an exemption row places it. The one relational answer to which family a relation belongs to: the schema gates, the generated reference and the docs drift guard all read this view rather than re-deriving the match, so two mechanisms of different fidelity can never answer the question differently.';
COMMENT ON COLUMN meta_relation_family.relation_name IS 'the relation''s name as declared, lowercased from the engine''s catalog spelling';
COMMENT ON COLUMN meta_relation_family.relation_type IS 'the engine''s kind for the relation: BASE TABLE or VIEW; both carry comments and columns and both render in the reference, but only base tables additionally carry keys and constraints';
COMMENT ON COLUMN meta_relation_family.prefix IS 'the covering family''s prefix, by exact prefix match against meta_family; NULL where no family covers the relation, which the gates require an exemption row to justify';
COMMENT ON COLUMN meta_relation_family.exempted IS 'whether a meta_prefixless_relation row places this relation; TRUE must hold exactly where prefix is NULL, in both directions (gated)';
