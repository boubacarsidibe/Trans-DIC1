"""Constructeurs génériques (flowchart Mermaid, cas d'utilisation, classes, états, activités,
ERD, C4, déploiement) utilisés par generate_cible_mdj.py."""
import math
import re

from generate_cible_mdj import (B, SUP, SRC, add, view, ref, rect, edge_view, edge_offset, note, package)


# ------------------------------------------------------------------ parseur flowchart
def _clean(label):
    label = label.strip().strip('"')
    label = label.replace("<br/>", " ").replace("\\n", " ")
    return re.sub(r"\s+", " ", label).strip()


def _parse_node(s, i):
    m = re.match(r"\w+", s[i:])
    if not m:
        return None
    nid = m.group(0)
    j = i + len(nid)
    rest = s[j:]
    kind, label, end = "plain", None, j
    if rest.startswith("(("):
        k = rest.index("))")
        kind, label, end = "circle", rest[2:k], j + k + 2
    elif rest.startswith("(["):
        k = rest.index("])")
        kind, label, end = "stadium", rest[2:k], j + k + 2
    elif rest.startswith("[("):
        k = rest.index(")]")
        kind, label, end = "db", rest[2:k], j + k + 2
    elif rest.startswith('["'):
        k = rest.index('"]')
        kind, label, end = "rect", rest[2:k], j + k + 2
    elif rest.startswith("["):
        k = rest.index("]")
        kind, label, end = "rect", rest[1:k], j + k + 1
    elif rest.startswith("{"):
        k = rest.index("}")
        kind, label, end = "decision", rest[1:k], j + k + 1
    return nid, kind, (_clean(label) if label is not None else None), end


EDGE_PATTERNS = [
    (re.compile(r"\s*<-->\s*"), lambda m: ("", True, False)),
    (re.compile(r'\s*-\.\s*"([^"]*)"\s*\.->\s*'), lambda m: (m.group(1), False, True)),
    (re.compile(r"\s*-\.->\s*"), lambda m: ("", False, True)),
    (re.compile(r"\s*-->\|([^|]*)\|\s*"), lambda m: (m.group(1), False, False)),
    (re.compile(r"\s*--\s+(.+?)\s+-->\s*"), lambda m: (m.group(1), False, False)),
    (re.compile(r"\s*-->\s*"), lambda m: ("", False, False)),
    (re.compile(r"\s*---\s*"), lambda m: ("", False, False)),
]


def parse_flow(text):
    nodes, edges, subs, stack = {}, [], [], []

    def reg(nid, kind, label):
        n = nodes.setdefault(nid, {"id": nid, "kind": "plain", "label": nid, "subs": []})
        if label is not None:
            n["kind"], n["label"] = kind, label
            if not n["subs"]:
                n["subs"] = [x["id"] for x in stack]
        return n

    for raw in text.splitlines():
        s = raw.strip()
        if not s or s.startswith(("flowchart", "direction", "classDef", "%%")):
            continue
        m = re.match(r'subgraph\s+(\w+)(?:\["?(.*?)"?\])?\s*$', s)
        if m:
            sg = {"id": m.group(1), "title": _clean(m.group(2) or m.group(1))}
            subs.append(sg)
            stack.append(sg)
            continue
        if s == "end":
            stack.pop()
            continue
        r = _parse_node(s, 0)
        if not r:
            continue
        nid, kind, label, pos = r
        reg(nid, kind, label)
        prev = nid
        while pos < len(s):
            for pat, fn in EDGE_PATTERNS:
                m = pat.match(s, pos)
                if m:
                    lab, bidir, dotted = fn(m)
                    pos = m.end()
                    break
            else:
                break
            nid2, kind2, label2, pos = _parse_node(s, pos)
            reg(nid2, kind2, label2)
            edges.append({"a": prev, "b": nid2, "label": _clean(lab), "bidir": bidir, "dotted": dotted})
            prev = nid2
    return nodes, edges, subs


