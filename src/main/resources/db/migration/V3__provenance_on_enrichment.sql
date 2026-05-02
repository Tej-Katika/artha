-- Artha v2 — Provenance columns on transaction_enrichments.
--
-- Run before activating the Provenance axis (Week 5+). The
-- existing columns enrichment_source and enrichment_confidence
-- already cover SourceType + confidence; this migration adds the
-- remaining three fields from the Provenance tuple defined in
-- research/ONTOLOGY_V2_SPEC.md §5.2.
--
-- All columns nullable: legacy rows (pre-Week-5) leave them empty
-- and ProvenanceService.why() falls back to updated_at for asof.

ALTER TABLE transaction_enrichments
    ADD COLUMN IF NOT EXISTS provenance_rule_id   VARCHAR(80),
    ADD COLUMN IF NOT EXISTS provenance_deps_json TEXT,
    ADD COLUMN IF NOT EXISTS provenance_asof      TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_enrichment_prov_rule
    ON transaction_enrichments (provenance_rule_id)
    WHERE provenance_rule_id IS NOT NULL;
