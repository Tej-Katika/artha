# -*- coding: utf-8 -*-
"""
FinWise AI - Multi-User Synthetic Data Generator
=================================================
Generates 12 months of realistic bank transaction data for 8 distinct
financial profiles directly into PostgreSQL (~1,000 txns per user).

Profiles:
  1. Priya Sharma         - High Earner / Disciplined Saver ($12k/mo)
  2. Marcus Johnson       - Paycheck to Paycheck ($3.4k/mo)
  3. Alex Chen            - Young Professional / High Debt ($5.5k/mo)
  4. Brandon Willis       - Overspender with Anomalies ($7k/mo)
  5. Sofia Reyes          - Freelancer / Irregular Income ($0-9k/mo)
  6. Robert & Helen Davis - Retired / Fixed Income ($4.2k/mo)
  7. James & Sarah Mitchell - Dual Income Household ($15k/mo)
  8. Taylor Kim           - Recent Grad / Rebuilding ($2.8k/mo)

Usage:
  pip install psycopg2-binary
  python generate_finwise_data.py

  # Dry run - prints summary without inserting:
  python generate_finwise_data.py --dry-run

  # Single user only:
  python generate_finwise_data.py --user 4

  # Clear existing data first:
  python generate_finwise_data.py --clear

  # Custom month range:
  python generate_finwise_data.py --months 6
"""

import argparse
import random
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta, date
from decimal import Decimal
from typing import Optional
import sys

# -- DB Config ------------------------------------------------------------------

DB_CONFIG = {
    "host":     "localhost",
    "port":     5432,
    "dbname":   "postgres",
    "user":     "postgres",
    "password": "finwise123",
}

MONTHS = 12


# -- Data Classes ---------------------------------------------------------------

@dataclass
class TxTemplate:
    description:     str
    merchant_name:   str
    tx_type:         str
    amount_min:      float
    amount_max:      float
    day_of_month:    Optional[int] = None
    day_of_week:     Optional[int] = None   # 0=Mon 6=Sun
    probability:     float = 1.0
    seasonal_mult:   dict  = field(default_factory=dict)
    category:        str   = "misc"
    is_anomaly_seed: bool  = False

    def amount(self, month: int, mult: float = 1.0) -> Decimal:
        seasonal = self.seasonal_mult.get(month, 1.0)
        raw = random.uniform(self.amount_min, self.amount_max)
        return Decimal(str(round(raw * seasonal * mult, 2)))


@dataclass
class UserProfile:
    user_id:           str
    name:              str
    description:       str
    starting_balance:  Decimal
    anomaly_count:     int
    anomaly_mult:      float
    seed:              int
    grocery_mult:      float = 1.0
    restaurant_mult:   float = 1.0
    subscription_mult: float = 1.0
    utility_mult:      float = 1.0
    gas_mult:          float = 1.0
    shopping_mult:     float = 1.0
    travel_mult:       float = 1.0
    # (description, merchant, min, max, day_of_month_or_None, daily_prob)
    extra_debits:      list  = field(default_factory=list)
    # (description, merchant, min, max, day_of_month)
    extra_credits:     list  = field(default_factory=list)


@dataclass
class Transaction:
    id:               str
    user_id:          str
    transaction_type: str
    post_date:        datetime
    description:      str
    merchant_name:    str
    amount:           Decimal
    balance:          Decimal
    is_anomaly:       bool = False


# -- Baseline Templates ---------------------------------------------------------

