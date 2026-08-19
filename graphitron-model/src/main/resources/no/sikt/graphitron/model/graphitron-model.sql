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
-- it.
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
  field_name_upper  VARCHAR GENERATED ALWAYS AS (UPPER(field_name)),
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
COMMENT ON COLUMN graphql_field.field_name_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against sql_column''s column_name_upper and jooq_name_upper where @field(name:) was omitted and the field name stands in as a column spelling. Generated, so nothing writes it and nothing can. A GraphQL field name is folded here for that crossing alone; nothing compares one to another case-insensitively';

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
COMMENT ON TABLE graphql_duplicate_declaration IS 'The duplicate-declaration overflow, sibling of graphitron_undecoded_argument in that each is its family''s overflow relation, holding what that family''s primary write path declined. The registry retains element-level duplicates without error (a field declared twice in one body or re-declared by an extension, a repeated argument, enum value, union member, or implements entry, a second application of a single-application graphitron directive, a repeated location or formal argument in a directive definition), so every element-level natural key in this schema is author-reachable. Capture is first-wins in merge order; the losing occurrence records here, rendered and located, so no authored text is lost and the duplicate-declaration detection has its row. The element-level kinds became reachable when capture stopped being conditional on the document assembling: assembly does reject these schemas (a twice-declared field is a NonUniqueNameError), but its refusal is now a row in graphql_schema_error rather than an abort, so the same pass captures both the verdict and the retained duplicate this relation holds. A second base definition, of a type or of a directive, is refused one stage earlier, by the registry, whose first-wins admission keeps the winner and reports the loser as a verdict without offering its declaration to capture; the TYPE kind is therefore still reachable only through the LSP''s per-file fragment path, now because the losing declaration never reaches the walk rather than because the registry throws.';
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
COMMENT ON COLUMN graphql_type_directive.declaration_line IS 'the applying site (extensions apply type directives too). Every row here is a site the author wrote: no expansion applies a type directive, federation''s synthesized @key being a derivation (intent_synthesized_federation_key) rather than a row in this family';
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


-- ==== The decoded graphitron and federation inventory =============================
-- A derivation over the transcription: every relation below is a function of the generic
-- directive applications the graphql_ family captured, decoded into graphitron's vocabulary.

-- The dotted paths this family stores as written, decomposed once each. Every relation below
-- whose right-hand side is an argument path shares this one decode, so it leads the family.
CREATE TABLE graphitron_argument_path_segment (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  argument_path VARCHAR NOT NULL,
  position      INT     NOT NULL,
  segment_name  VARCHAR NOT NULL,
  segment_name_upper VARCHAR GENERATED ALWAYS AS (UPPER(segment_name)),
  PRIMARY KEY (graph_name, type_name, field_name, argument_path, position),
  FOREIGN KEY (graph_name, type_name, field_name) REFERENCES graphql_field (graph_name, type_name, field_name)
);
COMMENT ON TABLE graphitron_argument_path_segment IS 'What a dotted argMapping right-hand side is made of: one row per segment of one path, in order, as one site decoded it. The seven pair relations of this family each store such a path as a single string, and this states its decomposition at the coordinate whose site spelled it, so a reader asking which paths a field''s mappings segment into asks this relation instead of joining on a bare string. It anchors on graphql_field, whose key is the triple all seven owners lead with, rather than on any one of them: the coordinate is what the owners share, and picking one as the parent would be choosing a site for a fact that has several. Which of the seven a segment set came from is therefore not answered here, and a consumer needing it joins the pair relation on the coordinate and the path. A path text several coordinates spell is decomposed once per coordinate, and that duplication is deliberate rather than tolerated: argument_path with position determines segment_name totally, off a column in the same row, so there is no independently updatable fact for two copies to disagree about and no update anomaly for a normalisation to prevent. It is the same trade the folded companion columns elsewhere in this schema make, a derived duplicate kept because its invariant is structural. The value-keyed alternative reads as the tidier one and is not: a path text no relation declares gives the segments no owner to be constrained against, so a set nothing spells any more is not merely unreferenced but unconstrainable, and no question can be asked of it at a coordinate. Capture writes it because the parse that produces the pair rows already holds the segments and joins them back into a column, so the decomposition is something capture has in hand and throws away, not something a reader could recover: splitting a string is outside what this schema asks of a view, on intent_input_occurrence_path''s terms. Positions are dense from zero and the segments in order rejoin the path exactly, the lexer admitting no dot inside a segment, so the relation and the column it decodes cannot say different things. A bare argument name is one row rather than none, a single-segment decode being a decode.';
COMMENT ON COLUMN graphitron_argument_path_segment.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_argument_path_segment.type_name IS 'the GraphQL type the spelling site sits on; with the field below, the coordinate that owns this decode';
COMMENT ON COLUMN graphitron_argument_path_segment.field_name IS 'the field name within the owning type; the coordinate all seven pair relations lead with, which is what makes this relation reachable from every one of them';
COMMENT ON COLUMN graphitron_argument_path_segment.argument_path IS 'the path as written, spelled exactly as the pair relations of this family spell it; a pair row reaches its own decode by joining on the coordinate and this column, which is a coordinate join rather than a match on a bare string';
COMMENT ON COLUMN graphitron_argument_path_segment.position IS '0-based position of the segment within the path, dense from zero';
COMMENT ON COLUMN graphitron_argument_path_segment.segment_name IS 'the segment itself, one name carrying no dot. Position zero is the head, naming an argument of the field the directive sits on, and each further position descends through an input-object field of the one before it; which of those a segment resolves to is a question for the derived stratum, and this relation only says what was written';
COMMENT ON COLUMN graphitron_argument_path_segment.segment_name_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against a node type''s resolved key columns where a trailing segment names one, which intent_resolved_node_key_projection spells. Generated, so nothing writes it and nothing can. An authored spelling is folded here for that crossing alone, which is the only reason anything in this schema is folded; a segment naming a GraphQL argument or input field is matched exactly, those names being case-sensitive in the language';

CREATE TABLE graphitron_table (
  graph_name       VARCHAR NOT NULL,
  type_name        VARCHAR NOT NULL,
  source_name      VARCHAR NOT NULL,
  declaration_line INT     NOT NULL,
  declaration_column INT   NOT NULL,
  source_line      INT,
  source_column    INT,
  table_ref        VARCHAR,
  table_ref_namespace_part VARCHAR,
  table_ref_name_part      VARCHAR,
  type_name_upper                VARCHAR GENERATED ALWAYS AS (UPPER(type_name)),
  table_ref_namespace_part_upper VARCHAR GENERATED ALWAYS AS (UPPER(table_ref_namespace_part)),
  table_ref_name_part_upper      VARCHAR GENERATED ALWAYS AS (UPPER(table_ref_name_part)),
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
COMMENT ON COLUMN graphitron_table.table_ref_namespace_part IS 'left of table_ref''s first period, NULL when no period appeared and the empty string when one appeared with nothing before it; for a table name this namespace is the SQL schema in every dialect jOOQ models. Written by capture, because splitting on a period is a decode and decodes happen there';
COMMENT ON COLUMN graphitron_table.table_ref_name_part IS 'right of table_ref''s first period, or the whole value when none; the empty string when a period was written with nothing after it, which joins nothing and is meant to';
COMMENT ON COLUMN graphitron_table.type_name_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against sql_table.table_name_upper where @table(name:) was omitted and the type name stands in as a table spelling. Generated, so nothing writes it and nothing can. A GraphQL type name is folded here for that crossing alone; nothing compares one to another case-insensitively';
COMMENT ON COLUMN graphitron_table.table_ref_namespace_part_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against sql_table''s schema and name. Generated, so nothing writes it and nothing can. It exists because an authored spelling meets a catalog name here, which is the only reason anything in this schema is folded';
COMMENT ON COLUMN graphitron_table.table_ref_name_part_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against sql_table''s schema and name. Generated, so nothing writes it and nothing can. It exists because an authored spelling meets a catalog name here, which is the only reason anything in this schema is folded';

CREATE TABLE graphitron_field_binding (
  graph_name    VARCHAR NOT NULL,
  type_name     VARCHAR NOT NULL,
  field_name    VARCHAR NOT NULL,
  source_name   VARCHAR,
  source_line   INT,
  source_column INT,
  name_ref      VARCHAR NOT NULL,
  name_ref_upper VARCHAR GENERATED ALWAYS AS (UPPER(name_ref)),
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
COMMENT ON COLUMN graphitron_field_binding.name_ref_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against sql_column''s column_name_upper and jooq_name_upper. Generated, so nothing writes it and nothing can. It exists because an authored spelling meets a catalog name here, which is the only reason anything in this schema is folded';

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
COMMENT ON COLUMN graphitron_field_condition_arg_mapping_pair.argument_path IS 'the right side as written, and written is the whole of what this column claims: capture records the author''s spelling verbatim and resolves nothing. A dot opens the thing at that position, and what a thing opens into depends on what it is: an input object opens into its fields, and an ID carrying @nodeId opens into the key columns of the node type it names, so a trailing segment may be a key column rather than a field of any SDL type. Which of those a segment turned out to be is intent_argmapping_segment_binding''s answer and the key projection''s beside it; enumerating the forms here would be a second statement of a resolution those views own, and the enumeration this column carried before the key-column form existed was exactly that mistake caught late. graphitron_argument_path_segment holds the decomposition, so nothing splits this string';

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
COMMENT ON COLUMN graphitron_argument_condition_arg_mapping_pair.argument_path IS 'the right side as written, and written is the whole of what this column claims: capture records the author''s spelling verbatim and resolves nothing. A dot opens the thing at that position, and what a thing opens into depends on what it is: an input object opens into its fields, and an ID carrying @nodeId opens into the key columns of the node type it names, so a trailing segment may be a key column rather than a field of any SDL type. Which of those a segment turned out to be is intent_argmapping_segment_binding''s answer and the key projection''s beside it; enumerating the forms here would be a second statement of a resolution those views own, and the enumeration this column carried before the key-column form existed was exactly that mistake caught late. graphitron_argument_path_segment holds the decomposition, so nothing splits this string';

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
  table_ref_namespace_part VARCHAR,
  table_ref_name_part      VARCHAR,
  key_ref     VARCHAR,
  key_ref_namespace_part   VARCHAR,
  key_ref_name_part        VARCHAR,
  class_name  VARCHAR,
  method      VARCHAR,
  arg_mapping VARCHAR,
  table_ref_namespace_part_upper VARCHAR GENERATED ALWAYS AS (UPPER(table_ref_namespace_part)),
  table_ref_name_part_upper      VARCHAR GENERATED ALWAYS AS (UPPER(table_ref_name_part)),
  key_ref_namespace_part_upper   VARCHAR GENERATED ALWAYS AS (UPPER(key_ref_namespace_part)),
  key_ref_name_part_upper        VARCHAR GENERATED ALWAYS AS (UPPER(key_ref_name_part)),
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
COMMENT ON COLUMN graphitron_field_reference_step.table_ref IS 'ReferenceElement.table as written (may carry a schema qualifier); it resolves through findTable, the same route the argument-site sibling takes';
COMMENT ON COLUMN graphitron_field_reference_step.table_ref_namespace_part IS 'left of table_ref''s first period, NULL when no period appeared and the empty string when one appeared with nothing before it; for a table name this namespace is the SQL schema in every dialect jOOQ models. Written by capture, because splitting on a period is a decode and decodes happen there';
COMMENT ON COLUMN graphitron_field_reference_step.table_ref_name_part IS 'right of table_ref''s first period, or the whole value when none; the empty string when a period was written with nothing after it, which joins nothing and is meant to';
COMMENT ON COLUMN graphitron_field_reference_step.key_ref IS 'ReferenceElement.key as written (may carry a schema qualifier)';
COMMENT ON COLUMN graphitron_field_reference_step.key_ref_namespace_part IS 'left of key_ref''s first period, NULL when no period appeared and the empty string when one appeared with nothing before it. This qualifier does not name the constraint''s own schema, because a constraint has none: it is scoped to its table, which is why sql_constraint takes its schema through the table. It names which schema''s table holds the constraint, disambiguating a constraint name that occurs in more than one, and the resolver reads it that way. Which namespace that is is dialect-dependent (the schema namespace in Oracle, the table namespace in PostgreSQL), which is why the column is not called a schema part';
COMMENT ON COLUMN graphitron_field_reference_step.key_ref_name_part IS 'right of key_ref''s first period, or the whole value when none; joins sql_constraint.constraint_name narrowed by the source table the walk is standing on, not by this row alone';
COMMENT ON COLUMN graphitron_field_reference_step.class_name IS 'the fully-qualified Java class name as written';
COMMENT ON COLUMN graphitron_field_reference_step.method IS 'the Java method name as written';
COMMENT ON COLUMN graphitron_field_reference_step.arg_mapping IS 'the argMapping string as written; the pair child is its decode';
COMMENT ON COLUMN graphitron_field_reference_step.table_ref_namespace_part_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against sql_table''s schema and name. Generated, so nothing writes it and nothing can. It exists because an authored spelling meets a catalog name here, which is the only reason anything in this schema is folded';
COMMENT ON COLUMN graphitron_field_reference_step.table_ref_name_part_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against sql_table''s schema and name. Generated, so nothing writes it and nothing can. It exists because an authored spelling meets a catalog name here, which is the only reason anything in this schema is folded';
COMMENT ON COLUMN graphitron_field_reference_step.key_ref_namespace_part_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against sql_constraint.table_schema_upper, the schema of the table holding the constraint. Generated, so nothing writes it and nothing can. It exists because an authored spelling meets a catalog name here, which is the only reason anything in this schema is folded';
COMMENT ON COLUMN graphitron_field_reference_step.key_ref_name_part_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against sql_constraint''s constraint_name_upper and jooq_name_upper, in that precedence. Generated, so nothing writes it and nothing can. It exists because an authored spelling meets a catalog name here, which is the only reason anything in this schema is folded';

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
COMMENT ON COLUMN graphitron_field_reference_step_arg_mapping_pair.argument_path IS 'the right side as written, and written is the whole of what this column claims: capture records the author''s spelling verbatim and resolves nothing. A dot opens the thing at that position, and what a thing opens into depends on what it is: an input object opens into its fields, and an ID carrying @nodeId opens into the key columns of the node type it names, so a trailing segment may be a key column rather than a field of any SDL type. Which of those a segment turned out to be is intent_argmapping_segment_binding''s answer and the key projection''s beside it; enumerating the forms here would be a second statement of a resolution those views own, and the enumeration this column carried before the key-column form existed was exactly that mistake caught late. graphitron_argument_path_segment holds the decomposition, so nothing splits this string';

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
  table_ref_namespace_part VARCHAR,
  table_ref_name_part      VARCHAR,
  key_ref       VARCHAR,
  key_ref_namespace_part   VARCHAR,
  key_ref_name_part        VARCHAR,
  class_name    VARCHAR,
  method        VARCHAR,
  arg_mapping   VARCHAR,
  table_ref_namespace_part_upper VARCHAR GENERATED ALWAYS AS (UPPER(table_ref_namespace_part)),
  table_ref_name_part_upper      VARCHAR GENERATED ALWAYS AS (UPPER(table_ref_name_part)),
  key_ref_namespace_part_upper   VARCHAR GENERATED ALWAYS AS (UPPER(key_ref_namespace_part)),
  key_ref_name_part_upper        VARCHAR GENERATED ALWAYS AS (UPPER(key_ref_name_part)),
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
COMMENT ON COLUMN graphitron_argument_reference_step.table_ref_namespace_part IS 'left of table_ref''s first period, NULL when no period appeared and the empty string when one appeared with nothing before it; for a table name this namespace is the SQL schema in every dialect jOOQ models. Written by capture, because splitting on a period is a decode and decodes happen there';
COMMENT ON COLUMN graphitron_argument_reference_step.table_ref_name_part IS 'right of table_ref''s first period, or the whole value when none; the empty string when a period was written with nothing after it, which joins nothing and is meant to';
COMMENT ON COLUMN graphitron_argument_reference_step.key_ref IS 'the constraint name as written (may carry a schema qualifier)';
COMMENT ON COLUMN graphitron_argument_reference_step.key_ref_namespace_part IS 'left of key_ref''s first period, NULL when no period appeared and the empty string when one appeared with nothing before it. This qualifier does not name the constraint''s own schema, because a constraint has none: it is scoped to its table, which is why sql_constraint takes its schema through the table. It names which schema''s table holds the constraint, disambiguating a constraint name that occurs in more than one, and the resolver reads it that way. Which namespace that is is dialect-dependent (the schema namespace in Oracle, the table namespace in PostgreSQL), which is why the column is not called a schema part';
COMMENT ON COLUMN graphitron_argument_reference_step.key_ref_name_part IS 'right of key_ref''s first period, or the whole value when none; joins sql_constraint.constraint_name narrowed by the source table the walk is standing on, not by this row alone';
COMMENT ON COLUMN graphitron_argument_reference_step.class_name IS 'the fully-qualified Java class name as written';
COMMENT ON COLUMN graphitron_argument_reference_step.method IS 'the Java method name as written';
COMMENT ON COLUMN graphitron_argument_reference_step.arg_mapping IS 'the argMapping string as written; the pair child is its decode';
COMMENT ON COLUMN graphitron_argument_reference_step.table_ref_namespace_part_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against sql_table''s schema and name. Generated, so nothing writes it and nothing can. It exists because an authored spelling meets a catalog name here, which is the only reason anything in this schema is folded';
COMMENT ON COLUMN graphitron_argument_reference_step.table_ref_name_part_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against sql_table''s schema and name. Generated, so nothing writes it and nothing can. It exists because an authored spelling meets a catalog name here, which is the only reason anything in this schema is folded';
COMMENT ON COLUMN graphitron_argument_reference_step.key_ref_namespace_part_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against sql_constraint.table_schema_upper, the schema of the table holding the constraint. Generated, so nothing writes it and nothing can. It exists because an authored spelling meets a catalog name here, which is the only reason anything in this schema is folded';
COMMENT ON COLUMN graphitron_argument_reference_step.key_ref_name_part_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against sql_constraint''s constraint_name_upper and jooq_name_upper, in that precedence. Generated, so nothing writes it and nothing can. It exists because an authored spelling meets a catalog name here, which is the only reason anything in this schema is folded';

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
COMMENT ON COLUMN graphitron_argument_reference_step_arg_mapping_pair.argument_path IS 'the right side as written, and written is the whole of what this column claims: capture records the author''s spelling verbatim and resolves nothing. A dot opens the thing at that position, and what a thing opens into depends on what it is: an input object opens into its fields, and an ID carrying @nodeId opens into the key columns of the node type it names, so a trailing segment may be a key column rather than a field of any SDL type. Which of those a segment turned out to be is intent_argmapping_segment_binding''s answer and the key projection''s beside it; enumerating the forms here would be a second statement of a resolution those views own, and the enumeration this column carried before the key-column form existed was exactly that mistake caught late. graphitron_argument_path_segment holds the decomposition, so nothing splits this string';

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
  table_ref_namespace_part VARCHAR,
  table_ref_name_part      VARCHAR,
  key_ref     VARCHAR,
  key_ref_namespace_part   VARCHAR,
  key_ref_name_part        VARCHAR,
  class_name  VARCHAR,
  method      VARCHAR,
  arg_mapping VARCHAR,
  table_ref_namespace_part_upper VARCHAR GENERATED ALWAYS AS (UPPER(table_ref_namespace_part)),
  table_ref_name_part_upper      VARCHAR GENERATED ALWAYS AS (UPPER(table_ref_name_part)),
  key_ref_namespace_part_upper   VARCHAR GENERATED ALWAYS AS (UPPER(key_ref_namespace_part)),
  key_ref_name_part_upper        VARCHAR GENERATED ALWAYS AS (UPPER(key_ref_name_part)),
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
COMMENT ON COLUMN graphitron_reference_for_step.table_ref_namespace_part IS 'left of table_ref''s first period, NULL when no period appeared and the empty string when one appeared with nothing before it; for a table name this namespace is the SQL schema in every dialect jOOQ models. Written by capture, because splitting on a period is a decode and decodes happen there';
COMMENT ON COLUMN graphitron_reference_for_step.table_ref_name_part IS 'right of table_ref''s first period, or the whole value when none; the empty string when a period was written with nothing after it, which joins nothing and is meant to';
COMMENT ON COLUMN graphitron_reference_for_step.key_ref IS 'the constraint name as written (may carry a schema qualifier)';
COMMENT ON COLUMN graphitron_reference_for_step.key_ref_namespace_part IS 'left of key_ref''s first period, NULL when no period appeared and the empty string when one appeared with nothing before it. This qualifier does not name the constraint''s own schema, because a constraint has none: it is scoped to its table, which is why sql_constraint takes its schema through the table. It names which schema''s table holds the constraint, disambiguating a constraint name that occurs in more than one, and the resolver reads it that way. Which namespace that is is dialect-dependent (the schema namespace in Oracle, the table namespace in PostgreSQL), which is why the column is not called a schema part';
COMMENT ON COLUMN graphitron_reference_for_step.key_ref_name_part IS 'right of key_ref''s first period, or the whole value when none; joins sql_constraint.constraint_name narrowed by the source table the walk is standing on, not by this row alone';
COMMENT ON COLUMN graphitron_reference_for_step.class_name IS 'the fully-qualified Java class name as written';
COMMENT ON COLUMN graphitron_reference_for_step.method IS 'the Java method name as written';
COMMENT ON COLUMN graphitron_reference_for_step.arg_mapping IS 'the argMapping string as written; the pair child is its decode';
COMMENT ON COLUMN graphitron_reference_for_step.table_ref_namespace_part_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against sql_table''s schema and name. Generated, so nothing writes it and nothing can. It exists because an authored spelling meets a catalog name here, which is the only reason anything in this schema is folded';
COMMENT ON COLUMN graphitron_reference_for_step.table_ref_name_part_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against sql_table''s schema and name. Generated, so nothing writes it and nothing can. It exists because an authored spelling meets a catalog name here, which is the only reason anything in this schema is folded';
COMMENT ON COLUMN graphitron_reference_for_step.key_ref_namespace_part_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against sql_constraint.table_schema_upper, the schema of the table holding the constraint. Generated, so nothing writes it and nothing can. It exists because an authored spelling meets a catalog name here, which is the only reason anything in this schema is folded';
COMMENT ON COLUMN graphitron_reference_for_step.key_ref_name_part_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against sql_constraint''s constraint_name_upper and jooq_name_upper, in that precedence. Generated, so nothing writes it and nothing can. It exists because an authored spelling meets a catalog name here, which is the only reason anything in this schema is folded';

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
COMMENT ON COLUMN graphitron_reference_for_step_arg_mapping_pair.argument_path IS 'the right side as written, and written is the whole of what this column claims: capture records the author''s spelling verbatim and resolves nothing. A dot opens the thing at that position, and what a thing opens into depends on what it is: an input object opens into its fields, and an ID carrying @nodeId opens into the key columns of the node type it names, so a trailing segment may be a key column rather than a field of any SDL type. Which of those a segment turned out to be is intent_argmapping_segment_binding''s answer and the key projection''s beside it; enumerating the forms here would be a second statement of a resolution those views own, and the enumeration this column carried before the key-column form existed was exactly that mistake caught late. graphitron_argument_path_segment holds the decomposition, so nothing splits this string';

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
COMMENT ON COLUMN graphitron_service_arg_mapping_pair.argument_path IS 'the right side as written, and written is the whole of what this column claims: capture records the author''s spelling verbatim and resolves nothing. A dot opens the thing at that position, and what a thing opens into depends on what it is: an input object opens into its fields, and an ID carrying @nodeId opens into the key columns of the node type it names, so a trailing segment may be a key column rather than a field of any SDL type. Which of those a segment turned out to be is intent_argmapping_segment_binding''s answer and the key projection''s beside it; enumerating the forms here would be a second statement of a resolution those views own, and the enumeration this column carried before the key-column form existed was exactly that mistake caught late. graphitron_argument_path_segment holds the decomposition, so nothing splits this string';

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
  table_ref_namespace_part VARCHAR,
  table_ref_name_part      VARCHAR,
  table_ref_namespace_part_upper VARCHAR GENERATED ALWAYS AS (UPPER(table_ref_namespace_part)),
  table_ref_name_part_upper      VARCHAR GENERATED ALWAYS AS (UPPER(table_ref_name_part)),
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
COMMENT ON COLUMN graphitron_mutation.table_ref IS 'the table argument as written (may carry a schema qualifier); it names the write target of a DELETE, INSERT or UPDATE, the three verbs the resolver accepts it for, and resolves through the same qualified-name route a @table binding takes';
COMMENT ON COLUMN graphitron_mutation.table_ref_namespace_part IS 'left of table_ref''s first period, NULL when no period appeared and the empty string when one appeared with nothing before it; for a table name this namespace is the SQL schema in every dialect jOOQ models. Written by capture, because splitting on a period is a decode and decodes happen there';
COMMENT ON COLUMN graphitron_mutation.table_ref_name_part IS 'right of table_ref''s first period, or the whole value when none; the empty string when a period was written with nothing after it, which joins nothing and is meant to';
COMMENT ON COLUMN graphitron_mutation.table_ref_namespace_part_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against sql_table''s schema and name. Generated, so nothing writes it and nothing can. It exists because an authored spelling meets a catalog name here, which is the only reason anything in this schema is folded';
COMMENT ON COLUMN graphitron_mutation.table_ref_name_part_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against sql_table''s schema and name. Generated, so nothing writes it and nothing can. It exists because an authored spelling meets a catalog name here, which is the only reason anything in this schema is folded';

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
  routine_ref_namespace_part VARCHAR,
  routine_ref_name_part      VARCHAR,
  arg_mapping    VARCHAR,
  column_mapping VARCHAR,
  routine_ref_namespace_part_upper VARCHAR GENERATED ALWAYS AS (UPPER(routine_ref_namespace_part)),
  routine_ref_name_part_upper      VARCHAR GENERATED ALWAYS AS (UPPER(routine_ref_name_part)),
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
COMMENT ON COLUMN graphitron_routine.routine_ref_namespace_part IS 'left of routine_ref''s first period, NULL when no period appeared and the empty string when one appeared with nothing before it; for a routine name this namespace is the SQL schema in every dialect jOOQ models as one. Written by capture, because splitting on a period is a decode and decodes happen there';
COMMENT ON COLUMN graphitron_routine.routine_ref_name_part IS 'right of routine_ref''s first period, or the whole value when none; the empty string when a period was written with nothing after it, which joins nothing and is meant to';
COMMENT ON COLUMN graphitron_routine.routine_ref_namespace_part_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against sql_table''s schema and name, a routine result being a catalog table. Generated, so nothing writes it and nothing can. It exists because an authored spelling meets a catalog name here, which is the only reason anything in this schema is folded';
COMMENT ON COLUMN graphitron_routine.routine_ref_name_part_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against sql_table''s schema and name, a routine result being a catalog table. Generated, so nothing writes it and nothing can. It exists because an authored spelling meets a catalog name here, which is the only reason anything in this schema is folded';
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
COMMENT ON COLUMN graphitron_routine_arg_mapping_pair.argument_path IS 'the right side as written, and written is the whole of what this column claims: capture records the author''s spelling verbatim and resolves nothing. A dot opens the thing at that position, and what a thing opens into depends on what it is: an input object opens into its fields, and an ID carrying @nodeId opens into the key columns of the node type it names, so a trailing segment may be a key column rather than a field of any SDL type. Which of those a segment turned out to be is intent_argmapping_segment_binding''s answer and the key projection''s beside it; enumerating the forms here would be a second statement of a resolution those views own, and the enumeration this column carried before the key-column form existed was exactly that mistake caught late. graphitron_argument_path_segment holds the decomposition, so nothing splits this string';

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
COMMENT ON TABLE graphitron_federation_key IS 'Federation @key as the author wrote it, decoded for consumption (its verbatim twin lives in graphql_type_directive for re-emission; a gate query pins agreement). Authored applications alone, which is what this family''s charter says a decode is: the key federation synthesizes for a node type is a derivation over these rows and the node metadata, and it lives in intent_synthesized_federation_key. A reader wanting every key the emitted schema carries reads intent_federation_key, which unions the two.';
COMMENT ON COLUMN graphitron_federation_key.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_federation_key.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_federation_key.ordinal IS '@key is repeatable; document order';
COMMENT ON COLUMN graphitron_federation_key.source_name IS 'the applying declaration site, which every row here has, the relation holding authored applications alone';
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
  PRIMARY KEY (graph_name, type_name, ordinal, position),
  FOREIGN KEY (graph_name, type_name, ordinal)
    REFERENCES graphitron_federation_key (graph_name, type_name, ordinal)
);
COMMENT ON TABLE graphitron_federation_key_field IS 'An ordered element of a @key field set (the field-set grammar is a parse boundary, so the decode happens at capture). One row per leaf selection, in written order, and the row is the position alone: what the selection names is the segment child, because the grammar admits nesting and a decoded grammar lands as rows rather than as a rendered string. A top-level selection is one segment, so the child is never empty. That today''s consumer rejects nesting is a detection, not a capture limit.';
COMMENT ON COLUMN graphitron_federation_key_field.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_federation_key_field.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_federation_key_field.ordinal IS 'capture-assigned position in document order';
COMMENT ON COLUMN graphitron_federation_key_field.position IS '0-based within the field set';

CREATE TABLE graphitron_federation_key_field_segment (
  graph_name       VARCHAR NOT NULL,
  type_name        VARCHAR NOT NULL,
  ordinal          INT     NOT NULL,
  position         INT     NOT NULL,
  segment_position INT     NOT NULL,
  segment_name     VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, ordinal, position, segment_position),
  FOREIGN KEY (graph_name, type_name, ordinal, position)
    REFERENCES graphitron_federation_key_field (graph_name, type_name, ordinal, position)
);
COMMENT ON TABLE graphitron_federation_key_field_segment IS 'What one @key selection names, segment by segment: the nesting the field-set parser computes, recorded rather than rendered. A reader asking which leaf a key selects, and under what parent, joins instead of splitting a dotted string, which is the whole reason the parser''s prefix stack reaches the store at all. Positions are dense from zero and a selection always has a position-zero segment, an unnested one having only that.';
COMMENT ON COLUMN graphitron_federation_key_field_segment.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_federation_key_field_segment.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_federation_key_field_segment.ordinal IS 'the owning @key application''s ordinal';
COMMENT ON COLUMN graphitron_federation_key_field_segment.position IS 'the owning selection''s 0-based position within the field set';
COMMENT ON COLUMN graphitron_federation_key_field_segment.segment_position IS '0-based position of the segment within the selection, dense from zero; position zero names a field of the type the @key sits on, and each further position descends into the one before it';
COMMENT ON COLUMN graphitron_federation_key_field_segment.segment_name IS 'the segment itself, one name carrying no dot; what a reader would otherwise have recovered by splitting a path';

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
-- The connection expansion's own record: which graphql_ rows it added, and the written expression
-- where it rewrote one. Synthesized rows inherit the causing application's source position; these
-- relations are what say a position means "caused here" rather than "written here".
--
-- Both residents are @asConnection's, and the family is closed at that. A macro qualifies to run in
-- capture only when its contribution is a function of one carrier's own declaration, reading nothing
-- outside the SDL corpus. @asConnection qualifies: its element type enters as a name and nothing
-- reads the type that name resolves to. Two do not, for two different reasons. @asFacet reads
-- through the carrier's arguments into the filter input type's fields, so it is an aggregate over
-- the whole schema rather than a local expansion, which is why no FACET value appears here. And
-- federation's key synthesis fires on nodehood, which conjoins the SDL claim with metadata a
-- generated jOOQ class publishes: a second corpus, so the rule is a derivation
-- (intent_synthesized_federation_key) and its rows are their own provenance, which is why no
-- FEDERATION_KEY value appears here either and why this family holds no relation about @key.
--
-- What survives here is what a derived relation cannot hold. A macro that adds a declaration site
-- adds it to graphql_type_declaration, because a minted type has to be a type every reader of the
-- transcription sees; that addition is marked rather than excluded. And a macro that rewrites a
-- captured value overwrites one, so the authored expression survives only in the relation that
-- stashed it. Both are the cost of an expansion running inside the walk, and both are payable
-- exactly because @asConnection reads one corpus.
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
  CHECK (macro IN ('CONNECTION'))
);
COMMENT ON TABLE graphitron_type_declaration_synthesis IS 'A declaration site was contributed by a macro rather than the author: a definition site when the macro creates the type (Connection, Edge, facet shapes, at merge_ordinal 0), and an empty extension site when a later carrier touches a shared machinery type (PageInfo), so carrier multiplicity is the site count. Synthesized element rows hang off these sites through the ordinary declaration reference, which is what marks additions without per-element provenance; a type is synthesized exactly when its merge_ordinal-0 site is.';
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
COMMENT ON TABLE graphitron_field_synthesis IS 'A field''s type expression was rewritten by a macro; the expression the field was written with survives here while the field''s graphql_field row holds the expansion''s result.';
COMMENT ON COLUMN graphitron_field_synthesis.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN graphitron_field_synthesis.type_name IS 'the GraphQL type this row is about';
COMMENT ON COLUMN graphitron_field_synthesis.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN graphitron_field_synthesis.macro IS 'which expansion rewrote the type expression';
COMMENT ON COLUMN graphitron_field_synthesis.authored_type_sdl IS 'the type expression as the author wrote it, pre-expansion';


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
  table_type   VARCHAR NOT NULL,
  jooq_name    VARCHAR NOT NULL,
  class_fqn    VARCHAR NOT NULL,
  record_class_fqn VARCHAR NOT NULL,
  description  VARCHAR,
  table_schema_upper VARCHAR GENERATED ALWAYS AS (UPPER(table_schema)),
  table_name_upper   VARCHAR GENERATED ALWAYS AS (UPPER(table_name)),
  PRIMARY KEY (source_name, table_schema, table_name),
  FOREIGN KEY (source_name) REFERENCES store_source (source_name),
  FOREIGN KEY (source_name, table_schema) REFERENCES sql_schema (source_name, table_schema)
);
COMMENT ON TABLE sql_table IS 'A table exists in the consumer''s catalog. Every table jOOQ''s generated model declares, across every schema it declares; ambiguity of an unqualified @table(name:) is a resolution question and therefore derivation, so capture just records them all.';
COMMENT ON COLUMN sql_table.source_name IS 'the generated package the table''s schema lives in; the partition this row belongs to and the key''s leading dimension, so two modules'' catalogs carrying one (schema, table) coordinate coexist instead of the second build clobbering the first. The package rather than the classpath entry it was loaded from, because one jar carries every schema a codegen run produced and invalidating the jar would discard them all, while the package is the granularity codegen actually rewrites. Schemas flattened into one package (jOOQ''s outputSchemaToDefault) share a source, which is correct: they are regenerated together';
COMMENT ON COLUMN sql_table.table_schema IS 'SQL schema the table lives in';
COMMENT ON COLUMN sql_table.table_name IS 'SQL table name';
COMMENT ON COLUMN sql_table.table_type IS 'what kind of table-like object this is, in jOOQ''s TableOptions.TableType vocabulary: TABLE, TEMPORARY, VIEW, MATERIALIZED_VIEW, FUNCTION, EXPRESSION or UNKNOWN. A property of the object every catalog states and this family recorded none of, so the store could not tell a base table from a view. FUNCTION is the value that carries weight today: it marks a table-valued function''s result, which has no primary key and no foreign keys by construction, and that one property is what every carve-out on a function-backed field turns on. A reader asking whether a name is table-valued asks this column rather than reaching back into the live catalog. The callable behind a FUNCTION row is its own subject in sql_routine, joined on the shared (source, schema, name).';
COMMENT ON COLUMN sql_table.class_fqn IS 'the fully qualified name of the generated jOOQ table class, read off the live Table during the catalog walk. Per table, unlike the Keys class name, which is per schema and lives on sql_schema. Goto-definition on @table(name:) and @field(name:) lands in this class, and jvm_class cannot supply it because that family deliberately excludes the generated jOOQ package, so this is the join key that reaches generated sources at all.';
COMMENT ON COLUMN sql_table.record_class_fqn IS 'the fully qualified name of the record class jOOQ binds this table''s rows to, read off the live Table during the catalog walk. A different fact from class_fqn beside it, and neither spells the other: that is the generated table class an author navigates to, this is the row type a producer method hands back, and the naming relation between them is jOOQ codegen configuration rather than anything the store may assume. Always present, a table always having a row type; a table jOOQ generated no record class for reports org.jooq.Record, which is the catalog''s own answer and stands as written, a reader that wants only generated records comparing against that name rather than reading a NULL. The classpath census cannot supply this, excluding the generated jOOQ package by design, so it is how a type bound to a table reaches a class name at all.';
COMMENT ON COLUMN sql_table.jooq_name IS 'the generated jOOQ Java field name for the table; under a family named for SQL this is the one foreign column, so the prefix marks it rather than leaving a reader to infer it';
COMMENT ON COLUMN sql_table.description IS 'the database comment on the table, when present';
COMMENT ON COLUMN sql_table.table_schema_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against the namespace half of an authored table or routine reference. Generated, so nothing writes it and nothing can. Fold only where an authored spelling meets a catalog name. Two values of one family are compared exactly, and a comparison that does want a fold on both sides reaches this column by joining sql_table on its key rather than by having it forwarded through a derived view';
COMMENT ON COLUMN sql_table.table_name_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against the name half of an authored table or routine reference, and against graphitron_table.type_name_upper where the name argument was omitted. Generated, so nothing writes it and nothing can. Fold only where an authored spelling meets a catalog name. Two values of one family are compared exactly, and a comparison that does want a fold on both sides reaches this column by joining sql_table on its key rather than by having it forwarded through a derived view. intent_field_reference_discovery is the worked example of that second sentence: both of its table names are catalog values, so it joins this relation twice on its key to compare them here';

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
  column_name_upper VARCHAR GENERATED ALWAYS AS (UPPER(column_name)),
  jooq_name_upper   VARCHAR GENERATED ALWAYS AS (UPPER(jooq_name)),
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
COMMENT ON COLUMN sql_column.column_name_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against an authored column reference or the field name standing in for one. Generated, so nothing writes it and nothing can. Fold only where an authored spelling meets a catalog name. Two values of one family are compared exactly, and a comparison that does want a fold on both sides reaches this column by joining sql_column on its key rather than by having it forwarded through a derived view. intent_name_matched_key_pair is the worked example of that second sentence: both of its column names are catalog values, so it reaches a key column''s fold through the foreign key sql_constraint_column already declares here';
COMMENT ON COLUMN sql_column.jooq_name_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against an authored column reference or the field name standing in for one, which is the tier tried before the SQL name. Generated, so nothing writes it and nothing can. Fold only where an authored spelling meets a catalog name. Two values of one family are compared exactly, and a comparison that does want a fold on both sides reaches this column by joining sql_column on its key rather than by having it forwarded through a derived view';

