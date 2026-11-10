import contextlib
import copy
import importlib.util
import io
import json
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "minecraft" / "verify_p0_pilot_inputs.py"
SPEC = importlib.util.spec_from_file_location("p0_pilot_inputs", SCRIPT)
tool = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(tool)
REPO = Path(__file__).resolve().parents[2]
RECORD_PATH = REPO / tool.RECORD_PATH


def sha(data):
    return tool.sha256_bytes(data)


class Pool:
    """Assemble a class file carrying the requested string constants."""

    def __init__(self):
        self.entries = []
        self.next_index = 1

    def _add(self, chunk, slots=1):
        self.entries.append(chunk)
        self.next_index += slots

    def utf8(self, value):
        raw = value.encode("utf-8")
        index = self.next_index
        self._add(b"\x01" + struct.pack(">H", len(raw)) + raw)
        return index

    def string(self, value):
        index = self.next_index
        target = self.utf8(value)
        self._add(b"\x08" + struct.pack(">H", target))
        return index

    def long(self, value):
        self._add(b"\x05" + struct.pack(">q", value), slots=2)

    def double(self, value):
        self._add(b"\x06" + struct.pack(">d", value), slots=2)

    def classref(self, name):
        index = self.next_index
        target = self.utf8(name)
        self._add(b"\x07" + struct.pack(">H", target))
        return index

    def bytes(self):
        header = b"\xca\xfe\xba\xbe" + struct.pack(">HH", 0, 61) + struct.pack(">H", self.next_index)
        return header + b"".join(self.entries)


def class_file(strings):
    pool = Pool()
    for value in strings:
        pool.string(value)
    return pool.bytes()


def write_zip(path, members):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, payload in members.items():
            archive.writestr(name, payload)


def inner_jar(strings, extra=None):
    members = {"ahf.class": class_file(strings)}
    if extra:
        members.update(extra)
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, payload in members.items():
            archive.writestr(name, payload)
    return buffer.getvalue()


def bundler_zip(path, inner, version="1.20.1"):
    row = "%s\t%s\t%s/server-%s.jar" % (sha(inner), version, version, version)
    write_zip(path, {
        "META-INF/versions.list": (row + "\n").encode("utf-8"),
        "META-INF/versions/%s/server-%s.jar" % (version, version): inner,
    })