# ------------------------------------------------------------------ mise en page par couches
def layered(ids, edges, size, horizontal=False, gap_rank=80, gap_node=36, passes=6):
    """Mise en page de type Sugiyama simplifiée. Renvoie (positions, rangs, arcs retour)."""
    idset = set(ids)
    adj = {i: [] for i in ids}
    for a, b in edges:
        if a in idset and b in idset and a != b and b not in adj[a]:
            adj[a].append(b)
    state, back = {}, set()
    for root in ids:
        if root in state:
            continue
        stack = [(root, iter(adj[root]))]
        state[root] = 1
        while stack:
            u, it = stack[-1]
            for v in it:
                if state.get(v) == 1:
                    back.add((u, v))
                elif v not in state:
                    state[v] = 1
                    stack.append((v, iter(adj[v])))
                    break
            else:
                state[u] = 2
                stack.pop()
    dag = {i: [v for v in adj[i] if (i, v) not in back] for i in ids}
    indeg = {i: 0 for i in ids}
    for u in ids:
        for v in dag[u]:
            indeg[v] += 1
    rank = {i: 0 for i in ids}
    queue = [i for i in ids if indeg[i] == 0]
    while queue:
        u = queue.pop(0)
        for v in dag[u]:
            rank[v] = max(rank[v], rank[u] + 1)
            indeg[v] -= 1
            if indeg[v] == 0:
                queue.append(v)
    nr = max(rank.values()) + 1 if ids else 0
    layers = [[i for i in ids if rank[i] == r] for r in range(nr)]
    preds = {i: [] for i in ids}
    for u in ids:
        for v in dag[u]:
            preds[v].append(u)
    idx = {}

    def reindex():
        for L in layers:
            for k, i in enumerate(L):
                idx[i] = k

    reindex()
    for _ in range(passes):
        for r in range(1, nr):
            layers[r].sort(key=lambda i: (sum(idx[x] for x in preds[i]) / len(preds[i])) if preds[i] else idx[i])
            reindex()
        for r in range(nr - 2, -1, -1):
            layers[r].sort(key=lambda i: (sum(idx[x] for x in dag[i]) / len(dag[i])) if dag[i] else idx[i])
            reindex()
    ax = 0 if horizontal else 1
    cross_ext = [sum(size[i][1 - ax] for i in L) + gap_node * max(0, len(L) - 1) for L in layers]
    widest = max(cross_ext) if cross_ext else 0
    pos, off = {}, 0
    for r, L in enumerate(layers):
        rank_ext = max(size[i][ax] for i in L)
        c = (widest - cross_ext[r]) / 2
        for i in L:
            pos[i] = (off, c) if horizontal else (c, off)
            c += size[i][1 - ax] + gap_node
        off += rank_ext + gap_rank
    return pos, rank, back


# ------------------------------------------------------------------ cas d'utilisation
def _actor_info(label):
    st = None
    m = re.match(r"^«([^»]+)»\s*(.*)$", label)
    if m:
        st, label = m.group(1), m.group(2)
    if label.startswith("Acteur : "):
        label = label[len("Acteur : "):]
    if label.startswith("Système externe : "):
        st, label = "système externe", label[len("Système externe : "):]
    return label, (None if st == "actor" else st)


