
import requests
import random
import time
import uuid
import argparse
from datetime import datetime, timedelta
from typing import List, Dict, Optional

# ── CONFIG ────────────────────────────────────────────────────────────
BACKEND_URL         = "http://backend:8080/api/transactions"
REPORTING_THRESHOLD = 1_000_000   # INR 10 lakh

PAYMENT_FORMATS = ["NEFT", "RTGS", "IMPS", "UPI", "Cheque"]

# 30 people — enough variety for demo
PEOPLE = [
    ("ACC_001", "Rajesh Kumar"),     ("ACC_002", "Priya Sharma"),
    ("ACC_003", "Amit Patel"),       ("ACC_004", "Sunita Verma"),
    ("ACC_005", "Vijay Singh"),      ("ACC_006", "Meera Nair"),
    ("ACC_007", "Arjun Mehta"),      ("ACC_008", "Kavitha Rao"),
    ("ACC_009", "Suresh Iyer"),      ("ACC_010", "Deepa Krishnan"),
    ("ACC_011", "Ravi Gupta"),       ("ACC_012", "Anita Joshi"),
    ("ACC_013", "Manoj Tiwari"),     ("ACC_014", "Pooja Agarwal"),
    ("ACC_015", "Sanjay Desai"),     ("ACC_016", "Neha Patil"),
    ("ACC_017", "Prakash Nair"),     ("ACC_018", "Rekha Menon"),
    ("ACC_019", "Dinesh Yadav"),     ("ACC_020", "Smita Kulkarni"),
    ("ACC_021", "Vinod Chandra"),    ("ACC_022", "Lata Mishra"),
    ("ACC_023", "Ramesh Bhat"),      ("ACC_024", "Geeta Pillai"),
    ("ACC_025", "Ashok Trivedi"),    ("ACC_026", "Seema Rawat"),
    ("ACC_027", "Harish Kapoor"),    ("ACC_028", "Nandini Sen"),
    ("ACC_029", "Govind Rao"),       ("ACC_030", "Pallavi Jain"),

    ("ACC_031", "Anil Saxena"),      ("ACC_032", "Kiran Reddy"),
    ("ACC_033", "Sandeep Khanna"),   ("ACC_034", "Ritu Malhotra"),
    ("ACC_035", "Nitin Bansal"),     ("ACC_036", "Shweta Dubey"),
    ("ACC_037", "Alok Srivastava"),  ("ACC_038", "Preeti Chawla"),
    ("ACC_039", "Tarun Arora"),      ("ACC_040", "Divya Sehgal"),
    ("ACC_041", "Kunal Verma"),      ("ACC_042", "Sneha Kapoor"),
    ("ACC_043", "Rahul Malhotra"),   ("ACC_044", "Isha Bhatia"),
    ("ACC_045", "Vikas Oberoi"),     ("ACC_046", "Poonam Sinha"),
    ("ACC_047", "Rohit Sethi"),      ("ACC_048", "Komal Arora"),
    ("ACC_049", "Sameer Kulshreshtha"), ("ACC_050", "Anjali Bansal"),

    ("ACC_051", "Mahesh Shetty"),    ("ACC_052", "Lakshmi Iyer"),
    ("ACC_053", "Ganesh Pillai"),    ("ACC_054", "Revathi Nair"),
    ("ACC_055", "Raghavendra Rao"),  ("ACC_056", "Bhavana Shetty"),
    ("ACC_057", "Srinivas Murthy"),  ("ACC_058", "Anupama Gowda"),
    ("ACC_059", "Pradeep Reddy"),    ("ACC_060", "Soumya Iyer"),

    ("ACC_061", "Farhan Khan"),      ("ACC_062", "Ayesha Khan"),
    ("ACC_063", "Imran Shaikh"),     ("ACC_064", "Nazia Pathan"),
    ("ACC_065", "Salman Qureshi"),   ("ACC_066", "Shabana Ali"),
    ("ACC_067", "Asif Siddiqui"),    ("ACC_068", "Rubina Sheikh"),

    ("ACC_069", "Tsering Dorje"),    ("ACC_070", "Sonam Wangchuk"),
    ("ACC_071", "Pema Lepcha"),      ("ACC_072", "Karma Bhutia"),

    ("ACC_073", "Joseph Dsouza"),    ("ACC_074", "Maria Fernandes"),
    ("ACC_075", "Anthony Rodrigues"),("ACC_076", "Clara Dsilva"),

    ("ACC_077", "Bikash Das"),       ("ACC_078", "Ananya Chatterjee"),
    ("ACC_079", "Subhash Bose"),     ("ACC_080", "Moushumi Sen"),

    ("ACC_081", "Jitendra Chauhan"), ("ACC_082", "Kavita Tomar"),
    ("ACC_083", "Rakesh Solanki"),   ("ACC_084", "Neelam Rathore"),
    ("ACC_085", "Mukesh Jadhav"),    ("ACC_086", "Vaishali Pawar"),
    ("ACC_087", "Pravin Shinde"),    ("ACC_088", "Archana More"),

    ("ACC_089", "Hemant Thakur"),    ("ACC_090", "Savita Negi"),
    ("ACC_091", "Devendra Bisht"),   ("ACC_092", "Pooja Rawal"),

    ("ACC_093", "Chandan Sahu"),     ("ACC_094", "Reena Yadav"),
    ("ACC_095", "Gopal Mishra"),     ("ACC_096", "Kusum Pandey"),

    ("ACC_097", "Ajay Dutta"),       ("ACC_098", "Nisha Paul"),
    ("ACC_099", "Sanjiv Roy"),       ("ACC_100", "Tanya Ghosh"),
]