BASELINE = [
    # Groceries
    TxTemplate("WHOLE FOODS MARKET", "Whole Foods Market", "DEBIT",
               55, 130, day_of_week=6, probability=0.85,
               seasonal_mult={11: 1.4, 12: 1.6},
               category="grocery", is_anomaly_seed=True),
    TxTemplate("HEB GROCERY", "HEB", "DEBIT",
               30, 90, day_of_week=2, probability=0.80,
               seasonal_mult={12: 1.3}, category="grocery"),
    TxTemplate("WALMART SUPERCENTER", "Walmart", "DEBIT",
               25, 110, day_of_week=5, probability=0.55, category="grocery"),
    TxTemplate("ALDI", "Aldi", "DEBIT",
               20, 70, day_of_week=3, probability=0.40, category="grocery"),

    # Subscriptions (fixed day)
    TxTemplate("NETFLIX MONTHLY", "Netflix", "DEBIT",
               15.99, 15.99, day_of_month=10, category="streaming"),
    TxTemplate("SPOTIFY PREMIUM", "Spotify", "DEBIT",
               9.99, 9.99, day_of_month=12, category="streaming"),
    TxTemplate("PLANET FITNESS", "Planet Fitness", "DEBIT",
               24.99, 24.99, day_of_month=5, category="fitness"),
    TxTemplate("AMAZON PRIME", "Amazon", "DEBIT",
               14.99, 14.99, day_of_month=22, category="shopping"),
    TxTemplate("ICLOUD STORAGE", "Apple", "DEBIT",
               2.99, 2.99, day_of_month=8, category="tech"),

    # Utilities (fixed day)
    TxTemplate("ONCOR ELECTRIC", "Oncor Electric", "DEBIT",
               85, 180, day_of_month=18,
               seasonal_mult={6: 1.6, 7: 1.8, 8: 1.9, 1: 1.3, 2: 1.2},
               category="utilities"),
    TxTemplate("AT&T INTERNET", "AT&T", "DEBIT",
               75, 75, day_of_month=20, category="utilities"),
    TxTemplate("T-MOBILE AUTOPAY", "T-Mobile", "DEBIT",
               65, 65, day_of_month=7, category="utilities"),
    TxTemplate("CITY OF DENTON WATER", "City of Denton", "DEBIT",
               45, 90, day_of_month=25, category="utilities"),

    # Gas
    TxTemplate("SHELL GAS STATION", "Shell", "DEBIT",
               45, 72, day_of_week=1, probability=0.80,
               category="gas", is_anomaly_seed=True),
    TxTemplate("EXXON MOBIL", "ExxonMobil", "DEBIT",
               40, 65, day_of_week=4, probability=0.45, category="gas"),
    TxTemplate("BUC-EES", "Buc-ee's", "DEBIT",
               50, 85, probability=0.02, category="gas"),

    # Restaurants
    TxTemplate("CHICK-FIL-A", "Chick-fil-A", "DEBIT",
               8, 22, day_of_week=5, probability=0.65, category="restaurant"),
    TxTemplate("CHIPOTLE", "Chipotle", "DEBIT",
               10, 18, day_of_week=3, probability=0.50, category="restaurant"),
    TxTemplate("DOMINOS PIZZA", "Dominos", "DEBIT",
               18, 38, day_of_week=6, probability=0.40,
               seasonal_mult={2: 1.3, 11: 1.2}, category="restaurant"),
    TxTemplate("STARBUCKS", "Starbucks", "DEBIT",
               5.50, 9.50, probability=0.35, category="restaurant"),
    TxTemplate("WHATABURGER", "Whataburger", "DEBIT",
               7, 16, probability=0.18, category="restaurant"),
    TxTemplate("PANDA EXPRESS", "Panda Express", "DEBIT",
               9, 18, probability=0.12, category="restaurant"),
    TxTemplate("RAISING CANES", "Raising Cane's", "DEBIT",
               10, 20, day_of_week=5, probability=0.30, category="restaurant"),

    # Shopping
    TxTemplate("AMAZON MARKETPLACE", "Amazon", "DEBIT",
               15, 120, probability=0.22,
               seasonal_mult={11: 1.8, 12: 2.5}, category="shopping"),
    TxTemplate("TARGET", "Target", "DEBIT",
               20, 95, day_of_week=6, probability=0.35,
               seasonal_mult={12: 1.6}, category="shopping"),
    TxTemplate("CVS PHARMACY", "CVS", "DEBIT",
               12, 55, probability=0.12, category="health"),
    TxTemplate("WALGREENS", "Walgreens", "DEBIT",
               10, 50, probability=0.10, category="health"),
    TxTemplate("UBER", "Uber", "DEBIT",
               8, 35, day_of_week=5, probability=0.28, category="transport"),
    TxTemplate("PARKMOBILE", "ParkMobile", "DEBIT",
               3, 18, probability=0.10, category="transport"),

    # Travel (summer-heavy)
    TxTemplate("SOUTHWEST AIRLINES", "Southwest Airlines", "DEBIT",
               180, 420, probability=0.06,
               seasonal_mult={6: 3.5, 7: 3.5, 8: 2.5, 12: 2.0},
               category="travel", is_anomaly_seed=True),
    TxTemplate("AIRBNB", "Airbnb", "DEBIT",
               100, 350, probability=0.04,
               seasonal_mult={6: 4.5, 7: 4.5, 8: 3.5},
               category="travel"),
    TxTemplate("HILTON HOTELS", "Hilton", "DEBIT",
               110, 280, probability=0.03,
               seasonal_mult={7: 5.0, 8: 4.0, 12: 3.0},
               category="travel"),
    TxTemplate("ENTERPRISE RENT-A-CAR", "Enterprise", "DEBIT",
               45, 130, probability=0.03,
               seasonal_mult={6: 3.0, 7: 3.0, 12: 2.0},
               category="travel"),
]

