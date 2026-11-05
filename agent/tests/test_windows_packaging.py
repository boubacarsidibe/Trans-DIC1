"""Paquet Windows : fonctions pures de common.ps1, construction du zip, install.ps1 et uninstall.ps1
en -DryRun (aucune modification du système). Ignoré hors Windows ou sans Windows PowerShell."""

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

from tests.support import TOKEN

AGENT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PACKAGING = os.path.join(AGENT_ROOT, "packaging", "windows")
COMMON = os.path.join(PACKAGING, "common.ps1")
POWERSHELL = shutil.which("powershell.exe") if sys.platform == "win32" else None


def run_powershell(arguments, timeout=120):
    completed = subprocess.run(
        [POWERSHELL, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass"] + arguments,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout,
    )
    # Windows PowerShell 5.1 écrit selon [Console]::OutputEncoding : UTF-8 si le système l'a activé,
    # sinon la page de code OEM de la console.
    try:
        return completed.returncode, completed.stdout.decode("utf-8")
    except UnicodeDecodeError:
        return completed.returncode, completed.stdout.decode("oem", "replace")


def call(expression):
    """Évalue une expression après avoir chargé common.ps1 ; renvoie toujours une liste (JSON)."""
    script = ". '%s'; $r = & { %s }; ConvertTo-Json -InputObject @($r) -Depth 4 -Compress" % (COMMON, expression)
    code, output = run_powershell(["-Command", script])
    if code != 0:
        raise AssertionError(output)
    return json.loads(output.strip())


@unittest.skipUnless(POWERSHELL, "Windows PowerShell requis")
class CommonFunctionsTests(unittest.TestCase):
    def test_disposition_des_dossiers(self):
        layout = call("Get-AgentLayout -ProgramFiles 'C:\\PF' -ProgramData 'C:\\PD'")[0]
        self.assertEqual(layout["AppDir"], "C:\\PF\\Trans-DIC1\\agent")
        self.assertEqual(layout["ConfigFile"], "C:\\PD\\Trans-DIC1\\agent\\agent.conf")
        self.assertEqual(layout["TokenFile"], "C:\\PD\\Trans-DIC1\\agent\\token")
        self.assertEqual(layout["LogFile"], "C:\\PD\\Trans-DIC1\\agent\\logs\\agent.log")
        self.assertEqual(layout["TaskName"], "Trans-DIC1 Agent")

    def test_controles_d_url_de_python_et_de_version(self):
        result = call(
            "Test-ServerUrl ''; Test-ServerUrl 'https://s.example'; Test-ServerUrl 'http://s.example'; Test-ServerUrl 'https://';"
            "Test-PythonCandidate 'C:\\Python312\\python.exe';"
            "Test-PythonCandidate 'C:\\Users\\u\\AppData\\Local\\Microsoft\\WindowsApps\\python.exe';"
            "Test-PythonCandidate 'python.exe';"
            "Test-PythonVersionText '3.8'; Test-PythonVersionText '3.12'; Test-PythonVersionText '3.7'; Test-PythonVersionText 'x'"
        )
        self.assertEqual(result, [True, True, False, False, True, False, False, True, True, False, False])

    def test_configuration_creee_depuis_le_modele_windows(self):
        template = os.path.join(PACKAGING, "agent.conf.example").replace("'", "''")
        text = call(
            "ConvertTo-AgentConfigText -Template ([IO.File]::ReadAllText('%s')) -ServerUrl 'https://sup.example' "
            "-TokenFile 'D:\\PD\\token'" % template
        )[0]
        lines = text.splitlines()
        self.assertIn("server_url = https://sup.example", lines)
        self.assertIn("token_file = D:\\PD\\token", lines)
        self.assertIn("interval = 60", lines)
        updated = call("Set-ServerUrlInConfigText \"[agent]`r`nserver_url = https://a`r`ninterval = 30\" 'https://b'")[0]
        self.assertEqual(updated.splitlines(), ["[agent]", "server_url = https://b", "interval = 30"])

    def test_jeton_nettoye_comme_install_sh(self):
        self.assertEqual(call("ConvertTo-CleanToken ([char]0xFEFF + ' ab c' + \"`r`n\")"), ["abc"])

    def test_arguments_icacls_fixes_et_par_sid(self):
        self.assertEqual(
            call("Get-RestrictedAclArguments 'C:\\PD\\x' -Container"),
            ["C:\\PD\\x", "/inheritance:r", "/grant:r", "*S-1-5-18:(OI)(CI)F", "*S-1-5-32-544:(OI)(CI)F"],
        )
        self.assertEqual(
            call("Get-RestrictedAclArguments 'C:\\PD\\token'"),
            ["C:\\PD\\token", "/inheritance:r", "/grant:r", "*S-1-5-18:F", "*S-1-5-32-544:F"],
        )
        self.assertEqual(
            call("Get-AgentTaskArguments 'C:\\PD\\agent.conf' 'C:\\PD\\logs\\agent.log'"),
            ['-m trans_dic1_agent --config "C:\\PD\\agent.conf" --log-file "C:\\PD\\logs\\agent.log"'],
        )

    def test_manifeste_detecte_fichier_modifie_ajoute_ou_manquant(self):
        root = tempfile.mkdtemp(prefix="manifeste-")
        os.makedirs(os.path.join(root, "pkg"))
        for name, content in (("a.txt", "a"), ("pkg/b.py", "b")):
            with open(os.path.join(root, name), "w", encoding="utf-8") as handle:
                handle.write(content)
        quoted = root.replace("'", "''")
        build = "[IO.File]::WriteAllText('%s\\MANIFEST.sha256', ((Get-ManifestEntries '%s') -join \"`n\"))" % (quoted, quoted)
        self.assertEqual(call(build + "; Test-PackageManifest '%s'" % quoted), [])
        with open(os.path.join(root, "pkg", "b.py"), "a", encoding="utf-8") as handle:
            handle.write("modifié")
        with open(os.path.join(root, "c.txt"), "w", encoding="utf-8") as handle:
            handle.write("c")
        os.remove(os.path.join(root, "a.txt"))
        self.assertEqual(
            call("Test-PackageManifest '%s'" % quoted),
            ["empreinte différente : pkg/b.py", "fichier inattendu : c.txt", "fichier manquant : a.txt"],
        )


@unittest.skipUnless(POWERSHELL, "Windows PowerShell requis")
class PackageAndDryRunTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work = tempfile.mkdtemp(prefix="paquet-windows-")
        code, output = run_powershell(["-File", os.path.join(PACKAGING, "build-package.ps1"), "-OutputDirectory", cls.work])
        if code != 0:
            raise AssertionError(output)
        zips = [name for name in os.listdir(cls.work) if name.endswith(".zip")]
        assert len(zips) == 1, zips
        cls.zip_path = os.path.join(cls.work, zips[0])
        cls.extract = os.path.join(cls.work, "extrait")
        with zipfile.ZipFile(cls.zip_path) as archive:
            archive.extractall(cls.extract)
            cls.names = archive.namelist()
        cls.package = os.path.join(cls.extract, zips[0][: -len(".zip")])
        cls.token_file = os.path.join(cls.work, "jeton.txt")
        with open(cls.token_file, "w", encoding="utf-8") as handle:
            handle.write(TOKEN + "\n")
        cls.program_files = os.path.join(cls.work, "PF")
        cls.program_data = os.path.join(cls.work, "PD")

    def install(self, *extra, package=None):
        script = os.path.join(package or self.package, "install.ps1")
        return run_powershell(
            ["-File", script, "-DryRun", "-ProgramFilesDir", self.program_files, "-ProgramDataDir", self.program_data] + list(extra)
        )

    def test_le_zip_a_le_nom_versionne_et_son_empreinte(self):
        from trans_dic1_agent import VERSION

        self.assertEqual(os.path.basename(self.zip_path), "trans-dic1-agent-windows-%s.zip" % VERSION)
        with open(self.zip_path, "rb") as handle:
            digest = hashlib.sha256(handle.read()).hexdigest()
        with open(self.zip_path + ".sha256", encoding="ascii") as handle:
            self.assertEqual(handle.read(), "%s  %s\n" % (digest, os.path.basename(self.zip_path)))

    def test_le_zip_contient_l_agent_et_les_scripts_sans_test_ni_cache(self):
        root = os.path.basename(self.package) + "/"
        for name in ("install.ps1", "uninstall.ps1", "common.ps1", "agent.conf.example", "README.md",
                     "MANIFEST.sha256", "trans_dic1_agent/metrics_windows.py", "trans_dic1_agent/winacl.py"):
            self.assertIn(root + name, self.names)
        self.assertFalse([n for n in self.names if "__pycache__" in n or "/tests/" in n or "\\" in n])

    def test_install_dry_run_n_affiche_pas_le_jeton_et_ne_modifie_rien(self):
        code, output = self.install("-ServerUrl", "https://supervision.example", "-TokenFile", self.token_file)
        self.assertEqual(code, 0, output)
        self.assertIn("paquet vérifié", output)
        self.assertIn("[DRY-RUN] copie du code", output)
        self.assertIn("*S-1-5-18:(OI)(CI)F *S-1-5-32-544:(OI)(CI)F", output)
        self.assertIn("[DRY-RUN] enregistrement de la tâche", output)
        self.assertIn("--log-file", output)
        self.assertNotIn(TOKEN, output)
        self.assertFalse(os.path.exists(self.program_files))
        self.assertFalse(os.path.exists(self.program_data))

    def test_install_refuse_une_adresse_http(self):
        code, output = self.install("-ServerUrl", "http://supervision.example", "-TokenFile", self.token_file)
        self.assertEqual(code, 1)
        self.assertIn("https://", output)

    def test_install_refuse_un_paquet_modifie(self):
        tampered = os.path.join(self.work, "altere")
        shutil.copytree(self.package, tampered)
        with open(os.path.join(tampered, "trans_dic1_agent", "client.py"), "a", encoding="utf-8") as handle:
            handle.write("\n# modification\n")
        code, output = self.install("-ServerUrl", "https://s.example", "-TokenFile", self.token_file, package=tampered)
        self.assertEqual(code, 1)
        self.assertIn("empreinte différente : trans_dic1_agent/client.py", output)

    def test_uninstall_dry_run_ne_supprime_rien(self):
        app = os.path.join(self.work, "PF2", "Trans-DIC1", "agent")
        data = os.path.join(self.work, "PD2", "Trans-DIC1", "agent")
        os.makedirs(app)
        os.makedirs(data)
        code, output = run_powershell([
            "-File", os.path.join(self.package, "uninstall.ps1"), "-DryRun", "-Purge",
            "-ProgramFilesDir", os.path.join(self.work, "PF2"), "-ProgramDataDir", os.path.join(self.work, "PD2"),
        ])
        self.assertEqual(code, 0, output)
        self.assertIn("[DRY-RUN] suppression de " + app, output)
        self.assertIn("[DRY-RUN] suppression de " + data, output)
        self.assertTrue(os.path.isdir(app))
        self.assertTrue(os.path.isdir(data))


if __name__ == "__main__":
    unittest.main()
