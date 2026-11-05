"""Contrôle des droits NTFS du fichier de jeton sous Windows (équivalent du contrôle « 600 » Linux).

La liste de contrôle d'accès (DACL) est lue par l'API Win32 (GetNamedSecurityInfoW, via ctypes),
sans icacls ni dépendance : la sortie d'icacls est traduite selon la langue de Windows, les SID ne
le sont pas. Le fichier est refusé si une entrée « autoriser » donne la lecture à un groupe large
(Tout le monde, Utilisateurs, Utilisateurs authentifiés, Invités, Anonyme, Interactif, Local), ou
s'il n'a pas de DACL du tout (accès libre). Les entrées « refuser » ne sont pas prises en compte :
le contrôle reste prudent, une autorisation large suffit à refuser le fichier.
"""

import ctypes
from ctypes import wintypes

# SID bien connus, indépendants de la langue du système.
BROAD_SIDS = {
    "S-1-1-0": "Tout le monde",
    "S-1-2-0": "Local",
    "S-1-5-4": "Interactif",
    "S-1-5-7": "Anonyme",
    "S-1-5-11": "Utilisateurs authentifiés",
    "S-1-5-32-545": "Utilisateurs",
    "S-1-5-32-546": "Invités",
}

SE_FILE_OBJECT = 1
DACL_SECURITY_INFORMATION = 0x00000004
ACCESS_ALLOWED_ACE_TYPE = 0x0
ACCESS_ALLOWED_CALLBACK_ACE_TYPE = 0x9
INHERIT_ONLY_ACE = 0x08
# FILE_READ_DATA, MAXIMUM_ALLOWED, GENERIC_ALL, GENERIC_READ
READ_MASK = 0x00000001 | 0x02000000 | 0x10000000 | 0x80000000


class _Acl(ctypes.Structure):
    _fields_ = [
        ("AclRevision", ctypes.c_ubyte),
        ("Sbz1", ctypes.c_ubyte),
        ("AclSize", ctypes.c_ushort),
        ("AceCount", ctypes.c_ushort),
        ("Sbz2", ctypes.c_ushort),
    ]


class _AceHeader(ctypes.Structure):
    _fields_ = [("AceType", ctypes.c_ubyte), ("AceFlags", ctypes.c_ubyte), ("AceSize", ctypes.c_ushort)]


class _AccessAllowedAce(ctypes.Structure):
    # SidStart : premier mot du SID, qui se prolonge au-delà de la structure.
    _fields_ = [("Header", _AceHeader), ("Mask", wintypes.DWORD), ("SidStart", wintypes.DWORD)]


def _api():
    advapi32 = ctypes.WinDLL("advapi32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    advapi32.GetNamedSecurityInfoW.argtypes = [
        wintypes.LPCWSTR,
        ctypes.c_int,
        wintypes.DWORD,
        ctypes.POINTER(ctypes.c_void_p),
        ctypes.POINTER(ctypes.c_void_p),
        ctypes.POINTER(ctypes.c_void_p),
        ctypes.POINTER(ctypes.c_void_p),
        ctypes.POINTER(ctypes.c_void_p),
    ]
    advapi32.GetNamedSecurityInfoW.restype = wintypes.DWORD
    advapi32.GetAce.argtypes = [ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(ctypes.c_void_p)]
    advapi32.GetAce.restype = wintypes.BOOL
    advapi32.ConvertSidToStringSidW.argtypes = [ctypes.c_void_p, ctypes.POINTER(wintypes.LPWSTR)]
    advapi32.ConvertSidToStringSidW.restype = wintypes.BOOL
    kernel32.LocalFree.argtypes = [ctypes.c_void_p]
    kernel32.LocalFree.restype = ctypes.c_void_p
    return advapi32, kernel32


def _sid_string(advapi32, kernel32, sid_pointer):
    text = wintypes.LPWSTR()
    if not advapi32.ConvertSidToStringSidW(sid_pointer, ctypes.byref(text)):
        error = ctypes.get_last_error()
        raise OSError(error, "ConvertSidToStringSidW a échoué (code Windows %d)" % error)
    try:
        return text.value
    finally:
        kernel32.LocalFree(ctypes.cast(text, ctypes.c_void_p))


def allowed_read_sids(path):
    """SID ayant une autorisation de lecture effective sur path ; None si le fichier n'a pas de DACL."""
    advapi32, kernel32 = _api()
    dacl = ctypes.c_void_p()
    descriptor = ctypes.c_void_p()
    status = advapi32.GetNamedSecurityInfoW(
        path, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION, None, None, ctypes.byref(dacl), None, ctypes.byref(descriptor)
    )
    if status != 0:
        raise OSError(status, "lecture des droits impossible (code Windows %d)" % status, path)
    try:
        if not dacl.value:
            return None
        count = ctypes.cast(dacl, ctypes.POINTER(_Acl)).contents.AceCount
        readers = []
        for index in range(count):
            ace = ctypes.c_void_p()
            if not advapi32.GetAce(dacl, index, ctypes.byref(ace)):
                error = ctypes.get_last_error()
                raise OSError(error, "GetAce a échoué (code Windows %d)" % error, path)
            header = ctypes.cast(ace, ctypes.POINTER(_AceHeader)).contents
            if header.AceType not in (ACCESS_ALLOWED_ACE_TYPE, ACCESS_ALLOWED_CALLBACK_ACE_TYPE):
                continue
            if header.AceFlags & INHERIT_ONLY_ACE:
                continue  # ne s'applique qu'aux objets enfants, pas au fichier lui-même
            allowed = ctypes.cast(ace, ctypes.POINTER(_AccessAllowedAce)).contents
            if not allowed.Mask & READ_MASK:
                continue
            sid = ace.value + _AccessAllowedAce.SidStart.offset
            readers.append(_sid_string(advapi32, kernel32, ctypes.c_void_p(sid)))
        return readers
    finally:
        kernel32.LocalFree(descriptor)


def broad_readers(path):
    """Groupes larges (SID) qui peuvent lire path ; [« DACL nulle »] si l'accès est libre."""
    readers = allowed_read_sids(path)
    if readers is None:
        return ["DACL nulle"]
    return sorted({sid for sid in readers if sid in BROAD_SIDS})
