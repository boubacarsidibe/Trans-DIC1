"""Lecture des métriques système Windows par l'API Win32 (ctypes), sans dépendance externe.

Mêmes clés, mêmes unités et même sens que le collecteur Linux (metrics.Collector) :
- cpu.usage : écart entre deux relevés de GetSystemTimes (le temps noyau inclut le temps inactif) ;
- memory.usage : GlobalMemoryStatusEx, (total - disponible) / total, comme MemTotal - MemAvailable ;
- disk.usage : GetDiskFreeSpaceExW sur le lecteur configuré (défaut : lecteur système), calculé
  comme « df » : utilisé / (utilisé + disponible pour l'appelant) ;
- availability : 100 tant que l'agent tourne.
En plus : uptime.seconds (GetTickCount64), en secondes.

Ce module s'importe sur toutes les plateformes (logique pure testable partout) ; les appels à
kernel32 ne sont faits qu'à l'exécution, sous Windows.
"""

import ctypes
import os
import re

from .metrics import cpu_usage, measure, utc_now

# Colonne metric_value du backend : NUMERIC(12, 4), soit moins de 10^8. Au-delà (plus de 3 ans
# sans redémarrage), la valeur est bornée et marquée DEGRADED plutôt que de faire refuser le lot.
MAX_UPTIME_SECONDS = 99999999

_DRIVE_ONLY = re.compile(r"^([A-Za-z]):?[\\/]?$")


def _kernel32():
    return ctypes.WinDLL("kernel32", use_last_error=True)


def _fail(function):
    error = ctypes.get_last_error()
    raise OSError(error, "%s a échoué (code Windows %d)" % (function, error))


def system_drive():
    """Lecteur système, au format « C: »."""
    return os.environ.get("SystemDrive", "C:").rstrip("\\/") or "C:"


def normalize_disk_path(path, drive=None):
    """Chemin passé à GetDiskFreeSpaceExW : racine de lecteur ou dossier, terminé par « \\ »."""
    drive = drive or system_drive()
    if path is None or path.strip() in ("", "/", "\\"):
        # Défaut, ou valeur Linux recopiée d'une configuration existante : le lecteur système.
        return drive.rstrip("\\/").upper() + "\\"
    path = path.strip()
    match = _DRIVE_ONLY.match(path)
    if match:
        return match.group(1).upper() + ":\\"
    path = path.replace("/", "\\")
    return path if path.endswith("\\") else path + "\\"


def df_percent(total, total_free, available):
    used = total - total_free
    usable = used + available
    return 100.0 * used / usable if usable > 0 else 0.0


def memory_percent(total, available):
    return 100.0 * (total - available) / total if total else 0.0


def read_cpu_times():
    """Temps CPU cumulés (inactif, total), en unités de 100 ns : total = noyau + utilisateur."""
    idle, kernel, user = ctypes.c_ulonglong(), ctypes.c_ulonglong(), ctypes.c_ulonglong()
    kernel32 = _kernel32()
    if not kernel32.GetSystemTimes(ctypes.byref(idle), ctypes.byref(kernel), ctypes.byref(user)):
        _fail("GetSystemTimes")
    return idle.value, kernel.value + user.value


class _MemoryStatusEx(ctypes.Structure):
    _fields_ = [
        ("dwLength", ctypes.c_ulong),
        ("dwMemoryLoad", ctypes.c_ulong),
        ("ullTotalPhys", ctypes.c_ulonglong),
        ("ullAvailPhys", ctypes.c_ulonglong),
        ("ullTotalPageFile", ctypes.c_ulonglong),
        ("ullAvailPageFile", ctypes.c_ulonglong),
        ("ullTotalVirtual", ctypes.c_ulonglong),
        ("ullAvailVirtual", ctypes.c_ulonglong),
        ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
    ]


def memory_usage():
    """Mémoire physique utilisée, en %."""
    status = _MemoryStatusEx()
    status.dwLength = ctypes.sizeof(_MemoryStatusEx)
    if not _kernel32().GlobalMemoryStatusEx(ctypes.byref(status)):
        _fail("GlobalMemoryStatusEx")
    return memory_percent(status.ullTotalPhys, status.ullAvailPhys)


def disk_usage(path=None):
    """Espace utilisé du volume qui contient path, en %, comme « df »."""
    target = normalize_disk_path(path)
    available, total, total_free = ctypes.c_ulonglong(), ctypes.c_ulonglong(), ctypes.c_ulonglong()
    kernel32 = _kernel32()
    kernel32.GetDiskFreeSpaceExW.argtypes = [
        ctypes.c_wchar_p,
        ctypes.POINTER(ctypes.c_ulonglong),
        ctypes.POINTER(ctypes.c_ulonglong),
        ctypes.POINTER(ctypes.c_ulonglong),
    ]
    if not kernel32.GetDiskFreeSpaceExW(target, ctypes.byref(available), ctypes.byref(total), ctypes.byref(total_free)):
        _fail("GetDiskFreeSpaceExW(%s)" % target)
    return df_percent(total.value, total_free.value, available.value)


def uptime_seconds():
    """Durée depuis le démarrage de Windows, en secondes."""
    kernel32 = _kernel32()
    kernel32.GetTickCount64.restype = ctypes.c_ulonglong
    return kernel32.GetTickCount64() / 1000.0


class WindowsCollector:
    """Produit un relevé complet ; le CPU se calcule entre deux appels successifs, comme sous Linux."""

    def __init__(
        self,
        disk_path=None,
        clock=utc_now,
        cpu_reader=read_cpu_times,
        memory_reader=memory_usage,
        disk_reader=disk_usage,
        uptime_reader=uptime_seconds,
    ):
        self.disk_path = normalize_disk_path(disk_path)
        self.clock = clock
        self.cpu_reader = cpu_reader
        self.memory_reader = memory_reader
        self.disk_reader = disk_reader
        self.uptime_reader = uptime_reader
        self.previous_cpu = cpu_reader()

    def collect(self):
        now = self.clock()
        measures = []
        # CPU et mémoire illisibles : OSError remonte et le relevé entier est abandonné (journalisé
        # par l'agent), exactement comme une erreur de lecture de /proc sous Linux.
        current_cpu = self.cpu_reader()
        measures.append(measure("cpu.usage", cpu_usage(self.previous_cpu, current_cpu), now))
        self.previous_cpu = current_cpu
        measures.append(measure("memory.usage", self.memory_reader(), now))
        try:
            measures.append(measure("disk.usage", self.disk_reader(self.disk_path), now))
        except OSError:
            # Lecteur absent ou illisible : mesure inexploitable (BAD), jamais un 0 % présenté comme bon.
            measures.append(measure("disk.usage", 0, now, quality="BAD"))
        measures.append(measure("availability", 100, now))
        uptime = self.uptime_reader()
        if uptime > MAX_UPTIME_SECONDS:
            measures.append(measure("uptime.seconds", MAX_UPTIME_SECONDS, now, quality="DEGRADED", unit="s"))
        else:
            measures.append(measure("uptime.seconds", uptime, now, unit="s"))
        return measures