CREATE TABLE sql_constraint (
  source_name     VARCHAR NOT NULL,
  table_schema    VARCHAR NOT NULL,
  table_name      VARCHAR NOT NULL,
  constraint_name VARCHAR NOT NULL,
  constraint_type VARCHAR NOT NULL,
  jooq_name       VARCHAR,
  table_schema_upper    VARCHAR GENERATED ALWAYS AS (UPPER(table_schema)),
  constraint_name_upper VARCHAR GENERATED ALWAYS AS (UPPER(constraint_name)),
  jooq_name_upper       VARCHAR GENERATED ALWAYS AS (UPPER(jooq_name)),
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
COMMENT ON COLUMN sql_constraint.table_schema_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against the namespace half of an authored key reference, which names the schema of the table holding the constraint rather than any schema of the constraint''s own. Generated, so nothing writes it and nothing can. Fold only where an authored spelling meets a catalog name. Two values of one family are compared exactly, and a comparison that does want a fold on both sides reaches this column by joining sql_constraint on its key rather than by having it forwarded through a derived view';
COMMENT ON COLUMN sql_constraint.constraint_name_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against the name half of an authored key reference. Generated, so nothing writes it and nothing can. Fold only where an authored spelling meets a catalog name. Two values of one family are compared exactly, and a comparison that does want a fold on both sides reaches this column by joining sql_constraint on its key rather than by having it forwarded through a derived view';
COMMENT ON COLUMN sql_constraint.jooq_name_upper IS 'the upper-cased form of the column beside it, for the case-insensitive match against the name half of an authored key reference, eligible only where no SQL constraint name answers it. NULL where jooq_name is, which is the constraint that resolves to no constant and therefore matches no reference. Generated, so nothing writes it and nothing can. Fold only where an authored spelling meets a catalog name. Two values of one family are compared exactly, and a comparison that does want a fold on both sides reaches this column by joining sql_constraint on its key rather than by having it forwarded through a derived view';

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

CREATE TABLE sql_routine (
  source_name          VARCHAR NOT NULL,
  table_schema         VARCHAR NOT NULL,
  routine_name         VARCHAR NOT NULL,
  routine_type         VARCHAR NOT NULL,
  routines_class_fqn   VARCHAR,
  routines_method_name VARCHAR,
  PRIMARY KEY (source_name, table_schema, routine_name),
  FOREIGN KEY (source_name) REFERENCES store_source (source_name),
  FOREIGN KEY (source_name, table_schema) REFERENCES sql_schema (source_name, table_schema)
);
COMMENT ON TABLE sql_routine IS 'A callable exists in the consumer''s catalog. Its own subject rather than columns on sql_table, on the rule that names this family: the standard the family is named for separates ROUTINES from TABLES, and a routine''s parameters are a fact about the callable, never about a result. The population is what makes that more than pedantry. A routine with no RETURNS TABLE form has a callable and no table at all, so parameters hung off sql_table would have nowhere to go the moment the walk reads one. This takes sql_constraint''s shape for the same reason: a supertype discriminated by type, with the forms an iteration does not yet read arriving as further routine_type values rather than as a reshaping. Today the walk reads jOOQ''s table census, and a table-valued function is the one routine form that appears in it, so every row here is currently a function that also has a sql_table row; whether a routine is table-valued is that join (a FUNCTION-typed sql_table row at the same coordinate), not a column here. The key is inherited from sql_table''s and carries its one hole with it: an overload set sharing a SQL name collides in both relations, jOOQ distinguishing overloads only by generated class name.';
COMMENT ON COLUMN sql_routine.source_name IS 'the owning partition''s generated-package source, as on sql_table; the key''s leading dimension';
COMMENT ON COLUMN sql_routine.table_schema IS 'SQL schema the routine lives in';
COMMENT ON COLUMN sql_routine.routine_name IS 'SQL routine name; for a table-valued function this is also its sql_table row''s table_name, the two being one database object read two ways';
COMMENT ON COLUMN sql_routine.routine_type IS 'the standard''s ROUTINE_TYPE vocabulary: FUNCTION or PROCEDURE. Single-valued today, every captured routine reaching the store through the table census and therefore being a function; it is the discriminator that lets the other form arrive without reshaping, which is the whole argument for a supertype relation';
COMMENT ON COLUMN sql_routine.routines_class_fqn IS 'the fully qualified name of the generated Routines class carrying this routine''s call surface, or NULL when the generated model exposes none. Captured beside the method name because the parameters below are a fact about one method: jOOQ generates several forms per routine (a Configuration-first execute form, a value-parameter form, a Field-expression form), and a parameter list that did not name its method would not say which one it described. NULL here and on the method name is also what distinguishes a routine with no parameters from one whose call surface the generated model does not expose, the two being the same zero rows in sql_routine_parameter otherwise.';
COMMENT ON COLUMN sql_routine.routines_method_name IS 'the Routines-class method the parameters below describe: the value-parameter form, the one an emitted FROM clause calls. NULL exactly when routines_class_fqn is';

CREATE TABLE sql_routine_parameter (
  source_name  VARCHAR NOT NULL,
  table_schema VARCHAR NOT NULL,
  routine_name VARCHAR NOT NULL,
  position     INT     NOT NULL,
  jooq_name    VARCHAR NOT NULL,
  binding_type VARCHAR NOT NULL,
  PRIMARY KEY (source_name, table_schema, routine_name, position),
  FOREIGN KEY (source_name, table_schema, routine_name)
    REFERENCES sql_routine (source_name, table_schema, routine_name)
);
COMMENT ON TABLE sql_routine_parameter IS 'An ordered IN parameter of a routine''s call surface. Under a family written in SQL''s vocabulary this relation carries none of it, and that is a finding rather than an omission: for a table-valued function jOOQ generates no Routine object at all, only the result table class and the Routines convenience method, so the database''s own parameter names survive only as jOOQ''s camelCase transform of them and the SQL types only as anonymous bind placeholders behind a protected field on TableImpl. Both columns were left out rather than shipped always-null or reached for through a field another module never opened.';
COMMENT ON COLUMN sql_routine_parameter.source_name IS 'the owning partition''s generated-package source, as on sql_table; the key''s leading dimension';
COMMENT ON COLUMN sql_routine_parameter.table_schema IS 'SQL schema the routine lives in';
COMMENT ON COLUMN sql_routine_parameter.routine_name IS 'SQL routine name';
COMMENT ON COLUMN sql_routine_parameter.position IS '0-based position in the call surface''s parameter list, which is the routine''s declaration order';
COMMENT ON COLUMN sql_routine_parameter.jooq_name IS 'the generated method parameter''s Java name, read reflectively; jOOQ''s camelCase transform of the database''s own parameter name, and the closest this relation gets to it. Reflection reports it only when the consumer compiled their jOOQ output with -parameters, and reports arg0, arg1 otherwise. The generator already depends on that flag, matching @routine(argMapping:) against these names, so recording the name makes an existing dependency visible rather than creating one.';
COMMENT ON COLUMN sql_routine_parameter.binding_type IS 'the fully qualified Java type the generated method takes at this position, as on sql_column.binding_type. Unlike a column, a parameter carries no sql_type beside it; the relation''s own comment says why';

CREATE TABLE sql_node_metadata (
  source_name       VARCHAR NOT NULL,
  table_schema      VARCHAR NOT NULL,
  table_name        VARCHAR NOT NULL,
  type_id_form      VARCHAR NOT NULL,
  type_id           VARCHAR,
  type_id_class     VARCHAR,
  key_columns_form  VARCHAR NOT NULL,
  key_columns_class VARCHAR,
  PRIMARY KEY (source_name, table_schema, table_name),
  FOREIGN KEY (source_name, table_schema, table_name)
    REFERENCES sql_table (source_name, table_schema, table_name),
  CHECK (type_id_form IN ('STRING', 'NULL', 'OTHER', 'ABSENT')),
  CHECK (key_columns_form IN ('FIELD_ARRAY', 'NULL', 'OTHER', 'ABSENT')),
  CHECK ((type_id IS NOT NULL) = (type_id_form = 'STRING')),
  CHECK ((type_id_class IS NOT NULL) = (type_id_form = 'OTHER')),
  CHECK ((key_columns_class IS NOT NULL) = (key_columns_form = 'OTHER')),
  CHECK (NOT (type_id_form = 'ABSENT' AND key_columns_form = 'ABSENT'))
);
COMMENT ON TABLE sql_node_metadata IS 'A generated jOOQ table class states node-identity metadata: the two static constants Sikt''s KjerneJooqGenerator emits on a table it treats as a node, transcribed as stated rather than as validated. A row exists exactly when the class declares either constant, so a table with no row publishes neither, and a class declaring only half the pair is a row with the other half''s ABSENT form rather than the silence the live reflection probe folds it into. Whether what the class stated is well-formed is not asked here: that is intent_node_metadata_defect, a derivation over these rows and sql_column, which is what keeps the crawler''s job transcription. Under the sql_ family because the constants ride on the same generated package sql_table partitions on, refreshed in the same clearing round by the same walk, and sql_table.class_fqn already commits this family to facts about the generated classes; a family boundary here would cut one refresh unit in half.';
COMMENT ON COLUMN sql_node_metadata.source_name IS 'the owning partition''s generated-package source, as on sql_table; the key''s leading dimension';
COMMENT ON COLUMN sql_node_metadata.table_schema IS 'SQL schema the table lives in';
COMMENT ON COLUMN sql_node_metadata.table_name IS 'SQL table name. With the two columns above this is sql_table''s full key: the metadata is a property of the table rather than of the class, which is why the key is the table''s and not a class name';
COMMENT ON COLUMN sql_node_metadata.type_id_form IS 'what the type-id constant stated, in a closed taxonomy the reading side''s own discrimination fixes: STRING when it held a String, NULL when it held null, OTHER when it held anything else, ABSENT when the class declares no such constant. The empty string is STRING like any other, its emptiness being a judgement the derivation makes rather than a shape capture recognises';
COMMENT ON COLUMN sql_node_metadata.type_id IS 'the stated value, exactly when type_id_form is STRING, empty string included; NULL otherwise, which the form column tells apart from a stated null';
COMMENT ON COLUMN sql_node_metadata.type_id_class IS 'the stated value''s runtime class, fully qualified, exactly when type_id_form is OTHER; NULL otherwise. The class name and deliberately not a rendering of the value: an arbitrary object''s toString may carry an identity hash, and a column that varied between two reads of one classpath would fail the warm-and-cold agreement sweep this relation sits under';
COMMENT ON COLUMN sql_node_metadata.key_columns_form IS 'what the key-columns constant stated, on the same terms as type_id_form: FIELD_ARRAY when it held an array of jOOQ fields, NULL when it held null, OTHER when it held anything else, ABSENT when the class declares no such constant. Child rows exist exactly under FIELD_ARRAY, so an empty array is that form with no children rather than a flag of its own';
COMMENT ON COLUMN sql_node_metadata.key_columns_class IS 'the stated value''s runtime class, fully qualified, exactly when key_columns_form is OTHER; NULL otherwise, on the same determinism ground as type_id_class';

CREATE TABLE sql_node_key_column (
  source_name  VARCHAR NOT NULL,
  table_schema VARCHAR NOT NULL,
  table_name   VARCHAR NOT NULL,
  position     INT     NOT NULL,
  column_name  VARCHAR,
  PRIMARY KEY (source_name, table_schema, table_name, position),
  FOREIGN KEY (source_name, table_schema, table_name)
    REFERENCES sql_node_metadata (source_name, table_schema, table_name)
);
COMMENT ON TABLE sql_node_key_column IS 'An ordered entry of the key-columns constant, as stated. Deliberately no foreign key to sql_column: the constant spells a column by name and may spell one the table does not have, which is exactly the state worth recording, and the schema''s own rule puts a foreign key only where the walk writes the child while standing on the parent, never on a reference an author spells by name. The crawler stands on the table. Whether an entry resolves is intent_node_metadata_defect''s question.';
COMMENT ON COLUMN sql_node_key_column.source_name IS 'the owning partition''s generated-package source, as on sql_table; the key''s leading dimension';
COMMENT ON COLUMN sql_node_key_column.table_schema IS 'SQL schema the table lives in';
COMMENT ON COLUMN sql_node_key_column.table_name IS 'SQL table name';
COMMENT ON COLUMN sql_node_key_column.position IS '0-based index in the stated array, recorded rather than reconstructed: the encoded identity depends on the declared order, so a reader that recovered the order from the table''s columns or from a key would encode different ids than the ones already issued. Dense from zero within a parent, and present only under a FIELD_ARRAY parent, both gated';
COMMENT ON COLUMN sql_node_key_column.column_name IS 'the name the entry states, as jOOQ reports it for the field; NULL exactly when the array entry itself is null, which is a stated fact about the entry rather than an absence of one. Resolution against the table''s own columns is the derivation''s business, and it matches the reading side: case-insensitively, against the generated Java name or the SQL name';


-- ==== JVM classpath facts =========================================================
-- What the classfiles on the compile classpath declare, in the JVM's vocabulary: classes, the
-- supertypes they name, methods and their parameters, record components, scalar-type fields, and
-- the classes each declared type names at each of its positions.
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

CREATE TABLE jvm_method_return_type_ref (
  source_name      VARCHAR NOT NULL,
  class_name       VARCHAR NOT NULL,
  method_name      VARCHAR NOT NULL,
  descriptor       VARCHAR NOT NULL,
  type_path        VARCHAR NOT NULL,
  referenced_class VARCHAR NOT NULL,
  variance         VARCHAR NOT NULL,
  PRIMARY KEY (source_name, class_name, method_name, descriptor, type_path),
  FOREIGN KEY (source_name, class_name, method_name, descriptor)
    REFERENCES jvm_method (source_name, class_name, method_name, descriptor),
  CHECK (variance IN ('NONE', 'EXTENDS', 'SUPER'))
);
COMMENT ON TABLE jvm_method_return_type_ref IS 'The classes a method''s declared return type names, one row per position in the type. The census''s other type columns are display forms with the package dropped, which is what they were added for and what makes them unusable for identity: a walk following a return type has to tell org.jooq.Result from another package''s Result, and that is the collision jvm_method.descriptor exists to avoid at the method level. This relation is where a declared type becomes resolvable. It decomposes rather than qualifies because a declared type is a tree and not a name: Map<String, List<Film>> names four classes at four positions, and a single qualified column could answer for the outermost only, leaving the element type (which is the position a walk is actually after) still unresolvable. The rows are read off the classfile Signature attribute where one is present and off the descriptor where it is not, which is the same rule the declared display columns follow; a non-generic method carries no Signature attribute at all, so the descriptor reading is the common case rather than a fallback. The path grammar and the omission rules are stated on the type_path and referenced_class columns and hold for all three type-reference relations.';
COMMENT ON COLUMN jvm_method_return_type_ref.source_name IS 'the owning class''s classpath entry, as on jvm_class; the key''s leading dimension';
COMMENT ON COLUMN jvm_method_return_type_ref.class_name IS 'the fully-qualified Java class name as written';
COMMENT ON COLUMN jvm_method_return_type_ref.method_name IS 'the owning method name';
COMMENT ON COLUMN jvm_method_return_type_ref.descriptor IS 'the owning method''s raw JVM descriptor';
COMMENT ON COLUMN jvm_method_return_type_ref.type_path IS 'the position within the declared type, as a dot-separated sequence of steps read outside in. The empty string is the type itself; a digit is a 0-based type-argument index; `[]` is an array''s component. So List<Film> names its element at `0`, Map<String, List<Film>> names Film at `1.0`, and Film[] names Film at `[]`. A path descends only through positions the source wrote, so it is stable against anything the erasure does';
COMMENT ON COLUMN jvm_method_return_type_ref.referenced_class IS 'the fully-qualified binary name of the class named at this position, a nested one spelled with the $ the JVM uses. Deliberately not a foreign key, on the same terms as jvm_class_supertype.supertype_name: the scan drops nested classes and the generated jOOQ package and nothing ships the JDK, so a named class frequently has no census row and that is the ordinary case. A position naming no class has no row rather than a row with a placeholder, which covers a primitive, an array (whose component is the next step down), a type variable, and an unbounded wildcard. The type-variable case is the one worth stating twice: the erasure reads Object where the declaration names nothing, and this relation follows the declaration, so a method returning T has a return_type of Object and no row here';
COMMENT ON COLUMN jvm_method_return_type_ref.variance IS 'NONE, EXTENDS or SUPER: the wildcard bound the position was written with, NONE where the source named the type directly. Carried rather than dropped because the three declare different things and the class name alone cannot tell them apart, so a consumer peeling an element type out of `? super Film` would otherwise read it as `Film` and be silently wrong about which direction the values flow. Always NONE at the root, a wildcard being a type-argument form';

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

CREATE TABLE jvm_method_parameter_type_ref (
  source_name      VARCHAR NOT NULL,
  class_name       VARCHAR NOT NULL,
  method_name      VARCHAR NOT NULL,
  descriptor       VARCHAR NOT NULL,
  position         INT     NOT NULL,
  type_path        VARCHAR NOT NULL,
  referenced_class VARCHAR NOT NULL,
  variance         VARCHAR NOT NULL,
  PRIMARY KEY (source_name, class_name, method_name, descriptor, position, type_path),
  FOREIGN KEY (source_name, class_name, method_name, descriptor, position)
    REFERENCES jvm_method_parameter (source_name, class_name, method_name, descriptor, position),
  CHECK (variance IN ('NONE', 'EXTENDS', 'SUPER'))
);
COMMENT ON TABLE jvm_method_parameter_type_ref IS 'The classes a parameter''s declared type names, one row per position, on exactly the terms jvm_method_return_type_ref states. Captured with its siblings rather than deferred to a first consumer, unlike the parameter-source question this relation''s owner defers: that one is decided per directive application and genuinely belongs to a later keying axis, where this is the same decomposition of the same declared form by the same rule, and a census that resolved a return type but not a parameter type would be answering an accident rather than a question. Where the method''s signature and descriptor disagree on argument count the parameter rows fall back to the erasure wholesale, and these rows follow that reading, so they decompose whatever the parameter row itself reports.';
COMMENT ON COLUMN jvm_method_parameter_type_ref.source_name IS 'the owning class''s classpath entry, as on jvm_class; the key''s leading dimension';
COMMENT ON COLUMN jvm_method_parameter_type_ref.class_name IS 'the fully-qualified Java class name as written';
COMMENT ON COLUMN jvm_method_parameter_type_ref.method_name IS 'the owning method name';
COMMENT ON COLUMN jvm_method_parameter_type_ref.descriptor IS 'the owning method''s raw JVM descriptor';
COMMENT ON COLUMN jvm_method_parameter_type_ref.position IS 'the owning parameter''s 0-based position, as on jvm_method_parameter; a parameter position, which type_path''s digits are not';
COMMENT ON COLUMN jvm_method_parameter_type_ref.type_path IS 'the position within the declared type; the grammar is stated on jvm_method_return_type_ref.type_path';
COMMENT ON COLUMN jvm_method_parameter_type_ref.referenced_class IS 'the fully-qualified binary name of the class named at this position; the omission rules are stated on jvm_method_return_type_ref.referenced_class';
COMMENT ON COLUMN jvm_method_parameter_type_ref.variance IS 'NONE, EXTENDS or SUPER, as on jvm_method_return_type_ref.variance';

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

CREATE TABLE jvm_record_component_type_ref (
  source_name      VARCHAR NOT NULL,
  class_name       VARCHAR NOT NULL,
  component_name   VARCHAR NOT NULL,
  type_path        VARCHAR NOT NULL,
  referenced_class VARCHAR NOT NULL,
  variance         VARCHAR NOT NULL,
  PRIMARY KEY (source_name, class_name, component_name, type_path),
  FOREIGN KEY (source_name, class_name, component_name)
    REFERENCES jvm_record_component (source_name, class_name, component_name),
  CHECK (variance IN ('NONE', 'EXTENDS', 'SUPER'))
);
COMMENT ON TABLE jvm_record_component_type_ref IS 'The classes a record component''s declared type names, one row per position, on exactly the terms jvm_method_return_type_ref states. Its own relation beside its owner rather than a shared one discriminated by a member-kind column, which is the same reading the walk reach relations take: the three owners are three keys, a method return being keyed by a descriptor, a parameter adding a position and a component named on its own, so one relation would carry a column that is NULL by kind and could carry no foreign key at all. A reader whose question is uniform across the owners (the accessor hop, which stands on a member slot and does not care which arm produced it) unions them in a view, which is the layer where a reader''s question belongs.';
COMMENT ON COLUMN jvm_record_component_type_ref.source_name IS 'the owning class''s classpath entry, as on jvm_class; the key''s leading dimension';
COMMENT ON COLUMN jvm_record_component_type_ref.class_name IS 'the fully-qualified Java class name as written';
COMMENT ON COLUMN jvm_record_component_type_ref.component_name IS 'the owning record component''s name, as on jvm_record_component';
COMMENT ON COLUMN jvm_record_component_type_ref.type_path IS 'the position within the declared type; the grammar is stated on jvm_method_return_type_ref.type_path';
COMMENT ON COLUMN jvm_record_component_type_ref.referenced_class IS 'the fully-qualified binary name of the class named at this position; the omission rules are stated on jvm_method_return_type_ref.referenced_class';
COMMENT ON COLUMN jvm_record_component_type_ref.variance IS 'NONE, EXTENDS or SUPER, as on jvm_method_return_type_ref.variance';

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
-- What the legacy classification walk registered and what it bound, in the walk's own
-- vocabulary. The writer is the capture-and-detect pass, at capture cadence, inside the
-- capture's graph-scoped ownership; a run without that pass writes no rows, and the warm
-- refresh clears the graph's partition with the rest of its ownership scope. No foreign key
-- into the graphql_ family on purpose: the writer stands on the walked model, not on captured
-- rows, and the walk's registries legitimately hold coordinates capture spells differently
-- (tombstones included).
--
-- The membership grains (walk_claim_domain_type, walk_claim_domain_field): the walked model's
-- type and field registries, one relation per grain in the claim views' own mould so neither
-- carries a column that is NULL by kind. They exist so the conflict detection's domain gate is
-- a join instead of a Java membership test: the walk's reach is narrower than capture's
-- (capture is total, with no reachability pruning), and the exemption populations the demand
-- exemption census recorded never reached a legacy detector, so an ungated detection would move
-- the accept line exactly there. The gate dissolves when the detection reads the resolved
-- demand relation instead of the walk's reach (the gate-flip follow-up's work), which drains
-- those two.
--
-- The binding grain (walk_type_backing_class): what the walk resolved each registered type's
-- backing class to. It is here so the derivation that replaces the walk's backing resolution has
-- a differential inside the store rather than a total-agreement test in Java: two relations in
-- one store diff over any corpus a run touches, can be compared while the derivation is half
-- built, and drain themselves. A total agreement test instead makes the walk normative and pins
-- whatever bugs it has as invariants, which is the shape this relation exists to avoid.
--
-- The family retires with the walk whose reach it transcribes, but its relations drain on
-- separate clocks, which is why none of them carries a foreign key into another even where one
-- population is contained in another (every bound type is a registered type). A constraint
-- across two clocks makes the earlier drainage impossible while a sibling still writes. The
-- containment is asserted where both populations are projected from one walked model instead
-- (no.sikt.graphitron.rewrite.derive.TypeBackingClassesTest), which is the only place it can be
-- checked without the constraint.
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

CREATE TABLE walk_type_backing_class (
  graph_name VARCHAR NOT NULL,
  type_name  VARCHAR NOT NULL,
  class_name VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
COMMENT ON TABLE walk_type_backing_class IS 'What the legacy classification walk resolved a type''s backing class to: one row per registered type the walk bound to a Java class, and no row for a type it left unbacked. The differential intent_type_backing_class checks itself against while it is built; see the family header for the writer, the cadence, and why the relation stands alone. Three populations are deliberately absent, each because another relation already owns it. A @table-bound type is absent: the walk answers it with a table rather than a class, and that population is intent_bound_table''s, so a second transcription here would be a duplicate with worse provenance. A type two producers bound to different classes is absent: the walk resolves that disagreement by refusing to bind at all, which is the population the derivation surfaces as two rows plus a conflict view, so the shadow''s silence there is a recorded behaviour difference and not a defect on either side. And the kind of backing (record, plain class, jOOQ record) is absent as a column, because it is a property of the class the census already states, and re-transcribing it would carry the leaf taxonomy this relation exists to dissolve into the relation replacing it.';
COMMENT ON COLUMN walk_type_backing_class.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN walk_type_backing_class.type_name IS 'the SDL type the walk bound; always a member of walk_claim_domain_type, unconstrained for the reason the family header gives. One row per type rather than per type and axis: an SDL name is an output type or an input type and never both, so the walk''s two-axis bookkeeping is internal to it and not a dimension of the answer';
COMMENT ON COLUMN walk_type_backing_class.class_name IS 'the binary name of the class the walk bound the type to, spelled as the jvm_ census spells a class name so the two join without normalising; a class the census never reached is still a row, since what the walk resolved is the fact here and whether the scan saw it is a separate question';

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
-- rows derive on read from the transcription relations above, so capture writes nothing here and
-- a claim can never drift stale against the applications it is derived from. Underneath the
-- classifier views sit the resolutions they stand on, residents in their own right rather than
-- CTEs inside their first reader: intent_bound_table answers which catalog table a type's @table
-- binds to, which the column-match classifier asks on its way to a claim and an editor asks with
-- no claim in view. Those resolutions layer among themselves on the same rule. intent_spelled_table
-- answers the rule every table name is subject to whatever site wrote it, so the binding view is a
-- keying over it rather than a second copy of it; intent_field_reference_step_hop and
-- intent_field_reference_step_target then split a @reference path into its local element
-- resolutions and the chain that walks them, because only the chain needs recursion and mixing the
-- two would put a copy of every element arm inside the recursive term. intent_field_chain_terminus
-- sits one layer above those: a @routine chain starts at a function result rather than at a type's
-- binding, so it walks the same hops from its own seed and answers where the chain lands and what
-- kind of table that is, which is the question every read-surface axis on a routine-backed field
-- turns out to be asking. That terminus is also a binding: the type such a field returns is bound
-- to the table the chain lands on, which is the fact @table on a routine's return type states by
-- hand today, so intent_routine_return_binding derives it and intent_resolved_type_binding is where
-- it meets the @table population. The reduction is where the readers point, because what a reader
-- of a binding asks is which table stands for the type and never which rule found it; the two
-- populations stay separate relations because each derives by its own rule from its own facts.
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
-- The stratum's fourth resident group is the class-backing chain, which answers which Java class
-- a type is backed by and is a chain rather than a relation because that question decomposes.
-- intent_declared_type_ref names the census's declared types under one owner key and
-- intent_declared_type_element peels a declared type down to the class it delivers; those two are
-- about classes alone and carry the census's key. intent_class_member_slot and
-- intent_class_member_element read the peel at a slot's own owner, intent_field_producer_method
-- resolves an authored Java reference to a census method, and intent_field_accessor_hop states one
-- edge of the binding walk: for a coordinate and a class its parent might stand on, where the hop
-- lands. intent_type_backing_class is the closure over those edges from the producer-grounded
-- seeds, materialized because the SDL type graph is cyclic, with intent_type_backing_conflict
-- naming the types the closure answers more than one way. The decomposition is the point: the walk
-- being replaced carried the grounding, the peel, the hop, the cardinality reading and a table
-- shadow as clauses of one procedure, and each of those is a fact with its own grain and its own
-- anchor here.
--
-- A resolution is keyed by whatever its own question is about, which is why not every resident
-- leads with graph_name. intent_class_member_slot asks what member names a class offers, a rule
-- over the classpath census with no graph in it, so it carries the census's key and a graph
-- reaches it through store_graph_source like any other source-keyed fact. Keying it by graph
-- would have made one copy of the answer per graph that reads the class, which is a claim about
-- the graph the rule never makes. Which stratum a row belongs to is decided by what its value is
-- a function of, and never by which key it happens to carry: a row recomputable from captured
-- facts alone is a derived fact, whatever produced it. That is what puts this resident here. The
-- transcription families hold what a walk read, and a bean-accessor rule over the classpath census
-- is not something any walk read.
--
-- intent_name_matched_key_pair is the same shape one family over, and it shows what the keying buys
-- besides economy. Whether a table-valued function's result can be keyed to a table at all is a
-- question about a catalog, so the relation carries no graph either; and because it does not, the
-- two consumers that ask it can find their two ends in completely different places.
-- intent_field_reference_step_hop finds them in an authored path element, and
-- intent_carrier_routine_hop finds them in the shape of a mutation payload whose data field
-- authored nothing. Keyed by a coordinate, the rule would have had to be written once per kind of
-- coordinate that asks, which is the duplication the generator carries today and the reason this
-- relation exists.

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
          FROM (SELECT graph_name, COALESCE(table_ref, type_name) AS spelling,
                       table_ref_namespace_part_upper AS namespace_part_upper,
                       COALESCE(table_ref_name_part_upper, type_name_upper) AS name_part_upper
                  FROM graphitron_table
                 UNION
                SELECT graph_name, table_ref,
                       table_ref_namespace_part_upper, table_ref_name_part_upper
                  FROM graphitron_field_reference_step
                 WHERE table_ref IS NOT NULL
                 UNION
                SELECT graph_name, table_ref,
                       table_ref_namespace_part_upper, table_ref_name_part_upper
                  FROM graphitron_argument_reference_step
                 WHERE table_ref IS NOT NULL
                 UNION
                SELECT graph_name, table_ref,
                       table_ref_namespace_part_upper, table_ref_name_part_upper
                  FROM graphitron_reference_for_step
                 WHERE table_ref IS NOT NULL
                 UNION
                SELECT graph_name, table_ref,
                       table_ref_namespace_part_upper, table_ref_name_part_upper
                  FROM graphitron_mutation
                 WHERE table_ref IS NOT NULL
                 UNION
                SELECT graph_name, routine_ref,
                       routine_ref_namespace_part_upper, routine_ref_name_part_upper
                  FROM graphitron_routine) s
          JOIN store_graph_source m ON m.graph_name = s.graph_name
          JOIN sql_table st ON st.source_name = m.source_name
           AND st.table_name_upper = s.name_part_upper
           AND (s.namespace_part_upper IS NULL
                OR st.table_schema_upper = s.namespace_part_upper)) resolved;