CATEGORY_MULT_MAP = {
    "grocery":       "grocery_mult",
    "streaming":     "subscription_mult",
    "fitness":       "subscription_mult",
    "tech":          "subscription_mult",
    "utilities":     "utility_mult",
    "gas":           "gas_mult",
    "restaurant":    "restaurant_mult",
    "shopping":      "shopping_mult",
    "health":        "shopping_mult",
    "transport":     "gas_mult",
    "entertainment": "subscription_mult",
    "travel":        "travel_mult",
    "misc":          "shopping_mult",
}


# -- Profiles -------------------------------------------------------------------

PROFILES = [

    # 1 - High Earner / Disciplined Saver
    UserProfile(
        user_id="aaaaaaaa-0001-0001-0001-000000000001",
        name="Priya Sharma", description="High Earner / Disciplined Saver",
        starting_balance=Decimal("25000.00"),
        anomaly_count=1, anomaly_mult=3.0, seed=101,
        grocery_mult=1.4, restaurant_mult=1.8, subscription_mult=2.0,
        utility_mult=1.2, gas_mult=0.6, shopping_mult=1.5, travel_mult=2.5,
        extra_debits=[
            ("VANGUARD 401K",          "Vanguard",       500,  500,  1,    0),
            ("ROTH IRA - FIDELITY",    "Fidelity",       500,  500,  15,   0),
            ("HULU SUBSCRIPTION",      "Hulu",           17.99,17.99,14,   0),
            ("APPLE ONE SUBSCRIPTION", "Apple",          29.95,29.95,8,    0),
            ("PELOTON MEMBERSHIP",     "Peloton",        44,   44,   20,   0),
            ("WHOLE FOODS EXTRA RUN",  "Whole Foods Market", 80,180, None, 0.08),
            ("NORDSTROM",              "Nordstrom",      80,  300,  None,  0.05),
        ],
        extra_credits=[
            ("PAYROLL - TECHCORP",     "TechCorp",  6000, 6000, 1),
            ("PAYROLL - TECHCORP",     "TechCorp",  6000, 6000, 15),
        ],
    ),

    # 2 - Paycheck to Paycheck
    UserProfile(
        user_id="bbbbbbbb-0002-0002-0002-000000000002",
        name="Marcus Johnson", description="Paycheck to Paycheck",
        starting_balance=Decimal("320.00"),
        anomaly_count=3, anomaly_mult=2.5, seed=202,
        grocery_mult=0.7, restaurant_mult=1.2, subscription_mult=0.5,
        utility_mult=0.9, gas_mult=1.4, shopping_mult=0.6, travel_mult=0.1,
        extra_debits=[
            ("PAYDAY LOAN PAYMENT",  "ACE Cash Express",  150, 220,  5,    0),
            ("OVERDRAFT FEE",        "Chase Bank",         35,  35,  None, 0.07),
            ("DOLLAR GENERAL",       "Dollar General",      8,  28,  None, 0.45),
            ("MCDONALDS",            "McDonald's",          6,  15,  None, 0.40),
            ("FAMILY DOLLAR",        "Family Dollar",       5,  30,  None, 0.30),
            ("CASH APP TRANSFER",    "Cash App",           20, 100,  None, 0.10),
            ("RENT PAYMENT",         "Greystar",          950, 950,  1,    0),
            ("TACO BELL",            "Taco Bell",           5,  14,  None, 0.30),
        ],
        extra_credits=[
            ("PAYROLL - WAREHOUSE",  "Amazon Warehouse", 1700, 1700, 1),
            ("PAYROLL - WAREHOUSE",  "Amazon Warehouse", 1700, 1700, 15),
        ],
    ),

    # 3 - Young Professional / High Debt
    UserProfile(
        user_id="cccccccc-0003-0003-0003-000000000003",
        name="Alex Chen", description="Young Professional / High Debt",
        starting_balance=Decimal("1200.00"),
        anomaly_count=2, anomaly_mult=3.5, seed=303,
        grocery_mult=0.9, restaurant_mult=1.5, subscription_mult=1.3,
        utility_mult=0.7, gas_mult=0.7, shopping_mult=1.2, travel_mult=0.5,
        extra_debits=[
            ("NAVIENT STUDENT LOAN",  "Navient",          450, 450,  3,    0),
            ("DISCOVER CARD PAYMENT", "Discover",         280, 380,  20,   0),
            ("DOORDASH",              "DoorDash",          18,  48,  None, 0.45),
            ("UBER EATS",             "Uber Eats",         15,  42,  None, 0.35),
            ("CHEWY PET SUPPLIES",    "Chewy",             45,  90,  None, 0.12),
            ("HINGE PREMIUM",         "Hinge",             29.99,29.99,18, 0),
            ("RAMEN PLACE",           "Jinya Ramen",       14,  28,  None, 0.20),
            ("APPLE MUSIC",           "Apple",              9.99,9.99,11,  0),
        ],
        extra_credits=[
            ("PAYROLL - DELOITTE",   "Deloitte",  2750, 2750, 1),
            ("PAYROLL - DELOITTE",   "Deloitte",  2750, 2750, 15),
        ],
    ),

    # 4 - Overspender with Anomalies
    UserProfile(
        user_id="dddddddd-0004-0004-0004-000000000004",
        name="Brandon Willis", description="Overspender with Anomalies",
        starting_balance=Decimal("3500.00"),
        anomaly_count=8, anomaly_mult=5.0, seed=404,
        grocery_mult=1.6, restaurant_mult=2.8, subscription_mult=2.5,
        utility_mult=1.5, gas_mult=1.9, shopping_mult=3.2, travel_mult=2.0,
        extra_debits=[
            ("NORDSTROM",             "Nordstrom",        150, 700,  None, 0.18),
            ("APPLE STORE",           "Apple",            299,1299,  None, 0.06),
            ("RUTH CHRIS STEAKHOUSE", "Ruth's Chris",     120, 300,  None, 0.12),
            ("GOLF MEMBERSHIP",       "Prestonwood Golf", 250, 250,  1,    0),
            ("BMW FINANCIAL",         "BMW",              750, 750,  5,    0),
            ("TOTAL WINE",            "Total Wine",        45, 200,  None, 0.22),
            ("DOORDASH",              "DoorDash",          25,  70,  None, 0.45),
            ("STUBHUB TICKETS",       "StubHub",           80, 500,  None, 0.08),
            ("SAKS FIFTH AVENUE",     "Saks Fifth Ave",   100, 800,  None, 0.06),
            ("RESORT FEE",            "Marriott",         200, 600,  None, 0.04),
        ],
        extra_credits=[
            ("PAYROLL - SALESFORCE",  "Salesforce", 3500, 3500, 1),
            ("PAYROLL - SALESFORCE",  "Salesforce", 3500, 3500, 15),
        ],
    ),

    # 5 - Freelancer / Irregular Income
    UserProfile(
        user_id="eeeeeeee-0005-0005-0005-000000000005",
        name="Sofia Reyes", description="Freelancer / Irregular Income",
        starting_balance=Decimal("8000.00"),
        anomaly_count=2, anomaly_mult=3.0, seed=505,
        grocery_mult=1.0, restaurant_mult=0.9, subscription_mult=1.3,
        utility_mult=1.0, gas_mult=0.8, shopping_mult=1.1, travel_mult=1.5,
        extra_debits=[
            ("ADOBE CREATIVE CLOUD",  "Adobe",             54.99,54.99,8,  0),
            ("FIGMA SUBSCRIPTION",    "Figma",             45,   45,  10,  0),
            ("NOTION PRO",            "Notion",            16,   16,  12,  0),
            ("WEWORK COWORKING",      "WeWork",            250, 250,  1,   0),
            ("QUICKBOOKS ONLINE",     "Intuit",            30,   30,  20,  0),
            ("LINKEDIN PREMIUM",      "LinkedIn",          39.99,39.99,22, 0),
            ("DRIBBBLE PRO",          "Dribbble",          20,   20,  15,  0),
        ],
        extra_credits=[],   # handled by irregular income logic
    ),

    # 6 - Retired / Fixed Income
    UserProfile(
        user_id="ffffffff-0006-0006-0006-000000000006",
        name="Robert & Helen Davis", description="Retired / Fixed Income",
        starting_balance=Decimal("45000.00"),
        anomaly_count=1, anomaly_mult=4.0, seed=606,
        grocery_mult=0.9, restaurant_mult=0.7, subscription_mult=0.5,
        utility_mult=1.1, gas_mult=0.6, shopping_mult=0.6, travel_mult=1.2,
        extra_debits=[
            ("MEDICARE SUPPLEMENT",   "AARP",             185, 185,  3,   0),
            ("PRESCRIPTION PICKUP",   "CVS Pharmacy",      45, 130,  None,0.22),
            ("DOCTORS VISIT COPAY",   "Medical Center",    30,  65,  None,0.10),
            ("KROGER",                "Kroger",            40, 100,  None,0.50),
            ("HOBBY LOBBY",           "Hobby Lobby",       20,  80,  None,0.15),
            ("GOLDEN CORRAL",         "Golden Corral",     12,  25,  None,0.20),
            ("CRACKER BARREL",        "Cracker Barrel",    14,  30,  None,0.12),
            ("BOOK SUBSCRIPTION",     "Book of Month",     16.99,16.99,18,0),
        ],
        extra_credits=[
            ("SOCIAL SECURITY",       "US Treasury",  1850, 1850, 3),
            ("PENSION - STATE OF TX", "State Pension",2350, 2350, 1),
        ],
    ),

    # 7 - Dual Income Household
    UserProfile(
        user_id="a7a7a7a7-0007-0007-0007-000000000007",
        name="James & Sarah Mitchell", description="Dual Income Household",
        starting_balance=Decimal("18000.00"),
        anomaly_count=2, anomaly_mult=3.5, seed=707,
        grocery_mult=1.8, restaurant_mult=1.7, subscription_mult=2.2,
        utility_mult=1.8, gas_mult=2.0, shopping_mult=2.0, travel_mult=1.8,
        extra_debits=[
            ("WELLS FARGO MORTGAGE",   "Wells Fargo",     2450,2450, 1,   0),
            ("DAYCARE - LITTLE STARS", "Little Stars",    1200,1200, 1,   0),
            ("COSTCO WHOLESALE",       "Costco",            80, 280, None,0.35),
            ("KIDS SOCCER LEAGUE",     "Rec Sports",        60, 120, None,0.10),
            ("PIANO LESSONS",          "Music Academy",     80,  80, 5,   0),
            ("HOME DEPOT",             "Home Depot",        40, 380, None,0.15),
            ("HULU + LIVE TV",         "Hulu",              82.99,82.99,17,0),
            ("DISNEY PLUS",            "Disney",            13.99,13.99,9, 0),
            ("KIDS ACTIVITY",          "Activity Center",   30, 120, None,0.10),
        ],
        extra_credits=[
            ("PAYROLL - JAMES",        "Dell Technologies",4000,4000,1),
            ("PAYROLL - JAMES",        "Dell Technologies",4000,4000,15),
            ("PAYROLL - SARAH",        "Frisco ISD",       3500,3500,1),
            ("PAYROLL - SARAH",        "Frisco ISD",       3500,3500,15),
        ],
    ),

    # 8 - Recent Grad / Rebuilding
    UserProfile(
        user_id="a8a8a8a8-0008-0008-0008-000000000008",
        name="Taylor Kim", description="Recent Grad / Rebuilding Credit",
        starting_balance=Decimal("800.00"),
        anomaly_count=2, anomaly_mult=2.0, seed=808,
        grocery_mult=0.65, restaurant_mult=0.55, subscription_mult=0.45,
        utility_mult=0.45, gas_mult=0.45, shopping_mult=0.45, travel_mult=0.15,
        extra_debits=[
            ("SALLIE MAE PAYMENT",   "Sallie Mae",        180, 180, 10,  0),
            ("CAPITAL ONE PAYMENT",  "Capital One",        50, 150, 18,  0),
            ("LIDL GROCERY",         "Lidl",               20,  55, None,0.40),
            ("GOODWILL",             "Goodwill",            5,  25, None,0.10),
            ("DART TRANSIT PASS",    "DART",               96,  96, 1,   0),
            ("ROBINHOOD TRANSFER",   "Robinhood",          25,  50, None,0.10),
            ("PLANET FITNESS",       "Planet Fitness",     10,  10, 5,   0),
        ],
        extra_credits=[
            ("PAYROLL - INDEED",     "Indeed",  1400, 1400, 1),
            ("PAYROLL - INDEED",     "Indeed",  1400, 1400, 15),
        ],
    ),
]


