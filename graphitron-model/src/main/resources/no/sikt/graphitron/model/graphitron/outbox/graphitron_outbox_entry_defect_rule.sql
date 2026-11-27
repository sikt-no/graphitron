-- The configured table name split into its parts and folded, as the target rule splits it, so the
-- table it names is found through the catalog's folded-name index.
WITH named (graph_name, name_upper, schema_upper) AS (
  SELECT b.graph_name,
         UPPER(CASE WHEN POSITION('.', b.table_name) = 0 THEN b.table_name
                    ELSE REGEXP_REPLACE(b.table_name, '^[^.]*\.', '') END),
         CASE WHEN POSITION('.', b.table_name) = 0 THEN CAST(NULL AS VARCHAR)
              ELSE UPPER(REGEXP_REPLACE(b.table_name, '\..*$', '')) END
    FROM store_graph_outbox b
   WHERE b.table_name IS NOT NULL
),
-- A graph whose named handle class the classpath actually holds. Without it the member arms would
-- report a missing class as a missing member; the class itself is reported where it is configured.
session_handle_found (graph_name, handle_class) AS (
  SELECT h.graph_name, h.handle_class
    FROM store_graph_session_handle h
   WHERE EXISTS (SELECT 1 FROM code_graph_class c
                  WHERE c.graph_name = h.graph_name AND c.class_name = h.handle_class)
),
-- One accessor of the handle class under the property name $session.<member> names, the slots the
-- class declares itself: graphitron_session_handle_member's rows, read off the tables it joins.
handle_member (graph_name, member_name, source_name, class_name, method_name, descriptor) AS (
  SELECT handle.graph_name, slot.slot_name, slot.source_name, slot.class_name, slot.method_name,
         slot.descriptor
    FROM store_graph_session_handle handle
    JOIN code_graph_class source
      ON source.graph_name = handle.graph_name AND source.class_name = handle.handle_class
    JOIN code_read_slot slot
      ON slot.source_name = source.source_name AND slot.class_name = source.class_name
   WHERE slot.declaring_class = slot.class_name
),
-- The written entries of an @outbox whose message has no key by the entry's name though its field
-- writes or calls a service, so the message is judged; NOT_A_WRITE speaks for any other. The
-- message's outcome and the field's errors field come along, so a misfit is told by what the key
-- rule did not resolve and on which outcome, the rule's own joins not repeated.
outbox_unkeyed (graph_name, source_name, source_line, source_column, position, coordinate, ordinal,
                key_name, bound_to, write_kind, is_service, outcome, errors_field) AS (
  SELECT p.graph_name, p.source_name, p.source_line, p.source_column, p.position, m.coordinate,
         m.ordinal, p.target_name, p.bound_to, w.kind,
         CASE WHEN w.kind IS NULL THEN 1 ELSE 0 END, m.outcome,
         (SELECT po.outcome_type_name || '.' || po.errors_field_name
            FROM graphitron_field f
            JOIN graphitron_producer_outcome po
              ON po.graph_name = f.graph_name AND po.type_name = f.type_name
             AND po.field_name = f.field_name
           WHERE f.graph_name = m.graph_name AND f.coordinate = m.coordinate)
    FROM graphitron_outbox_message m
    JOIN graphitron_argmapping_site s
      ON s.graph_name = m.graph_name AND s.directive_name = 'outbox'
     AND s.coordinate = m.coordinate AND s.ordinal = m.ordinal
    JOIN graphitron_ast_argmapping_pair_entry p
      ON p.graph_name = s.graph_name AND p.source_name = s.source_name
     AND p.source_line = s.source_line AND p.source_column = s.source_column AND p.target_kind = 'NAME'
    LEFT JOIN graphitron_occurrence_producer_write w
      ON w.graph_name = m.graph_name AND w.coordinate = m.occurrence_coordinate_ref
   WHERE (w.kind IS NOT NULL
          OR EXISTS (SELECT 1 FROM graphitron_occurrence_producer op
                      WHERE op.graph_name = m.graph_name AND op.coordinate = m.occurrence_coordinate_ref
                        AND op.kind = 'SERVICE'))
     AND NOT EXISTS (SELECT 1 FROM graphitron_outbox_key k
                      WHERE k.graph_name = m.graph_name AND k.coordinate = m.coordinate
                        AND k.ordinal = m.ordinal AND k.key_name = p.target_name)
),
-- Each $result path of an @outbox that lands: whether a step of it is a list, and over a service's
-- value, the type a path read whole ends on, the member's yield or else the class it is read off.
result_landed (graph_name, coordinate, ordinal, key_name, through_list, type_name) AS (
  SELECT st.graph_name, st.coordinate, st.ordinal, st.target_name,
         MAX(CASE WHEN sf.is_list THEN 1 ELSE 0 END),
         MAX(CASE WHEN st.consumed = st.segments
                  THEN COALESCE(cs.slot_type, so_src.class_name) END)
    FROM graphitron_argmapping_step st
    JOIN graphitron_occurrence so ON so.graph_name = st.graph_name AND so.coordinate = st.step_coordinate
    JOIN graphitron_field sf ON sf.graph_name = so.graph_name AND sf.coordinate = so.element_coordinate
    LEFT JOIN graphitron_occurrence_binding_value v
      ON v.graph_name = st.graph_name AND v.coordinate = st.step_coordinate
    LEFT JOIN code_read_slot cs
      ON cs.source_name = v.slot_source_name AND cs.class_name = v.slot_class_name
     AND cs.method_name = v.slot_method_name AND cs.descriptor = v.slot_descriptor
    LEFT JOIN graphitron_occurrence_source_object so_src
      ON so_src.graph_name = st.graph_name AND so_src.coordinate = st.step_coordinate
   WHERE st.directive_name = 'outbox' AND st.bound_to LIKE '$result.%'
     AND EXISTS (SELECT 1 FROM graphitron_argmapping_landing l
                  WHERE l.graph_name = st.graph_name AND l.coordinate = st.coordinate
                    AND l.directive_name = st.directive_name AND l.ordinal = st.ordinal
                    AND l.owner = st.owner AND l.target_name = st.target_name AND l.target_kind = st.target_kind
                    AND l.call_coordinate = st.call_coordinate)
   GROUP BY st.graph_name, st.coordinate, st.ordinal, st.target_name
),
pair_defect (graph_name, source_name, source_line, source_column, position, code, detail) AS (
  -- A source found that cannot fill the place it binds, here a payload key: the whole session handle,
  -- a member whose yield JSON cannot carry, a landed $result path that reads no node id, column or
  -- value the message can take, $result on an ERROR message, $error on a SUCCESS one, or an $error
  -- the field's errors field does not resolve, or a field without one. A source not found is a
  -- defect of its sigil, raised where its facts are. The detail is the entry and why.
  SELECT u.graph_name, u.source_name, u.source_line, u.source_column, u.position,
         'ARGMAPPING_SOURCE_MISFIT',
         u.key_name || ': ' || u.bound_to || ' ('
           || CASE WHEN u.outcome = 'ERROR' AND u.bound_to LIKE '$result%'
                     THEN 'an ERROR message is written after the rollback, when nothing the call wrote is left'
                   WHEN u.outcome = 'SUCCESS' AND u.bound_to LIKE '$error%'
                     THEN 'a SUCCESS message is written when the call succeeds, with no error to read'
                   WHEN u.bound_to LIKE '$error%' AND u.errors_field IS NULL
                     THEN 'the field returns no type with an errors field'
                   WHEN u.bound_to = '$error'
                     THEN 'the error whole is no value a payload key carries'
                   WHEN u.bound_to LIKE '$error.%'
                     THEN 'neither __typename nor a field every error type of ' || u.errors_field
                          || ' declares with one type'
                   WHEN u.bound_to = '$session' THEN hf.handle_class
                   WHEN u.bound_to LIKE '$session.%'
                     THEN (SELECT MAX(cs.slot_type) FROM handle_member hm
                             JOIN code_read_slot cs
                               ON cs.source_name = hm.source_name AND cs.class_name = hm.class_name
                              AND cs.method_name = hm.method_name AND cs.descriptor = hm.descriptor
                            WHERE hm.graph_name = u.graph_name
                              AND hm.member_name = SUBSTRING(u.bound_to, 10))
                   WHEN rl.through_list = 1 THEN 'through a list, where a key holds one value'
                   WHEN rl.type_name IS NOT NULL THEN rl.type_name
                   WHEN u.is_service = 1 AND u.bound_to = '$result'
                     THEN 'the returned value is no scalar a payload carries'
                   WHEN u.is_service = 1 THEN 'not a member read off the returned value'
                   WHEN u.bound_to = '$result'
                     THEN 'the written table is not one node type keyed by its primary key'
                   WHEN u.write_kind = 'DELETE' THEN 'after a DELETE only the node id is read'
                   ELSE 'not the written row''s node id or one of its columns' END
           || ')'
    FROM outbox_unkeyed u
    LEFT JOIN session_handle_found hf ON hf.graph_name = u.graph_name
    LEFT JOIN result_landed rl
      ON rl.graph_name = u.graph_name AND rl.coordinate = u.coordinate AND rl.ordinal = u.ordinal
     AND rl.key_name = u.key_name
   WHERE (u.bound_to = '$session' AND hf.handle_class IS NOT NULL)
      OR (u.bound_to LIKE '$session.%'
          AND (SELECT COUNT(*) FROM handle_member hm
                WHERE hm.graph_name = u.graph_name AND hm.member_name = SUBSTRING(u.bound_to, 10)) = 1)
      OR u.bound_to = '$result'
      OR (u.bound_to LIKE '$result.%' AND (rl.key_name IS NOT NULL OR u.outcome = 'ERROR'))
      OR u.bound_to = '$error' OR u.bound_to LIKE '$error.%'
),
-- An entry of an @outbox's own mapping whose key it binds twice, or the common payload also names,
-- with where its mapping string sits so the first one as written is found by position, then site.
key_conflict (graph_name, source_name, source_line, source_column, site_source_name, site_line,
              site_column, position, key_name) AS (
  SELECT m.graph_name, m.source_name, m.source_line, m.source_column, s.source_name, s.source_line,
         s.source_column, p.position, p.target_name
    FROM graphitron_outbox_message m
    JOIN graphitron_argmapping_site s
      ON s.graph_name = m.graph_name AND s.directive_name = 'outbox'
     AND s.coordinate = m.coordinate AND s.ordinal = m.ordinal
    JOIN graphitron_ast_argmapping_pair_entry p
      ON p.graph_name = s.graph_name AND p.source_name = s.source_name
     AND p.source_line = s.source_line AND p.source_column = s.source_column AND p.target_kind = 'NAME'
   WHERE EXISTS (SELECT 1 FROM graphitron_ast_argmapping_pair_entry p2
                  WHERE p2.graph_name = p.graph_name AND p2.source_name = p.source_name
                    AND p2.source_line = p.source_line AND p2.source_column = p.source_column
                    AND p2.target_name = p.target_name AND p2.target_kind = p.target_kind AND p2.position <> p.position)
      OR EXISTS (SELECT 1 FROM store_graph_outbox_payload_entry c
                  WHERE c.graph_name = m.graph_name AND c.key_name = p.target_name)
),
-- An entry of the common payload, at one @outbox carrying it, that names a key twice or that the
-- message has no key for; the payload's position is its key within the graph.
payload_invalid (graph_name, source_name, source_line, source_column, position, key_name, bound_to) AS (
  SELECT m.graph_name, m.source_name, m.source_line, m.source_column, c.position, c.key_name, c.bound_to
    FROM graphitron_outbox_message m
    JOIN store_graph_outbox_payload_entry c ON c.graph_name = m.graph_name
   WHERE EXISTS (SELECT 1 FROM store_graph_outbox_payload_entry c2
                  WHERE c2.graph_name = c.graph_name AND c2.key_name = c.key_name
                    AND c2.position <> c.position)
      OR NOT EXISTS (SELECT 1 FROM graphitron_outbox_key k
                      WHERE k.graph_name = m.graph_name AND k.coordinate = m.coordinate
                        AND k.ordinal = m.ordinal AND k.key_name = c.key_name)
)
-- An @outbox that is not on a field writing a table with @mutation, nor on a mutation field a
-- @service answers: no write, no message. A field no occurrence reaches writes nothing either.
SELECT m.graph_name, m.source_name, m.source_line, m.source_column, 'OUTBOX_NOT_A_WRITE' AS code,
       CAST(NULL AS VARCHAR) AS detail, 'outbox' AS stage
  FROM graphitron_outbox_message m
 WHERE NOT EXISTS (SELECT 1 FROM graphitron_occurrence_producer_write w
                    WHERE w.graph_name = m.graph_name AND w.coordinate = m.occurrence_coordinate_ref)
   AND NOT EXISTS (SELECT 1 FROM graphitron_occurrence_producer op
                     JOIN graphitron_field f
                       ON f.graph_name = m.graph_name AND f.coordinate = m.coordinate
                     JOIN graphql_root_operation ro
                       ON ro.graph_name = f.graph_name AND ro.type_name = f.type_name
                      AND ro.operation = 'MUTATION'
                    WHERE op.graph_name = m.graph_name AND op.coordinate = m.occurrence_coordinate_ref
                      AND op.kind = 'SERVICE')