COMMENT ON VIEW intent_spelled_table IS 'How a written table name resolves against the catalog census: one row per candidate table, keyed on the spelling itself rather than on any one site that wrote it. A spelling arrives already split, capture having written the two halves of it beside the value, so this view reads a partition rather than performing one: a qualified spelling binds both halves and an unqualified one, whose namespace half is null, matches on its name half alone. Both sides of both comparisons are stored folded columns, which is what makes the match an equality an index can serve instead of a fold computed per candidate row. The catalog side scopes through store_graph_source so a sibling graph''s tables never resolve here. Keyed on the spelling because the rule does not vary by site: @table(name:), a @reference path element''s table, its argument-site and @referenceFor siblings, @mutation''s delete target and @routine(name:) all name a table the same way, and a resolution with several askers is a relation rather than a subquery repeated in each of them. The routine name is in that list because jOOQ models a table-valued function''s result as a catalog table like any other, so the name an author writes in @routine(name:) is a table spelling and resolves under this rule with nothing routine-specific about it; what makes the resolved row a function rather than a stored table is sql_table.table_type, which a reader that means the function form filters on and this view does not, its job being the spelling and not the kind. The population is therefore every spelling this graph authors anywhere, including graphitron_table''s type-name fallback, which is a spelling by the time resolution sees it. Ambiguity is rows, never a decline: a name two schemas both declare is two rows and candidates says so, leaving the reading to the reader.';
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
COMMENT ON VIEW intent_bound_table IS 'Which catalog table an @table-bearing type is bound to: graphitron_table''s reference resolved through intent_spelled_table, one row per candidate table. The reference is the name argument as written, or the type name where the argument was omitted, which is the derivation graphitron_table.table_ref''s own comment defers; how a spelling then meets the census is the spelling view''s rule, stated once there and not restated here. What this view adds over that one is the keying: a type, not a string, which is what every reader of a binding actually holds. The three root names are masked, transcribing the walk''s root short-circuit that classifies a root before any table binding is read, which is the same mask the authored type claims carry. A base derivation rather than a resolved_ reduction: it stands directly on a transcription pair and nothing reduces over sibling views to produce it. It is one arm of such a reduction, intent_resolved_type_binding coalescing it with the binding a @routine chain''s return derives, and that is where a reader asking which table stands for a type points; this relation answers the narrower question of what the author wrote @table for, which is what an editor locating a written site and any reader of the directive population itself asks. Ambiguity is rows, never a decline: two candidates are two rows and the count says so, so a reader can transcribe the walk''s Ambiguous verdict (require candidates = 1, as the column-match classifier does), offer every candidate (as an editor does, since each is a table the author might mean), or report the ambiguity, without any of them re-spelling the resolution. The reference as written and the application''s position are one join back to graphitron_table, which holds both.';
COMMENT ON COLUMN intent_bound_table.graph_name IS 'the owning graph''s partition, carried from graphitron_table';
COMMENT ON COLUMN intent_bound_table.type_name IS 'the @table-bearing type whose binding this row resolves';
COMMENT ON COLUMN intent_bound_table.table_source_name IS 'the resolved table''s catalog partition, the first column of the sql_table key this row names';
COMMENT ON COLUMN intent_bound_table.table_schema IS 'the resolved table''s SQL schema; what tells two candidates of one name apart';
COMMENT ON COLUMN intent_bound_table.table_name IS 'the resolved table''s SQL name. With the two columns above this is sql_table''s full key; the table''s other facts (its jOOQ name, its generated class, its comment) are one join away, per the referenced-side discipline sql_referential_constraint states';
COMMENT ON COLUMN intent_bound_table.candidates IS 'how many tables the reference resolves to, this row being one of them; 1 on an unambiguous binding. Carried through from the spelling view rather than recounted here, and stated as a column rather than left to each reader''s own count, because whether a binding is ambiguous decides the reading (a claim declines, an editor offers every candidate) and a reader that counted for itself would be re-deriving the resolution''s own arity';

CREATE VIEW intent_name_matched_key_pair
  (from_source_name, from_schema, from_table,
   to_source_name, to_schema, to_table,
   position, to_column, from_column, unmatched_columns) AS
SELECT from_source_name, from_schema, from_table,
       to_source_name, to_schema, to_table,
       position, to_column, from_column,
       CAST(COUNT(CASE WHEN from_column IS NULL THEN 1 END) OVER (
              PARTITION BY from_source_name, from_schema, from_table,
                           to_source_name, to_schema, to_table) AS INT)
  FROM (SELECT fn.source_name AS from_source_name, fn.table_schema AS from_schema,
               fn.table_name AS from_table,
               pk.source_name AS to_source_name, pk.table_schema AS to_schema,
               pk.table_name AS to_table,
               kc.position, kc.column_name AS to_column, fc.column_name AS from_column
          FROM sql_table fn
         CROSS JOIN sql_primary_key pk
          JOIN sql_constraint_column kc
            ON kc.source_name = pk.source_name AND kc.table_schema = pk.table_schema
           AND kc.table_name = pk.table_name AND kc.constraint_name = pk.constraint_name
          JOIN sql_column kcc
            ON kcc.source_name = kc.source_name AND kcc.table_schema = kc.table_schema
           AND kcc.table_name = kc.table_name AND kcc.column_name = kc.column_name
          LEFT JOIN sql_column fc
            ON fc.source_name = fn.source_name AND fc.table_schema = fn.table_schema
           AND fc.table_name = fn.table_name
           AND fc.column_name_upper = kcc.column_name_upper
         WHERE fn.table_type = 'FUNCTION') matched;
COMMENT ON VIEW intent_name_matched_key_pair IS 'How a hop out of a table-valued function''s result is keyed: for every function result and every table with a primary key, that key''s columns paired with the function''s own columns of the same name. A function result declares no foreign key, so a join leaving one has no constraint to read and the only rule available is the column name, which is the rule the generator applies at both of the seats that leave one. Catalog only, and deliberately so. Which two tables a hop actually connects is a question about a schema, and this relation answers the question underneath it: whether those two tables can be keyed to each other at all. That is why it carries no graph partition and gates on no directive, leaving the graph scope to its consumers exactly as intent_class_assignable leaves its own to store_graph_source, and it is what lets one relation serve consumers that find their endpoints in different places. Matching is case-insensitive on the column name, as the resolver compares them. Both names are catalog values, so neither side is folded for this comparison''s sake: the function''s column carries a fold because an authored reference meets it elsewhere, and the key column reaches the same fold through the foreign key sql_constraint_column already declares to sql_column. That is the schema''s rule for a comparison inside one family, and it is why this view joins a relation it appears not to need. The match is against the arrival''s primary key alone; a unique constraint is not a candidate here, the generator matching primary-key columns and nothing else. A shortfall is rows rather than absence: every key column of the arrival gets a row whether or not the function exposes it, so a consumer keying a join demands unmatched_columns = 0 and a consumer reporting why it cannot names the columns whose from_column is NULL, which is what the diagnostic at either seat has to say. That is the discipline the ambiguity columns state, applied to a match that came up short rather than to one that came up plural. The pairs carry the key''s own position because a consumer building a key tuple has to build it in the key''s order, and a set would send it back to the constraint to recover one. Nothing here says a pair is meaningful: two tables that name-match are not thereby connected, and every consumer reaches this relation already holding the two ends from somewhere that does say so.';
COMMENT ON COLUMN intent_name_matched_key_pair.from_source_name IS 'the departing function result''s catalog partition. Separate from the arrival''s rather than shared, because the consumers resolve their two ends independently and a graph reading two jOOQ sources can reach a table in either; the pairing is a comparison of column names, and names do not stop matching at a partition boundary';
COMMENT ON COLUMN intent_name_matched_key_pair.from_schema IS 'the departing function result''s SQL schema';
COMMENT ON COLUMN intent_name_matched_key_pair.from_table IS 'the departing function result''s SQL name. With the two columns above this is sql_table''s full key, and table_type is FUNCTION on it by construction';
COMMENT ON COLUMN intent_name_matched_key_pair.to_source_name IS 'the arriving table''s catalog partition';
COMMENT ON COLUMN intent_name_matched_key_pair.to_schema IS 'the arriving table''s SQL schema';
COMMENT ON COLUMN intent_name_matched_key_pair.to_table IS 'the arriving table''s SQL name; a table with a primary key, one without having nothing to name-match and contributing no rows at all';
COMMENT ON COLUMN intent_name_matched_key_pair.position IS 'the key column''s position within the arriving table''s primary key, carried from sql_constraint_column; the order a consumer building the key tuple has to build it in';
COMMENT ON COLUMN intent_name_matched_key_pair.to_column IS 'the arriving table''s primary-key column at this position: the target side of the pair, and the column a diagnostic names when the function does not expose it';
COMMENT ON COLUMN intent_name_matched_key_pair.from_column IS 'the function result''s column of the same name, spelled as the function spells it, which is the source side of the pair. NULL where the function exposes no column of that name, which is the shortfall this relation states as a row rather than as a missing one';
COMMENT ON COLUMN intent_name_matched_key_pair.unmatched_columns IS 'how many of the arriving key''s columns this function does not expose; 0 on a pairing a consumer can take whole. Stated as a column rather than left to each reader''s count, for the reason the arity columns elsewhere are: whether the pairing is total decides the reading, a consumer keying a join demanding 0 and a consumer explaining a refusal reading the rows behind a number above it';

CREATE VIEW intent_node_metadata_defect
  (source_name, table_schema, table_name, defect, position) AS
SELECT source_name, table_schema, table_name, 'TYPE_ID_NOT_DECLARED', CAST(NULL AS INT)
  FROM sql_node_metadata WHERE type_id_form = 'ABSENT'
 UNION ALL
SELECT source_name, table_schema, table_name, 'TYPE_ID_NULL', CAST(NULL AS INT)
  FROM sql_node_metadata WHERE type_id_form = 'NULL'
 UNION ALL
SELECT source_name, table_schema, table_name, 'TYPE_ID_WRONG_TYPE', CAST(NULL AS INT)
  FROM sql_node_metadata WHERE type_id_form = 'OTHER'
 UNION ALL
SELECT source_name, table_schema, table_name, 'TYPE_ID_EMPTY', CAST(NULL AS INT)
  FROM sql_node_metadata WHERE type_id_form = 'STRING' AND type_id = ''
 UNION ALL
SELECT source_name, table_schema, table_name, 'KEY_COLUMNS_NOT_DECLARED', CAST(NULL AS INT)
  FROM sql_node_metadata WHERE key_columns_form = 'ABSENT'
 UNION ALL
SELECT source_name, table_schema, table_name, 'KEY_COLUMNS_NULL', CAST(NULL AS INT)
  FROM sql_node_metadata WHERE key_columns_form = 'NULL'
 UNION ALL
SELECT source_name, table_schema, table_name, 'KEY_COLUMNS_WRONG_TYPE', CAST(NULL AS INT)
  FROM sql_node_metadata WHERE key_columns_form = 'OTHER'
 UNION ALL
SELECT m.source_name, m.table_schema, m.table_name, 'KEY_COLUMNS_EMPTY', CAST(NULL AS INT)
  FROM sql_node_metadata m
 WHERE m.key_columns_form = 'FIELD_ARRAY'
   AND NOT EXISTS (SELECT 1 FROM sql_node_key_column k
                    WHERE k.source_name = m.source_name AND k.table_schema = m.table_schema
                      AND k.table_name = m.table_name)
 UNION ALL
SELECT k.source_name, k.table_schema, k.table_name, 'KEY_COLUMN_ENTRY_NULL', k.position
  FROM sql_node_key_column k
 WHERE k.column_name IS NULL
 UNION ALL
SELECT k.source_name, k.table_schema, k.table_name, 'KEY_COLUMN_UNRESOLVED', k.position
  FROM sql_node_key_column k
 WHERE k.column_name IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM sql_column c
                    WHERE c.source_name = k.source_name AND c.table_schema = k.table_schema
                      AND c.table_name = k.table_name
                      AND (UPPER(c.jooq_name) = UPPER(k.column_name)
                           OR UPPER(c.column_name) = UPPER(k.column_name)));
COMMENT ON VIEW intent_node_metadata_defect IS 'What is wrong with the node-identity metadata a generated table class stated: one row per defect, over the sql_node_metadata rows and their entries alone. The rows it reads are transcription because a walk read them; this is not, because no walk read the verdict that metadata is malformed. Graphitron''s rule produces it, and every join is inside the jOOQ corpus, which is what makes it a derivation over one corpus rather than a validation smuggled into a crawler. Well-formed metadata is a sql_node_metadata row with no defect rows, and the conjunction is the whole test: no defect rows alone is also what a table publishing nothing at all has, that table having no metadata row to be well-formed. Every defect a table exhibits gets a row, with no first-failing short-circuit, so no evaluation order becomes normative; a reader wanting one message reduces by an ordering it owns. There is no reason-text column, deliberately: the closed defect vocabulary plus the witness columns already stored are the fact base, and message prose belongs with the consumer that composes it. Keyed on the catalog''s own key with no graph partition, as intent_name_matched_key_pair is: the question is about a table, and a graph reaches it the way it reaches any source-keyed fact.';
COMMENT ON COLUMN intent_node_metadata_defect.source_name IS 'the table''s catalog partition, the first column of the sql_node_metadata key this row is about';
COMMENT ON COLUMN intent_node_metadata_defect.table_schema IS 'the table''s SQL schema';
COMMENT ON COLUMN intent_node_metadata_defect.table_name IS 'the table''s SQL name; with the two columns above, the metadata row this defect is about';
COMMENT ON COLUMN intent_node_metadata_defect.defect IS 'which defect, in a closed vocabulary of ten: TYPE_ID_NOT_DECLARED, TYPE_ID_NULL, TYPE_ID_WRONG_TYPE and TYPE_ID_EMPTY on the type-id constant; KEY_COLUMNS_NOT_DECLARED, KEY_COLUMNS_NULL, KEY_COLUMNS_WRONG_TYPE and KEY_COLUMNS_EMPTY on the key-columns constant; KEY_COLUMN_ENTRY_NULL and KEY_COLUMN_UNRESOLVED on one entry of it. The vocabulary is finer than the reflection probe it will eventually replace, which reports one message per constant however that constant went wrong: the store distinguishes the states because it holds the forms separately, and collapsing them here to match the probe would discard a distinction the rows already carry';
COMMENT ON COLUMN intent_node_metadata_defect.position IS 'the offending entry''s index in the stated array, on the two per-entry defects; NULL on the eight that are about a whole constant, which is the stated absent bucket rather than a missing value';

CREATE VIEW intent_inferred_node_type
  (graph_name, type_name, table_source_name, table_schema, table_name) AS
SELECT b.graph_name, b.type_name, b.table_source_name, b.table_schema, b.table_name
  FROM intent_bound_table b
  JOIN sql_node_metadata m
    ON m.source_name = b.table_source_name AND m.table_schema = b.table_schema
   AND m.table_name = b.table_name
 WHERE b.candidates = 1
   AND EXISTS (SELECT 1 FROM graphql_implements i
                WHERE i.graph_name = b.graph_name AND i.type_name = b.type_name
                  AND i.interface_name = 'Node')
   AND NOT EXISTS (SELECT 1 FROM intent_node_metadata_defect d
                    WHERE d.source_name = m.source_name
                      AND d.table_schema = m.table_schema
                      AND d.table_name = m.table_name);
COMMENT ON VIEW intent_inferred_node_type IS 'A type nobody wrote @node on that is a node anyway: an @table binding, an implements Node, and a bound table whose generated class publishes well-formed node-identity metadata. The inferred half of nodehood, kept a relation of its own rather than a tagged arm of the membership reduction, per the provenance rule the schema applies throughout: authored and inferred values reached by independent rules live in separate relations coalesced by a view, and a tag column no reader forks on is inventory. What earns this one its place independently of that reduction is the witness columns, which say which table''s metadata made the type a node; a reader asking that question joins instead of re-deriving the binding. Well-formedness is the conjunction intent_node_metadata_defect''s own comment states, a metadata row with no defect rows for it, and not the anti-join alone: a table publishing nothing at all also has no defect rows, and that table is not a node. The membership is a cross-corpus join, which is the licensed shape for a derivation precisely because no crawler may perform one. Its binding is intent_bound_table, the @table arm alone, deliberately and not the intent_resolved_type_binding reduction the sibling spelling below stands on: nodehood demands a written @table, so a type whose only binding is a routine chain''s return is not a node however well-formed that table''s metadata is. Its sibling spelling is intent_resolved_node_key_column''s JOOQ_METADATA tier, which carries the same well-formed-metadata conjunction on that other stand; the duplication is stated rather than latent, so a reader who finds one finds the pair, and folding the conjunction into a relation of its own on sql_table''s key is the follow-on neither view performs on its own. Standing on intent_bound_table it inherits intent_spelled_table''s window function, so an outer predicate cannot prune it: a caller filtering on one type still resolves every spelling in the graph.';
COMMENT ON COLUMN intent_inferred_node_type.graph_name IS 'the owning graph''s partition, carried from the binding';
COMMENT ON COLUMN intent_inferred_node_type.type_name IS 'the type this row infers nodehood for; keyed with the graph, one row per inferred node type';
COMMENT ON COLUMN intent_inferred_node_type.table_source_name IS 'the metadata-publishing table''s catalog partition, the first column of the sql_table key this row names; a witness, so the reader that asks which table made this a node joins rather than re-deriving the binding';
COMMENT ON COLUMN intent_inferred_node_type.table_schema IS 'the metadata-publishing table''s SQL schema';
COMMENT ON COLUMN intent_inferred_node_type.table_name IS 'the metadata-publishing table''s SQL name. With the two columns above this is sql_table''s full key, and the sql_node_metadata row this inference read sits on that same key';

CREATE VIEW intent_node_type (graph_name, type_name) AS
SELECT graph_name, type_name FROM graphitron_node
 UNION
SELECT graph_name, type_name FROM intent_inferred_node_type;
COMMENT ON VIEW intent_node_type IS 'Which of a graph''s types are node types: the union of the authored @node population and the inferred one. The store''s answer to the question NodeDeclaration.isNodeType answers live, and the relation every reader of nodehood joins instead of restating the rule. A declaration-level answer, matching that predicate: @node without implements Node still reads as a node here, and rejecting that shape is the classifier''s job rather than this relation''s, since a membership relation that silently dropped a declared node would leave a detection with nothing to detect. The predicate''s declared-wins short-circuit needs no transcription: a UNION dedupes, so precedence dissolves along with the provenance column that would have asked for it, and a reader wanting to know which rule answered reads the arm, both arms being residents in their own right. Inference is a cross-corpus join and lands in the arm that performs it, which is what keeps the SDL crawlers writing rows about the SDL alone.';
COMMENT ON COLUMN intent_node_type.graph_name IS 'the owning graph''s partition, carried from whichever arm produced the row';
COMMENT ON COLUMN intent_node_type.type_name IS 'the node type; keyed with the graph, one row per node type however many arms answered for it';

CREATE VIEW intent_synthesized_federation_key
  (graph_name, type_name, fields_sdl, resolvable) AS
SELECT n.graph_name, n.type_name, 'id', TRUE
  FROM intent_node_type n
 WHERE EXISTS (SELECT 1 FROM graphitron_link l
                WHERE l.graph_name = n.graph_name
                  AND l.url LIKE 'https://specs.apollo.dev/federation/%')
   AND NOT EXISTS (SELECT 1 FROM graphitron_federation_key k
                    WHERE k.graph_name = n.graph_name AND k.type_name = n.type_name
                      AND 1 = (SELECT COUNT(*) FROM graphitron_federation_key_field f
                                WHERE f.graph_name = k.graph_name
                                  AND f.type_name = k.type_name AND f.ordinal = k.ordinal)
                      AND 1 = (SELECT COUNT(*) FROM graphitron_federation_key_field_segment s
                                WHERE s.graph_name = k.graph_name
                                  AND s.type_name = k.type_name AND s.ordinal = k.ordinal)
                      AND EXISTS (SELECT 1 FROM graphitron_federation_key_field_segment s
                                   WHERE s.graph_name = k.graph_name
                                     AND s.type_name = k.type_name AND s.ordinal = k.ordinal
                                     AND s.segment_name = 'id'));
COMMENT ON VIEW intent_synthesized_federation_key IS 'Federation''s node-entity rule as a relation: which node types get a @key(fields: "id") nobody wrote, because federation needs the entity declaration visible in the emitted SDL and a node carries a globally-unique id by definition. A derivation and not a capture: the rule reads the SDL claim rows and the node metadata a generated class publishes, so its inputs span two corpora and its output is computable from captured facts, which is what puts it in this stratum rather than in the walk that used to run it. The three conditions are the live rule''s. The graph is federation-linked, which is a predicate over graphitron_link.url as graphitron_link''s own comment says, and the decode rather than the verbatim twin: reading the argument value out of graphql_schema_directive_arg would mean compensating for AST quoting, which is exactly the string surgery a decoded relation exists to retire. A url the author omitted is a null and matches nothing, which is the live predicate''s null guard falling out of the join. The type is a node, by intent_node_type. And no authored key already states the id contract, meaning no @key application on the type whose decode is exactly the single path id: one field row, one segment, and that segment named id. Positions are dense from zero in both children, so the two counts pin the shape without naming a position. That transcribes the live rule including its deliberate asymmetry, a malformed fields: argument decoding to no field rows and therefore not counting as the id key, so the misuse reaches its detection instead of suppressing synthesis on the strength of a parse failure; compound and other-field keys likewise do not count, being additional alternatives rather than the id contract. The rule''s constants appear here, in SQL, rather than in a comment each composing reader re-mints from: fields_sdl is the field-set literal the rule would have written and resolvable is true. The federation-spec prefix is a third spelling beside the two Java readers that share the constant, and is pinned to it by a named test rather than by a shared literal, a view being unable to bind a query parameter. This relation is its own provenance, which is what lets the synthesized application leave the transcription families entirely: nothing marks a synthesized row in graphql_type_directive because no synthesized row lands there.';
COMMENT ON COLUMN intent_synthesized_federation_key.graph_name IS 'the owning graph''s partition, carried from the membership relation';
COMMENT ON COLUMN intent_synthesized_federation_key.type_name IS 'the node type the key is synthesized for; keyed with the graph, one row per type that gets one';
COMMENT ON COLUMN intent_synthesized_federation_key.fields_sdl IS 'the field-set literal the rule states, always id; a column and not an implied constant, so a reader composing this arm with the authored one projects the same shape from both';
COMMENT ON COLUMN intent_synthesized_federation_key.resolvable IS 'the resolvable: the rule states, always true; the synthesized entity is resolvable by construction, an opt-out being something only an author can write';

CREATE VIEW intent_federation_key
  (graph_name, type_name, ordinal, fields_sdl, resolvable) AS
SELECT graph_name, type_name, ordinal, fields_sdl, resolvable
  FROM graphitron_federation_key
 UNION ALL
SELECT graph_name, type_name, CAST(NULL AS INT), fields_sdl, resolvable
  FROM intent_synthesized_federation_key;
COMMENT ON VIEW intent_federation_key IS 'Every @key a graph''s emitted schema carries, authored and synthesized alike: the composition two readers already ask for, the round trip that re-emits the applications and the agreement anchor that pins the derivation against the pipeline''s registry rewrite. A relation rather than a union each of them assembles for itself, on the rule that a composition with a second asker is a relation. The grain is the authored relation''s, with a NULL ordinal on the synthesized arm rather than an invented one: document order is a property of something the author wrote, and a derived row has no position in a document. UNION ALL and not UNION, deliberately. The authored arm is unique on its own key already and the synthesized arm cannot collide with it, its condition being that no authored id key exists, so deduplication could only ever fold together rows a reader wants told apart: two authored @key(fields: "id") applications at distinct ordinals are two rows here, which is the arity the authored relation states and this reduction owes its readers. Key grain only. The path and segment children stay authored-only until a reader asks for them composed, the synthesized arm''s single id path being recoverable from fields_sdl by the same rule that would have decoded it. A reader wanting a total order over both arms orders the authored rows by ordinal and appends the derived one, which is the ordering the composing query owns rather than one this relation invents.';
COMMENT ON COLUMN intent_federation_key.graph_name IS 'the owning graph''s partition, carried from whichever arm produced the row';
COMMENT ON COLUMN intent_federation_key.type_name IS 'the type the key sits on';
COMMENT ON COLUMN intent_federation_key.ordinal IS 'the authored application''s position in document order; NULL on a synthesized row, which is the stated absent bucket rather than a missing value, a derived row having no document position and the type''s declaration site being one join away';
COMMENT ON COLUMN intent_federation_key.fields_sdl IS 'the field-set literal: as written on an authored row, and the rule''s own id on a synthesized one';
COMMENT ON COLUMN intent_federation_key.resolvable IS 'as written on an authored row, NULL where the author omitted it; always true on a synthesized one';

CREATE VIEW intent_field_reference_step_hop
  (graph_name, type_name, field_name, ordinal, position, via, key_matched_by,
   from_source_name, from_schema, from_table,
   to_source_name, to_schema, to_table, constraint_name, fk_on_from) AS
SELECT s.graph_name, s.type_name, s.field_name, s.ordinal, s.position, 'KEY',
       CASE WHEN c.constraint_name_upper = s.key_ref_name_part_upper
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
   AND CASE WHEN s.key_ref_namespace_part IS NOT NULL
        THEN c.table_schema_upper = s.key_ref_namespace_part_upper
         AND c.constraint_name_upper = s.key_ref_name_part_upper
        ELSE c.constraint_name_upper = s.key_ref_name_part_upper
          OR (c.jooq_name_upper = s.key_ref_name_part_upper
              AND NOT EXISTS (SELECT 1
                                FROM sql_constraint c2
                                JOIN store_graph_source m2
                                  ON m2.source_name = c2.source_name
                               WHERE m2.graph_name = s.graph_name
                                 AND c2.constraint_name_upper = s.key_ref_name_part_upper))
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
        OR rc.table_schema <> rc.referenced_schema OR rc.table_name <> rc.referenced_table)
UNION ALL
SELECT s.graph_name, s.type_name, s.field_name, s.ordinal, s.position, 'NAME_MATCH', NULL,
       fn.source_name, fn.table_schema, fn.table_name,
       sp.table_source_name, sp.table_schema, sp.table_name, NULL, CAST(NULL AS BOOLEAN)
  FROM graphitron_field_reference_step s
  JOIN intent_spelled_table sp
    ON sp.graph_name = s.graph_name AND sp.spelling = s.table_ref
  JOIN store_graph_source m ON m.graph_name = s.graph_name
  JOIN sql_table fn ON fn.source_name = m.source_name AND fn.table_type = 'FUNCTION'
 WHERE s.table_ref IS NOT NULL AND s.key_ref IS NULL
   AND EXISTS (
         SELECT 1 FROM intent_name_matched_key_pair p
          WHERE p.from_source_name = fn.source_name AND p.from_schema = fn.table_schema
            AND p.from_table = fn.table_name
            AND p.to_source_name = sp.table_source_name AND p.to_schema = sp.table_schema
            AND p.to_table = sp.table_name
            AND p.unmatched_columns = 0);
COMMENT ON VIEW intent_field_reference_step_hop IS 'One @reference path element''s local resolution: every table-to-table hop the element could express, before anything decides which table the chain has actually arrived at. Both arms of authored navigation are here. A key element resolves its constraint name the way the generator''s resolver does: a leading qualifier, split off by capture and stored beside the value, binds hard, an unqualified name matches the SQL constraint name, and only where no SQL constraint in this graph''s sources answers that name does the generated Keys-class constant become eligible, which is the resolver''s namespace precedence rather than a looser match on either. That qualifier does not name the constraint''s own schema, a constraint having none of its own; it names which schema''s table holds it, which is why it binds against the constraint''s table_schema and not against anything the constraint itself is namespaced by. A table element resolves its spelling through intent_spelled_table and pins the arriving side to it, leaving the foreign key to be discovered. A table element has a second resolution beside that one, for the departure a foreign key cannot describe: a table-valued function''s result declares no constraints, so a hop leaving one is keyed by matching the arriving table''s primary-key column names against the columns the function exposes, which is the rule the generator applies there and the only one available. That arm pins the arriving side to the spelling exactly as the foreign-key arm does, and enumerates as candidate departures every FUNCTION-typed table in the graph''s sources that intent_name_matched_key_pair pairs wholly to the arrival. The pairing rule lives there rather than here because this arm is not its only asker, a carrier''s inferred hop reaching it from a coordinate that authored no element; what this arm contributes is the two ends, and it demands only that the pairing come up total. An arrival with no primary key has nothing to match and yields none, which is the same shortfall the generator reports in the name-match vocabulary rather than in the foreign-key one, and the columns behind a shortfall are rows on that relation for a reader that has to name them. The two table arms cannot produce the same row, a function result declaring no foreign key for the other arm to discover. Both foreign-key arms enumerate the hop in both orientations, because a foreign key is a hop in either direction and which one an element means depends on where the chain stands; a self-referential key is one hop and not two, since both orientations land on the same table and the walk''s cardinality hint chooses join columns rather than a destination. Separate from intent_field_reference_step_target because the local resolution has no recursion in it: keeping the two apart is what lets that view''s recursive term be a single join instead of a copy of these arms.';
COMMENT ON COLUMN intent_field_reference_step_hop.graph_name IS 'the owning graph''s partition, carried from graphitron_field_reference_step';
COMMENT ON COLUMN intent_field_reference_step_hop.type_name IS 'the type owning the field the @reference is applied to';
COMMENT ON COLUMN intent_field_reference_step_hop.field_name IS 'the field the @reference is applied to';
COMMENT ON COLUMN intent_field_reference_step_hop.ordinal IS 'the owning @reference application''s ordinal, since the directive is repeatable';
COMMENT ON COLUMN intent_field_reference_step_hop.position IS 'the element''s 0-based position within its application''s path';
COMMENT ON COLUMN intent_field_reference_step_hop.via IS 'which arm resolved the element: KEY where it named a constraint, TABLE where it named a table and a foreign key connects the two, NAME_MATCH where it named a table and the departure is a function result, whose hop is keyed by column name because it declares no constraints. KEY and TABLE are the author''s two spellings; NAME_MATCH is the second reading of the table spelling rather than a third thing to write. The element''s own written form is one join back to graphitron_field_reference_step; this column is the resolution''s reading of it';
COMMENT ON COLUMN intent_field_reference_step_hop.key_matched_by IS 'for a KEY hop, which namespace answered: SQL_NAME (the SQL constraint name) or JOOQ_NAME (the generated Keys constant). NULL on a TABLE or NAME_MATCH hop, neither of which names a constraint. Makes the resolver''s namespace precedence visible data instead of a hidden pick, as the column-match claim''s own tier column does';
COMMENT ON COLUMN intent_field_reference_step_hop.from_source_name IS 'the departing table''s catalog partition, first column of its sql_table key';
COMMENT ON COLUMN intent_field_reference_step_hop.from_schema IS 'the departing table''s SQL schema';
COMMENT ON COLUMN intent_field_reference_step_hop.from_table IS 'the departing table''s SQL name; a candidate departure, not yet a fact about the chain. On a NAME_MATCH hop the candidacy is wider than on the other arms, being every function result in the graph''s sources that exposes the arrival''s key columns rather than the endpoints of one named constraint, which costs nothing until a chain says where it actually stands';
COMMENT ON COLUMN intent_field_reference_step_hop.to_source_name IS 'the arriving table''s catalog partition, first column of its sql_table key';
COMMENT ON COLUMN intent_field_reference_step_hop.to_schema IS 'the arriving table''s SQL schema';
COMMENT ON COLUMN intent_field_reference_step_hop.to_table IS 'the arriving table''s SQL name';
COMMENT ON COLUMN intent_field_reference_step_hop.constraint_name IS 'the foreign key the hop joins on, named or discovered. Its own sql_referential_constraint key is this name under whichever endpoint declares it, which fk_on_from says. NULL on a NAME_MATCH hop, which joins on no foreign key. The constraint such a hop does key by is the arriving table''s primary key, and that is left to the join rather than carried: sql_primary_key is keyed by the table, so the arriving triple this row already carries reaches it directly, and repeating it here would be the denormalisation the referenced-side discipline declines';
COMMENT ON COLUMN intent_field_reference_step_hop.fk_on_from IS 'TRUE when the departing table declares the foreign key, FALSE when the arriving one does; the hop''s direction, and what completes the constraint''s key from the two endpoint triples. NULL on a NAME_MATCH hop, where there is no foreign key to sit on either side and the direction is fixed by the arms themselves, a function result being always the departure';

CREATE VIEW intent_field_chain_terminus
  (graph_name, type_name, field_name, via, ordinal, position,
   table_source_name, table_schema, table_name, table_type, candidates) AS