# -- Freelancer Income Logic ----------------------------------------------------

def build_freelancer_credits(start: date, end: date) -> list[tuple]:
    """Lumpy income: feast months, normal months, famine months."""
    credits = []
    current = start.replace(day=1)
    while current <= end:
        scenario = random.choices(
            ["feast", "normal", "famine"],
            weights=[0.25, 0.55, 0.20]
        )[0]

        if scenario == "feast":
            for _ in range(random.randint(1, 3)):
                amount = round(random.uniform(3500, 9000), 2)
                day    = random.randint(1, 28)
                d      = current.replace(day=day)
                if d <= end:
                    credits.append(("CLIENT PAYMENT", "Freelance Client", amount, d))
        elif scenario == "normal":
            amount = round(random.uniform(2000, 4500), 2)
            day    = random.randint(5, 20)
            d      = current.replace(day=day)
            if d <= end:
                credits.append(("CLIENT PAYMENT", "Freelance Client", amount, d))
        # famine: nothing

        if current.month == 12:
            current = current.replace(year=current.year + 1, month=1)
        else:
            current = current.replace(month=current.month + 1)

    return credits


# -- Core Generator -------------------------------------------------------------

def generate_for_profile(profile: UserProfile,
                          months: int = MONTHS) -> list[Transaction]:
    random.seed(profile.seed)

    transactions: list[Transaction] = []
    balance    = profile.starting_balance
    end_date   = date.today()
    start_date = end_date - timedelta(days=months * 30)

    # Anomaly candidates from baseline + profile extras
    anomaly_pool = [t for t in BASELINE if t.is_anomaly_seed]

    # Freelancer income pre-built
    freelancer_credits = (
        build_freelancer_credits(start_date, end_date)
        if profile.description.startswith("Freelancer") else []
    )
    freelancer_lookup: dict[date, list[tuple]] = {}
    for item in freelancer_credits:
        freelancer_lookup.setdefault(item[3], []).append(item)

    def add(description: str, merchant: str, tx_type: str,
            amount: Decimal, tx_date: date, anomaly: bool = False) -> None:
        nonlocal balance
        if tx_type == "CREDIT":
            balance += amount
        else:
            balance -= amount

        dt = datetime(
            tx_date.year, tx_date.month, tx_date.day,
            random.randint(7, 21), random.randint(0, 59), random.randint(0, 59)
        )
        transactions.append(Transaction(
            id=str(uuid.uuid4()),
            user_id=profile.user_id,
            transaction_type=tx_type,
            post_date=dt,
            description=description,
            merchant_name=merchant,
            amount=amount,
            balance=max(balance, Decimal("0.00")),
            is_anomaly=anomaly,
        ))

    # -- Walk every day ---------------------------------------------
    current = start_date
    while current <= end_date:
        dow   = current.weekday()
        month = current.month

        # Fixed-day credits (salary, pension, SS)
        for c in profile.extra_credits:
            desc, merchant, mn, mx, day = c
            if current.day == day:
                amt = Decimal(str(round(random.uniform(mn, mx), 2)))
                add(desc, merchant, "CREDIT", amt, current)

        # Freelancer irregular credits
        for item in freelancer_lookup.get(current, []):
            add(item[0], item[1], "CREDIT", Decimal(str(item[2])), current)

        # Baseline debit templates
        for tmpl in BASELINE:
            mult = getattr(profile, CATEGORY_MULT_MAP.get(tmpl.category, "shopping_mult"), 1.0)

            if tmpl.day_of_month is not None:
                if current.day == tmpl.day_of_month and random.random() < tmpl.probability:
                    add(tmpl.description, tmpl.merchant_name, "DEBIT",
                        tmpl.amount(month, mult), current)

            elif tmpl.day_of_week is not None:
                if dow == tmpl.day_of_week and random.random() < tmpl.probability:
                    add(tmpl.description, tmpl.merchant_name, "DEBIT",
                        tmpl.amount(month, mult), current)

            else:
                # Daily probability = weekly_prob / 7
                if random.random() < min((tmpl.probability / 7) * mult, 0.85):
                    add(tmpl.description, tmpl.merchant_name, "DEBIT",
                        tmpl.amount(month, mult), current)

        # Profile-specific extra debits
        for extra in profile.extra_debits:
            desc, merchant = extra[0], extra[1]
            mn, mx         = extra[2], extra[3]
            dom            = extra[4] if len(extra) > 4 else None
            prob           = extra[5] if len(extra) > 5 else 0.0

            if dom is not None:
                if current.day == dom:
                    amt = Decimal(str(round(random.uniform(mn, mx), 2)))
                    add(desc, merchant, "DEBIT", amt, current)
            elif prob > 0 and random.random() < prob:
                amt = Decimal(str(round(random.uniform(mn, mx), 2)))
                add(desc, merchant, "DEBIT", amt, current)

        current += timedelta(days=1)

    # -- Inject anomalies -------------------------------------------
    all_dates = [start_date + timedelta(days=i)
                 for i in range((end_date - start_date).days)]
    for _ in range(profile.anomaly_count):
        if not anomaly_pool:
            break
        tmpl    = random.choice(anomaly_pool)
        tx_date = random.choice(all_dates)
        amount  = Decimal(str(round(
            random.uniform(tmpl.amount_min, tmpl.amount_max) * profile.anomaly_mult, 2
        )))
        add(tmpl.description, tmpl.merchant_name,
            "DEBIT", amount, tx_date, anomaly=True)

    # Sort and recompute running balance chronologically
    transactions.sort(key=lambda t: t.post_date)
    running = profile.starting_balance
    for tx in transactions:
        if tx.transaction_type == "CREDIT":
            running += tx.amount
        else:
            running -= tx.amount
        tx.balance = max(running, Decimal("0.00"))

    return transactions


