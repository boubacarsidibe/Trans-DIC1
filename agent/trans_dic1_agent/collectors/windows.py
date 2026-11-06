"""Mesures étendues sous Windows, sans dépendance : kernel32, psapi, iphlpapi, pdh et advapi32.

Les compteurs disque passent par PDH avec leurs noms anglais (PdhAddEnglishCounterW), pour marcher
sur un Windows français. La requête PDH reste ouverte : un compteur de débit se calcule entre deux
lectures. Le réseau vient de GetIfTable2 (compteurs 64 bits, pas de débordement à 1 Gbit/s).
"""

import ctypes
import os
import platform
import shutil
import socket
import struct

from ..metrics_windows import WindowsCollector
from .common import (
    MAX_INSTANCES,
    ActiveLinks,
    SERVICE_NAME,
    FullCollector,
    Rates,
    instance_key,
    top_processes,
    value,
)

# Décalages dans MIB_IF_ROW2 (taille 1352 octets) ; la table commence à 8 octets.
ROW_SIZE = 1352
ROW_ALIAS = 28
ROW_TYPE = 1128
ROW_FLAGS = 1152
ROW_OPER = 1156
ROW_IN_OCTETS = 1208
ROW_IN_ERRORS = 1240
ROW_OUT_OCTETS = 1280
ROW_OUT_ERRORS = 1312
IF_ETHERNET, IF_WIFI = 6, 71
FLAG_HARDWARE, FLAG_FILTER = 0x01, 0x02
OPER_UP = 1

DRIVE_FIXED = 3
PDH_FMT_DOUBLE = 0x00000200
SERVICE_RUNNING = 4
TH32CS_SNAPPROCESS = 2
PROCESS_QUERY_LIMITED = 0x1000
INVALID_HANDLE = ctypes.c_void_p(-1).value


def parse_if_table(raw):
    """Cartes physiques d'une table MIB_IF_TABLE2 : [(nom, connectée, in, out, erreurs)]."""
    count = struct.unpack_from("<I", raw, 0)[0]
    found = []
    for index in range(count):
        start = 8 + index * ROW_SIZE
        kind = struct.unpack_from("<I", raw, start + ROW_TYPE)[0]
        flags = raw[start + ROW_FLAGS]
        if kind not in (IF_ETHERNET, IF_WIFI) or not flags & FLAG_HARDWARE or flags & FLAG_FILTER:
            continue
        alias = raw[start + ROW_ALIAS : start + ROW_ALIAS + 514].decode("utf-16-le").split("\0", 1)[0]
        oper = struct.unpack_from("<I", raw, start + ROW_OPER)[0]
        received = struct.unpack_from("<Q", raw, start + ROW_IN_OCTETS)[0]
        sent = struct.unpack_from("<Q", raw, start + ROW_OUT_OCTETS)[0]
        errors = struct.unpack_from("<Q", raw, start + ROW_IN_ERRORS)[0]
        errors += struct.unpack_from("<Q", raw, start + ROW_OUT_ERRORS)[0]
        found.append((alias, oper == OPER_UP, received, sent, errors))
    return found[:MAX_INSTANCES]