ACCOUNT_IDS   = [p[0] for p in PEOPLE]
ACCOUNT_NAMES = {p[0]: p[1] for p in PEOPLE}


def ts(offset_minutes: int = 0) -> str:
    t = datetime.now() + timedelta(minutes=offset_minutes)
    return t.strftime("%Y-%m-%dT%H:%M:%S")


def make_tx(
    from_acc:   str,
    to_acc:     str,
    amount:     float,
    pfmt:       str,
    timestamp:  str  = None,
    pattern_id: str  = None,
    is_fraud:   bool = False,
    typology:   str  = None,
    note:       str  = None,
) -> Dict:
    return {
        "transaction_id":  str(uuid.uuid4()),
        "from_account":    from_acc,
        "from_name":       ACCOUNT_NAMES.get(from_acc, from_acc),
        "to_account":      to_acc,
        "to_name":         ACCOUNT_NAMES.get(to_acc, to_acc),
        "amount":          round(amount, 2),
        "currency":        "INR",
        "payment_format":  pfmt,
        "timestamp":       timestamp or ts(),
        "pattern_id":      pattern_id or str(uuid.uuid4()),
        "is_fraud_seed":   is_fraud,
        "typology_hint":   typology or "Legitimate",
        "note":            note or "",
    }


# ── LEGITIMATE ────────────────────────────────────────────────────────
def gen_legit() -> List[Dict]:
    a, b = random.sample(ACCOUNT_IDS, 2)
    return [make_tx(a, b, random.uniform(1_000, 500_000),
                    random.choice(PAYMENT_FORMATS), note="normal payment")]


def gen_legit_salary() -> List[Dict]:
    employer  = random.choice(ACCOUNT_IDS[:10])
    employees = random.sample([a for a in ACCOUNT_IDS if a != employer],
                               random.randint(2, 3))
    pid  = str(uuid.uuid4())
    return [
        make_tx(employer, emp, random.uniform(30_000, 150_000),
                "NEFT", timestamp=ts(i * 30), pattern_id=pid,
                note="salary")
        for i, emp in enumerate(employees)
    ]


# ── FRAUD GENERATORS ──────────────────────────────────────────────────
def gen_fan_out() -> List[Dict]:
    hub   = random.choice(ACCOUNT_IDS[:15])
    n     = random.randint(3, 5)
    dests = random.sample([a for a in ACCOUNT_IDS if a != hub], n)
    total = random.uniform(2_000_000, 8_000_000)
    pid   = str(uuid.uuid4())
    return [
        make_tx(hub, dst, (total/n) * random.uniform(0.85, 1.15),
                random.choice(["RTGS", "NEFT"]),
                timestamp=ts(i * random.randint(20, 60)),
                pattern_id=pid, is_fraud=True, typology="Fan-Out",
                note=f"fan-out leg {i+1}/{n}")
        for i, dst in enumerate(dests)
    ]


def gen_fan_in() -> List[Dict]:
    dst  = random.choice(ACCOUNT_IDS[15:])
    n    = random.randint(3, 5)
    srcs = random.sample([a for a in ACCOUNT_IDS if a != dst], n)
    pid  = str(uuid.uuid4())
    return [
        make_tx(src, dst, random.uniform(300_000, 1_500_000),
                random.choice(["RTGS", "NEFT", "IMPS"]),
                timestamp=ts(i * random.randint(20, 90)),
                pattern_id=pid, is_fraud=True, typology="Fan-In",
                note=f"fan-in leg {i+1}/{n}")
        for i, src in enumerate(srcs)
    ]


