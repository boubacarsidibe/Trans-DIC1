#!/usr/bin/env python3
"""Génère docs/uml/modele_uml_cible.mdj (projet StarUML) à partir du jeu canonique
Mermaid de docs/architecture/diagrams/ (voir README.md de ce dossier).

Usage : python docs/uml/tools/generate_cible_mdj.py
"""
import json
import math
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "architecture" / "diagrams"
OUT = ROOT / "uml" / "modele_uml_cible.mdj"

FONT = "Arial;13;0"
SUP = {"suppressAttributes": True, "suppressOperations": True, "suppressReceptions": True}


# --------------------------------------------------------------------------- base
class Builder:
    def __init__(self):
        self.n = 0

    def id(self):
        self.n += 1
        return "AAAAAAF%024d" % self.n

    def el(self, typ, parent, **kw):
        e = {"_type": typ, "_id": self.id()}
        if parent is not None:
            e["_parent"] = ref(parent)
        e.update(kw)
        return e


def ref(e):
    return {"$ref": e["_id"]}


B = Builder()


def add(parent, key, child):
    parent.setdefault(key, []).append(child)
    return child


def view(typ, diagram, model, x, y, w, h, **kw):
    v = B.el(typ, diagram, font=FONT, left=x, top=y, width=w, height=h)
    if model is not None:
        v["model"] = ref(model)
    v.update(kw)
    diagram.setdefault("ownedViews", []).append(v)
    return v


def rect(v):
    return (v["left"], v["top"], v["width"], v["height"])


def center(v):
    x, y, w, h = rect(v)
    return x + w / 2, y + h / 2


def clip(v, tx, ty, ellipse=False):
    """Point du bord de la vue v dans la direction (tx, ty)."""
    x, y, w, h = rect(v)
    cx, cy = x + w / 2, y + h / 2
    dx, dy = tx - cx, ty - cy
    if dx == 0 and dy == 0:
        return cx, cy
    if ellipse:
        t = 1 / math.sqrt((dx / (w / 2)) ** 2 + (dy / (h / 2)) ** 2)
    else:
        t = min((w / 2) / abs(dx) if dx else 1e9, (h / 2) / abs(dy) if dy else 1e9)
    return cx + dx * t, cy + dy * t


def edge(a, b, ell_a=False, ell_b=False):
    ca, cb = center(a), center(b)
    p1 = clip(a, cb[0], cb[1], ell_a)
    p2 = clip(b, ca[0], ca[1], ell_b)
    return "%d:%d;%d:%d" % (p1[0], p1[1], p2[0], p2[1])


def edge_offset(a, b, k):
    """Comme edge() mais décalé de k pixels perpendiculairement (arêtes parallèles)."""
    (ax, ay), (bx, by) = center(a), center(b)
    dx, dy = bx - ax, by - ay
    n = math.hypot(dx, dy) or 1
    ox, oy = -dy / n * k, dx / n * k
    p1 = clip(a, bx + ox, by + oy)
    p2 = clip(b, ax + ox, ay + oy)
    return "%d:%d;%d:%d" % (p1[0] + ox, p1[1] + oy, p2[0] + ox, p2[1] + oy)


def edge_view(typ, diagram, model, a, b, ell_a=False, ell_b=False, points=None, **kw):
    v = B.el(typ, diagram, model=ref(model), tail=ref(a), head=ref(b), lineStyle=1,
             points=points or edge(a, b, ell_a, ell_b))
    v.update(kw)
    diagram.setdefault("ownedViews", []).append(v)
    return v


def note(diagram, text, x, y, w, h, target_view=None):
    nv = B.el("UMLNoteView", diagram, font=FONT, left=x, top=y, width=w, height=h, text=text)
    diagram.setdefault("ownedViews", []).append(nv)
    if target_view is not None:
        lv = B.el("UMLNoteLinkView", diagram, tail=ref(nv), head=ref(target_view), lineStyle=1,
                  points=edge(nv, target_view))
        diagram["ownedViews"].append(lv)
    return nv


