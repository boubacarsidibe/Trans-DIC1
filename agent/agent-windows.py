"""Agent Trans-DIC1 pour Windows.

Relève CPU, mémoire, disque et disponibilité et les envoie à la plateforme,
exactement dans le même format que l'agent Linux.

Prérequis :
    pip install psutil

Lancement rapide (test local) :
    set TRANS_DIC1_AGENT_SERVER_URL=http://localhost:8090
    set TRANS_DIC1_AGENT_TOKEN=tdic1_XXXXX
    set TRANS_DIC1_AGENT_ALLOW_INSECURE_HTTP=true
    python agent-windows.py

Variables d'environnement :
    TRANS_DIC1_AGENT_SERVER_URL   URL de la plateforme (obligatoire)
    TRANS_DIC1_AGENT_TOKEN        Jeton d'agent (obligatoire, commence par tdic1_)
    TRANS_DIC1_AGENT_INTERVAL     Secondes entre deux relevés (défaut 60, min 10)
    TRANS_DIC1_AGENT_DISK_PATH    Lecteur mesuré (défaut C:\\)
    TRANS_DIC1_AGENT_ALLOW_INSECURE_HTTP  true pour http:// en dev local
"""

import datetime
import json
import os
import sys
import time
import urllib.request
import urllib.error

try:
    import psutil
except ImportError:
    print("ERREUR : psutil manquant. Lance : pip install psutil")
    sys.exit(1)

# ------------------------------------------------------------------ configuration
SERVER_URL = os.environ.get("TRANS_DIC1_AGENT_SERVER_URL", "").rstrip("/")
TOKEN = os.environ.get("TRANS_DIC1_AGENT_TOKEN", "")
INTERVAL = int(os.environ.get("TRANS_DIC1_AGENT_INTERVAL", "60"))
DISK_PATH = os.environ.get("TRANS_DIC1_AGENT_DISK_PATH", "C:\\")
ALLOW_HTTP = os.environ.get("TRANS_DIC1_AGENT_ALLOW_INSECURE_HTTP", "false").lower() in ("1", "true", "yes", "oui")

def check_config():
    errors = []
    if not SERVER_URL:
        errors.append("TRANS_DIC1_AGENT_SERVER_URL est vide")
    elif SERVER_URL.startswith("http://") and not ALLOW_HTTP:
        errors.append("URL en http:// non autorisée sans TRANS_DIC1_AGENT_ALLOW_INSECURE_HTTP=true")
    if not TOKEN or len(TOKEN) < 20:
        errors.append("TRANS_DIC1_AGENT_TOKEN absent ou trop court")
    if not 10 <= INTERVAL <= 240:
        errors.append("TRANS_DIC1_AGENT_INTERVAL hors de la plage 10–240")
    return errors

# ------------------------------------------------------------------ métriques
def iso_now():
    now = datetime.datetime.now(datetime.timezone.utc)
    return now.strftime("%Y-%m-%dT%H:%M:%S.") + "%03dZ" % (now.microsecond // 1000)

def collect():
    ts = iso_now()
    measures = []

    # CPU (intervalle de 1 s pour avoir une valeur utile dès le premier relevé)
    cpu = psutil.cpu_percent(interval=1)
    measures.append({"key": "cpu.usage",    "value": round(cpu, 2),  "unit": "%", "quality": "GOOD", "collectedAt": ts})

    # Mémoire
    mem = psutil.virtual_memory()
    mem_pct = round(mem.percent, 2)
    measures.append({"key": "memory.usage", "value": mem_pct,        "unit": "%", "quality": "GOOD", "collectedAt": ts})

    # Disque
    try:
        disk = psutil.disk_usage(DISK_PATH)
        disk_pct = round(disk.percent, 2)
        quality = "GOOD"
    except OSError as e:
        print(f"AVERTISSEMENT disque ({DISK_PATH}) : {e}")
        disk_pct, quality = 0.0, "BAD"
    measures.append({"key": "disk.usage",   "value": disk_pct,       "unit": "%", "quality": quality, "collectedAt": ts})

    # Disponibilité : l'agent répond → 100 %
    measures.append({"key": "availability", "value": 100.0,          "unit": "%", "quality": "GOOD", "collectedAt": ts})

    return measures

# ------------------------------------------------------------------ envoi
def send(measures):
    url = SERVER_URL + "/api/v1/agent/measures"
    body = json.dumps({"measures": measures}).encode("utf-8")
    req = urllib.request.Request(
        url, data=body,
        headers={
            "Authorization": f"Bearer {TOKEN}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        return resp.status

# ------------------------------------------------------------------ boucle
def main():
    errors = check_config()
    if errors:
        for e in errors:
            print(f"ERREUR : {e}")
        print("\nVariables nécessaires :")
        print("  set TRANS_DIC1_AGENT_SERVER_URL=http://localhost:8090")
        print("  set TRANS_DIC1_AGENT_TOKEN=tdic1_...")
        print("  set TRANS_DIC1_AGENT_ALLOW_INSECURE_HTTP=true  (si http://)")
        sys.exit(2)

    print(f"Agent démarré → {SERVER_URL}  (intervalle {INTERVAL} s, disque {DISK_PATH})")
    print("Ctrl+C pour arrêter.\n")

    consecutive_errors = 0
    while True:
        try:
            measures = collect()
            status = send(measures)
            print(f"[{datetime.datetime.now().strftime('%H:%M:%S')}] {len(measures)} mesure(s) envoyée(s) (HTTP {status})")
            consecutive_errors = 0
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", errors="replace")[:200]
            print(f"[{datetime.datetime.now().strftime('%H:%M:%S')}] ERREUR HTTP {e.code} : {body}")
            if e.code in (401, 403):
                print("Jeton refusé : génère un nouveau jeton dans la plateforme.")
                sys.exit(1)
            consecutive_errors += 1
        except Exception as e:
            print(f"[{datetime.datetime.now().strftime('%H:%M:%S')}] ERREUR : {e}")
            consecutive_errors += 1

        if consecutive_errors >= 10:
            print("10 erreurs consécutives — arrêt.")
            sys.exit(1)

        time.sleep(INTERVAL)

if __name__ == "__main__":
    main()