def build_uc_generic(pkg, path, did, title, secondary=()):
    """Acteurs principaux à gauche (association simple), acteurs secondaires à droite
    (association orientée du cas vers l'acteur), généralisation, «include», «extend»."""
    nodes, edges, subs = parse_flow((SRC / path).read_text(encoding="utf8"))
    actors = [n for n in nodes.values() if n["kind"] == "rect"]
    ucs = [n for n in nodes.values() if n["kind"] == "stadium"]
    uc_ids = {n["id"] for n in ucs}
    act_ids = {n["id"] for n in actors}
    links, rels = [], []
    for e in edges:
        a, b = e["a"], e["b"]
        if e["dotted"] and e["label"]:
            rels.append(e)
        elif a in act_ids and b in uc_ids:
            links.append((a, b, b in uc_ids and a in secondary))
        elif a in uc_ids and b in act_ids:
            links.append((b, a, True))
    sec = set(secondary) | {a for a, _, rev in links if rev}
    links = [(a, u, a in sec) for a, u, _ in links]
    primary = [n["id"] for n in actors if n["id"] not in sec]

    # regroupement des cas par acteur principal, puis par relations include/extend
    group = {}
    for a in primary:
        for x, u, _ in links:
            if x == a and u not in group:
                group[u] = a
    for _ in range(5):
        for e in rels:
            if e["a"] in uc_ids and e["b"] in uc_ids:
                if e["a"] in group and e["b"] not in group:
                    group[e["b"]] = group[e["a"]]
                elif e["b"] in group and e["a"] not in group:
                    group[e["a"]] = group[e["b"]]
    order = []
    for a in primary + [None]:
        order += [n["id"] for n in ucs if group.get(n["id"]) == a and n["id"] not in order]
    cols = 1
    maxlen = max(len(n["label"]) for n in ucs)
    uw = int(min(430, max(250, 6.4 * maxlen + 50)))
    UH, RH = 52, 66
    rows = math.ceil(len(order) / cols)
    n_br = sum(1 for e in rels if "include" in e["label"] or "extend" in e["label"])
    box_x, box_w, box_h = 240, cols * (uw + 40) + 60 + 18 * n_br, rows * RH + 90

    p = package(pkg, did)
    d = add(p, "ownedElements", B.el("UMLUseCaseDiagram", p, name="%s %s" % (did, title)))
    sysname = subs[0]["title"] if len(subs) == 1 else "Trans-DIC1"
    sysname = re.sub(r"^«[^»]+»\s*", "", sysname)
    subj = add(p, "ownedElements", B.el("UMLUseCaseSubject", p, name=sysname))
    view("UMLUseCaseSubjectView", d, subj, box_x, 20, box_w, box_h)
    am, av, um, uv, uy = {}, {}, {}, {}, {}
    for k, uid in enumerate(order):
        r, c = divmod(k, cols)
        m = add(p, "ownedElements", B.el("UMLUseCase", p, name=nodes[uid]["label"]))
        um[uid] = m
        uv[uid] = view("UMLUseCaseView", d, m, box_x + 40 + c * (uw + 40), 70 + r * RH, uw, UH, **SUP)
        uy[uid] = 70 + r * RH + UH / 2
    # sous-ensembles du système (subgraph) : un titre en note au-dessus du premier cas
    if len(subs) > 1:
        for sg in subs:
            first = [u for u in order if sg["id"] in nodes[u]["subs"]]
            if first:
                v = uv[first[0]]
                note(d, sg["title"], v["left"] - 30, v["top"] - 14, 220, 22)

    placed = {"L": [], "R": []}
    for n in actors:
        aid = n["id"]
        name, st = _actor_info(n["label"])
        m = add(p, "ownedElements", B.el("UMLActor", p, name=name))
        if aid in sec:
            m["stereotype"] = st or "acteur secondaire"
            m["documentation"] = "Acteur secondaire : sollicité par le système, il n'initie aucun cas d'utilisation."
        else:
            if st:
                m["stereotype"] = st
            m["documentation"] = "Acteur principal : initie les cas d'utilisation auxquels il est associé."
        am[aid] = m
        side = "R" if aid in sec else "L"
        ys = [uy[u] for x, u, _ in links if x == aid and u in uy]
        y = (sum(ys) / len(ys) if ys else box_h / 2) - 47
        while any(abs(py - y) < 125 for py in placed[side]):
            y += 125
        placed[side].append(y)
        x = box_x + box_w + 70 if side == "R" else 50
        av[aid] = view("UMLActorView", d, m, x, int(y), 110, 95, **SUP)

    allm, allv = {**am, **um}, {**av, **uv}
    for a, u, is_sec in links:
        as_ = add(p, "ownedElements", B.el("UMLAssociation", p, name=""))
        if not is_sec:
            as_["end1"] = B.el("UMLAssociationEnd", as_, reference=ref(am[a]), navigable="unspecified")
            as_["end2"] = B.el("UMLAssociationEnd", as_, reference=ref(um[u]), navigable="unspecified")
            edge_view("UMLAssociationView", d, as_, av[a], uv[u], False, True)
        else:
            as_["end1"] = B.el("UMLAssociationEnd", as_, reference=ref(um[u]), navigable="unspecified")
            as_["end2"] = B.el("UMLAssociationEnd", as_, reference=ref(am[a]), navigable="navigable")
            edge_view("UMLAssociationView", d, as_, uv[u], av[a], True, False)
    bracket = 0
    for e in rels:
        a, b, lab = e["a"], e["b"], e["label"].lower()
        if "généralisation" in lab or "spécialisé" in lab:
            src, tgt = (a, b) if "généralisation" in lab else (b, a)
            g = add(p, "ownedElements", B.el("UMLGeneralization", p, name="", source=ref(allm[src]),
                                              target=ref(allm[tgt])))
            edge_view("UMLGeneralizationView", d, g, allv[src], allv[tgt])
        elif "include" in lab or "extend" in lab:
            typ = "UMLInclude" if "include" in lab else "UMLExtend"
            m = add(allm[a], "ownedElements", B.el(typ, allm[a], name="", source=ref(allm[a]),
                                                    target=ref(allm[b])))
            va, vb = allv[a], allv[b]
            pts = None
            if va["left"] == vb["left"]:  # même colonne : crochet sur la droite
                bracket += 1
                xr = va["left"] + va["width"] + 18 * bracket
                ya, yb = va["top"] + va["height"] / 2, vb["top"] + vb["height"] / 2
                pts = "%d:%d;%d:%d;%d:%d;%d:%d" % (va["left"] + va["width"] - 4, ya, xr, ya, xr, yb,
                                                    vb["left"] + vb["width"] - 4, yb)
            edge_view(typ + "View", d, m, va, vb, True, True, points=pts)
    return d