# -- DB Insert ------------------------------------------------------------------

def insert_transactions(txns: list[Transaction],
                         clear: bool = False,
                         user_ids: Optional[list[str]] = None) -> None:
    try:
        import psycopg2
    except ImportError:
        print("ERROR: Run: pip install psycopg2-binary")
        sys.exit(1)

    conn   = psycopg2.connect(**DB_CONFIG)
    cursor = conn.cursor()

    try:
        if clear:
            targets = user_ids or [p.user_id for p in PROFILES]
            print(f"\n[DB] Clearing data for {len(targets)} user(s)...")
            for uid in targets:
                cursor.execute(
                    "DELETE FROM transaction_enrichments WHERE transaction_id IN "
                    "(SELECT id FROM transactions WHERE user_id = %s::uuid)", (uid,))
                cursor.execute("DELETE FROM transactions WHERE user_id = %s::uuid",   (uid,))
                cursor.execute("DELETE FROM recurring_bills WHERE user_id = %s::uuid",(uid,))
                cursor.execute("DELETE FROM users WHERE id = %s::uuid", (uid,))
            conn.commit()
            print("[DB] Cleared.")

        # Insert users first (foreign key requirement)
        print(f"\n[DB] Inserting users...")
        targets = user_ids or [p.user_id for p in PROFILES]
        profile_map = {p.user_id: p for p in PROFILES}
        for uid in targets:
            p = profile_map.get(uid)
            if p:
                email = p.name.lower().replace(" ", ".").replace("&", "and") + "@finwise.test"
                cursor.execute("""
                    INSERT INTO users (id, email, full_name, created_at)
                    VALUES (%s::uuid, %s, %s, NOW())
                    ON CONFLICT (id) DO NOTHING
                """, (p.user_id, email, p.name))
        conn.commit()
        print(f"[DB] {len(targets)} users ready.")

        print(f"\n[DB] Inserting {len(txns):,} transactions...")
        sql = """
            INSERT INTO transactions
                (id, user_id, transaction_type, post_date,
                 description, merchant_name, amount, balance)
            VALUES (%s::uuid,%s::uuid,%s,%s,%s,%s,%s,%s)
            ON CONFLICT (id) DO NOTHING
        """
        batch = [
            (t.id, t.user_id, t.transaction_type,
             t.post_date.isoformat(),
             t.description, t.merchant_name,
             float(t.amount), float(t.balance))
            for t in txns
        ]
        cursor.executemany(sql, batch)
        conn.commit()
        users = len(set(t.user_id for t in txns))
        print(f"[DB] [OK] {len(batch):,} transactions inserted across {users} users")

    except Exception as e:
        conn.rollback()
        print(f"[DB] ERROR: {e}")
        raise
    finally:
        cursor.close()
        conn.close()


