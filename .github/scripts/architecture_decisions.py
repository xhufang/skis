#!/usr/bin/env python3
"""Check registered architecture decisions locally or against trusted CI history.

This inspects declarations and configuration, not implementation semantics. Local ADR
text is deliberately unavailable to CI. See docs/architecture-decisions.md.
"""

import argparse
import ast
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

import tree_sitter_java
from tree_sitter import Language, Parser
import yaml


MANIFEST = ".github/architecture/decisions.json"
REGISTRATION = ".github/architecture/registration.json"
STATUSES = {"Proposed", "Accepted", "Implemented", "Superseded", "Abandoned"}
TERMINAL = {"Superseded", "Abandoned"}
VERSION = r"\d+\.\d+\.\d+(?:-SNAPSHOT)?"
REVISION = r"[0-9a-f]{40}"
TYPES = {"class_declaration", "interface_declaration", "enum_declaration",
         "record_declaration", "annotation_type_declaration"}
LOCAL_FIELDS = {"decisionVersion": "决策版本", "targetVersion": "目标版本",
                "implementedVersion": "代码落地版本", "remaining": "未完成范围",
                "task": "实施任务"}


class GateError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise GateError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"Duplicate configuration key: {key}")
        result[key] = value
    return result


class UniqueYamlLoader(yaml.BaseLoader):
    """BaseLoader preserves GitHub's 'on' key rather than treating it as YAML 1.1 true."""

    def construct_mapping(self, node, deep=False):
        return unique_object([(self.construct_object(key, deep=deep),
                               self.construct_object(value, deep=deep))
                              for key, value in node.value])


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"), object_pairs_hook=unique_object)


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest(value):
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