# ------------------------------------------------------------------ classes
_REL = re.compile(r'^(\w+)(?: "([^"]+)")? (\*--|-->|\.\.>|\.\.\|>|--) (?:"([^"]+)" )?(\w+)(?: : (.+))?$')


def parse_classes_generic(text):
    classes, order, rels, cur = {}, [], [], None
    for raw in text.splitlines():
        s = raw.strip()
        m = re.match(r"class (\w+) \{", s)
        if m:
            cur = {"stereo": None, "attrs": [], "ops": [], "lits": []}
            classes[m.group(1)] = cur
            order.append(m.group(1))
            continue
        if cur is not None:
            if s == "}":
                cur = None
                continue
            m = re.match(r"<<(.+)>>", s)
            if m:
                cur["stereo"] = m.group(1)
            elif s[:1] in "+-#" and s:
                vis = {"+": "public", "-": "private", "#": "protected"}[s[0]]
                body = s[1:].strip()
                m = re.match(r"(\w+)\((.*)\)$", body)
                if m:
                    cur["ops"].append((vis, m.group(1), [x.strip() for x in m.group(2).split(",") if x.strip()]))
                else:
                    t, _, n = body.rpartition(" ")
                    cur["attrs"].append((vis, n, t))
            elif s:
                cur["lits"].append(s)
            continue
        m = _REL.match(s)
        if m:
            rels.append(m.groups())
    return classes, order, rels


def build_classes_generic(pkg, path, did, title):
    classes, order, rels = parse_classes_generic((SRC / path).read_text(encoding="utf8"))
    p = package(pkg, did)
    d = add(p, "ownedElements", B.el("UMLClassDiagram", p, name="%s %s" % (did, title)))
    kw = dict(containerChangeable=True, stereotypeDisplay="label", showVisibility=True, showProperty=True,
              showType=True, showOperationSignature=True, suppressAttributes=False, suppressOperations=False)
    mods, size = {}, {}
    for name in order:
        c = classes[name]
        st = c["stereo"]
        if st == "enumeration":
            m = add(p, "ownedElements", B.el("UMLEnumeration", p, name=name))
            for lit in c["lits"]:
                add(m, "literals", B.el("UMLEnumerationLiteral", m, name=lit))
            typ, h = "UMLEnumerationView", 45 + 18 * len(c["lits"])
        else:
            is_itf = st == "interface"
            m = add(p, "ownedElements", B.el("UMLInterface" if is_itf else "UMLClass", p, name=name))
            if st and not is_itf:
                m["stereotype"] = st
            for vis, an, at in c["attrs"]:
                add(m, "attributes", B.el("UMLAttribute", m, name=an, type=at, visibility=vis))
            for vis, on, params in c["ops"]:
                op = add(m, "operations", B.el("UMLOperation", m, name=on, visibility=vis))
                for pt in params:
                    add(op, "parameters", B.el("UMLParameter", op, name=pt[0].lower() + pt[1:], type=pt,
                                               direction="in"))
            typ = "UMLInterfaceView" if is_itf else "UMLClassView"
            h = 50 + 18 * (len(c["attrs"]) + len(c["ops"])) + (14 if is_itf else 0)
        mods[name] = (m, typ)
        size[name] = (250, h)
    rank_edges = [((b, a) if op == "..|>" else (a, b)) for a, ma, op, mb, b, lab in rels]
    pos, _, _ = layered(order, rank_edges, size, horizontal=True, gap_rank=120, gap_node=50)
    views = {}
    for name in order:
        m, typ = mods[name]
        views[name] = view(typ, d, m, int(pos[name][0]) + 30, int(pos[name][1]) + 30, *size[name], **kw)
    for a, ma, op, mb, b, lab in rels:
        ma_, mb_ = mods[a][0], mods[b][0]
        if op in ("-->", "*--", "--"):
            as_ = add(p, "ownedElements", B.el("UMLAssociation", p, name=lab or ""))
            e1 = B.el("UMLAssociationEnd", as_, reference=ref(ma_), navigable="unspecified",
                      aggregation="composite" if op == "*--" else "none")
            e2 = B.el("UMLAssociationEnd", as_, reference=ref(mb_), aggregation="none",
                      navigable="navigable" if op == "-->" else "unspecified")
            if ma:
                e1["multiplicity"] = ma
            if mb:
                e2["multiplicity"] = mb
            as_["end1"], as_["end2"] = e1, e2
            edge_view("UMLAssociationView", d, as_, views[a], views[b])
        elif op == "..>":
            dep = add(p, "ownedElements", B.el("UMLDependency", p, name="", source=ref(ma_), target=ref(mb_)))
            edge_view("UMLDependencyView", d, dep, views[a], views[b])
        else:
            r = add(p, "ownedElements", B.el("UMLInterfaceRealization", p, name="", source=ref(ma_),
                                              target=ref(mb_)))
            edge_view("UMLInterfaceRealizationView", d, r, views[a], views[b])
    return d


