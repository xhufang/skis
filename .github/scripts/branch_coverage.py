#!/usr/bin/env python3
"""Authoritative Java 21 JaCoCo collection and fail-closed branch ratchet.

Build tooling only. Uses the Python standard library and the pinned JaCoCo CLI;
never installs a consumer, rebuilds classes, or writes the tracked baseline.
"""

import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from io import BytesIO


BASELINE = ".github/quality/branch-baseline.json"
POLICY = ".github/quality/coverage-policy.json"
JACOCO = "0.8.15"
PRECISION = "floor(covered * 10000 / (covered + missed))"
PENDING = "awaiting-first-authoritative-run"
COMMAND = ["clean", "verify", "-Pstatic-analysis,coverage,architecture,postgres-contracts",
           "-Dmaven.compiler.release=21"]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
WORKFLOW = ".github/workflows/ci.yml"
PRODUCER_FILES = (WORKFLOW, ".github/scripts/branch_coverage.py",
                  ".github/scripts/verify-test-reports.ps1",
                  ".github/scripts/verify-postgres-selection.ps1",
                  ".github/scripts/verify-postgres-reports.ps1",
                  ".github/quality/postgresql-suites.json",
                  ".github/quality/postgresql-image.json")


class GateError(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise GateError(message)


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_json(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def run(args, cwd=None):
    result = subprocess.run(args, cwd=cwd, text=True, encoding="utf-8",
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
    require(result.returncode == 0, f"Command failed: {args}\n{result.stdout}")
    return result.stdout


def git(root, *args):
    return run(["git", *args], root).strip()


def score(covered, missed):
    require(type(covered) is int and type(missed) is int and covered >= 0 and missed >= 0,
            "Coverage counters must be nonnegative integers")
    require(covered + missed > 0, "A branch-bearing module has zero branches")
    return covered * 10000 // (covered + missed)


def validate_row(row, mode):
    require(mode in ("required", "none"), "Unknown branch policy")
    if mode == "none":
        require(row["covered"] == 0 and row["missed"] == 0 and row["threshold"] == "N/A",
                "N/A requires a real zero-branch report")
    else:
        require(type(row["threshold"]) is int and 0 <= row["threshold"] <= 10000,
                "Invalid integer threshold")
        require(row["threshold"] == score(row["covered"], row["missed"]),
                "Baseline threshold disagrees with its recorded counters")


def scope_identity(scope):
    return {"jacoco": scope.get("jacoco"), "java": scope.get("java"), "release": scope.get("release"),
            "precision": scope.get("precision"), "modules": {
                name: {"branches": config["branches"], "generatedExclusions": config["generatedExclusions"]}
                for name, config in scope["modules"].items()}}


def validate_precision(scope):
    require(scope.get("precision") == PRECISION, "Unsupported coverage precision")


def same_module_scope(first, second, name):
    if name not in first["modules"] or name not in second["modules"]:
        return False
    return (all(first.get(key) == second.get(key) for key in ("jacoco", "java", "release", "precision"))
            and all(first["modules"][name][key] == second["modules"][name][key]
                    for key in ("branches", "generatedExclusions")))


def compare(baseline, previous, measurement):
    """Pure policy check. Evidence authentication is performed separately."""
    require(baseline.get("schema") == 1, "Unsupported baseline schema")
    require(baseline.get("state") == "active", "First real baseline must be registered from CI")
    scope = measurement["scope"]
    validate_precision(scope)
    require(baseline["scope"] == scope, "Baseline scope differs from this build")
    require(set(baseline["modules"]) == set(scope["modules"]), "Missing or extra baseline module")
    require(set(measurement["modules"]) == set(scope["modules"]), "Missing module measurement")
    require(previous is None or previous.get("schema") == 1
            and previous.get("state") in ("active", PENDING), "Invalid trusted historical baseline")
    previous_active = previous is not None and previous.get("state") == "active"
    if previous_active:
        validate_precision(previous["scope"])
    same_scope = previous_active and previous["scope"] == scope
    if previous_active:
        require(baseline["registration"] == previous["registration"],
                "First registration cannot be replaced or bootstrapped again")
        old_changes = previous.get("scopeChanges", [])
        changes = baseline.get("scopeChanges", [])
        if same_scope:
            require(changes == old_changes, "Scope history cannot be rewritten")
        else:
            require(scope_identity(scope) != scope_identity(previous["scope"]),
                    "A revision-only change cannot reset coverage thresholds")
            require(scope["revision"] == previous["scope"]["revision"] + 1,
                    "Scope changes require the next revision")
            require(len(changes) == len(old_changes) + 1 and changes[:-1] == old_changes,
                    "Scope changes require an append-only transition record")
            change = changes[-1]
            require(change["previousScope"] == previous["scope"]
                    and change["previousModules"] == previous["modules"],
                    "Scope revision must retain all previous thresholds and evidence")
            require(isinstance(change.get("reason"), str) and change["reason"].strip(),
                    "Scope revision needs a reason")
            require(set(change["mapping"]) == set(previous["modules"]),
                    "Every previous module needs an explicit mapping")
            targets = set(change.get("newModules", []))
            for mapping in change["mapping"].values():
                require(isinstance(mapping.get("reason"), str) and mapping["reason"].strip(),
                        "Each scope mapping needs a reason, including deletions")
                targets.update(mapping["targets"])
            require(targets == set(scope["modules"]), "Scope mapping omits or invents modules")
    else:
        require(not baseline.get("scopeChanges"), "First registration cannot invent history")

    for name, policy in scope["modules"].items():
        row = baseline["modules"][name]
        actual = measurement["modules"][name]
        validate_row(row, policy["branches"])
        validate_row(actual, policy["branches"])
        require(actual["classes"] > 0, f"{name}: missing production classes")
        if policy["branches"] == "none":
            continue
        current = actual["threshold"]
        if previous_active and same_module_scope(previous["scope"], scope, name):
            old = previous["modules"][name]["threshold"]
            require(row["threshold"] >= old, f"{name}: tracked threshold was lowered")
            require(current >= old, f"{name}: coverage regressed below the historical threshold")
        require(current >= row["threshold"], f"{name}: coverage is below the registered threshold")
        require(current <= row["threshold"], f"{name}: coverage improved; register the raised candidate")


def parse_classinfo(output):
    classes = {}
    for match in re.finditer(r"^\s*\d+\s+\d+\s+\d+\s+\d+\s+\d+\s+class 0x([0-9a-f]{16}) (\S+)\s*$",
                             output, re.MULTILINE):
        class_id, name = match.groups()
        require(name not in classes, f"Duplicate production class: {name}")
        classes[name] = class_id
    require(classes, "JaCoCo classinfo returned no production classes")
    return classes


def parse_execinfo(output, *, allow_empty=False):
    data = {}
    for match in re.finditer(r"^([0-9a-f]{16})\s+\d+ of\s+\d+\s+(\S+)\s*$", output, re.MULTILINE):
        class_id, name = match.groups()
        data.setdefault(name, set()).add(class_id)
    require(data or allow_empty, "JaCoCo execution data contains no classes")
    return data


def match_class_ids(classes, executed):
    for name, class_id in classes.items():
        if name in executed:
            require(executed[name] == {class_id}, f"Class ID mismatch for {name}")


def validate_fork(path, module_name, session, info, *, allow_empty=False):
    require(path.stat().st_size > 0 and path.stat().st_mtime >= session["started"],
            f"Empty or stale execution data: {path}")
    sessions = re.findall(r'^Session "([^"]+)":', info, re.MULTILINE)
    require(re.fullmatch(r"(?:unit|postgres)-[1-9][0-9]*", path.stem) is not None,
            f"Unknown test fork execution file: {path}")
    expected = f"{session['commit']}:{module_name}:{path.stem}"
    require(sessions and all(item == expected for item in sessions), f"Foreign execution session: {path}")
    parse_execinfo(info, allow_empty=allow_empty)


def report_counters(path, expected_name):
    require(Path(path).is_file(), f"Missing module report: {path}")
    return xml_counters(ET.parse(path).getroot(), expected_name)


def xml_counters(report, expected_name):
    require(report.tag == "report" and report.get("name") == expected_name, "Wrong module report")
    classes = report.findall("./package/class")
    require(classes, f"{expected_name}: report contains no classes")
    counters = report.findall("./counter[@type='BRANCH']")
    require(len(counters) <= 1, "Duplicate report-level branch counters")
    covered = int(counters[0].get("covered")) if counters else 0
    missed = int(counters[0].get("missed")) if counters else 0
    # Check the report totals against the per-class data as well.
    totals = [0, 0]
    for cls in classes:
        counter = cls.find("./counter[@type='BRANCH']")
        if counter is not None:
            totals[0] += int(counter.get("covered"))
            totals[1] += int(counter.get("missed"))
    require(totals == [covered, missed], "Report-level counters disagree with class counters")
    return covered, missed, len(classes)


def reactor_modules(root):
    result = []

    def visit(directory):
        result.append(directory)
        pom = ET.parse(directory / "pom.xml")
        for child in pom.findall("./m:modules/m:module", NS):
            visit((directory / child.text).resolve())

    visit(root)
    return result


def generated_exclusions(module, class_paths):
    """Map generated sources to compiler outputs and verify each class's SourceFile attribute."""
    outputs = set()
    for listing in (module / "target/maven-status").glob("**/compile/*/createdFiles.lst"):
        outputs.update(line.replace("\\", "/") for line in listing.read_text(encoding="utf-8").splitlines())
    exclusions = []
    for source in sorted((module / "target/generated-sources").glob("**/*.java")):
        package = re.search(r"^\s*package\s+([\w.]+)\s*;", source.read_text(encoding="utf-8"), re.MULTILINE)
        require(package is not None, f"Generated source has no explicit package: {source}")
        outer = package.group(1).replace(".", "/") + "/" + source.stem
        for cls in class_paths:
            name = cls.relative_to(module / "target/classes").as_posix()[:-6]
            if name == outer or name.startswith(outer + "$"):
                require(name + ".class" in outputs, f"Generated class is not a compiler output: {cls}")
                details = run(["javap", "-verbose", str(cls)])
                declared_source = re.search(r'^SourceFile: "([^"]+)"', details, re.MULTILINE)
                require(declared_source is not None and declared_source.group(1) == source.name,
                        f"Generated class SourceFile does not match its source: {cls}")
                exclusions.append({"class": name, "source": source.relative_to(module).as_posix()})
    require(len({entry["class"] for entry in exclusions}) == len(exclusions),
            "More than one generated source claims a production class")
    return sorted(exclusions, key=lambda entry: entry["class"])


def trusted_previous(root, commit):
    require(re.fullmatch(r"[0-9a-f]{40}", commit or "") is not None
            and commit != "0" * 40, "A real comparison commit is required")
    git(root, "cat-file", "-e", commit + "^{commit}")
    exists = subprocess.run(["git", "cat-file", "-e", f"{commit}:{BASELINE}"], cwd=root,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    if exists.returncode == 0:
        return json.loads(git(root, "show", f"{commit}:{BASELINE}"))
    require(not git(root, "log", "-1", "--format=%H", commit, "--", BASELINE),
            "Historical baseline was deleted; first registration cannot be restarted")
    return None


def producer_inputs(root):
    return {path: git(root, "rev-parse", f"HEAD:{path}") for path in PRODUCER_FILES}


def comparison_context(root, comparison):
    """Validate the caller's comparison against the event, never against its own choice."""
    event_name = os.environ["GITHUB_EVENT_NAME"]
    event = read_json(os.environ["GITHUB_EVENT_PATH"])
    repository = os.environ["GITHUB_REPOSITORY"]
    require(event["repository"]["full_name"] == repository, "Event belongs to another repository")
    context = {"event": event_name, "headCommit": os.environ["GITHUB_SHA"]}
    if event_name == "pull_request":
        pr = event["pull_request"]
        expected = pr["base"]["sha"]
        context["headCommit"] = pr["head"]["sha"]
        require(git(root, "show", "-s", "--format=%P", "HEAD").split()
                == [expected, context["headCommit"]], "PR checkout is not the event's merge commit")
    elif event_name == "push":
        expected = event["before"]
    elif event_name == "workflow_dispatch":
        branch = event["repository"]["default_branch"]
        expected = api_json(repository, f"branches/{urllib.parse.quote(branch, safe='')}")["commit"]["sha"]
        context["defaultBranch"] = branch
    else:
        raise GateError("Unsupported authoritative workflow event")
    require(comparison == expected,
            "Comparison must match the event; manual runs require the current default-branch tip")
    return context


def start(root, session_path, comparison):
    require(os.environ.get("GITHUB_JOB") == "engineering-gates", "Only engineering-gates is authoritative")
    require(not git(root, "status", "--porcelain", "--untracked-files=no"), "Tracked checkout is dirty")
    commit = git(root, "rev-parse", "HEAD")
    require(commit == os.environ.get("GITHUB_SHA"), "Checkout differs from the workflow commit")
    context = comparison_context(root, comparison)
    previous = trusted_previous(root, comparison)
    java = run(["java", "-version"])
    require(re.search(r'version "21[.\"]', java) is not None and "Temurin" in java,
            "Authoritative coverage requires Temurin 21")
    write_json(session_path, {**context, "producer": producer_inputs(root),
                             "commit": commit, "comparison": comparison, "previous": previous,
                             "started": time.time(), "java": java, "command": COMMAND,
                             "policySha256": digest(root / POLICY),
                             "baselineSha256": digest(root / BASELINE),
                             "runId": os.environ["GITHUB_RUN_ID"],
                             "attempt": os.environ["GITHUB_RUN_ATTEMPT"],
                             "repository": os.environ["GITHUB_REPOSITORY"]})


def collect(root, session_path, output):
    session = read_json(session_path)
    require(git(root, "rev-parse", "HEAD") == session["commit"], "Checkout changed after compilation")
    require(not git(root, "status", "--porcelain", "--untracked-files=no"), "Build changed tracked inputs")
    require(digest(root / POLICY) == session["policySha256"]
            and digest(root / BASELINE) == session["baselineSha256"], "Coverage inputs changed during build")
    require(session["command"] == COMMAND, "Wrong authoritative build parameters")
    output.mkdir(parents=True, exist_ok=False)
    cli = root / "target/coverage-tools/jacococli.jar"

    def jacoco(*args):
        return run(["java", "-jar", str(cli), *map(str, args)], root)

    require(jacoco("version").strip().startswith(JACOCO + "."), "Unexpected JaCoCo CLI version")
    policy = read_json(root / POLICY)
    validate_precision(policy)
    production = {entry["path"] for entry in read_json(root / ".github/quality/modules.json")["modules"]
                  if entry["kind"] in ("production", "processor")}
    require(set(policy["modules"]) == production, "Coverage policy omits or invents production modules")
    require(policy["jacoco"] == JACOCO and policy["java"] == 21 and policy["release"] == 21,
            "Unsupported coverage toolchain")
    exec_files = []
    for module in reactor_modules(root):
        data = sorted((module / "target/jacoco").glob("*.exec"))
        reports = []
        reported_phases = set()
        for directory, phase in (("surefire-reports", "unit"), ("failsafe-reports", "postgres")):
            phase_reports = sorted((module / "target" / directory).glob("TEST-*.xml"))
            if phase_reports:
                reported_phases.add(phase)
                require(any(path.stem.startswith(phase + "-") for path in data),
                        f"{module.name}: missing {phase} execution data")
            reports.extend(phase_reports)
        if any((module / "src/test").glob("**/*Test.java")):
            require(reports and data, f"{module.name}: missing test reports or execution data")
        for report in reports:
            result = ET.parse(report).getroot()
            require(int(result.get("errors", 0)) == 0 and int(result.get("failures", 0)) == 0,
                    f"Failed test suite: {report}")
            require(report.stat().st_mtime >= session["started"], f"Stale test report: {report}")
        for path in data:
            info = jacoco("execinfo", path)
            # Tag filtering can start a fork without selecting tests in this phase.
            validate_fork(path, module.name, session, info,
                          allow_empty=path.stem.split("-", 1)[0] not in reported_phases)
            exec_files.append(path)
    require(exec_files, "No execution data was produced")
    merged = output / "merged.exec"
    jacoco("merge", *exec_files, "--destfile", merged)
    exec_info = jacoco("execinfo", merged)
    (output / "execinfo.txt").write_text(exec_info, encoding="utf-8")
    executed = parse_execinfo(exec_info)
    measurement = {"schema": 1, "scope": copy.deepcopy(policy), "modules": {}, "session": session,
                   "execFiles": [{"path": p.relative_to(root).as_posix(), "sha256": digest(p)}
                                 for p in exec_files]}
    all_classes = {}
    for name, config in measurement["scope"]["modules"].items():
        module = root / name
        classes_dir = module / "target/classes"
        paths = sorted(classes_dir.glob("**/*.class"))
        require(paths, f"{name}: missing production classes")
        for path in paths:
            with path.open("rb") as class_file:
                header = class_file.read(8)
            require(len(header) == 8 and header[:4] == b"\xca\xfe\xba\xbe"
                    and struct.unpack(">H", header[6:8])[0] == 65, f"Not release-21 bytecode: {path}")
            require(path.stat().st_mtime >= session["started"], f"Stale production class: {path}")
        info = jacoco("classinfo", classes_dir)
        class_ids = parse_classinfo(info)
        require(not (set(all_classes) & set(class_ids)), "Production modules contain duplicate classes")
        all_classes.update(class_ids)
        match_class_ids(class_ids, executed)
        exclusions = generated_exclusions(module, paths)
        config["generatedExclusions"] = exclusions
        excluded = {entry["class"] for entry in exclusions}
        report_dir = output / name
        report_dir.mkdir()
        (report_dir / "classinfo.txt").write_text(info, encoding="utf-8")
        write_json(report_dir / "classes.json", {"classes": class_ids, "excluded": exclusions})
        # A filtered staging directory avoids both package exclusions and long CLI argument lists.
        filtered = report_dir / "classes"
        for path in paths:
            relative = path.relative_to(classes_dir)
            if relative.as_posix()[:-6] not in excluded:
                target = filtered / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, target)
        xml = report_dir / "jacoco.xml"
        report_output = jacoco("report", merged, "--classfiles", filtered, "--sourcefiles", module / "src/main/java",
                               "--xml", xml, "--html", report_dir / "html", "--name", name, "--encoding", "UTF-8")
        require("[WARN]" not in report_output, f"{name}: JaCoCo reported mismatched classes: {report_output}")
        covered, missed, count = report_counters(xml, name)
        reported_classes = {cls.get("name") for cls in ET.parse(xml).findall("./package/class")}
        require(reported_classes == set(class_ids) - excluded, f"{name}: report omitted production classes")
        row = {"covered": covered, "missed": missed, "classes": count,
               "threshold": score(covered, missed) if config["branches"] == "required" else "N/A",
               "reportSha256": digest(xml)}
        validate_row(row, config["branches"])
        if config["branches"] == "required":
            require(any(cls in executed for cls in class_ids if cls not in excluded),
                    f"{name}: no production execution data")
        measurement["modules"][name] = row
        # Only XML/HTML and class identities are deliverable, not copies of the class files.
        shutil.rmtree(filtered)
    write_json(output / "measurement.json", measurement)
    candidate = make_candidate(session["previous"], measurement)
    write_json(output / "branch-baseline.candidate.json", candidate)
    lines = ["| Module | Covered | Missed | Branch % |", "| --- | ---: | ---: | ---: |"]
    for name, row in measurement["modules"].items():
        value = "N/A" if row["threshold"] == "N/A" else f"{row['threshold'] / 100:.2f}"
        lines.append(f"| {name} | {row['covered']} | {row['missed']} | {value} |")
    (output / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def evidence_for(measurement):
    session = measurement["session"]
    return {"commit": session["commit"], "runId": session["runId"], "attempt": session["attempt"],
            "repository": session["repository"], "artifact":
            f"branch-coverage-java-21-{session['runId']}-{session['attempt']}"}


def make_candidate(previous, measurement):
    """Retain evidence only from the session's trusted comparison, not an unregistered HEAD candidate."""
    previous_active = previous is not None and previous.get("state") == "active"
    candidate = {"schema": 1, "state": "active", "scope": measurement["scope"],
                 "registration": copy.deepcopy(previous["registration"]) if previous_active else evidence_for(measurement),
                 "scopeChanges": copy.deepcopy(previous.get("scopeChanges", [])) if previous_active else [],
                 "modules": {}}
    if previous_active and previous["scope"] != measurement["scope"]:
        candidate["scopeChanges"].append({
            "previousScope": copy.deepcopy(previous["scope"]), "previousModules": copy.deepcopy(previous["modules"]),
            "reason": None,
            "mapping": {name: {"targets": [name] if name in measurement["modules"] else [], "reason": None}
                        for name in previous["modules"]},
            "newModules": sorted(set(measurement["modules"]) - set(previous["modules"]))})
    for name, row in measurement["modules"].items():
        old = previous["modules"].get(name) if previous_active else None
        if (old is not None and same_module_scope(previous["scope"], measurement["scope"], name)
                and old["threshold"] == row["threshold"]):
            candidate["modules"][name] = copy.deepcopy(old)
        else:
            candidate["modules"][name] = {**row, "evidence": evidence_for(measurement)}
    return candidate


def api_json(repository, endpoint):
    token = os.environ.get("GH_TOKEN")
    require(token, "GH_TOKEN with actions:read is required to authenticate coverage evidence")
    request = urllib.request.Request(f"https://api.github.com/repos/{repository}/{endpoint}",
                                     headers={"Authorization": f"Bearer {token}",
                                              "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


class ArtifactRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        redirected = super().redirect_request(request, fp, code, message, headers, new_url)
        if redirected is not None:
            # The storage redirect uses its own signed URL, not the GitHub token.
            redirected.remove_header("Authorization")
        return redirected


def load_evidence(source, repository, cache, root):
    require(source["repository"] == repository, "Evidence belongs to another repository")
    require(re.fullmatch(r"[0-9a-f]{40}", source["commit"]) is not None, "Invalid evidence commit")
    require(str(source["runId"]).isdigit() and str(source["attempt"]).isdigit(), "Invalid evidence run")
    expected = f"branch-coverage-java-21-{source['runId']}-{source['attempt']}"
    require(source["artifact"] == expected, "Evidence artifact name is not authoritative")
    key = (source["runId"], source["attempt"])
    if key not in cache:
        workflow = api_json(repository, "actions/workflows/ci.yml")
        execution = api_json(repository, f"actions/runs/{key[0]}/attempts/{key[1]}")
        require(workflow["path"] == WORKFLOW and execution["workflow_id"] == workflow["id"]
                and execution["path"] == WORKFLOW, "Evidence was produced by another workflow")
        require(str(execution["id"]) == str(key[0]) and str(execution["run_attempt"]) == str(key[1])
                and execution["repository"]["full_name"] == repository,
                "Evidence workflow run identity differs")
        require(execution["status"] == "completed"
                and execution["event"] in ("push", "pull_request", "workflow_dispatch"),
                "Evidence run is incomplete or has an unsupported event")
        commit = api_json(repository, f"git/commits/{source['commit']}")
        require(commit["sha"] == source["commit"], "Evidence commit differs from GitHub")
        parents = [parent["sha"] for parent in commit["parents"]]
        head = source["commit"]
        if execution["event"] == "pull_request":
            require(len(parents) == 2, "PR evidence is not a tested merge commit")
            head = parents[1]
        # PR runs may identify the source head while checkout tests the event's merge commit.
        require(execution["head_sha"] in (source["commit"], head),
                "Evidence commit differs from the actual workflow commit")
        tree = api_json(repository, f"git/trees/{commit['tree']['sha']}?recursive=1")
        require(not tree.get("truncated", False), "Evidence producer tree is truncated")
        producer = {entry["path"]: entry["sha"] for entry in tree["tree"]
                    if entry["path"] in PRODUCER_FILES and entry["type"] == "blob"}
        require(producer == producer_inputs(root), "Evidence used a different coverage producer version")
        jobs = api_json(repository, f"actions/runs/{key[0]}/attempts/{key[1]}/jobs?per_page=100")
        required_steps = {"Collect module coverage and candidate", "Upload authoritative coverage evidence",
                          "Upload build test and raw fork evidence"}
        require(any(job["name"] == "Engineering gates (Java 21 coverage)"
                    and job["head_sha"] == execution["head_sha"]
                    and required_steps <= {step["name"] for step in job.get("steps", [])
                                           if step["conclusion"] == "success"}
                    for job in jobs["jobs"]), "Run has no successful authoritative coverage collection")
        artifacts = []
        page = 1
        while True:
            batch = api_json(repository, f"actions/runs/{key[0]}/artifacts?per_page=100&page={page}")["artifacts"]
            artifacts.extend(a for a in batch if a["name"] == expected and not a["expired"])
            if len(batch) < 100:
                break
            page += 1
        require(len(artifacts) == 1, "Authoritative report artifact is missing, expired or ambiguous")
        artifact_run = artifacts[0]["workflow_run"]
        require(str(artifact_run["id"]) == str(key[0])
                and artifact_run["head_sha"] == execution["head_sha"],
                "Artifact belongs to another workflow commit")
        request = urllib.request.Request(artifacts[0]["archive_download_url"],
                                         headers={"Authorization": f"Bearer {os.environ['GH_TOKEN']}"})
        with urllib.request.build_opener(ArtifactRedirect()).open(request, timeout=60) as response:
            archive = zipfile.ZipFile(BytesIO(response.read()))
        measurement = json.loads(archive.read("measurement.json"))
        require(evidence_for(measurement) == source, "Evidence commit/run differs from the artifact")
        session = measurement["session"]
        require(session["event"] == execution["event"] and session["headCommit"] == head
                and session["producer"] == producer, "Evidence session disagrees with its actual producer")
        if execution["event"] == "pull_request":
            require(session["comparison"] == parents[0], "PR evidence has a foreign comparison base")
        require(set(measurement["modules"]) == set(measurement["scope"]["modules"]),
                "Evidence omits or invents module reports")
        for name, row in measurement["modules"].items():
            xml = archive.read(f"{name}/jacoco.xml")
            require(hashlib.sha256(xml).hexdigest() == row["reportSha256"],
                    "Evidence report checksum mismatch")
            require(xml_counters(ET.fromstring(xml), name)
                    == (row["covered"], row["missed"], row["classes"]),
                    "Evidence counters disagree with its XML report")
            validate_row(row, measurement["scope"]["modules"][name]["branches"])
        cache[key] = measurement
    require(evidence_for(cache[key]) == source, "Evidence source disagrees with the cached run")
    return cache[key]


def authenticate(baseline, previous, measurement, root):
    cache = {}
    repository = measurement["session"]["repository"]
    if previous is None or previous.get("state") != "active":
        first = load_evidence(baseline["registration"], repository, cache, root)
        require(first["session"]["previous"] is None
                or first["session"]["previous"].get("state") == PENDING,
                "Initial registration report was produced after activation")
        require(first["scope"] == baseline["scope"], "First registration has a different scope")
    for name, row in baseline["modules"].items():
        old = previous.get("modules", {}).get(name) if previous else None
        if (previous and previous.get("state") == "active" and row == old
                and same_module_scope(previous["scope"], baseline["scope"], name)):
            continue
        evidence = load_evidence(row["evidence"], repository, cache, root)
        require(evidence["scope"] == baseline["scope"], f"{name}: evidence scope differs")
        original = {key: value for key, value in row.items() if key != "evidence"}
        require(original == evidence["modules"][name], f"{name}: baseline is not backed by its report")


def check(root, output):
    measurement = read_json(output / "measurement.json")
    baseline = read_json(root / BASELINE)
    previous = trusted_previous(root, measurement["session"]["comparison"])
    require(previous == measurement["session"]["previous"], "Comparison baseline changed")
    compare(baseline, previous, measurement)
    authenticate(baseline, previous, measurement, root)
    print("All module branch thresholds match the current authoritative reports and trusted history.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["start", "collect", "check"])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--session", type=Path)
    parser.add_argument("--comparison")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        if args.action == "start":
            start(args.root.resolve(), args.session, args.comparison)
        elif args.action == "collect":
            collect(args.root.resolve(), args.session, args.output)
        else:
            check(args.root.resolve(), args.output)
    except (GateError, KeyError, ValueError, OSError, ET.ParseError) as failure:
        print(f"Coverage gate failed: {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