WITH RECURSIVE
last_routine (graph_name, type_name, field_name, ordinal, routine_ref,
              source_name, source_line, source_column) AS (
  SELECT r.graph_name, r.type_name, r.field_name, r.ordinal, r.routine_ref,
         r.source_name, r.source_line, r.source_column
    FROM graphitron_routine r
   WHERE r.ordinal = (SELECT MAX(r2.ordinal) FROM graphitron_routine r2
                       WHERE r2.graph_name = r.graph_name
                         AND r2.type_name = r.type_name
                         AND r2.field_name = r.field_name)
),
routine_node (graph_name, type_name, field_name, ordinal,
              table_source_name, table_schema, table_name, table_type) AS (
  SELECT lr.graph_name, lr.type_name, lr.field_name, lr.ordinal,
         sp.table_source_name, sp.table_schema, sp.table_name, ft.table_type
    FROM last_routine lr
    JOIN intent_spelled_table sp
      ON sp.graph_name = lr.graph_name AND sp.spelling = lr.routine_ref
    JOIN sql_table ft
      ON ft.source_name = sp.table_source_name AND ft.table_schema = sp.table_schema
     AND ft.table_name = sp.table_name AND ft.table_type = 'FUNCTION'
),
tail (graph_name, type_name, field_name, ordinal, position, seq) AS (
  SELECT st.graph_name, st.type_name, st.field_name, st.ordinal, st.position,
         CAST(ROW_NUMBER() OVER (PARTITION BY st.graph_name, st.type_name, st.field_name
                                 ORDER BY st.ordinal, st.position) AS INT)
    FROM graphitron_field_reference_step st
    JOIN graphitron_field_reference fr
      ON fr.graph_name = st.graph_name AND fr.type_name = st.type_name
     AND fr.field_name = st.field_name AND fr.ordinal = st.ordinal
    JOIN last_routine lr
      ON lr.graph_name = fr.graph_name AND lr.type_name = fr.type_name
     AND lr.field_name = fr.field_name
   WHERE fr.source_name = lr.source_name
     AND (fr.source_line > lr.source_line
          OR (fr.source_line = lr.source_line AND fr.source_column > lr.source_column))
),
walk (graph_name, type_name, field_name, ordinal, position, seq,
      to_source_name, to_schema, to_table) AS (
  SELECT t.graph_name, t.type_name, t.field_name, t.ordinal, t.position, t.seq,
         h.to_source_name, h.to_schema, h.to_table
    FROM tail t
    JOIN routine_node n
      ON n.graph_name = t.graph_name AND n.type_name = t.type_name
     AND n.field_name = t.field_name
    JOIN intent_field_reference_step_hop h
      ON h.graph_name = t.graph_name AND h.type_name = t.type_name
     AND h.field_name = t.field_name AND h.ordinal = t.ordinal AND h.position = t.position
     AND h.from_source_name = n.table_source_name AND h.from_schema = n.table_schema
     AND h.from_table = n.table_name
   WHERE t.seq = 1
  UNION ALL
  SELECT t.graph_name, t.type_name, t.field_name, t.ordinal, t.position, t.seq,
         h.to_source_name, h.to_schema, h.to_table
    FROM walk p
    JOIN tail t
      ON t.graph_name = p.graph_name AND t.type_name = p.type_name
     AND t.field_name = p.field_name AND t.seq = p.seq + 1
    JOIN intent_field_reference_step_hop h
      ON h.graph_name = t.graph_name AND h.type_name = t.type_name
     AND h.field_name = t.field_name AND h.ordinal = t.ordinal AND h.position = t.position
     AND h.from_source_name = p.to_source_name AND h.from_schema = p.to_schema
     AND h.from_table = p.to_table
)
SELECT graph_name, type_name, field_name, via, ordinal, position,
       table_source_name, table_schema, table_name, table_type,
       CAST(COUNT(*) OVER (PARTITION BY graph_name, type_name, field_name) AS INT)
  FROM (SELECT n.graph_name, n.type_name, n.field_name, 'ROUTINE' AS via, n.ordinal,
               CAST(NULL AS INT) AS position, n.table_source_name, n.table_schema,
               n.table_name, n.table_type
          FROM routine_node n
         WHERE NOT EXISTS (SELECT 1 FROM tail t
                            WHERE t.graph_name = n.graph_name
                              AND t.type_name = n.type_name
                              AND t.field_name = n.field_name)
         UNION
        SELECT w.graph_name, w.type_name, w.field_name, 'REFERENCE', w.ordinal, w.position,
               w.to_source_name, w.to_schema, w.to_table, tt.table_type
          FROM walk w
          JOIN sql_table tt
            ON tt.source_name = w.to_source_name AND tt.table_schema = w.to_schema
           AND tt.table_name = w.to_table
         WHERE w.seq = (SELECT MAX(t.seq) FROM tail t
                         WHERE t.graph_name = w.graph_name
                           AND t.type_name = w.type_name
                           AND t.field_name = w.field_name)) terminus;
COMMENT ON VIEW intent_field_chain_terminus IS 'Where a field''s @routine chain lands, and what kind of table it lands on. The chain is the field''s @routine and @reference applications walked as one running source, and its terminus is the last node; every read-surface axis on a routine-backed field is a question about that node. Which table an ordering or a filter resolves its column names against is the terminus, and whether the terminus is a table-valued function''s result decides whether an ordering can fall back on a primary key at all, a function result having none. Both were answered per axis before this relation, each from the directives directly, which is how one property of one catalog object came to be restated as several unrelated refusals. Population: fields carrying at least one @routine. A field whose navigation is @reference alone has a terminus too and it is not this relation''s, intent_field_column_scope''s PATH_TERMINAL rule answering it from the type''s own binding; the chain arm that view is missing should read this relation rather than grow a second copy of the walk. The walk itself. Its seed is the last @routine application''s result table, resolved as any written table name is and then required to be FUNCTION-typed, which is the only kind @routine accepts. Its tail is the @reference applications written after that routine, in document order, which is a comparison of source positions and not of ordinals because the two relations number their ordinals separately, the rule graphitron_field_reference''s own comment states. Those applications'' elements are then walked one at a time through intent_field_reference_step_hop, each departing from the previous one''s arrival, exactly as intent_field_reference_step_target walks a path from a type''s binding; the hop view''s name-matched arm is what carries the first of them out of the function result, no foreign key being able to. Applications written before the routine are not walked, because they move where the chain starts and never where it ends. Absence means "not reached", as on the target view: a tail element resolving to nothing ends the walk and the field gets no row at all, rather than a row naming the last place the walk did reach, which would read as a terminus the generator will not produce. A routine name resolving to no FUNCTION-typed table is that same silence one step earlier. Ambiguity is rows, and they are landings rather than routes: an element reaching one table by three foreign keys is one row here where the hop view has three, a terminus being a place and not a join, and a reader that has to render the join reads the hop or target view where the routes are. Two schemas declaring the routine''s name is genuinely two landings, and candidates says so.';
COMMENT ON COLUMN intent_field_chain_terminus.graph_name IS 'the owning graph''s partition, carried from graphitron_routine';
COMMENT ON COLUMN intent_field_chain_terminus.type_name IS 'the type owning the field the chain is written on';
COMMENT ON COLUMN intent_field_chain_terminus.field_name IS 'the field the chain is written on';
COMMENT ON COLUMN intent_field_chain_terminus.via IS 'which node ends the chain: ROUTINE where the @routine application is the last one written, REFERENCE where a path element after it is. A property of the chain rather than of the landing, and what tells a reader whether the terminus is the function result itself or a table hopped to out of it; what kind of table it is is table_type beside it';
COMMENT ON COLUMN intent_field_chain_terminus.ordinal IS 'the terminating application''s ordinal within its own directive name, so a ROUTINE row joins back to graphitron_routine on it and a REFERENCE row to graphitron_field_reference. The coordinate a rejection about the terminus names';
COMMENT ON COLUMN intent_field_chain_terminus.position IS 'the terminating path element''s 0-based position within its application; NULL on a ROUTINE terminus, a routine application having no elements';
COMMENT ON COLUMN intent_field_chain_terminus.table_source_name IS 'the landing table''s catalog partition, the first column of the sql_table key this row names';
COMMENT ON COLUMN intent_field_chain_terminus.table_schema IS 'the landing table''s SQL schema';
COMMENT ON COLUMN intent_field_chain_terminus.table_name IS 'the landing table''s SQL name. With the two columns above this is sql_table''s full key, so the terminus''s columns, its primary key and its generated classes are each one join away';
COMMENT ON COLUMN intent_field_chain_terminus.table_type IS 'the landing''s kind, carried from sql_table: FUNCTION where the chain ends on the routine''s own result, whatever the hopped-to table declares otherwise. The column every axis over this relation actually turns on, because a function result has no primary key and no foreign keys, so an ordering there cannot fall back on a key and must be authored. Always FUNCTION on a ROUTINE row, which is worth carrying rather than leaving to the via column: a reader asks one column whichever arm answered, and the day a non-function callable reaches a chain the answer changes here instead of at every reader';
COMMENT ON COLUMN intent_field_chain_terminus.candidates IS 'how many distinct tables this field''s chain lands on, this row''s landing being one of them; 1 where the terminus is certain. Distinct landings and not routes, which is the arity a reader of a terminus needs and the reason this relation counts differently from the hop and target views; stated as a column rather than left to each reader''s own count, on intent_bound_table.candidates'' terms';

CREATE VIEW intent_routine_return_binding
  (graph_name, type_name, table_source_name, table_schema, table_name, candidates) AS
SELECT graph_name, type_name, table_source_name, table_schema, table_name,
       CAST(COUNT(*) OVER (PARTITION BY graph_name, type_name) AS INT)
  FROM (SELECT DISTINCT nt.graph_name, nt.type_name,
               ct.table_source_name, ct.table_schema, ct.table_name
          FROM intent_field_chain_terminus ct
          JOIN graphql_field f
            ON f.graph_name = ct.graph_name AND f.type_name = ct.type_name
           AND f.field_name = ct.field_name
          LEFT JOIN graphitron_field_synthesis fs
            ON fs.graph_name = f.graph_name AND fs.type_name = f.type_name
           AND fs.field_name = f.field_name
          JOIN graphql_type nt
            ON nt.graph_name = f.graph_name
           AND nt.type_name = COALESCE(
                 REPLACE(REPLACE(REPLACE(fs.authored_type_sdl, '[', ''), ']', ''), '!', ''),
                 f.named_type)
           AND nt.kind = 'OBJECT'
         WHERE NOT (EXISTS (SELECT 1 FROM graphql_root_operation r
                             WHERE r.graph_name = f.graph_name
                               AND r.type_name = f.type_name
                               AND r.operation = 'MUTATION')
                AND NOT EXISTS (SELECT 1 FROM graphitron_field_reference fr
                                 WHERE fr.graph_name = f.graph_name
                                   AND fr.type_name = f.type_name
                                   AND fr.field_name = f.field_name))) landing;
COMMENT ON VIEW intent_routine_return_binding IS 'Which catalog table a type is bound to by being what a @routine chain field returns: intent_field_chain_terminus keyed by the returned type rather than by the field. This is the binding a @routine author writes @table for today, derived instead, so the routine name is written once and the two spellings can no longer disagree. The type read is the named type with its wrappers stripped, taken off graphitron_field_synthesis where a macro rewrote the field''s type expression, so a connection-returning routine field binds its element type and not the wrapper; that is intent_field_column_scope''s named-type rule''s reading and it is stated the same way here rather than differently. OBJECT only, a landing being a row and a row standing for an object type. The population is the chain read seats, and one seat is excluded: the payload carrier, a mutation root''s @routine field carrying no @reference, whose chain rows are not what the field returns but what its data field re-reads post-commit, so a binding there would name a table for a type no table stands for. The exclusion names that seat rather than the carrier because the store holds no carrier fact yet; the seat is the classifier''s own fork, which reaches the carrier resolution exactly when the parent is the mutation root and the chain has a single node, and it narrows to the carrier itself the day a carrier relation lands. The seat exclusion costs the routine write chain nothing, that shape carrying @reference by construction. Keyed on the root operation binding and not on the literal name Mutation, the same intended-rule form the demand rules and intent_field_separate_fetch''s root arm state. Ambiguity is rows, never a decline, on intent_bound_table''s terms: two fields returning one type off different routines are two rows and candidates says so. Nothing here is masked by an author''s @table, a type carrying both being a fact about the schema and not a conflict for this relation to settle; where the two populations meet is intent_resolved_type_binding.';
COMMENT ON COLUMN intent_routine_return_binding.graph_name IS 'the owning graph''s partition, carried from the chain terminus';
COMMENT ON COLUMN intent_routine_return_binding.type_name IS 'the type this row binds: the named type of the expression the returning field was written with, its list and non-null wrappers stripped';
COMMENT ON COLUMN intent_routine_return_binding.table_source_name IS 'the landing table''s catalog partition, the first column of the sql_table key this row names';
COMMENT ON COLUMN intent_routine_return_binding.table_schema IS 'the landing table''s SQL schema; what tells two candidates of one name apart';
COMMENT ON COLUMN intent_routine_return_binding.table_name IS 'the landing table''s SQL name. With the two columns above this is sql_table''s full key, so the table''s kind, its columns and its generated record are each one join away';
COMMENT ON COLUMN intent_routine_return_binding.candidates IS 'how many distinct tables this type''s routine-returning fields land on, this row''s table being one of them; 1 on an unambiguous binding. Counted over this relation''s own rows rather than carried from the terminus, because a type two fields return is ambiguous here even where each field''s own chain lands certainly; stated as a column rather than left to each reader''s own count, on intent_bound_table.candidates'' terms';

CREATE VIEW intent_resolved_type_binding
  (graph_name, type_name, table_source_name, table_schema, table_name, candidates) AS
SELECT graph_name, type_name, table_source_name, table_schema, table_name,
       CAST(COUNT(*) OVER (PARTITION BY graph_name, type_name) AS INT)
  FROM (SELECT graph_name, type_name, table_source_name, table_schema, table_name
          FROM intent_bound_table
         UNION
        SELECT graph_name, type_name, table_source_name, table_schema, table_name
          FROM intent_routine_return_binding) bound;
COMMENT ON VIEW intent_resolved_type_binding IS 'Which catalog table stands for a graph''s type, from either population that can answer: the author''s @table binding and the return binding a @routine chain derives. One relation for the question every reader of a binding actually asks, which is what table, not which rule found it. A reduction over sibling views rather than a base derivation, which is the shape the resolved_ prefix names and the one intent_bound_table''s own comment reserves it for: each population is derived by its own rule from its own facts and neither is a special case of the other, so they stay separate relations and this is where they meet, as intent_type_backing is where the two populations answering for a class meet. How a binding was reached is deliberately not a column, on intent_type_backing_class''s terms: a type its @table and its routine return agree on is one binding, and a provenance column would key the relation by rule and hand every reader two rows where one table stands for the type, which is exactly the multiplication that would break the one-row-per-site property intent_field_column_scope stands on. A reader that wants the rule reads the arm, both arms being residents in their own right. Ambiguity is rows and there is no precedence: a type whose @table names one table and whose routine return lands on another is two rows, candidates says two, and which of them to believe is not this relation''s to decide. The arity is recounted over the union rather than carried from an arm, since a type the two arms answer differently is ambiguous here while each arm calls itself certain; on a type only @table binds, the recount equals the spelling arity that arm carries, so the population that had a binding before sees no change. Five readers take the resolution rather than the directive, each because its question is what table and not what was written: the position-0 seed of intent_field_reference_step_target, which is what lets a path depart a routine-result type whose @table the derivation makes redundant; both intent_field_column_scope rules that read a binding, which is what lets such a type''s own columns and its children''s resolve; intent_field_reference_discovery''s departing endpoint, whose arriving one already comes through that navigation; and intent_type_backing''s table arm, a routine result''s generated record standing for the type exactly as a stored table''s does. Two readers deliberately keep reading intent_bound_table, and both are stated rather than left to be noticed. intent_field_separate_fetch asks what makes a parent a table row rather than a producer-handed object, and whether a routine-return binding answers that is the record-handed precedence question its own comment records, not a substitution to make in passing. The editor''s declaration facts answer where a type is declared, which is a written site and not a resolution.';
COMMENT ON COLUMN intent_resolved_type_binding.graph_name IS 'the owning graph''s partition, carried from whichever arm produced the row';
COMMENT ON COLUMN intent_resolved_type_binding.type_name IS 'the type the table stands for';
COMMENT ON COLUMN intent_resolved_type_binding.table_source_name IS 'the resolved table''s catalog partition, the first column of the sql_table key this row names';
COMMENT ON COLUMN intent_resolved_type_binding.table_schema IS 'the resolved table''s SQL schema; what tells two candidates of one name apart';
COMMENT ON COLUMN intent_resolved_type_binding.table_name IS 'the resolved table''s SQL name. With the two columns above this is sql_table''s full key; the table''s other facts are one join away, per the referenced-side discipline sql_referential_constraint states';
COMMENT ON COLUMN intent_resolved_type_binding.candidates IS 'how many distinct tables stand for this type across both arms, this row''s table being one of them; 1 on an unambiguous binding, which is the guard a reader that must pick one applies. Recounted here rather than carried from an arm, for the reason the view comment gives';

CREATE VIEW intent_resolved_node_key_column
  (graph_name, type_name, position, column_name, tier) AS
SELECT graph_name, type_name, position, column_name, tier
  FROM (SELECT arms.graph_name, arms.type_name, arms.position, arms.column_name, arms.tier,
               DENSE_RANK() OVER (
                 PARTITION BY arms.graph_name, arms.type_name
                 ORDER BY arms.precedence) AS tier_rank
          FROM (SELECT k.graph_name, k.type_name, k.position,
                       k.column_ref AS column_name, 'SDL_PINNED' AS tier, 0 AS precedence
                  FROM graphitron_node_key_column k
                UNION ALL
                SELECT b.graph_name, b.type_name, k.position, k.column_name, 'JOOQ_METADATA', 1
                  FROM intent_resolved_type_binding b
                  JOIN sql_node_metadata m
                    ON m.source_name = b.table_source_name AND m.table_schema = b.table_schema
                   AND m.table_name = b.table_name
                  JOIN sql_node_key_column k
                    ON k.source_name = m.source_name AND k.table_schema = m.table_schema
                   AND k.table_name = m.table_name
                 WHERE b.candidates = 1
                   AND NOT EXISTS (SELECT 1 FROM intent_node_metadata_defect d
                                    WHERE d.source_name = m.source_name
                                      AND d.table_schema = m.table_schema
                                      AND d.table_name = m.table_name)
                UNION ALL
                SELECT b.graph_name, b.type_name, cc.position, cc.column_name,
                       'CATALOG_PRIMARY_KEY', 2
                  FROM intent_node_type n
                  JOIN intent_resolved_type_binding b
                    ON b.graph_name = n.graph_name AND b.type_name = n.type_name
                  JOIN sql_primary_key pk
                    ON pk.source_name = b.table_source_name AND pk.table_schema = b.table_schema
                   AND pk.table_name = b.table_name
                  JOIN sql_constraint_column cc
                    ON cc.source_name = pk.source_name AND cc.table_schema = pk.table_schema
                   AND cc.table_name = pk.table_name
                   AND cc.constraint_name = pk.constraint_name
                 WHERE b.candidates = 1) arms) picked
 WHERE tier_rank = 1;
COMMENT ON VIEW intent_resolved_node_key_column IS 'The ordered key columns a graph''s type encodes a node id from: what a @nodeId(typeName:) decode projects values into, and what an editor offers as completions after a node id opens. A reduction over the three populations that can answer, in first-tier-wins precedence, which is the resolution BuildContext.resolveTargetKeys makes with a live catalog in hand; naming it as a relation is what lets a second reader take the same answer instead of re-deriving it, the editor being that reader and the reason the view earns its place independently of any one consumer. The tiers carry ordered lists rather than independent facts per position, so the pick is by type and never by the (type, position) coordinate: one tier wins for a type and its whole list is taken. Splicing one tier''s column into another tier''s order is the transposition the resolution''s own reasoning warns about, an @node(keyColumns:) pinning an order the metadata does not share projecting columns against the order the decode returns values in, and a per-position pick is exactly how it would arrive. The pick is therefore DENSE_RANK over the tiers rather than intent_field_column_table''s ROW_NUMBER, which is the same window mechanism reading a tier rather than a row: ROW_NUMBER partitioned by the type would keep position zero and discard the rest of the winning list. An ambiguous binding resolves no key columns on the lower two tiers both. Each reaches a table through intent_resolved_type_binding, which carries candidates and declines to pick between a @table and a routine return that name different tables, and two candidate tables are two different key tuples: picking one would encode ids against a table the author never named. Only the pinned-SDL tier survives it, graphitron_node_key_column being keyed by graph and type and needing no table to answer. A type whose binding is ambiguous and whose keyColumns are unpinned therefore has no row, and naming that ambiguity is the detection stratum''s job rather than this relation''s. Absence means no tier answered, which is what the resolution reports as an error rather than a default. Whether a resolved name is a column the table actually has is deliberately not asked here: the pinned tier answers without a table at all, so a name that resolves against nothing is a row here and a detection elsewhere, on intent_node_metadata_defect''s terms for the tier it reads. The JOOQ_METADATA tier''s sibling spelling is intent_inferred_node_type, which asks the same well-formed-stated-metadata question on a different stand, the @table binding alone rather than this reduction, for the reason that view''s comment gives; the two carry the conjunction twice and name each other so a reader who finds one finds the pair, and extracting it into a relation on sql_table''s own key is the follow-on neither performs unilaterally.';
COMMENT ON COLUMN intent_resolved_node_key_column.graph_name IS 'the owning graph''s partition, carried from whichever tier answered';
COMMENT ON COLUMN intent_resolved_node_key_column.type_name IS 'the graph type whose node key this row is one column of';
COMMENT ON COLUMN intent_resolved_node_key_column.position IS '0-based position within the key, dense from zero, in the order the winning tier states; the order the encoded identity depends on, which is why the pick keeps a tier''s list whole';
COMMENT ON COLUMN intent_resolved_node_key_column.column_name IS 'the key column''s name as the winning tier spells it: as written on the pinned tier, as the generated class stated it on the metadata tier, and the catalog''s own name on the primary-key tier. Matching against a table''s columns is case-insensitive wherever a reader does it, which is settled convention rather than this relation''s rule. No fold is exposed beside it, and the reason is not that the tiers are three: intent_spelled_table is a union across as many arms with no single owning relation either, and it reads each arm''s stored fold internally without trouble. What it does not do is expose one, because no view in this schema does; forwarding a fold through a derived view is what the folded columns'' own comments forbid, a fold being minted on the base relation a comparison joins. What this relation hands out is a spelling rather than a resolved column, which is what makes the question of whether handing out a spelling is the right payload here a live one rather than a closed door, and it is asked on the roadmap rather than settled by this comment. A reader matching an authored spelling against this column therefore folds this side at the crossing and joins the authored side''s own generated column, which is where the schema mints one';
COMMENT ON COLUMN intent_resolved_node_key_column.tier IS 'which population answered, in a closed vocabulary of three: SDL_PINNED from an @node(keyColumns:) list, JOOQ_METADATA from the well-formed node metadata the bound table''s generated class states, CATALOG_PRIMARY_KEY from the bound table''s primary key under a node type, read off intent_node_type rather than the authored @node arm alone because that view is the one relation a reader of nodehood joins. Widening it there is inert today and stated as such rather than as a fix: the inferred arm requires well-formed node metadata, well-formedness requires a declared key-columns list, and that list is what the JOOQ_METADATA tier above answers with, so an inferred node type always resolves on the higher tier and this one only ever fires under an authored @node. It is the union anyway, so the tier stops being wrong rather than starting to be right if inference ever loosens. The order is the resolution''s own precedence, and the column is what lets a test pin which tier fired rather than only that the columns came out right';

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
    JOIN intent_resolved_type_binding bt
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
COMMENT ON VIEW intent_field_reference_step_target IS 'Where each element of a field''s @reference path actually lands: the hop view walked from the enclosing type''s table binding, one element at a time, so a row exists only for an element the chain can be shown to reach. Recursive because the arms are sequential and nothing else about them is: an element''s departure is the previous element''s arrival, and only the first element''s departure is known without walking, being the type''s own binding. The seed reads intent_resolved_type_binding and not the @table population alone: what the walk needs is a table for the enclosing type, and a type standing for a routine chain''s result has one whether or not the author also wrote the directive, so seeding from the directive would leave a path departing such a type reaching nothing for exactly the schemas that stop restating the routine as a @table. Two consequences worth stating, both deliberate. An element that resolves to nothing ends the chain, so a path whose second element is fine but whose first names an unknown key contributes no rows at all rather than a row starting from nowhere; that is the walk''s own behaviour and the reason absence here means "not reached", never "resolves to nothing in particular". And an element carrying neither key nor table is not a hop this view knows: a condition-only element takes its target from the condition method''s Java return type, and an omitted path is foreign-key discovery between a parent and a child type, both resolutions this view does not perform and neither of which should be mistaken for its silence. Terminal-element readers project the maximum position per application; the chain has no separate terminal relation because one would be a reduction over this view with a single reader.';
COMMENT ON COLUMN intent_field_reference_step_target.graph_name IS 'the owning graph''s partition, carried from the hop view';
COMMENT ON COLUMN intent_field_reference_step_target.type_name IS 'the type owning the field the @reference is applied to; also the type whose binding started the chain';
COMMENT ON COLUMN intent_field_reference_step_target.field_name IS 'the field the @reference is applied to';
COMMENT ON COLUMN intent_field_reference_step_target.ordinal IS 'the owning @reference application''s ordinal, since the directive is repeatable';
COMMENT ON COLUMN intent_field_reference_step_target.position IS 'the element''s 0-based position within its application''s path; positions are contiguous from 0 up to wherever the chain stopped';
COMMENT ON COLUMN intent_field_reference_step_target.via IS 'which arm resolved the element, as on the hop view: KEY, TABLE or NAME_MATCH';
COMMENT ON COLUMN intent_field_reference_step_target.key_matched_by IS 'for a KEY element, the namespace that answered; NULL on a TABLE or NAME_MATCH element. As on the hop view';
COMMENT ON COLUMN intent_field_reference_step_target.from_source_name IS 'the departing table''s catalog partition; the type''s bound table at position 0, the previous element''s arrival after that';
COMMENT ON COLUMN intent_field_reference_step_target.from_schema IS 'the departing table''s SQL schema';
COMMENT ON COLUMN intent_field_reference_step_target.from_table IS 'the departing table''s SQL name';
COMMENT ON COLUMN intent_field_reference_step_target.to_source_name IS 'the arriving table''s catalog partition, first column of its sql_table key';
COMMENT ON COLUMN intent_field_reference_step_target.to_schema IS 'the arriving table''s SQL schema';
COMMENT ON COLUMN intent_field_reference_step_target.to_table IS 'the arriving table''s SQL name. At the path''s last position this is the table a @field(name:) on the same field resolves its column against';
COMMENT ON COLUMN intent_field_reference_step_target.constraint_name IS 'the foreign key this element joins on, named or discovered; NULL on a NAME_MATCH element, as on the hop view';
COMMENT ON COLUMN intent_field_reference_step_target.fk_on_from IS 'TRUE when the departing table declares the foreign key; the element''s direction. NULL on a NAME_MATCH element, as on the hop view';
COMMENT ON COLUMN intent_field_reference_step_target.targets IS 'how many distinct tables this element reaches, this row''s arrival being one of them; 1 where the destination is certain. Separate from candidates because the two arities answer different questions and genuinely differ: a table element with three foreign keys connecting the two tables reaches one table by three routes, so a reader that only needs the destination can trust it while a reader that has to render the join cannot';
COMMENT ON COLUMN intent_field_reference_step_target.candidates IS 'how many rows this element resolved to, counting routes and not just destinations; 1 is the walk''s requirement for an expressible hop, and a larger number is what its own "which foreign key did you mean" rejection counts';

CREATE VIEW intent_field_column_scope
  (graph_name, type_name, field_name, basis,
   table_source_name, table_schema, table_name) AS
SELECT DISTINCT tg.graph_name, tg.type_name, tg.field_name,
       'PATH_TERMINAL',
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
UNION ALL
SELECT f.graph_name, f.type_name, f.field_name,
       'NAMED_TYPE_TABLE',
       bt.table_source_name, bt.table_schema, bt.table_name
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
  JOIN intent_resolved_type_binding bt
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
                      AND pv.field_name = f.field_name)
UNION ALL
SELECT f.graph_name, f.type_name, f.field_name,
       'PARENT_BINDING',
       bt.table_source_name, bt.table_schema, bt.table_name
  FROM graphql_field f
  JOIN graphql_type leaf
    ON leaf.graph_name = f.graph_name AND leaf.type_name = f.named_type
   AND leaf.kind IN ('SCALAR', 'ENUM')
  JOIN intent_resolved_type_binding bt
    ON bt.graph_name = f.graph_name AND bt.type_name = f.type_name
   AND bt.candidates = 1
 WHERE NOT EXISTS (SELECT 1 FROM graphitron_field_reference_step s
                    WHERE s.graph_name = f.graph_name
                      AND s.type_name = f.type_name
                      AND s.field_name = f.field_name);
COMMENT ON VIEW intent_field_column_scope IS 'Which table the column names written at a field''s site resolve against: the field''s own navigation, answered at every site where a column name resolves at all. A row means "resolve names against this table"; absence means no column name resolves here, which is what a field of an unbound parent and a field whose authored path reaches no single table both get. The relation exists because two consumers were deriving the same navigation apart from each other. The structural column-match classifier read the parent''s binding directly, so a name at a site an authored path had moved still resolved against the parent, and intent_field_column_table restated the same two navigation rules to answer the narrower question of when the resolved table is not the parent''s own. Both read this now, so the navigation is derived once and the consumers differ only in what each adds to it. Three rules, and they are disjoint rather than ranked, which is what lets this relation be a plain union with no windowed collapse over it. A collapse would be a cost multiplier and not a small one: the column-match classifier joins this view per coordinate, and a window inside it forces the whole relation to materialize on every read, over a store that holds every graph of a workspace. Disjointness carries the one-row-per-site property instead, so state the rules with their boundaries. An authored @reference path resolves to its terminal element''s table: the first application''s last element, the repeatable directive''s ordinal grain collapsed the way the authored-claim view collapses @routine''s, demanding the terminal reach exactly one table rather than exactly one row, so an element joining two tables by three keys still names its destination. An element that resolved to several rows all reaching one table is one row here, the arm taking DISTINCT over a projection that keeps only the table, which is exactly what demanding a single target makes safe. A field with no path whose named type is itself bound to a table resolves to that table, which is where an ordering column named on a list field lives; the named type read is the one the author wrote, taken off graphitron_field_synthesis where a macro rewrote the field''s type expression, so a connection field''s columns are the element type''s rather than the wrapper''s. A leaf field with no path resolves in its own parent''s binding, which is the scope every column-bound field of a table-bound type resolves in, and the leaf guard is what keeps this rule clear of the one above rather than a ranking between them: that rule reads an OBJECT named type and this one reads a SCALAR or ENUM. The two read the named type at different stages, this rule the field''s current one and that rule the expression the field was written with, so a macro that turned an object-typed field into a scalar-typed one would let both fire at a coordinate. None does, and if one ever did it would announce itself as two rows at one site, which the anchor test asserts against, rather than as a silent pick. Both binding rules read intent_resolved_type_binding rather than the @table population, because a column name resolves against whatever table stands for the type and a type standing for a @routine chain''s result has one; that is what lets a routine-returned type''s own scalar fields resolve without the author restating the routine as a @table. The resolution is also what keeps the rules one row per site: it collapses a type its @table and its routine return agree on to one binding, where a provenance-keyed relation would hand this view two. The single-table demand each rule already carries is unchanged, an ambiguous binding staying a site with no answer here. A field whose named type is an unbound object resolves nowhere, so a column name written at a nesting type''s site has no answer here yet; no consumer asks one, and the rule that would answer it is the type-grain nesting question rather than a fourth rule at this grain. A field carrying reference steps takes the first rule or nothing: a path reaching no single table must not fall back on the parent, because resolving a name against the parent there points the author at the wrong end of a join. The three rules do not carry the same guards and that is not an inconsistency. The named-type rule guards against a root parent, a named type of any kind but OBJECT, an ambiguous binding, an authored claim and a @pivot, because navigating to another type''s binding is exactly what an authored claim diverts. @pivot is the one claim that rule names directly, because the claim vocabulary has no arm for it yet and a pivoted field reads its columns from the pivot rather than from the type it names; the explicit guard folds into the anti-join the day that arm lands. The parent rule guards against none of those, because a field''s own parent scope exists whatever claims the field, and the structural classifier reads it precisely so a diagnostic can say "would classify as a table column; @service overrides it". A consumer joining this relation puts it first in its own FROM clause; intent_column_match_claim''s comment carries the measurement, and the shape it warns against is the one a reader reaches for. Masking is a consumer''s join and never a rule here: the authored-conflict silence intent_field_column_table adds sits in that view, and folding it in would silence the column-match classifier at a contested coordinate, where its raw reading is the whole point.';
COMMENT ON COLUMN intent_field_column_scope.graph_name IS 'the owning graph''s partition, carried from every rule''s base relation';
COMMENT ON COLUMN intent_field_column_scope.type_name IS 'the site''s owning type, the field''s parent';
COMMENT ON COLUMN intent_field_column_scope.field_name IS 'the site''s field name within the owning type';
COMMENT ON COLUMN intent_field_column_scope.basis IS 'which rule resolved this site: PATH_TERMINAL (an authored @reference path''s terminal element), NAMED_TYPE_TABLE (the field''s named type''s own binding), PARENT_BINDING (the field''s own parent''s binding). A closed vocabulary of three disjoint rules, so it is also which boundary the site fell inside, and the column that lets a consumer tell an override from the parent''s own scope without re-deriving either';
COMMENT ON COLUMN intent_field_column_scope.table_source_name IS 'the resolved table''s catalog partition, the first column of the sql_table key this row names';
COMMENT ON COLUMN intent_field_column_scope.table_schema IS 'the resolved table''s SQL schema';
COMMENT ON COLUMN intent_field_column_scope.table_name IS 'the resolved table''s SQL name. With the two columns above this is sql_table''s full key, so the table''s columns are one join away';


CREATE VIEW intent_column_match_claim
  (graph_name, type_name, field_name, classifier, matched_name, matched_by,
   table_source_name, table_schema, table_name, column_name,
   source_name, source_line, source_column) AS
SELECT graph_name, type_name, field_name, 'TABLE_COLUMN', matched_name, matched_by,
       table_source_name, table_schema, table_name, column_name,
       source_name, source_line, source_column
  FROM (SELECT f.graph_name, f.type_name, f.field_name,
               COALESCE(fb.name_ref, f.field_name) AS matched_name,
               CASE WHEN c.jooq_name_upper = COALESCE(fb.name_ref_upper, f.field_name_upper)
                    THEN 'JOOQ_NAME' ELSE 'SQL_NAME' END AS matched_by,
               bt.table_source_name, bt.table_schema, bt.table_name,
               c.column_name,
               f.source_name, f.source_line, f.source_column,
               ROW_NUMBER() OVER (
                 PARTITION BY f.graph_name, f.type_name, f.field_name
                 ORDER BY CASE WHEN c.jooq_name_upper
                                    = COALESCE(fb.name_ref_upper, f.field_name_upper)
                               THEN 0 ELSE 1 END, c.ordinal) AS rn
          FROM intent_field_column_scope bt
          JOIN graphql_field f
            ON f.graph_name = bt.graph_name AND f.type_name = bt.type_name
           AND f.field_name = bt.field_name
          JOIN graphql_type leaf
            ON leaf.graph_name = f.graph_name AND leaf.type_name = f.named_type
           AND leaf.kind IN ('SCALAR', 'ENUM')
          LEFT JOIN graphitron_field_binding fb
            ON fb.graph_name = f.graph_name AND fb.type_name = f.type_name
           AND fb.field_name = f.field_name
          JOIN sql_column c
            ON c.source_name = bt.table_source_name AND c.table_schema = bt.table_schema
           AND c.table_name = bt.table_name
           AND (c.jooq_name_upper = COALESCE(fb.name_ref_upper, f.field_name_upper)
                OR c.column_name_upper = COALESCE(fb.name_ref_upper, f.field_name_upper))) matched
 WHERE rn = 1;