# ------------------------------------------------------------------ activités
def build_activity_generic(pkg, path, did, title):
    nodes, edges, _ = parse_flow((SRC / path).read_text(encoding="utf8"))
    ids = list(nodes)
    size = {}
    for i, n in nodes.items():
        # une décision réserve à sa gauche la place de la note qui porte sa question
        size[i] = {"circle": (30, 30), "decision": (270, 50)}.get(n["kind"], (250, 54))
    pos, _, back = layered(ids, [(e["a"], e["b"]) for e in edges], size, gap_rank=70, gap_node=60)
    ox = 150  # marge à gauche pour les retours de boucle
    act = add(pkg, "ownedElements", B.el("UMLActivity", pkg, name="%s %s" % (did, title)))
    d = add(act, "ownedElements", B.el("UMLActivityDiagram", act, name="%s %s" % (did, title)))
    mods, views = {}, {}
    for i in ids:
        n = nodes[i]
        x, y = int(pos[i][0] + ox), int(pos[i][1] + 30)
        w, h = size[i]
        if n["kind"] == "circle":
            initial = not any(e["b"] == i for e in edges)
            m = add(act, "nodes", B.el("UMLInitialNode" if initial else "UMLActivityFinalNode", act, name=n["label"]))
            views[i] = view("UMLControlNodeView", d, m, x, y, w, h)
            note(d, n["label"], x + 45, y - 2, 150, 32, views[i])
        elif n["kind"] == "decision":
            m = add(act, "nodes", B.el("UMLDecisionNode", act, name=n["label"]))
            views[i] = view("UMLControlNodeView", d, m, x + 210, y, 60, 50)
            note(d, n["label"], x, y, 190, 50, views[i])
        else:
            m = add(act, "nodes", B.el("UMLAction", act, name=n["label"]))
            views[i] = view("UMLActionView", d, m, x, y, w, h)
        mods[i] = m
    right = max(v["left"] + v["width"] for v in views.values())
    left = min(v["left"] for v in views.values())
    mid = (left + right) / 2
    kr = kl = 0
    for e in edges:
        a, b = e["a"], e["b"]
        f = add(act, "edges", B.el("UMLControlFlow", act, name="", source=ref(mods[a]), target=ref(mods[b]),
                                   guard=e["label"]))
        pts = None
        if (a, b) in back:  # retour en boucle : contourné par le côté le plus proche
            va, vb = views[a], views[b]
            ya, yb = va["top"] + va["height"] / 2, vb["top"] + vb["height"] / 2
            if va["left"] + va["width"] / 2 < mid:
                # par la gauche, en sortant par le bas pour ne pas traverser la note de décision
                kl += 1
                xl = left - 30 - 24 * kl
                cx, yb0 = va["left"] + va["width"] / 2, va["top"] + va["height"]
                pts = "%d:%d;%d:%d;%d:%d;%d:%d;%d:%d" % (cx, yb0, cx, yb0 + 16, xl, yb0 + 16, xl, yb,
                                                         vb["left"], yb)
            else:
                kr += 1
                xr = right + 30 + 24 * kr
                pts = "%d:%d;%d:%d;%d:%d;%d:%d" % (va["left"] + va["width"], ya, xr, ya, xr, yb,
                                                    vb["left"] + vb["width"], yb)
        edge_view("UMLControlFlowView", d, f, views[a], views[b], points=pts)
    return d


