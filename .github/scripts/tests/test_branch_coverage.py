"""Policy and corrupted-evidence fixtures; run by engineering-gates, not production."""

import copy
from contextlib import contextmanager
import hashlib
import importlib.util
from io import BytesIO
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import Mock, patch
from types import SimpleNamespace
import zipfile


SPEC = importlib.util.spec_from_file_location("branch_coverage", Path(__file__).parents[1] / "branch_coverage.py")
coverage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(coverage)


def row(covered=80, missed=20, classes=2):
    return {"covered": covered, "missed": missed, "classes": classes,
            "threshold": coverage.score(covered, missed), "reportSha256": "report",
            "evidence": {"runId": "1"}}


def fixture():
    scope = {"revision": 1, "jacoco": coverage.JACOCO, "java": 21, "release": 21,
             "precision": coverage.PRECISION,
             "modules": {"skis-jdbc": {"branches": "required", "generatedExclusions": []}}}
    baseline = {"schema": 1, "state": "active", "scope": scope, "scopeChanges": [],
                "registration": {"runId": "1"}, "modules": {"skis-jdbc": row()}}
    return baseline, copy.deepcopy(baseline), {"scope": scope, "modules": {"skis-jdbc": row()}}


def scope_change(baseline, previous, measurement):
    baseline["scopeChanges"] = [{
        "previousScope": previous["scope"], "previousModules": previous["modules"],
        "reason": "Generated source scope updated",
        "mapping": {name: {"targets": [name], "reason": "Retained module"}
                    for name in previous["modules"]}, "newModules": []}]
    measurement["scope"] = baseline["scope"]


def authoritative_fixture(event="push"):
    _, _, measurement = fixture()
    commit, head, base = "a" * 40, "b" * 40, "c" * 40
    producer = {path: "d" * 40 for path in coverage.PRODUCER_FILES}
    measurement["session"] = {
        "commit": commit, "headCommit": head if event == "pull_request" else commit,
        "comparison": base, "previous": None, "event": event, "producer": producer,
        "runId": "1", "attempt": "1", "repository": "owner/repo"}
    if event == "workflow_dispatch":
        measurement["session"]["defaultBranch"] = "main"
    xml = (b'<report name="skis-jdbc"><package name="io/skis">'
           b'<class name="io/skis/Example"><counter type="BRANCH" covered="80" missed="20"/>'
           b'</class><class name="io/skis/Other"/></package>'
           b'<counter type="BRANCH" covered="80" missed="20"/></report>')
    measurement["modules"]["skis-jdbc"].pop("evidence")
    measurement["modules"]["skis-jdbc"]["reportSha256"] = hashlib.sha256(xml).hexdigest()
    api_head = head if event == "pull_request" else commit
    required = ("Collect module coverage and candidate", "Upload authoritative coverage evidence",
                "Upload build test and raw fork evidence")
    responses = {
        "actions/workflows/ci.yml": {"id": 7, "path": coverage.WORKFLOW},
        "actions/runs/1/attempts/1": {
            "id": 1, "run_attempt": 1, "workflow_id": 7, "path": coverage.WORKFLOW,
            "repository": {"full_name": "owner/repo"}, "status": "completed",
            "event": event, "head_sha": api_head},
        f"git/commits/{commit}": {
            "sha": commit, "parents": [{"sha": base}, {"sha": head}], "tree": {"sha": "e" * 40}},
        f"git/trees/{'e' * 40}?recursive=1": {
            "truncated": False,
            "tree": [{"path": path, "type": "blob", "sha": value} for path, value in producer.items()]},
        "actions/runs/1/attempts/1/jobs?per_page=100": {"jobs": [{
            "name": "Engineering gates (Java 21 coverage)", "head_sha": api_head,
            "steps": [{"name": name, "conclusion": "success"} for name in required]}]},
        "actions/runs/1/artifacts?per_page=100&page=1": {"artifacts": [{
            "name": "branch-coverage-java-21-1-1", "expired": False,
            "workflow_run": {"id": 1, "head_sha": api_head},
            "archive_download_url": "https://api.github.com/repos/owner/repo/actions/artifacts/1/zip"}]}}
    return measurement, xml, producer, responses


def evidence_archive(measurement, xml):
    stream = BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        archive.writestr("measurement.json", json.dumps(measurement))
        archive.writestr("skis-jdbc/jacoco.xml", xml)
    stream.seek(0)
    return stream


@contextmanager
def evidence_service(measurement, xml, producer, responses):
    opener = Mock()
    opener.open.side_effect = lambda *args, **kwargs: evidence_archive(measurement, xml)
    with patch.object(coverage, "api_json", side_effect=lambda repo, endpoint: responses[endpoint]), \
            patch.object(coverage, "producer_inputs", return_value=producer), \
            patch.dict(coverage.os.environ, {"GH_TOKEN": "fixture-token"}), \
            patch.object(coverage.urllib.request, "build_opener", return_value=opener):
        yield opener