def gen_round_trip() -> List[Dict]:
    n_hops   = random.randint(3, 4)
    accts    = random.sample(ACCOUNT_IDS, n_hops)
    amount   = random.uniform(1_000_000, 5_000_000)
    pid      = str(uuid.uuid4())
    interval = random.randint(30, 90)
    txns     = []
    for i in range(n_hops):
        src = accts[i]
        dst = accts[(i + 1) % n_hops]
        txns.append(make_tx(
            src, dst, amount * (0.98 ** i), "RTGS",
            timestamp  = ts(i * interval),
            pattern_id = pid,
            is_fraud   = True,
            typology   = "Round-Trip",
            note       = f"hop {i+1}/{n_hops}"
                         + (" ← BACK TO ORIGIN" if i == n_hops-1 else "")
        ))
    return txns


def gen_mutual() -> List[Dict]:
    a, b   = random.sample(ACCOUNT_IDS, 2)
    amount = random.uniform(500_000, 3_000_000)
    pid    = str(uuid.uuid4())
    return [
        make_tx(a, b, amount, "RTGS",
                timestamp=ts(0), pattern_id=pid,
                is_fraud=True, typology="Mutual",
                note="mutual A→B"),
        make_tx(b, a, amount * random.uniform(0.97, 1.03), "RTGS",
                timestamp=ts(random.randint(60, 300)),
                pattern_id=pid, is_fraud=True, typology="Mutual",
                note="mutual B→A (near same amount)"),
    ]


def gen_structuring() -> List[Dict]:
    src  = random.choice(ACCOUNT_IDS[:10])
    n    = random.randint(4, 6)
    dsts = random.sample([a for a in ACCOUNT_IDS if a != src], n)
    pid  = str(uuid.uuid4())
    return [
        make_tx(src, dst,
                random.uniform(REPORTING_THRESHOLD * 0.92,
                               REPORTING_THRESHOLD * 0.999),
                random.choice(PAYMENT_FORMATS),
                timestamp  = ts(i * random.randint(60, 180)),
                pattern_id = pid,
                is_fraud   = True,
                typology   = "Structuring",
                note       = "below 10L threshold")
        for i, dst in enumerate(dsts)
    ]


def gen_dormant() -> List[Dict]:
    src     = random.choice(ACCOUNT_IDS[:10])
    dormant = random.choice(ACCOUNT_IDS[10:20])
    dst     = random.choice(ACCOUNT_IDS[20:])
    amount  = random.uniform(1_000_000, 5_000_000)
    pid     = str(uuid.uuid4())
    return [
        make_tx(src, dormant, amount, "RTGS",
                timestamp=ts(0), pattern_id=pid,
                is_fraud=True, typology="Dormant",
                note="activates dormant account"),
        make_tx(dormant, dst, amount * 0.99, "NEFT",
                timestamp=ts(random.randint(10, 45)),
                pattern_id=pid, is_fraud=True, typology="Dormant",
                note="immediate forward"),
    ]


# ── REGISTRY ──────────────────────────────────────────────────────────
FRAUD_GENERATORS = [
    ("Fan-Out",     gen_fan_out),
    ("Fan-In",      gen_fan_in),
    ("Round-Trip",  gen_round_trip),
    ("Mutual",      gen_mutual),
    ("Structuring", gen_structuring),
    ("Dormant",     gen_dormant),
]


# ── SEND ──────────────────────────────────────────────────────────────
def send(transactions: List[Dict], delay: float = 0.5) -> bool:
    for tx in transactions:
        try:
            resp = requests.post(BACKEND_URL, json=tx, timeout=10,
                                 headers={"Content-Type": "application/json"})
            flag  = "🚨" if tx.get("is_fraud_seed") else "  "
            fname = tx.get("from_name", tx["from_account"])[:14]
            tname = tx.get("to_name",   tx["to_account"])[:14]
            tip   = tx.get("typology_hint", "Legitimate")

            if resp.status_code in [200, 201]:
                print(f"{flag} {fname:<14} → {tname:<14} | "
                      f"₹{tx['amount']:>12,.0f} | "
                      f"{tx['payment_format']:<5} | {tip}")
            else:
                print(f"  ✗ HTTP {resp.status_code}: {resp.text[:80]}")

            time.sleep(delay)

        except requests.exceptions.ConnectionError:
            print(f"\n✗ Cannot connect to {BACKEND_URL}")
            print(f"  Is Spring Boot running on port 8080?")
            return False
        except Exception as e:
            print(f"  ✗ Error: {e}")
    return True


