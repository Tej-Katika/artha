# -*- coding: utf-8 -*-
"""
FinWise Synthetic Data Generator v2
=====================================
Generates 500 realistic user profiles (50 per archetype x 10 archetypes)
with 12 months of transactions and optionally inserts them into PostgreSQL.

Schema-aligned to:
  users(id, email, full_name, risk_profile, income_bracket, ...)
  bank_accounts(id, user_id, institution_name, account_name, account_type, mask, current_balance, ...)
  transactions(id, user_id, account_id, transaction_type, post_date, description,
               merchant_name, amount, balance, category, category_detail, source)

Usage:
    python generate_finwise_data_v2.py --count 50 --clear --save-map
    python generate_finwise_data_v2.py --count 50 --dry-run
    python generate_finwise_data_v2.py --archetype high_earner --count 10
"""

import argparse
import json
import random
import sys
import uuid
from datetime import date
from decimal import Decimal

# -- DB config -----------------------------------------------------------------

DB_CONFIG = dict(host="localhost", port=5432, dbname="postgres",
                 user="postgres", password="finwise123")

# -- Constants -----------------------------------------------------------------

MONTHS = 12
START  = date(2024, 1, 1)

# -- Data model (plain class, no dataclass) ------------------------------------

class TxRow(object):
    def __init__(self, id, user_id, account_id, transaction_type,
                 post_date, description, merchant_name,
                 amount, balance, category, category_detail, source="SYNTHETIC"):
        self.id               = id
        self.user_id          = user_id
        self.account_id       = account_id
        self.transaction_type = transaction_type
        self.post_date        = post_date
        self.description      = description
        self.merchant_name    = merchant_name
        self.amount           = amount
        self.balance          = balance
        self.category         = category
        self.category_detail  = category_detail
        self.source           = source

# -- Archetype configs (plain dicts) ------------------------------------------

