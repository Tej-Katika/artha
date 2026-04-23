-- =====================================================================
-- Phase 6 (rewritten) — subscription detection via is_recurring flag
-- =====================================================================
-- The generator produces amounts with very high variance for "subscription"
-- merchants (e.g., Netflix amounts range $5-$800), so SubscriptionDetector's
-- amount-consistency heuristic cannot find them. Instead, rely on the
-- ontology's merchant_profiles.is_recurring annotation (pre-curated list
-- of merchants that are recurring-by-nature: telecoms, streaming, etc.).
-- =====================================================================

BEGIN;

-- 1. Mark obvious recurring merchants in our 15-profile set.
UPDATE merchant_profiles
SET    is_recurring = TRUE
WHERE  canonical_name IN ('T-Mobile', 'Verizon')
  AND  is_recurring = FALSE;

-- 2. Seed recurring_bills for users who have ≥3 debits at any is_recurring
--    merchant in the last 6 months (relative to eval reference date 2024-12-31).
--    Uses per-user mean amount as the expected_amount (robust to the
--    generator's amount variance).
WITH ref AS (SELECT TIMESTAMP '2024-12-31 00:00:00+00' AS now_ts),
candidates AS (
    SELECT t.user_id,
           mp.id                       AS profile_id,
           mp.canonical_name           AS merchant_name,
           mp.merchant_type_id,
           COUNT(*)                    AS n,
           ROUND(AVG(t.amount)::numeric, 2) AS mean_amt,
           MAX(t.post_date)::date      AS last_seen_date
    FROM   transactions t
    CROSS JOIN ref
    JOIN   merchant_profiles mp
           ON UPPER(COALESCE(t.merchant_name, '')) LIKE '%' || UPPER(mp.canonical_name) || '%'
    WHERE  t.transaction_type = 'DEBIT'
      AND  mp.is_recurring = TRUE
      AND  t.post_date >= ref.now_ts - INTERVAL '180 days'
      AND  t.post_date <= ref.now_ts
    GROUP BY t.user_id, mp.id, mp.canonical_name, mp.merchant_type_id
    HAVING COUNT(*) >= 3
)
INSERT INTO recurring_bills
    (user_id, merchant_profile_id, name, expected_amount, billing_cycle,
     next_expected_date, last_seen_date, detection_source, confidence_score, is_active,
     spending_category_id)
SELECT c.user_id,
       c.profile_id,
       c.merchant_name,
       c.mean_amt,
       'MONTHLY',
       (c.last_seen_date + INTERVAL '30 days')::date,
       c.last_seen_date,
       'AUTO',
       0.85,
       TRUE,
       sc.id
FROM candidates c
LEFT JOIN spending_categories sc
       ON sc.user_id = c.user_id AND sc.merchant_type_id = c.merchant_type_id
WHERE NOT EXISTS (
    SELECT 1 FROM recurring_bills rb
    WHERE rb.user_id = c.user_id
      AND rb.merchant_profile_id = c.profile_id
);

COMMIT;