class RatchetTest(unittest.TestCase):
    def test_unchanged_and_registered_raise_pass(self):
        baseline, previous, actual = fixture()
        coverage.compare(baseline, previous, actual)
        baseline["modules"]["skis-jdbc"] = row(85, 15)
        actual["modules"]["skis-jdbc"] = row(85, 15)
        coverage.compare(baseline, previous, actual)

    def test_regression_fails(self):
        baseline, previous, actual = fixture()
        actual["modules"]["skis-jdbc"] = row(79, 21)
        with self.assertRaisesRegex(coverage.GateError, "regressed"):
            coverage.compare(baseline, previous, actual)

    def test_same_change_lowering_threshold_fails(self):
        baseline, previous, actual = fixture()
        baseline["modules"]["skis-jdbc"] = row(70, 30)
        actual["modules"]["skis-jdbc"] = row(70, 30)
        with self.assertRaisesRegex(coverage.GateError, "lowered"):
            coverage.compare(baseline, previous, actual)

    def test_rollback_after_raise_above_initial_line_still_fails(self):
        baseline, previous, actual = fixture()
        previous["modules"]["skis-jdbc"] = row(90, 10)
        baseline["modules"]["skis-jdbc"] = row(85, 15)
        actual["modules"]["skis-jdbc"] = row(85, 15)
        with self.assertRaisesRegex(coverage.GateError, "lowered"):
            coverage.compare(baseline, previous, actual)

    def test_improvement_requires_registration(self):
        baseline, previous, actual = fixture()
        actual["modules"]["skis-jdbc"] = row(81, 19)
        with self.assertRaisesRegex(coverage.GateError, "improved"):
            coverage.compare(baseline, previous, actual)

    def test_no_rounded_float_comparison(self):
        self.assertEqual(3333, coverage.score(1, 2))
        self.assertEqual(9999, coverage.score(100000, 1))
        self.assertEqual(0, coverage.score(1, 10000))
        for counters in [(0, 0), (-1, 2), (True, 1), (1.0, 2)]:
            with self.assertRaises(coverage.GateError):
                coverage.score(*counters)

    def test_missing_measurement_and_zero_classes_fail(self):
        for missing in (True, False):
            baseline, previous, actual = fixture()
            if missing:
                actual["modules"].clear()
            else:
                actual["modules"]["skis-jdbc"]["classes"] = 0
            with self.assertRaises(coverage.GateError):
                coverage.compare(baseline, previous, actual)

    def test_pending_cannot_pass_or_reset_an_active_baseline(self):
        baseline, previous, actual = fixture()
        baseline["state"] = coverage.PENDING
        with self.assertRaisesRegex(coverage.GateError, "registered"):
            coverage.compare(baseline, previous, actual)

    def test_cannot_replace_first_registration(self):
        baseline, previous, actual = fixture()
        baseline["registration"] = {"runId": "2"}
        with self.assertRaisesRegex(coverage.GateError, "bootstrapped"):
            coverage.compare(baseline, previous, actual)

    def test_na_requires_classes_and_real_zero_counters(self):
        baseline, _, actual = fixture()
        baseline["scope"]["modules"]["skis-jdbc"]["branches"] = "none"
        na = {"covered": 0, "missed": 0, "classes": 2, "threshold": "N/A"}
        baseline["modules"]["skis-jdbc"] = na
        actual["modules"]["skis-jdbc"] = dict(na)
        coverage.compare(baseline, None, actual)
        actual["modules"]["skis-jdbc"]["missed"] = 2
        with self.assertRaisesRegex(coverage.GateError, "zero-branch"):
            coverage.compare(baseline, None, actual)

    def test_required_module_with_zero_branch_denominator_fails(self):
        baseline, previous, actual = fixture()
        actual["modules"]["skis-jdbc"].update(covered=0, missed=0, threshold=0)
        with self.assertRaisesRegex(coverage.GateError, "zero branches"):
            coverage.compare(baseline, previous, actual)

    def test_scope_revision_requires_preserved_history_and_mapping(self):
        baseline, previous, actual = fixture()
        changed = copy.deepcopy(baseline["scope"])
        changed["revision"] = 2
        changed["modules"]["skis-jdbc"]["generatedExclusions"] = [{"class": "io/skis/Generated", "source": "target/generated-sources/Generated.java"}]
        baseline["scope"] = actual["scope"] = changed
        with self.assertRaisesRegex(coverage.GateError, "transition"):
            coverage.compare(baseline, previous, actual)
        baseline["scopeChanges"] = [{"previousScope": previous["scope"],
                                      "previousModules": previous["modules"], "reason": "Generated source added",
                                      "mapping": {"skis-jdbc": {"targets": ["skis-jdbc"], "reason": "Same module"}}}]
        coverage.compare(baseline, previous, actual)
        baseline["scopeChanges"][0]["previousModules"] = {}
        with self.assertRaisesRegex(coverage.GateError, "retain"):
            coverage.compare(baseline, previous, actual)

    def test_revision_bump_alone_cannot_rebuild_a_lower_baseline(self):
        baseline, previous, actual = fixture()
        baseline["scope"] = copy.deepcopy(baseline["scope"])
        baseline["scope"]["revision"] = 2
        actual["scope"] = baseline["scope"]
        with self.assertRaisesRegex(coverage.GateError, "revision-only"):
            coverage.compare(baseline, previous, actual)

    def test_precision_text_cannot_reset_historical_thresholds(self):
        for precision in (None, "floor((covered * 10000) / (covered + missed))", "0.1 percent"):
            with self.subTest(precision=precision):
                baseline, previous, actual = fixture()
                baseline["scope"]["revision"] = 2
                if precision is None:
                    baseline["scope"].pop("precision")
                else:
                    baseline["scope"]["precision"] = precision
                baseline["modules"]["skis-jdbc"] = actual["modules"]["skis-jdbc"] = row(79, 21)
                scope_change(baseline, previous, actual)
                with self.assertRaisesRegex(coverage.GateError, "Unsupported coverage precision"):
                    coverage.compare(baseline, previous, actual)

    def test_unsupported_historical_precision_cannot_reset_thresholds(self):
        baseline, previous, actual = fixture()
        previous["scope"]["precision"] = "arbitrary description"
        with self.assertRaisesRegex(coverage.GateError, "Unsupported coverage precision"):
            coverage.compare(baseline, previous, actual)

    def test_another_modules_scope_change_does_not_allow_lowering_jdbc(self):
        for lower_baseline in (False, True):
            with self.subTest(lower_baseline=lower_baseline):
                baseline, _, measurement = fixture()
                baseline["scope"]["modules"]["skis-metadata"] = {
                    "branches": "required", "generatedExclusions": []}
                baseline["modules"]["skis-metadata"] = row()
                previous = copy.deepcopy(baseline)
                measurement["modules"]["skis-metadata"] = row()
                baseline["scope"]["revision"] = 2
                baseline["scope"]["modules"]["skis-metadata"]["generatedExclusions"] = [
                    {"class": "io/skis/Generated", "source": "target/generated-sources/Generated.java"}]
                scope_change(baseline, previous, measurement)
                measurement["modules"]["skis-jdbc"] = row(79, 21)
                if lower_baseline:
                    baseline["modules"]["skis-jdbc"] = row(79, 21)
                with self.assertRaisesRegex(coverage.GateError, "skis-jdbc:.*(lowered|regressed)"):
                    coverage.compare(baseline, previous, measurement)

    def test_changed_module_can_rebuild_while_unchanged_module_retains_its_evidence(self):
        baseline, _, measurement = fixture()
        baseline["scope"]["modules"]["skis-metadata"] = {
            "branches": "required", "generatedExclusions": []}
        baseline["modules"]["skis-metadata"] = row()
        previous = copy.deepcopy(baseline)
        measurement["modules"]["skis-metadata"] = row(70, 30)
        measurement["session"] = {"commit": "a" * 40, "runId": "2", "attempt": "1", "repository": "owner/repo"}
        measurement["scope"]["revision"] = 2
        measurement["scope"]["modules"]["skis-metadata"]["generatedExclusions"] = [
            {"class": "io/skis/Generated", "source": "target/generated-sources/Generated.java"}]
        candidate = coverage.make_candidate(previous, measurement)
        scope_change(candidate, previous, measurement)
        coverage.compare(candidate, previous, measurement)
        self.assertEqual(previous["modules"]["skis-jdbc"], candidate["modules"]["skis-jdbc"])
        self.assertEqual(7000, candidate["modules"]["skis-metadata"]["threshold"])
        candidate["scopeChanges"][0]["mapping"]["skis-jdbc"]["targets"] = []
        candidate["scopeChanges"][0]["newModules"] = ["skis-jdbc"]
        candidate["modules"]["skis-jdbc"] = measurement["modules"]["skis-jdbc"] = row(79, 21)
        with self.assertRaisesRegex(coverage.GateError, "skis-jdbc: tracked threshold was lowered"):
            coverage.compare(candidate, previous, measurement)