ARCHETYPES = {
    "overspender": {
        "label":             "Lifestyle Overspender",
        "monthly_income":    (7000, 12000),
        "monthly_spend_pct": (0.95, 1.30),
        "categories": [
            ("FOOD_AND_DRINK",   "Restaurants",   0.25),
            ("SHOPPING",         "Clothing",       0.20),
            ("ENTERTAINMENT",    "Events",         0.15),
            ("TRAVEL",           "Hotels",         0.15),
            ("PERSONAL_CARE",    "Salon/Spa",      0.10),
            ("BILLS_UTILITIES",  "Subscriptions",  0.10),
            ("GROCERIES",        "Grocery Store",  0.05),
        ],
        "merchant_pool": ["Sephora","Nordstrom","Uber Eats","Airbnb","StubHub",
                          "DoorDash","Amazon","Cheesecake Factory","Spotify","Netflix"],
    },
    "high_debt": {
        "label":             "High Debt Load",
        "monthly_income":    (4000, 7000),
        "monthly_spend_pct": (0.85, 1.10),
        "categories": [
            ("LOAN_PAYMENT",    "Student Loan",    0.30),
            ("LOAN_PAYMENT",    "Credit Card Min", 0.20),
            ("BILLS_UTILITIES", "Utilities",       0.15),
            ("GROCERIES",       "Grocery Store",   0.15),
            ("TRANSPORTATION",  "Gas",             0.10),
            ("FOOD_AND_DRINK",  "Fast Food",       0.10),
        ],
        "merchant_pool": ["Navient","Discover","Chase","Con Edison","Kroger",
                          "Shell","McDonald's","Dollar Tree","Walgreens","Verizon"],
    },
    "high_earner": {
        "label":             "High Earner / Saver",
        "monthly_income":    (12000, 20000),
        "monthly_spend_pct": (0.55, 0.75),
        "categories": [
            ("INVESTMENTS",     "Brokerage",       0.25),
            ("FOOD_AND_DRINK",  "Fine Dining",     0.15),
            ("TRAVEL",          "Business Travel", 0.15),
            ("SHOPPING",        "Electronics",     0.10),
            ("BILLS_UTILITIES", "Mortgage",        0.20),
            ("PERSONAL_CARE",   "Gym/Wellness",    0.10),
            ("GROCERIES",       "Whole Foods",     0.05),
        ],
        "merchant_pool": ["Fidelity","Vanguard","Amex","OpenTable","Delta Airlines",
                          "Whole Foods","Apple Store","Equinox","Sweetgreen","Tesla"],
    },
    "recent_grad": {
        "label":             "Recent Graduate",
        "monthly_income":    (2800, 5000),
        "monthly_spend_pct": (0.85, 1.05),
        "categories": [
            ("LOAN_PAYMENT",    "Student Loan",    0.25),
            ("HOUSING",         "Rent",            0.30),
            ("FOOD_AND_DRINK",  "Restaurants",     0.15),
            ("TRANSPORTATION",  "Rideshare",       0.10),
            ("ENTERTAINMENT",   "Streaming",       0.05),
            ("GROCERIES",       "Grocery Store",   0.10),
            ("PERSONAL_CARE",   "Pharmacy",        0.05),
        ],
        "merchant_pool": ["Sallie Mae","Zillow Rent","Chipotle","Uber","Lyft",
                          "Spotify","Trader Joe's","CVS","Netflix","Hulu"],
    },
    "freelancer": {
        "label":             "Freelancer / Contractor",
        "monthly_income":    (3000, 9000),
        "monthly_spend_pct": (0.70, 0.95),
        "categories": [
            ("BUSINESS_EXPENSE","Software Tools",  0.20),
            ("FOOD_AND_DRINK",  "Coffee/Coworking",0.15),
            ("TRANSPORTATION",  "Rideshare/Mileage",0.10),
            ("BILLS_UTILITIES", "Internet/Phone",  0.10),
            ("HOUSING",         "Rent",            0.25),
            ("GROCERIES",       "Grocery Store",   0.10),
            ("TAXES",           "Quarterly Tax",   0.10),
        ],
        "merchant_pool": ["Notion","Figma","Adobe","WeWork","Zoom","Stripe",
                          "Starbucks","Lyft","T-Mobile","Trader Joe's"],
    },
    "retired_fixed": {
        "label":             "Retired / Fixed Income",
        "monthly_income":    (2000, 4000),
        "monthly_spend_pct": (0.70, 0.90),
        "categories": [
            ("HEALTHCARE",      "Doctor/Pharmacy", 0.25),
            ("GROCERIES",       "Grocery Store",   0.20),
            ("BILLS_UTILITIES", "Utilities",       0.20),
            ("FOOD_AND_DRINK",  "Casual Dining",   0.10),
            ("ENTERTAINMENT",   "Cable/Streaming", 0.10),
            ("TRANSPORTATION",  "Gas",             0.10),
            ("PERSONAL_CARE",   "Misc",            0.05),
        ],
        "merchant_pool": ["CVS","Walgreens","Kroger","AARP","Xfinity","Denny's",
                          "Shell","AT&T","Medicare Supplement","Social Security"],
    },
    "dual_income": {
        "label":             "Dual Income / Family",
        "monthly_income":    (9000, 16000),
        "monthly_spend_pct": (0.75, 0.95),
        "categories": [
            ("HOUSING",         "Mortgage",        0.28),
            ("GROCERIES",       "Family Groceries",0.15),
            ("CHILDCARE",       "Daycare/School",  0.15),
            ("TRANSPORTATION",  "Car Payment/Gas", 0.12),
            ("FOOD_AND_DRINK",  "Family Dining",   0.10),
            ("BILLS_UTILITIES", "Utilities",       0.10),
            ("ENTERTAINMENT",   "Family Activities",0.10),
        ],
        "merchant_pool": ["Costco","Target","Whole Foods","BrightHorizons","Honda",
                          "Shell","Chuck E Cheese","Disney+","Verizon","Olive Garden"],
    },
    "paycheck_to_paycheck": {
        "label":             "Paycheck to Paycheck",
        "monthly_income":    (2500, 4000),
        "monthly_spend_pct": (0.95, 1.05),
        "categories": [
            ("HOUSING",         "Rent",            0.35),
            ("GROCERIES",       "Grocery Store",   0.20),
            ("BILLS_UTILITIES", "Utilities/Phone", 0.15),
            ("TRANSPORTATION",  "Bus/Gas",         0.10),
            ("FOOD_AND_DRINK",  "Fast Food",       0.10),
            ("PERSONAL_CARE",   "Essentials",      0.10),
        ],
        "merchant_pool": ["Walmart","Aldi","McDonald's","Greyhound","Shell",
                          "T-Mobile","Dollar General","Laundromat","Medicaid","Payday Loan"],
    },
    "small_biz_owner": {
        "label":             "Small Business Owner",
        "monthly_income":    (5000, 14000),
        "monthly_spend_pct": (0.75, 1.05),
        "categories": [
            ("BUSINESS_EXPENSE","Inventory/Supplies",0.30),
            ("BUSINESS_EXPENSE","Payroll",           0.25),
            ("BILLS_UTILITIES", "Business Rent",     0.15),
            ("FOOD_AND_DRINK",  "Client Meals",      0.10),
            ("TRANSPORTATION",  "Business Vehicle",  0.10),
            ("MARKETING",       "Ads/Software",      0.10),
        ],
        "merchant_pool": ["QuickBooks","Square","Shopify","UPS","FedEx",
                          "Staples","Google Ads","Mailchimp","Chase Business","Comcast Biz"],
    },
    "gig_worker": {
        "label":             "Gig Economy Worker",
        "monthly_income":    (2000, 5000),
        "monthly_spend_pct": (0.80, 1.00),
        "categories": [
            ("TRANSPORTATION",  "Gas/Car Maint",   0.30),
            ("HOUSING",         "Rent",            0.30),
            ("FOOD_AND_DRINK",  "Quick Meals",     0.15),
            ("BILLS_UTILITIES", "Phone/Data",      0.10),
            ("GROCERIES",       "Grocery Store",   0.10),
            ("PERSONAL_CARE",   "Misc",            0.05),
        ],
        "merchant_pool": ["Shell","BP","AutoZone","Jiffy Lube","McDonald's",
                          "7-Eleven","T-Mobile","Lyft","Uber","Instacart"],
    },
}