# ── AUTO MODE ─────────────────────────────────────────────────────────
def run_auto(typology_filter: Optional[str] = None):
    """
    Pattern: 2-3 legit → 1 fraud → 2-3 legit → next fraud typology
    Cycles all 6 typologies in order.
    """
    print(f"\nAUTO MODE")
    print(f"  Backend : {BACKEND_URL}")
    print(f"  Pattern : legit → fraud → legit → fraud (all typologies)")
    print("-" * 65)

    cycle_idx   = 0
    total_sent  = 0
    fraud_count = 0

    while True:
        try:
            # 2-3 legitimate first
            for _ in range(random.randint(2, 3)):
                txns = random.choice([gen_legit, gen_legit_salary])()
                send(txns, delay=0.8)
                total_sent += len(txns)

            time.sleep(1.0)

            # One fraud pattern — cycle through all typologies
            name, gen = FRAUD_GENERATORS[cycle_idx % len(FRAUD_GENERATORS)]
            cycle_idx += 1

            if typology_filter and typology_filter.lower() not in name.lower():
                continue

            print(f"\n  ┌── {name} pattern starting ──")
            txns = gen()
            send(txns, delay=1.0)
            total_sent  += len(txns)
            fraud_count += 1
            print(f"  └── {name} sent. Total fraud: {fraud_count}\n")

            time.sleep(2.0)

        except KeyboardInterrupt:
            print(f"\nStopped. Sent {total_sent} tx, {fraud_count} fraud patterns")
            break


# ── MANUAL MODE ───────────────────────────────────────────────────────
def run_manual():
    """Interactive CLI for live demo."""
    print("\nMANUAL MODE — AML Transaction Sender")
    print("Use this during live demo to show judges exactly what you're sending")
    print("="*60)

    MENU = {
        "1": ("Normal transaction (legitimate)",  gen_legit),
        "2": ("Salary payments (legitimate)",     gen_legit_salary),
        "3": ("Fan-Out fraud",                    gen_fan_out),
        "4": ("Fan-In fraud",                     gen_fan_in),
        "5": ("Round-Trip fraud",                 gen_round_trip),
        "6": ("Mutual fraud",                     gen_mutual),
        "7": ("Structuring fraud",                gen_structuring),
        "8": ("Dormant account fraud",            gen_dormant),
        "9": ("All typologies in sequence",       None),
        "c": ("Custom transaction",               None),
        "0": ("Exit",                             None),
    }

    while True:
        print("\nWhat to send?")
        for key, (label, _) in MENU.items():
            print(f"  {key}. {label}")

        choice = input("\n> ").strip().lower()

        if choice == "0":
            break

        elif choice == "9":
            print("\nSending all typologies...")
            for name, gen in FRAUD_GENERATORS:
                print(f"\n── {name} ──")
                send(gen_legit(), delay=0.3)
                time.sleep(0.5)
                send(gen(), delay=0.5)
                time.sleep(1.5)
            print("\nAll typologies sent!")

        elif choice == "c":
            print("\nAvailable accounts:")
            for acc, name in ACCOUNT_NAMES.items():
                print(f"  {acc}: {name}")
            try:
                fa = input("From account ID: ").strip()
                ta = input("To account ID: ").strip()
                am = float(input("Amount (INR): ").strip())
                pf = input(f"Format {PAYMENT_FORMATS} [NEFT]: ").strip() or "NEFT"

                if fa not in ACCOUNT_NAMES or ta not in ACCOUNT_NAMES:
                    print("Unknown account ID")
                    continue

                tx = make_tx(fa, ta, am, pf)
                print(f"\nSending: {tx['from_name']} → {tx['to_name']} | ₹{am:,.0f}")
                send([tx])
            except ValueError:
                print("Invalid amount")

        elif choice in MENU and MENU[choice][1]:
            _, gen = MENU[choice]
            txns = gen()
            print(f"\nSending {len(txns)} transaction(s)...")
            send(txns, delay=0.5)

        else:
            print("Invalid choice")


# ── MAIN ──────────────────────────────────────────────────────────────
if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["auto", "manual"],
                        default="manual",
                        help="auto=continuous stream | manual=interactive")
    parser.add_argument("--typology", default=None,
                        help="Filter auto to one typology e.g. 'Round-Trip'")
    args = parser.parse_args()

    if args.mode == "auto":
        run_auto(typology_filter=args.typology)
    else:
        run_manual()