class CollectionTest(unittest.TestCase):
    @contextmanager
    def build(self, previous=None, tracked=None, producer_changed=False):
        """Exercise filesystem collection; substitute only external Git/Java and Actions services."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            template, _, producer, responses = authoritative_fixture()
            if producer_changed:
                path = ".github/scripts/branch_coverage.py"
                producer[path] = "f" * 40
                for entry in responses[f"git/trees/{'e' * 40}?recursive=1"]["tree"]:
                    if entry["path"] == path:
                        entry["sha"] = producer[path]
            policy = copy.deepcopy(template["scope"])
            policy["modules"]["skis-jdbc"].pop("generatedExclusions")
            pending = {"schema": 1, "state": coverage.PENDING, "registration": None,
                       "scopeChanges": [], "modules": {}}
            coverage.write_json(root / coverage.POLICY, policy)
            coverage.write_json(root / coverage.BASELINE, pending if tracked is None else tracked)
            coverage.write_json(root / ".github/quality/modules.json", {"modules": [
                {"path": "skis-jdbc", "kind": "production"},
                {"path": "skis-integration-tests", "kind": "test"}]})
            namespace = 'xmlns="http://maven.apache.org/POM/4.0.0"'
            modules = ("skis-jdbc", "skis-integration-tests")
            (root / "pom.xml").write_text(
                f'<project {namespace}><modules>'
                + ''.join(f'<module>{name}</module>' for name in modules)
                + '</modules></project>', encoding="utf-8")
            forks, reports = [], []
            session = {**template["session"], "previous": copy.deepcopy(previous),
                       "started": time.time() - 10, "command": coverage.COMMAND,
                       "policySha256": coverage.digest(root / coverage.POLICY),
                       "baselineSha256": coverage.digest(root / coverage.BASELINE)}
            class_name, class_id = "io/skis/jdbc/Fixture", "0123456789abcdef"
            for name, phase, branches in ((modules[0], "unit", range(60)),
                                          (modules[1], "postgres", range(60, 80))):
                module = root / name
                module.mkdir()
                (module / "pom.xml").write_text(f'<project {namespace}/>', encoding="utf-8")
                test = module / "src/test/java/FixtureTest.java"
                test.parent.mkdir(parents=True)
                test.write_text("// Test discovery fixture; never compiled.\n", encoding="utf-8")
                report = module / "target" / ("surefire-reports" if phase == "unit" else "failsafe-reports") / "TEST-FixtureTest.xml"
                report.parent.mkdir(parents=True)
                report.write_text('<testsuite name="FixtureTest" tests="1" errors="0" failures="0" skipped="0"/>',
                                  encoding="utf-8")
                reports.append(report)
                fork = module / "target/jacoco" / f"{phase}-1.exec"
                coverage.write_json(fork, {"covered": list(branches), "sessions": [
                    f"{session['commit']}:{name}:{phase}-1"]})
                forks.append(fork)
            class_file = root / f"skis-jdbc/target/classes/{class_name}.class"
            class_file.parent.mkdir(parents=True)
            # Only the header is read by collect; no actual compiler or JVM is invoked.
            class_file.write_bytes(b"\xca\xfe\xba\xbe\x00\x00\x00\x41")
            context = SimpleNamespace(root=root, session=session, session_path=root / "session.json",
                                      output=root / "coverage", policy=policy, forks=forks, reports=reports,
                                      producer=producer, responses=responses, merge_inputs=[])
            coverage.write_json(context.session_path, session)

            def git(_, *args):
                if args == ("rev-parse", "HEAD"):
                    return session["commit"]
                self.assertEqual(("status", "--porcelain", "--untracked-files=no"), args)
                return ""

            def java(args, cwd=None):
                self.assertEqual(root, cwd)
                self.assertEqual(["java", "-jar", str(root / "target/coverage-tools/jacococli.jar")], args[:3])
                command = args[3]
                if command == "version":
                    return coverage.JACOCO + ".fixture"
                if command == "execinfo":
                    data = coverage.read_json(args[4])
                    return (''.join(f'Session "{name}": dates\n' for name in data["sessions"])
                            + f"{class_id} {len(data['covered'])} of 100 {class_name}\n")
                if command == "merge":
                    marker = args.index("--destfile")
                    context.merge_inputs = [Path(path) for path in args[4:marker]]
                    inputs = [coverage.read_json(path) for path in context.merge_inputs]
                    coverage.write_json(args[marker + 1], {
                        "covered": sorted({branch for item in inputs for branch in item["covered"]}),
                        "sessions": [name for item in inputs for name in item["sessions"]]})
                    return "[INFO] merged fixture data"
                if command == "classinfo":
                    self.assertEqual(class_file.parents[3], Path(args[4]))
                    return f" 100 100 20 1 50 class 0x{class_id} {class_name}\n"
                self.assertEqual("report", command)
                filtered = Path(args[args.index("--classfiles") + 1])
                self.assertEqual(class_file.read_bytes(), (filtered / f"{class_name}.class").read_bytes())
                covered = len(coverage.read_json(args[4])["covered"])
                counter = f'<counter type="BRANCH" covered="{covered}" missed="{100 - covered}"/>'
                xml = (f'<report name="skis-jdbc"><package name="io/skis/jdbc">'
                       f'<class name="{class_name}">{counter}</class></package>{counter}</report>')
                Path(args[args.index("--xml") + 1]).write_text(xml, encoding="utf-8")
                html = Path(args[args.index("--html") + 1])
                html.mkdir()
                (html / "index.html").write_text("<!doctype html><title>Fixture</title>", encoding="utf-8")
                return "[INFO] created fixture report"

            with patch.object(coverage, "git", side_effect=git), patch.object(coverage, "run", side_effect=java):
                yield context

    def register_and_check(self, context, measurement):
        candidate = coverage.read_json(context.output / "branch-baseline.candidate.json")
        coverage.write_json(context.root / coverage.BASELINE, candidate)
        xml = (context.output / "skis-jdbc/jacoco.xml").read_bytes()
        with evidence_service(measurement, xml, context.producer, context.responses) as opener, \
                patch.object(coverage, "trusted_previous", return_value=context.session["previous"]), \
                patch("builtins.print"):
            coverage.check(context.root, context.output)
            opener.open.assert_called_once()
        return candidate

    def test_collect_merges_unit_and_postgres_then_registers_and_authenticates(self):
        with self.build() as context:
            coverage.collect(context.root, context.session_path, context.output)
            measurement = coverage.read_json(context.output / "measurement.json")
            actual = measurement["modules"]["skis-jdbc"]
            self.assertEqual((80, 20, 1, 8000), tuple(actual[key] for key in ("covered", "missed", "classes", "threshold")))
            self.assertEqual(set(context.forks), set(context.merge_inputs))
            self.assertEqual({path.relative_to(context.root).as_posix() for path in context.forks},
                             {item["path"] for item in measurement["execFiles"]})
            self.assertTrue((context.output / "skis-jdbc/html/index.html").is_file())
            self.assertFalse((context.output / "skis-jdbc/classes").exists())
            self.assertIn("80.00", (context.output / "summary.md").read_text(encoding="utf-8"))
            with patch.object(coverage, "trusted_previous", return_value=None), \
                    self.assertRaisesRegex(coverage.GateError, "registered"):
                coverage.check(context.root, context.output)
            self.register_and_check(context, measurement)

    def test_collect_rejects_missing_unit_or_postgres_fork_or_report(self):
        for missing in ("unit-fork", "postgres-fork", "unit-report", "postgres-report"):
            with self.subTest(missing=missing), self.build() as context:
                paths = context.forks if missing.endswith("fork") else context.reports
                paths[0 if missing.startswith("unit") else 1].unlink()
                with self.assertRaisesRegex(coverage.GateError, "missing .*execution data"):
                    coverage.collect(context.root, context.session_path, context.output)
                self.assertFalse((context.output / "branch-baseline.candidate.json").exists())

    def test_collect_rejects_precision_metadata_before_generating_a_candidate(self):
        for precision in (None, "floor((covered * 10000) / (covered + missed))"):
            with self.subTest(precision=precision), self.build() as context:
                if precision is None:
                    context.policy.pop("precision")
                else:
                    context.policy["precision"] = precision
                coverage.write_json(context.root / coverage.POLICY, context.policy)
                context.session["policySha256"] = coverage.digest(context.root / coverage.POLICY)
                coverage.write_json(context.session_path, context.session)
                with self.assertRaisesRegex(coverage.GateError, "Unsupported coverage precision"):
                    coverage.collect(context.root, context.session_path, context.output)
                self.assertFalse((context.output / "branch-baseline.candidate.json").exists())

    def test_collect_refreshes_an_unregistered_raise_after_a_producer_change(self):
        source, _, _, _ = authoritative_fixture()
        previous = coverage.make_candidate(None, source)
        previous["modules"]["skis-jdbc"].update(covered=75, missed=25, threshold=7500)
        tracked = coverage.make_candidate(previous, source)
        tracked["modules"]["skis-jdbc"]["evidence"].update(
            commit="9" * 40, runId="9", artifact="branch-coverage-java-21-9-1")
        with self.build(previous=previous, tracked=tracked, producer_changed=True) as context:
            coverage.collect(context.root, context.session_path, context.output)
            measurement = coverage.read_json(context.output / "measurement.json")
            candidate = self.register_and_check(context, measurement)
            self.assertEqual(previous["registration"], candidate["registration"])
            self.assertEqual(coverage.evidence_for(measurement), candidate["modules"]["skis-jdbc"]["evidence"])

    def test_collect_refreshes_first_registration_after_a_producer_change(self):
        source, _, _, _ = authoritative_fixture()
        tracked = coverage.make_candidate(None, source)
        tracked["registration"].update(commit="9" * 40, runId="9", artifact="branch-coverage-java-21-9-1")
        tracked["modules"]["skis-jdbc"]["evidence"] = copy.deepcopy(tracked["registration"])
        pending = {"schema": 1, "state": coverage.PENDING, "registration": None,
                   "scopeChanges": [], "modules": {}}
        for previous in (None, pending):
            with self.subTest(previous=previous), self.build(
                    previous=previous, tracked=tracked, producer_changed=True) as context:
                coverage.collect(context.root, context.session_path, context.output)
                measurement = coverage.read_json(context.output / "measurement.json")
                candidate = self.register_and_check(context, measurement)
                self.assertEqual(coverage.evidence_for(measurement), candidate["registration"])
                self.assertEqual([], candidate["scopeChanges"])


class ComparisonContextTest(unittest.TestCase):
    def test_manual_comparison_must_be_the_current_default_branch_tip(self):
        with tempfile.TemporaryDirectory() as directory:
            event_path = Path(directory) / "event.json"
            event_path.write_text(json.dumps({"repository": {
                "full_name": "owner/repo", "default_branch": "main"}}), encoding="utf-8")
            environment = {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_EVENT_PATH": str(event_path),
                           "GITHUB_REPOSITORY": "owner/repo", "GITHUB_SHA": "a" * 40}
            with patch.dict(coverage.os.environ, environment), \
                    patch.object(coverage, "api_json", return_value={"commit": {"sha": "c" * 40}}) as api:
                for stale in ("b" * 40, "d" * 40):
                    with self.subTest(stale=stale), self.assertRaisesRegex(coverage.GateError, "default-branch tip"):
                        coverage.comparison_context(Path(directory), stale)
                self.assertEqual("main", coverage.comparison_context(Path(directory), "c" * 40)["defaultBranch"])
                api.assert_called_with("owner/repo", "branches/main")

    def test_start_rejects_an_old_manual_baseline_before_reading_its_lower_threshold(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            event_path = root / "event.json"
            event_path.write_text(json.dumps({"repository": {
                "full_name": "owner/repo", "default_branch": "main"}}), encoding="utf-8")
            environment = {"GITHUB_JOB": "engineering-gates", "GITHUB_EVENT_NAME": "workflow_dispatch",
                           "GITHUB_EVENT_PATH": str(event_path), "GITHUB_REPOSITORY": "owner/repo",
                           "GITHUB_SHA": "a" * 40}
            baseline, _, _ = fixture()
            with patch.dict(coverage.os.environ, environment), \
                    patch.object(coverage, "git", side_effect=["", "a" * 40]), \
                    patch.object(coverage, "api_json", return_value={"commit": {"sha": "c" * 40}}), \
                    patch.object(coverage, "trusted_previous", return_value=baseline) as previous:
                with self.assertRaisesRegex(coverage.GateError, "default-branch tip"):
                    coverage.start(root, root / "session.json", "b" * 40)
                previous.assert_not_called()
                self.assertFalse((root / "session.json").exists())

    def test_pr_uses_the_event_base_and_requires_the_actual_merge_parents(self):
        with tempfile.TemporaryDirectory() as directory:
            event_path = Path(directory) / "event.json"
            event_path.write_text(json.dumps({
                "repository": {"full_name": "owner/repo"},
                "pull_request": {"base": {"sha": "c" * 40}, "head": {"sha": "b" * 40}}}), encoding="utf-8")
            environment = {"GITHUB_EVENT_NAME": "pull_request", "GITHUB_EVENT_PATH": str(event_path),
                           "GITHUB_REPOSITORY": "owner/repo", "GITHUB_SHA": "a" * 40}
            with patch.dict(coverage.os.environ, environment), \
                    patch.object(coverage, "git", return_value=f"{'c' * 40} {'b' * 40}") as git:
                self.assertEqual("b" * 40, coverage.comparison_context(Path(directory), "c" * 40)["headCommit"])
                with self.assertRaisesRegex(coverage.GateError, "Comparison must match"):
                    coverage.comparison_context(Path(directory), "d" * 40)
                git.return_value = f"{'d' * 40} {'b' * 40}"
                with self.assertRaisesRegex(coverage.GateError, "event's merge commit"):
                    coverage.comparison_context(Path(directory), "c" * 40)

    def test_push_uses_before_and_does_not_query_a_moving_branch(self):
        with tempfile.TemporaryDirectory() as directory:
            event_path = Path(directory) / "event.json"
            event_path.write_text(json.dumps({"repository": {"full_name": "owner/repo"},
                                              "before": "c" * 40}), encoding="utf-8")
            environment = {"GITHUB_EVENT_NAME": "push", "GITHUB_EVENT_PATH": str(event_path),
                           "GITHUB_REPOSITORY": "owner/repo", "GITHUB_SHA": "a" * 40}
            with patch.dict(coverage.os.environ, environment), patch.object(coverage, "api_json") as api:
                self.assertEqual("a" * 40, coverage.comparison_context(Path(directory), "c" * 40)["headCommit"])
                with self.assertRaisesRegex(coverage.GateError, "Comparison must match"):
                    coverage.comparison_context(Path(directory), "b" * 40)
                api.assert_not_called()


class EvidenceTest(unittest.TestCase):
    def test_absent_comparison_cannot_fall_back_to_head(self):
        for commit in (None, "", "HEAD", "0" * 40):
            with self.assertRaisesRegex(coverage.GateError, "comparison commit"):
                coverage.trusted_previous(Path("."), commit)

    def test_deleted_historical_baseline_cannot_restart_registration(self):
        with patch.object(coverage, "git", side_effect=["", "b" * 40]), \
                patch.object(coverage.subprocess, "run", return_value=SimpleNamespace(returncode=1)):
            with self.assertRaisesRegex(coverage.GateError, "was deleted"):
                coverage.trusted_previous(Path("."), "a" * 40)

    def test_empty_stale_foreign_and_sessionless_fork_data_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "unit-1.exec"
            path.write_bytes(b"data")
            session = {"started": 0, "commit": "a" * 40}
            info = f'Session "{session["commit"]}:skis-jdbc:unit-1": dates\n0123456789abcdef    3 of   4   io/skis/Example\n'
            coverage.validate_fork(path, "skis-jdbc", session, info)
            with self.assertRaisesRegex(coverage.GateError, "Foreign"):
                coverage.validate_fork(path, "skis-query", session, info)
            with self.assertRaisesRegex(coverage.GateError, "Foreign"):
                coverage.validate_fork(path, "skis-jdbc", session, info.split("\n", 1)[1])
            with self.assertRaisesRegex(coverage.GateError, "stale"):
                coverage.validate_fork(path, "skis-jdbc", {**session, "started": time.time() + 60}, info)
            path.write_bytes(b"")
            with self.assertRaisesRegex(coverage.GateError, "Empty"):
                coverage.validate_fork(path, "skis-jdbc", session, info)

    def test_exec_and_class_info_parse_and_mismatched_id_fails(self):
        classes = coverage.parse_classinfo("     5      2      3      1      2   class 0x0123456789abcdef io/skis/Example\n")
        executed = coverage.parse_execinfo("0123456789abcdef    3 of   4   io/skis/Example\n")
        coverage.match_class_ids(classes, executed)
        executed["io/skis/Example"].add("ffffffffffffffff")
        with self.assertRaisesRegex(coverage.GateError, "Class ID mismatch"):
            coverage.match_class_ids(classes, executed)
        for parser in (coverage.parse_execinfo, coverage.parse_classinfo):
            with self.assertRaises(coverage.GateError):
                parser("[WARN] no data")

    def test_missing_wrong_and_inconsistent_report_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "jacoco.xml"
            with self.assertRaisesRegex(coverage.GateError, "Missing"):
                coverage.report_counters(path, "skis-jdbc")
            path.write_text('<report name="other"/>', encoding="utf-8")
            with self.assertRaisesRegex(coverage.GateError, "Wrong"):
                coverage.report_counters(path, "skis-jdbc")
            path.write_text('<report name="skis-jdbc"><package><class name="C"><counter type="BRANCH" covered="1" missed="1"/></class></package><counter type="BRANCH" covered="1" missed="2"/></report>', encoding="utf-8")
            with self.assertRaisesRegex(coverage.GateError, "disagree"):
                coverage.report_counters(path, "skis-jdbc")

    def test_no_suffix_or_package_exclusion(self):
        with tempfile.TemporaryDirectory() as directory:
            module = Path(directory)
            path = module / "target/classes/io/skis/CustomerPro.class"
            path.parent.mkdir(parents=True)
            path.touch()
            self.assertEqual([], coverage.generated_exclusions(module, [path]))
            generated = module / "target/generated-sources/annotations/io/skis/Actual.java"
            generated.parent.mkdir(parents=True)
            generated.write_text("package io.skis; public class Actual {}", encoding="utf-8")
            actual_class = path.with_name("Actual.class")
            inner_class = path.with_name("Actual$Nested.class")
            actual_class.touch()
            inner_class.touch()
            with self.assertRaisesRegex(coverage.GateError, "compiler output"):
                coverage.generated_exclusions(module, [path, actual_class])
            listing = module / "target/maven-status/maven-compiler-plugin/compile/default-compile/createdFiles.lst"
            listing.parent.mkdir(parents=True)
            listing.write_text("io/skis/Actual.class\nio/skis/Actual$Nested.class\n", encoding="utf-8")
            with patch.object(coverage, "run", return_value='SourceFile: "Decoy.java"\n'):
                with self.assertRaisesRegex(coverage.GateError, "SourceFile"):
                    coverage.generated_exclusions(module, [actual_class])
            with patch.object(coverage, "run", return_value='SourceFile: "Actual.java"\n'):
                exclusions = coverage.generated_exclusions(module, [path, actual_class, inner_class])
            self.assertEqual(["io/skis/Actual", "io/skis/Actual$Nested"], [item["class"] for item in exclusions])

    def test_missing_or_unuploaded_authoritative_artifact_fails(self):
        for missing in (True, False):
            with self.subTest(missing=missing):
                measurement, xml, producer, responses = authoritative_fixture()
                if missing:
                    responses["actions/runs/1/artifacts?per_page=100&page=1"]["artifacts"] = []
                else:
                    responses["actions/runs/1/attempts/1/jobs?per_page=100"]["jobs"][0]["steps"][-1]["conclusion"] = "failure"
                with evidence_service(measurement, xml, producer, responses), \
                        self.assertRaisesRegex(coverage.GateError, "missing, expired|no successful"):
                    coverage.load_evidence(coverage.evidence_for(measurement), "owner/repo", {}, Path("."))

    def test_candidate_through_check_authenticates_real_zip_and_caches_the_source(self):
        for event in ("push", "pull_request", "workflow_dispatch"):
            with self.subTest(event=event), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                output = root / "coverage"
                measurement, xml, producer, responses = authoritative_fixture(event)
                pending = {"state": coverage.PENDING, "registration": None, "modules": {}, "scopeChanges": []}
                candidate = coverage.make_candidate(pending, measurement)
                coverage.write_json(root / coverage.BASELINE, candidate)
                coverage.write_json(output / "measurement.json", measurement)
                with evidence_service(measurement, xml, producer, responses) as opener, \
                        patch.object(coverage, "trusted_previous", return_value=None), \
                        patch("builtins.print"):
                    coverage.check(root, output)
                    opener.open.assert_called_once()

    def test_unchanged_registered_rows_need_no_new_artifact_after_a_producer_change(self):
        measurement, _, _, _ = authoritative_fixture()
        pending = {"state": coverage.PENDING, "registration": None, "modules": {}, "scopeChanges": []}
        baseline = coverage.make_candidate(pending, measurement)
        with patch.object(coverage, "load_evidence") as load:
            coverage.authenticate(baseline, copy.deepcopy(baseline), measurement, Path("."))
            load.assert_not_called()

    def test_changed_module_cannot_reuse_an_old_scope_report_at_the_same_threshold(self):
        baseline, previous, measurement = fixture()
        baseline["scope"]["revision"] = 2
        baseline["scope"]["modules"]["skis-jdbc"]["generatedExclusions"] = [
            {"class": "io/skis/Generated", "source": "target/generated-sources/Generated.java"}]
        measurement["session"] = {"repository": "owner/repo"}
        with patch.object(coverage, "load_evidence", return_value={"scope": previous["scope"]}) as load:
            with self.assertRaisesRegex(coverage.GateError, "evidence scope differs"):
                coverage.authenticate(baseline, previous, measurement, Path("."))
            load.assert_called_once()

    def test_pr_run_metadata_can_identify_the_tested_merge_commit(self):
        measurement, xml, producer, responses = authoritative_fixture("pull_request")
        responses["actions/runs/1/attempts/1"]["head_sha"] = "a" * 40
        responses["actions/runs/1/attempts/1/jobs?per_page=100"]["jobs"][0]["head_sha"] = "a" * 40
        responses["actions/runs/1/artifacts?per_page=100&page=1"]["artifacts"][0]["workflow_run"]["head_sha"] = "a" * 40
        with evidence_service(measurement, xml, producer, responses):
            result = coverage.load_evidence(coverage.evidence_for(measurement), "owner/repo", {}, Path("."))
        self.assertEqual(measurement, result)

    def test_another_workflow_commit_event_or_attempt_cannot_supply_evidence(self):
        cases = (("workflow_id", 8, "another workflow"), ("path", ".github/workflows/fake.yml", "another workflow"),
                 ("head_sha", "f" * 40, "actual workflow commit"), ("id", 2, "run identity"),
                 ("run_attempt", 2, "run identity"), ("repository", {"full_name": "other/repo"}, "run identity"),
                 ("event", "workflow_run", "unsupported event"), ("status", "in_progress", "incomplete"))
        for key, value, error in cases:
            with self.subTest(key=key):
                measurement, xml, producer, responses = authoritative_fixture()
                responses["actions/runs/1/attempts/1"][key] = value
                with evidence_service(measurement, xml, producer, responses) as opener, \
                        self.assertRaisesRegex(coverage.GateError, error):
                    coverage.load_evidence(coverage.evidence_for(measurement), "owner/repo", {}, Path("."))
                opener.open.assert_not_called()

    def test_different_producer_or_truncated_source_tree_fails(self):
        for truncated in (False, True):
            with self.subTest(truncated=truncated):
                measurement, xml, producer, responses = authoritative_fixture()
                tree = responses[f"git/trees/{'e' * 40}?recursive=1"]
                if truncated:
                    tree["truncated"] = True
                else:
                    tree["tree"][0]["sha"] = "f" * 40
                with evidence_service(measurement, xml, producer, responses), \
                        self.assertRaisesRegex(coverage.GateError, "producer version|tree is truncated"):
                    coverage.load_evidence(coverage.evidence_for(measurement), "owner/repo", {}, Path("."))

    def test_same_names_do_not_authenticate_a_foreign_job_or_artifact_commit(self):
        for foreign_job in (True, False):
            with self.subTest(foreign_job=foreign_job):
                measurement, xml, producer, responses = authoritative_fixture()
                if foreign_job:
                    responses["actions/runs/1/attempts/1/jobs?per_page=100"]["jobs"][0]["head_sha"] = "f" * 40
                else:
                    responses["actions/runs/1/artifacts?per_page=100&page=1"]["artifacts"][0]["workflow_run"]["head_sha"] = "f" * 40
                with evidence_service(measurement, xml, producer, responses), \
                        self.assertRaisesRegex(coverage.GateError, "no successful|another workflow commit"):
                    coverage.load_evidence(coverage.evidence_for(measurement), "owner/repo", {}, Path("."))

    def test_artifact_self_report_cannot_replace_api_source_or_xml_counters(self):
        for defect in ("session", "producer", "checksum", "counters"):
            with self.subTest(defect=defect):
                measurement, xml, producer, responses = authoritative_fixture()
                if defect == "session":
                    measurement["session"]["headCommit"] = "f" * 40
                elif defect == "producer":
                    measurement["session"]["producer"] = {**producer, coverage.WORKFLOW: "f" * 40}
                elif defect == "checksum":
                    measurement["modules"]["skis-jdbc"]["reportSha256"] = "0" * 64
                else:
                    measurement["modules"]["skis-jdbc"].update(covered=79, missed=21, threshold=7900)
                with evidence_service(measurement, xml, producer, responses), \
                        self.assertRaisesRegex(coverage.GateError, "actual producer|checksum mismatch|counters disagree"):
                    coverage.load_evidence(coverage.evidence_for(measurement), "owner/repo", {}, Path("."))

    def test_pr_merge_report_requires_the_real_head_and_base(self):
        for wrong_base in (False, True):
            with self.subTest(wrong_base=wrong_base):
                measurement, xml, producer, responses = authoritative_fixture("pull_request")
                if wrong_base:
                    measurement["session"]["comparison"] = "f" * 40
                else:
                    responses[f"git/commits/{'a' * 40}"]["parents"][1]["sha"] = "f" * 40
                with evidence_service(measurement, xml, producer, responses), \
                        self.assertRaisesRegex(coverage.GateError, "actual workflow commit|foreign comparison base"):
                    coverage.load_evidence(coverage.evidence_for(measurement), "owner/repo", {}, Path("."))

    def test_candidate_retains_existing_evidence_for_unchanged_threshold(self):
        baseline, _, measurement = fixture()
        measurement["session"] = {"commit": "a" * 40, "runId": "2", "attempt": "1", "repository": "owner/repo"}
        candidate = coverage.make_candidate(baseline, measurement)
        self.assertEqual(baseline["modules"], candidate["modules"])
        measurement["modules"]["skis-jdbc"] = row(81, 19)
        candidate = coverage.make_candidate(baseline, measurement)
        self.assertEqual("2", candidate["modules"]["skis-jdbc"]["evidence"]["runId"])


if __name__ == "__main__":
    unittest.main()