def package(parent, name):
    return add(parent, "ownedElements", B.el("UMLPackage", parent, name=name))


# ---------------------------------------------------------------------- 2. classes
NS_TITLES = {
    "IdentiteEtAcces": "Identité et accès",
    "InventaireEtCollecte": "Inventaire et collecte",
    "AlertesEtMaintenance": "Alertes et maintenance",
    "NotificationsEtRapports": "Notifications et rapports",
    "Audit": "Audit",
}


def build_classes(pkg):
    txt = (SRC / "02-domain/persistence-model-uml.mmd").read_text(encoding="utf8")
    order, ns = [], {}
    for m in re.finditer(r"namespace (\w+) \{(.*?)\n\}\n", txt, re.S):
        order.append(m.group(1))
        for c in re.finditer(r"class (\w+) \{(.*?)\n  \}", m.group(2), re.S):
            body = c.group(2)
            ster = re.search(r"<<(\w+)>>", body)
            attrs = re.findall(r"^\s*([-+#])(\w+): (.+)$", body, re.M)
            ns.setdefault(m.group(1), []).append((c.group(1), ster.group(1) if ster else None, attrs))
    rels = re.findall(r'^(\w+) "([^"]+)" (\*--|--) "([^"]+)" (\w+) : (\w+)', txt, re.M)
    notes = re.findall(r'^note for (\w+) "(.+)"', txt, re.M)

    root = package(pkg, "Vue statique (classes persistantes)")
    sub, cls_m = {}, {}
    for n in order:
        sub[n] = package(root, NS_TITLES[n])
        for name, ster, attrs in ns[n]:
            c = add(sub[n], "ownedElements", B.el("UMLClass", sub[n], name=name))
            if ster:
                c["stereotype"] = ster
            for vis, an, at in attrs:
                add(c, "attributes", B.el("UMLAttribute", c, name=an, type=at.strip(),
                                          visibility={"-": "private", "+": "public", "#": "protected"}[vis]))
            cls_m[name] = c
    assoc = []
    for a, ma, op, mb, b, verb in rels:
        as_ = add(root, "ownedElements", B.el("UMLAssociation", root, name=verb))
        as_["end1"] = B.el("UMLAssociationEnd", as_, reference=ref(cls_m[a]), multiplicity=ma,
                           aggregation="composite" if op == "*--" else "none", navigable="unspecified")
        as_["end2"] = B.el("UMLAssociationEnd", as_, reference=ref(cls_m[b]), multiplicity=mb,
                           aggregation="none", navigable="unspecified")
        assoc.append((a, b, as_))

    cls_kw = dict(containerChangeable=True, stereotypeDisplay="label", showVisibility=True,
                  showProperty=True, showType=True, showOperationSignature=True)
    for i, n in enumerate(order):
        d = add(sub[n], "ownedElements", B.el("UMLClassDiagram", sub[n],
                                               name="CD-C0%d %s" % (i + 1, NS_TITLES[n])))
        local = [c[0] for c in ns[n]]
        foreign = []
        for a, b, _ in assoc:
            if a in local and b not in local and b not in foreign:
                foreign.append(b)
            if b in local and a not in local and a not in foreign:
                foreign.append(a)
        views = {}
        colh = [40, 40, 40]
        for name in local:
            h = 55 + 18 * len(cls_m[name].get("attributes", []))
            ci = colh.index(min(colh))
            views[name] = view("UMLClassView", d, cls_m[name], 40 + ci * 330, colh[ci], 250, h,
                               suppressAttributes=False, suppressOperations=True, **cls_kw)
            colh[ci] += h + 50
        top = 40
        for name in foreign:
            views[name] = view("UMLClassView", d, cls_m[name], 1080, top, 230, 50,
                               suppressAttributes=True, suppressOperations=True, **cls_kw)
            top += 100
        for a, b, as_ in assoc:
            if a in views and b in views and (a in local or b in local):
                edge_view("UMLAssociationView", d, as_, views[a], views[b])
        ny = max(colh) + 20
        for cname, text in notes:
            if cname in local:
                note(d, text, 40, ny, 700, 60, views[cname])
                ny += 90
    return root


