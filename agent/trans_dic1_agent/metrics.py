"""Lecture des métriques système Linux : /proc pour le CPU et la mémoire, statvfs pour le disque.

Les clés et unités sont celles de la plateforme (cpu.usage, memory.usage, disk.usage,
availability, en %), déjà utilisées par la collecte SNMP et la source simulée.
"""

import datetime
import os


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc)


def iso(moment):
    """Horodatage ISO 8601 en UTC, au format attendu par l'API (Instant Java)."""
    return moment.strftime("%Y-%m-%dT%H:%M:%S.") + "%03dZ" % (moment.microsecond // 1000)


def measure(key, value, collected_at, quality="GOOD"):
    return {
        "key": key,
        "value": round(float(value), 2),
        "unit": "%",
        "quality": quality,
        "collectedAt": iso(collected_at),
    }


def read_cpu_times(proc_root="/proc"):
    """Temps CPU cumulés (inactif, total) de la ligne « cpu » de /proc/stat."""
    with open(os.path.join(proc_root, "stat"), encoding="ascii") as handle:
        for line in handle:
            if line.startswith("cpu "):
                values = [int(v) for v in line.split()[1:]]
                # user nice system idle iowait irq softirq steal (guest est déjà compté dans user)
                idle = values[3] + (values[4] if len(values) > 4 else 0)
                total = sum(values[:8])
                return idle, total
    raise ValueError("ligne cpu absente de /proc/stat")


def cpu_usage(previous, current):
    """Taux d'occupation CPU entre deux relevés, en %."""
    idle = current[0] - previous[0]
    total = current[1] - previous[1]
    if total <= 0:
        return 0.0
    return max(0.0, min(100.0, 100.0 * (total - idle) / total))


def memory_usage(proc_root="/proc"):
    """Mémoire utilisée, en % : (MemTotal - MemAvailable) / MemTotal."""
    info = {}
    with open(os.path.join(proc_root, "meminfo"), encoding="ascii") as handle:
        for line in handle:
            name, _, rest = line.partition(":")
            info[name] = int(rest.split()[0])
    total = info["MemTotal"]
    available = info.get("MemAvailable", info.get("MemFree", 0))
    return 100.0 * (total - available) / total if total else 0.0


def disk_usage(path="/"):
    """Espace utilisé du système de fichiers, en %, comme « df » (réserve root exclue)."""
    stats = os.statvfs(path)
    used = (stats.f_blocks - stats.f_bfree) * stats.f_frsize
    usable = used + stats.f_bavail * stats.f_frsize
    return 100.0 * used / usable if usable else 0.0


class Collector:
    """Produit un relevé complet ; le CPU se calcule entre deux appels successifs."""

    def __init__(self, proc_root="/proc", disk_path="/", clock=utc_now):
        self.proc_root = proc_root
        self.disk_path = disk_path
        self.clock = clock
        self.previous_cpu = read_cpu_times(proc_root)

    def collect(self):
        now = self.clock()
        measures = []
        current_cpu = read_cpu_times(self.proc_root)
        measures.append(measure("cpu.usage", cpu_usage(self.previous_cpu, current_cpu), now))
        self.previous_cpu = current_cpu
        measures.append(measure("memory.usage", memory_usage(self.proc_root), now))
        try:
            measures.append(measure("disk.usage", disk_usage(self.disk_path), now))
        except OSError:
            # Disque illisible : la mesure est signalée inexploitable plutôt que perdue en silence.
            measures.append(measure("disk.usage", 0, now, quality="BAD"))
        # L'agent répond : le serveur est disponible. Son silence est détecté côté plateforme.
        measures.append(measure("availability", 100, now))
        return measures