# -- ID helpers ----------------------------------------------------------------

ARCH_PREFIX = {
    "high_earner":          "aa000000",
    "paycheck_to_paycheck": "bb000000",
    "high_debt":            "cc000000",
    "overspender":          "dd000000",
    "freelancer":           "ee000000",
    "retired_fixed":        "ff000000",
    "dual_income":          "11000000",
    "recent_grad":          "22000000",
    "gig_worker":           "33000000",
    "small_biz_owner":      "44000000",
}

def build_user_id(arch_name, idx):
    prefix = ARCH_PREFIX[arch_name]
    suffix = str(idx).zfill(4)
    raw    = (prefix + suffix + "0" * 32)[:32]
    return "%s-%s-%s-%s-%s" % (raw[:8], raw[8:12], raw[12:16], raw[16:20], raw[20:32])

def build_tx_id(user_id, idx):
    return str(uuid.uuid5(uuid.UUID(user_id), "tx-%d" % idx))

def build_account_id(user_id):
    return str(uuid.uuid5(uuid.UUID(user_id), "primary-checking"))

# -- Profile generator ---------------------------------------------------------

def generate_profile(cfg, user_id, seed):
    rng        = random.Random(seed * 999983)
    account_id = build_account_id(user_id)
    rows       = []
    balance    = Decimal(str(round(rng.uniform(500, 3000), 2)))
    tx_idx     = 0

    for month_offset in range(MONTHS):
        mo_month = START.month + month_offset
        mo_year  = START.year + (mo_month - 1) // 12
        mo_month = ((mo_month - 1) % 12) + 1
        mo_start = date(mo_year, mo_month, 1)

        income_base = rng.uniform(cfg["monthly_income"][0], cfg["monthly_income"][1])
        n_income    = 2 if rng.random() < 0.4 else 1
        for k in range(n_income):
            amt      = Decimal(str(round(income_base / n_income + rng.uniform(-50, 50), 2)))
            balance += amt
            day      = min(k * 14 + 1, 28)
            rows.append(TxRow(
                id               = build_tx_id(user_id, tx_idx),
                user_id          = user_id,
                account_id       = account_id,
                transaction_type = "CREDIT",
                post_date        = mo_start.replace(day=day),
                description      = "Direct Deposit - Payroll",
                merchant_name    = "Payroll",
                amount           = amt,
                balance          = balance,
                category         = "INCOME",
                category_detail  = "Payroll",
            ))
            tx_idx += 1

        spend_target = Decimal(str(round(
            income_base * rng.uniform(cfg["monthly_spend_pct"][0], cfg["monthly_spend_pct"][1]), 2
        )))
        spent = Decimal(0)
        cats  = cfg["categories"]

        while spent < spend_target:
            weights  = [c[2] for c in cats]
            # manual weighted choice (compatible with Python < 3.6)
            total = sum(weights)
            r     = rng.uniform(0, total)
            cum   = 0
            chosen = cats[-1]
            for cat, w in zip(cats, weights):
                cum += w
                if r <= cum:
                    chosen = cat
                    break
            cat, detail = chosen[0], chosen[1]
            merchant = rng.choice(cfg["merchant_pool"])
            max_amt  = min(float(spend_target) * 0.15, 800)
            amt      = Decimal(str(round(rng.uniform(5, max_amt), 2)))
            if spent + amt > spend_target * Decimal("1.1"):
                break
            balance -= amt
            spent   += amt
            day      = rng.randint(1, 28)
            rows.append(TxRow(
                id               = build_tx_id(user_id, tx_idx),
                user_id          = user_id,
                account_id       = account_id,
                transaction_type = "DEBIT",
                post_date        = mo_start.replace(day=day),
                description      = merchant,
                merchant_name    = merchant,
                amount           = amt,
                balance          = balance,
                category         = cat,
                category_detail  = detail,
            ))
            tx_idx += 1

    rows.sort(key=lambda r: r.post_date)
    return rows

