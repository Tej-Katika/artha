-- =====================================================================
-- Artha Eval — Data Enrichment Setup (idempotent)
-- =====================================================================
-- Run once to prepare the DB for the full benchmark run.
-- Safe to re-run: all INSERTs use ON CONFLICT DO NOTHING, all UPDATEs
-- are scoped to NULL fields or use ON CONFLICT guards.
--
-- Phases:
--   1. Add 15 new merchant_profiles + classification_rules (hybrid rules + metadata).
--   2. Ensure every user has a spending_category for every merchant_type
--      used by rules, plus one per metadata category name (for backfill).
--   3. Apply the new rules retroactively via SQL UPDATE (rule-based enrichment).
--   4. Backfill remaining NULL spending_category_id from transactions.metadata.
--   5. Seed financial_goals and budgets for every user.
--   6. Detect and insert recurring_bills via SQL (replicates SubscriptionDetector).
-- =====================================================================

BEGIN;

-- ─── Phase 1 — new merchant_profiles + classification_rules ──────────
-- Patterns are UPPERCASE (matched against UPPER(merchant_name)); pattern_type = CONTAINS.
-- Mapping: each (canonical_name, merchant_type_name) pair below becomes a profile + rule.
WITH mt AS (
    SELECT id, name FROM merchant_types
), seeds(pattern, canonical, mt_name) AS (
    VALUES
      ('MCDONALD',           'McDonald''s',        'Restaurant'),
      ('T-MOBILE',           'T-Mobile',           'Utilities'),
      ('VERIZON',            'Verizon',            'Utilities'),
      ('LYFT',               'Lyft',               'Rideshare'),
      ('DOORDASH',           'DoorDash',           'Restaurant'),
      ('KROGER',             'Kroger',             'Grocery'),
      ('TARGET',             'Target',             'Shopping'),
      ('WALGREENS',          'Walgreens',          'Pharmacy'),
      ('CVS',                'CVS',                'Pharmacy'),
      ('COSTCO',             'Costco',             'Grocery'),
      ('TRADER JOE',         'Trader Joe''s',      'Grocery'),
      ('OLIVE GARDEN',       'Olive Garden',       'Restaurant'),
      ('CHEESECAKE FACTORY', 'Cheesecake Factory', 'Restaurant'),
      ('APPLE',              'Apple',              'Shopping'),
      ('STUBHUB',            'StubHub',            'Entertainment')
)
INSERT INTO merchant_profiles (canonical_name, merchant_type_id)
SELECT s.canonical, mt.id
FROM seeds s JOIN mt ON mt.name = s.mt_name
ON CONFLICT (canonical_name) DO NOTHING;

INSERT INTO classification_rules (pattern, pattern_type, merchant_profile_id, priority, is_active)
SELECT v.pattern, 'CONTAINS', mp.id, 10, TRUE
FROM (VALUES
    ('MCDONALD',           'McDonald''s'),
    ('T-MOBILE',           'T-Mobile'),
    ('VERIZON',            'Verizon'),
    ('LYFT',               'Lyft'),
    ('DOORDASH',           'DoorDash'),
    ('KROGER',             'Kroger'),
    ('TARGET',             'Target'),
    ('WALGREENS',          'Walgreens'),
    ('CVS',                'CVS'),
    ('COSTCO',             'Costco'),
    ('TRADER JOE',         'Trader Joe''s'),
    ('OLIVE GARDEN',       'Olive Garden'),
    ('CHEESECAKE FACTORY', 'Cheesecake Factory'),
    ('APPLE',              'Apple'),
    ('STUBHUB',            'StubHub')
) AS v(pattern, canonical)
JOIN merchant_profiles mp ON mp.canonical_name = v.canonical
WHERE NOT EXISTS (SELECT 1 FROM classification_rules cr WHERE cr.pattern = v.pattern);

-- ─── Phase 2a — ensure users have spending_categories for rule merchant_types ───
-- Rule-backed merchant_types (after phase 1): Restaurant, Utilities, Rideshare,
-- Grocery, Shopping, Pharmacy, Entertainment, Streaming, Coffee Shop, Gas Station.
INSERT INTO spending_categories (user_id, name, merchant_type_id, is_system)
SELECT u.id, mt.name, mt.id, TRUE
FROM users u
CROSS JOIN merchant_types mt
WHERE mt.name IN ('Restaurant','Utilities','Rideshare','Grocery','Shopping',
                  'Pharmacy','Entertainment','Streaming','Coffee Shop','Gas Station',
                  'Other','Income')
ON CONFLICT (user_id, name) DO NOTHING;