COMMENT ON VIEW intent_column_match_claim IS 'The column-match structural classifier: a field whose name resolves against the table its site navigates to claims TABLE_COLUMN, no directive involved. Usually that is the parent''s own bound table, and where an authored @reference path moves the site it is the path''s terminal, which intent_field_column_scope answers for both and this view no longer decides for itself. One view per structural classifier, so the row''s columns are exactly this classifier''s join witnesses. The reading transcribes the classification walk''s fall-through arm: the field''s named type has kind SCALAR or ENUM, the site resolves against exactly one table (the resolution is intent_field_column_scope''s, which requires a single candidate on its parent-binding rule and so is how this arm transcribes the walk''s Ambiguous verdict; distinguishing that decline from a name not in the catalog at all is a future resolution-stratum detection over graphitron_table, not something this view''s absence encodes), and the effective name matches a column, generated-Java-name tier before SQL-name tier, both case-insensitive, collapsed to the first match in tier-then-ordinal order. The effective name is the @field binding where one decoded, else the field name; the arm needs no undecoded presence fallback because a declined @field decode leaves the COALESCE on the field name, which is the walk''s own fallback. The scope drives the join and that is load-bearing rather than stylistic: H2 re-evaluates a joined derived relation once per outer row, so reading the scope from underneath graphql_field costs the whole relation per candidate field and measured seventy times this shape on a store holding a dozen graphs. Any relation joining a derivation this deep wants the derivation first in the FROM clause. Deliberately mask-light: the only exclusion is the three root names, and it arrives through the scope view''s own binding read rather than being restated here, roots classifying before any table binding is read. The scope''s parent-binding rule is mask-light for the same reason, so a coordinate an authored directive claims still produces the structural reading here and the reduction is what drops it. No parent-kind gate and no directive knowledge: masking against authored claims is the reduction''s job, and the raw structural reading surviving here is what lets a diagnostic say "would classify as a table column; @service overrides it".';
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
                 WHERE NOT EXISTS (SELECT 1 FROM intent_field_column_scope sc
                                    WHERE sc.graph_name = a.graph_name
                                      AND sc.type_name = a.type_name
                                      AND sc.field_name = a.field_name
                                      AND sc.basis = 'PATH_TERMINAL')
                UNION ALL
                SELECT sc.graph_name, sc.type_name, sc.field_name,
                       'RESOLVE', sc.basis,
                       sc.table_source_name, sc.table_schema, sc.table_name, 2
                  FROM intent_field_column_scope sc
                 WHERE sc.basis <> 'PARENT_BINDING') arms) picked
 WHERE rn = 1;
COMMENT ON VIEW intent_field_column_table IS 'Which table a column name written at a field''s site resolves against, when that table is not the one the field''s own parent is bound to. The question an editor asks at a @field(name:) or @defaultOrder(fields: [{name:}]) site, and the resolution three LSP arms (completion, hover, the field-member diagnostic) each used to ask a projected per-permit switch. The navigation itself is intent_field_column_scope''s and no longer restated here: this view is that relation read as an override, its two non-parent bases carried through as RESOLVE rows and its parent-binding rows dropped. Deliberately narrow, which is what dropping them means: a field whose column names resolve against its parent''s own binding contributes no row, because a reader already holding the parent''s binding needs no relation to tell it so, and stating that case here would make this view a copy of the scope keyed one grain down. Absence therefore means "the parent''s own scope answers", which is the reading every consumer already falls back to; only a row overrides it. Two rules produce silence, meaning "no column name resolves here, and the parent''s scope must not stand in", and both are this view''s own rather than the scope''s: a coordinate whose classification the author has already contested, and an authored path that reaches no single table, the second read as the scope answering a path-carrying field with no terminal. The conflict silence stays here on purpose, because the column-match classifier reads the same scope and its raw reading at a contested coordinate is what lets a diagnostic name the override. The silences are structural, never a reading of the rejection residue: a derivation that asked the residue whether a coordinate had been reported would go quiet the day that family drains, and this relation''s meaning must not depend on where a message currently lives.';
COMMENT ON COLUMN intent_field_column_table.graph_name IS 'the owning graph''s partition, carried from every arm''s base relation';
COMMENT ON COLUMN intent_field_column_table.type_name IS 'the site''s owning type; the parent whose binding this row overrides';
COMMENT ON COLUMN intent_field_column_table.field_name IS 'the site''s field name within the owning type';
COMMENT ON COLUMN intent_field_column_table.disposition IS 'RESOLVE when the row names a table to resolve column names against, SILENT when it names none and the parent''s scope must not stand in. A closed two-value fork, which is what a consumer switches on; the basis it came from is the next column. Determined by basis rather than independent of it, and carried anyway because the fork is the reading every consumer needs and re-deriving it from a five-value vocabulary at each of them is how the two would drift';
COMMENT ON COLUMN intent_field_column_table.basis IS 'which rule produced this row: PATH_TERMINAL (an authored @reference path''s terminal element), NAMED_TYPE_TABLE (the field''s named type''s own binding), UNRESOLVED_PATH (an authored path reaching no single table), CONFLICTED (the coordinate''s claims are mutually exclusive). A closed vocabulary, and the column that lets a consumer explain its answer and a test pin which rule fired without asserting on the table it happened to reach';
COMMENT ON COLUMN intent_field_column_table.table_source_name IS 'the resolved table''s catalog partition, the first column of the sql_table key this row names; NULL on every SILENT row';
COMMENT ON COLUMN intent_field_column_table.table_schema IS 'the resolved table''s SQL schema; NULL on every SILENT row';
COMMENT ON COLUMN intent_field_column_table.table_name IS 'the resolved table''s SQL name; NULL on every SILENT row. With the two columns above this is sql_table''s full key, so the columns themselves are one join away';

CREATE VIEW intent_field_reference_discovery
  (graph_name, type_name, field_name,
   from_source_name, from_schema, from_table,
   to_source_name, to_schema, to_table,
   constraint_name, fk_on_from, candidates) AS
SELECT graph_name, type_name, field_name,
       from_source_name, from_schema, from_table,
       to_source_name, to_schema, to_table,
       constraint_name, fk_on_from,
       CAST(COUNT(*) OVER (
         PARTITION BY graph_name, type_name, field_name) AS INT)
  FROM (SELECT sc.graph_name, sc.type_name, sc.field_name,
               bt.table_source_name AS from_source_name,
               bt.table_schema      AS from_schema,
               bt.table_name        AS from_table,
               sc.table_source_name AS to_source_name,
               sc.table_schema      AS to_schema,
               sc.table_name        AS to_table,
               rc.constraint_name,
               CASE WHEN rc.source_name = bt.table_source_name
                     AND rc.table_schema = bt.table_schema
                     AND rc.table_name = bt.table_name
                    THEN TRUE ELSE FALSE END AS fk_on_from
          FROM intent_field_column_scope sc
          JOIN intent_resolved_type_binding bt
            ON bt.graph_name = sc.graph_name AND bt.type_name = sc.type_name
           AND bt.candidates = 1
          JOIN sql_referential_constraint rc
            ON (rc.source_name = bt.table_source_name
                AND rc.table_schema = bt.table_schema
                AND rc.table_name = bt.table_name
                AND rc.referenced_source_name = sc.table_source_name
                AND rc.referenced_schema = sc.table_schema
                AND rc.referenced_table = sc.table_name)
            OR (rc.source_name = sc.table_source_name
                AND rc.table_schema = sc.table_schema
                AND rc.table_name = sc.table_name
                AND rc.referenced_source_name = bt.table_source_name
                AND rc.referenced_schema = bt.table_schema
                AND rc.referenced_table = bt.table_name)
          JOIN sql_table arriving
            ON arriving.source_name = sc.table_source_name
           AND arriving.table_schema = sc.table_schema
           AND arriving.table_name = sc.table_name
          JOIN sql_table departing
            ON departing.source_name = bt.table_source_name
           AND departing.table_schema = bt.table_schema
           AND departing.table_name = bt.table_name
         WHERE sc.basis = 'NAMED_TYPE_TABLE'
           AND arriving.table_name_upper <> departing.table_name_upper) endpoints;
COMMENT ON VIEW intent_field_reference_discovery IS 'The foreign key an omitted @reference path discovers: for a field whose parent type and named type are each bound to a table, every foreign key connecting those two tables in either direction. This is the resolution the walk runs where a field carries no path element, and sql_referential_constraint''s own comment defers it here, "exactly one foreign key between these two tables" being a derivation over that relation rather than a captured fact. Separate from intent_field_reference_step_hop because that view resolves what an author wrote and this one answers where nothing was written; the hop view names this as a resolution it deliberately does not perform, so its silence is not the absence of a discovery. Neither endpoint is re-derived here. The arriving table is intent_field_column_scope''s named-type rule, which reads the written type expression through graphitron_field_synthesis so a connection field navigates as its element type, demands an OBJECT named type and an unambiguous binding, and excludes the coordinates an authored claim, a @pivot or an authored path diverts; restating any of that would be a second spelling of the navigation that view exists to state once. The departing table is the parent''s own binding, demanded unambiguous for the reason the arriving one is: a discovery between endpoints that are not certain is not the pair the walk would have had in hand. One row per connecting key and not two, a foreign key connecting a pair once whichever end declares it, and fk_on_from says which end that is; a self-referential key therefore needs no special case, the same-table pair being excluded outright. Excluded because the walk excludes it, comparing the two table names case-insensitively and nothing else, so two like-named tables in different schemas are one table to this rule as they are to the walk. Both names are catalog values reached through derived views, and a derived view does not forward a fold, so the comparison joins sql_table on its key at each end and reads the fold there; that costs two lookups on a primary key and keeps the fold a property of the relation that owns the spelling. The self-referencing field states its key explicitly or is rejected with that advice. Absence covers several things and none of them is "the discovery found nothing in particular": no foreign key connects the endpoints, or an endpoint is unbound or ambiguously bound, or the coordinate is one the named-type rule excludes. The walk''s other element-less arm needs no exclusion here: where the departing table is a table-valued function the walk name-matches instead of discovering, and a routine result declares no foreign keys, so such a coordinate contributes no rows on its own. The departing endpoint reads intent_resolved_type_binding, as the arriving one already does through the navigation view, so a parent bound by a @routine chain''s return rather than by a written @table is the same endpoint here; where that chain landed on a function result the sentence above is why the pair still finds nothing, and where it landed on a stored table through a hop the discovery is the ordinary one.';
COMMENT ON COLUMN intent_field_reference_discovery.graph_name IS 'the owning graph''s partition, carried from the navigation view';
COMMENT ON COLUMN intent_field_reference_discovery.type_name IS 'the type owning the field the path was omitted on; also the type whose binding is the departing end';
COMMENT ON COLUMN intent_field_reference_discovery.field_name IS 'the field name within the owning type';
COMMENT ON COLUMN intent_field_reference_discovery.from_source_name IS 'the departing table''s catalog partition, first column of its sql_table key';
COMMENT ON COLUMN intent_field_reference_discovery.from_schema IS 'the departing table''s SQL schema';
COMMENT ON COLUMN intent_field_reference_discovery.from_table IS 'the departing table''s SQL name; the parent type''s own binding';
COMMENT ON COLUMN intent_field_reference_discovery.to_source_name IS 'the arriving table''s catalog partition, first column of its sql_table key';
COMMENT ON COLUMN intent_field_reference_discovery.to_schema IS 'the arriving table''s SQL schema';
COMMENT ON COLUMN intent_field_reference_discovery.to_table IS 'the arriving table''s SQL name; the binding of the type the field names';
COMMENT ON COLUMN intent_field_reference_discovery.constraint_name IS 'the foreign key connecting the two tables, which is what an author would write into a {key:} element. Its own sql_referential_constraint key is this name under whichever endpoint declares it, which fk_on_from says';
COMMENT ON COLUMN intent_field_reference_discovery.fk_on_from IS 'TRUE when the departing table declares the foreign key, FALSE when the arriving one does; the hop''s direction, and what completes the constraint''s key from the two endpoint triples';
COMMENT ON COLUMN intent_field_reference_discovery.candidates IS 'how many foreign keys connect the two tables, this row being one of them; 1 is what the walk requires of a discovery, and a larger number is what its own "which foreign key did you mean" rejection counts. Stated as a column rather than left to each reader''s count, because whether the discovery is certain decides the reading and a reader that counted for itself would be re-deriving the resolution''s arity';

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
COMMENT ON VIEW intent_class_member_slot IS 'The member names a class offers an SDL author, in the author''s vocabulary rather than the JVM''s: what @field(name:) resolves against on a type whose backing is a class rather than a table. Keyed by the census''s own key, not by a graph: the question is about a class, and a graph reaches it the way it reaches any source-keyed fact, through store_graph_source. A class takes exactly one arm, chosen by its declared form, which is what keeps a slot name unambiguous about where it came from: a record answers with its components, and anything else answers with its bean accessors. The bean rule is the reason this is a relation and not a reader''s loop. It was written in the LSP-facing projection, where it had to be re-run on every build to hand the same list back, and it is a rule over the census rather than a fact about any graph: a public no-argument method whose name is get or is followed by an upper-case letter offers the remainder with its first letter lowered. The two prefixes are joined as data rather than spelled twice, and no arm reads the return type: a method named isTitle returning a String is a slot exactly as the projection made it one, because an author who wrote that name meant that member and a rule that second-guessed the type would hide it. Taking no parameters is read as the absence of parameter rows rather than as a descriptor''s shape, which is the same reading and the one that does not depend on how a descriptor is spelled. Two spellings of one property (getTitle beside isTitle) are two rows, the same two the projection''s list held; a reader wanting one takes the first, and a reader offering candidates offers both. Declaration order is deliberately not carried: the census records a position for a record component and nothing for a method, so an ordered column would be a fact about one arm only, and a reader that wants a stable list orders by name. What this relation does not answer is which class a type is backed by. What a slot delivers is one relation further on (intent_class_member_element, the peel read at the slot''s own owner), the hop a coordinate takes over it is intent_field_accessor_hop, and the closure over those hops is intent_type_backing_class. A reader that already holds the class name asks this relation only what the class offers, which is the question it answers and the whole of it.';
COMMENT ON COLUMN intent_class_member_slot.source_name IS 'the owning class''s classpath entry, carried from jvm_class; the partition a graph reaches through store_graph_source, and the reason one workspace''s modules do not fold their classes into each other''s answers';
COMMENT ON COLUMN intent_class_member_slot.class_name IS 'the fully-qualified binary name of the class offering the slot';
COMMENT ON COLUMN intent_class_member_slot.origin IS 'RECORD_COMPONENT or BEAN_ACCESSOR: which arm produced the row, and the whole of what a consumer needs to say what it found. A function of the class''s declared form rather than of the slot, so every slot of one class carries the same value, and carried per row anyway because the readers that fork on it (a diagnostic naming the member kind, a jump landing on a field rather than a method) hold a slot and not a class kind';
COMMENT ON COLUMN intent_class_member_slot.slot_name IS 'the name an author writes into @field(name:): a record component''s own name, or a bean accessor''s name with its prefix removed and its first letter lowered. Not unique within a class, two accessor spellings of one property being two rows';
COMMENT ON COLUMN intent_class_member_slot.display_type IS 'the member''s type as the source declared it, package-less and with type arguments kept (String, Integer, List<Film>); what a hover shows beside the name. The declared form rather than the erasure because this column exists to be rendered, and an author reading List learns less than one reading List<Film>. The erasure is a join away on the census relation the arm came from, for a reader comparing a type''s identity rather than showing it';
COMMENT ON COLUMN intent_class_member_slot.accessor_method_name IS 'the Java declaration the slot resolves to in source: the accessor method''s own name, which for a record component is the component name. The one column goto-definition reads, and the reason the bean rule''s two directions (a name to a slot, a slot back to its declaration) are stated once here rather than re-derived from slot_name by whoever needs the reverse';

CREATE VIEW intent_class_assignable (source_name, class_name, supertype_name) AS
WITH RECURSIVE reaches (source_name, class_name, supertype_name, path) AS (
  SELECT s.source_name, s.class_name, s.supertype_name,
         '/' || s.class_name || '/' || s.supertype_name || '/'
    FROM jvm_class_supertype s
  UNION ALL
  SELECT r.source_name, r.class_name, s.supertype_name,
         r.path || s.supertype_name || '/'
    FROM reaches r
    JOIN jvm_class_supertype s
      ON s.class_name = r.supertype_name
   WHERE POSITION('/' || s.supertype_name || '/' IN r.path) = 0
)
SELECT DISTINCT source_name, class_name, supertype_name FROM reaches;
COMMENT ON VIEW intent_class_assignable IS 'Every type a class in the census can stand in for: the transitive closure of the declared supertype relation, one row per class and reachable supertype. This is isAssignableFrom stated relationally, and it is the rule a walk over accessor return types could not apply without a live loader, a container test being a comparison against java.util.List, org.jooq.Result and a handful of others; it is also the whole reason jvm_class_supertype records what it records. A view rather than a materialized relation, which is the one thing that distinguishes it from the schema''s other closure: a supertype declaration is acyclic where the SDL type graph intent_type_domain closes over is not, so nothing here has to be re-derived at capture cadence and held. Worth stating that nothing waits behind it either; the container question is closed over a handful of named classes, and a general transitive relation is here because it is cheap over a census relation that had to exist anyway. Reflexivity is absent because it is unstateable rather than because a reader would not want it: a row saying java.util.List stands in for java.util.List needs a source_name for java.util.List, and the names this relation exists to reach are exactly the ones no classpath entry declares. So a consumer testing whether a type is a container compares the name itself and reads this relation for everything above it, which is the one reading that does not answer differently for a scanned class than for a JDK interface. Which clause a hop came through is absent for a different reason: EXTENDS and IMPLEMENTS are properties of one edge and a chain is not one edge, so a column here would either pick arbitrarily or report a set nobody asked for. Distance is absent on the same terms, a pair reachable by two chains having no single one. Both stay on jvm_class_supertype, for a reader that wants the declarations rather than what they reach. The closure ends where the scan does, and that is a disclosed limit rather than a bug. Nothing ships the JDK as a classpath entry, so org.jooq.Result reaching java.util.List is one hop inside the census and resolves, while a method declared to return java.util.ArrayList reaches nothing, the chain stopping at the first name no entry declares. A missing hop reads as not-known-to-be-assignable and never as not-assignable, and closing the gap means capturing supertypes for names the scan never reached, which is a fact the store is short rather than anything this view can do. The hop joins on the supertype''s name alone and does not require the next declaration to come from the same classpath entry, because the ordinary chain crosses entries: a consumer''s class implements an interface a jar declares, and that interface''s own supertypes are the jar''s rows. The crossing is also why the recursion carries a path guard where one program''s hierarchy would not need one. The store holds every entry every graph ever read, so one class name declared by two entries is routine, and two such names declared into each other would not terminate under H2''s recursive UNION, which does not deduplicate against rows earlier iterations produced. The guard enumerates simple paths, which over the acyclic shape a census actually holds costs what the unguarded form costs, and it removes a build that hangs with no diagnostic from the set of possible outcomes.';
COMMENT ON COLUMN intent_class_assignable.source_name IS 'the subtype''s classpath entry, carried from jvm_class_supertype; the partition a graph reaches through store_graph_source, and the reason one workspace''s modules do not fold their hierarchies into each other''s answers. The subtype''s own entry throughout, a chain that continues into another entry''s declarations keeping the coordinate the question was asked at';
COMMENT ON COLUMN intent_class_assignable.class_name IS 'the fully-qualified binary name of the subtype: the class that can stand in for the supertype. Never equal to supertype_name, the closure being over declarations and identity being no declaration';
COMMENT ON COLUMN intent_class_assignable.supertype_name IS 'the fully-qualified binary name of a type the subtype can stand in for, erased: a class implementing List of Film reaches java.util.List and the type argument is nowhere, which is the shape an assignability question wants and the wrong one for a reader after the element type. Frequently not a census row at all, which is the ordinary case at the end of a chain and the reason the relation this closes over carries no foreign key on it either. A nested type is spelled with the $ the JVM writes and can only ever appear on this side: the scan skips classfiles whose name carries a $, so a nested supertype declares nothing the store holds and a chain reaching one ends there';

CREATE VIEW intent_field_producer_reference
  (graph_name, type_name, field_name, declared_via, class_name, method_name) AS
SELECT s.graph_name, s.type_name, s.field_name, 'SERVICE' AS declared_via,
       s.class_name, s.method AS method_name
  FROM graphitron_service s
 WHERE s.class_name IS NOT NULL AND s.method IS NOT NULL
UNION ALL
SELECT e.graph_name, e.type_name, e.field_name, 'EXTERNAL_FIELD',
       e.class_name, COALESCE(e.method, e.field_name)
  FROM graphitron_external_field e
 WHERE e.class_name IS NOT NULL;
COMMENT ON VIEW intent_field_producer_reference IS 'The Java method a field''s authored directive names, before anything checks whether it exists: @service and @externalField coalesced, one row per application. Named apart from its resolution because the two answer different questions and one of them survives an unreachable class. A surface that must name the declaration a field binds to needs the reference, which the author wrote and which is a fact whether or not the classpath census reached the class; a surface that needs the method''s arity or wants to know the reference is ambiguous needs intent_field_producer_method, which is this relation resolved against jvm_method and therefore empty exactly where the census fell short. Splitting them also gives the @externalField omitted-method fallback one home: graphitron_external_field''s own comment defers that default to a derivation, and this is it, so no reader repeats the coalesce. Two arms coalesced by a view rather than one relation over a merged base, because which directive named the method is not recoverable from the pair of names and the two say different things about how the method is reached; declared_via carries it, on jvm_class_supertype.declared_via''s terms. Nothing here judges the reference: a coordinate carrying both directives is two rows, neither winning, and the conflict is intent_authored_claim_conflict''s to report.';
COMMENT ON COLUMN intent_field_producer_reference.graph_name IS 'the owning graph''s partition, carried from the directive relation the arm reads';
COMMENT ON COLUMN intent_field_producer_reference.type_name IS 'the referring field''s owning type';
COMMENT ON COLUMN intent_field_producer_reference.field_name IS 'the referring field''s name within the owning type';
COMMENT ON COLUMN intent_field_producer_reference.declared_via IS 'SERVICE or EXTERNAL_FIELD: which directive named the method, a closed two-value vocabulary, carried forward to intent_field_producer_method where its full argument is written';
COMMENT ON COLUMN intent_field_producer_reference.class_name IS 'the fully-qualified binary name the reference spells, exactly as authored. Unresolved: no row here asserts the class is on the classpath, and a misspelling is a row like any other';
COMMENT ON COLUMN intent_field_producer_reference.method_name IS 'the method name the reference spells: the directive''s method argument, or the SDL field''s own name where an @externalField omitted it. An @service missing either name has no row at all, that directive having no fallback';

CREATE VIEW intent_field_producer_method
  (graph_name, type_name, field_name, declared_via,
   source_name, class_name, method_name, descriptor, candidates) AS
SELECT graph_name, type_name, field_name, declared_via,
       source_name, class_name, method_name, descriptor, candidates
  FROM (SELECT r.graph_name, r.type_name, r.field_name, r.declared_via,
               m.source_name, m.class_name, m.method_name, m.descriptor,
               CAST(COUNT(*) OVER (PARTITION BY r.graph_name, r.type_name,
                                                r.field_name, r.declared_via) AS INT) AS candidates
          FROM intent_field_producer_reference r
          JOIN store_graph_source g ON g.graph_name = r.graph_name
          JOIN jvm_method m
            ON m.source_name = g.source_name
           AND m.class_name = r.class_name
           AND m.method_name = r.method_name) resolved;
COMMENT ON VIEW intent_field_producer_method IS 'The census method a field''s authored Java reference names: intent_field_producer_reference resolved against jvm_method, one row per method the reference matches. A use-keyed resolution over a source-keyed census, which is the shape an authored coordinate reaching into the classpath always takes here, and it states the resolution alone: which class the method''s return names is jvm_method_return_type_ref''s answer, one join further on. Which directive named the method, how the two arms coalesce and where the @externalField omitted-method fallback is applied are all the reference relation''s, stated in its comment; this relation adds the census match and nothing else, so a reader wanting the authored names without the match reads the reference and is not made to care whether the class was scanned. Ambiguity is rows and never a decline, as on intent_bound_table: a reference matching two overloads is two rows and candidates says so. That is the one place this relation departs from the walk it replaces, which takes whichever matching method the reflection API hands back first, in an order the JVM does not specify, so the walk''s answer for an overloaded name is unstable rather than merely arbitrary. The intended reading is that a reference matching more than one method is a rejection, and what a rejection needs is the arity, which is why this relation states it rather than picking. Absence has two causes and one join separates them: no jvm_class row under the graph''s sources means the census never reached the class (the scan''s filters, an entry nothing read, the generated jOOQ package), while a class row with no method row means the class declares no method of that name. Nothing here judges the reference beyond matching it. The census carries neither a static flag nor a lifter''s parameter shape, so an @externalField row does not assert the method satisfies that directive''s contract, and a nested class an author spells Outer.Inner matches nothing, the census writing the $ the JVM writes and skipping nested classes anyway.';
COMMENT ON COLUMN intent_field_producer_method.graph_name IS 'the owning graph''s partition, carried from the directive relation the arm reads';
COMMENT ON COLUMN intent_field_producer_method.type_name IS 'the referring field''s owning type';
COMMENT ON COLUMN intent_field_producer_method.field_name IS 'the referring field''s name within the owning type';
COMMENT ON COLUMN intent_field_producer_method.declared_via IS 'SERVICE or EXTERNAL_FIELD: which directive named the method, a closed two-value vocabulary. Carried rather than derived because the pair of names a row resolves cannot tell the two apart, and they are not interchangeable: a service method is invoked for the field''s value, an external field''s is invoked once for the jOOQ Field the generator selects. A coordinate carrying both directives is a conflict intent_authored_claim_conflict already reports, and here it is simply two references, each resolved on its own, neither winning';
COMMENT ON COLUMN intent_field_producer_method.source_name IS 'the resolved method''s classpath entry, as on jvm_method; the census partition the graph reached through store_graph_source, and the reason another graph''s entries cannot answer this graph''s reference';
COMMENT ON COLUMN intent_field_producer_method.class_name IS 'the fully-qualified binary name of the class declaring the resolved method, matched against the reference exactly. Java names are case-sensitive, so a misspelling resolves to nothing rather than to a near match';
COMMENT ON COLUMN intent_field_producer_method.method_name IS 'the resolved method''s name: the reference''s method argument, or the SDL field''s own name where an @externalField omitted it';
COMMENT ON COLUMN intent_field_producer_method.descriptor IS 'the resolved method''s raw JVM descriptor, jvm_method''s overload discriminator and the whole of what tells two rows of one reference apart. The column a reader carries forward to reach the method''s parameters and the classes its declared return type names';
COMMENT ON COLUMN intent_field_producer_method.candidates IS 'how many census methods this reference matches, this row being one of them; 1 on an unambiguous reference. Partitioned by the reference and not by the coordinate, so a field carrying both directives does not report one arm''s overloads as the other''s. Two overloads and one class declared by two classpath entries both raise it, which is one fact from a reader''s side: the reference names more than one method. Stated as a column rather than left to each reader''s own count, on intent_bound_table.candidates'' terms, whether a reference is unique being what decides the reading';

CREATE VIEW intent_field_routine_method
  (graph_name, type_name, field_name, ordinal,
   source_name, table_schema, routine_name, class_name, method_name, parameters, candidates) AS
SELECT graph_name, type_name, field_name, ordinal,
       source_name, table_schema, routine_name, class_name, method_name, parameters, candidates
  FROM (SELECT r.graph_name, r.type_name, r.field_name, r.ordinal,
               sr.source_name, sr.table_schema, sr.routine_name,
               sr.routines_class_fqn AS class_name, sr.routines_method_name AS method_name,
               COALESCE(p.parameters, 0) AS parameters,
               CAST(COUNT(*) OVER (PARTITION BY r.graph_name, r.type_name,
                                                r.field_name, r.ordinal) AS INT) AS candidates
          FROM graphitron_routine r
          JOIN intent_spelled_table sp
            ON sp.graph_name = r.graph_name AND sp.spelling = r.routine_ref
          JOIN sql_routine sr
            ON sr.source_name = sp.table_source_name
           AND sr.table_schema = sp.table_schema
           AND sr.routine_name = sp.table_name
          LEFT JOIN (SELECT source_name, table_schema, routine_name,
                            CAST(COUNT(*) AS INT) AS parameters
                       FROM sql_routine_parameter
                      GROUP BY source_name, table_schema, routine_name) p
            ON p.source_name = sr.source_name
           AND p.table_schema = sr.table_schema
           AND p.routine_name = sr.routine_name
         WHERE sr.routines_class_fqn IS NOT NULL) resolved;
COMMENT ON VIEW intent_field_routine_method IS 'The generated call surface a @routine application names: graphitron_routine resolved through intent_spelled_table and onto sql_routine, one row per call surface the application matches. The sibling of intent_field_producer_method, and the same shape for the same reason: an authored coordinate reaching into a census is a use-keyed resolution over a source-keyed relation, and it states the resolution alone. How a written name meets the catalog is the spelling view''s rule, stated once there; that a @routine(name:) resolves under it is that view''s own claim, jOOQ modelling a function result as a catalog table. Keyed on the application rather than the field, because @routine is repeatable and the ordinal is what tells two applications on one field apart, in the written order the table chain interleaves them in. No table_type filter: a spelling naming a stored table resolves on the spelling view and then matches no row here, so the join says "not a callable" without restating what sql_table.table_type means. Absence has three causes and the joins separate them: no spelled-table row means the name matched no catalog object at all, a spelled-table row with no routine row means the object it matched is not callable, and a routine whose generated model exposes no call surface is excluded here, because this relation is the call surface and naming a class that does not exist would be a worse answer than naming nothing. Ambiguity is rows and never a decline, as on intent_bound_table.';
COMMENT ON COLUMN intent_field_routine_method.graph_name IS 'the owning graph''s partition, carried from graphitron_routine';
COMMENT ON COLUMN intent_field_routine_method.type_name IS 'the applying field''s owning type';
COMMENT ON COLUMN intent_field_routine_method.field_name IS 'the applying field''s name within the owning type';
COMMENT ON COLUMN intent_field_routine_method.ordinal IS 'the @routine application''s own ordinal, carried from graphitron_routine; the fourth key part, because a field may carry several applications and each resolves on its own';
COMMENT ON COLUMN intent_field_routine_method.source_name IS 'the resolved routine''s catalog partition, the first column of the sql_routine key this row names; the partition the graph reached through the spelling view''s own scoping';
COMMENT ON COLUMN intent_field_routine_method.table_schema IS 'the resolved routine''s SQL schema; what tells two candidates of one spelling apart';
COMMENT ON COLUMN intent_field_routine_method.routine_name IS 'the resolved routine''s SQL name. With the two columns above this is sql_routine''s full key';
COMMENT ON COLUMN intent_field_routine_method.class_name IS 'the generated Routines class carrying the call surface, from sql_routine.routines_class_fqn. Never null: a routine the generated model exposes none for has no row here at all';
COMMENT ON COLUMN intent_field_routine_method.method_name IS 'the Routines-class method an emitted FROM clause calls, from sql_routine.routines_method_name. Never null, on class_name''s terms, the two being null together in the census';
COMMENT ON COLUMN intent_field_routine_method.parameters IS 'how many IN parameters that method takes: the count of sql_routine_parameter rows for the routine, which is the call surface''s arity. A fact about the generated method, so it is the answer whether or not the consumer''s generated sources were ever scanned as a classpath entry, which they ordinarily are not. 0 is an honest arity here and not a fallback, a routine with no parameters and one whose surface the model does not expose being separated by the row''s existence rather than by this column';
COMMENT ON COLUMN intent_field_routine_method.candidates IS 'how many call surfaces this one application resolves to, this row being one of them; 1 on an unambiguous one. Partitioned by the application, as intent_field_producer_method partitions by the reference, and stated as a column on intent_bound_table.candidates'' terms: whether the resolution is unique is what decides the reading';

CREATE VIEW intent_delivery_container (container_class, element_index, multiplies) AS VALUES
  ('java.util.List', '0', TRUE),
  ('java.util.Set', '0', TRUE),
  ('java.util.Collection', '0', TRUE),
  ('java.util.Optional', '0', FALSE),
  ('java.util.concurrent.CompletableFuture', '0', FALSE),
  ('org.jooq.Result', '0', TRUE),
  ('java.util.Map', '1', FALSE);
COMMENT ON VIEW intent_delivery_container IS 'The classes a declared type delivers something through rather than delivers: the container vocabulary the peel descends, one row per class with the type-argument position its element sits at. Named data rather than a predicate spelled inside its reader, on the terms intent_class_member_slot joins its two bean prefixes, and its own relation because the peel reads it twice, once to descend and once to ask whether a position can descend at all. A rule that decides which classes are containers is exactly the kind that must have one home. They are named rather than recognised through the assignability closure because that closure cannot answer here: nothing ships the JDK as a classpath entry, so java.util.List declares nothing the census holds and standing in for it is unreachable from below. The set is closed by what a generator meets rather than by what Java offers, so a consumer returning its own collection type is not a container here and delivers itself; widening it is adding a row.';
COMMENT ON COLUMN intent_delivery_container.container_class IS 'the fully-qualified binary name of the container class, spelled as the census spells a class name';
COMMENT ON COLUMN intent_delivery_container.element_index IS 'the 0-based type-argument position the delivered element sits at, as a type_path step: 0 for the single-argument containers, 1 for a map, whose key is not what it delivers';
COMMENT ON COLUMN intent_delivery_container.multiplies IS 'whether passing through this container makes the delivery many rather than one. A collection multiplies and a wrapper does not, which is why the two live in one relation rather than two: both are stepped through by the same descent, and only what the step means to cardinality differs. A map is the case worth stating, and it is FALSE: a map from a key to one value delivers one, and a map from a key to a list delivers many because of the list, so the map itself is transparent and the descent through it decides nothing.';

CREATE VIEW intent_declared_type_ref
  (source_name, class_name, owner_kind, owner_name, owner_descriptor, owner_position,
   type_path, referenced_class, variance) AS
SELECT source_name, class_name, 'METHOD_RETURN', method_name, descriptor, CAST(NULL AS INT),
       type_path, referenced_class, variance
  FROM jvm_method_return_type_ref
UNION ALL
SELECT source_name, class_name, 'RECORD_COMPONENT', component_name, CAST(NULL AS VARCHAR),
       CAST(NULL AS INT), type_path, referenced_class, variance
  FROM jvm_record_component_type_ref
UNION ALL
SELECT source_name, class_name, 'METHOD_PARAMETER', method_name, descriptor, position,
       type_path, referenced_class, variance
  FROM jvm_method_parameter_type_ref;