# -- Generate all users --------------------------------------------------------

FIRST_NAMES = ["Alex","Jordan","Morgan","Casey","Taylor","Riley","Sam","Jamie",
               "Avery","Cameron","Drew","Parker","Quinn","Reese","Blake","Peyton",
               "Logan","Skyler","Hayden","Devon","Finley","Rowan","Emery","Sage",
               "River","Elliot","Kai","Dallas","Phoenix","Remy","Landen","Bellamy"]
LAST_NAMES  = ["Smith","Johnson","Williams","Brown","Jones","Garcia","Miller",
               "Davis","Wilson","Anderson","Thomas","Taylor","Moore","Martin",
               "Jackson","Thompson","White","Harris","Lewis","Robinson","Walker",
               "Hall","Allen","Young","Hernandez","King","Wright","Scott","Green"]

def generate_all_users(archetypes_filter, count):
    rng   = random.Random(42)
    users = []
    for arch_name in archetypes_filter:
        cfg = ARCHETYPES[arch_name]
        for i in range(count):
            uid  = build_user_id(arch_name, i)
            name = "%s %s" % (rng.choice(FIRST_NAMES), rng.choice(LAST_NAMES))
            txs  = generate_profile(cfg, uid, i)
            users.append((uid, name, arch_name, txs))
    return users

# -- Summary -------------------------------------------------------------------

