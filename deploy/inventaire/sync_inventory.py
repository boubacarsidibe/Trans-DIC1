#!/usr/bin/env python3
"""Synchronise l'inventaire Trans-DIC1 avec une liste de machines exportée d'une CMDB.

Usage :
    export TRANS_DIC1_API_TOKEN=...          # jeton d'API d'un administrateur ou d'un opérateur NOC
    python3 sync_inventory.py --server https://supervision.example --source machines.csv --dry-run
    python3 sync_inventory.py --server https://supervision.example --source machines.json

Source CSV (séparateur virgule ou point-virgule, première ligne = en-têtes) ou JSON (liste d'objets,
ou {"machines": [...]}), avec les champs : code, nom, adresse, type, site (code du site).
Types : SERVER, ROUTER, SWITCH, VIRTUAL_MACHINE, WIFI_ACCESS_POINT.

Idempotent : un équipement identique n'est pas réécrit, une seconde exécution ne change rien.
Le script crée et met à jour ; il ne supprime jamais un équipement absent de la source (il le
signale). Le jeton est lu dans l'environnement, jamais sur la ligne de commande (visible par tous
dans la liste des processus) et n'est jamais affiché. Bibliothèque standard, Python 3.8 et plus.

Codes de sortie : 0 tout est synchronisé ; 1 au moins une machine en erreur ; 2 usage ou accès.
"""

import argparse
import csv
import json
import os
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

TYPES = ("SERVER", "ROUTER", "SWITCH", "VIRTUAL_MACHINE", "WIFI_ACCESS_POINT")
FIELDS = ("code", "nom", "adresse", "type", "site")
PAGE_SIZE = 200
LIMITS = {"code": 64, "nom": 120, "adresse": 45}


class SyncError(Exception):
    """Erreur d'accès ou d'usage : la synchronisation s'arrête (code 2)."""


