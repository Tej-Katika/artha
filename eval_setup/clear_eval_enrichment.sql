-- Clear enrichment data for the 10 eval users.
-- Simulates "Enrichment OFF" for ablation Conditions C and D.
-- Preserves enrichment rows (so tools' JOINs don't fail) but nulls all
-- ontology-derived fields and deletes recurring_bills.
BEGIN;

UPDATE transaction_enrichments e
SET    spending_category_id    = NULL,
       merchant_profile_id     = NULL,
       is_anomaly              = FALSE,
       anomaly_reason          = NULL,
       canonical_merchant_name = NULL,
       budget_id               = NULL,
       budget_utilization_pct  = NULL,
       enrichment_source       = 'CLEARED',
       updated_at              = now()
FROM   transactions t
WHERE  e.transaction_id = t.id
  AND  t.user_id IN (
      'aa000000-0000-0000-0000-000000000000',
      'bb000000-0000-0000-0000-000000000000',
      'cc000000-0000-0000-0000-000000000000',
      'dd000000-0000-0000-0000-000000000000',
      'ee000000-0000-0000-0000-000000000000',
      'ff000000-0000-0000-0000-000000000000',
      '11000000-0000-0000-0000-000000000000',
      '22000000-0000-0000-0000-000000000000',
      '33000000-0000-0000-0000-000000000000',
      '44000000-0000-0000-0000-000000000000'
  );

DELETE FROM recurring_bills
WHERE user_id IN (
    'aa000000-0000-0000-0000-000000000000',
    'bb000000-0000-0000-0000-000000000000',
    'cc000000-0000-0000-0000-000000000000',
    'dd000000-0000-0000-0000-000000000000',
    'ee000000-0000-0000-0000-000000000000',
    'ff000000-0000-0000-0000-000000000000',
    '11000000-0000-0000-0000-000000000000',
    '22000000-0000-0000-0000-000000000000',
    '33000000-0000-0000-0000-000000000000',
    '44000000-0000-0000-0000-000000000000'
);

COMMIT;