# --------------------------------------------------------------------- 3. séquences
def parse_sequence(path):
    parts, items, stack = [], [], []

    def sink():
        return stack[-1]["ops"][-1]["items"] if stack else items

    for raw in (SRC / path).read_text(encoding="utf8").splitlines():
        s = raw.strip()
        if not s or s in ("sequenceDiagram", "autonumber"):
            continue
        m = re.match(r"(participant|actor) (\w+) as (.+)", s)
        if m:
            parts.append((m.group(2), m.group(3), m.group(1)))
            continue
        m = re.match(r"Note over (\w+)(?:,(\w+))?: (.*)", s)
        if m:
            sink().append(("note", m.group(1), m.group(2) or m.group(1), m.group(3)))
            continue
        m = re.match(r"(loop|alt|opt)\b ?(.*)", s)
        if m:
            f = {"kind": m.group(1), "ops": [{"guard": m.group(2), "items": []}]}
            sink().append(("frag", f))
            stack.append(f)
            continue
        m = re.match(r"else\b ?(.*)", s)
        if m:
            stack[-1]["ops"].append({"guard": m.group(1), "items": []})
            continue
        if s == "end":
            stack.pop()
            continue
        m = re.match(r"(\w+)(-->>|->>)(\w+): (.*)", s)
        if m:
            sink().append(("msg", m.group(1), m.group(3), m.group(4), m.group(2) == "-->>"))
    return parts, items


