"""Mesures étendues sous Linux : /proc, /sys, statvfs et systemctl."""

import os
import platform
import socket
import subprocess

from ..metrics import Collector
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

PSEUDO_FS = {
    "autofs", "binfmt_misc", "bpf", "cgroup", "cgroup2", "configfs", "debugfs", "devpts", "devtmpfs",
    "efivarfs", "fuse.gvfsd-fuse", "fusectl", "hugetlbfs", "mqueue", "nsfs", "overlay", "proc",
    "pstore", "ramfs", "rpc_pipefs", "securityfs", "selinuxfs", "squashfs", "sysfs", "tmpfs", "tracefs",
}
SECTOR = 512


def read_text(path):
    with open(path, encoding="ascii", errors="replace") as handle:
        return handle.read()


def mounts(proc_root):
    """Systèmes de fichiers réels en lecture-écriture : [(périphérique, point de montage)]."""
    found, seen = [], set()
    for line in read_text(os.path.join(proc_root, "mounts")).splitlines():
        fields = line.split()
        if len(fields) < 4 or fields[2] in PSEUDO_FS:
            continue
        device, mount = fields[0], fields[1].replace("\\040", " ")
        if "ro" in fields[3].split(",") or device in seen:
            continue
        seen.add(device)
        found.append((device, mount))
    return found[:MAX_INSTANCES]


