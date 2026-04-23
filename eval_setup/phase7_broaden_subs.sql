-- =====================================================================
-- Phase 7 — broader subscription seeding
-- =====================================================================
-- Generator data is archetype-specific: each user has different
-- recurring merchants (Equinox, Comcast Biz, AARP, Xfinity, etc.) that
-- my is_recurring allowlist didn't cover. Instead of adding every
-- possible merchant, seed recurring_bills for any merchant the user
-- charged ≥4 times in the 180-day eval window.
--
-- merchant_profile_id is left NULL when no matching profile exists —
-- the schema allows it and GetSubscriptionsTool doesn't require it.
-- =====================================================================

BEGIN;

WITH ref AS (SELECT TIMESTAMP '2024-12-31 00:00:00+00' AS now_ts),
candidates AS (
    SELECT t.user_id,
           t.merchant_name,
           COUNT(*)                        AS n,
           ROUND(AVG(t.amount)::numeric, 2) AS mean_amt,
           MAX(t.post_date)::date          AS last_seen_date
    FROM   transactions t
    CROSS JOIN ref
    WHERE  t.transaction_type = 'DEBIT'
      AND  t.merchant_name IS NOT NULL
      AND  t.post_date >= ref.now_ts - INTERVAL '180 days'
      AND  t.post_date <= ref.now_ts
    GROUP BY t.user_id, t.merchant_name
    HAVING COUNT(*) >= 4
)
INSERT INTO recurring_bills
    (user_id, merchant_profile_id, name, expected_amount, billing_cycle,
     next_expected_date, last_seen_date, detection_source, confidence_score, is_active,
     spending_category_id)
SELECT c.user_id,
       mp.id,                                                   -- nullable
       c.merchant_name,
       c.mean_amt,
       'MONTHLY',
       (c.last_seen_date + INTERVAL '30 days')::date,
       c.last_seen_date,
       'AUTO',
       0.75,
       TRUE,
       NULL                                                     -- spending_category_id optional
FROM candidates c
LEFT JOIN merchant_profiles mp
       ON UPPER(mp.canonical_name) = UPPER(c.merchant_name)
WHERE NOT EXISTS (
    SELECT 1 FROM recurring_bills rb
    WHERE rb.user_id = c.user_id
      AND UPPER(rb.name) = UPPER(c.merchant_name)
);

COMMIT;