def build_sequence(pkg, path, title):
    parts, items = parse_sequence(path)
    col = add(pkg, "ownedElements", B.el("UMLCollaboration", pkg, name=title))
    inter = add(col, "ownedElements", B.el("UMLInteraction", col, name=title))
    d = add(inter, "ownedElements", B.el("UMLSequenceDiagram", inter, name=title,
                                         showActivation=False, showSequenceNumber=True))
    SP, W, STEP, TOP0 = 200, 160, 40, 110
    life_m, xs = {}, {}
    for i, (pid, label, kind) in enumerate(parts):
        life_m[pid] = add(inter, "participants", B.el("UMLLifeline", inter, name=label))
        if kind == "actor":
            life_m[pid]["stereotype"] = "actor"
        xs[pid] = 60 + i * SP + W // 2
    st = {"slot": 0, "msgs": [], "frags": [], "notes": [], "n": 0}

    def walk(its, depth):
        for it in its:
            if it[0] == "msg":
                _, a, b, text, reply = it
                st["msgs"].append((a, b, text, reply, TOP0 + st["slot"] * STEP))
                st["slot"] += 2 if a == b else 1
                continue
            if it[0] == "note":
                st["notes"].append((it[1], it[2], it[3], TOP0 + st["slot"] * STEP - 12))
                st["slot"] += 2
                continue
            f = it[1]
            top = TOP0 + st["slot"] * STEP - 14
            st["slot"] += 1
            st["n"] += 1
            fm = add(inter, "fragments", B.el("UMLCombinedFragment", inter,
                                              name="%s-%d" % (f["kind"], st["n"]),
                                              interactionOperator=f["kind"]))
            bounds = []
            for op in f["ops"]:
                om = add(fm, "operands", B.el("UMLInteractionOperand", fm, name=op["guard"], guard=op["guard"]))
                start = TOP0 + st["slot"] * STEP - 32  # séparateur à mi-chemin du message précédent
                walk(op["items"], depth + 1)
                st["slot"] += 1
                bounds.append((om, op["guard"], start))
            st["frags"].append((fm, depth, top, TOP0 + st["slot"] * STEP - 24, bounds, f["kind"]))

    walk(items, 0)
    total = TOP0 + st["slot"] * STEP + 40
    lps = {}
    for i, (pid, _, _) in enumerate(parts):
        lv = view("UMLSeqLifelineView", d, life_m[pid], 60 + i * SP, 40, W, total - 40)
        lp = B.el("UMLLinePartView", lv, model=ref(life_m[pid]), font=FONT,
                  left=xs[pid], top=75, width=1, height=total - 75)
        lv["subViews"] = [lp]
        lv["linePart"] = ref(lp)
        lps[pid] = lp
    right = 60 + (len(parts) - 1) * SP + W
    for fm, depth, top, bottom, bounds, kind in sorted(st["frags"], key=lambda t: t[1]):
        fx, fw = 30 + depth * 12, right - 2 * depth * 12
        top, bottom = int(top), int(bottom)
        fv = view("UMLCombinedFragmentView", d, fm, fx, top, fw, bottom - top, parentStyle=False)
        # structure attendue par StarUML : étiquettes + compartiment d'opérandes à hauteurs explicites,
        # sinon il recrée des opérandes de hauteur minimale et les séparateurs tombent au mauvais endroit
        name_l = B.el("LabelView", fv, font=FONT, parentStyle=True, left=fx + 34, top=top + 5, width=60,
                      height=13, text=fm["name"])
        kind_l = B.el("LabelView", fv, font="Arial;13;1", parentStyle=True, left=fx + 5, top=top + 5, width=25,
                      height=13, text=kind)
        ctop = top + 23
        comp = B.el("UMLInteractionOperandCompartmentView", fv, model=ref(fm), font=FONT, parentStyle=True,
                    left=fx, top=ctop, width=fw, height=bottom - ctop)
        edges_y = [ctop] + [int(b[2]) for b in bounds[1:]] + [bottom]
        comp["subViews"] = []
        for i, (om, guard, _) in enumerate(bounds):
            ov = B.el("UMLInteractionOperandView", comp, model=ref(om), font=FONT, parentStyle=True,
                      left=fx, top=edges_y[i], width=fw, height=edges_y[i + 1] - edges_y[i])
            gl = B.el("LabelView", ov, font=FONT, parentStyle=True, left=fx + 20, top=edges_y[i] + 8,
                      width=200, height=13, text="[%s]" % guard if guard else "")
            ov["subViews"] = [gl]
            ov["guardLabel"] = ref(gl)
            comp["subViews"].append(ov)
        fv["subViews"] = [name_l, kind_l, comp]
        fv["nameLabel"], fv["frameTypeLabel"], fv["operandCompartment"] = ref(name_l), ref(kind_l), ref(comp)
    for a, b, text, y in st["notes"]:
        x0, x1 = min(xs[a], xs[b]) - 80, max(xs[a], xs[b]) + 80
        note(d, text, x0, y, x1 - x0, 40)
    for a, b, text, reply, y in st["msgs"]:
        m = add(inter, "messages", B.el("UMLMessage", inter, name=text, source=ref(life_m[a]),
                                        target=ref(life_m[b]),
                                        messageSort="reply" if reply else "synchCall"))
        if a == b:
            x = xs[a]
            pts = "%d:%d;%d:%d;%d:%d;%d:%d" % (x, y, x + 45, y, x + 45, y + 22, x, y + 22)
        else:
            pts = "%d:%d;%d:%d" % (xs[a], y, xs[b], y)
        d["ownedViews"].append(B.el("UMLSeqMessageView", d, model=ref(m), tail=ref(lps[a]),
                                    head=ref(lps[b]), font=FONT, points=pts))
    return d


# ------------------------------------------------------------------------- 5. états
STATE_POS = {
    "OUVERTE": (300, 130), "PRISE_EN_CHARGE": (780, 130), "RESOLUE": (780, 420),
    "ARCHIVEE": (780, 700), "SUPPRIMEE": (300, 700),
}