# ------------------------------------------------------------------ états
def build_states_generic(pkg, path, did, title, fixed_pos=None):
    txt = (SRC / path).read_text(encoding="utf8")
    trs = re.findall(r"^\s*(\[\*\]|\w+) --> (\[\*\]|\w+)(?: : (.+))?$", txt, re.M)
    notes = [(m.group(1), " ".join(x.strip() for x in m.group(2).splitlines() if x.strip()))
             for m in re.finditer(r"note (?:right|left) of (\w+)\n(.*?)end note", txt, re.S)]
    names = []
    for a, b, _ in trs:
        for x in (a, b):
            if x != "[*]" and x not in names:
                names.append(x)
    sm = add(pkg, "ownedElements", B.el("UMLStateMachine", pkg, name="%s %s" % (did, title)))
    reg = add(sm, "regions", B.el("UMLRegion", sm, name="Region1"))
    d = add(sm, "ownedElements", B.el("UMLStatechartDiagram", sm, name="%s %s" % (did, title)))
    norm = lambda a, b: ("INIT" if a == "[*]" else a, "FINAL" if b == "[*]" else b)
    ids = ["INIT"] + names + ["FINAL"]
    size = {i: (190, 70) for i in names}
    size["INIT"], size["FINAL"] = (24, 24), (30, 30)
    if fixed_pos:
        pos = fixed_pos
    else:
        pos, _, _ = layered(ids, [norm(a, b) for a, b, _ in trs], size, gap_rank=130, gap_node=160)
    sts, views = {}, {}
    sts["INIT"] = add(reg, "vertices", B.el("UMLPseudostate", reg, name="", kind="initial"))
    sts["FINAL"] = add(reg, "vertices", B.el("UMLFinalState", reg, name=""))
    for n in names:
        sts[n] = add(reg, "vertices", B.el("UMLState", reg, name=n))
    for i in ids:
        typ = {"INIT": "UMLPseudostateView", "FINAL": "UMLFinalStateView"}.get(i, "UMLStateView")
        extra = {"containerChangeable": True} if typ == "UMLStateView" else {}
        views[i] = view(typ, d, sts[i], int(pos[i][0]) + 60, int(pos[i][1]) + 30, *size[i], **extra)
    pairs = [frozenset(norm(a, b)) for a, b, _ in trs]
    seen = {}
    for (a, b, label), pair in zip(trs, pairs):
        a, b = norm(a, b)
        tr = add(reg, "transitions", B.el("UMLTransition", reg, name="", source=ref(sts[a]), target=ref(sts[b])))
        if label:
            guard = re.search(r"\[(.+)\]\s*$", label)
            trig = re.sub(r"\s*\[.+\]\s*$", "", label)
            if " / " in trig:
                trig, eff = trig.split(" / ", 1)
                tr["effect"] = B.el("UMLOpaqueBehavior", tr, name=eff.strip())
            tr["triggers"] = [B.el("UMLEvent", tr, name=trig.strip(), kind="signal")]
            if guard:
                tr["guard"] = guard.group(1)
        seen[pair] = seen.get(pair, 0) + 1
        total = pairs.count(pair)
        pts = None
        if a == b:
            x, y, w, h = rect(views[a])
            pts = "%d:%d;%d:%d;%d:%d;%d:%d" % (x + w, y + 20, x + w + 40, y + 20, x + w + 40, y + 50, x + w, y + 50)
        elif total > 1:
            k = (seen[pair] - 1 - (total - 1) / 2) * 26
            pts = edge_offset(views[a], views[b], -k if a > b else k)
        edge_view("UMLTransitionView", d, tr, views[a], views[b], points=pts)
    for target, text in notes:
        x, y, w, h = rect(views[target])
        note(d, text, x + w + 80, y - 10, 290, 80, views[target])
    return d


# ------------------------------------------------------------------ composants
def _stereo_name(label):
    m = re.match(r"^«([^»]+)»\s*(.*)$", label)
    return (m.group(1), m.group(2)) if m else (None, label)


def build_components_generic(pkg, path, did, title):
    nodes, edges, subs = parse_flow((SRC / path).read_text(encoding="utf8"))
    ids = list(nodes)
    size = {i: (230, 70) for i in ids}
    pos, _, _ = layered(ids, [(e["a"], e["b"]) for e in edges], size, horizontal=True, gap_rank=150, gap_node=40)
    p = package(pkg, did)
    d = add(p, "ownedElements", B.el("UMLComponentDiagram", p, name="%s %s" % (did, title)))
    holders = {}
    for sg in subs:
        holders[sg["id"]] = add(p, "ownedElements", B.el("UMLComponent", p, name=sg["title"], stereotype="subsystem"))
        members = [i for i in ids if sg["id"] in nodes[i]["subs"]]
        xs = [pos[i][0] + 60 for i in members]
        ys = [pos[i][1] + 60 for i in members]
        view("UMLComponentView", d, holders[sg["id"]], int(min(xs)) - 30, int(min(ys)) - 50,
             int(max(xs) - min(xs)) + 230 + 60, int(max(ys) - min(ys)) + 70 + 80, **SUP)
    mods, views = {}, {}
    for i in ids:
        n = nodes[i]
        st, name = _stereo_name(n["label"])
        owner = holders[n["subs"][-1]] if n["subs"] else p
        if not st:
            st = "database" if n["kind"] == "db" else ("component" if n["subs"] else "externe")
        mods[i] = add(owner, "ownedElements", B.el("UMLComponent", owner, name=name, stereotype=st))
        views[i] = view("UMLComponentView", d, mods[i], int(pos[i][0]) + 60, int(pos[i][1]) + 60, 230, 70, **SUP)
    for e in edges:
        a, b = e["a"], e["b"]
        if e["bidir"]:
            as_ = add(p, "ownedElements", B.el("UMLAssociation", p, name=e["label"]))
            as_["end1"] = B.el("UMLAssociationEnd", as_, reference=ref(mods[a]), navigable="navigable")
            as_["end2"] = B.el("UMLAssociationEnd", as_, reference=ref(mods[b]), navigable="navigable")
            edge_view("UMLAssociationView", d, as_, views[a], views[b])
        else:
            dep = add(mods[a], "ownedElements", B.el("UMLDependency", mods[a], name=e["label"],
                                                      source=ref(mods[a]), target=ref(mods[b])))
            edge_view("UMLDependencyView", d, dep, views[a], views[b])
    return d