COMMENT ON VIEW intent_declared_type_ref IS 'Every declared type in the census that a reader can peel, keyed by the owner the census gives it: one row per position, unioning the method-return and record-component type-reference relations. This is the union jvm_record_component_type_ref''s own comment forecast, and the owner key is the reason it is stated here rather than at a reader: the three census relations are three keys, so a reader whose question is uniform across the owners has to name the owner before it can ask, and naming it once means the two readers that peel a declared type ask the same relation. Source-keyed like the census, a graph reaching it through store_graph_source. The path grammar and the omission rules are jvm_method_return_type_ref''s and hold unchanged: the empty path is the type itself, a digit is a 0-based type-argument index, and a position naming no class has no row, so a primitive-typed member and an array-typed member alike name nothing at their root. The parameter relation is the third arm and joined when a reader arrived that peels a parameter, which is the widening this view was shaped to take rather than a second view: it brought owner_position with it, NULL on the two arms whose owner needs no ordinal. Two of the three key parts are therefore arm-determined, and neither NULL is a fact withheld; owner_kind says which parts to read, and a reader that joins the owner key blind uses IS NOT DISTINCT FROM, as the peel above this one does.';
COMMENT ON COLUMN intent_declared_type_ref.source_name IS 'the owning class''s classpath entry, as on jvm_class; the partition a graph reaches through store_graph_source';
COMMENT ON COLUMN intent_declared_type_ref.class_name IS 'the fully-qualified binary name of the class declaring the owner';
COMMENT ON COLUMN intent_declared_type_ref.owner_kind IS 'METHOD_RETURN, RECORD_COMPONENT or METHOD_PARAMETER: which census relation the position was read from, and the whole of what says how to read the three columns beside it. A closed vocabulary, one value per census relation of this shape, and there is no fourth';
COMMENT ON COLUMN intent_declared_type_ref.owner_name IS 'the owner''s own name: a method name on the return and parameter arms, a component name on the record arm. Never a key on its own, overloads sharing a method name and a parameter sharing its method''s. A parameter''s own name is not here and could not be, being NULL for a consumer compiled without -parameters, so the ordinal is the identity';
COMMENT ON COLUMN intent_declared_type_ref.owner_descriptor IS 'the owning method''s raw JVM descriptor, the overload discriminator on the return and parameter arms; NULL exactly on the record arm, where a component is named on its own and there is no descriptor to carry. A NULL determined entirely by owner_kind, which is the two-arm union''s key shape rather than a fact withheld, on intent_authored_claim_conflict.field_name''s terms';
COMMENT ON COLUMN intent_declared_type_ref.owner_position IS 'the parameter''s 0-based position, completing the key on the parameter arm; NULL exactly on the other two, whose owner is identified without an ordinal. Determined entirely by owner_kind, the three-arm union''s key shape rather than a fact withheld, on owner_descriptor''s terms';
COMMENT ON COLUMN intent_declared_type_ref.type_path IS 'the position within the declared type; the grammar is stated on jvm_method_return_type_ref.type_path';
COMMENT ON COLUMN intent_declared_type_ref.referenced_class IS 'the fully-qualified binary name of the class named at this position; the omission rules are stated on jvm_method_return_type_ref.referenced_class';
COMMENT ON COLUMN intent_declared_type_ref.variance IS 'NONE, EXTENDS or SUPER, as on jvm_method_return_type_ref.variance';

CREATE VIEW intent_declared_type_element
  (source_name, class_name, owner_kind, owner_name, owner_descriptor, owner_position,
   element_path, element_class, variance, delivers_many) AS
SELECT r0.source_name, r0.class_name, r0.owner_kind, r0.owner_name, r0.owner_descriptor,
       r0.owner_position,
       COALESCE(r4.type_path, r3.type_path, r2.type_path, r1.type_path, r0.type_path),
       COALESCE(r4.referenced_class, r3.referenced_class, r2.referenced_class,
                r1.referenced_class, r0.referenced_class),
       COALESCE(r4.variance, r3.variance, r2.variance, r1.variance, r0.variance),
       COALESCE(r1.type_path IS NOT NULL AND c1.multiplies, FALSE)
         OR COALESCE(r2.type_path IS NOT NULL AND c2.multiplies, FALSE)
         OR COALESCE(r3.type_path IS NOT NULL AND c3.multiplies, FALSE)
         OR COALESCE(r4.type_path IS NOT NULL AND c4.multiplies, FALSE)
  FROM intent_declared_type_ref r0
  LEFT JOIN intent_delivery_container c1 ON c1.container_class = r0.referenced_class
  LEFT JOIN intent_declared_type_ref r1
    ON r1.source_name = r0.source_name AND r1.class_name = r0.class_name
     AND r1.owner_kind = r0.owner_kind AND r1.owner_name = r0.owner_name
     AND r1.owner_descriptor IS NOT DISTINCT FROM r0.owner_descriptor
     AND r1.owner_position IS NOT DISTINCT FROM r0.owner_position
     AND r1.type_path = c1.element_index
  LEFT JOIN intent_delivery_container c2 ON c2.container_class = r1.referenced_class
  LEFT JOIN intent_declared_type_ref r2
    ON r2.source_name = r0.source_name AND r2.class_name = r0.class_name
     AND r2.owner_kind = r0.owner_kind AND r2.owner_name = r0.owner_name
     AND r2.owner_descriptor IS NOT DISTINCT FROM r0.owner_descriptor
     AND r2.owner_position IS NOT DISTINCT FROM r0.owner_position
     AND r2.type_path = r1.type_path || '.' || c2.element_index
  LEFT JOIN intent_delivery_container c3 ON c3.container_class = r2.referenced_class
  LEFT JOIN intent_declared_type_ref r3
    ON r3.source_name = r0.source_name AND r3.class_name = r0.class_name
     AND r3.owner_kind = r0.owner_kind AND r3.owner_name = r0.owner_name
     AND r3.owner_descriptor IS NOT DISTINCT FROM r0.owner_descriptor
     AND r3.owner_position IS NOT DISTINCT FROM r0.owner_position
     AND r3.type_path = r2.type_path || '.' || c3.element_index
  LEFT JOIN intent_delivery_container c4 ON c4.container_class = r3.referenced_class
  LEFT JOIN intent_declared_type_ref r4
    ON r4.source_name = r0.source_name AND r4.class_name = r0.class_name
     AND r4.owner_kind = r0.owner_kind AND r4.owner_name = r0.owner_name
     AND r4.owner_descriptor IS NOT DISTINCT FROM r0.owner_descriptor
     AND r4.owner_position IS NOT DISTINCT FROM r0.owner_position
     AND r4.type_path = r3.type_path || '.' || c4.element_index
 WHERE r0.type_path = '';
COMMENT ON VIEW intent_declared_type_element IS 'The class a declared type delivers: the type with its delivery wrappers peeled off, one row per owner. A member declared as a List of Film delivers Film, and so do a CompletableFuture of a List of Film and a Map from a key to Film, which is the rule that lets an SDL field naming one object stand on a member, or on a producer method, that hands back many. Keyed by the declared type''s own owner rather than by any reader''s subject, which is the correction a second reader forced: the rule was first stated over member slots, and a producer method''s return is the same declared form under a key no slot relation can hold, so a peel keyed at either reader would have been spelled twice and drifted. A third reader arrived for the parameter arm and needed nothing changed here but the key, which is the shape paying for itself. The peel descends from the root position: at a position naming a container it steps to that container''s element argument, and it stops at the first position naming no container, or naming one whose element argument names no class. That stopping position is the row, and the descent is four outer joins deep rather than a recursion. The bound is deliberate and was measured into existence. A recursive form terminates on its own, a declared type being a finite tree, so termination was never the question; the cost was. H2 re-evaluates a recursive view once per outer row of whatever joins it, and the readers here join it without a class predicate on purpose, the accessor hop being total over standing classes, so at sixteen thousand census methods the hop took six minutes to return nothing where reading the peel by itself took a third of a second. Four is what the reflective walk this replaces also descends, so the bound costs no agreement with it, and a nesting deeper than four delivers the last container reached rather than silently delivering the wrong class: element_path shows the depth and element_class names a container, which is a shape a reader can detect and a detection can be built on. The containers are intent_delivery_container''s rows, joined once per level, which is why they are a relation rather than a list inlined in this one. The recursion is not the one the SDL type graph needs a guard for. A declared type is a finite tree, so the descent terminates on its own, and the depth is the type''s rather than a bound the rule picks. Two populations fall away with no filter, because the census already omits them: a primitive-typed owner and an array-typed owner name no class at their root and so have no spine at all, an array''s component being the next step down and this walk never taking that step. What this view does not do is judge the class it lands on. A raw List with no type argument delivers java.util.List and says so, and an owner delivering java.lang.String is a row like any other; which landing classes are worth binding an SDL type to is a filter a reader applies rather than a fact this relation withholds.';
COMMENT ON COLUMN intent_declared_type_element.source_name IS 'the owning class''s classpath entry, carried from intent_declared_type_ref; the partition a graph reaches through store_graph_source';
COMMENT ON COLUMN intent_declared_type_element.class_name IS 'the fully-qualified binary name of the class declaring the owner';
COMMENT ON COLUMN intent_declared_type_element.owner_kind IS 'METHOD_RETURN or RECORD_COMPONENT, as on intent_declared_type_ref';
COMMENT ON COLUMN intent_declared_type_element.owner_name IS 'the owner''s own name, as on intent_declared_type_ref';
COMMENT ON COLUMN intent_declared_type_element.owner_descriptor IS 'the owning method''s raw JVM descriptor, NULL exactly on the record arm, as on intent_declared_type_ref; the column a reader carries back to the census method it resolved';
COMMENT ON COLUMN intent_declared_type_element.owner_position IS 'the parameter''s 0-based position, NULL exactly on the other two arms, as on intent_declared_type_ref; what tells one parameter''s peel from its neighbour''s under a key they otherwise share';
COMMENT ON COLUMN intent_declared_type_element.element_path IS 'the position the peel stopped at, the empty string where the declared type names its own delivery. The evidence for the answer rather than decoration: a reader can see whether a row came off the root or off three descents, and a test can pin which without asserting on the class that happened to be there';
COMMENT ON COLUMN intent_declared_type_element.element_class IS 'the fully-qualified binary name of the class the owner delivers. Not a foreign key, on jvm_method_return_type_ref.referenced_class''s terms, so an owner delivering a class no classpath entry declares is an ordinary row and a reader learns nothing further about it';
COMMENT ON COLUMN intent_declared_type_element.variance IS 'NONE, EXTENDS or SUPER at the position landed on: a type declared as a List of ? extends Film delivers Film under EXTENDS. Carried because the three declare different things about which direction values flow and the class name alone cannot tell them apart';
COMMENT ON COLUMN intent_declared_type_element.delivers_many IS 'whether the declared type delivers many of the element rather than one: TRUE where the descent crossed a container that multiplies, FALSE where it crossed only wrappers or did not descend at all. Carried rather than left to the reader because element_path says how deep the descent went and not what it went through, so recovering this would mean re-reading the positions and the container vocabulary that this view already read. A raw container is FALSE and delivers itself, the descent never having happened, which is the same reading the reflective walk reaches by requiring a parameterised type before it looks at all.';

CREATE VIEW intent_class_member_element
  (source_name, class_name, origin, slot_name, accessor_method_name,
   element_path, element_class, variance) AS
SELECT s.source_name, s.class_name, s.origin, s.slot_name, s.accessor_method_name,
       e.element_path, e.element_class, e.variance
  FROM intent_class_member_slot s
  JOIN intent_declared_type_element e
    ON e.source_name = s.source_name AND e.class_name = s.class_name
   AND e.owner_kind = 'RECORD_COMPONENT' AND e.owner_name = s.slot_name
 WHERE s.origin = 'RECORD_COMPONENT'
UNION ALL
SELECT s.source_name, s.class_name, s.origin, s.slot_name, s.accessor_method_name,
       e.element_path, e.element_class, e.variance
  FROM intent_class_member_slot s
  JOIN intent_declared_type_element e
    ON e.source_name = s.source_name AND e.class_name = s.class_name
   AND e.owner_kind = 'METHOD_RETURN' AND e.owner_name = s.accessor_method_name
 WHERE s.origin = 'BEAN_ACCESSOR'
   AND NOT EXISTS (SELECT 1 FROM jvm_method_parameter p
                    WHERE p.source_name = e.source_name
                      AND p.class_name = e.class_name
                      AND p.method_name = e.owner_name
                      AND p.descriptor = e.owner_descriptor);
COMMENT ON VIEW intent_class_member_element IS 'The class a member slot delivers: intent_declared_type_element read at the slot''s own owner, one row per slot. A reader standing on a slot asks what it delivers and does not care whether a record component or a bean accessor answered, which is a reader''s question and therefore a view rather than a second peel. Source-keyed like the slot relation it extends, a graph reaching it through store_graph_source. The peel rule, the container vocabulary and the omission consequences are all stated on intent_declared_type_element and hold here unchanged; what this view adds is which owner a slot resolves to, which is exactly the join a reader would otherwise write for itself. The bean arm carries one condition the record arm does not need. A slot carries its accessor''s name and not its descriptor, and the peel is keyed by descriptor, so this arm picks the accessor among same-named methods the way the slot rule picked it in the first place, by the absence of parameter rows. That is the same reading applied twice rather than a second rule, and it is what stops a getTitle(int) declared beside getTitle() from lending its return type to the title slot.';
COMMENT ON COLUMN intent_class_member_element.source_name IS 'the owning class''s classpath entry, carried from intent_class_member_slot; the partition a graph reaches through store_graph_source';
COMMENT ON COLUMN intent_class_member_element.class_name IS 'the fully-qualified binary name of the class offering the slot';
COMMENT ON COLUMN intent_class_member_element.origin IS 'RECORD_COMPONENT or BEAN_ACCESSOR, as on intent_class_member_slot; which arm produced the slot, and here also which owner kind the peel was read at';
COMMENT ON COLUMN intent_class_member_element.slot_name IS 'the name an author writes into @field(name:), as on intent_class_member_slot; two spellings of one property are two slots and so two rows, which this view inherits rather than resolves';
COMMENT ON COLUMN intent_class_member_element.accessor_method_name IS 'the Java declaration the slot resolves to, as on intent_class_member_slot';
COMMENT ON COLUMN intent_class_member_element.element_path IS 'the position the peel stopped at, carried from intent_declared_type_element';
COMMENT ON COLUMN intent_class_member_element.element_class IS 'the fully-qualified binary name of the class the slot delivers, carried from intent_declared_type_element; not a foreign key, on the same terms';
COMMENT ON COLUMN intent_class_member_element.variance IS 'NONE, EXTENDS or SUPER at the position landed on, carried from intent_declared_type_element';

CREATE VIEW intent_field_accessor_hop
  (graph_name, type_name, field_name, source_name, from_class_name,
   origin, slot_name, accessor_method_name, to_class_name, element_path, variance) AS
SELECT f.graph_name, f.type_name, f.field_name, e.source_name, e.class_name,
       e.origin, e.slot_name, e.accessor_method_name,
       e.element_class, e.element_path, e.variance
  FROM graphql_field f
  JOIN store_graph_source g ON g.graph_name = f.graph_name
  LEFT JOIN graphitron_field_binding b
    ON b.graph_name = f.graph_name AND b.type_name = f.type_name
   AND b.field_name = f.field_name
  JOIN intent_class_member_element e
    ON e.source_name = g.source_name
   AND e.slot_name = COALESCE(b.name_ref, f.field_name);
COMMENT ON VIEW intent_field_accessor_hop IS 'Where an accessor hop lands: for a field coordinate and a class its parent might stand on, the class the member reading that field delivers. One edge of the walk that binds SDL types to backing classes, stated as a fact instead of as a step, so the closure over these edges is a reader''s recursion rather than a rule buried inside one. The slot a coordinate reads is the @field(name:) override where the field carries one and the field''s own name otherwise, which is the resolution the emission side makes; an output field and an input-object field resolve it identically and are one population here, that directive landing on both. Total over standing classes by construction. Nothing here says which class a parent actually stands on, so a coordinate pairs with every class in the graph''s sources offering a slot of that name, and a row is a conditional rather than an answer. That totality is what makes this an edge relation instead of another copy of the binding, and it is why this is a view that is never materialized: the product is large wherever a slot name is common and small wherever a reader binds the standing class before asking. Ambiguity is rows and no count. Two spellings of one property are two slots on intent_class_member_slot''s stated terms, and that relation already declines to choose between them, so a count here would be a second stance on a question one relation has settled. Two departures from the walk this replaces, both to be adjudicated against its shadow rather than assumed harmless. The first is that an SDL field''s arguments are not read at all. The walk probes for an accessor whose parameters match them, and a slot is a no-argument member by definition, so this relation hops where the walk would not (an argument-taking field standing on a no-argument accessor of the same name) and stays silent where the walk would hop (a field whose accessor takes those arguments). Reading the shape here would mean a slot relation holding parameterised members, which is a different question from the one that relation answers, so the difference is recorded rather than quietly closed. The second is that the walk skips a field carrying @service and skips a child type already bound; both are conditions on the closure rather than properties of an edge, and neither belongs here.';
COMMENT ON COLUMN intent_field_accessor_hop.graph_name IS 'the owning graph''s partition, carried from graphql_field';
COMMENT ON COLUMN intent_field_accessor_hop.type_name IS 'the coordinate''s owning type: the type whose standing class the hop departs from';
COMMENT ON COLUMN intent_field_accessor_hop.field_name IS 'the coordinate''s field name within that type';
COMMENT ON COLUMN intent_field_accessor_hop.source_name IS 'the departing class''s classpath entry, as on jvm_class; the census partition the graph reached through store_graph_source, and the reason another graph''s entries offer no hops here';
COMMENT ON COLUMN intent_field_accessor_hop.from_class_name IS 'the class the parent must stand on for this row to hold. A hypothesis the row is conditional on rather than a fact about the coordinate, which is exactly what keeps this relation clear of the closure that decides it';
COMMENT ON COLUMN intent_field_accessor_hop.origin IS 'RECORD_COMPONENT or BEAN_ACCESSOR, as on intent_class_member_slot';
COMMENT ON COLUMN intent_field_accessor_hop.slot_name IS 'the member name the coordinate resolved to: the @field(name:) override, or the field''s own name where it carries none';
COMMENT ON COLUMN intent_field_accessor_hop.accessor_method_name IS 'the Java declaration the hop reads, as on intent_class_member_slot; the column a jump to the member''s own source lands on';
COMMENT ON COLUMN intent_field_accessor_hop.to_class_name IS 'the class the hop lands on: what the slot delivers with its wrappers peeled, on intent_class_member_element''s terms. Not a foreign key, a landing class no classpath entry declares being ordinary rather than exceptional';
COMMENT ON COLUMN intent_field_accessor_hop.element_path IS 'the position within the slot''s declared type the landing class was read at, carried from intent_class_member_element; what says whether the hop peeled anything';
COMMENT ON COLUMN intent_field_accessor_hop.variance IS 'NONE, EXTENDS or SUPER at that position, carried from intent_class_member_element';

CREATE VIEW intent_producer_cardinality_conflict
  (graph_name, type_name, field_name, declared_via, source_name, class_name,
   method_name, descriptor, field_is_list, producer_delivers_many) AS
SELECT p.graph_name, p.type_name, p.field_name, p.declared_via,
       p.source_name, p.class_name, p.method_name, p.descriptor,
       f.is_list, e.delivers_many
  FROM intent_field_producer_method p
  JOIN graphql_field f
    ON f.graph_name = p.graph_name AND f.type_name = p.type_name
   AND f.field_name = p.field_name
  JOIN intent_declared_type_element e
    ON e.source_name = p.source_name AND e.class_name = p.class_name
   AND e.owner_kind = 'METHOD_RETURN' AND e.owner_name = p.method_name
   AND e.owner_descriptor = p.descriptor
 WHERE f.is_list <> e.delivers_many;
COMMENT ON VIEW intent_producer_cardinality_conflict IS 'Where a field and the method producing its value disagree about how many: one row per producing coordinate whose SDL type is a list whose producer delivers one, or whose SDL type is single where the producer delivers many. A detection the store did not have, and the reason it did not is worth stating, because it is the argument for decomposing a walk into facts at all. The walk this derivation replaces reads the same two cardinalities and uses the comparison as a clause: where they disagree it declines to bind, reading the field as a carrier whose collection feeds an inner list field. So the reading existed and its result was a silence, which is exactly the shape a defect hides in. Stated as its own relation the comparison is observable, and whether a given row is a carrier or an author error is a question a reader can now ask rather than one the walk answered by moving on. Nothing gates on these rows yet. A coordinate whose reference matches several overloads contributes a row per overload that disagrees, on intent_field_producer_method''s terms, since which method the reference means is that relation''s open question and not this one''s to settle. A producer whose declared return names no class at its root has no row here at all rather than a row asserting agreement: the peel it would be compared against does not exist, and a primitive or an array return is a different complaint from a cardinality one.';
COMMENT ON COLUMN intent_producer_cardinality_conflict.graph_name IS 'the owning graph''s partition, carried from intent_field_producer_method';
COMMENT ON COLUMN intent_producer_cardinality_conflict.type_name IS 'the disagreeing coordinate''s owning type';
COMMENT ON COLUMN intent_producer_cardinality_conflict.field_name IS 'the disagreeing coordinate''s field name';
COMMENT ON COLUMN intent_producer_cardinality_conflict.declared_via IS 'SERVICE or EXTERNAL_FIELD, as on intent_field_producer_method; which directive named the method whose cardinality disagrees';
COMMENT ON COLUMN intent_producer_cardinality_conflict.source_name IS 'the producing method''s classpath entry, as on intent_field_producer_method';
COMMENT ON COLUMN intent_producer_cardinality_conflict.class_name IS 'the class declaring the producing method';
COMMENT ON COLUMN intent_producer_cardinality_conflict.method_name IS 'the producing method''s name';
COMMENT ON COLUMN intent_producer_cardinality_conflict.descriptor IS 'the producing method''s raw JVM descriptor, which tells two overloads of one reference apart';
COMMENT ON COLUMN intent_producer_cardinality_conflict.field_is_list IS 'what the SDL says, carried from graphql_field.is_list; always the negation of the column beside it, and carried anyway so a reader learns which way the disagreement runs without joining back';
COMMENT ON COLUMN intent_producer_cardinality_conflict.producer_delivers_many IS 'what the declared return says, carried from intent_declared_type_element.delivers_many; the other half of the disagreement this row reports';

CREATE VIEW intent_type_backing_seed (graph_name, type_name, class_name) AS
SELECT p.graph_name, f.named_type, e.element_class
  FROM intent_field_producer_method p
  JOIN graphql_field f
    ON f.graph_name = p.graph_name
   AND f.type_name = p.type_name
   AND f.field_name = p.field_name
  JOIN graphql_type t
    ON t.graph_name = p.graph_name
   AND t.type_name = f.named_type
   AND t.kind IN ('OBJECT', 'INPUT_OBJECT')
  JOIN intent_declared_type_element e
    ON e.source_name = p.source_name
   AND e.class_name = p.class_name
   AND e.owner_kind = 'METHOD_RETURN'
   AND e.owner_name = p.method_name
   AND e.owner_descriptor = p.descriptor
UNION
SELECT p.graph_name, a.named_type, e.element_class
  FROM intent_field_producer_method p
  JOIN jvm_method_parameter mp
    ON mp.source_name = p.source_name
   AND mp.class_name = p.class_name
   AND mp.method_name = p.method_name
   AND mp.descriptor = p.descriptor
  LEFT JOIN graphitron_service_arg_mapping_pair m
    ON m.graph_name = p.graph_name
   AND m.type_name = p.type_name
   AND m.field_name = p.field_name
   AND m.param_name = mp.parameter_name
  LEFT JOIN graphitron_argument_path_segment s
    ON s.graph_name = m.graph_name
   AND s.type_name = m.type_name
   AND s.field_name = m.field_name
   AND s.argument_path = m.argument_path
   AND s.position = 0
  JOIN graphql_argument a
    ON a.graph_name = p.graph_name
   AND a.type_name = p.type_name
   AND a.field_name = p.field_name
   AND a.argument_name = COALESCE(s.segment_name, mp.parameter_name)
  JOIN graphql_type t
    ON t.graph_name = p.graph_name
   AND t.type_name = a.named_type
   AND t.kind IN ('OBJECT', 'INPUT_OBJECT')
  JOIN intent_declared_type_element e
    ON e.source_name = p.source_name
   AND e.class_name = p.class_name
   AND e.owner_kind = 'METHOD_PARAMETER'
   AND e.owner_name = p.method_name
   AND e.owner_descriptor = p.descriptor
   AND e.owner_position = mp.position
 WHERE mp.parameter_name IS NOT NULL;
COMMENT ON VIEW intent_type_backing_seed IS 'A graph''s type is backed by this class by a producer of its own, rather than by being read off some other type''s class. Two arms, one per axis, and they are the seeds intent_type_backing_class closes over: a field with an authored Java reference backs the type it returns with the class the resolved method delivers, and a producer''s parameter backs the type of the argument it is fed from with the class that parameter delivers. Which argument feeds a parameter is the parameter''s own name unless an argMapping entry redirects it, in which case it is the head of the path, read from graphitron_argument_path_segment. Objects and input objects only, as in the closure, and a parameter the consumer compiled without -parameters feeds nothing. Every row here is a row of intent_type_backing_class too, this relation being where that one starts; what it adds is which of that relation''s rows a producer grounded. Not a column on the closure, for the reason that relation gives for having no route column: a class reached both by a seed and by a hop is one backing and one row there, and a route column would multiply every reader''s rows by however many routes converged. Kept as its own relation, grounding is a join and the closure keeps its grain. Why a reader would want it: the classification walk this family shadows settles a type''s groundings before it propagates anything, and then declines to read an already-grounded type off a parent''s member, because a hop reads the parent''s member type without checking it against the child''s own grounding and can therefore land on a class that is simply wrong. A reader reproducing that precedence takes a type''s rows from here when it has any and from the closure otherwise. The precedence is the reader''s and not this relation''s, which states only where a backing came from.';
COMMENT ON COLUMN intent_type_backing_seed.graph_name IS 'the owning graph''s partition, carried from intent_field_producer_method';
COMMENT ON COLUMN intent_type_backing_seed.type_name IS 'the SDL type the producer grounds; the field''s named type on the return arm, the argument''s named type on the parameter arm';
COMMENT ON COLUMN intent_type_backing_seed.class_name IS 'the fully-qualified binary name of the class the producer delivers at that position, once the containers come off; spelled as the jvm_ census spells a class name, on intent_type_backing_class.class_name''s terms';

CREATE TABLE intent_type_backing_class (
  graph_name VARCHAR NOT NULL,
  type_name  VARCHAR NOT NULL,
  class_name VARCHAR NOT NULL,
  PRIMARY KEY (graph_name, type_name, class_name),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name),
  FOREIGN KEY (graph_name, type_name) REFERENCES graphql_type (graph_name, type_name)
);
COMMENT ON TABLE intent_type_backing_class IS 'A graph''s type is backed by a class: the reachability of intent_field_accessor_hop''s edges from the classes the graph''s producer methods deliver. The seeds are intent_type_backing_seed, which states both axes and says which of this relation''s rows a producer grounded rather than a hop reached; the closure then reads each backed type''s fields off its class and backs what they return with what the member delivers. Objects and input objects only, on both ends. A class stands for a composite type by answering its fields, and an interface''s implementors and a union''s members are not what a hop lands on, so an SDL name of any other kind is where the closure stops rather than a row it declines to write; a field typed by a scalar therefore falls away here without any reject list over Java classes, which is the population the walk this replaces excludes by naming String, Boolean, the java packages and the rest one at a time. One closure condition is applied and it is not a hop''s property: a coordinate that has a producer of its own is not read off its parent, its value coming from the method rather than from the member, so the hop over it is no edge of this closure. Materialized, not a view, for intent_type_domain''s reason exactly: the closure is over the SDL type graph, which is cyclic, and H2 has no safe recursive view form for one. Written by a capture-cadence derivation writer that clears its own graph partition and re-derives after every flush, so on any settled store these rows are current for every captured graph. Ambiguity is rows and there is no first-wins. A type two seeds answer differently is two rows and intent_type_backing_conflict names it, where the walk suppresses the second observation to protect the first and leaves the disagreement unobservable. A type a seed and a hop answer differently is two rows here as well, and there the walk''s suppression is doing more than ordering: a hop reads the parent''s member type without checking it against the child''s own grounding, so it can land on a class that is wrong rather than merely second. Which of those two rows to believe is intent_type_backing_seed''s to tell a reader, not this relation''s to decide. How a binding was reached is deliberately not a column: a class reached by two routes is one backing, so a route column would key the relation by path and multiply every reader''s rows by however many routes converged, which is the reading intent_class_assignable declined for the same reason; the routes are the seed and hop relations'' own rows for a reader that wants them. An arity is absent for a different reason: it is an aggregate over this relation''s own rows, so storing it beside them would put a function of the relation inside the relation, which a materialization has to earn and this one cannot. Both axes seed it, on intent_type_backing_seed''s terms: a producer''s return backs the type the field names and a producer''s parameter backs the type of the argument it is fed from, and that is one closure rather than two, an input object seeded from a parameter having its own fields read off that class by the frontier that reads an output type''s. Three populations remain absent while the derivation is built out, each queued for adjudication against the walk''s shadow rather than assumed harmless. A @table-bound type seeds nothing here: that population is intent_bound_table''s, and the classes it would seed are the generated jOOQ records the classpath census excludes by design, so the subtree below one is unreachable from the store rather than merely unwritten; intent_type_backing is where the two populations meet. The walk''s cardinality guard is not applied, so a single-object field produced by a collection return backs its type here where the walk reads a carrier and declines. And the two-level carrier fork is not applied, so a payload wrapper backs itself here where the walk reaches past it to the data field it wraps; both of those are the cardinality reading, which is its own fact and not a clause of this one.';
COMMENT ON COLUMN intent_type_backing_class.graph_name IS 'the owning graph''s partition, anchored by store_graph; the leading key dimension that keeps one workspace''s graphs apart';
COMMENT ON COLUMN intent_type_backing_class.type_name IS 'the SDL type the class backs; an object or an input object, and a captured type, which is what makes the type FK structural';
COMMENT ON COLUMN intent_type_backing_class.class_name IS 'the fully-qualified binary name of the class backing the type, spelled as the jvm_ census spells a class name so the two join without normalising. Not a foreign key, on intent_declared_type_element.element_class''s terms: a class the census never reached is the ordinary case at the end of a declared type, and what a producer delivers is a fact whether or not an entry declared it. Not unique per type either, ambiguity being rows here';

CREATE VIEW intent_type_backing (graph_name, type_name, class_name, declared_via) AS
SELECT b.graph_name, b.type_name, t.record_class_fqn, 'BOUND_TABLE'
  FROM intent_resolved_type_binding b
  JOIN sql_table t
    ON t.source_name = b.table_source_name
   AND t.table_schema = b.table_schema
   AND t.table_name = b.table_name
 WHERE t.record_class_fqn <> 'org.jooq.Record'
UNION ALL
SELECT graph_name, type_name, class_name, 'BACKING_CLOSURE'
  FROM intent_type_backing_class;
COMMENT ON VIEW intent_type_backing IS 'What class stands for a graph''s type, from either population that can answer: the type''s table binding read through the table''s generated record, and the closure over producer returns and accessor hops. The table arm reads intent_resolved_type_binding and not the @table population alone, because what puts a generated record behind a type is that some table stands for it, and a routine chain''s return binding stands for one exactly as a written @table does; a reader wanting only the written population joins the arm. One relation for the question every consumer of a backing actually asks, which is what class, not which walk found it. A view coalescing two relations rather than a base with a provenance tag, on the stratum''s provenance rule: each population is derived by its own rule from its own facts and neither is a special case of the other, so they are separate relations and this is where they meet. Both arms carry the same payload, one binary class name, and they can do that only because sql_table records the record class; before that fact was captured the arms had nothing in common and this view could not be stated without four columns NULL by kind. A table whose generated model has no record class reports org.jooq.Record, and that is not a backing, so the arm drops it and the type is unbacked here, the same silence a type no producer reaches already gets. Ambiguity is rows on both arms and nothing is preferred: a type its @table binding and its closure answer differently is two rows, and intent_type_backing_conflict over this view is where a reader learns so. The walk resolves that pair by precedence, reading the table and never looking at the class, which is a reading a consumer may still apply by filtering on declared_via; what it may not do here is mistake the precedence for agreement.';
COMMENT ON COLUMN intent_type_backing.graph_name IS 'the owning graph''s partition, carried from whichever arm produced the row';
COMMENT ON COLUMN intent_type_backing.type_name IS 'the SDL type the class stands for';
COMMENT ON COLUMN intent_type_backing.class_name IS 'the fully-qualified binary name of the backing class, spelled as the jvm_ census spells a class name on both arms. On the table arm this is the generated jOOQ record, which the census deliberately never scanned, so a class name here is not a promise that jvm_class holds the class';
COMMENT ON COLUMN intent_type_backing.declared_via IS 'which population answered, a closed two-value domain: BOUND_TABLE for the resolved table binding read through its table''s record class, whichever rule bound it, BACKING_CLOSURE for the reachability over producer returns and accessor hops. Provenance, never a preference; a reader that wants one arm filters on it and owns having chosen';

CREATE VIEW intent_type_backing_conflict (graph_name, type_name, class_names, candidates) AS
SELECT graph_name, type_name,
       LISTAGG(DISTINCT class_name, ', ') WITHIN GROUP (ORDER BY class_name),
       CAST(COUNT(DISTINCT class_name) AS INT)
  FROM intent_type_backing
 GROUP BY graph_name, type_name