UNION ALL
-- An @outbox in a build that configures no outbox target. The configuration has no SDL position, so
-- the fault is reported at every @outbox it leaves without one.
SELECT m.graph_name, m.source_name, m.source_line, m.source_column, 'OUTBOX_NO_TARGET',
       CAST(NULL AS VARCHAR), 'outbox'
  FROM graphitron_outbox_message m
 WHERE NOT EXISTS (SELECT 1 FROM store_graph_outbox b
                    WHERE b.graph_name = m.graph_name
                      AND (b.table_name IS NOT NULL OR b.function_name IS NOT NULL))
UNION ALL
-- A configured target that does not resolve, reported at every @outbox it serves, the detail naming
-- why: a role graphitron does not fill, a table with no id or with a column no one fills, or a name
-- that matches no one such table or routine. The table is looked up as the target rule looks it up,
-- by name through the graph's own sources, so the reason can name what the rule refused.
SELECT m.graph_name, m.source_name, m.source_line, m.source_column, 'OUTBOX_TARGET_INVALID',
       CASE WHEN b.table_name IS NOT NULL AND b.function_name IS NOT NULL
              THEN 'both <table> and <function> are named, name one'
            WHEN ro.role IS NOT NULL
              THEN CASE ro.kind WHEN 'COLUMN' THEN '<columns>' ELSE '<parameters>' END || ' binds '
                   || ro.role || ', a role graphitron does not fill: a message is its type and its'
                   || ' payload, and a target''s other columns are the database''s'
            WHEN b.table_name IS NULL
              THEN '<function> ' || b.function_name || ' is not one procedure or function of the jOOQ'
                   || ' catalog, no table, whose parameters are the type, text, and the payload, JSON,'
                   || ' JSONB or text'
            -- A table that is not one, or lacks a role's column, is named as such before any finer reason.
            WHEN tn.table_name IS NULL OR NOT EXISTS (SELECT 1 FROM sql_column c
                             WHERE c.source_name = tn.source_name AND c.table_schema = tn.table_schema
                               AND c.table_name = tn.table_name
                               AND c.column_name_upper = UPPER(COALESCE((SELECT x.name FROM store_graph_outbox_role x
                                     WHERE x.graph_name = m.graph_name AND x.kind = 'COLUMN'
                                       AND x.role = 'type'), 'type')))
                 OR NOT EXISTS (SELECT 1 FROM sql_column c
                             WHERE c.source_name = tn.source_name AND c.table_schema = tn.table_schema
                               AND c.table_name = tn.table_name
                               AND c.column_name_upper = UPPER(COALESCE((SELECT x.name FROM store_graph_outbox_role x
                                     WHERE x.graph_name = m.graph_name AND x.kind = 'COLUMN'
                                       AND x.role = 'payload'), 'payload')))
              THEN '<table> ' || b.table_name || ' is not one table of the jOOQ catalog with a type column'
                   || ' and a payload column that is JSON, JSONB or text'
            WHEN NOT EXISTS (
                   SELECT 1 FROM sql_primary_key pk
                    WHERE pk.source_name = tn.source_name AND pk.table_schema = tn.table_schema
                      AND pk.table_name = tn.table_name)
              THEN '<table> ' || b.table_name || ' has no primary key, so a consumer cannot tell a'
                   || ' message delivered twice from two messages'
            -- The first NOT NULL column of the table with no default that is no role's column.
            WHEN (SELECT MIN(c.column_name) FROM sql_column c
                   WHERE c.source_name = tn.source_name AND c.table_schema = tn.table_schema
                     AND c.table_name = tn.table_name AND NOT c.nullable AND NOT c.defaulted
                     AND c.column_name_upper NOT IN (
                           UPPER(COALESCE((SELECT x.name FROM store_graph_outbox_role x
                                            WHERE x.graph_name = m.graph_name AND x.kind = 'COLUMN'
                                              AND x.role = 'type'), 'type')),
                           UPPER(COALESCE((SELECT x.name FROM store_graph_outbox_role x
                                            WHERE x.graph_name = m.graph_name AND x.kind = 'COLUMN'
                                              AND x.role = 'payload'), 'payload')))) IS NOT NULL
              THEN '<table> ' || b.table_name || '''s column ' || (SELECT MIN(c.column_name) FROM sql_column c
                   WHERE c.source_name = tn.source_name AND c.table_schema = tn.table_schema
                     AND c.table_name = tn.table_name AND NOT c.nullable AND NOT c.defaulted
                     AND c.column_name_upper NOT IN (
                           UPPER(COALESCE((SELECT x.name FROM store_graph_outbox_role x
                                            WHERE x.graph_name = m.graph_name AND x.kind = 'COLUMN'
                                              AND x.role = 'type'), 'type')),
                           UPPER(COALESCE((SELECT x.name FROM store_graph_outbox_role x
                                            WHERE x.graph_name = m.graph_name AND x.kind = 'COLUMN'
                                              AND x.role = 'payload'), 'payload'))))
                   || ' is NOT NULL with no default, and graphitron fills only the type and the payload'
            ELSE '<table> ' || b.table_name || ' is not one table of the jOOQ catalog with a type column'
                 || ' and a payload column that is JSON, JSONB or text' END, 'outbox'
  FROM graphitron_outbox_message m
  JOIN store_graph_outbox b ON b.graph_name = m.graph_name
  -- The first role the configuration binds that graphitron does not fill.
  LEFT JOIN (SELECT r.graph_name, MIN(r.kind) AS kind, MIN(r.role) AS role
               FROM store_graph_outbox_role r
              WHERE r.role NOT IN ('type', 'payload')
              GROUP BY r.graph_name) ro ON ro.graph_name = m.graph_name
  -- The one table the name matches, where it matches one.
  LEFT JOIN (SELECT n.graph_name, MIN(st.source_name) AS source_name,
                    MIN(st.table_schema) AS table_schema, MIN(st.table_name) AS table_name
               FROM named n
               JOIN store_graph_source gs ON gs.graph_name = n.graph_name
               JOIN sql_table st
                 ON st.source_name = gs.source_name AND st.table_name_upper = n.name_upper
                AND (n.schema_upper IS NULL OR st.table_schema_upper = n.schema_upper)
              GROUP BY n.graph_name
             HAVING COUNT(*) = 1) tn ON tn.graph_name = m.graph_name
 WHERE (b.table_name IS NOT NULL OR b.function_name IS NOT NULL)
   AND ((b.table_name IS NOT NULL AND b.function_name IS NOT NULL)
        OR ro.role IS NOT NULL
        OR (b.function_name IS NULL
            AND NOT EXISTS (SELECT 1 FROM graphitron_outbox_target t WHERE t.graph_name = m.graph_name))
        OR (b.table_name IS NULL
            AND NOT EXISTS (SELECT 1 FROM graphitron_outbox_routine_target t
                             WHERE t.graph_name = m.graph_name)))
UNION ALL
-- Two @outbox with one event, at each of the two.
SELECT m.graph_name, m.source_name, m.source_line, m.source_column, 'OUTBOX_DUPLICATE_EVENT', m.event, 'outbox'
  FROM graphitron_outbox_message m
 WHERE EXISTS (SELECT 1 FROM graphitron_outbox_message o
                WHERE o.graph_name = m.graph_name AND o.event = m.event
                  AND (o.coordinate <> m.coordinate OR o.ordinal <> m.ordinal))
UNION ALL
-- A key bound twice in one mapping, or also a key of the common payload: one row per @outbox, the
-- first offending key as written; the author fixes it and the next one is reported.
SELECT d.graph_name, d.source_name, d.source_line, d.source_column, 'OUTBOX_KEY_CONFLICT', d.key_name, 'outbox'
  FROM key_conflict d
 WHERE NOT EXISTS (SELECT 1 FROM key_conflict e
                    WHERE e.graph_name = d.graph_name AND e.source_name = d.source_name
                      AND e.source_line = d.source_line AND e.source_column = d.source_column
                      AND (e.position < d.position
                           OR (e.position = d.position
                               AND (e.site_source_name < d.site_source_name
                                    OR (e.site_source_name = d.site_source_name
                                        AND (e.site_line < d.site_line
                                             OR (e.site_line = d.site_line
                                                 AND e.site_column < d.site_column)))))))
UNION ALL
-- The configured common payload's faults, reported at every @outbox: a key it names twice, or an
-- entry no message could carry.
SELECT d.graph_name, d.source_name, d.source_line, d.source_column, 'OUTBOX_PAYLOAD_INVALID',
       d.key_name || ': ' || d.bound_to, 'outbox'
  FROM payload_invalid d
 WHERE NOT EXISTS (SELECT 1 FROM payload_invalid e
                    WHERE e.graph_name = d.graph_name AND e.source_name = d.source_name
                      AND e.source_line = d.source_line AND e.source_column = d.source_column
                      AND e.position < d.position)
UNION ALL
-- Each string one of whose entries a rule above refuses, once per code; the entries are
-- graphitron_outbox_entry_defect_pair_rule's rows, written under it.
SELECT DISTINCT d.graph_name, d.source_name, d.source_line, d.source_column, d.code,
       CAST(NULL AS VARCHAR), 'outbox'
  FROM pair_defect d