def build_states(pkg):
    txt = (SRC / "05-states/alert-lifecycle.mmd").read_text(encoding="utf8")
    sm = add(pkg, "ownedElements", B.el("UMLStateMachine", pkg, name="Cycle de vie d'une alerte"))
    reg = add(sm, "regions", B.el("UMLRegion", sm, name="Region1"))
    d = add(sm, "ownedElements", B.el("UMLStatechartDiagram", sm, name="SM-C01 Cycle de vie d'une alerte"))
    states, views = {}, {}
    states["INIT"] = add(reg, "vertices", B.el("UMLPseudostate", reg, name="", kind="initial"))
    views["INIT"] = view("UMLPseudostateView", d, states["INIT"], 120, 160, 20, 20)
    states["FINAL"] = add(reg, "vertices", B.el("UMLFinalState", reg, name=""))
    views["FINAL"] = view("UMLFinalStateView", d, states["FINAL"], 100, 760, 30, 30)
    for k, (x, y) in STATE_POS.items():
        states[k] = add(reg, "vertices", B.el("UMLState", reg, name=k))
        views[k] = view("UMLStateView", d, states[k], x, y, 190, 70, containerChangeable=True)
    trs = re.findall(r"^\s*(\[\*\]|\w+) --> (\[\*\]|\w+)(?: : (.+))?$", txt, re.M)
    seen = {}
    for s, t, label in trs:
        a = "INIT" if s == "[*]" else s
        b = "FINAL" if t == "[*]" else t
        tr = add(reg, "transitions", B.el("UMLTransition", reg, name="", source=ref(states[a]),
                                          target=ref(states[b])))
        if label:
            tr["triggers"] = [B.el("UMLEvent", tr, name=label, kind="signal")]
        pts = None
        pair = frozenset((a, b))
        seen[pair] = seen.get(pair, 0) + 1
        total = sum(1 for x, y, _ in trs if frozenset(((x if x != "[*]" else "INIT"), (y if y != "[*]" else "FINAL"))) == pair)
        if a != b and total > 1:
            k = (seen[pair] - 1 - (total - 1) / 2) * 26
            if a > b:
                k = -k
            pts = edge_offset(views[a], views[b], k)
        if a == b:
            x, y, w, h = rect(views[a])
            pts = "%d:%d;%d:%d;%d:%d;%d:%d" % (x + w, y + 20, x + w + 40, y + 20,
                                               x + w + 40, y + 50, x + w, y + 50)
        edge_view("UMLTransitionView", d, tr, views[a], views[b], points=pts)
    note(d, "Sévérité indépendante de l'état : INFO, AVERTISSEMENT ou CRITIQUE.",
         1080, 90, 260, 70, views["OUVERTE"])
    note(d, "Résolution manuelle : NOC ou Administrateur. Résolution automatique : si elle est "
            "activée par l'Administrateur.", 1080, 380, 280, 100, views["RESOLUE"])
    return d


# ------------------------------------------------------------------------ 6. composants
COMP_POS = {
    "API": (300, 80), "AUTH": (300, 220), "INVENTORY": (300, 330), "CONFIG": (300, 440),
    "MONITORING": (620, 440), "ALERTS": (620, 600), "REPORTS": (300, 600), "NOTIFICATIONS": (620, 760),
    "AUDIT": (300, 760), "SCHEDULER": (940, 80), "COLLECTOR": (940, 250),
    "SNMP_ADAPTER": (940, 440), "AGENT_API": (940, 590),
    "WEB": (20, 80), "AGENT": (1340, 590), "SNMP_DEVICE": (1340, 440), "SMTP": (1340, 760),
    "DATABASE": (20, 440), "FILES": (20, 640),
}
INTERNAL = {"API", "AUTH", "INVENTORY", "CONFIG", "MONITORING", "ALERTS", "REPORTS", "NOTIFICATIONS",
            "AUDIT", "SCHEDULER", "COLLECTOR", "SNMP_ADAPTER", "AGENT_API"}