HAVING COUNT(DISTINCT class_name) > 1;
COMMENT ON VIEW intent_type_backing_conflict IS 'The types the store answers with more than one backing class: one row per type whose producers, accessor hops and @table binding do not all name the same class. Stated over the coalesced intent_type_backing rather than over one arm, because a type contested across the two populations is contested in exactly the sense a consumer needing one class cares about, and an arm-local view would have called that agreement. Two disagreements therefore land here. Two producers answering differently is the one the closure surfaces, a population nobody could previously ask about: the walk resolves it by refusing the second observation and folding the survivors, so a contradiction between producers is either invisible or arrives as a rejection with the losing side already discarded. A @table binding and a reached class answering differently is the other, which the walk resolves by precedence, reading the table without ever consulting the class; that is a defensible reading and a consumer may still apply it, but it is a choice, and a relation that folded it in would have hidden the choice rather than recorded it. Counted over distinct class names, not rows, so one class both arms name is one answer and not a contest. A view over the coalesce rather than a column on the closure, on intent_authored_claim_conflict''s terms: the contested population is a grouping over rows already held, and it costs nothing to state on read. Nothing gates on these rows yet. The reading this relation is shaped for is that a contested type is a rejection at whichever consumer needs one class, and the arity is what such a rejection stands on, which is why it is here rather than left to each reader''s own count.';
COMMENT ON COLUMN intent_type_backing_conflict.graph_name IS 'the owning graph''s partition, carried from intent_type_backing';
COMMENT ON COLUMN intent_type_backing_conflict.type_name IS 'the contested SDL type';
COMMENT ON COLUMN intent_type_backing_conflict.class_names IS 'the contesting classes, comma-joined in name order: one canonical render so two readers grouping by the contested set cannot split a group on row order, on intent_authored_claim_conflict.directives'' terms. Display and grouping only, never a dimension; a reader wanting the classes themselves reads them as rows';
COMMENT ON COLUMN intent_type_backing_conflict.candidates IS 'how many distinct classes back the type, always two or more here; distinct, so a class both arms name counts once, and the arity a rejection stands on, on intent_bound_table.candidates'' terms';

CREATE VIEW intent_carrier_data_field
  (graph_name, type_name, field_name, family, element_kind, data_fields) AS
WITH poly_member (graph_name, container_name, member_type_name) AS (
  SELECT graph_name, union_name, member_type_name FROM graphql_union_member
   UNION ALL
  SELECT graph_name, interface_name, type_name FROM graphql_implements
),
errors_field (graph_name, type_name, field_name) AS (
  SELECT f.graph_name, f.type_name, f.field_name
    FROM graphql_field f
    JOIN graphql_type nt
      ON nt.graph_name = f.graph_name AND nt.type_name = f.named_type
     AND nt.kind IN ('UNION', 'INTERFACE')
   WHERE f.is_list AND NOT f.non_null
     AND NOT EXISTS (SELECT 1 FROM graphql_field_directive ac
                      WHERE ac.graph_name = f.graph_name AND ac.type_name = f.type_name
                        AND ac.field_name = f.field_name
                        AND ac.directive_name = 'asConnection')
     AND EXISTS (SELECT 1 FROM poly_member m
                  WHERE m.graph_name = f.graph_name AND m.container_name = f.named_type)
     AND NOT EXISTS (SELECT 1 FROM poly_member m
                      WHERE m.graph_name = f.graph_name AND m.container_name = f.named_type
                        AND NOT EXISTS (SELECT 1 FROM graphitron_error e
                                         WHERE e.graph_name = m.graph_name
                                           AND e.type_name = m.member_type_name))
),
data_channel (graph_name, type_name, field_name, element_kind,
              is_list, item_non_null, data_fields) AS (
  SELECT f.graph_name, f.type_name, f.field_name,
         CASE WHEN EXISTS (SELECT 1 FROM intent_bound_table b
                            WHERE b.graph_name = f.graph_name AND b.type_name = f.named_type
                              AND b.candidates = 1) THEN 'TABLE'
              WHEN EXISTS (SELECT 1 FROM intent_type_backing tb
                            WHERE tb.graph_name = f.graph_name AND tb.type_name = f.named_type
                              AND tb.declared_via = 'BACKING_CLOSURE') THEN 'RECORD'
              WHEN f.named_type = 'ID' THEN 'ID'
              ELSE NULL END,
         f.is_list, f.item_non_null,
         CAST(COUNT(*) OVER (PARTITION BY f.graph_name, f.type_name) AS INT)
    FROM graphql_field f
    JOIN graphql_type t
      ON t.graph_name = f.graph_name AND t.type_name = f.type_name AND t.kind = 'OBJECT'
   WHERE NOT EXISTS (SELECT 1 FROM errors_field e
                      WHERE e.graph_name = f.graph_name AND e.type_name = f.type_name
                        AND e.field_name = f.field_name)
),
producer (graph_name, payload_type_name, family) AS (
  SELECT DISTINCT f.graph_name, f.named_type, 'SERVICE'
    FROM graphitron_service s
    JOIN graphql_field f
      ON f.graph_name = s.graph_name AND f.type_name = s.type_name
     AND f.field_name = s.field_name
    JOIN graphql_root_operation r
      ON r.graph_name = f.graph_name AND r.operation = 'MUTATION' AND r.type_name = f.type_name
   UNION
  SELECT DISTINCT f.graph_name, f.named_type, 'DML'
    FROM graphitron_mutation m
    JOIN graphql_field f
      ON f.graph_name = m.graph_name AND f.type_name = m.type_name
     AND f.field_name = m.field_name
    JOIN graphql_root_operation r
      ON r.graph_name = f.graph_name AND r.operation = 'MUTATION' AND r.type_name = f.type_name
   UNION
  SELECT DISTINCT f.graph_name, f.named_type, 'ROUTINE'
    FROM graphitron_routine rt
    JOIN graphql_field f
      ON f.graph_name = rt.graph_name AND f.type_name = rt.type_name
     AND f.field_name = rt.field_name
    JOIN graphql_root_operation r
      ON r.graph_name = f.graph_name AND r.operation = 'MUTATION' AND r.type_name = f.type_name
)
SELECT p.graph_name, d.type_name, d.field_name, p.family, d.element_kind, d.data_fields
  FROM producer p
  JOIN data_channel d
    ON d.graph_name = p.graph_name AND d.type_name = p.payload_type_name
 WHERE NOT EXISTS (SELECT 1 FROM data_channel u
                    WHERE u.graph_name = d.graph_name AND u.type_name = d.type_name
                      AND u.element_kind IS NULL)
   AND NOT EXISTS (SELECT 1 FROM data_channel u
                    JOIN graphql_field_directive fd
                      ON fd.graph_name = u.graph_name AND fd.type_name = u.type_name
                     AND fd.field_name = u.field_name
                    WHERE u.graph_name = d.graph_name AND u.type_name = d.type_name
                      AND fd.directive_name IN ('service', 'sourceRow', 'reference', 'asConnection',
                            'splitQuery', 'externalField', 'condition', 'lookupKey', 'notGenerated',
                            'defaultOrder', 'orderBy', 'multitableReference')
                      AND NOT (fd.directive_name = 'splitQuery' AND p.family = 'SERVICE'))
   AND NOT EXISTS (SELECT 1 FROM data_channel u
                    WHERE u.graph_name = d.graph_name AND u.type_name = d.type_name
                      AND u.element_kind = 'ID'
                      AND (p.family = 'ROUTINE'
                           OR (p.family = 'DML' AND u.is_list AND NOT u.item_non_null)));
COMMENT ON VIEW intent_carrier_data_field IS 'Where a mutation payload''s data arrives: for each OBJECT type a mutation-root write field returns, that type''s data channels, with the shape each element declares and how many channels the type has. A carrier is a payload whose whole job is to wrap one produced value beside an error channel, and the coordinate this relation names is where that value lands, which is what a surface offering or judging the $source sigil is asking about. The producing directive decides the family and the family decides two policies, so it is a column and not a filter this view applies for one reader: @service on a mutation-root field is SERVICE, @mutation is DML, @routine is ROUTINE, and a payload two families both return is a row per family rather than a pick. Errors-shaped fields are not data channels and never counted as one: a nullable-list field whose named type is a union or interface whose every member carries @error, which is the same shape the walk detects, minus the @asConnection case where the wrapper is a Connection rather than a list and the field falls through to the data-channel rules the directive then excludes it under anyway. What a data channel''s element declares is one of three kinds, tried in the walk''s own order: a named type bound unambiguously to a table is TABLE, a named type the backing closure reaches is RECORD, and the ID scalar is ID. The closure arm is read on declared_via so a @table type''s own record class cannot answer here, that population being the first arm''s and an ambiguous binding being no binding at all. That arm inherits the closure''s own stated departure, and it costs this relation the two-level carrier: where a payload wraps a result type the producer''s class stands for, the closure backs the wrapper and the walk reaches past it, so the element resolves to no kind and the payload names nothing. The departure is the closure''s to close, not a second reading of it here. An element of any other kind is not a payload shape the generator admits, and it rejects the whole payload rather than just its own coordinate, so a type carrying one contributes nothing; the same holds for a data-channel directive that routes the type out of the carrier mold, and for the two ID-element refusals that are a family''s own (a routine write has no PK-echo shape, so ROUTINE admits no ID element at any wrapper, and a DELETE echo cannot have a nullable slot, so DML refuses [ID]). The arity is a column and the refusal is the reader''s, as on the discovery view: a payload declaring two data channels is two rows counting two, which is the coordinate the generator rejects for having no single data field, and a reader demanding data_fields = 1 transcribes that refusal without re-counting. Absence covers several things and none of them is "this payload has no data": no mutation-root field returns the type, or the type is not an OBJECT, or one of the rejections above dropped it. Element kind and family are columns for the same reason the arity is: which rows admit a given surface is that surface''s rule, and the $source sigil''s reader demands SERVICE and a TABLE or ID element, those being the producer the user manual names and the two elements the carrier classification encodes the upstream value onto.';
COMMENT ON COLUMN intent_carrier_data_field.graph_name IS 'the owning graph''s partition, carried from the producing field';
COMMENT ON COLUMN intent_carrier_data_field.type_name IS 'the payload type the producing field returns; the type whose data channels these rows are';
COMMENT ON COLUMN intent_carrier_data_field.field_name IS 'the data channel''s field name within the payload type; the coordinate an author''s cursor sits on';
COMMENT ON COLUMN intent_carrier_data_field.family IS 'which producing directive returns the payload, a closed three-value domain: SERVICE (@service), DML (@mutation), ROUTINE (@routine). Provenance and policy both, the two rejections that differ between families being this column''s; a payload two families return is a row per family, and a reader that means one of them filters on it';
COMMENT ON COLUMN intent_carrier_data_field.element_kind IS 'what the channel''s element is, a closed three-value domain: TABLE (the named type is bound to one catalog table), RECORD (the backing closure reaches a class for it), ID (the ID scalar, the encoded-key echo). Never NULL here, an unrecognized element having dropped its whole payload';
COMMENT ON COLUMN intent_carrier_data_field.data_fields IS 'how many data channels the payload declares, this row being one of them; 1 is what a carrier requires, and a larger number is what the generator''s own "require exactly one" rejection counts. Stated as a column rather than left to each reader''s count, because whether the payload is a carrier at all decides the reading and a reader that counted for itself would be re-deriving the scan''s arity';

CREATE VIEW intent_carrier_routine_hop
  (graph_name, type_name, field_name,
   from_source_name, from_schema, from_table,
   to_source_name, to_schema, to_table, candidates) AS
SELECT graph_name, type_name, field_name,
       from_source_name, from_schema, from_table,
       to_source_name, to_schema, to_table,
       CAST(COUNT(*) OVER (PARTITION BY graph_name, type_name, field_name) AS INT)
  FROM (SELECT DISTINCT cdf.graph_name, cdf.type_name, cdf.field_name,
               fn.source_name AS from_source_name, fn.table_schema AS from_schema,
               fn.table_name AS from_table,
               b.table_source_name AS to_source_name, b.table_schema AS to_schema,
               b.table_name AS to_table
          FROM intent_carrier_data_field cdf
          JOIN graphql_field pf
            ON pf.graph_name = cdf.graph_name AND pf.named_type = cdf.type_name
          JOIN graphql_root_operation r
            ON r.graph_name = pf.graph_name AND r.type_name = pf.type_name
           AND r.operation = 'MUTATION'
          JOIN graphitron_routine rt
            ON rt.graph_name = pf.graph_name AND rt.type_name = pf.type_name
           AND rt.field_name = pf.field_name
          JOIN intent_spelled_table sp
            ON sp.graph_name = rt.graph_name AND sp.spelling = rt.routine_ref
          JOIN sql_table fn
            ON fn.source_name = sp.table_source_name AND fn.table_schema = sp.table_schema
           AND fn.table_name = sp.table_name AND fn.table_type = 'FUNCTION'
          JOIN graphql_field df
            ON df.graph_name = cdf.graph_name AND df.type_name = cdf.type_name
           AND df.field_name = cdf.field_name
          JOIN intent_resolved_type_binding b
            ON b.graph_name = df.graph_name AND b.type_name = df.named_type
           AND b.candidates = 1
         WHERE cdf.family = 'ROUTINE' AND cdf.element_kind = 'TABLE'
           AND NOT EXISTS (SELECT 1 FROM graphitron_field_reference fr
                            WHERE fr.graph_name = pf.graph_name
                              AND fr.type_name = pf.type_name
                              AND fr.field_name = pf.field_name)) inferred;
COMMENT ON VIEW intent_carrier_routine_hop IS 'The hop a routine write''s payload carrier takes to re-read its committed row, for a data field that declares no path. A @routine write on a mutation root may return a payload wrapping one data field beside an error channel; the routine call is the write, and the data field owns the post-commit re-read. That hop is inferred from the payload''s shape rather than written, which is precisely why intent_field_reference_step_hop holds no row for it: that relation''s population is authored path elements, and this coordinate authors none. So the same keying rule is reached from two relations, and it lives in neither of them. This one states the two ends and joins intent_name_matched_key_pair for the pairing, exactly as the authored arm does, and for the reason the referenced-side discipline gives: the pairs are reachable from the triples this row already carries, and repeating them here would be a denormalisation. The departure is the routine the producing mutation field names, resolved as any written table name is and then required to be FUNCTION-typed. The arrival is the data field''s own named type''s binding, demanded unambiguous, an arrival that is not certain not being the one the re-read would run against. The producing field is required to carry no @reference, which is not a narrowing but the carrier''s own boundary: the chained form returns the terminus table type and has an authored element to resolve through, and @routine with @reference over a carrier return is rejected outright, so a row here would name a hop the generator will not emit. Ambiguity is rows, and here it is a real one the generator currently hides: two mutation fields returning one payload are two candidate departures, where the grounding memo keeps whichever field classified first. Absence covers several things and none of them is "this carrier has no hop": the payload is not a carrier, or its element is not table-backed, or its data field''s type binds ambiguously, or the routine name resolves to nothing FUNCTION-typed. One narrowness is inherited rather than chosen. The element_kind gate is intent_carrier_data_field''s, and that relation reads the @table population alone where this one reads the resolved binding, so a data field whose type is bound only by being what a routine returns is excluded upstream of here; the gate follows that relation when it moves, rather than this one reading past it.';
COMMENT ON COLUMN intent_carrier_routine_hop.graph_name IS 'the owning graph''s partition, carried from the carrier relation';
COMMENT ON COLUMN intent_carrier_routine_hop.type_name IS 'the payload type the producing mutation field returns; the carrier whose data field this hop serves';
COMMENT ON COLUMN intent_carrier_routine_hop.field_name IS 'the data field within that payload: the coordinate the re-read runs at, and the one an author would hang a path on if the data field admitted one';
COMMENT ON COLUMN intent_carrier_routine_hop.from_source_name IS 'the routine result''s catalog partition; the departure, resolved from the producing field''s @routine(name:) through the spelling view';
COMMENT ON COLUMN intent_carrier_routine_hop.from_schema IS 'the routine result''s SQL schema';
COMMENT ON COLUMN intent_carrier_routine_hop.from_table IS 'the routine result''s SQL name, FUNCTION-typed by construction. With the two columns above this is the departing side of the pairing relation''s key';
COMMENT ON COLUMN intent_carrier_routine_hop.to_source_name IS 'the arriving table''s catalog partition; the data field''s named type''s binding, demanded unambiguous';
COMMENT ON COLUMN intent_carrier_routine_hop.to_schema IS 'the arriving table''s SQL schema';
COMMENT ON COLUMN intent_carrier_routine_hop.to_table IS 'the arriving table''s SQL name, the row the write committed and the re-read fetches. With the two columns above this is the arriving side of the pairing relation''s key';
COMMENT ON COLUMN intent_carrier_routine_hop.candidates IS 'how many departures this data field''s hop could leave from, this row being one of them; 1 where one mutation field produces the payload. Above 1 is two producing fields naming different routines, which the generator resolves by first-producer-wins without saying so, and which this column says';

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
    ON r.graph_name = f.graph_name AND r.type_name = f.type_name
UNION
SELECT f.graph_name, f.type_name, f.field_name, 'RECORD_HANDED_PARENT'
  FROM graphql_field f
  JOIN graphql_type pt
    ON pt.graph_name = f.graph_name AND pt.type_name = f.type_name
   AND pt.kind = 'OBJECT'
  JOIN intent_type_backing_class p
    ON p.graph_name = f.graph_name AND p.type_name = f.type_name
  JOIN intent_bound_table c
    ON c.graph_name = f.graph_name AND c.type_name = f.named_type
 WHERE NOT EXISTS (SELECT 1 FROM intent_bound_table pb
                    WHERE pb.graph_name = f.graph_name
                      AND pb.type_name = f.type_name);
COMMENT ON VIEW intent_field_separate_fetch IS 'Which fields are fetched by a statement of their own rather than projected out of the enclosing SELECT, one rule literal per arm. The question a schema author asks about round-trips: a field with no row here that resolves against its parent''s table costs nothing beyond the parent''s own statement, while a field with one is a second trip to the database. The two marker arms are the delivery-forcing union the table-backed child arm reads (@splitQuery defers the fetch through a DataLoader; @tenantFanOut forces the same boundary because a fanned child runs once per tenant and cannot join into a parent statement running on one source), stated as separate rules rather than one DELIVERY_MARKER because which marker forced the split is what an author reads and the two are written for different reasons. The service arm is the non-root @service contract: the service fetches independently of the parent''s SELECT, which is why the split is required rather than optional there. The root arm is every field of a bound root operation type, whose fetch is the operation''s own entry point and never a projection of anything; keyed by the root operation binding rather than the conventional names, so it states the intended rule the way the demand rules do, today''s walk dispatching on the literal names being the same known difference recorded there. The record-handed-parent arm is the implicit split, the one no author writes: a field of a type the backing closure grounds on a class, naming a type of its own that is bound to a table. There is no enclosing statement for such a field to be projected out of, the parent''s value being a Java object a producer handed back rather than a row of a running select, so the child''s table is a trip of its own. It reads intent_type_backing_class rather than the coalesced intent_type_backing, and anti-joins the parent''s own @table binding away, because a type both populations answer is one the walk reads as a table row and never as a handed object; that precedence is the one intent_type_backing''s comment records, so transcribing it is the same reading rather than a new opinion. The anti-join is over intent_bound_table and not over the coalesced view''s table arm, because what makes a parent a table row is its binding, whether or not jOOQ generated a record class for the table the binding names. Both joins over that binding stay on the @table population rather than moving to intent_resolved_type_binding as the navigation relations did, and that is a deliberate hold rather than an oversight: a type standing for a @routine chain''s result is handed to its children by the routine''s own statement, so whether such a parent is a table row or a handed row is the same precedence question this arm exists to state, and answering it by substituting the relation would decide it in passing. The two joins move together when it is settled. The parent''s kind is guarded and the child''s is not: the closure holds input objects beside objects and an input coordinate is not a fetch, while on the child side @table on an input object is unreachable from an object''s field to begin with. The literal is the walk''s own trigger name, so one population keeps one word wherever it is stated; record there means a producer-handed domain object, which this schema calls a class backing. Two readings depart from today''s walk and both are the intended rule. An ambiguously bound child splits here where the walk mints no table-backed verdict for it at all, since an ambiguous binding is contested rather than projected, and the split is what an editor can say about a schema mid-edit; a reader wanting the walk''s reading filters on intent_bound_table.candidates the way every other reader of that arity does. A @table interface child splits at either cardinality where the walk inlines the single-valued one, its discriminated-interface arm running before its record-handed one. Two populations stay absent, which is why absence is still not the complement''s claim: a child reached through a connection wrapper, no relation naming a connection''s element type, and the polymorphic fan-in, where a list-valued interface or union child with a table-bound participant batches through a DataLoader. A reader may say a field with a row is separately fetched, and may not say a field without one is inlined.';
COMMENT ON COLUMN intent_field_separate_fetch.graph_name IS 'the owning graph''s partition, carried through from every arm''s base relation';
COMMENT ON COLUMN intent_field_separate_fetch.type_name IS 'the separately fetched field''s owning type';
COMMENT ON COLUMN intent_field_separate_fetch.field_name IS 'the separately fetched field''s name within the owning type';
COMMENT ON COLUMN intent_field_separate_fetch.rule IS 'why the fetch is its own; a closed vocabulary (SPLIT_QUERY, TENANT_FAN_OUT, SERVICE, ROOT_OPERATION, RECORD_HANDED_PARENT) the reading side decodes into a typed value. A coordinate several rules cover is several rows, the arity being the answer rather than a precedence this view picks; each rule''s witnesses live one join away in the arm''s base relation, so no arm''s witness columns go nullable on the others';

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

CREATE VIEW intent_argmapping_pair
  (graph_name, site, use_site, type_name, field_name, argument_name, ordinal, step_position,
   position, param_name, argument_path, source_name, source_line, source_column) AS
SELECT p.graph_name, 'ROUTINE',
       p.type_name || '.' || p.field_name || '#' || CAST(p.ordinal AS VARCHAR),
       p.type_name, p.field_name, CAST(NULL AS VARCHAR), p.ordinal, CAST(NULL AS INT),
       p.position, p.param_name, p.argument_path,
       d.source_name, d.source_line, d.source_column
  FROM graphitron_routine_arg_mapping_pair p
  JOIN graphitron_routine d
    ON d.graph_name = p.graph_name AND d.type_name = p.type_name
   AND d.field_name = p.field_name AND d.ordinal = p.ordinal
 UNION ALL
SELECT p.graph_name, 'SERVICE',
       p.type_name || '.' || p.field_name,
       p.type_name, p.field_name, NULL, NULL, NULL,
       p.position, p.param_name, p.argument_path,
       d.source_name, d.source_line, d.source_column
  FROM graphitron_service_arg_mapping_pair p
  JOIN graphitron_service d
    ON d.graph_name = p.graph_name AND d.type_name = p.type_name
   AND d.field_name = p.field_name
 UNION ALL
SELECT p.graph_name, 'FIELD_CONDITION',
       p.type_name || '.' || p.field_name,
       p.type_name, p.field_name, NULL, NULL, NULL,
       p.position, p.param_name, p.argument_path,
       d.source_name, d.source_line, d.source_column
  FROM graphitron_field_condition_arg_mapping_pair p
  JOIN graphitron_field_condition d
    ON d.graph_name = p.graph_name AND d.type_name = p.type_name
   AND d.field_name = p.field_name
  JOIN graphql_type t ON t.graph_name = p.graph_name AND t.type_name = p.type_name
 WHERE t.kind <> 'INPUT_OBJECT'
 UNION ALL
SELECT p.graph_name, 'INPUT_FIELD_CONDITION',
       p.type_name || '.' || p.field_name,
       p.type_name, p.field_name, NULL, NULL, NULL,
       p.position, p.param_name, p.argument_path,
       d.source_name, d.source_line, d.source_column
  FROM graphitron_field_condition_arg_mapping_pair p
  JOIN graphitron_field_condition d
    ON d.graph_name = p.graph_name AND d.type_name = p.type_name
   AND d.field_name = p.field_name
  JOIN graphql_type t ON t.graph_name = p.graph_name AND t.type_name = p.type_name
 WHERE t.kind = 'INPUT_OBJECT'
 UNION ALL
SELECT p.graph_name, 'ARGUMENT_CONDITION',
       p.type_name || '.' || p.field_name || '(' || p.argument_name || ')',
       p.type_name, p.field_name, p.argument_name, NULL, NULL,
       p.position, p.param_name, p.argument_path,
       d.source_name, d.source_line, d.source_column
  FROM graphitron_argument_condition_arg_mapping_pair p
  JOIN graphitron_argument_condition d
    ON d.graph_name = p.graph_name AND d.type_name = p.type_name
   AND d.field_name = p.field_name AND d.argument_name = p.argument_name
 UNION ALL
SELECT p.graph_name, 'FIELD_REFERENCE_STEP',
       p.type_name || '.' || p.field_name || '#' || CAST(p.ordinal AS VARCHAR)
         || '[' || CAST(p.step_position AS VARCHAR) || ']',
       p.type_name, p.field_name, NULL, p.ordinal, p.step_position,
       p.position, p.param_name, p.argument_path,
       d.source_name, d.source_line, d.source_column
  FROM graphitron_field_reference_step_arg_mapping_pair p
  JOIN graphitron_field_reference d
    ON d.graph_name = p.graph_name AND d.type_name = p.type_name
   AND d.field_name = p.field_name AND d.ordinal = p.ordinal
 UNION ALL
SELECT p.graph_name, 'ARGUMENT_REFERENCE_STEP',
       p.type_name || '.' || p.field_name || '(' || p.argument_name || ')#'
         || CAST(p.ordinal AS VARCHAR) || '[' || CAST(p.step_position AS VARCHAR) || ']',
       p.type_name, p.field_name, p.argument_name, p.ordinal, p.step_position,
       p.position, p.param_name, p.argument_path,
       d.source_name, d.source_line, d.source_column
  FROM graphitron_argument_reference_step_arg_mapping_pair p
  JOIN graphitron_argument_reference d
    ON d.graph_name = p.graph_name AND d.type_name = p.type_name
   AND d.field_name = p.field_name AND d.argument_name = p.argument_name
   AND d.ordinal = p.ordinal
 UNION ALL
SELECT p.graph_name, 'REFERENCE_FOR_STEP',
       p.type_name || '.' || p.field_name || '#' || CAST(p.ordinal AS VARCHAR)
         || '[' || CAST(p.step_position AS VARCHAR) || ']',
       p.type_name, p.field_name, NULL, p.ordinal, p.step_position,
       p.position, p.param_name, p.argument_path,
       d.source_name, d.source_line, d.source_column
  FROM graphitron_reference_for_step_arg_mapping_pair p
  JOIN graphitron_reference_for d
    ON d.graph_name = p.graph_name AND d.type_name = p.type_name
   AND d.field_name = p.field_name AND d.ordinal = p.ordinal;
COMMENT ON VIEW intent_argmapping_pair IS 'Every argMapping pair any directive spells, in one shape: the seven pair relations of that family normalised onto the widest arm''s projection, with a site literal naming which one a row came from. Those relations are one shape only in their tail (position, param_name, argument_path); their use-site keys run from four columns to seven, so a reader over all of them either widens by hand or asks this. Naming it keeps the widening written once, which is the point: the arms are hand-written SELECTs over relations of differing key arity, a typo in one is exactly the drift a cross-site parity test exists to catch, and a second consumer re-spelling the union is how two readings of one population begin disagreeing. Every reader of a pair''s resolution therefore departs from here, and one needing an arm''s own extra key columns joins this relation on site plus the use-site key rather than parsing anything or re-assembling the union. Non-destructive by construction: it adds a discriminator and drops nothing, so an arm''s own relation stays where a reader of that site alone goes. The owning application''s source position is carried the same way and for the same reason: every arm reaches one by a join on its own key, all eight joins are inner (each pair relation has a foreign key onto its owner, the three step arms through their step relation), and a reader assembling that eight-way lookup for itself is exactly the drift this relation exists to prevent. It is what lets a detection over a pair''s resolution locate its message without knowing which of the seven relations the pair came from. Eight site values over seven relations, the field-condition relation being a shared coordinate whose owning type''s kind splits it into an output-field site and an input-field site with different heads and different emitters, which is how the capture side already tells those halves apart. The grain is the pair''s own with ordinal intact: @routine and @reference are repeatable and each application carries its own argMapping, so collapsing to one row per field coordinate would resolve one application''s paths and silently drop its siblings, which is the one move the nearest sibling view makes that this family must not.';
COMMENT ON COLUMN intent_argmapping_pair.graph_name IS 'the owning graph''s partition, carried from every arm''s own relation';
COMMENT ON COLUMN intent_argmapping_pair.site IS 'which SDL site spelled this pair, in a closed vocabulary of eight: ROUTINE, SERVICE, FIELD_CONDITION, INPUT_FIELD_CONDITION, ARGUMENT_CONDITION, FIELD_REFERENCE_STEP, ARGUMENT_REFERENCE_STEP, REFERENCE_FOR_STEP. Seven relations and eight values, the two condition sites sharing one. The column a consumer switches on, and the one a test pins so a case reaching an arm is a case naming it';
COMMENT ON COLUMN intent_argmapping_pair.use_site IS 'the consuming coordinate serialized, in intent_input_occurrence_path''s own vocabulary extended by two forms: Type.field for a field-grain site with the argument in parentheses after it, then #<ordinal> for a repeatable application and [<step>] for a step position within one. With site and position this is the relation''s grain, and it is the coordinate a rejection about a pair has to be able to name, an author told to change a definition-keyed fact needing to know which use site is asking. Serialized rather than assembled at each reader because a message needs one string and the components differ by arm; those components are columns beside it, so nothing ever parses this';
COMMENT ON COLUMN intent_argmapping_pair.type_name IS 'the spelling site''s owning type; with the field below, the coordinate all seven relations lead with and the one graphitron_argument_path_segment anchors on';
COMMENT ON COLUMN intent_argmapping_pair.field_name IS 'the spelling site''s field name within the owning type. An input field on the INPUT_FIELD_CONDITION arm, an output field on every other';
COMMENT ON COLUMN intent_argmapping_pair.argument_name IS 'the argument the site sits on, on the two argument-grain arms (ARGUMENT_CONDITION, ARGUMENT_REFERENCE_STEP); NULL on the other six, whose sites sit on a field. Determined by site rather than independent of it, which is what makes the nullness a stated rule instead of a missing value';
COMMENT ON COLUMN intent_argmapping_pair.ordinal IS 'the owning application''s ordinal, on the four arms whose directive is repeatable (ROUTINE and the three step sites); NULL on SERVICE and the two condition sites, which are not repeatable and carry no ordinal';
COMMENT ON COLUMN intent_argmapping_pair.step_position IS 'the owning step''s 0-based position within its application''s path, on the three step arms; NULL on the other five, which have no step';
COMMENT ON COLUMN intent_argmapping_pair.position IS '0-based position of the pair within its own argMapping list, carried unchanged from every arm; part of the grain, so an author''s duplicate parameter survives here as it does in the base relations';
COMMENT ON COLUMN intent_argmapping_pair.param_name IS 'the left side of the pair: the Java or routine parameter the path binds to';
COMMENT ON COLUMN intent_argmapping_pair.argument_path IS 'the right side as written, spelled exactly as the arm''s own relation spells it, so a pair reaches its own segment decomposition by joining graphitron_argument_path_segment on the coordinate and this column';
COMMENT ON COLUMN intent_argmapping_pair.source_name IS 'the SDL file the owning directive application was captured from, joined from that application''s own relation rather than from the field: a rejection about a pair has to point at the argMapping the author wrote, and a repeatable directive''s second application sits on a line the field''s own position does not name. NULL where the application carries no position, on graphitron_routine.source_name''s terms. The pair itself carries no finer position, the eight owners recording the application and not the list entry, so two pairs of one application share a location and the message tells them apart by naming the entry';
COMMENT ON COLUMN intent_argmapping_pair.source_line IS 'source line of the owning directive application, 1-based per the graphql-java convention; NULL exactly where source_name is';
COMMENT ON COLUMN intent_argmapping_pair.source_column IS 'source column of the owning directive application, 1-based per the graphql-java convention; NULL exactly where source_name is';

CREATE VIEW intent_argmapping_segment_binding
  (graph_name, site, use_site, type_name, field_name, position, argument_path,
   segment_position, segment_name, bound_kind, bound_type_name, bound_field_name,
   bound_argument_name) AS
WITH headed (graph_name, site, use_site, type_name, field_name, position, argument_path,
             head, head_kind) AS (
  SELECT ap.graph_name, ap.site, ap.use_site, ap.type_name, ap.field_name, ap.position,
         ap.argument_path, h.segment_name, 'ARGUMENT'
    FROM intent_argmapping_pair ap
    JOIN graphitron_argument_path_segment h
      ON h.graph_name = ap.graph_name AND h.type_name = ap.type_name
     AND h.field_name = ap.field_name AND h.argument_path = ap.argument_path
     AND h.position = 0
    JOIN graphql_argument a
      ON a.graph_name = ap.graph_name AND a.type_name = ap.type_name
     AND a.field_name = ap.field_name AND a.argument_name = h.segment_name
   WHERE ap.site IN ('ROUTINE', 'SERVICE', 'FIELD_CONDITION')
   UNION ALL
  SELECT ap.graph_name, ap.site, ap.use_site, ap.type_name, ap.field_name, ap.position,
         ap.argument_path, h.segment_name, 'ARGUMENT'
    FROM intent_argmapping_pair ap
    JOIN graphitron_argument_path_segment h
      ON h.graph_name = ap.graph_name AND h.type_name = ap.type_name
     AND h.field_name = ap.field_name AND h.argument_path = ap.argument_path
     AND h.position = 0
   WHERE ap.site = 'ARGUMENT_CONDITION' AND h.segment_name = ap.argument_name
   UNION ALL
  SELECT ap.graph_name, ap.site, ap.use_site, ap.type_name, ap.field_name, ap.position,
         ap.argument_path, h.segment_name, 'INPUT_FIELD'
    FROM intent_argmapping_pair ap
    JOIN graphitron_argument_path_segment h
      ON h.graph_name = ap.graph_name AND h.type_name = ap.type_name
     AND h.field_name = ap.field_name AND h.argument_path = ap.argument_path
     AND h.position = 0
   WHERE ap.site = 'INPUT_FIELD_CONDITION' AND h.segment_name = ap.field_name
)
SELECT h.graph_name, h.site, h.use_site, h.type_name, h.field_name, h.position, h.argument_path,
       0, h.head, 'ARGUMENT', h.type_name, h.field_name, h.head
  FROM headed h
 WHERE h.head_kind = 'ARGUMENT'
 UNION ALL
SELECT h.graph_name, h.site, h.use_site, h.type_name, h.field_name, h.position, h.argument_path,
       0, h.head, 'INPUT_FIELD', h.type_name, h.head, CAST(NULL AS VARCHAR)
  FROM headed h
 WHERE h.head_kind = 'INPUT_FIELD'
 UNION ALL
