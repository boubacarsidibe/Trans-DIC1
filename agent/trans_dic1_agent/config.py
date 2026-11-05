"""Configuration de l'agent : fichier INI, surchargé par les variables d'environnement.

Le jeton n'est jamais écrit dans le fichier de configuration lui-même : il est lu dans un fichier
dédié (droits 600 sous Linux, ACL restreinte sous Windows) ou dans la variable
TRANS_DIC1_AGENT_TOKEN, et n'apparaît dans aucun message.
"""

import configparser
import os
import stat
import sys


def default_config_file(platform=None):
    """/etc/trans-dic1-agent/agent.conf sous Linux, %ProgramData%\\Trans-DIC1\\agent\\agent.conf sous Windows."""
    platform = sys.platform if platform is None else platform
    if platform == "win32":
        root = os.environ.get("ProgramData", "C:\\ProgramData")
        return os.path.join(root, "Trans-DIC1", "agent", "agent.conf")
    return "/etc/trans-dic1-agent/agent.conf"


def default_disk_path(platform=None):
    """Système de fichiers mesuré par défaut : « / » sous Linux, le lecteur système sous Windows."""
    platform = sys.platform if platform is None else platform
    if platform == "win32":
        return os.environ.get("SystemDrive", "C:").rstrip("\\/") + "\\"
    return "/"


DEFAULT_CONFIG_FILE = default_config_file()

# Limites fixées par l'API (AgentMeasuresRequest) et par le contrôle de silence du backend (300 s).
MAX_BATCH = 500
MIN_INTERVAL = 10
MAX_INTERVAL = 240


class ConfigError(Exception):
    """Configuration invalide : l'agent refuse de démarrer."""


class Config:
    def __init__(
        self,
        server_url,
        token,
        interval=60,
        disk_path=None,
        timeout=10.0,
        max_retries=3,
        backoff=2.0,
        buffer_limit=2000,
        ca_file=None,
        allow_insecure_http=False,
        proc_root="/proc",
    ):
        self.server_url = server_url.rstrip("/")
        self.token = token
        self.interval = interval
        self.disk_path = disk_path or default_disk_path()
        self.timeout = timeout
        self.max_retries = max_retries
        self.backoff = backoff
        self.buffer_limit = buffer_limit
        self.ca_file = ca_file
        self.allow_insecure_http = allow_insecure_http
        self.proc_root = proc_root
        self.validate()

    @property
    def measures_url(self):
        return self.server_url + "/api/v1/agent/measures"

    def validate(self):
        if not self.server_url.startswith(("https://", "http://")):
            raise ConfigError("server_url doit commencer par https://")
        if self.server_url.startswith("http://") and not self.allow_insecure_http:
            raise ConfigError(
                "server_url doit utiliser HTTPS : le jeton circulerait en clair "
                "(allow_insecure_http=true uniquement pour un test local)"
            )
        if not self.token or len(self.token) < 20 or any(c.isspace() for c in self.token):
            raise ConfigError("jeton d'agent absent ou invalide")
        if not MIN_INTERVAL <= self.interval <= MAX_INTERVAL:
            raise ConfigError(
                "interval doit être compris entre %d et %d secondes" % (MIN_INTERVAL, MAX_INTERVAL)
            )
        if self.timeout <= 0 or self.max_retries < 0 or self.backoff < 0:
            raise ConfigError("timeout, max_retries et backoff doivent être positifs")
        if self.buffer_limit < MAX_BATCH:
            raise ConfigError("buffer_limit doit être d'au moins %d mesures" % MAX_BATCH)
        if self.ca_file and not os.path.isfile(self.ca_file):
            raise ConfigError("ca_file introuvable : %s" % self.ca_file)

    def __repr__(self):
        # Jamais le jeton, même dans une trace de débogage.
        return "Config(server_url=%r, interval=%r, disk_path=%r)" % (
            self.server_url,
            self.interval,
            self.disk_path,
        )


def read_token_file(path):
    """Lit le jeton ; refuse un fichier lisible par d'autres utilisateurs que son propriétaire."""
    try:
        mode = os.stat(path).st_mode
    except OSError:
        raise ConfigError("fichier de jeton introuvable : %s" % path)
    if sys.platform == "win32":
        _check_windows_acl(path)
    elif mode & (stat.S_IRWXG | stat.S_IRWXO):
        raise ConfigError("le fichier de jeton %s doit avoir les droits 600" % path)
    # Windows : un fichier écrit par le Bloc-notes ou PowerShell peut commencer par une marque BOM.
    encoding = "utf-8-sig" if sys.platform == "win32" else "utf-8"
    with open(path, encoding=encoding) as handle:
        return handle.read().strip()


def _check_windows_acl(path):
    """Windows : les bits Unix ne veulent rien dire, l'ACL NTFS est contrôlée (voir winacl)."""
    from . import winacl

    try:
        broad = winacl.broad_readers(path)
    except OSError as error:
        raise ConfigError("droits du fichier de jeton %s illisibles : %s" % (path, error.strerror or error))
    if broad:
        raise ConfigError(
            "le fichier de jeton %s est lisible par un groupe trop large (%s) : ne laisser que SYSTEM "
            "et Administrateurs (voir install.ps1)" % (path, ", ".join(broad))
        )


def _flag(value):
    return str(value).strip().lower() in ("1", "true", "yes", "oui")


def load(path=None, environ=None):
    """Construit la configuration depuis le fichier puis l'environnement (prioritaire)."""
    environ = os.environ if environ is None else environ
    path = path or environ.get("TRANS_DIC1_AGENT_CONFIG", default_config_file())
    parser = configparser.ConfigParser()
    if os.path.isfile(path):
        parser.read(path, encoding="utf-8-sig" if sys.platform == "win32" else "utf-8")
    section = parser["agent"] if parser.has_section("agent") else {}

    def get(name, default=None):
        return environ.get("TRANS_DIC1_AGENT_" + name.upper(), section.get(name, default))

    token = environ.get("TRANS_DIC1_AGENT_TOKEN")
    if not token:
        token_file = get("token_file")
        if not token_file:
            raise ConfigError("ni token_file ni TRANS_DIC1_AGENT_TOKEN : jeton absent")
        token = read_token_file(token_file)

    server_url = get("server_url")
    if not server_url:
        raise ConfigError("server_url absent")
    try:
        return Config(
            server_url=server_url,
            token=token,
            interval=int(get("interval", 60)),
            disk_path=get("disk_path") or None,
            timeout=float(get("timeout", 10)),
            max_retries=int(get("max_retries", 3)),
            backoff=float(get("backoff", 2)),
            buffer_limit=int(get("buffer_limit", 2000)),
            ca_file=get("ca_file") or None,
            allow_insecure_http=_flag(get("allow_insecure_http", "false")),
            proc_root=get("proc_root", "/proc"),
        )
    except ValueError as error:
        raise ConfigError("valeur numérique invalide : %s" % error)
