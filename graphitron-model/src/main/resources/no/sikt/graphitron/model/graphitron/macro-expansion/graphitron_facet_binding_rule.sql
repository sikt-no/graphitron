SELECT f.graph_name, f.type_name, f.field_name, f.ordinal,
       fb.name_ref AS column_name,
       f.named_type AS value_type_name,
       CASE WHEN f.is_list THEN NOT f.item_non_null ELSE TRUE END AS value_nullable,
       -- A @reference written beside the facet marker, asked of the marker's siblings for the
       -- reason the exclusions below are.
       EXISTS (SELECT 1
                 FROM graphql_ast_entry mark
                 JOIN graphql_ast_entry de
                   ON de.graph_name = mark.graph_name
                  AND de.source_name = mark.source_name
                  AND de.parent_line = mark.parent_line
                  AND de.parent_column = mark.parent_column
                 JOIN graphql_ast_directive_application_entry d
                   ON d.graph_name = de.graph_name AND d.source_name = de.source_name
                  AND d.source_line = de.source_line AND d.source_column = de.source_column
                WHERE mark.graph_name = fc.graph_name
                  AND mark.source_name = fc.source_name
                  AND mark.source_line = fc.source_line
                  AND mark.source_column = fc.source_column
                  AND d.name = 'reference') AS reference_bound,
       fc.source_name, fc.source_line, fc.source_column
  -- The field's first @asFacet, and the name its first @field binds. AS source_column
  FROM graphql_directive_application fc
  JOIN graphql_field_element fe
    ON fe.graph_name = fc.graph_name AND fe.coordinate = fc.coordinate
  JOIN graphql_field f
    ON f.graph_name = fe.graph_name AND f.type_name = fe.type_name
   AND f.field_name = fe.field_name
  JOIN graphql_type owner
    ON owner.graph_name = f.graph_name AND owner.type_name = f.type_name
   AND owner.kind = 'INPUT_OBJECT'
  JOIN graphql_directive_application fa
    ON fa.graph_name = fc.graph_name AND fa.coordinate = fc.coordinate
   AND fa.directive_name = 'field' AND fa.ordinal = 0
  JOIN graphitron_ast_binding_entry fb
    ON fb.graph_name = fa.graph_name AND fb.source_name = fa.source_name
   AND fb.source_line = fa.source_line AND fb.source_column = fa.source_column
 WHERE fc.directive_name = 'asFacet' AND fc.ordinal = 0
   AND NOT f.non_null
   AND f.named_type <> 'ID'
   AND NOT EXISTS (SELECT 1 FROM graphql_type leaf
                    WHERE leaf.graph_name = f.graph_name AND leaf.type_name = f.named_type
                      AND leaf.kind = 'INPUT_OBJECT')
   -- The two applications that make a facet field something else instead, asked of the
   -- applications rather than of their decodes. What excludes the field is that one of them was
   -- written on it, which is a fact about the document; the decode of what it says is a later
   -- reading, and asking it here would make this view answer differently depending on how far the
   -- pass had got.
   --
   -- Siblings of the @asFacet application itself, not of the coordinate. Where several documents
   -- declare one input field the corpus honours one of them, and the declarations it did not honour
   -- contribute no directives to the merged field; anchoring the search on the position the facet
   -- marker won at is what keeps a losing declaration's @reference from disqualifying a facet the
   -- author never wrote it on.
   --
   -- @reference is not among them: it does not replace the column binding, it says which table the
   -- @field(name:) column is on, so a reference-bound facet is a row here like any other.
   AND NOT EXISTS (SELECT 1
                     FROM graphql_ast_entry mark
                     JOIN graphql_ast_entry de
                       ON de.graph_name = mark.graph_name
                      AND de.source_name = mark.source_name
                      AND de.parent_line = mark.parent_line
                      AND de.parent_column = mark.parent_column
                     JOIN graphql_ast_directive_application_entry d
                       ON d.graph_name = de.graph_name AND d.source_name = de.source_name
                      AND d.source_line = de.source_line AND d.source_column = de.source_column
                    WHERE mark.graph_name = fc.graph_name
                      AND mark.source_name = fc.source_name
                      AND mark.source_line = fc.source_line
                      AND mark.source_column = fc.source_column
                      AND d.name IN ('condition', 'nodeId'))