# ------------------------------------------------------------------ C4 (en notation UML)
C4_STEREO = {"Person": "person", "System": "system", "System_Ext": "système externe",
             "Container": "container", "ContainerDb": "container : base de données"}


def build_c4(pkg, path, did, title):
    txt = (SRC / path).read_text(encoding="utf8")
    elems, rels, bounds, stack = {}, [], [], []
    for s in (x.strip() for x in txt.splitlines()):
        m = re.match(r'(Person|System_Ext|System_Boundary|System|ContainerDb|Container)\((\w+), "([^"]*)"'
                     r'(?:, "([^"]*)")?(?:, "([^"]*)")?\)\s*(\{)?$', s)
        if m:
            kind, eid, name, a1, a2, _ = m.groups()
            if kind == "System_Boundary":
                bounds.append({"id": eid, "name": name})
                stack.append(eid)
                continue
            tech, desc = (a1 or "", a2 or "") if kind.startswith("Container") else ("", a1 or "")
            elems[eid] = {"kind": kind, "name": name, "tech": tech, "desc": desc, "bound": stack[-1] if stack else None}
        elif s == "}":
            stack.pop()
        else:
            m = re.match(r'Rel\((\w+), (\w+), "([^"]*)"(?:, "([^"]*)")?\)', s)
            if m:
                rels.append(m.groups())
    ids = list(elems)
    size = {i: (320, 90) for i in ids}
    pos, _, _ = layered(ids, [(a, b) for a, b, _, _ in rels], size, horizontal=True, gap_rank=190, gap_node=60)
    p = package(pkg, did)
    d = add(p, "ownedElements", B.el("UMLComponentDiagram", p, name="%s %s" % (did, title)))
    holders = {}
    for b in bounds:
        holders[b["id"]] = add(p, "ownedElements", B.el("UMLComponent", p, name=b["name"],
                                                         stereotype="system boundary"))
        members = [i for i in ids if elems[i]["bound"] == b["id"]]
        xs = [pos[i][0] + 50 for i in members]
        ys = [pos[i][1] + 60 for i in members]
        view("UMLComponentView", d, holders[b["id"]], int(min(xs)) - 30, int(min(ys)) - 50,
             int(max(xs) - min(xs)) + 320 + 60, int(max(ys) - min(ys)) + 90 + 80, **SUP)
    mods, views = {}, {}
    for i in ids:
        e = elems[i]
        owner = holders[e["bound"]] if e["bound"] else p
        name = e["name"] + (" [%s]" % e["tech"] if e["tech"] else "")
        mods[i] = add(owner, "ownedElements", B.el("UMLComponent", owner, name=name,
                                                    stereotype=C4_STEREO[e["kind"]], documentation=e["desc"]))
        views[i] = view("UMLComponentView", d, mods[i], int(pos[i][0]) + 50, int(pos[i][1]) + 60, 320, 90, **SUP)
    for a, b, lab, tech in rels:
        dep = add(mods[a], "ownedElements", B.el("UMLDependency", mods[a],
                                                  name="%s [%s]" % (lab, tech) if tech else lab,
                                                  source=ref(mods[a]), target=ref(mods[b])))
        edge_view("UMLDependencyView", d, dep, views[a], views[b])
    return d