# -- Summary Printer ------------------------------------------------------------

def print_profile_summary(profile: UserProfile, txns: list[Transaction]) -> None:
    debits    = [t for t in txns if t.transaction_type == "DEBIT"]
    credits   = [t for t in txns if t.transaction_type == "CREDIT"]
    anomalies = [t for t in txns if t.is_anomaly]

    income   = sum(t.amount for t in credits)
    spending = sum(t.amount for t in debits)
    net      = income - spending

    by_merchant: dict[str, Decimal] = {}
    for t in debits:
        by_merchant[t.merchant_name] = \
            by_merchant.get(t.merchant_name, Decimal("0")) + t.amount
    top5 = sorted(by_merchant.items(), key=lambda x: x[1], reverse=True)[:5]

    print(f"\n  {'-'*58}")
    print(f"  {profile.name:<34} [{profile.description}]")
    print(f"  {'-'*58}")
    print(f"  User:      {profile.user_id}")
    print(f"  Txns:      {len(txns):>5}  "
          f"(debits={len(debits)}, credits={len(credits)}, anomalies={len(anomalies)})")
    print(f"  Income:    ${income:>12,.2f}")
    print(f"  Spending:  ${spending:>12,.2f}")
    print(f"  Net:       ${net:>12,.2f}  ({'surplus' if net >= 0 else 'DEFICIT'})")
    print(f"  Balance:   ${txns[-1].balance:>12,.2f}")
    print(f"  Top 5:     {', '.join(f'{m}(${a:,.0f})' for m, a in top5)}")
    if anomalies:
        print(f"  Anomalies: {', '.join(f'{t.merchant_name} ${t.amount:,.2f}' for t in anomalies)}")