-- ─── Phase 2b — ensure users have a spending_category for each metadata category ───
-- Category names match the generator's metadata values so the budget tool
-- (which reads transactions.metadata['category']) and the ontology tools agree.
INSERT INTO spending_categories (user_id, name, merchant_type_id, is_system)
SELECT u.id, v.meta_cat, v.mt_id, TRUE
FROM users u
CROSS JOIN (
    SELECT 'FOOD_AND_DRINK'   AS meta_cat, (SELECT id FROM merchant_types WHERE name='Restaurant') AS mt_id UNION ALL
    SELECT 'HOUSING',                      (SELECT id FROM merchant_types WHERE name='Housing')            UNION ALL
    SELECT 'BILLS_UTILITIES',              (SELECT id FROM merchant_types WHERE name='Utilities')          UNION ALL
    SELECT 'GROCERIES',                    (SELECT id FROM merchant_types WHERE name='Grocery')            UNION ALL
    SELECT 'TRANSPORTATION',               (SELECT id FROM merchant_types WHERE name='Transportation')     UNION ALL
    SELECT 'BUSINESS_EXPENSE',             (SELECT id FROM merchant_types WHERE name='Other')              UNION ALL
    SELECT 'INCOME',                       (SELECT id FROM merchant_types WHERE name='Income')             UNION ALL
    SELECT 'LOAN_PAYMENT',                 (SELECT id FROM merchant_types WHERE name='Other')              UNION ALL
    SELECT 'ENTERTAINMENT',                (SELECT id FROM merchant_types WHERE name='Entertainment')      UNION ALL
    SELECT 'PERSONAL_CARE',                (SELECT id FROM merchant_types WHERE name='Other')              UNION ALL
    SELECT 'SHOPPING',                     (SELECT id FROM merchant_types WHERE name='Shopping')           UNION ALL
    SELECT 'TRAVEL',                       (SELECT id FROM merchant_types WHERE name='Transportation')     UNION ALL
    SELECT 'INVESTMENTS',                  (SELECT id FROM merchant_types WHERE name='Other')              UNION ALL
    SELECT 'CHILDCARE',                    (SELECT id FROM merchant_types WHERE name='Other')              UNION ALL
    SELECT 'HEALTHCARE',                   (SELECT id FROM merchant_types WHERE name='Healthcare')         UNION ALL
    SELECT 'MARKETING',                    (SELECT id FROM merchant_types WHERE name='Other')              UNION ALL
    SELECT 'TAXES',                        (SELECT id FROM merchant_types WHERE name='Other')
) v
ON CONFLICT (user_id, name) DO NOTHING;

-- ─── Phase 3 — apply new rules retroactively ────────────────────────
-- Only touch enrichments where merchant_profile_id IS NULL (preserve
-- any prior rule-based matches).
-- CTE computes the match; DISTINCT ON (e.id) avoids multi-match
-- duplicates (picks the highest-priority rule per enrichment).
WITH matches AS (
    SELECT DISTINCT ON (e.id)
           e.id                        AS enrichment_id,
           mp.id                       AS profile_id,
           sc.id                       AS category_id,
           mp.canonical_name           AS canonical_name
    FROM   transaction_enrichments e
    JOIN   transactions t             ON t.id = e.transaction_id
    JOIN   classification_rules cr
           ON cr.is_active = TRUE
          AND cr.pattern_type = 'CONTAINS'
          AND UPPER(COALESCE(t.merchant_name, t.description, '')) LIKE '%' || cr.pattern || '%'
    JOIN   merchant_profiles mp       ON mp.id = cr.merchant_profile_id
    JOIN   spending_categories sc
           ON sc.user_id = t.user_id AND sc.merchant_type_id = mp.merchant_type_id
    WHERE  e.merchant_profile_id IS NULL
    ORDER BY e.id, cr.priority ASC
)
UPDATE transaction_enrichments e
SET    merchant_profile_id     = m.profile_id,
       spending_category_id    = m.category_id,
       canonical_merchant_name = m.canonical_name,
       enrichment_confidence   = 0.85,
       enrichment_source       = 'RULES',
       updated_at              = now()
FROM   matches m
WHERE  e.id = m.enrichment_id;

-- ─── Phase 4 — metadata backfill for remaining NULLs ────────────────
-- Only fills rows that rules didn't match. Uses the spending_category
-- that matches the metadata's canonical category name.
UPDATE transaction_enrichments e
SET spending_category_id    = sc.id,
    enrichment_source       = 'METADATA',
    enrichment_confidence   = COALESCE(e.enrichment_confidence, 0.60),
    canonical_merchant_name = COALESCE(e.canonical_merchant_name, t.merchant_name),
    updated_at              = now()
FROM transactions t
JOIN spending_categories sc
    ON sc.user_id = t.user_id AND sc.name = t.metadata->>'category'
WHERE e.transaction_id = t.id
  AND e.spending_category_id IS NULL
  AND t.metadata IS NOT NULL
  AND t.metadata->>'category' IS NOT NULL;

-- ─── Phase 5a — seed financial_goals per user ───────────────────────
-- Two goals per user: Emergency Fund + Retirement.
INSERT INTO financial_goals
    (user_id, name, goal_type, target_amount, current_amount, monthly_contribution, target_date, status, priority)
SELECT u.id, 'Emergency Fund', 'EMERGENCY_FUND',
       10000.00, 2500.00, 500.00,
       DATE '2025-12-31', 'ACTIVE', 1
FROM users u
WHERE NOT EXISTS (
    SELECT 1 FROM financial_goals g
    WHERE g.user_id = u.id AND g.goal_type = 'EMERGENCY_FUND'
);

