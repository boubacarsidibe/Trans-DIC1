"""Relevé des métriques d'un serveur Windows, sans dépendance : appels kernel32 par ctypes.

Mêmes clés, mêmes unités (%) et même format de lot que la collecte Linux (cpu.usage, memory.usage,
disk.usage, availability) : la plateforme ne fait aucune différence entre les deux.

- CPU : GetSystemTimes. Le noyau compte le temps d'inactivité dans le temps « noyau » ; le total est
  donc noyau + utilisateur, et l'occupation se calcule entre deux relevés successifs.
- Mémoire : GlobalMemoryStatusEx (mémoire physique totale et disponible).
- Disque : shutil.disk_usage sur le lecteur mesuré.
"""

import shutil

from .metrics import assemble, utc_now


class Win32Api:
    """Accès minimal à kernel32. ctypes est importé à l'usage : le module se charge aussi sous Linux."""

    def __init__(self):
        import ctypes
        from ctypes import wintypes

        class FileTime(ctypes.Structure):
            _fields_ = [("low", wintypes.DWORD), ("high", wintypes.DWORD)]

        class MemoryStatus(ctypes.Structure):
            _fields_ = [
                ("length", wintypes.DWORD),
                ("memory_load", wintypes.DWORD),
                ("total_phys", ctypes.c_ulonglong),
                ("avail_phys", ctypes.c_ulonglong),
                ("total_page_file", ctypes.c_ulonglong),
                ("avail_page_file", ctypes.c_ulonglong),
                ("total_virtual", ctypes.c_ulonglong),
                ("avail_virtual", ctypes.c_ulonglong),
                ("avail_extended_virtual", ctypes.c_ulonglong),
            ]

        self._ctypes = ctypes
        self._FileTime = FileTime
        self._MemoryStatus = MemoryStatus
        self._kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        self._kernel32.GetSystemTimes.argtypes = [ctypes.POINTER(FileTime)] * 3
        self._kernel32.GetSystemTimes.restype = wintypes.BOOL
        self._kernel32.GlobalMemoryStatusEx.argtypes = [ctypes.POINTER(MemoryStatus)]
        self._kernel32.GlobalMemoryStatusEx.restype = wintypes.BOOL

    def _fail(self, name):
        code = self._ctypes.get_last_error()
        raise OSError("%s a échoué (code Windows %d)" % (name, code))

    def system_times(self):
        """(temps inactif, temps total) cumulés, en intervalles de 100 ns."""
        idle, kernel, user = self._FileTime(), self._FileTime(), self._FileTime()
        ref = self._ctypes.byref
        if not self._kernel32.GetSystemTimes(ref(idle), ref(kernel), ref(user)):
            self._fail("GetSystemTimes")

        def ticks(value):
            return (value.high << 32) | value.low

        return ticks(idle), ticks(kernel) + ticks(user)

    def memory_status(self):
        """(mémoire physique totale, mémoire physique disponible), en octets."""
        status = self._MemoryStatus()
        status.length = self._ctypes.sizeof(self._MemoryStatus)
        if not self._kernel32.GlobalMemoryStatusEx(self._ctypes.byref(status)):
            self._fail("GlobalMemoryStatusEx")
        return status.total_phys, status.avail_phys


def memory_usage(api):
    """Mémoire physique utilisée, en %."""
    total, available = api.memory_status()
    return 100.0 * (total - available) / total if total else 0.0


def disk_usage(path):
    """Espace utilisé du lecteur, en %."""
    usage = shutil.disk_usage(path)
    return 100.0 * usage.used / usage.total if usage.total else 0.0


def default_disk_path():
    import os

    return os.environ.get("SystemDrive", "C:") + "\\"


class WindowsCollector:
    """Produit un relevé complet ; le CPU se calcule entre deux appels successifs."""

    def __init__(self, api=None, disk_path=None, clock=utc_now):
        self.api = api or Win32Api()
        self.disk_path = disk_path or default_disk_path()
        self.clock = clock
        self.previous_cpu = self.api.system_times()

    def collect(self):
        current_cpu = self.api.system_times()
        previous, self.previous_cpu = self.previous_cpu, current_cpu
        return assemble(
            self.clock(),
            previous,
            current_cpu,
            memory=lambda: memory_usage(self.api),
            disk=lambda: disk_usage(self.disk_path),
        )