def print_summary(users):
    arch_stats = {}
    total_txs  = 0
    for uid, name, arch_name, txs in users:
        total_txs += len(txs)
        if arch_name not in arch_stats:
            arch_stats[arch_name] = {"users":0,"total_txs":0,"avg_income":[],"avg_spend":[]}
        s = arch_stats[arch_name]
        s["users"]     += 1
        s["total_txs"] += len(txs)
        income = sum(t.amount for t in txs if t.transaction_type == "CREDIT")
        spend  = sum(t.amount for t in txs if t.transaction_type == "DEBIT")
        s["avg_income"].append(float(income) / MONTHS)
        s["avg_spend"].append(float(spend)   / MONTHS)

    print("\n" + "="*72)
    print("  FINWISE SYNTHETIC DATA v2 - SUMMARY")
    print("="*72)
    print("  Total users:        %d" % len(users))
    print("  Total transactions: %d" % total_txs)
    print("  Avg txns per user:  %d" % (total_txs // max(len(users), 1)))
    print()
    print("  %-28s %6s %8s %15s %14s" % ("Archetype","Users","Txns","Avg Income/mo","Avg Spend/mo"))
    print("  " + "-"*28 + " " + "-"*6 + " " + "-"*8 + " " + "-"*15 + " " + "-"*14)
    for arch_name in sorted(arch_stats):
        s       = arch_stats[arch_name]
        avg_inc = sum(s["avg_income"]) / len(s["avg_income"]) if s["avg_income"] else 0
        avg_spd = sum(s["avg_spend"])  / len(s["avg_spend"])  if s["avg_spend"]  else 0
        label   = ARCHETYPES[arch_name]["label"]
        print("  %-28s %6d %8d    $%10.0f   $%10.0f" % (
            label, s["users"], s["total_txs"], avg_inc, avg_spd))
    print("="*72 + "\n")

# -- Save user map -------------------------------------------------------------

def save_user_map(users, path="finwise_users_v2.json"):
    data = {
        "generated_at": date.today().isoformat(),
        "total_users":  len(users),
        "archetypes":   {}
    }
    for uid, name, arch_name, txs in users:
        income = sum(t.amount for t in txs if t.transaction_type == "CREDIT")
        entry  = {
            "user_id":            uid,
            "name":               name,
            "tx_count":           len(txs),
            "avg_monthly_income": round(float(income) / MONTHS, 2),
            "transactions": [
                {
                    "transaction_id":   t.id,
                    "date":             t.post_date.isoformat(),
                    "description":      t.description,
                    "merchant_name":    t.merchant_name,
                    "amount":           float(t.amount),
                    "balance":          float(t.balance),
                    "transaction_type": t.transaction_type,
                    "category":         t.category,
                    "subcategory":      t.category_detail,
                }
                for t in txs
            ]
        }
        if arch_name not in data["archetypes"]:
            data["archetypes"][arch_name] = []
        data["archetypes"][arch_name].append(entry)

    with open(path, "w") as f:
        json.dump(data, f, indent=2)
    print("User map saved to %s" % path)

# -- DB helpers ----------------------------------------------------------------

def income_bracket(avg_monthly):
    annual = avg_monthly * 12
    if annual < 50000:  return "UNDER_50K"
    if annual < 100000: return "50K_100K"
    if annual < 200000: return "100K_200K"
    return "OVER_200K"

def risk_profile(arch_name):
    mapping = {
        "high_earner":    "AGGRESSIVE",
        "small_biz_owner":"AGGRESSIVE",
        "dual_income":    "MODERATE",
        "freelancer":     "MODERATE",
        "recent_grad":    "MODERATE",
    }
    return mapping.get(arch_name, "CONSERVATIVE")

# -- DB insert -----------------------------------------------------------------

def insert_all(users, clear):
    try:
        import psycopg2
        from psycopg2.extras import execute_batch
    except ImportError:
        print("ERROR: pip install psycopg2-binary")
        sys.exit(1)

    conn = psycopg2.connect(**DB_CONFIG)
    cur  = conn.cursor()

    if clear:
        print("[DB] Clearing v2 data...")
        for arch_name in ARCHETYPES:
            p = build_user_id(arch_name, 0)[:8] + "%"
            cur.execute("""
                DELETE FROM transaction_enrichments
                WHERE transaction_id IN (
                    SELECT id FROM transactions
                    WHERE user_id IN (SELECT id FROM users WHERE id::text LIKE %s)
                )
            """, (p,))
            cur.execute("""
                DELETE FROM transactions
                WHERE user_id IN (SELECT id FROM users WHERE id::text LIKE %s)
            """, (p,))
            cur.execute("""
                DELETE FROM bank_accounts
                WHERE user_id IN (SELECT id FROM users WHERE id::text LIKE %s)
            """, (p,))
            cur.execute("DELETE FROM users WHERE id::text LIKE %s", (p,))
        conn.commit()
        print("[DB] Cleared.")

    total_inserted = 0

    for uid, name, arch_name, txs in users:
        avg_mo = float(
            sum(t.amount for t in txs if t.transaction_type == "CREDIT")
        ) / MONTHS
        email = "%s@finwise-test.com" % uid

        # 1. Insert user
        cur.execute("""
            INSERT INTO users (id, email, full_name)
            VALUES (%s::uuid, %s, %s)
            ON CONFLICT (id) DO NOTHING
        """, (uid, email, name))

        # 2. Insert bank account
        account_id = build_account_id(uid)
        bal = float(sum(
            t.amount * (1 if t.transaction_type == "CREDIT" else -1) for t in txs
        ))
        cur.execute("""
            INSERT INTO bank_accounts
                (id, user_id, institution_name, account_name,
                 account_type, mask, current_balance, available_balance, currency_code)
            VALUES (%s::uuid, %s::uuid, %s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (id) DO NOTHING
        """, (account_id, uid,
              "FinWise Test Bank",
              name.split()[0] + "'s Checking",
              "CHECKING",
              uid[-4:],
              round(max(bal, 0), 2),
              round(max(bal, 0), 2),
              "USD"))

        # 3. Insert transactions
        import json as _json
        batch = [(
            tx.id,
            uid,
            account_id,
            tx.transaction_type,
            tx.post_date.isoformat(),
            tx.description,
            tx.merchant_name,
            float(tx.amount),
            float(tx.balance),
            "ACH",
            tx.id,
            _json.dumps({"category": tx.category, "category_detail": tx.category_detail, "source": "SYNTHETIC"}),
        ) for tx in txs]

        execute_batch(cur, """
            INSERT INTO transactions
                (id, user_id, bank_account_id, transaction_type, post_date,
                 description, merchant_name, amount, balance,
                 payment_method, reference_id, metadata)
            VALUES (%s, %s::uuid, %s::uuid, %s, %s, %s, %s, %s, %s, %s, %s, %s::jsonb)
            ON CONFLICT (id) DO NOTHING
        """, batch, page_size=500)

        total_inserted += len(batch)

    conn.commit()
    cur.close()
    conn.close()
    print("[DB] Inserted %d transactions for %d users" % (total_inserted, len(users)))

# -- Main ----------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="FinWise Synthetic Data Generator v2")
    parser.add_argument("--count",     type=int, default=50)
    parser.add_argument("--archetype", type=str, default="all")
    parser.add_argument("--dry-run",   action="store_true")
    parser.add_argument("--clear",     action="store_true")
    parser.add_argument("--save-map",  action="store_true")
    args = parser.parse_args()

    if args.archetype == "all":
        arch_list = list(ARCHETYPES.keys())
    elif args.archetype in ARCHETYPES:
        arch_list = [args.archetype]
    else:
        print("Unknown archetype '%s'. Choices: %s" % (args.archetype, list(ARCHETYPES.keys())))
        sys.exit(1)

    print("FinWise Synthetic Data Generator v2")
    print("Archetypes    : %s" % arch_list)
    print("Per archetype : %d  Total users: %d" % (args.count, len(arch_list) * args.count))
    print("Generating...")

    users = generate_all_users(arch_list, args.count)
    print_summary(users)

    if args.save_map:
        save_user_map(users)

    if args.dry_run:
        print("[DRY RUN] No data inserted into DB.")
    else:
        insert_all(users, clear=args.clear)
        print("\nDone! Sample enrichment endpoints:")
        for uid, name, arch, _ in users[:3]:
            print("  POST http://localhost:8080/api/enrichment/user/%s/all" % uid)
        if len(users) > 3:
            print("  ... and %d more" % (len(users) - 3))

if __name__ == "__main__":
    main()