# -- Main -----------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="FinWise multi-user synthetic data generator")
    parser.add_argument("--months",  type=int, default=MONTHS,
                        help="Months to generate (default: 12)")
    parser.add_argument("--dry-run", action="store_true",
                        help="Print summary only, no DB insert")
    parser.add_argument("--clear",   action="store_true",
                        help="Clear existing data for these users first")
    parser.add_argument("--user",    type=int, default=0,
                        help="Generate single user 1-8 (default: all)")
    args = parser.parse_args()

    profiles = [PROFILES[args.user - 1]] if args.user > 0 else PROFILES

    print(f"\n{'='*62}")
    print(f"  FinWise AI - Multi-User Data Generator")
    print(f"  Users: {len(profiles)}  |  Months: {args.months}  |  "
          f"Target: ~{len(profiles) * 1000:,} txns")
    print(f"{'='*62}")

    all_txns: list[Transaction] = []
    for profile in profiles:
        print(f"\n  Generating {profile.name}...", end="", flush=True)
        txns = generate_for_profile(profile, months=args.months)
        all_txns.extend(txns)
        print(f" {len(txns):,} txns")
        print_profile_summary(profile, txns)

    print(f"\n{'='*62}")
    print(f"  TOTAL: {len(all_txns):,} transactions across {len(profiles)} users")
    print(f"{'='*62}")

    if args.dry_run:
        print("\n[DRY RUN] No data inserted. Remove --dry-run to insert.\n")
        return

    user_ids = [p.user_id for p in profiles]
    insert_transactions(all_txns, clear=args.clear, user_ids=user_ids)

    print(f"\n[OK] Done! Trigger enrichment for each user:")
    for p in profiles:
        print(f"  POST http://localhost:8080/api/enrichment/user/{p.user_id}/all")
    print()


if __name__ == "__main__":
    main()