INSERT INTO financial_goals
    (user_id, name, goal_type, target_amount, current_amount, monthly_contribution, target_date, status, priority)
SELECT u.id, 'Retirement', 'RETIREMENT',
       100000.00, 15000.00, 800.00,
       DATE '2030-12-31', 'ACTIVE', 2
FROM users u
WHERE NOT EXISTS (
    SELECT 1 FROM financial_goals g
    WHERE g.user_id = u.id AND g.goal_type = 'RETIREMENT'
);

-- ─── Phase 5b — seed budgets per user ──────────────────────────────
-- Three budgets per user on common discretionary categories.
-- effective_from = 2024-01-01 so the 2024 transactions fall within the window.
INSERT INTO budgets (user_id, spending_category_id, monthly_limit, effective_from)
SELECT u.id, sc.id, limits.limit_amt, DATE '2024-01-01'
FROM users u
CROSS JOIN (VALUES
    ('FOOD_AND_DRINK', 500.00),
    ('SHOPPING',       400.00),
    ('ENTERTAINMENT',  200.00)
) AS limits(cat_name, limit_amt)
JOIN spending_categories sc
    ON sc.user_id = u.id AND sc.name = limits.cat_name
ON CONFLICT (user_id, spending_category_id, effective_from) DO NOTHING;

-- ─── Phase 6 — detect recurring bills (SQL-based subscription detection) ───
-- Criteria mirror SubscriptionDetector:
--   - 3+ DEBIT occurrences for same merchant in last 6 months (relative to ref date)
--   - Amount variance < 10% of mean
--   - Avg interval 25-35 days (MONTHLY) or 360-370 (ANNUAL)
-- Uses 2024-12-31 as the reference "now" to match ARTHA_EVAL_REFERENCE_DATE.
WITH ref AS (SELECT TIMESTAMP '2024-12-31 00:00:00+00' AS now_ts),
recent AS (
    SELECT t.user_id,
           UPPER(TRIM(t.merchant_name)) AS m_key,
           t.merchant_name,
           t.amount,
           t.post_date
    FROM transactions t, ref
    WHERE t.transaction_type = 'DEBIT'
      AND t.merchant_name IS NOT NULL
      AND t.post_date >= ref.now_ts - INTERVAL '180 days'
      AND t.post_date <= ref.now_ts
),
agg AS (
    SELECT user_id, m_key,
           MIN(merchant_name)             AS merchant_name,
           COUNT(*)                        AS n_occurrences,
           AVG(amount)                     AS mean_amt,
           STDDEV_SAMP(amount)             AS sd_amt,
           MAX(post_date)                  AS last_seen,
           (MAX(post_date) - MIN(post_date)) / NULLIF(COUNT(*) - 1, 0) AS avg_interval
    FROM recent
    GROUP BY user_id, m_key
    HAVING COUNT(*) >= 3
),
scored AS (
    SELECT *,
           CASE
               WHEN EXTRACT(EPOCH FROM avg_interval) / 86400 BETWEEN 25 AND 35 THEN 'MONTHLY'
               WHEN EXTRACT(EPOCH FROM avg_interval) / 86400 BETWEEN 360 AND 370 THEN 'ANNUAL'
               ELSE NULL
           END AS billing_cycle
    FROM agg
    WHERE sd_amt IS NULL OR sd_amt <= mean_amt * 0.10  -- amount consistency
)
INSERT INTO recurring_bills
    (user_id, merchant_profile_id, name, expected_amount, billing_cycle,
     next_expected_date, last_seen_date, detection_source, confidence_score, is_active)
SELECT s.user_id,
       mp.id,
       s.merchant_name,
       ROUND(s.mean_amt::numeric, 2),
       s.billing_cycle,
       (s.last_seen + CASE WHEN s.billing_cycle='MONTHLY' THEN INTERVAL '30 days'
                           ELSE INTERVAL '365 days' END)::date,
       s.last_seen::date,
       'AUTO',
       0.80,
       TRUE
FROM scored s
LEFT JOIN merchant_profiles mp ON UPPER(mp.canonical_name) = s.m_key
WHERE s.billing_cycle IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM recurring_bills rb
      WHERE rb.user_id = s.user_id
        AND UPPER(rb.name) = s.m_key
  );

COMMIT;

-- ─── Verification queries (run separately) ──────────────────────────
-- SELECT COUNT(*) AS rules_now FROM classification_rules;
-- SELECT COUNT(*) AS profiles_now FROM merchant_profiles;
-- SELECT COUNT(*) AS with_cat, COUNT(*) FILTER (WHERE spending_category_id IS NULL) AS null_cat
--   FROM transaction_enrichments;
-- SELECT COUNT(DISTINCT user_id) AS users_with_goals FROM financial_goals;
-- SELECT COUNT(DISTINCT user_id) AS users_with_budgets FROM budgets;
-- SELECT COUNT(DISTINCT user_id) AS users_with_subs, COUNT(*) AS total_subs FROM recurring_bills;