class Api:
    """Client minimal de l'API Trans-DIC1, authentifié par un jeton d'API."""

    def __init__(self, server, token, ca_file=None, timeout=20.0):
        self.base = server.rstrip("/") + "/api/v1"
        self.token = token
        self.timeout = timeout
        self.context = ssl.create_default_context(cafile=ca_file) if server.startswith("https://") else None

    def call(self, method, path, body=None, query=None):
        url = self.base + path + ("?" + urllib.parse.urlencode(query) if query else "")
        data = json.dumps(body).encode("utf-8") if body is not None else None
        request = urllib.request.Request(
            url,
            data=data,
            method=method,
            headers={
                "Authorization": "Bearer " + self.token,
                "Accept": "application/json",
                "Content-Type": "application/json",
                "User-Agent": "trans-dic1-sync-inventaire",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout, context=self.context) as response:
                payload = response.read()
                return json.loads(payload.decode("utf-8")) if payload else None
        except urllib.error.HTTPError as error:
            detail = _error_detail(error)
            error.close()
            if error.code in (401, 403):
                raise SyncError("accès refusé par la plateforme (HTTP %d) : vérifier le jeton et son rôle" % error.code)
            raise ApiError(error.code, detail)
        except (urllib.error.URLError, OSError) as error:
            reason = getattr(error, "reason", error)
            raise SyncError("plateforme injoignable (%s)" % type(reason).__name__)

    def pages(self, path, query=None):
        """Toutes les lignes d'une liste paginée de l'API."""
        page = 0
        while True:
            params = dict(query or {}, page=page, size=PAGE_SIZE)
            result = self.call("GET", path, query=params) or {}
            for item in result.get("content", []):
                yield item
            # Format de Spring Data (VIA_DTO) : {"content": [...], "page": {"totalPages": n}}.
            total = (result.get("page") or {}).get("totalPages", 1)
            page += 1
            if page >= total:
                return


class ApiError(Exception):
    """Refus d'une écriture par l'API (conflit, validation) : la machine est en erreur, on continue."""

    def __init__(self, status, detail):
        super().__init__("HTTP %d : %s" % (status, detail))
        self.status = status


def _error_detail(error):
    try:
        body = json.loads(error.read().decode("utf-8"))
        fields = body.get("fields") or {}
        extra = ", ".join("%s %s" % item for item in fields.items())
        return (body.get("error") or "sans détail") + (" (" + extra + ")" if extra else "")
    except (ValueError, OSError, AttributeError):
        return "sans détail"


def read_source(path, fmt=None):
    """Lit la liste des machines (CSV ou JSON) et normalise les clés et les espaces."""
    fmt = fmt or ("json" if path.lower().endswith(".json") else "csv")
    with open(path, encoding="utf-8-sig", newline="") as handle:
        if fmt == "json":
            data = json.load(handle)
            rows = data.get("machines", []) if isinstance(data, dict) else data
            if not isinstance(rows, list):
                raise SyncError("JSON attendu : une liste de machines, ou {\"machines\": [...]}")
        else:
            sample = handle.read(4096)
            handle.seek(0)
            dialect = csv.Sniffer().sniff(sample, delimiters=",;") if sample.strip() else csv.excel
            rows = list(csv.DictReader(handle, dialect=dialect))
    machines = []
    for number, row in enumerate(rows, start=2 if fmt == "csv" else 1):
        if not isinstance(row, dict):
            raise SyncError("ligne %d : objet attendu" % number)
        clean = {str(k).strip().lower(): ("" if v is None else str(v).strip()) for k, v in row.items() if k}
        clean["_ligne"] = number
        machines.append(clean)
    return machines


def validate(machine, sites):
    """Liste des problèmes d'une machine de la source (vide si elle est utilisable)."""
    problems = []
    for field in FIELDS:
        if not machine.get(field):
            problems.append("%s manquant" % field)
    for field, limit in LIMITS.items():
        if len(machine.get(field, "")) > limit:
            problems.append("%s trop long (%d caractères au plus)" % (field, limit))
    kind = machine.get("type", "").upper()
    if kind and kind not in TYPES:
        problems.append("type inconnu : %s" % machine.get("type"))
    if machine.get("site") and machine["site"] not in sites:
        problems.append("site inconnu : %s" % machine["site"])
    return problems


def plan(machines, sites, existing):
    """Décide, pour chaque machine, de l'action : créer, modifier, rien, ou erreur.

    existing : équipements actuels de la plateforme. Renvoie une liste de (action, machine, détail).
    """
    by_code = {e["inventoryCode"]: e for e in existing}
    by_address = {e["managementAddress"]: e for e in existing}
    seen = set()
    steps = []
    for machine in machines:
        problems = validate(machine, sites)
        code = machine.get("code", "")
        if code in seen:
            problems.append("code en double dans la source")
        seen.add(code)
        if problems:
            steps.append(("erreur", machine, "; ".join(problems)))
            continue
        wanted = {
            "inventoryCode": code,
            "name": machine["nom"],
            "managementAddress": machine["adresse"],
            "type": machine["type"].upper(),
            "siteId": sites[machine["site"]],
        }
        current = by_code.get(code)
        holder = by_address.get(machine["adresse"])
        if holder is not None and holder["inventoryCode"] != code:
            steps.append(("erreur", machine, "adresse déjà utilisée par l'équipement %s" % holder["inventoryCode"]))
            continue
        if current is None:
            steps.append(("créer", machine, dict(wanted, status="UNKNOWN")))
            continue
        changes = [
            key
            for key in ("name", "managementAddress", "type", "siteId")
            if str(current.get(key)) != str(wanted[key])
        ]
        if not changes:
            steps.append(("rien", machine, current["id"]))
        else:
            # Le statut opérationnel est décidé par la supervision, pas par la CMDB : on le garde.
            steps.append(("modifier", machine, (current["id"], dict(wanted, status=current["status"]), changes)))
    return steps


def synchronize(api, machines, dry_run=False, out=sys.stdout):
    sites = {s["code"]: s["id"] for s in api.pages("/sites")}
    existing = list(api.pages("/equipements"))
    steps = plan(machines, sites, existing)
    counts = {"créer": 0, "modifier": 0, "rien": 0, "erreur": 0}
    prefix = "[simulation] " if dry_run else ""
    for action, machine, detail in steps:
        label = "%s (ligne %s)" % (machine.get("code") or "?", machine.get("_ligne"))
        if action == "erreur":
            counts["erreur"] += 1
            print("ERREUR %s : %s" % (label, detail), file=out)
            continue
        if action == "rien":
            counts["rien"] += 1
            continue
        try:
            if action == "créer":
                if not dry_run:
                    api.call("POST", "/equipements", body=detail)
                print("%scréé    %s" % (prefix, label), file=out)
            else:
                equipment_id, body, changes = detail
                if not dry_run:
                    api.call("PUT", "/equipements/" + urllib.parse.quote(str(equipment_id)), body=body)
                print("%smodifié %s (%s)" % (prefix, label, ", ".join(changes)), file=out)
            counts[action] += 1
        except ApiError as error:
            counts["erreur"] += 1
            print("ERREUR %s : refusé par la plateforme, %s" % (label, error), file=out)
    codes = {m.get("code") for m in machines}
    absent = [e["inventoryCode"] for e in existing if e["inventoryCode"] not in codes]
    print(
        "%sbilan : %d créé(s), %d modifié(s), %d inchangé(s), %d en erreur ; %d équipement(s) de la "
        "plateforme absent(s) de la source (jamais supprimés)"
        % (prefix, counts["créer"], counts["modifier"], counts["rien"], counts["erreur"], len(absent)),
        file=out,
    )
    return counts


def main(argv=None, environ=None, out=sys.stdout):
    environ = os.environ if environ is None else environ
    parser = argparse.ArgumentParser(description="Synchronise l'inventaire Trans-DIC1 avec une CMDB.")
    parser.add_argument("--server", required=True, help="adresse de la plateforme (https://...)")
    parser.add_argument("--source", required=True, help="fichier CSV ou JSON exporté de la CMDB")
    parser.add_argument("--format", choices=("csv", "json"), help="format de la source (défaut : extension)")
    parser.add_argument("--dry-run", action="store_true", help="affiche ce qui serait fait, sans rien écrire")
    parser.add_argument("--ca-file", help="autorité de certification interne")
    parser.add_argument("--allow-insecure-http", action="store_true", help="HTTP en clair (essai local seulement)")
    args = parser.parse_args(argv)

    token = environ.get("TRANS_DIC1_API_TOKEN", "").strip()
    try:
        if not token:
            raise SyncError("TRANS_DIC1_API_TOKEN absent : jeton d'API à définir dans l'environnement")
        if not args.server.startswith("https://") and not (args.allow_insecure_http and args.server.startswith("http://")):
            raise SyncError("--server doit commencer par https:// (le jeton circulerait en clair)")
        machines = read_source(args.source, args.format)
        counts = synchronize(Api(args.server, token, args.ca_file), machines, args.dry_run, out)
    except (SyncError, ApiError, OSError, ValueError, csv.Error) as error:
        print("ERREUR : %s" % error, file=out)
        return 2
    return 1 if counts["erreur"] else 0


if __name__ == "__main__":
    sys.exit(main())