SELECT DISTINCT h.graph_name, h.site, h.use_site, h.type_name, h.field_name, h.position,
       h.argument_path, sg.position, sg.segment_name,
       'INPUT_FIELD', lf.container_type_name, lf.field_name, CAST(NULL AS VARCHAR)
  FROM headed h
  JOIN intent_input_occurrence_path p
    ON p.graph_name = h.graph_name AND p.root_type_name = h.type_name
   AND p.root_field_name = h.field_name AND p.root_argument_name = h.head
  JOIN intent_input_occurrence_path_step lf
    ON lf.graph_name = p.graph_name AND lf.path = p.path AND lf.ordinal = p.depth
  JOIN graphitron_argument_path_segment sg
    ON sg.graph_name = h.graph_name AND sg.type_name = h.type_name
   AND sg.field_name = h.field_name AND sg.argument_path = h.argument_path
   AND sg.position = p.depth
 WHERE h.head_kind = 'ARGUMENT' AND p.depth >= 1
   AND NOT EXISTS (SELECT 1 FROM intent_input_occurrence_path_step o
                    WHERE o.graph_name = p.graph_name AND o.path = p.path
                      AND NOT EXISTS (
                        SELECT 1 FROM graphitron_argument_path_segment s2
                         WHERE s2.graph_name = h.graph_name AND s2.type_name = h.type_name
                           AND s2.field_name = h.field_name
                           AND s2.argument_path = h.argument_path
                           AND s2.position = o.ordinal
                           AND s2.segment_name = o.field_name))
 UNION ALL
SELECT DISTINCT h.graph_name, h.site, h.use_site, h.type_name, h.field_name, h.position,
       h.argument_path, sg.position, sg.segment_name,
       'INPUT_FIELD', lf.container_type_name, lf.field_name, CAST(NULL AS VARCHAR)
  FROM headed h
  JOIN intent_input_occurrence_path_step an
    ON an.graph_name = h.graph_name AND an.container_type_name = h.type_name
   AND an.field_name = h.head
  JOIN intent_input_occurrence_path_step lf
    ON lf.graph_name = an.graph_name AND lf.path = an.path AND lf.ordinal > an.ordinal
  JOIN graphitron_argument_path_segment sg
    ON sg.graph_name = h.graph_name AND sg.type_name = h.type_name
   AND sg.field_name = h.field_name AND sg.argument_path = h.argument_path
   AND sg.position = lf.ordinal - an.ordinal
 WHERE h.head_kind = 'INPUT_FIELD'
   AND NOT EXISTS (SELECT 1 FROM intent_input_occurrence_path_step o
                    WHERE o.graph_name = an.graph_name AND o.path = an.path
                      AND o.ordinal > an.ordinal AND o.ordinal <= lf.ordinal
                      AND NOT EXISTS (
                        SELECT 1 FROM graphitron_argument_path_segment s2
                         WHERE s2.graph_name = h.graph_name AND s2.type_name = h.type_name
                           AND s2.field_name = h.field_name
                           AND s2.argument_path = h.argument_path
                           AND s2.position = o.ordinal - an.ordinal
                           AND s2.segment_name = o.field_name));
COMMENT ON VIEW intent_argmapping_segment_binding IS 'What each segment of an argMapping path binds to, one row per segment that names something reachable. The grain is the segment and not the path, which is the whole of the design: graphitron_argument_path_segment already says whether a segment exists at a position, so a position that has a segment and no row here means exactly one thing, it means it locally, and no verdict vocabulary is needed to say it. A path that stops halfway is therefore a prefix of rows rather than a stated silence, and the reader who wants to know where it stopped reads the last position that bound. One row per segment of every pair row of intent_argmapping_pair, at every site that spells an argMapping, which is what makes uniformity across @routine, @service and @condition structural rather than three call sites agreeing by discipline. A keying over intent_input_occurrence_path rather than a second walk of the input surface, joined through graphitron_argument_path_segment so neither the occurrence key nor the written path is ever split: the segment relation exists precisely so no reader has to, and a second decomposition here would be two spellings of one resolution that agree until one of them changes. The head is not always an argument, so position 0 has three arms, and which slots a head may name is the walk''s own rule read off the site: every argument of the field at a @routine, @service or output-field @condition, the pair''s own argument at an argument-site @condition, the pair''s own input field at an input-field @condition, and nothing at all at a path-step @condition, where no arm fires and the path binds nothing at any position. Positions below the head need no recursion, and that is what lets this be a view at all: every prefix of an occurrence path is its own row, so a segment at position j binds exactly when some occurrence path of depth j has every step matching the segment at the same ordinal, which is a join per position rather than a walk. The rows are prefix-dense by construction, since the prefix of a matching path matches too, so the bound positions of a pair are always 0 through some k with no hole; a reader may rely on that rather than checking for one. DISTINCT because the join is one-to-many in occurrence paths while the answer is one: two paths agreeing on the field names at ordinals 1 through j are descending the same input fields from the same root type, so their container types agree ordinal by ordinal and the tied rows cannot disagree. Two caveats the arms inherit. The occurrence expansion stops at a type already visited on the path, the classification walk''s own first-visit guard restated, so a cyclic re-entry contributes no step and a path that would have re-entered binds up to the last segment before the cycle and no further; that stop is load-bearing rather than incidental. And an input type no argument reaches has no occurrence row to descend, so a dotted input-field head binds at position 0 and nowhere below, which looks from here like any other path that stopped; the two are deliberately one fact at this grain, and a reader who needs them apart joins intent_input_occurrence_path_step to ask whether the head''s type is reached at all.';
COMMENT ON COLUMN intent_argmapping_segment_binding.graph_name IS 'the owning graph''s partition, carried from the pair relation';
COMMENT ON COLUMN intent_argmapping_segment_binding.site IS 'which SDL site spelled the pair, in intent_argmapping_pair''s closed vocabulary of eight; with the use-site key, the position and the segment position this is the grain, and it is what a consumer switches on to know whether an emitter is wired for the answer';
COMMENT ON COLUMN intent_argmapping_segment_binding.use_site IS 'the consuming coordinate, serialized as intent_argmapping_pair serializes it; the coordinate a rejection about this pair names, and the key a reader joins that relation on to recover the arm''s own components';
COMMENT ON COLUMN intent_argmapping_segment_binding.type_name IS 'the spelling site''s owning type, carried so the segment decomposition is one join away';
COMMENT ON COLUMN intent_argmapping_segment_binding.field_name IS 'the spelling site''s field name within that type';
COMMENT ON COLUMN intent_argmapping_segment_binding.position IS 'the pair''s 0-based position within its own argMapping list';
COMMENT ON COLUMN intent_argmapping_segment_binding.argument_path IS 'the path as written, carried so a message can quote what the author wrote and so the segment rows are reachable without a second read of the pair';
COMMENT ON COLUMN intent_argmapping_segment_binding.segment_position IS 'the bound segment''s 0-based position within the path, the same ordinal graphitron_argument_path_segment gives it. Position 0 is the head; the highest bound position of a pair is where the path stopped, and a segment existing one above it is a name that resolved to nothing';
COMMENT ON COLUMN intent_argmapping_segment_binding.segment_name IS 'the segment as the author spelled it, carried beside what it bound so a reader never re-joins the segment relation to say which name this row is about';
COMMENT ON COLUMN intent_argmapping_segment_binding.bound_kind IS 'ARGUMENT where the segment bound a field argument, which only position 0 can do and only at a site whose slots are arguments; INPUT_FIELD where it bound an input field, which is every position below the head and also position 0 at an input-field @condition. A closed two-value vocabulary, and the column saying which of the two @nodeId relations a reader joins to ask whether this binding carries one';
COMMENT ON COLUMN intent_argmapping_segment_binding.bound_type_name IS 'the bound thing''s owning type: the argument''s own owning type on an ARGUMENT binding, the input object declaring the field on an INPUT_FIELD binding';
COMMENT ON COLUMN intent_argmapping_segment_binding.bound_field_name IS 'the bound thing''s owning field on an ARGUMENT binding, and the input field itself on an INPUT_FIELD binding. With the columns around it this is the @nodeId relation''s own key, so a binding''s directive row and its source position are one join away';
COMMENT ON COLUMN intent_argmapping_segment_binding.bound_argument_name IS 'the argument''s name on an ARGUMENT binding; NULL on an INPUT_FIELD binding, whose key needs no argument component';

CREATE VIEW intent_argmapping_binding_leaf
  (graph_name, site, use_site, type_name, field_name, position, argument_path,
   segment_position, bound_kind, bound_type_name, bound_field_name, bound_argument_name,
   node_id_declared, node_type_ref, trailing_segments) AS
SELECT b.graph_name, b.site, b.use_site, b.type_name, b.field_name, b.position, b.argument_path,
       b.segment_position, b.bound_kind, b.bound_type_name, b.bound_field_name,
       b.bound_argument_name,
       CASE WHEN an.type_name IS NOT NULL OR fn.type_name IS NOT NULL THEN TRUE ELSE FALSE END,
       COALESCE(an.node_type_ref, fn.node_type_ref),
       CAST((SELECT COUNT(*) FROM graphitron_argument_path_segment t
              WHERE t.graph_name = b.graph_name AND t.type_name = b.type_name
                AND t.field_name = b.field_name AND t.argument_path = b.argument_path
                AND t.position > b.segment_position) AS INT)
  FROM intent_argmapping_segment_binding b
  LEFT JOIN graphitron_argument_node_id an
    ON b.bound_kind = 'ARGUMENT' AND an.graph_name = b.graph_name
   AND an.type_name = b.bound_type_name AND an.field_name = b.bound_field_name
   AND an.argument_name = b.bound_argument_name
  LEFT JOIN graphitron_field_node_id fn
    ON b.bound_kind = 'INPUT_FIELD' AND fn.graph_name = b.graph_name
   AND fn.type_name = b.bound_type_name AND fn.field_name = b.bound_field_name
 WHERE NOT EXISTS (SELECT 1 FROM intent_argmapping_segment_binding nx
                    WHERE nx.graph_name = b.graph_name AND nx.site = b.site
                      AND nx.use_site = b.use_site AND nx.position = b.position
                      AND nx.segment_position = b.segment_position + 1);
COMMENT ON VIEW intent_argmapping_binding_leaf IS 'The last thing an argMapping path bound, and whether that thing carries a @nodeId. A reduction over intent_argmapping_segment_binding and not a resolution of its own, which is what the leaf reading costs: the leaf is the bound segment with no bound successor, and this view exists so that the readers needing it (the key projection beside it, and the detections that reject a bare or unresolvable spelling) share one spelling of "no bound successor" rather than one each. Prefix-density upstream is what makes the definition sound: the bound positions of a pair are 0 through some k with no hole, so "no successor" identifies exactly one row per pair and the arity is one answer rather than a set. Absence means the path bound nothing at all, which at a path-step @condition is every path (the walk resolves there against an empty slot map) and at every other site is a head naming no slot the site has in scope. Both are rejections the walk already returns from ArgBindingMap.of before the store is written, so neither is this relation''s to restate; the row is absent here because there is no leaf, not because the fact is unavailable, and a reader wanting it joins intent_argmapping_pair against this relation to find the pairs with no leaf. The @nodeId reading is two columns and not a verdict, because three answers are wanted and a fork would collapse two of them: no directive at all is the ordinary binding, a directive with typeName: is the projectable case, and a directive without one is the bare spelling that cannot infer a node type at this position, there being no containing table to infer it from. Which of the two @nodeId relations answers follows from bound_kind, so an argument binding and an input-field binding are one row shape rather than two readings.';
COMMENT ON COLUMN intent_argmapping_binding_leaf.graph_name IS 'the owning graph''s partition, carried from the binding relation';
COMMENT ON COLUMN intent_argmapping_binding_leaf.site IS 'which SDL site spelled the pair, in intent_argmapping_pair''s closed vocabulary of eight; with the use-site key and the position this is the grain, and it is what a consumer switches on to know whether an emitter is wired for the answer';
COMMENT ON COLUMN intent_argmapping_binding_leaf.use_site IS 'the consuming coordinate, serialized as intent_argmapping_pair serializes it; the coordinate a rejection about this pair names, and the key a reader joins that relation on to recover the arm''s own components';
COMMENT ON COLUMN intent_argmapping_binding_leaf.type_name IS 'the spelling site''s owning type, carried so the segment decomposition is one join away';
COMMENT ON COLUMN intent_argmapping_binding_leaf.field_name IS 'the spelling site''s field name within that type';
COMMENT ON COLUMN intent_argmapping_binding_leaf.position IS 'the pair''s 0-based position within its own argMapping list';
COMMENT ON COLUMN intent_argmapping_binding_leaf.argument_path IS 'the path as written, carried so a message can quote what the author wrote and so the segment rows are reachable without a second read of the pair';
COMMENT ON COLUMN intent_argmapping_binding_leaf.segment_position IS 'where the path stopped: the 0-based position of the last segment that bound. 0 means only the head bound, which on a single-segment path is the whole path and on a dotted one is a name below the head that resolved to nothing. The position a reader adds one to in order to name the first segment that bound nothing';
COMMENT ON COLUMN intent_argmapping_binding_leaf.bound_kind IS 'ARGUMENT where the leaf is a field argument, INPUT_FIELD where it is an input field; carried from the binding relation, and what tells an emitter which slot the wire value is read out of';
COMMENT ON COLUMN intent_argmapping_binding_leaf.bound_type_name IS 'the leaf''s owning type, carried from the binding relation';
COMMENT ON COLUMN intent_argmapping_binding_leaf.bound_field_name IS 'the leaf''s owning field on an ARGUMENT leaf, the input field itself on an INPUT_FIELD leaf';
COMMENT ON COLUMN intent_argmapping_binding_leaf.bound_argument_name IS 'the argument''s name on an ARGUMENT leaf; NULL on an INPUT_FIELD leaf, whose key needs no argument component';
COMMENT ON COLUMN intent_argmapping_binding_leaf.node_id_declared IS 'whether the leaf carries a @nodeId at all. FALSE is the ordinary binding, where the wire value is the value and no decode is implied; TRUE says a decode is, and the column beside it says whether the author named what to decode against. Kept as its own column rather than read off a NULL node type, because the two NULLs mean opposite things and a reader collapsing them would treat the bare spelling as an ordinary binding, which is the silence the projection exists to close';
COMMENT ON COLUMN intent_argmapping_binding_leaf.node_type_ref IS 'the typeName: the leaf''s @nodeId names, as written; NULL where the directive is absent, and NULL where it is present without one, which at this position is a rejection rather than an inference, there being no containing table to infer a node type from. Read with node_id_declared, never alone';
COMMENT ON COLUMN intent_argmapping_binding_leaf.trailing_segments IS 'how many segments the path spells beyond the leaf: 0 where the path bound everything it spelled, 1 where one name is left over, more where several are. A count rather than a flag, because the readings differ: zero on a @nodeId leaf is the bare binding a rejection closes, one is a key-column projection, and two or more is a typo or a nested form neither this relation nor its readers claim to resolve. Counted over the segment rows above the leaf''s position rather than derived from a path length, so it is arithmetic over rows and never a parse';

CREATE VIEW intent_resolved_node_key_projection
  (graph_name, site, use_site, type_name, field_name, position, argument_path,
   bound_kind, bound_type_name, bound_field_name, bound_argument_name,
   node_type_name, column_name, key_position, tier) AS
SELECT l.graph_name, l.site, l.use_site, l.type_name, l.field_name, l.position, l.argument_path,
       l.bound_kind, l.bound_type_name, l.bound_field_name, l.bound_argument_name,
       l.node_type_ref, k.column_name, k.position, k.tier
  FROM intent_argmapping_binding_leaf l
  JOIN graphitron_argument_path_segment sg
    ON sg.graph_name = l.graph_name AND sg.type_name = l.type_name
   AND sg.field_name = l.field_name AND sg.argument_path = l.argument_path
   AND sg.position = l.segment_position + 1
  JOIN intent_resolved_node_key_column k
    ON k.graph_name = l.graph_name AND k.type_name = l.node_type_ref
   AND UPPER(k.column_name) = sg.segment_name_upper
 WHERE l.node_type_ref IS NOT NULL
   AND l.trailing_segments = 1;
COMMENT ON VIEW intent_resolved_node_key_projection IS 'An argMapping binding that decodes a node id and projects one column out of the decoded key: the pairs whose leaf carries a @nodeId(typeName:), whose path spells exactly one segment beyond that leaf, and whose trailing segment names one of that node type''s resolved key columns. The reduction the whole item turns on, and the row an emitter reads to know which column of a decoded record to hand a routine parameter. A reduction over the two relations beside it rather than a derivation of its own, which is what the resolved_ prefix names: the path resolution is intent_argmapping_segment_binding''s, reduced to a leaf next door, and the key list is intent_resolved_node_key_column''s; this is only where the two meet. Exactly one trailing segment, never a minimum: two or more is a typo or a nested object form, and admitting it here would let a projection resolve off a path whose middle the author got wrong. The trailing segment is reached by position, one above the leaf''s own, which is the same arithmetic the count beside it does and not a second statement of it: the count says how many there are and the join says which one, and neither could be derived from the other without knowing the leaf''s position anyway. Matching is case-insensitive, which is inherited rather than introduced: the catalog resolution uses the same rule, so a projection spelled the generated way and one spelled the SQL way are one answer. It is spelled once, here, and every consumer asking whether a segment names a key column asks by joining this relation rather than by repeating the predicate: the detection beside it states the unknown column as the absence of a row here, which is what keeps one match from becoming two that agree until one changes. The authored side is folded on its own base relation, graphitron_argument_path_segment.segment_name_upper, which is where the schema mints a fold; the key-column side is folded at the crossing instead, that relation being a pick across three tiers with no one base relation to reach a generated column through, and forwarding one through a derived view being what the folded columns'' own comments forbid. The column comes out under the winning tier''s own spelling and not the author''s, because it is the decode''s key list the projection indexes into, and naming the column rather than a tuple position is what makes a transposed composite-key projection unconstructable. Absence means this pair is not a projection, and every way of arriving at that absence is a query over the relations beside it rather than a fact this one withheld: a leaf with nothing trailing is the bare form a rejection closes, a leaf with two or more trailing segments is the typo, a trailing segment matching no key column is the unknown column, a leaf carrying @nodeId with no typeName: is the missing type name, and a pair with no leaf row at all is a path the walk rejects before the store is written. None of them is this relation''s to report, which is what keeps it a positive population an emitter can trust rather than a verdict it has to interpret.';
COMMENT ON COLUMN intent_resolved_node_key_projection.graph_name IS 'the owning graph''s partition, carried from both sides of the reduction, which agree on it by the join';
COMMENT ON COLUMN intent_resolved_node_key_projection.site IS 'which SDL site spelled the pair, in intent_argmapping_pair''s closed vocabulary of eight; the column a consumer reads to know whether an emitter is wired for this projection yet';
COMMENT ON COLUMN intent_resolved_node_key_projection.use_site IS 'the consuming coordinate, serialized as intent_argmapping_pair serializes it; with site and position the grain, and the key a planner joins the pair relation on to recover the application ordinal a command row needs';
COMMENT ON COLUMN intent_resolved_node_key_projection.type_name IS 'the spelling site''s owning type';
COMMENT ON COLUMN intent_resolved_node_key_projection.field_name IS 'the spelling site''s field name within that type';
COMMENT ON COLUMN intent_resolved_node_key_projection.position IS 'the pair''s 0-based position within its own argMapping list; two parameters bound from one node id are two rows at two positions, which is what lets a composite key fill both';
COMMENT ON COLUMN intent_resolved_node_key_projection.argument_path IS 'the path as written, carried so a message quotes the author''s own spelling rather than the resolution''s';
COMMENT ON COLUMN intent_resolved_node_key_projection.bound_kind IS 'ARGUMENT where the decoded node id is a field argument, INPUT_FIELD where it is an input field reached below one; carried from the binding resolution, and what tells an emitter which slot the wire value is read out of';
COMMENT ON COLUMN intent_resolved_node_key_projection.bound_type_name IS 'the leaf''s owning type, carried from the binding resolution';
COMMENT ON COLUMN intent_resolved_node_key_projection.bound_field_name IS 'the leaf''s owning field on an ARGUMENT leaf, the input field itself on an INPUT_FIELD leaf';
COMMENT ON COLUMN intent_resolved_node_key_projection.bound_argument_name IS 'the argument''s name on an ARGUMENT leaf; NULL on an INPUT_FIELD leaf, whose key needs no argument component';
COMMENT ON COLUMN intent_resolved_node_key_projection.node_type_name IS 'the node type the leaf''s @nodeId names, as written; what the wire id decodes against, and the type whose key list the column below belongs to';
COMMENT ON COLUMN intent_resolved_node_key_projection.column_name IS 'the projected key column, spelled as the winning key-column tier spells it rather than as the author wrote it: the decode returns values against that tier''s list, so this is the spelling that lines up with it. The author''s own spelling is in the path, one segment join away';
COMMENT ON COLUMN intent_resolved_node_key_projection.key_position IS 'the projected column''s 0-based position within the node key. Not what an emitter reads (it names the column) but what a reader checking a composite projection asks, and what shows two parameters bound from one id take two different positions of one key';
COMMENT ON COLUMN intent_resolved_node_key_projection.tier IS 'which key-column population answered for this node type, carried from intent_resolved_node_key_column''s closed vocabulary of three; a diagnostic explaining why a column is or is not available reads it rather than re-deriving the precedence';

CREATE VIEW intent_argmapping_projection_defect
  (graph_name, site, use_site, type_name, field_name, position, param_name, argument_path,
   verdict, bound_kind, bound_type_name, bound_field_name, bound_argument_name,
   node_type_ref, trailing_segment_name,
   source_name, source_line, source_column) AS
SELECT l.graph_name, l.site, l.use_site, l.type_name, l.field_name, l.position, ap.param_name,
       l.argument_path, 'BARE_NODE_ID', l.bound_kind, l.bound_type_name, l.bound_field_name,
       l.bound_argument_name, l.node_type_ref, CAST(NULL AS VARCHAR),
       ap.source_name, ap.source_line, ap.source_column
  FROM intent_argmapping_binding_leaf l
  JOIN intent_argmapping_pair ap
    ON ap.graph_name = l.graph_name AND ap.site = l.site AND ap.use_site = l.use_site
   AND ap.position = l.position
 WHERE l.node_id_declared AND l.trailing_segments = 0
 UNION ALL
SELECT l.graph_name, l.site, l.use_site, l.type_name, l.field_name, l.position, ap.param_name,
       l.argument_path, 'MISSING_TYPE_NAME', l.bound_kind, l.bound_type_name, l.bound_field_name,
       l.bound_argument_name, l.node_type_ref, sg.segment_name,
       ap.source_name, ap.source_line, ap.source_column
  FROM intent_argmapping_binding_leaf l
  JOIN intent_argmapping_pair ap
    ON ap.graph_name = l.graph_name AND ap.site = l.site AND ap.use_site = l.use_site
   AND ap.position = l.position
  JOIN graphitron_argument_path_segment sg
    ON sg.graph_name = l.graph_name AND sg.type_name = l.type_name
   AND sg.field_name = l.field_name AND sg.argument_path = l.argument_path
   AND sg.position = l.segment_position + 1
 WHERE l.node_id_declared AND l.node_type_ref IS NULL AND l.trailing_segments = 1
 UNION ALL
SELECT l.graph_name, l.site, l.use_site, l.type_name, l.field_name, l.position, ap.param_name,
       l.argument_path, 'UNKNOWN_KEY_COLUMN', l.bound_kind, l.bound_type_name, l.bound_field_name,
       l.bound_argument_name, l.node_type_ref, sg.segment_name,
       ap.source_name, ap.source_line, ap.source_column
  FROM intent_argmapping_binding_leaf l
  JOIN intent_argmapping_pair ap
    ON ap.graph_name = l.graph_name AND ap.site = l.site AND ap.use_site = l.use_site
   AND ap.position = l.position
  JOIN graphitron_argument_path_segment sg
    ON sg.graph_name = l.graph_name AND sg.type_name = l.type_name
   AND sg.field_name = l.field_name AND sg.argument_path = l.argument_path
   AND sg.position = l.segment_position + 1
 WHERE l.node_id_declared AND l.node_type_ref IS NOT NULL AND l.trailing_segments = 1
   AND NOT EXISTS (SELECT 1 FROM intent_resolved_node_key_projection pr
                    WHERE pr.graph_name = l.graph_name AND pr.site = l.site
                      AND pr.use_site = l.use_site AND pr.position = l.position)
;
COMMENT ON VIEW intent_argmapping_projection_defect IS 'What is wrong with an argMapping binding that opens a @nodeId: one row per defective pair, in a closed verdict vocabulary of three, over the binding leaf and the resolved key columns alone. The rejections that close the silent hole this family had, where a path bound a node id and the base64 wire id went to the database verbatim with nothing in the build saying a word. Every arm is a positive statement about a captured population rather than a negative space maintained by hand: the leaf relation says what each path bound and whether that thing declares a decode, and these are the three ways a declared decode fails to become a projection. Which arm fires is decided by trailing_segments and nothing else, so the arms are disjoint by construction and no precedence rule is needed. Zero trailing segments means the author did not ask for a projection at all, and BARE_NODE_ID is that fact whether or not the directive names a type; the remedy differs in a second clause the consumer adds from node_type_ref, not in a second verdict, because the defect is one. Exactly one trailing segment means the author did ask, and then the resolution either succeeds (a row of intent_resolved_node_key_projection and no row here) or names what stopped it: MISSING_TYPE_NAME where the directive carries no typeName: and there is no containing table at this position to infer one from, UNKNOWN_KEY_COLUMN where the trailing segment matches no resolved key column of the named type. Two or more trailing segments is deliberately not an arm: the walk rejects walking through a scalar leaf and keeps rejecting it after the grammar admits one trailing segment, so an arm here would double-report a rejection the error stream already carries. The same reasoning keeps the undeclared decode out, and that boundary is worth stating because an earlier shape of this view had an arm for it. What a dot opens is a node id, so the grammar admits a trailing segment only where the thing at that position declares a @nodeId; an ID that declares none has nothing to open and takes the walk''s own rejection, exactly as a String does. An arm here would double-report it, and worse, would say that the grammar admits something it cannot interpret. Two further shapes stay out. A path that bound nothing has no leaf row and is ArgBindingMap.of''s rejection, a head naming no slot in scope and a path-step @condition resolving against an empty slot map both. And a projection that resolves at a site whose emitter is not wired yet is a deferral rather than an author defect, which is why it is not a verdict here: whether an emitter exists is a fact about the generator''s own code and not about the schema, so its arm lives with the consumer that knows the wired set (no.sikt.graphitron.rewrite.derive.ArgmappingProjectionDefects) rather than being asserted by a view that cannot see it. Every arm is use-keyed rather than definition-keyed, which is the point of resolving at the pair''s grain: one input type can be consumed by a routine call with no containing table and by a table-bound mutation where inference works, so an author told to add typeName: is being asked to satisfy a use-site constraint and the message has to name the use site that is asking. Locations are the owning directive application''s, carried from intent_argmapping_pair, so a message points at the argMapping the author wrote rather than at the input type''s declaration. There is no message column: the closed vocabulary plus the witness columns are the fact base, and the prose belongs with the consumer that composes it, which is also where the wording converges with the two hand-written sites stating this same condition elsewhere. Nor is there a rendered candidate list, though a message about a named type wants one: a consumer joining intent_resolved_node_key_column on the graph and node_type_ref gets the columns as rows in key order, and a render here would have to be split apart to be used, which is the one thing no reader of this schema does.';
COMMENT ON COLUMN intent_argmapping_projection_defect.graph_name IS 'the owning graph''s partition, carried from the binding leaf';
COMMENT ON COLUMN intent_argmapping_projection_defect.site IS 'which SDL site spelled the defective pair, in intent_argmapping_pair''s closed vocabulary of eight; with the use-site key and the position this is the grain, and it is what a message reads to name the directive the author wrote';
COMMENT ON COLUMN intent_argmapping_projection_defect.use_site IS 'the consuming coordinate, serialized as intent_argmapping_pair serializes it: the use site whose constraint is being violated, which a message about a definition-keyed remedy has to name so the author knows which consumer is asking';
COMMENT ON COLUMN intent_argmapping_projection_defect.type_name IS 'the spelling site''s owning type; with the field beside it, the coordinate a validation error attaches to';
COMMENT ON COLUMN intent_argmapping_projection_defect.field_name IS 'the spelling site''s field name within that type. An input field on the INPUT_FIELD_CONDITION arm, an output field on every other';
COMMENT ON COLUMN intent_argmapping_projection_defect.position IS 'the defective pair''s 0-based position within its own argMapping list; part of the grain, so two defective pairs of one application are two rows rather than one';
COMMENT ON COLUMN intent_argmapping_projection_defect.param_name IS 'the left side of the pair, carried from intent_argmapping_pair so a message quotes the whole entry the author wrote rather than half of it';
COMMENT ON COLUMN intent_argmapping_projection_defect.argument_path IS 'the right side as written; quoted in the message beside the parameter, and the column the segment decomposition is reachable through';
COMMENT ON COLUMN intent_argmapping_projection_defect.verdict IS 'which defect, in a closed vocabulary of three: BARE_NODE_ID where a declared decode names no key column to project out of it, MISSING_TYPE_NAME where a projection is asked for against a @nodeId carrying no typeName:, UNKNOWN_KEY_COLUMN where the trailing segment names no resolved key column of the named type. Disjoint by trailing_segments, so this column is a discriminator a consumer switches on and never a precedence to re-test';
COMMENT ON COLUMN intent_argmapping_projection_defect.bound_kind IS 'ARGUMENT where the defective leaf is a field argument, INPUT_FIELD where it is an input field; carried from the leaf, and what tells a reader which @nodeId relation the directive sits on';
COMMENT ON COLUMN intent_argmapping_projection_defect.bound_type_name IS 'the leaf''s owning type, carried from the leaf: the type declaring the input field on an INPUT_FIELD leaf, the argument''s own owning type on an ARGUMENT one. With the two columns beside it, the @nodeId row''s own key, so a consumer wanting the directive''s own source position is one join away';
COMMENT ON COLUMN intent_argmapping_projection_defect.bound_field_name IS 'the leaf''s owning field on an ARGUMENT leaf, the input field itself on an INPUT_FIELD leaf';
COMMENT ON COLUMN intent_argmapping_projection_defect.bound_argument_name IS 'the argument''s name on an ARGUMENT leaf; NULL on an INPUT_FIELD leaf, whose key needs no argument component';
COMMENT ON COLUMN intent_argmapping_projection_defect.node_type_ref IS 'the typeName: the leaf''s @nodeId names, as written. NULL on every MISSING_TYPE_NAME row, that being the arm''s own condition, and NULL or not on a BARE_NODE_ID row, which is the second clause of that arm''s remedy rather than a second verdict. Never NULL on an UNKNOWN_KEY_COLUMN row';
COMMENT ON COLUMN intent_argmapping_projection_defect.trailing_segment_name IS 'the segment the author spelled beyond the leaf, as written: what the projection would have named. NULL exactly on the BARE_NODE_ID arm, where there is no such segment, which is the stated absent bucket rather than a missing value. Reached by position from the leaf rather than by splitting the path';
COMMENT ON COLUMN intent_argmapping_projection_defect.source_name IS 'the SDL file the owning directive application was captured from, carried from intent_argmapping_pair; NULL where that application carries no position';
COMMENT ON COLUMN intent_argmapping_projection_defect.source_line IS 'source line of the owning directive application, 1-based; NULL exactly where source_name is';
COMMENT ON COLUMN intent_argmapping_projection_defect.source_column IS 'source column of the owning directive application, 1-based; NULL exactly where source_name is';

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
  ('graphitron_', 'The decoded graphitron reading', 2, 'What graphitron makes of the SDL document: the decoded directives, and the provenance of the rows macro expansion mints. A row here is a decode of a captured application rather than a conclusion drawn about the schema: it says what a directive application spelled, in graphitron''s vocabulary instead of the document''s. That makes it a derivation whose producer runs at capture cadence, not a second transcription.'),
  ('sql_', 'The consumer database catalog', 3, 'What the consumer''s database declares, read through jOOQ''s generated model, plus what that generated model states about the catalog it was generated from. Not jooq_: naming a family for its reader is what this name replaces, because jOOQ defines neither table nor column nor foreign key. The second clause is the narrower residency the generated-model facts earn, sql_table.class_fqn and the node-identity metadata a generated table class publishes: the corpus is one generated package, refreshed as one unit by one walk, and a prefix boundary drawn through the middle of that unit would buy a tidier charter at the price of a family that no longer matches a refresh.'),
  ('jvm_', 'The compile classpath census', 4, 'What the classfiles on the compile classpath declare. Not extension_: naming a family for a presumed role is what this name replaces, because an ObjectMapper on the classpath extends nothing yet still earns a row.'),
  ('java_', 'The consumer''s Java sources', 5, 'What the consumer''s .java sources declare, read by an unattributed parse: where each class, method and field is written, and what its doc comment says. Its own family beside jvm_ rather than columns on it, because the two are separate populations on separate cadences that may legitimately disagree: a source parse yields arity where a classfile yields a descriptor, and the jvm_ census excludes the generated jOOQ package this family has to answer for. Named for the language whose declarations it transcribes, and distinct from javac_, which holds what the compiler concluded about generated sources rather than what a parse read from authored ones.'),
  ('javac_', 'The compile oracle''s verdicts', 6, 'What the JDK compiler reports about the emitted sources, written in javax.tools.Diagnostic''s terms.'),
  ('walk_', 'The legacy walk''s reach', 7, 'What the legacy classification walk registered and what it bound, transcribed in the walk''s own vocabulary: its registries'' reach as membership rows, and its backing resolution as the differential a store-native derivation checks itself against. Naming the family for the retiring walk gives the name its own retirement clock: when the walk is gone, the family has no referent. Its relations retire on separate clocks under that one, each draining as its own consumer migrates.'),
  ('intent_', 'Derived intent', 8, 'The third and topmost layer of the SDL depth ordering, graphql_ under graphitron_ under this name, whose upper two layers are both derivation over what graphql_ captured: what gets derived once something resolves and combines those readings into what the generator will actually do. The residents are views plus the materialized derivations whose table comments own why they cannot be views; that changes nothing about the name, since a family is named for whose vocabulary its rows are written in and materialization is not the discriminator. The stratum has two layers, and a new resident picks one deliberately: the base derivations (the authored claim views, one per grain; the structural classifier views, one per classifier so each carries exactly its own witness columns; the resolutions those classifiers stand on, which earn their own relation as soon as a second reader asks them and which layer among themselves on that same rule, a resolution keyed on a written name sitting under the ones keyed on a coordinate; the demand and exemption rule views, stated at the grain their rules are authored at), and the reductions over them (intent_resolved_field_claim and the resolved demand views, the resolution expressions a planning reader joins). No relation should acquire the prefix by drifting into it; each new derived resident is its own change.'),
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