def git(root, *args, allowed=(0,)):
    result = subprocess.run(["git", *args], cwd=root, capture_output=True, text=True,
                            encoding="utf-8", check=False)
    require(result.returncode in allowed, f"git {args[0]} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def revision(root, value):
    require(isinstance(value, str) and re.fullmatch(REVISION, value)
            and value != "0" * 40, "A real, full baseline commit SHA is required")
    git(root, "cat-file", "-e", value + "^{commit}")
    return value


def historical_json(root, commit, path):
    # Inspect the tree first: other git failures must never look like first registration.
    exists = git(root, "ls-tree", commit, "--", path)
    if exists:
        return json.loads(git(root, "show", f"{commit}:{path}"), object_pairs_hook=unique_object)
    require(not git(root, "log", "-1", "--format=%H", commit, "--", path),
            f"Historical {path} was deleted; bootstrap cannot be restarted")
    return None


def nonempty(value, label):
    require(isinstance(value, str) and value.strip() == value and bool(value),
            f"{label} must be a nonempty trimmed string")


def validate_manifest(document):
    require(isinstance(document, dict) and type(document.get("schemaVersion")) is int
            and document["schemaVersion"] == 1,
            "decisions.json must have schemaVersion=1")
    rows = document.get("decisions")
    require(isinstance(rows, list) and rows, "Decision registration must not be empty")
    decisions = {}
    for row in rows:
        require(isinstance(row, dict), "Every decision must be an object")
        identity = row.get("id")
        require(isinstance(identity, str) and re.fullmatch(r"ADR-\d{4}", identity),
                "Invalid decision ID")
        require(identity not in decisions, f"Duplicate decision: {identity}")
        decisions[identity] = row
        status = row.get("status")
        require(isinstance(status, str) and status in STATUSES, f"{identity}: invalid status {status}")
        for field in ("change", "disposition"):
            require(field not in row or isinstance(row[field], dict),
                    f"{identity}: {field} must be an object")
        require(isinstance(row.get("decisionVersion"), str)
                and re.fullmatch(VERSION, row["decisionVersion"]),
                f"{identity}: invalid decisionVersion")
        for field in ("targetVersion", "implementedVersion"):
            value = row.get(field)
            require(value is None or isinstance(value, str) and re.fullmatch(VERSION, value),
                    f"{identity}: invalid {field}")
        require("targetVersion" in row and "implementedVersion" in row,
                f"{identity}: version fields must be explicit (null when inapplicable)")
        nonempty(row.get("title"), f"{identity}: title")
        require(isinstance(row.get("remaining"), str) and isinstance(row.get("task"), str),
                f"{identity}: remaining and task must be explicit strings")
        if status in {"Proposed", "Accepted"}:
            nonempty(row["remaining"], f"{identity}: remaining")
            nonempty(row["task"], f"{identity}: task")
            require(row["targetVersion"] is not None, f"{identity}: targetVersion required")
        if status == "Implemented":
            require(row["implementedVersion"] is not None and not row["remaining"]
                    and not row["task"], f"{identity}: Implemented still has unfinished work")
        for field in ("anchors", "contracts"):
            anchors = row.get(field)
            require(isinstance(anchors, list), f"{identity}: {field} must be an array")
            require(len({canonical(anchor) for anchor in anchors}) == len(anchors),
                    f"{identity}: duplicate {field}")
            if status == "Implemented":
                require(anchors, f"{identity}: Implemented requires {field}")
        disposition = row.get("disposition", {})
        replacements = disposition.get("replacedBy", [])
        require(isinstance(replacements, list)
                and all(isinstance(item, str) for item in replacements)
                and len(set(replacements)) == len(replacements),
                f"{identity}: disposition.replacedBy must be an array of unique strings")
        if status in TERMINAL:
            nonempty(disposition.get("reason"), f"{identity}: disposition reason")
            require(status != "Superseded" or replacements,
                    f"{identity}: Superseded requires a replacement")

    def visit(identity, visiting, visited):
        require(identity not in visiting, f"Replacement cycle involving {identity}")
        if identity in visited:
            return
        visiting.add(identity)
        for target in decisions[identity].get("disposition", {}).get("replacedBy", []):
            require(target in decisions, f"{identity}: missing replacement {target}")
            visit(target, visiting, visited)
        visiting.remove(identity)
        visited.add(identity)

    visited = set()
    for identity in decisions:
        visit(identity, set(), visited)
    return decisions


def at_pointer(document, pointer):
    require(isinstance(pointer, list) and pointer, "Configuration pointer must not be empty")
    value = document
    for segment in pointer:
        if isinstance(segment, dict):
            require(isinstance(value, list) and len(segment) == 1,
                    f"Invalid configuration selector: {segment}")
            key, expected = next(iter(segment.items()))
            matches = [item for item in value if isinstance(item, dict) and item.get(key) == expected]
            require(len(matches) == 1, f"Missing or ambiguous selector: {segment}")
            value = matches[0]
        elif isinstance(value, dict):
            require(segment in value, f"Missing configuration key: {segment}")
            value = value[segment]
        else:
            require(type(segment) is int and isinstance(value, list) and 0 <= segment < len(value),
                    f"Missing configuration index: {segment}")
            value = value[segment]
    require(value is not None and value != "", "Configuration anchor is empty")
    return value


def check_configuration(value, anchor):
    label = f"{anchor['path']} {anchor.get('pointer')}"
    expected_type = anchor.get("expectedType")
    if "expectedType" in anchor:
        require(expected_type in ("array", "object"), "expectedType must be array or object")
        require(isinstance(value, list if expected_type == "array" else dict),
                f"Configuration type must be {expected_type}: {label}")
    for field, container_type in (("minItems", "array"), ("minProperties", "object")):
        if field in anchor:
            require(expected_type == container_type, f"{field} requires expectedType={container_type}")
            minimum = anchor[field]
            require(type(minimum) is int and minimum >= 0,
                    f"{field} must be a nonnegative integer")
            require(len(value) >= minimum, f"Configuration {field}={minimum} not met: {label}")
    if isinstance(value, (list, dict)):
        bound = "minItems" if isinstance(value, list) else "minProperties"
        require(value or anchor.get(bound) == 0, f"Configuration anchor is empty: {label}")
    require("expected" not in anchor or value == anchor["expected"],
            f"Configuration value changed: {label}")


def direct_entry_call(statement, entry):
    """Recognize explicit entry calls without walking branches or deferred code."""
    if isinstance(statement, ast.Expr):
        call = statement.value
        if (isinstance(call, ast.Call) and isinstance(call.func, ast.Attribute)
                and isinstance(call.func.value, ast.Name) and call.func.value.id == "sys"
                and call.func.attr == "exit" and len(call.args) == 1 and not call.keywords):
            call = call.args[0]
    elif (isinstance(statement, ast.Raise) and isinstance(statement.exc, ast.Call)
          and isinstance(statement.exc.func, ast.Name) and statement.exc.func.id == "SystemExit"
          and len(statement.exc.args) == 1 and not statement.exc.keywords):
        call = statement.exc.args[0]
    else:
        return False
    return isinstance(call, ast.Call) and isinstance(call.func, ast.Name) and call.func.id == entry


class SourceInspector:
    def __init__(self, root, tracked=None):
        self.root = Path(root).resolve()
        self.tracked = tracked
        self.cache = {}
        self.parser = Parser(Language(tree_sitter_java.language()))

    def path(self, name):
        require(isinstance(name, str) and "\\" not in name, "Anchor must use a relative POSIX path")
        relative = PurePosixPath(name)
        require(not relative.is_absolute() and bool(relative.parts)
                and ":" not in name and ".." not in relative.parts, "Unsafe anchor path")
        path = (self.root / name).resolve()
        require(path.is_relative_to(self.root) and path.is_file(), f"Missing anchor file: {name}")
        if self.tracked is not None:
            require(name in self.tracked, f"CI anchor is not committed: {name}")
        return path

    def load(self, name, kind):
        key = (name, kind)
        if key not in self.cache:
            path = self.path(name)
            if kind == "java":
                source = path.read_bytes()
                tree = self.parser.parse(source)
                require(not tree.root_node.has_error, f"Unparseable Java anchor: {name}")
                self.cache[key] = tree
            elif kind == "json":
                self.cache[key] = read_json(path)
            elif kind == "yaml":
                self.cache[key] = yaml.load(path.read_text(encoding="utf-8"), Loader=UniqueYamlLoader)
            elif kind == "xml":
                self.cache[key] = ET.parse(path).getroot()
            elif kind == "python":
                self.cache[key] = ast.parse(path.read_text(encoding="utf-8"), filename=name)
            else:
                raise GateError(f"Unsupported structured anchor: {kind}")
        return self.cache[key]

    def java_type(self, name, qualified):
        root = self.load(name, "java").root_node
        packages = [node for node in root.named_children if node.type == "package_declaration"]
        require(len(packages) == 1, f"{name}: explicit Java package required")
        package_nodes = [node for node in packages[0].named_children
                         if node.type in {"identifier", "scoped_identifier"}]
        require(len(package_nodes) == 1, f"{name}: missing package name")
        package = package_nodes[0].text.decode("utf-8")

        def declarations(container, prefix):
            for node in container.named_children:
                if node.type in TYPES:
                    declared = prefix + "." + node.child_by_field_name("name").text.decode("utf-8")
                    if declared == qualified:
                        yield node
                    body = node.child_by_field_name("body")
                    if body is not None:
                        yield from declarations(body, declared)

        found = list(declarations(root, package))
        require(len(found) == 1, f"Missing or ambiguous Java declaration: {qualified} in {name}")
        return found[0]

    def workflow_contract(self, name, pointer):
        relative = PurePosixPath(name)
        require(relative.parent == PurePosixPath(".github/workflows")
                and relative.suffix in {".yml", ".yaml"},
                "YAML contracts must belong to .github/workflows")
        require(isinstance(pointer, list) and len(pointer) in {2, 5}
                and pointer[0] == "jobs" and isinstance(pointer[1], str)
                and (len(pointer) == 2 or pointer[2] == "steps" and isinstance(pointer[4], str)
                     and pointer[4] in {"run", "uses"}),
                "Workflow contract must select a job or an executable step")
        job = at_pointer(self.load(name, "yaml"), pointer[:2])
        require(isinstance(job, dict), "Workflow contract job must be an object")
        if "uses" in job:
            nonempty(job["uses"], "Reusable workflow contract")
        else:
            steps = job.get("steps")
            require(job.get("runs-on") and isinstance(steps, list) and steps
                    and any(isinstance(step, dict)
                            and any(isinstance(step.get(key), str) and step[key].strip()
                                    for key in ("run", "uses")) for step in steps),
                    "Workflow contract job has no executable steps")
        if len(pointer) == 5:
            command = at_pointer(self.load(name, "yaml"), pointer)
            require(isinstance(command, str) and command.strip(),
                    "Workflow contract step has no executable command")

    def python_test(self, name, suite):
        relative = PurePosixPath(name)
        require("tests" in relative.parts[:-1] and relative.name.startswith("test_")
                and relative.suffix == ".py", "Python contracts must belong to test sources")
        tree = self.load(name, "python")
        modules = {alias.asname or alias.name for node in tree.body if isinstance(node, ast.Import)
                   for alias in node.names if alias.name == "unittest"}
        bases = {alias.asname or alias.name for node in tree.body if isinstance(node, ast.ImportFrom)
                 and node.module == "unittest" for alias in node.names if alias.name == "TestCase"}
        declarations = [node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == suite]
        require(len(declarations) == 1, f"Missing or ambiguous Python test suite: {name}:{suite}")
        declaration = declarations[0]
        require(any(isinstance(base, ast.Name) and base.id in bases
                    or isinstance(base, ast.Attribute) and base.attr == "TestCase"
                    and isinstance(base.value, ast.Name) and base.value.id in modules
                    for base in declaration.bases),
                f"Python contract suite must directly extend unittest.TestCase: {name}:{suite}")
        require(any(isinstance(node, ast.FunctionDef) and node.name.startswith("test_")
                    for node in declaration.body),
                f"Python contract suite has no test declarations: {name}:{suite}")

    def check(self, anchor, contract=False):
        require(isinstance(anchor, dict), "Anchor must be an object")
        kind, name = anchor.get("kind"), anchor.get("path")
        self.path(name)
        if contract:
            require(kind in {"java", "yaml", "python-test"},
                    "Contracts must select Java tests, Python test suites, or workflow jobs/steps")
        if kind == "java":
            declaration = self.java_type(name, anchor.get("type"))
            member = anchor.get("member")
            if member is not None:
                body = declaration.child_by_field_name("body")
                require(isinstance(member, dict) and member.get("kind") in {"method", "field"},
                        "Java member must declare kind and name")
                matches = []
                for child in body.named_children:
                    if member["kind"] == "method" and child.type == "method_declaration":
                        node = child.child_by_field_name("name")
                        if node.text.decode("utf-8") == member.get("name"):
                            matches.append(child)
                    if member["kind"] == "field" and child.type in {"field_declaration", "constant_declaration"}:
                        for variable in child.named_children:
                            if variable.type == "variable_declarator":
                                node = variable.child_by_field_name("name")
                                if node.text.decode("utf-8") == member.get("name"):
                                    matches.append(variable)
                require(matches, f"Missing Java member declaration: {anchor['type']}.{member.get('name')}")
            if contract:
                require("/src/test/" in name or "/src/consumer/" in name,
                        f"Contract must belong to test sources: {name}")
                stack = [declaration]
                tests = False
                while stack:
                    node = stack.pop()
                    if node.type in {"annotation", "marker_annotation"}:
                        annotation = node.child_by_field_name("name")
                        tests |= annotation is not None and annotation.text.decode("utf-8").split(".")[-1] in {
                            "Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate"}
                    stack.extend(node.named_children)
                require(tests, f"Contract suite has no test declarations: {name}")
        elif kind in {"json", "yaml"}:
            if contract:
                self.workflow_contract(name, anchor.get("pointer"))
            value = at_pointer(self.load(name, kind), anchor.get("pointer"))
            check_configuration(value, anchor)
        elif kind == "xml":
            xpath = anchor.get("xpath")
            nonempty(xpath, "XML xpath")
            matches = self.load(name, kind).findall(xpath, {"m": "http://maven.apache.org/POM/4.0.0"})
            require(len(matches) == 1, f"Missing or ambiguous XML anchor: {name} {xpath}")
            require("expected" not in anchor or (matches[0].text or "").strip() == anchor["expected"],
                    f"XML value changed: {name} {xpath}")
        elif kind == "python":
            tree = self.load(name, kind)
            entry = anchor.get("entry")
            require(any(isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
                        and node.name == entry for node in tree.body),
                    f"Missing Python entry declaration: {name}:{entry}")
            guards = [node for node in tree.body if isinstance(node, ast.If)
                      and isinstance(node.test, ast.Compare)
                      and isinstance(node.test.left, ast.Name) and node.test.left.id == "__name__"
                      and len(node.test.ops) == 1 and isinstance(node.test.ops[0], ast.Eq)
                      and len(node.test.comparators) == 1
                      and isinstance(node.test.comparators[0], ast.Constant)
                      and node.test.comparators[0].value == "__main__"]
            require(any(direct_entry_call(statement, entry)
                        for guard in guards for statement in guard.body),
                    f"Python entry is not registered under __main__: {name}:{entry}")
        elif kind == "python-test":
            require(contract, "Python test suites must be registered as contracts")
            self.python_test(name, anchor.get("suite"))
        else:
            raise GateError(f"Unsupported structured anchor: {kind}")


def check_sources(root, decisions, tracked=None):
    inspector = SourceInspector(root, tracked)
    for identity, row in decisions.items():
        for field in ("anchors", "contracts"):
            for anchor in row[field]:
                try:
                    inspector.check(anchor, contract=field == "contracts")
                except GateError as failure:
                    raise GateError(f"{identity} {field}: {failure}") from failure


def check_registration(document, registration, comparison, bootstrap):
    require(isinstance(registration, dict) and type(registration.get("schemaVersion")) is int
            and registration["schemaVersion"] == 1,
            "Missing initial registration record")
    require(re.fullmatch(REVISION, registration.get("baselineRevision", "")) is not None,
            "Invalid registration baselineRevision")
    ids = registration.get("ids")
    require(isinstance(ids, list) and ids and ids == sorted(set(ids)),
            "Registration IDs must be unique and sorted")
    require(re.fullmatch(r"[0-9a-f]{64}", registration.get("decisionsSha256", "")) is not None,
            "Invalid registration digest")
    local = registration.get("localSync", {})
    require(local.get("method") == "static-inspection" and local.get("result") == "matched"
            and local.get("fields") == ["id", "status", *LOCAL_FIELDS],
            "Initial local synchronization declaration is missing")
    if bootstrap:
        require(registration["baselineRevision"] == comparison,
                "Bootstrap record is bound to a different trusted baseline")
        require(ids == sorted(row["id"] for row in document["decisions"])
                and registration["decisionsSha256"] == digest(document),
                "Bootstrap ID set or manifest digest differs from local registration")


def compare_history(current, previous, comparison):
    for identity, old in previous.items():
        require(identity in current, f"Registered decision deleted: {identity}")
        new = current[identity]
        removed = {field: [anchor for anchor in old[field] if anchor not in new[field]]
                   for field in ("anchors", "contracts")}
        if old["status"] != new["status"] or any(removed.values()):
            change = new.get("change", {})
            require(change.get("baselineRevision") == comparison
                    and change.get("fromStatus") == old["status"],
                    f"{identity}: status/anchor change lacks trusted disposition")
            nonempty(change.get("reason"), f"{identity}: change reason")
            for field in ("anchors", "contracts"):
                require(change.get("removed" + field.title(), []) == removed[field],
                        f"{identity}: removed {field} must be explicitly recorded")


def check_history(root, document, registration, comparison):
    comparison = revision(root, comparison)
    previous = historical_json(root, comparison, MANIFEST)
    old_registration = historical_json(root, comparison, REGISTRATION)
    if previous is None:
        require(old_registration is None, "Manifest missing from an already registered baseline")
        check_registration(document, registration, comparison, bootstrap=True)
    else:
        require(old_registration is not None and registration == old_registration,
                "Initial registration record must be retained unchanged")
        check_registration(document, registration, comparison, bootstrap=False)
        compare_history(validate_manifest(document), validate_manifest(previous), comparison)
    return {"baselineRevision": comparison, "bootstrap": previous is None,
            "baselineIds": sorted(validate_manifest(previous)) if previous is not None else []}


def header_fields(text):
    header = re.split(r"^## ", text, maxsplit=1, flags=re.MULTILINE)[0]
    fields = {}
    for match in re.finditer(r"^- ([^：\n]+)：([^\n]*)$", header, re.MULTILINE):
        key, value = match.group(1), match.group(2).strip()
        require(key not in fields, f"Duplicate ADR header field: {key}")
        fields[key] = value
    status = re.match(r"(Proposed|Accepted|Implemented|Superseded|Abandoned)(?:\b|（)", fields.get("状态", ""))
    require(status is not None, "ADR header has no valid status")
    return fields, status.group(1)


def check_local(root, decisions):
    directory = Path(root) / "docs/adr"
    require(directory.is_dir(), "Local docs/adr is required for local synchronization")
    bodies = {}
    for file in sorted(directory.rglob("*.md")):
        if file == directory / "README.md":
            continue
        require(file.parent == directory, f"Local ADR must be directly under docs/adr: {file.name}")
        text = file.read_text(encoding="utf-8-sig")
        heading = re.match(r"# (ADR-\d{4})[：:]", text)
        require(heading is not None, f"Unregistered ADR document: {file.name}")
        identity = heading.group(1)
        require(identity not in bodies, f"Duplicate local ADR: {identity}")
        require(file.name.startswith(identity[4:] + "-"), f"ADR file/ID mismatch: {file.name}")
        fields, status = header_fields(text)
        body = {"status": status}
        for field, label in LOCAL_FIELDS.items():
            require(label in fields, f"{identity}: missing header field {label}")
            value = fields[label]
            body[field] = None if value == "—" and field.endswith("Version") else "" if value == "—" else value
        bodies[identity] = (file.name, body)
    require(set(bodies) == set(decisions), "Local ADR documents and manifest ID sets differ")

    index = (directory / "README.md").read_text(encoding="utf-8-sig")
    rows = {}
    for line in index.splitlines():
        if not line.startswith("|") or "[ADR-" not in line:
            continue
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        require(len(cells) == 8, "ADR index must have eight structured columns")
        link = re.fullmatch(r"\[(ADR-\d{4})\]\(([^)]+)\)", cells[0])
        require(link is not None, "Malformed ADR index link")
        identity, filename = link.groups()
        require(identity not in rows, f"Duplicate ADR index entry: {identity}")
        values = [None if cell == "—" and field.endswith("Version") else "" if cell == "—" else cell
                  for field, cell in zip(LOCAL_FIELDS, cells[3:])]
        rows[identity] = (filename, {"status": cells[2], **dict(zip(LOCAL_FIELDS, values))})
    require(set(rows) == set(decisions), "Local ADR index and manifest ID sets differ")
    for identity, row in decisions.items():
        expected = {field: row[field] for field in ("status", *LOCAL_FIELDS)}
        require(bodies[identity] == rows[identity], f"{identity}: body/index mismatch")
        require(bodies[identity][1] == expected, f"{identity}: local/manifest metadata mismatch")


def event_baseline(root, supplied):
    event = read_json(os.environ["GITHUB_EVENT_PATH"])
    require(event["repository"]["full_name"] == os.environ["GITHUB_REPOSITORY"],
            "Event belongs to another repository")
    require(git(root, "rev-parse", "HEAD") == os.environ["GITHUB_SHA"],
            "Checkout differs from the workflow commit")
    name = os.environ["GITHUB_EVENT_NAME"]
    if name == "pull_request":
        expected = event["pull_request"]["base"]["sha"]
        require(git(root, "show", "-s", "--format=%P", "HEAD").split()
                == [expected, event["pull_request"]["head"]["sha"]],
                "PR checkout is not the event merge commit")
    elif name == "push":
        expected = event["before"]
    elif name == "workflow_dispatch":
        expected = event.get("inputs", {}).get("architecture_baseline_commit")
        branch = event["repository"]["default_branch"]
        tip = git(root, "ls-remote", "--exit-code", "origin", f"refs/heads/{branch}").split()
        require(len(tip) == 2 and expected == tip[0],
                "Manual baseline must be the current default-branch tip")
        require(historical_json(root, revision(root, expected), MANIFEST) is not None,
                "Manual runs require an already registered baseline")
    else:
        raise GateError(f"Unsupported architecture CI event: {name}")
    require(supplied == expected, "Baseline differs from the trusted event")
    return revision(root, expected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["local", "ci"])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--baseline", required=True, help="Full trusted comparison commit SHA")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    report = {"mode": args.mode, "result": "failed", "localAdrChecked": False,
              "semanticContractsExecuted": False, "baselineRevision": args.baseline}
    code = 1
    try:
        document = read_json(root / MANIFEST)
        decisions = validate_manifest(document)
        report["ids"] = sorted(decisions)
        report["manifestSha256"] = digest(document)
        tracked = None
        if args.mode == "ci":
            baseline = event_baseline(root, args.baseline)
            require(not (root / "docs/adr").exists(), "CI must use a clean checkout without local ADR text")
            require(not git(root, "status", "--porcelain", "--untracked-files=no"), "CI checkout is dirty")
            tracked = set(git(root, "ls-files", "-z").split("\0"))
            require({MANIFEST, REGISTRATION} <= tracked, "Architecture registration must be committed")
        else:
            baseline = revision(root, args.baseline)
            check_local(root, decisions)
            report["localAdrChecked"] = True
        report.update(check_history(root, document, read_json(root / REGISTRATION), baseline))
        check_sources(root, decisions, tracked)
        report["result"] = "passed"
        code = 0
    except (GateError, OSError, ValueError, TypeError, KeyError, SyntaxError, yaml.YAMLError) as failure:
        report["error"] = str(failure)
        print(f"Architecture decisions: {failure}", file=sys.stderr)
    finally:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if code == 0:
        print(f"Architecture {args.mode} declarations passed; semantic test evidence is separate.")
    return code


if __name__ == "__main__":
    sys.exit(main())
