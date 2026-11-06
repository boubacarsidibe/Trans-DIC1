"""Droits du fichier de jeton sous Windows : équivalent du « 600 » de Linux.

Windows n'a pas de bits de droits POSIX : on lit la liste de contrôle d'accès (DACL) au format SDDL
avec icacls, et on refuse le fichier si un accès est accordé à un groupe large (Tout le monde,
Utilisateurs, Utilisateurs authentifiés). Les groupes sont reconnus par leur SID, pas par leur nom :
la vérification est identique sur un Windows en français ou en anglais.
"""

import os
import re
import subprocess
import tempfile

# Alias SDDL et SID bien connus des groupes que le fichier de jeton ne doit pas ouvrir.
BROAD_PRINCIPALS = {
    "WD": "Tout le monde",
    "S-1-1-0": "Tout le monde",
    "BU": "Utilisateurs",
    "S-1-5-32-545": "Utilisateurs",
    "AU": "Utilisateurs authentifiés",
    "S-1-5-11": "Utilisateurs authentifiés",
    "IU": "Session interactive",
    "S-1-5-4": "Session interactive",
    "NU": "Réseau",
    "S-1-5-2": "Réseau",
}


def broad_principals(sddl):
    """Groupes larges auxquels la DACL accorde un accès (les refus explicites sont ignorés)."""
    dacl = sddl.split("D:", 1)[1] if "D:" in sddl else ""
    dacl = dacl.split("S:", 1)[0]
    found = []
    for ace in re.findall(r"\(([^()]*)\)", dacl):
        fields = ace.split(";")
        # type;drapeaux;droits;guid objet;guid hérité;compte
        if len(fields) < 6 or fields[0] not in ("A", "OA"):
            continue
        name = BROAD_PRINCIPALS.get(fields[5].strip())
        if name and name not in found:
            found.append(name)
    return found


def read_sddl(path, run=subprocess.run):
    """SDDL du fichier, lu par « icacls /save » (sortie en UTF-16)."""
    folder = tempfile.mkdtemp(prefix="tdic1-acl-")
    out = os.path.join(folder, "acl.txt")
    try:
        result = run(["icacls", path, "/save", out], capture_output=True)
        if result.returncode != 0:
            raise OSError("icacls a échoué (code %d)" % result.returncode)
        with open(out, "rb") as handle:
            raw = handle.read()
    finally:
        try:
            os.remove(out)
            os.rmdir(folder)
        except OSError:
            pass
    for encoding in ("utf-16", "utf-8", "cp1252"):
        try:
            return raw.decode(encoding)
        except UnicodeError:
            continue
    raise OSError("sortie d'icacls illisible")


def token_file_problem(path, run=subprocess.run):
    """Message d'erreur si le fichier de jeton est ouvert à des groupes larges, sinon None."""
    try:
        groups = broad_principals(read_sddl(path, run))
    except OSError as error:
        return "impossible de vérifier les droits du fichier de jeton %s : %s" % (path, error)
    if groups:
        return (
            "le fichier de jeton %s est accessible à : %s. Retirer ces droits "
            "(icacls \"%s\" /inheritance:r /grant:r *S-1-5-18:F *S-1-5-32-544:F)"
            % (path, ", ".join(groups), path)
        )
    return None