class Win32Extended:  # pragma: no cover - appels système, exercés par les tests réels sous Windows
    """Appels système de la collecte étendue."""

    def __init__(self):
        from ctypes import wintypes

        self._wintypes = wintypes
        self.kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        self.psapi = ctypes.WinDLL("psapi", use_last_error=True)
        self.iphlpapi = ctypes.WinDLL("iphlpapi", use_last_error=True)
        self.advapi32 = ctypes.WinDLL("advapi32", use_last_error=True)
        self.pdh = ctypes.WinDLL("pdh")
        self.kernel32.GetTickCount64.restype = ctypes.c_ulonglong
        self.kernel32.OpenProcess.restype = ctypes.c_void_p
        self.kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        self.kernel32.CloseHandle.argtypes = [ctypes.c_void_p]
        self.kernel32.CreateToolhelp32Snapshot.restype = ctypes.c_void_p
        self.kernel32.CreateToolhelp32Snapshot.argtypes = [wintypes.DWORD, wintypes.DWORD]
        self.advapi32.OpenSCManagerW.restype = ctypes.c_void_p
        self.advapi32.OpenSCManagerW.argtypes = [wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.DWORD]
        self.advapi32.OpenServiceW.restype = ctypes.c_void_p
        self.advapi32.OpenServiceW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR, wintypes.DWORD]
        self.advapi32.CloseServiceHandle.argtypes = [ctypes.c_void_p]
        self.advapi32.QueryServiceStatus.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
        self.query = ctypes.c_void_p()
        status = self.pdh.PdhOpenQueryW(None, None, ctypes.byref(self.query))
        if status != 0:
            raise OSError("PdhOpenQueryW a échoué (code %#x)" % (status & 0xFFFFFFFF))
        self.counters = {}

    def uptime(self):
        return self.kernel32.GetTickCount64() / 1000.0

    def performance(self):
        """(processus, threads, charge validée, limite de charge), en nombre et en octets."""

        class Info(ctypes.Structure):
            _fields_ = [
                ("cb", ctypes.c_ulong),
                ("commit_total", ctypes.c_size_t),
                ("commit_limit", ctypes.c_size_t),
                ("commit_peak", ctypes.c_size_t),
                ("physical_total", ctypes.c_size_t),
                ("physical_available", ctypes.c_size_t),
                ("system_cache", ctypes.c_size_t),
                ("kernel_total", ctypes.c_size_t),
                ("kernel_paged", ctypes.c_size_t),
                ("kernel_nonpaged", ctypes.c_size_t),
                ("page_size", ctypes.c_size_t),
                ("handles", ctypes.c_ulong),
                ("processes", ctypes.c_ulong),
                ("threads", ctypes.c_ulong),
            ]

        info = Info()
        info.cb = ctypes.sizeof(Info)
        if not self.psapi.GetPerformanceInfo(ctypes.byref(info), info.cb):
            raise OSError("GetPerformanceInfo a échoué")
        return {
            "processes": info.processes,
            "threads": info.threads,
            "commit_used": info.commit_total * info.page_size,
            "commit_limit": info.commit_limit * info.page_size,
            "physical_available": info.physical_available * info.page_size,
            "physical_total": info.physical_total * info.page_size,
        }

    def fixed_drives(self):
        mask = self.kernel32.GetLogicalDrives()
        found = []
        for index in range(26):
            if mask & (1 << index):
                letter = "%s:" % chr(65 + index)
                if self.kernel32.GetDriveTypeW(letter + "\\") == DRIVE_FIXED:
                    found.append(letter)
        return found[:MAX_INSTANCES]

    def rate_counter(self, path):
        """Valeur d'un compteur PDH ; None tant qu'il n'a pas deux lectures."""
        if path not in self.counters:
            handle = ctypes.c_void_p()
            status = self.pdh.PdhAddEnglishCounterW(self.query, ctypes.c_wchar_p(path), None, ctypes.byref(handle))
            if status != 0:
                raise OSError("compteur PDH refusé (code %#x)" % (status & 0xFFFFFFFF))
            self.counters[path] = handle
            return None

        class Value(ctypes.Structure):
            _fields_ = [("status", ctypes.c_ulong), ("double", ctypes.c_double)]

        result = Value()
        status = self.pdh.PdhGetFormattedCounterValue(
            self.counters[path], PDH_FMT_DOUBLE, None, ctypes.byref(result)
        )
        return result.double if status == 0 else None

    def collect_counters(self):
        self.pdh.PdhCollectQueryData(self.query)

    def if_table(self):
        pointer = ctypes.c_void_p()
        status = self.iphlpapi.GetIfTable2(ctypes.byref(pointer))
        if status != 0:
            raise OSError("GetIfTable2 a échoué (code %d)" % status)
        try:
            count = ctypes.cast(pointer, ctypes.POINTER(ctypes.c_ulong)).contents.value
            return ctypes.string_at(pointer, 8 + count * ROW_SIZE)
        finally:
            self.iphlpapi.FreeMibTable(pointer)

    def service_running(self, name):
        manager = self.advapi32.OpenSCManagerW(None, None, 0x0001)
        if not manager:
            raise OSError("gestionnaire de services inaccessible")
        try:
            handle = self.advapi32.OpenServiceW(manager, name, 0x0004)
            if not handle:
                return False
            try:
                status = (ctypes.c_ulong * 7)()
                if not self.advapi32.QueryServiceStatus(handle, status):
                    return False
                return status[1] == SERVICE_RUNNING
            finally:
                self.advapi32.CloseServiceHandle(handle)
        finally:
            self.advapi32.CloseServiceHandle(manager)

    def processes(self):
        """[(pid, nom, temps CPU en s, mémoire en octets ou None)] ; illisibles : temps None."""

        class Entry(ctypes.Structure):
            _fields_ = [
                ("size", ctypes.c_ulong),
                ("usage", ctypes.c_ulong),
                ("pid", ctypes.c_ulong),
                ("heap", ctypes.c_size_t),
                ("module", ctypes.c_ulong),
                ("threads", ctypes.c_ulong),
                ("parent", ctypes.c_ulong),
                ("priority", ctypes.c_long),
                ("flags", ctypes.c_ulong),
                ("name", ctypes.c_wchar * 260),
            ]

        class Counters(ctypes.Structure):
            _fields_ = [
                ("cb", ctypes.c_ulong),
                ("faults", ctypes.c_ulong),
                ("peak_ws", ctypes.c_size_t),
                ("ws", ctypes.c_size_t),
                ("q1", ctypes.c_size_t),
                ("q2", ctypes.c_size_t),
                ("q3", ctypes.c_size_t),
                ("q4", ctypes.c_size_t),
                ("page", ctypes.c_size_t),
                ("peak_page", ctypes.c_size_t),
            ]

        snap = self.kernel32.CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0)
        if snap in (None, INVALID_HANDLE):
            raise OSError("CreateToolhelp32Snapshot a échoué")
        entry = Entry()
        entry.size = ctypes.sizeof(Entry)
        rows = []
        try:
            more = self.kernel32.Process32FirstW(snap, ctypes.byref(entry))
            while more:
                rows.append(self._process_row(entry.pid, entry.name, Counters))
                more = self.kernel32.Process32NextW(snap, ctypes.byref(entry))
        finally:
            self.kernel32.CloseHandle(snap)
        return rows

    def _process_row(self, pid, name, counters_type):
        handle = self.kernel32.OpenProcess(PROCESS_QUERY_LIMITED, False, pid)
        if not handle:
            return (pid, name, None, None)
        try:
            times = (ctypes.c_ulonglong * 4)()
            cpu = None
            if self.kernel32.GetProcessTimes(ctypes.c_void_p(handle), *[ctypes.byref(times, i * 8) for i in range(4)]):
                cpu = (times[2] + times[3]) / 10_000_000.0
            counters = counters_type()
            counters.cb = ctypes.sizeof(counters_type)
            memory = None
            if self.kernel32.K32GetProcessMemoryInfo(ctypes.c_void_p(handle), ctypes.byref(counters), counters.cb):
                memory = counters.ws
            return (pid, name, cpu, memory)
        finally:
            self.kernel32.CloseHandle(handle)