def build_components(pkg):
    txt = (SRC / "06-components/target-components.mmd").read_text(encoding="utf8")
    p = package(pkg, "Vue des composants")
    d = add(p, "ownedElements", B.el("UMLComponentDiagram", p,
                                      name="CP-C01 Composants — Trans-DIC1 (cible)"))
    nodes = {}
    for m in re.finditer(r'(\w+)(?:\["«([^»]+)»<br/>([^"]+)"\]|\(\["«([^»]+)»<br/>([^"]+)"\]\)'
                         r'|\[\("«([^»]+)»<br/>([^"]+)"\)\])', txt):
        nodes[m.group(1)] = (m.group(3) or m.group(5) or m.group(7), m.group(2) or m.group(4) or m.group(6))
    plat = add(p, "ownedElements", B.el("UMLComponent", p, name="Plateforme centrale Trans-DIC1",
                                         stereotype="subsystem"))
    view("UMLComponentView", d, plat, 260, 20, 1020, 900, **SUP)
    comps, views = {}, {}
    for k, (name, ster) in nodes.items():
        owner = plat if k in INTERNAL else p
        comps[k] = add(owner, "ownedElements", B.el("UMLComponent", owner, name=name, stereotype=ster))
        x, y = COMP_POS[k]
        views[k] = view("UMLComponentView", d, comps[k], x, y, 230, 70, **SUP)
    for a, op, b in re.findall(r"^\s*(\w+) (-->|<-->) (\w+)\s*$", txt, re.M):
        if op == "<-->":
            as_ = add(p, "ownedElements", B.el("UMLAssociation", p, name=""))
            as_["end1"] = B.el("UMLAssociationEnd", as_, reference=ref(comps[a]), navigable="unspecified")
            as_["end2"] = B.el("UMLAssociationEnd", as_, reference=ref(comps[b]), navigable="unspecified")
            edge_view("UMLAssociationView", d, as_, views[a], views[b])
        else:
            dep = add(comps[a], "ownedElements", B.el("UMLDependency", comps[a], name="",
                                                       source=ref(comps[a]), target=ref(comps[b])))
            edge_view("UMLDependencyView", d, dep, views[a], views[b])
    return d


# ---------------------------------------------------------------------- 7. déploiement
def build_deployment(pkg):
    p = package(pkg, "Vue de déploiement")
    d = add(p, "ownedElements", B.el("UMLDeploymentDiagram", p,
                                      name="DP-C01 Déploiement cible — Trans-DIC1"))
    nodes, views = {}, {}

    def node(key, name, ster, x, y, w, h):
        nodes[key] = add(p, "ownedElements", B.el("UMLNode", p, name=name, stereotype=ster))
        views[key] = view("UMLNodeView", d, nodes[key], x, y, w, h, **SUP)

    def art(key, name, ster, x, y, w=210, h=50):
        nodes[key] = add(p, "ownedElements", B.el("UMLArtifact", p, name=name))
        if ster != "artifact":
            nodes[key]["stereotype"] = ster
        views[key] = view("UMLArtifactView", d, nodes[key], x, y, w, h, **SUP)

    node("CLIENT", "Poste Administrateur ou NOC", "device", 20, 40, 300, 160)
    node("CENTRAL", "Serveur central Trans-DIC1", "node", 420, 40, 560, 330)
    node("DATA", "Serveur de données", "node", 420, 470, 560, 170)
    node("SITE", "Site supervisé 1..*", "node", 1130, 40, 380, 560)
    node("HOST", "Serveur compatible", "device", 1160, 100, 320, 160)
    node("SMTP", "Serveur e-mail", "node externe", 1130, 700, 380, 100)
    node("BROWSER", "Navigateur Web", "executionEnvironment", 50, 95, 240, 80)
    art("FRONTEND", "Application Web", "artifact", 450, 100)
    art("API", "API Backend", "artifact", 740, 100)
    art("WORKER", "Workers de collecte", "artifact", 740, 270)
    art("SCHEDULER", "Planificateur", "artifact", 450, 270)
    art("DATABASE", "Base de données", "database", 450, 540)
    art("REPORT_STORAGE", "Rapports générés", "storage", 740, 540)
    art("AGENT", "Agent Trans-DIC1", "artifact", 1215, 175)
    node("SNMP_DEVICE", "Équipement réseau SNMP", "device", 1190, 350, 260, 90)
    conns = [("BROWSER", "FRONTEND", "HTTPS"), ("FRONTEND", "API", "HTTPS / API REST"),
             ("API", "DATABASE", "TLS"), ("API", "REPORT_STORAGE", ""),
             ("SCHEDULER", "WORKER", ""), ("WORKER", "DATABASE", "TLS"),
             ("WORKER", "AGENT", "HTTPS ou mTLS"), ("WORKER", "SNMP_DEVICE", "SNMPv3"),
             ("AGENT", "API", "Métriques et heartbeat"), ("API", "SMTP", "SMTP/TLS")]
    for a, b, label in conns:
        as_ = add(p, "ownedElements", B.el("UMLAssociation", p, name=label))
        as_["end1"] = B.el("UMLAssociationEnd", as_, reference=ref(nodes[a]), navigable="unspecified")
        as_["end2"] = B.el("UMLAssociationEnd", as_, reference=ref(nodes[b]), navigable="unspecified")
        edge_view("UMLAssociationView", d, as_, views[a], views[b])
    return d