# ------------------------------------------------------------------ déploiement (architecture-beta)
def build_deployment_beta(pkg, path, did, title):
    txt = (SRC / path).read_text(encoding="utf8")
    groups, services, links = [], [], []
    for s in (x.strip() for x in txt.splitlines()):
        m = re.match(r"group (\w+)\((\w+)\)\[(.+)\]", s)
        if m:
            groups.append({"id": m.group(1), "label": m.group(3)})
            continue
        m = re.match(r"service (\w+)\((\w+)\)\[(.+)\] in (\w+)", s)
        if m:
            services.append({"id": m.group(1), "icon": m.group(2), "label": m.group(3), "group": m.group(4)})
            continue
        m = re.match(r"(\w+):\w -- \w:(\w+)", s)
        if m:
            links.append((m.group(1), m.group(2)))
    p = package(pkg, did)
    d = add(p, "ownedElements", B.el("UMLDeploymentDiagram", p, name="%s %s" % (did, title)))
    sm, sv = {}, {}
    x = 30
    for g in groups:
        mem = [s for s in services if s["group"] == g["id"]]
        external = g["id"] in ("managed", "external")
        ster = {"client": "device", "host": "device", "managed": "node externe", "external": "node externe"}.get(g["id"], "node")
        n = add(p, "ownedElements", B.el("UMLNode", p, name=g["label"], stereotype=ster))
        view("UMLNodeView", d, n, x, 30, 290, 80 + len(mem) * 90, **SUP)
        for k, s in enumerate(mem):
            if s["icon"] == "database":
                el, typ = B.el("UMLArtifact", n, name=s["label"], stereotype="database"), "UMLArtifactView"
            elif external:
                el, typ = B.el("UMLNode", n, name=s["label"], stereotype="device"), "UMLNodeView"
            elif s["icon"] == "internet":
                el, typ = B.el("UMLNode", n, name=s["label"], stereotype="executionEnvironment"), "UMLNodeView"
            else:
                el, typ = B.el("UMLArtifact", n, name=s["label"]), "UMLArtifactView"
            add(n, "ownedElements", el)
            sm[s["id"]] = el
            sv[s["id"]] = view(typ, d, el, x + 30, 85 + k * 90, 230, 56, **SUP)
        x += 290 + 130
    for a, b in links:
        as_ = add(p, "ownedElements", B.el("UMLCommunicationPath", p, name=""))
        as_["end1"] = B.el("UMLAssociationEnd", as_, reference=ref(sm[a]), navigable="unspecified")
        as_["end2"] = B.el("UMLAssociationEnd", as_, reference=ref(sm[b]), navigable="unspecified")
        edge_view("UMLCommunicationPathView", d, as_, sv[a], sv[b])
    return d


# ------------------------------------------------------------------ modèle de données (ERD)
CARD = {"||": "1", "o|": "0..1", "|o": "0..1", "}o": "0..*", "o{": "0..*", "}|": "1..*", "|{": "1..*"}


def build_erd(proj, path, did, title):
    txt = (SRC / path).read_text(encoding="utf8")
    model = add(proj, "ownedElements", B.el("ERDDataModel", proj, name="%s %s" % (did, title)))
    ents, order = {}, []
    for m in re.finditer(r"^ {4}(\w+) \{\n(.*?)\n {4}\}", txt, re.M | re.S):
        e = add(model, "ownedElements", B.el("ERDEntity", model, name=m.group(1)))
        for line in m.group(2).splitlines():
            t = line.split(None, 2)
            flags = t[2] if len(t) > 2 else ""
            pk, fk, uk = "PK" in flags, "FK" in flags, "UK" in flags
            add(e, "columns", B.el("ERDColumn", e, name=t[1], type=t[0].upper(), primaryKey=pk, foreignKey=fk,
                                   unique=uk, nullable=not (pk or uk or fk)))
        ents[m.group(1)] = e
        order.append(m.group(1))
    rels = re.findall(r"^ {4}(\w+) (\S\S)--(\S\S) (\w+) : (\w+)", txt, re.M)
    # une clé étrangère vers un parent optionnel (o|) reste nullable
    for a, l, r, b, lab in rels:
        if CARD[l] == "0..1":
            for c in ents[b]["columns"]:
                if c["foreignKey"] and not c["name"].startswith(a.lower().rstrip("s") + "_"):
                    c["nullable"] = True
    d = add(model, "ownedElements", B.el("ERDDiagram", model, name="%s %s" % (did, title)))
    size = {n: (250, 40 + 20 * len(ents[n]["columns"])) for n in order}
    pos, _, _ = layered(order, [(a, b) for a, _, _, b, _ in rels], size, horizontal=True, gap_rank=130, gap_node=40)
    views = {n: view("ERDEntityView", d, ents[n], int(pos[n][0]) + 30, int(pos[n][1]) + 30, *size[n]) for n in order}
    for a, l, r, b, lab in rels:
        ident = any(c["primaryKey"] and c["foreignKey"] for c in ents[b]["columns"])
        rel = add(model, "ownedElements", B.el("ERDRelationship", model, name=lab, identifying=ident))
        rel["end1"] = B.el("ERDRelationshipEnd", rel, reference=ref(ents[a]), cardinality=CARD[l])
        rel["end2"] = B.el("ERDRelationshipEnd", rel, reference=ref(ents[b]), cardinality=CARD[r])
        edge_view("ERDRelationshipView", d, rel, views[a], views[b])
    return d