class WindowsFamilies:
    def __init__(self, api, services=(), rates=None, disk_usage=shutil.disk_usage):
        self.api = api
        self.services = services
        self.rates = rates or Rates()
        self.disk_usage = disk_usage
        self.processes = Rates()
        self.links = ActiveLinks()

    def system(self, at):
        info = self.api.performance()
        return [
            value("system.uptime", self.api.uptime(), "s", at),
            value("system.processes", info["processes"], "count", at),
            value("system.threads", info["threads"], "count", at),
        ]

    def memory(self, at):
        info = self.api.performance()
        measures = [value("memory.available", info["physical_available"], "B", at)]
        if info["commit_limit"]:
            measures.append(value("swap.usage", 100.0 * info["commit_used"] / info["commit_limit"], "%", at))
        return measures

    def disks(self, at):
        measures = []
        drives = self.api.fixed_drives()
        self.api.collect_counters()
        for letter in drives:
            usage = self.disk_usage(letter + "\\")
            if usage.total:
                measures.append(value(instance_key("disk.usage", letter), 100.0 * usage.used / usage.total, "%", at))
            measures.append(value(instance_key("disk.free", letter), usage.free, "B", at))
            for family, counter in (("disk.read", "Disk Read Bytes/sec"), ("disk.write", "Disk Write Bytes/sec")):
                rate = self.api.rate_counter("\\LogicalDisk(%s)\\%s" % (letter, counter))
                if rate is not None:
                    measures.append(value(instance_key(family, letter), rate, "B/s", at))
        return measures

    def network(self, at):
        measures = []
        for name, up, received, sent, errors in parse_if_table(self.api.if_table()):
            if not self.links.follow(name, up):
                continue
            measures.append(value(instance_key("net.up", name), 1 if up else 0, "state", at))
            for family, counter, factor, unit in (
                ("net.in", received, 8, "bit/s"),
                ("net.out", sent, 8, "bit/s"),
                ("net.errors", errors, 1, "/s"),
            ):
                rate = self.rates.rate(family + name, counter)
                if rate is not None:
                    measures.append(value(instance_key(family, name), rate * factor, unit, at))
        return measures

    def service_states(self, at):
        return [
            value(instance_key("service.up", name), 1 if self.api.service_running(name) else 0, "state", at)
            for name in self.services
            if SERVICE_NAME.match(name)
        ]

    def snapshot(self):
        info = self.api.performance()
        rows, partial = [], False
        seen = set()
        for pid, name, cpu_seconds, memory in self.api.processes():
            if pid == 0:
                continue
            if cpu_seconds is None or memory is None:
                partial = True
                continue
            seen.add(pid)
            rate = self.processes.rate(pid, cpu_seconds)
            rows.append((name, pid, 100.0 * (rate or 0.0) / (os.cpu_count() or 1), memory))
        self.processes.keep(seen)
        return {
            "hostname": socket.gethostname(),
            "os": "Windows",
            "osVersion": platform.version(),
            "architecture": platform.machine(),
            "cpuCount": os.cpu_count() or 0,
            "memoryTotal": info["physical_total"],
            "partial": partial,
            "processes": top_processes(rows),
        }


def create(config, clock):
    api = Win32Extended()
    families = WindowsFamilies(api, config.services)
    base = WindowsCollector(disk_path=config.disk_path, clock=clock)

    def cpu_detail(at):
        # cpu.user et cpu.system : mêmes temps que cpu.usage, découpés entre noyau et utilisateur.
        idle, kernel, user = base.api.split_times()
        rate_total = families.rates.rate("cpu.total", kernel + user)
        rate_user = families.rates.rate("cpu.user", user)
        rate_kernel = families.rates.rate("cpu.kernel", kernel - idle)
        if not rate_total:
            return []
        return [
            value("cpu.user", 100.0 * (rate_user or 0.0) / rate_total, "%", at),
            value("cpu.system", 100.0 * (rate_kernel or 0.0) / rate_total, "%", at),
        ]

    collector = FullCollector(
        base,
        [
            ("système", families.system),
            ("cpu", cpu_detail),
            ("mémoire", families.memory),
            ("disques", families.disks),
            ("réseau", families.network),
            ("services", families.service_states),
        ],
        clock,
    )
    collector.snapshot = families.snapshot
    return collector