# --------------------------------------------------------------------------- 8. paquets
def build_packages(pkg):
    txt = (SRC / "10-packages/packages.mmd").read_text(encoding="utf8")
    root = package(pkg, "Vue des paquets")
    d = add(root, "ownedElements", B.el("UMLPackageDiagram", root,
                                         name="PK-C01 Dépendances entre paquets"))
    pos = {"PRESENTATION": (60, 40), "IDENTITY": (600, 40), "INVENTORY": (60, 230), "ALERTS": (60, 420),
           "REPORTS": (60, 610), "AUDIT": (600, 610), "INFRA": (1000, 330)}
    pk, pv = {}, {}
    for m in re.finditer(r'(\w+)\["«package»<br/>([^"]+)"\]', txt):
        k = m.group(1)
        pk[k] = add(root, "ownedElements", B.el("UMLPackage", root, name=m.group(2)))
        pv[k] = view("UMLPackageView", d, pk[k], pos[k][0], pos[k][1], 220, 90)
    for a, ster, b in re.findall(r'^\s*(\w+) -\. "«(\w+)»" \.-> (\w+)', txt, re.M):
        dep = add(root, "ownedElements", B.el("UMLDependency", root, name="", source=ref(pk[a]),
                                               target=ref(pk[b]), stereotype=ster))
        edge_view("UMLDependencyView", d, dep, pv[a], pv[b])
    return d


# ---------------------------------------------------------------------------- main
VARIANTES = [  # (dossier, constructeur, source Mermaid, identifiant, titre, options)
    ("Cas d'utilisation", "uc", "01-use-cases/global-use-cases.mmd", "UC-V01",
     "Cas d'utilisation global (proposition initiale)", {"secondary": {"source", "smtp"}}),
    ("Cas d'utilisation", "uc", "01-use-cases/validated-global-use-cases.mmd", "UC-V02",
     "Cas d'utilisation global validé", {}),
    ("Cas d'utilisation", "uc", "01-use-cases/complete-global-use-cases.mmd", "UC-V03",
     "Cas d'utilisation global complet", {}),
    ("Cas d'utilisation", "uc", "01-use-cases/complete-reader-use-cases.mmd", "UC-V04",
     "Lecteur — consultation", {}),
    ("Cas d'utilisation", "uc", "01-use-cases/monitoring-use-cases.mmd", "UC-V05",
     "Opérateur NOC — supervision et incidents", {}),
    ("Cas d'utilisation", "uc", "01-use-cases/complete-noc-use-cases.mmd", "UC-V06",
     "Opérateur NOC — cas complets", {}),
    ("Cas d'utilisation", "uc", "01-use-cases/administration-use-cases.mmd", "UC-V07",
     "Administrateur — administration globale", {}),
    ("Cas d'utilisation", "uc", "01-use-cases/complete-admin-use-cases.mmd", "UC-V08",
     "Administrateur — cas complets", {}),
    ("Classes", "class", "02-domain/domain-overview.mmd", "CD-V01", "Modèle conceptuel métier", {}),
    ("Classes", "class", "02-domain/design-model.mmd", "CD-V02", "Modèle de conception (ports et adaptateurs)", {}),
    ("Séquences", "seq", "03-sequences/collect-measure-and-open-alert.mmd", "SD-V01",
     "Collecter une mesure et ouvrir une alerte", {}),
    ("Séquences", "seq", "03-sequences/acknowledge-and-resolve-alert.mmd", "SD-V02",
     "Acquitter et résoudre une alerte", {}),
    ("Activités", "act", "04-activities/incident-lifecycle.mmd", "AD-V01", "Cycle de vie d'un incident", {}),
    ("États", "state", "05-states/alert-state.mmd", "SM-V01", "États d'une alerte (proposition initiale)", {}),
    ("Composants", "comp", "06-components/components.mmd", "CP-V01", "Composants (proposition initiale)", {}),
    ("Déploiement", "deploy", "07-deployment/deployment-current.mmd", "DP-V01", "Déploiement actuel (développement)", {}),
    ("Déploiement", "deploy", "07-deployment/deployment-target.mmd", "DP-V02", "Déploiement cible (proposition initiale)", {}),
    ("C4", "c4", "09-c4/context.mmd", "C4-01", "Contexte système", {}),
    ("C4", "c4", "09-c4/containers.mmd", "C4-02", "Conteneurs", {}),
]


