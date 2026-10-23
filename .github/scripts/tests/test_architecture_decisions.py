"""Architecture gate failure contracts; fixtures never compile or execute Java."""

import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "architecture_decisions.py"
RUNNER = SCRIPT.with_name("run_architecture_contracts.py")
SPEC = importlib.util.spec_from_file_location("architecture_decisions", SCRIPT)
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


def java(path, name, member=None):
    anchor = {"kind": "java", "path": path, "type": name}
    if member:
        anchor["member"] = {"kind": "method", "name": member}
    return anchor


class ArchitectureDecisionsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.source = "module/src/main/java/example/Alpha.java"
        self.suite = "module/src/test/java/example/AlphaTest.java"
        self.write(".gitignore", "/docs/adr/\n/target/\n")
        self.write(self.source, "package example; public record Alpha(int value) { void bind() {} }\n")
        self.write(self.suite, "package example; class AlphaTest { @Test void contract() {} }\n")
        self.git("init", "-q")
        self.git("config", "user.email", "architecture-fixture@example.invalid")
        self.git("config", "user.name", "Architecture fixture")
        self.base = self.commit()
        self.row = {"id": "ADR-0001", "title": "Fixture", "status": "Implemented",
                    "decisionVersion": "0.2.7-SNAPSHOT", "targetVersion": None,
                    "implementedVersion": "0.2.7-SNAPSHOT", "remaining": "", "task": "",
                    "anchors": [java(self.source, "example.Alpha", "bind")],
                    "contracts": [java(self.suite, "example.AlphaTest")]}
        self.document = {"schemaVersion": 1, "decisions": [self.row]}
        self.registration = {"schemaVersion": 1, "baselineRevision": self.base,
                             "ids": ["ADR-0001"], "decisionsSha256": gate.digest(self.document),
                             "localSync": {"method": "static-inspection", "result": "matched",
                                           "fields": ["id", "status", *gate.LOCAL_FIELDS]}}
        self.save()

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def git(self, *args):
        result = subprocess.run(["git", *args], cwd=self.root, capture_output=True, text=True,
                                encoding="utf-8", check=True)
        return result.stdout.strip()

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-qm", "Architecture fixture")
        return self.git("rev-parse", "HEAD")

    def save(self):
        self.write(gate.MANIFEST, json.dumps(self.document))
        self.write(gate.REGISTRATION, json.dumps(self.registration))

    def local_docs(self):
        lines = ["# ADR-0001：Fixture", "", "- 状态：" + self.row["status"]]
        values = [self.row[field] or "—" for field in gate.LOCAL_FIELDS]
        lines.extend(f"- {label}：{value}" for label, value in zip(gate.LOCAL_FIELDS.values(), values))
        lines.extend(["", "## Body", "Local design text must not appear in CI reports."])
        self.write("docs/adr/0001-fixture.md", "\n".join(lines) + "\n")
        self.write("docs/adr/README.md", "| 编号 | 决策 | 状态 | 决策版本 | 目标版本 | 代码落地版本 | 未完成范围 | 实施任务 |\n"
                   "| --- | --- | --- | --- | --- | --- | --- | --- |\n"
                   "| [ADR-0001](0001-fixture.md) | Fixture | " + self.row["status"] + " | "
                   + " | ".join(values) + " |\n")

    def check_history(self, baseline=None):
        return gate.check_history(self.root, self.document, self.registration, baseline or self.base)

    def rejects(self, message, function, *args):
        with self.assertRaisesRegex(gate.GateError, message):
            function(*args)

    def test_bootstrap_requires_exact_ids_digest_and_trusted_revision(self):
        self.assertTrue(self.check_history()["bootstrap"])
        self.registration["ids"].append("ADR-0002")
        self.rejects("ID set or manifest digest", self.check_history)
        self.registration["ids"].pop()
        self.registration["decisionsSha256"] = "a" * 64
        self.rejects("ID set or manifest digest", self.check_history)
        self.registration["decisionsSha256"] = gate.digest(self.document)
        self.registration["baselineRevision"] = "a" * 40
        self.rejects("different trusted baseline", self.check_history)

    def test_baseline_is_mandatory_and_must_exist(self):
        for baseline in (None, "", "HEAD", "0" * 40, "a" * 40):
            with self.subTest(baseline=baseline):
                self.rejects("baseline|git", gate.check_history, self.root, self.document,
                             self.registration, baseline)

    def test_registered_decision_deletion_is_detected_from_old_ids(self):
        second = copy.deepcopy(self.row)
        second["id"] = "ADR-0002"
        self.document["decisions"].append(second)
        self.registration["ids"].append("ADR-0002")
        self.registration["decisionsSha256"] = gate.digest(self.document)
        self.save()
        baseline = self.commit()
        self.document["decisions"].pop()
        self.rejects("decision deleted: ADR-0002", self.check_history, baseline)

    def test_cannot_rewrite_registration_or_restart_after_full_deletion(self):
        baseline = self.commit()
        self.registration["decisionsSha256"] = "a" * 64
        self.rejects("retained unchanged", self.check_history, baseline)
        self.git("rm", gate.MANIFEST, gate.REGISTRATION)
        self.git("commit", "-qm", "Delete registration")
        self.rejects("deleted; bootstrap cannot", self.check_history, self.git("rev-parse", "HEAD"))

    def test_downgrade_needs_reason_remaining_task_and_exact_baseline(self):
        baseline = self.commit()
        self.row.update(status="Accepted", targetVersion="0.2.8-SNAPSHOT", remaining="CI validation",
                        task="0.2.8:T09")
        self.rejects("lacks trusted disposition", self.check_history, baseline)
        self.row["change"] = {"baselineRevision": baseline, "fromStatus": "Implemented", "reason": ""}
        self.rejects("change reason", self.check_history, baseline)
        self.row["change"]["reason"] = "Reopened after missing failure coverage"
        self.assertFalse(self.check_history(baseline)["bootstrap"])
        self.row["change"]["baselineRevision"] = self.base
        self.rejects("lacks trusted disposition", self.check_history, baseline)

    def test_anchor_and_contract_removals_require_explicit_disposition(self):
        baseline = self.commit()
        old = self.row["anchors"].pop()
        # Keep a different anchor: empty Implemented anchors fail schema validation first.
        self.row["anchors"].append(java(self.source, "example.Alpha"))
        self.rejects("lacks trusted disposition", self.check_history, baseline)
        self.row["change"] = {"baselineRevision": baseline, "fromStatus": "Implemented",
                              "reason": "Member no longer represents the decision"}
        self.rejects("removed anchors", self.check_history, baseline)
        self.row["change"]["removedAnchors"] = [old]
        self.check_history(baseline)
        replacement = self.suite.replace("AlphaTest", "ReplacementTest")
        self.write(replacement, "package example; class ReplacementTest { @Test void contract() {} }\n")
        self.row["contracts"].append(java(replacement, "example.ReplacementTest"))
        removed = self.row["contracts"].pop(0)
        self.rejects("removed contracts", self.check_history, baseline)
        self.row["change"]["removedContracts"] = [removed]
        self.check_history(baseline)
        gate.check_sources(self.root, gate.validate_manifest(self.document))

    def test_invalid_duplicate_status_and_partial_registration_fail(self):
        self.document["decisions"].append(copy.deepcopy(self.row))
        self.rejects("Duplicate decision", gate.validate_manifest, self.document)
        self.document["decisions"].pop()
        self.row["status"] = "Done"
        self.rejects("invalid status", gate.validate_manifest, self.document)
        self.row["status"] = "Accepted"
        self.rejects("remaining", gate.validate_manifest, self.document)
        self.row.update(remaining="aggregate rules", task="0.2.9:T01", targetVersion="0.2.9-SNAPSHOT")
        gate.validate_manifest(self.document)

    def test_optional_disposition_objects_require_object_types_for_every_status(self):
        for status in sorted(gate.STATUSES):
            document = copy.deepcopy(self.document)
            row = document["decisions"][0]
            row["status"] = status
            if status in {"Proposed", "Accepted"}:
                row.update(targetVersion="0.2.8-SNAPSHOT", remaining="CI validation", task="0.2.8:T09")
            elif status in gate.TERMINAL:
                row["disposition"] = {"reason": "Fixture disposition"}
                if status == "Superseded":
                    replacement = copy.deepcopy(self.row)
                    replacement["id"] = "ADR-0002"
                    document["decisions"].append(replacement)
                    row["disposition"]["replacedBy"] = ["ADR-0002"]
            gate.validate_manifest(document)
            for field in ("change", "disposition"):
                for value in (None, [], "", False, 1):
                    with self.subTest(status=status, field=field, value=value):
                        invalid = copy.deepcopy(document)
                        invalid["decisions"][0][field] = value
                        self.rejects(f"ADR-0001: {field} must be an object",
                                     gate.validate_manifest, invalid)

    def test_disposition_replacements_are_validated_before_graph_traversal(self):
        self.row["disposition"] = {"replacedBy": []}
        gate.validate_manifest(self.document)
        for value in (None, "ADR-0002", {}, [None], [{}], ["ADR-0002", "ADR-0002"]):
            with self.subTest(value=value):
                self.row["disposition"] = {"replacedBy": value}
                self.rejects("ADR-0001: disposition.replacedBy must be an array of unique strings",
                             gate.validate_manifest, self.document)

    def test_superseded_keeps_id_reason_and_acyclic_existing_replacement(self):
        self.row.update(status="Superseded", disposition={"reason": "Replacement", "replacedBy": ["ADR-0002"]})
        self.rejects("missing replacement", gate.validate_manifest, self.document)
        second = copy.deepcopy(self.row)
        second["id"] = "ADR-0002"
        second["disposition"]["replacedBy"] = ["ADR-0001"]
        self.document["decisions"].append(second)
        self.rejects("Replacement cycle", gate.validate_manifest, self.document)

    def test_java_comments_literals_nested_types_and_calls_are_not_declarations(self):
        gate.check_sources(self.root, gate.validate_manifest(self.document))
        self.write(self.source, 'package example; /* record Alpha(int value) { void bind() {} } */\n'
                   'class Other { String text = "class Alpha {}"; class Alpha { void bind() {} } }\n')
        self.rejects("Missing or ambiguous Java declaration", gate.check_sources, self.root,
                     gate.validate_manifest(self.document))
        self.write(self.source, 'package example; class Alpha { void other() { bind(); } }\n')
        self.rejects("Missing Java member", gate.check_sources, self.root,
                     gate.validate_manifest(self.document))

    def test_missing_contract_file_or_test_annotation_is_rejected(self):
        (self.root / self.suite).unlink()
        self.rejects("Missing anchor file", gate.check_sources, self.root,
                     gate.validate_manifest(self.document))
        self.write(self.suite, "package example; class AlphaTest { String text = \"@Test\"; }\n")
        self.rejects("no test declarations", gate.check_sources, self.root,
                     gate.validate_manifest(self.document))

    def test_config_and_script_anchors_cannot_be_satisfied_by_comments(self):
        inspector = gate.SourceInspector(self.root)
        self.write("pom.xml", '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                   '<!-- <properties><enabled>true</enabled></properties> --></project>')
        self.rejects("XML anchor", inspector.check, {"kind": "xml", "path": "pom.xml",
                                                    "xpath": "m:properties/m:enabled"})
        self.write("ci.yml", "# jobs:\n#   gate: {}\non: push\njobs:\n  other: {}\n")
        self.rejects("Missing configuration key", inspector.check,
                     {"kind": "yaml", "path": "ci.yml", "pointer": ["jobs", "gate"]})
        self.write("check.py", '# def main(): pass\ntext = "main()"\n')
        self.rejects("Python entry declaration", inspector.check,
                     {"kind": "python", "path": "check.py", "entry": "main"})
        self.write("unregistered.py", 'def main(): pass\n')
        self.rejects("not registered under __main__", inspector.check,
                     {"kind": "python", "path": "unregistered.py", "entry": "main"})
        self.write("settings.json", '{"unused": "enabled"}')
        self.rejects("Missing configuration key", inspector.check,
                     {"kind": "json", "path": "settings.json", "pointer": ["enabled"]})

    def test_structured_configuration_and_entry_registration(self):
        self.write("ci.yml", "on: push\njobs:\n  gate:\n    steps:\n      - name: Check\n        run: python check.py\n")
        self.write("pom.xml", '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                   '<properties><enabled>true</enabled></properties></project>')
        self.write("check.py", 'def main(): pass\nif __name__ == "__main__":\n    main()\n')
        self.write("settings.json", '{"enabled": true}')
        inspector = gate.SourceInspector(self.root)
        for anchor in [
            {"kind": "yaml", "path": "ci.yml", "pointer": ["on"], "expected": "push"},
            {"kind": "yaml", "path": "ci.yml", "pointer": ["jobs", "gate", "steps", {"name": "Check"}, "run"],
             "expected": "python check.py"},
            {"kind": "xml", "path": "pom.xml", "xpath": "m:properties/m:enabled", "expected": "true"},
            {"kind": "json", "path": "settings.json", "pointer": ["enabled"], "expected": True},
            {"kind": "python", "path": "check.py", "entry": "main"},
        ]:
            inspector.check(anchor)

    def test_python_entry_registration_accepts_direct_calls_and_exit_wrappers(self):
        for statement in ("main()", "sys.exit(main())", "raise SystemExit(main())"):
            with self.subTest(statement=statement):
                self.write("entry.py", 'import sys\ndef main(): pass\n'
                           'if __name__ == "__main__":\n    ' + statement + '\n')
                gate.SourceInspector(self.root).check(
                    {"kind": "python", "path": "entry.py", "entry": "main"})

    def test_python_entry_registration_rejects_else_and_deferred_or_conditional_calls(self):
        for label, body in [
            ("else_only", "    pass\nelse:\n    main()\n"),
            ("function", "    def deferred():\n        main()\n"),
            ("async_function", "    async def deferred():\n        main()\n"),
            ("class_method", "    class Deferred:\n        def run(self):\n            main()\n"),
            ("lambda", "    deferred = lambda: main()\n"),
            ("generator", "    deferred = (main() for _ in range(1))\n"),
            ("conditional", "    if False:\n        main()\n"),
            ("reference", "    main\n"),
        ]:
            with self.subTest(case=label):
                self.write("entry.py", 'def main(): pass\nif __name__ == "__main__":\n' + body)
                self.rejects("not registered under __main__", gate.SourceInspector(self.root).check,
                             {"kind": "python", "path": "entry.py", "entry": "main"})

    def test_configuration_collections_reject_empty_values_unless_explicitly_allowed(self):
        for kind in ("json", "yaml"):
            name = "settings." + kind
            for value, expected_type, bound in (([], "array", "minItems"),
                                                ({}, "object", "minProperties")):
                with self.subTest(kind=kind, expected_type=expected_type):
                    self.write(name, json.dumps({"settings": value}))
                    inspector = gate.SourceInspector(self.root)
                    anchor = {"kind": kind, "path": name, "pointer": ["settings"]}
                    self.rejects("Configuration anchor is empty", inspector.check, anchor)
                    self.rejects("Configuration anchor is empty", inspector.check,
                                 {**anchor, "expectedType": expected_type})
                    inspector.check({**anchor, "expectedType": expected_type, bound: 0})
                    self.rejects("not met", inspector.check,
                                 {**anchor, "expectedType": expected_type, bound: 1})
            for value, expected_type, bound in ((["module"], "array", "minItems"),
                                                ({"module": "skis-core"}, "object", "minProperties")):
                with self.subTest(kind=kind, expected_type=expected_type, nonempty=True):
                    self.write(name, json.dumps({"settings": value}))
                    inspector = gate.SourceInspector(self.root)
                    anchor = {"kind": kind, "path": name, "pointer": ["settings"],
                              "expectedType": expected_type, bound: 1}
                    inspector.check(anchor)
                    self.rejects("not met", inspector.check, {**anchor, bound: 2})
            for value in (False, 0):
                with self.subTest(kind=kind, scalar=value):
                    self.write(name, json.dumps({"settings": value}))
                    gate.SourceInspector(self.root).check(
                        {"kind": kind, "path": name, "pointer": ["settings"]})

    def test_configuration_collection_constraints_reject_invalid_declarations_and_types(self):
        for kind in ("json", "yaml"):
            name = "settings." + kind
            self.write(name, '{"settings": ["module"]}')
            inspector = gate.SourceInspector(self.root)
            anchor = {"kind": kind, "path": name, "pointer": ["settings"]}
            for declaration, error in [
                ({"expectedType": "list"}, "expectedType must be array or object"),
                ({"expectedType": None}, "expectedType must be array or object"),
                ({"expectedType": "object"}, "Configuration type must be object"),
                ({"minItems": 0}, "minItems requires expectedType=array"),
                ({"expectedType": "array", "minProperties": 0}, "minProperties requires expectedType=object"),
                *[({"expectedType": "array", "minItems": minimum}, "must be a nonnegative integer")
                  for minimum in (-1, True, False, 1.0, "1", None)],
            ]:
                with self.subTest(kind=kind, declaration=declaration):
                    self.rejects(error, inspector.check, {**anchor, **declaration})
            for value in ({"module": "skis-core"}, "skis-core", 1, False):
                with self.subTest(kind=kind, value=value):
                    self.write(name, json.dumps({"settings": value}))
                    self.rejects("Configuration type must be array", gate.SourceInspector(self.root).check,
                                 {**anchor, "expectedType": "array", "minItems": 1})

    def test_registered_module_anchor_requires_a_nonempty_array(self):
        repository = SCRIPT.parents[2]
        document = gate.read_json(repository / gate.MANIFEST)
        decision = next(row for row in document["decisions"] if row["id"] == "ADR-0012")
        anchor = next(item for item in decision["anchors"] if item["path"] == ".github/quality/modules.json")
        self.row["anchors"] = [anchor]
        decisions = gate.validate_manifest(self.document)
        for value, error in (([], "Configuration minItems=1 not met"),
                             ({}, "Configuration type must be array"),
                             ("skis-core", "Configuration type must be array")):
            with self.subTest(value=value):
                self.write(anchor["path"], json.dumps({"modules": value}))
                self.rejects(error, gate.check_sources, self.root, decisions)
        self.write(anchor["path"], '{"modules": [{"path": "skis-core"}]}')
        gate.check_sources(self.root, decisions)

    def test_plain_configuration_and_script_entries_are_not_contracts(self):
        self.write("settings.json", '{"schemaVersion": 1}')
        self.write("settings.yml", "enabled: true\n")
        self.write("pom.xml", '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                   '<properties><enabled>true</enabled></properties></project>')
        self.write("check.py", 'def main(): pass\nif __name__ == "__main__":\n    main()\n')
        for anchor, error in [
            ({"kind": "json", "path": "settings.json", "pointer": ["schemaVersion"]}, "Contracts must select"),
            ({"kind": "xml", "path": "pom.xml", "xpath": "m:properties/m:enabled"}, "Contracts must select"),
            ({"kind": "python", "path": "check.py", "entry": "main"}, "Contracts must select"),
            ({"kind": "yaml", "path": "settings.yml", "pointer": ["enabled"]}, "YAML contracts must belong"),
        ]:
            with self.subTest(kind=anchor["kind"]):
                inspector = gate.SourceInspector(self.root)
                inspector.check(anchor)
                self.rejects(error, inspector.check, anchor, True)

    def test_workflow_contracts_require_jobs_or_executable_steps(self):
        name = ".github/workflows/contracts.yml"
        self.write(name, "on: push\njobs:\n  gate:\n    name: Contract\n    runs-on: ubuntu-latest\n"
                   "    steps:\n      - name: Check\n        run: python check.py\n"
                   "      - name: Upload\n        uses: actions/upload-artifact@v7\n"
                   "      - name: Empty\n        run: '   '\n"
                   "  reused:\n    uses: ./.github/workflows/reused.yml\n")
        inspector = gate.SourceInspector(self.root)
        for pointer in (["jobs", "gate"], ["jobs", "reused"],
                        ["jobs", "gate", "steps", {"name": "Check"}, "run"],
                        ["jobs", "gate", "steps", {"name": "Upload"}, "uses"]):
            inspector.check({"kind": "yaml", "path": name, "pointer": pointer}, contract=True)
        for pointer in (["on"], ["jobs", "gate", "name"],
                        ["jobs", "gate", "steps", {"name": "Check"}, "name"]):
            self.rejects("must select a job or an executable step", inspector.check,
                         {"kind": "yaml", "path": name, "pointer": pointer}, True)
        self.rejects("step has no executable command", inspector.check,
                     {"kind": "yaml", "path": name,
                      "pointer": ["jobs", "gate", "steps", {"name": "Empty"}, "run"]}, True)
        for job in ("{}", "{runs-on: ubuntu-latest}", "{steps: [{run: echo test}]}",
                    "{runs-on: ubuntu-latest, steps: []}",
                    "{runs-on: ubuntu-latest, steps: [{name: Metadata only}]}"):
            with self.subTest(job=job):
                self.write(name, "jobs:\n  gate: " + job + "\n")
                self.rejects("has no executable steps", gate.SourceInspector(self.root).check,
                             {"kind": "yaml", "path": name, "pointer": ["jobs", "gate"]}, True)

    def test_python_contracts_require_registered_nonempty_unittest_suites(self):
        name = ".github/scripts/tests/test_owned.py"
        anchor = {"kind": "python-test", "path": name, "suite": "Owned"}
        self.row["contracts"] = [anchor]
        decisions = gate.validate_manifest(self.document)
        self.write("owned.py", "import unittest\nclass Owned(unittest.TestCase):\n    def test_contract(self): pass\n")
        self.rejects("must belong to test sources", gate.SourceInspector(self.root).check,
                     {**anchor, "path": "owned.py"}, True)
        for imported, base in (("import unittest", "unittest.TestCase"),
                               ("import unittest as unit", "unit.TestCase"),
                               ("from unittest import TestCase as Case", "Case")):
            self.write(name, imported + "\nclass Owned(" + base + "):\n    def test_contract(self): pass\n")
            gate.check_sources(self.root, decisions)
        (self.root / name).unlink()
        self.rejects("Missing anchor file", gate.check_sources, self.root, decisions)
        for source, error in [
            ("import unittest\nclass Other(unittest.TestCase):\n    def test_contract(self): pass\n",
             "Missing or ambiguous Python test suite"),
            ("class Owned:\n    def test_contract(self): pass\n", "must directly extend unittest.TestCase"),
            ("import unittest\nclass Owned(unittest.TestCase):\n    text = 'def test_contract(self): pass'\n",
             "has no test declarations"),
        ]:
            self.write(name, source)
            self.rejects(error, gate.check_sources, self.root, decisions)

    def test_failure_runner_rejects_missing_and_empty_discovery(self):
        for label, source, error in [
            ("missing", None, "Missing required architecture failure contracts"),
            ("empty", "", "No architecture failure contracts were discovered"),
            ("no_tests", "def helper(): pass\n", "No architecture failure contracts were discovered"),
            ("load_tests", "import unittest\ndef load_tests(loader, tests, pattern):\n"
             "    return unittest.TestSuite()\n", "No architecture failure contracts were discovered"),
        ]:
            with self.subTest(case=label):
                root = self.root / label
                if source is not None:
                    self.write(label + "/.github/scripts/tests/test_architecture_decisions.py", source)
                result = subprocess.run([sys.executable, str(RUNNER), "--root", str(root)],
                                        capture_output=True, text=True, encoding="utf-8", check=False)
                self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                self.assertIn(error, result.stderr)

    def test_failure_runner_preserves_test_and_import_failures(self):
        for label, source, expected in [
            ("passing", "import unittest\nclass Contract(unittest.TestCase):\n"
             "    def test_contract(self): self.assertTrue(True)\n", 0),
            ("failing", "import unittest\nclass Contract(unittest.TestCase):\n"
             "    def test_contract(self): self.fail('Expected failure')\n", 1),
            ("import_error", "raise ImportError('Expected import failure')\n", 1),
            ("unexpected_success", "import unittest\nclass Contract(unittest.TestCase):\n"
             "    @unittest.expectedFailure\n    def test_contract(self): pass\n", 1),
        ]:
            with self.subTest(case=label):
                self.write(label + "/.github/scripts/tests/test_architecture_decisions.py", source)
                result = subprocess.run([sys.executable, str(RUNNER), "--root", str(self.root / label)],
                                        capture_output=True, text=True, encoding="utf-8", check=False)
                self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
                self.assertIn("Ran 1 test", result.stderr)

    def test_failure_runner_rejects_skipped_suites_methods_and_subtests(self):
        for label, source in [
            ("suite", "import unittest\n@unittest.skip('Required suite skipped')\n"
             "class Contract(unittest.TestCase):\n    def test_contract(self): pass\n"),
            ("partial", "import unittest\nclass Contract(unittest.TestCase):\n"
             "    def test_passing(self): pass\n"
             "    @unittest.skip('Required method skipped')\n    def test_skipped(self): pass\n"),
            ("runtime", "import unittest\nclass Contract(unittest.TestCase):\n"
             "    def test_contract(self): self.skipTest('Required method skipped at runtime')\n"),
            ("subtest", "import unittest\nclass Contract(unittest.TestCase):\n"
             "    def test_contract(self):\n        with self.subTest(case='required'):\n"
             "            self.skipTest('Required subtest skipped')\n"),
        ]:
            with self.subTest(case=label):
                self.write(label + "/.github/scripts/tests/test_architecture_decisions.py", source)
                result = subprocess.run([sys.executable, str(RUNNER), "--root", str(self.root / label)],
                                        capture_output=True, text=True, encoding="utf-8", check=False)
                self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                self.assertIn("Required architecture failure contracts must not be skipped", result.stderr)

    def test_failure_runner_rejects_expected_failures(self):
        for label, source in [
            ("suite", "import unittest\n@unittest.expectedFailure\n"
             "class Contract(unittest.TestCase):\n"
             "    def test_contract(self): self.fail('Required suite failed')\n"),
            ("method", "import unittest\nclass Contract(unittest.TestCase):\n"
             "    @unittest.expectedFailure\n"
             "    def test_contract(self): self.fail('Required method failed')\n"),
            ("partial", "import unittest\nclass Contract(unittest.TestCase):\n"
             "    def test_passing(self): pass\n"
             "    @unittest.expectedFailure\n"
             "    def test_failed(self): self.fail('Required method failed')\n"),
            ("subtest", "import unittest\nclass Contract(unittest.TestCase):\n"
             "    @unittest.expectedFailure\n"
             "    def test_contract(self):\n        with self.subTest(case='required'):\n"
             "            self.fail('Required subtest failed')\n"),
        ]:
            with self.subTest(case=label):
                self.write(label + "/.github/scripts/tests/test_architecture_decisions.py", source)
                result = subprocess.run([sys.executable, str(RUNNER), "--root", str(self.root / label)],
                                        capture_output=True, text=True, encoding="utf-8", check=False)
                self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                self.assertIn("Required architecture failure contracts must not have expected failures",
                              result.stderr)

    def test_duplicate_configuration_keys_and_path_escape_are_rejected(self):
        self.write("settings.json", '{"id": 1, "id": 2}')
        self.rejects("Duplicate configuration key", gate.read_json, self.root / "settings.json")
        self.write("ci.yml", "jobs: {}\njobs: {}\n")
        self.rejects("Duplicate configuration key", gate.SourceInspector(self.root).load, "ci.yml", "yaml")
        for name in ("../outside.java", "C:/outside.java", "/outside.java"):
            self.rejects("Unsafe anchor path", gate.SourceInspector(self.root).path, name)

    def test_local_sync_catches_unregistered_missing_duplicate_and_different_states(self):
        self.local_docs()
        decisions = gate.validate_manifest(self.document)
        gate.check_local(self.root, decisions)
        self.write("docs/adr/0002-unregistered.md",
                   (self.root / "docs/adr/0001-fixture.md").read_text(encoding="utf-8").replace("ADR-0001", "ADR-0002"))
        self.rejects("documents and manifest ID sets differ", gate.check_local, self.root, decisions)
        (self.root / "docs/adr/0002-unregistered.md").unlink()
        self.write("docs/adr/nested/0002-unregistered.md", "# ADR-0002：Nested\n")
        self.rejects("directly under docs/adr", gate.check_local, self.root, decisions)
        (self.root / "docs/adr/nested/0002-unregistered.md").unlink()
        self.write("docs/adr/0001-duplicate.md", (self.root / "docs/adr/0001-fixture.md").read_text(encoding="utf-8"))
        self.rejects("Duplicate local ADR", gate.check_local, self.root, decisions)
        (self.root / "docs/adr/0001-duplicate.md").unlink()
        index = self.root / "docs/adr/README.md"
        original = index.read_text(encoding="utf-8")
        index.write_text(original.replace("Implemented", "Accepted"), encoding="utf-8")
        self.rejects("body/index mismatch", gate.check_local, self.root, decisions)
        index.write_text(original.splitlines()[0] + "\n", encoding="utf-8")
        self.rejects("index and manifest ID sets differ", gate.check_local, self.root, decisions)
        index.write_text(original + original.splitlines()[-1] + "\n", encoding="utf-8")
        self.rejects("Duplicate ADR index entry", gate.check_local, self.root, decisions)
        self.local_docs()
        self.row["implementedVersion"] = "0.2.6-SNAPSHOT"
        self.rejects("local/manifest metadata mismatch", gate.check_local, self.root, decisions)

    def check_event(self, name, event, supplied):
        self.write("target/event.json", json.dumps(event))
        with mock.patch.dict(os.environ, {"GITHUB_EVENT_PATH": str(self.root / "target/event.json"),
                                        "GITHUB_EVENT_NAME": name,
                                        "GITHUB_REPOSITORY": "example/skis",
                                        "GITHUB_SHA": self.git("rev-parse", "HEAD")}):
            return gate.event_baseline(self.root, supplied)

    def test_manual_baseline_must_be_registered_default_branch_tip(self):
        branch = self.git("symbolic-ref", "--short", "HEAD")
        self.git("remote", "add", "origin", str(self.root))
        event = {"repository": {"full_name": "example/skis", "default_branch": branch},
                 "inputs": {"architecture_baseline_commit": self.base}}
        self.rejects("already registered baseline", self.check_event, "workflow_dispatch", event, self.base)
        registered = self.commit()
        self.rejects("current default-branch tip", self.check_event, "workflow_dispatch", event, self.base)
        event["inputs"]["architecture_baseline_commit"] = registered
        self.assertEqual(registered, self.check_event("workflow_dispatch", event, registered))
        self.rejects("trusted event", self.check_event, "workflow_dispatch", event, self.base)

    def test_pr_baseline_is_bound_to_the_actual_merge_parents(self):
        baseline = self.commit()
        branch = self.git("symbolic-ref", "--short", "HEAD")
        self.git("checkout", "-qb", "feature")
        self.write("feature.txt", "Feature\n")
        head = self.commit()
        self.git("checkout", "-q", branch)
        self.git("merge", "--no-ff", "-qm", "Merge fixture", "feature")
        event = {"repository": {"full_name": "example/skis"},
                 "pull_request": {"base": {"sha": baseline}, "head": {"sha": head}}}
        self.assertEqual(baseline, self.check_event("pull_request", event, baseline))
        event["pull_request"]["base"]["sha"] = self.base
        self.rejects("event merge commit", self.check_event, "pull_request", event, self.base)

    def invoke(self, mode, baseline, event=None):
        environment = {key: value for key, value in os.environ.items() if not key.startswith("GITHUB_")}
        if event is not None:
            self.write("target/event.json", json.dumps(event))
            environment.update(GITHUB_EVENT_PATH=str(self.root / "target/event.json"),
                               GITHUB_EVENT_NAME="push", GITHUB_REPOSITORY="example/skis",
                               GITHUB_SHA=self.git("rev-parse", "HEAD"))
        report = self.root / "target" / (mode + ".json")
        result = subprocess.run([sys.executable, str(SCRIPT), mode, "--root", str(self.root),
                                 "--baseline", baseline, "--report", str(report)],
                                env=environment, capture_output=True, text=True, encoding="utf-8", check=False)
        return result.returncode, gate.read_json(report)

    def test_local_and_clean_checkout_ci_reports_have_separate_scopes(self):
        self.local_docs()
        code, report = self.invoke("local", self.base)
        self.assertEqual(0, code, report)
        self.assertTrue(report["localAdrChecked"])
        self.commit()
        # Clone excludes ignored local ADR and runs against committed inputs only.
        clean = self.root / "target/clean"
        self.git("clone", "-q", "--no-hardlinks", str(self.root), str(clean))
        self.root = clean
        event = {"repository": {"full_name": "example/skis"}, "before": self.base}
        code, report = self.invoke("ci", self.base, event)
        self.assertEqual(0, code, report)
        self.assertFalse(report["localAdrChecked"])
        self.assertFalse(report["semanticContractsExecuted"])
        self.assertNotIn("Local design text", json.dumps(report))
        event["before"] = "a" * 40
        code, report = self.invoke("ci", self.base, event)
        self.assertEqual(1, code)
        self.assertIn("trusted event", report["error"])

    def test_invalid_optional_objects_have_structured_failure_reports(self):
        for mode in ("local", "ci"):
            for field in ("change", "disposition"):
                for value in (None, []):
                    with self.subTest(mode=mode, field=field, value=value):
                        invalid = copy.deepcopy(self.document)
                        invalid["decisions"][0][field] = value
                        self.write(gate.MANIFEST, json.dumps(invalid))
                        code, report = self.invoke(mode, self.base)
                        self.assertEqual(1, code)
                        self.assertEqual("failed", report["result"])
                        self.assertEqual(f"ADR-0001: {field} must be an object", report["error"])
                        self.assertFalse(report["localAdrChecked"])
                        self.assertFalse(report["semanticContractsExecuted"])

    def test_ci_reports_missing_manifest_instead_of_silent_success(self):
        baseline = self.commit()
        self.git("rm", gate.MANIFEST)
        self.git("commit", "-qm", "Remove full manifest")
        event = {"repository": {"full_name": "example/skis"}, "before": baseline}
        code, report = self.invoke("ci", baseline, event)
        self.assertEqual(1, code)
        self.assertEqual("failed", report["result"])


if __name__ == "__main__":
    unittest.main()