class LinuxFamilies:
    def __init__(self, proc_root="/proc", sys_root="/sys", services=(), rates=None, run=subprocess.run):
        self.proc = proc_root
        self.sys = sys_root
        self.services = services
        self.rates = rates or Rates()
        self.run = run
        self.processes = Rates()
        self.links = ActiveLinks()
        self.cpu_before = None

    def _proc(self, name):
        return os.path.join(self.proc, name)

    def system(self, at):
        uptime = float(read_text(self._proc("uptime")).split()[0])
        running_total = read_text(self._proc("loadavg")).split()[3]
        pids = [d for d in os.listdir(self.proc) if d.isdigit()]
        return [
            value("system.uptime", uptime, "s", at),
            value("system.processes", len(pids), "count", at),
            value("system.threads", int(running_total.split("/")[1]), "count", at),
        ]

    def cpu(self, at):
        line = read_text(self._proc("stat")).splitlines()[0].split()[1:]
        ticks = [int(v) for v in line]
        before, self.cpu_before = self.cpu_before, ticks
        load = read_text(self._proc("loadavg")).split()
        measures = [
            value("cpu.load1", float(load[0]), "load", at),
            value("cpu.load5", float(load[1]), "load", at),
            value("cpu.load15", float(load[2]), "load", at),
        ]
        if before is None:
            return measures
        deltas = [c - b for c, b in zip(ticks, before)]
        total = sum(deltas[:8])
        if total <= 0:
            return measures
        user = deltas[0] + deltas[1]
        measures += [
            value("cpu.user", 100.0 * user / total, "%", at),
            value("cpu.system", 100.0 * deltas[2] / total, "%", at),
            value("cpu.iowait", 100.0 * deltas[4] / total, "%", at),
        ]
        return measures

    def memory(self, at):
        info = {}
        for line in read_text(self._proc("meminfo")).splitlines():
            name, _, rest = line.partition(":")
            if rest.strip():
                info[name] = int(rest.split()[0]) * 1024
        available = info.get("MemAvailable", info.get("MemFree", 0))
        measures = [value("memory.available", available, "B", at)]
        swap = info.get("SwapTotal", 0)
        if swap:
            measures.append(value("swap.usage", 100.0 * (swap - info.get("SwapFree", 0)) / swap, "%", at))
        return measures

    def disks(self, at):
        measures = []
        counters = {}
        for line in read_text(self._proc("diskstats")).splitlines():
            fields = line.split()
            if len(fields) >= 10:
                counters[fields[2]] = (int(fields[5]) * SECTOR, int(fields[9]) * SECTOR)
        for device, mount in mounts(self.proc):
            stats = os.statvfs(mount)
            used = (stats.f_blocks - stats.f_bfree) * stats.f_frsize
            free = stats.f_bavail * stats.f_frsize
            usable = used + free
            if usable:
                measures.append(value(instance_key("disk.usage", mount), 100.0 * used / usable, "%", at))
            measures.append(value(instance_key("disk.free", mount), free, "B", at))
            name = os.path.basename(device)
            if name in counters:
                for family, counter in zip(("disk.read", "disk.write"), counters[name]):
                    rate = self.rates.rate(family + name, counter)
                    if rate is not None:
                        measures.append(value(instance_key(family, mount), rate, "B/s", at))
        return measures

    def network(self, at):
        measures = []
        lines = read_text(self._proc("net/dev")).splitlines()[2:]
        for line in lines[: MAX_INSTANCES + 1]:
            name, _, rest = line.partition(":")
            name, fields = name.strip(), rest.split()
            if name == "lo" or len(fields) < 11:
                continue
            state = self._operstate(name)
            if not self.links.follow(name, state):
                continue
            measures.append(value(instance_key("net.up", name), 1 if state else 0, "state", at))
            received, sent = int(fields[0]), int(fields[8])
            errors = int(fields[2]) + int(fields[10])
            for family, counter, factor, unit in (
                ("net.in", received, 8, "bit/s"),
                ("net.out", sent, 8, "bit/s"),
                ("net.errors", errors, 1, "/s"),
            ):
                rate = self.rates.rate(family + name, counter)
                if rate is not None:
                    measures.append(value(instance_key(family, name), rate * factor, unit, at))
        return measures

    def _operstate(self, name):
        try:
            return read_text(os.path.join(self.sys, "class/net", name, "operstate")).strip() in ("up", "unknown")
        except OSError:
            return False

    def service_states(self, at):
        measures = []
        for name in self.services:
            if not SERVICE_NAME.match(name):
                continue
            result = self.run(["systemctl", "is-active", "--quiet", name], timeout=5, capture_output=True)
            measures.append(value(instance_key("service.up", name), 1 if result.returncode == 0 else 0, "state", at))
        return measures

    def snapshot(self):
        ticks = os.sysconf("SC_CLK_TCK")
        cores = os.cpu_count() or 1
        total_memory = int(read_text(self._proc("meminfo")).split()[1]) * 1024
        rows = []
        for pid in (d for d in os.listdir(self.proc) if d.isdigit()):
            try:
                raw = read_text(os.path.join(self.proc, pid, "stat"))
                name = raw[raw.index("(") + 1 : raw.rindex(")")]
                fields = raw[raw.rindex(")") + 2 :].split()
                cpu_seconds = (int(fields[11]) + int(fields[12])) / ticks
                rss = int(read_text(os.path.join(self.proc, pid, "statm")).split()[1]) * os.sysconf("SC_PAGE_SIZE")
            except (OSError, ValueError, IndexError):
                continue
            rate = self.processes.rate(pid, cpu_seconds)
            rows.append((name, int(pid), 100.0 * (rate or 0.0) / cores, rss))
        self.processes.keep({str(row[1]) for row in rows})
        return {
            "hostname": socket.gethostname(),
            "os": "Linux",
            "osVersion": platform.release(),
            "architecture": platform.machine(),
            "cpuCount": os.cpu_count() or 0,
            "memoryTotal": total_memory,
            "partial": False,
            "processes": top_processes(rows),
        }


def create(config, clock):
    families = LinuxFamilies(config.proc_root, config.sys_root, config.services)
    base = Collector(proc_root=config.proc_root, disk_path=config.disk_path, clock=clock)
    collector = FullCollector(
        base,
        [
            ("système", families.system),
            ("cpu", families.cpu),
            ("mémoire", families.memory),
            ("disques", families.disks),
            ("réseau", families.network),
            ("services", families.service_states),
        ],
        clock,
    )
    collector.snapshot = families.snapshot
    return collector