def main():
    sys.modules.setdefault("generate_cible_mdj", sys.modules[__name__])  # un seul compteur d'identifiants
    import mdj_extra as X

    proj = B.el("Project", None, name="Trans-DIC1 — modèles UML")
    model = add(proj, "ownedElements", B.el("UMLModel", proj, name="Trans-DIC1 — architecture cible"))
    canon = package(model, "1. Jeu canonique (validé)")
    X.build_uc_generic(package(canon, "Vue des cas d'utilisation"), "01-use-cases/trans-dic1-use-cases.mmd",
                       "UC-C01", "Cas d'utilisation — Trans-DIC1 (cible)", secondary={"EMAIL"})
    build_classes(canon)
    seq = package(canon, "Vue dynamique (séquences)")
    build_sequence(seq, "03-sequences/collection-and-alert.mmd",
                   "SD-C01 Collecte configurable et évaluation des alertes")
    build_sequence(seq, "03-sequences/alert-handling.mmd",
                   "SD-C02 Prise en charge, assignation et résolution d'une alerte")
    dyn = package(canon, "Vue dynamique (activités et états)")
    X.build_activity_generic(dyn, "04-activities/metric-collection.mmd", "AD-C01",
                             "Collecte planifiée des métriques")
    build_states(dyn)
    build_components(canon)
    build_deployment(canon)
    build_packages(canon)

    var = package(model, "2. Variantes antérieures (traçabilité)")
    folders = {}
    for folder, kind, path, did, title, opts in VARIANTES:
        f = folders.get(folder) or folders.setdefault(folder, package(var, folder))
        if kind == "uc":
            X.build_uc_generic(f, path, did, title, **opts)
        elif kind == "class":
            X.build_classes_generic(f, path, did, title)
        elif kind == "seq":
            build_sequence(f, path, "%s %s" % (did, title))
        elif kind == "act":
            X.build_activity_generic(f, path, did, title)
        elif kind == "state":
            X.build_states_generic(f, path, did, title)
        elif kind == "comp":
            X.build_components_generic(f, path, did, title)
        elif kind == "deploy":
            X.build_deployment_beta(f, path, did, title)
        elif kind == "c4":
            X.build_c4(f, path, did, title)
    X.build_erd(proj, "08-data/erd-target.mmd", "ER-01", "Modèle de données cible")

    first = [e for e in canon["ownedElements"][0]["ownedElements"][0]["ownedElements"]
             if e["_type"] == "UMLUseCaseDiagram"]
    if first:
        first[0]["defaultDiagram"] = True
    OUT.write_text(json.dumps(proj, ensure_ascii=False, indent=2), encoding="utf8")
    print("écrit", OUT, "-", B.n, "objets")


if __name__ == "__main__":
    main()