class Fixture(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.record = tool.load_record(RECORD_PATH)
        self.template = (REPO / self.record["launchInput"]["path"]).read_text(encoding="ascii")

    def tearDown(self):
        self.temporary.cleanup()

    def write(self, relative, payload):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(payload, bytes):
            path.write_bytes(payload)
        else:
            path.write_text(payload, encoding="utf-8")
        return path

    def write_template(self, text, record=None):
        record = record if record is not None else self.record
        raw = text.encode("ascii")
        self.write(record["launchInput"]["path"], raw)
        record["launchInput"]["bytes"] = len(raw)
        record["launchInput"]["sha256"] = sha(raw)
        return record

class OfflineUuidTest(unittest.TestCase):
    def test_known_answers(self):
        self.assertEqual("72212196-314f-382e-bb12-fcc3585e6d10", tool.offline_player_uuid("LuoP0Body"))
        self.assertEqual("ef578af7-17f1-34db-8d89-3eaa9f6c3c20", tool.offline_player_uuid("LuoP0Human"))
        # Cross-check: the offline UUID commonly cited for the name "Notch".
        self.assertEqual("b50ad385-829d-3141-a216-7e7d7539ba7f", tool.offline_player_uuid("Notch"))

    def test_version_and_variant(self):
        value = tool.offline_player_uuid("LuoP0Body")
        self.assertEqual("3", value[14])
        self.assertIn(value[19], "89ab")
        self.assertEqual(value, value.lower())


class PropertiesParseTest(unittest.TestCase):
    def test_checked_in_template(self):
        raw = (REPO / "tests/p0-pilot/server.properties").read_bytes()
        self.assertEqual(712, len(raw))
        self.assertEqual("aaf21979aa36bfaf847337fdb6a10cf86245efc564587c1aae0ad2bfaf8c9890", sha(raw))
        self.assertNotIn(b"\r", raw)
        raw.decode("ascii")
        parsed = tool.parse_properties(raw)
        self.assertEqual(20, len(parsed))
        self.assertEqual(set(tool.EXPECTED_PROPERTY_KEYS), set(parsed))
        self.assertEqual("minecraft:flat", parsed["level-type"])

    def test_checked_in_record_and_inputs_pass(self):
        record = tool.load_record(RECORD_PATH)
        self.assertEqual("PASS", tool.check_record(record)["status"])
        self.assertEqual("PASS", tool.check_launch_input(REPO, record)["status"])
        self.assertEqual("PASS", tool.check_identities(record)["status"])

    def test_repository_report_has_no_failed_check(self):
        result = tool.report(REPO, None)
        self.assertNotEqual("FAILED", result["status"])
        checks = result["checks"]
        self.assertEqual("PASS", checks["record"]["status"])
        self.assertEqual("PASS", checks["launch_input"]["status"])
        self.assertEqual("PASS", checks["identities"]["status"])
        self.assertEqual("BLOCKED", checks["java"]["status"])
        for name in ("artifacts", "server_property_keys"):
            self.assertIn(checks[name]["status"], {"PASS", "BLOCKED"})

    def test_rejections(self):
        cases = {
            "properties_bom_forbidden": b"\xef\xbb\xbfserver-ip=127.0.0.1\n",
            "properties_non_ascii": "motd=\u4e2d\n".encode("utf-8"),
            "properties_cr_forbidden": b"server-ip=127.0.0.1\r\n",
            "properties_missing_final_newline": b"server-ip=127.0.0.1",
            "properties_blank_line": b"server-ip=127.0.0.1\n\nmotd=x\n",
            "properties_missing_separator": b"server-ip 127.0.0.1\n",
            "properties_key_invalid": b"Server-ip=127.0.0.1\n",
            "properties_duplicate_key": b"motd=a\nmotd=b\n",
            "properties_value_empty": b"motd=\n",
            "properties_value_characters": b"motd= padded \n",
            "properties_escape_invalid": b"motd=a\\b\n",
        }
        for code, raw in cases.items():
            with self.subTest(code=code):
                with self.assertRaises(ValueError) as raised:
                    tool.parse_properties(raw)
                self.assertEqual(code, str(raised.exception))

    def test_comments_and_escaped_colon(self):
        parsed = tool.parse_properties(b"# comment\nlevel-type=minecraft\\:flat\nmotd=a\n")
        self.assertEqual({"level-type": "minecraft:flat", "motd": "a"}, parsed)

    def test_comment_only_input_rejected(self):
        with self.assertRaises(ValueError):
            tool.parse_properties(b"# only a comment\n")

class LaunchInputTest(Fixture):
    CASES = {
        "template_server_ip_invalid": ("server-ip=127.0.0.1", "server-ip=127.0.0.2"),
        "template_server_port_invalid": ("server-port=25565", "server-port=25566"),
        "template_online_mode_invalid": ("online-mode=false", "online-mode=true"),
        "template_white_list_invalid": ("white-list=true", "white-list=false"),
        "template_enforce_whitelist_invalid": ("enforce-whitelist=true", "enforce-whitelist=false"),
        "template_rcon_invalid": ("enable-rcon=false", "enable-rcon=true"),
        "template_query_invalid": ("enable-query=false", "enable-query=true"),
        "template_max_players_invalid": ("max-players=2", "max-players=3"),
        "template_level_name_invalid": ("level-name=p0-pilot-world", "level-name=other"),
        "template_level_type_invalid": ("level-type=minecraft\\:flat", "level-type=minecraft\\:normal"),
        "template_generate_structures_invalid": ("generate-structures=false", "generate-structures=true"),
        "template_allow_nether_invalid": ("allow-nether=false", "allow-nether=true"),
        "template_max_world_size_invalid": ("max-world-size=16", "max-world-size=32"),
        "template_difficulty_invalid": ("difficulty=easy", "difficulty=peaceful"),
        "template_gamemode_invalid": ("gamemode=survival", "gamemode=creative"),
        "template_force_gamemode_invalid": ("force-gamemode=true", "force-gamemode=false"),
        "template_hardcore_invalid": ("hardcore=false", "hardcore=true"),
        "template_spawn_monsters_invalid": ("spawn-monsters=false", "spawn-monsters=true"),
        "template_level_seed_invalid": ("level-seed=20261110", "level-seed=seed"),
    }

    def test_invariants(self):
        for code, (old, new) in self.CASES.items():
            with self.subTest(code=code):
                record = copy.deepcopy(self.record)
                text = self.template.replace(old, new)
                self.assertNotEqual(self.template, text)
                self.write_template(text, record)
                result = tool.check_launch_input(self.root, record)
                self.assertEqual("FAILED", result["status"])
                self.assertIn(code, result["errors"])

    def test_record_network_ip_must_be_loopback(self):
        record = copy.deepcopy(self.record)
        self.write_template(self.template, record)
        record["network"]["serverIp"] = "0.0.0.0"
        result = tool.check_launch_input(self.root, record)
        self.assertIn("record_network_ip_invalid", result["errors"])

    def test_key_set_drift(self):
        record = copy.deepcopy(self.record)
        self.write_template(self.template + "extra-key=1\n", record)
        result = tool.check_launch_input(self.root, record)
        self.assertIn("launch_input_key_set_invalid", result["errors"])
        record = copy.deepcopy(self.record)
        self.write_template(self.template.replace("motd=Luo-Sandbox P0 pilot - isolated loopback\n", ""), record)
        result = tool.check_launch_input(self.root, record)
        self.assertIn("launch_input_key_set_invalid", result["errors"])

    def test_size_and_hash_mismatch(self):
        record = copy.deepcopy(self.record)
        self.write_template(self.template, record)
        record["launchInput"]["bytes"] += 1
        result = tool.check_launch_input(self.root, record)
        self.assertIn("launch_input_size_mismatch", result["errors"])
        record = copy.deepcopy(self.record)
        self.write_template(self.template, record)
        record["launchInput"]["sha256"] = "0" * 64
        result = tool.check_launch_input(self.root, record)
        self.assertIn("launch_input_hash_mismatch", result["errors"])

    def test_missing_template_blocked(self):
        result = tool.check_launch_input(self.root, copy.deepcopy(self.record))
        self.assertEqual("BLOCKED", result["status"])

class ServerPropertyKeysTest(Fixture):
    def build(self, strings, record=None, extra=None):
        record = copy.deepcopy(record if record is not None else self.record)
        record["artifactRoot"] = "artifacts"
        inner = inner_jar(strings, extra)
        record["serverPropertySource"]["innerJarSha256"] = sha(inner)
        bundler_zip(self.root / "artifacts" / record["serverPropertySource"]["bundler"], inner)
        properties = tool.parse_properties(self.template.encode("ascii"))
        return record, properties

    def test_pass_requires_every_key(self):
        record, properties = self.build(sorted(tool.EXPECTED_PROPERTY_KEYS))
        result = tool.check_server_property_keys(self.root, record, properties)
        self.assertEqual("PASS", result["status"], result["errors"])
        self.assertEqual(["ahf.class"], result["propertyClasses"])

    def test_missing_key_fails(self):
        record, properties = self.build(sorted(tool.EXPECTED_PROPERTY_KEYS - {"motd"}))
        result = tool.check_server_property_keys(self.root, record, properties)
        self.assertEqual("FAILED", result["status"])
        self.assertEqual(["motd"], result["missingKeys"])

    def test_bundler_missing_blocked(self):
        record = copy.deepcopy(self.record)
        record["artifactRoot"] = "absent"
        result = tool.check_server_property_keys(self.root, record, {})
        self.assertEqual("BLOCKED", result["status"])

    def test_inner_hash_mismatch_fails(self):
        record, properties = self.build(sorted(tool.EXPECTED_PROPERTY_KEYS))
        record["serverPropertySource"]["innerJarSha256"] = "0" * 64
        result = tool.check_server_property_keys(self.root, record, properties)
        self.assertEqual("FAILED", result["status"])
        self.assertIn("inner_jar_hash_mismatch_record", result["errors"])

    def test_two_marker_classes_fail(self):
        record = copy.deepcopy(self.record)
        record["artifactRoot"] = "artifacts"
        strings = sorted(tool.EXPECTED_PROPERTY_KEYS)
        inner = inner_jar(strings, {"bah.class": class_file(strings)})
        record["serverPropertySource"]["innerJarSha256"] = sha(inner)
        bundler_zip(self.root / "artifacts" / record["serverPropertySource"]["bundler"], inner)
        properties = tool.parse_properties(self.template.encode("ascii"))
        result = tool.check_server_property_keys(self.root, record, properties)
        self.assertEqual("FAILED", result["status"])
        self.assertIn("property_class_not_unique", result["errors"])

    def test_malformed_versions_list_fails(self):
        record = copy.deepcopy(self.record)
        record["artifactRoot"] = "artifacts"
        inner = inner_jar(sorted(tool.EXPECTED_PROPERTY_KEYS))
        record["serverPropertySource"]["innerJarSha256"] = sha(inner)
        write_zip(self.root / "artifacts" / record["serverPropertySource"]["bundler"], {
            "META-INF/versions.list": b"not-a-row\n",
            "META-INF/versions/1.20.1/server-1.20.1.jar": inner,
        })
        properties = tool.parse_properties(self.template.encode("ascii"))
        result = tool.check_server_property_keys(self.root, record, properties)
        self.assertEqual("FAILED", result["status"])


class StringConstantTest(unittest.TestCase):
    def test_double_slot_entries_are_skipped(self):
        pool = Pool()
        pool.long(1)
        pool.double(2.5)
        pool.string("server-ip")
        pool.string("online-mode")
        self.assertEqual(["server-ip", "online-mode"], tool.string_constants(pool.bytes()))

    def test_unknown_tag_rejected(self):
        raw = b"\xca\xfe\xba\xbe" + struct.pack(">HH", 0, 61) + struct.pack(">H", 2) + b"\x0d\x00\x00"
        with self.assertRaises(ValueError) as raised:
            tool.string_constants(raw)
        self.assertEqual("class_constant_tag_unknown_13", str(raised.exception))

    def test_bad_magic_rejected(self):
        with self.assertRaises(ValueError):
            tool.string_constants(b"not-a-class-file")

class ArtifactTest(Fixture):
    def build(self):
        record = copy.deepcopy(self.record)
        record["artifactRoot"] = "artifacts"
        record["artifacts"] = []
        for index in range(6):
            payload = ("artifact-%d" % index).encode("ascii")
            name = "a%d.jar" % index
            self.write("artifacts/" + name, payload)
            record["artifacts"].append({"file": name, "bytes": len(payload), "sha256": sha(payload)})
        return record

    def test_pass(self):
        self.assertEqual("PASS", tool.check_artifacts(self.root, self.build())["status"])

    def test_tampered_fails(self):
        record = self.build()
        self.write("artifacts/a0.jar", b"tampered")
        result = tool.check_artifacts(self.root, record)
        self.assertEqual("FAILED", result["status"])
        self.assertIn("artifact_size_mismatch:a0.jar", result["errors"])
        self.assertIn("artifact_hash_mismatch:a0.jar", result["errors"])

    def test_missing_blocked(self):
        record = self.build()
        (self.root / "artifacts" / "a1.jar").unlink()
        result = tool.check_artifacts(self.root, record)
        self.assertEqual("BLOCKED", result["status"])
        self.assertEqual(["a1.jar"], result["missing"])


class JavaTest(Fixture):
    def build(self, runtime_version="17.0.20.1+1", java_version="17.0.20.1"):
        record = copy.deepcopy(self.record)
        record["java"]["fingerprint"] = []
        for relative in ("bin/java.exe", "release"):
            payload = ("jdk-" + relative).encode("ascii")
            self.write("jdk/" + relative, payload)
            record["java"]["fingerprint"].append({"path": relative, "bytes": len(payload), "sha256": sha(payload)})
        release = 'JAVA_VERSION="%s"\nJAVA_RUNTIME_VERSION="%s"\n' % (java_version, runtime_version)
        payload = self.write("jdk/release", release).read_bytes()
        for entry in record["java"]["fingerprint"]:
            if entry["path"] == "release":
                entry["bytes"] = len(payload)
                entry["sha256"] = sha(payload)
        return record

    def test_pass(self):
        result = tool.check_java(self.root / "jdk", self.build())
        self.assertEqual("PASS", result["status"], result["errors"])

    def test_runtime_version_mismatch_fails(self):
        record = self.build(runtime_version="17.0.19+1")
        result = tool.check_java(self.root / "jdk", record)
        self.assertEqual("FAILED", result["status"])
        self.assertIn("java_runtime_version_mismatch", result["errors"])

    def test_major_version_mismatch_fails(self):
        record = self.build(java_version="21.0.1")
        result = tool.check_java(self.root / "jdk", record)
        self.assertIn("java_major_version_mismatch", result["errors"])

    def test_tampered_file_fails(self):
        record = self.build()
        self.write("jdk/bin/java.exe", b"different")
        result = tool.check_java(self.root / "jdk", record)
        self.assertIn("java_hash_mismatch:bin/java.exe", result["errors"])

    def test_blocked_without_home(self):
        self.assertEqual("BLOCKED", tool.check_java(None, self.record)["status"])
        self.assertEqual("BLOCKED", tool.check_java(self.root / "absent", self.record)["status"])


class DocumentTest(Fixture):
    def write_docs(self, record):
        for relative, values in tool.document_needles(record).items():
            self.write(relative, "\n".join(values) + "\n")

    def test_pass(self):
        record = copy.deepcopy(self.record)
        self.write_docs(record)
        self.assertEqual("PASS", tool.check_documents(self.root, record)["status"])

    def test_missing_needle_fails(self):
        record = copy.deepcopy(self.record)
        self.write_docs(record)
        self.write(tool.MANIFEST_PATH, "empty\n")
        result = tool.check_documents(self.root, record)
        self.assertEqual("FAILED", result["status"])
        self.assertTrue(any(item.startswith(tool.MANIFEST_PATH) for item in result["missing"]))

    def test_missing_document_fails(self):
        record = copy.deepcopy(self.record)
        self.write_docs(record)
        (self.root / tool.PREFLIGHT_PATH).unlink()
        result = tool.check_documents(self.root, record)
        self.assertIn("document_missing:" + tool.PREFLIGHT_PATH, result["errors"])

class RecordTest(unittest.TestCase):
    def test_checked_in_record_passes(self):
        self.assertEqual("PASS", tool.check_record(tool.load_record(RECORD_PATH))["status"])

    def test_mutations_fail(self):
        base = tool.load_record(RECORD_PATH)
        mutations = {
            "record_key_set_invalid": lambda r: r.pop("claims"),
            "record_schema_invalid": lambda r: r.__setitem__("schema", "other"),
            "record_status_invalid": lambda r: r.__setitem__("status", "DONE"),
            "record_date_invalid": lambda r: r.__setitem__("recordedOn", ""),
            "claims_invalid": lambda r: r.__setitem__("claims", []),
            "artifact_count_invalid": lambda r: r["artifacts"].pop(),
            "artifact_0_hash_invalid": lambda r: r["artifacts"][0].__setitem__("sha256", "ABC"),
            "server_property_source_hash_invalid": lambda r: r["serverPropertySource"].__setitem__("innerJarSha256", "x"),
            "java_major_version_invalid": lambda r: r["java"].__setitem__("majorVersion", 0),
            "java_fingerprint_0_path_invalid": lambda r: r["java"]["fingerprint"][0].__setitem__("path", "../escape"),
            "launch_input_role_invalid": lambda r: r["launchInput"].__setitem__("role", "EFFECTIVE"),
            "network_scope_invalid": lambda r: r["network"].__setitem__("scope", "PRIVATE"),
            "network_ip_invalid": lambda r: r["network"].__setitem__("serverIp", "0.0.0.0"),
            "identity_count_invalid": lambda r: r["identities"].pop(),
            "identity_0_name_invalid": lambda r: r["identities"][0].__setitem__("name", "bad-name"),
            "identity_1_role_duplicated": lambda r: r["identities"][1].__setitem__("role", "CONTROLLED_BODY"),
            "identity_0_measured_uuid_invalid": lambda r: r["identities"][0].__setitem__("measuredOfflineUuid", "nope"),
            "world_level_name_invalid": lambda r: r["world"].__setitem__("levelName", ""),
            "region_not_symmetric": lambda r: r["world"]["region"].__setitem__("maxX", 32),
            "datapacks_external_not_empty": lambda r: r["datapacks"].__setitem__("external", ["x.zip"]),
            "blocked_invalid": lambda r: r.__setitem__("blocked", []),
        }
        for code, mutate in mutations.items():
            with self.subTest(code=code):
                record = copy.deepcopy(base)
                mutate(record)
                result = tool.check_record(record)
                self.assertEqual("FAILED", result["status"])
                self.assertIn(code, result["errors"])


class LoadRecordTest(Fixture):
    def test_rejections(self):
        with self.assertRaises(ValueError):
            tool.load_record(self.write("dup.json", '{"schema": "a", "schema": "b"}'))
        with self.assertRaises(ValueError):
            tool.load_record(self.write("bom.json", b"\xef\xbb\xbf{}"))
        with self.assertRaises(ValueError):
            tool.load_record(self.root / "missing.json")
        with self.assertRaises(ValueError):
            tool.load_record(self.write("list.json", "[]"))

class ReportTest(Fixture):
    def valid_root(self):
        record = copy.deepcopy(self.record)
        record["artifactRoot"] = "artifacts"
        record["artifacts"] = []
        for index in range(6):
            payload = ("a%d" % index).encode("ascii")
            name = "a%d.jar" % index
            self.write("artifacts/" + name, payload)
            record["artifacts"].append({"file": name, "bytes": len(payload), "sha256": sha(payload)})
        inner = inner_jar(sorted(tool.EXPECTED_PROPERTY_KEYS))
        record["serverPropertySource"]["innerJarSha256"] = sha(inner)
        bundler_zip(self.root / "artifacts" / record["serverPropertySource"]["bundler"], inner)
        self.write(record["launchInput"]["path"], self.template.encode("ascii"))
        record["java"]["fingerprint"] = []
        for relative in ("bin/java.exe", "release"):
            payload = ("jdk-" + relative).encode("ascii")
            self.write("jdk/" + relative, payload)
            record["java"]["fingerprint"].append({"path": relative, "bytes": len(payload), "sha256": sha(payload)})
        release = 'JAVA_VERSION="17.0.20.1"\nJAVA_RUNTIME_VERSION="17.0.20.1+1"\n'
        payload = self.write("jdk/release", release).read_bytes()
        for entry in record["java"]["fingerprint"]:
            if entry["path"] == "release":
                entry["bytes"] = len(payload)
                entry["sha256"] = sha(payload)
        for relative, values in tool.document_needles(record).items():
            self.write(relative, "\n".join(values) + "\n")
        self.write(tool.RECORD_PATH, json.dumps(record, ensure_ascii=False, indent=2))
        return self.root / "jdk"

    def test_verified_and_exit_zero(self):
        jdk = self.valid_root()
        result = tool.report(self.root, jdk)
        self.assertEqual(tool.VERIFIED, result["status"], result["checks"])
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            code = tool.main(["--root", str(self.root), "--jdk-home", str(jdk)])
        self.assertEqual(0, code)
        self.assertEqual(tool.VERIFIED, json.loads(buffer.getvalue())["status"])

    def test_tampered_artifact_fails_and_exit_two(self):
        jdk = self.valid_root()
        self.write("artifacts/a0.jar", b"changed")
        self.assertEqual("FAILED", tool.report(self.root, jdk)["status"])
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(2, tool.main(["--root", str(self.root), "--jdk-home", str(jdk)]))

    def test_missing_record_blocked(self):
        result = tool.report(self.root, None)
        self.assertEqual("BLOCKED", result["status"])
        self.assertEqual("BLOCKED", result["checks"]["record"]["status"])
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(2, tool.main(["--root", str(self.root)]))

    def test_no_report_file_without_flag(self):
        jdk = self.valid_root()
        before = sorted(str(path.relative_to(self.root)) for path in self.root.rglob("*"))
        with contextlib.redirect_stdout(io.StringIO()):
            tool.main(["--root", str(self.root), "--jdk-home", str(jdk)])
        after = sorted(str(path.relative_to(self.root)) for path in self.root.rglob("*"))
        self.assertEqual(before, after)


class SourceGuardTest(unittest.TestCase):
    def test_no_forbidden_imports(self):
        text = SCRIPT.read_text(encoding="utf-8")
        for forbidden in ("import socket", "import urllib", "import subprocess",
                          "import ctypes", "http.client"):
            self.assertNotIn(forbidden, text)

    def test_report_does_not_read_environment(self):
        text = SCRIPT.read_text(encoding="utf-8")
        body = text.split("def report(")[1].split("def main(")[0]
        self.assertNotIn("environ", body)


if __name__ == "__main__":
    unittest.main()
