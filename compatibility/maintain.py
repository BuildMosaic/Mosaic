#!/usr/bin/env python3
"""Reviewed Kotlin mapping and published-registry proposals; stdlib plus GitHub CLI."""

import argparse
import base64
import calendar
import copy
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import json
import os
from pathlib import Path
import subprocess
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

import certify
import registry

ROOT = certify.ROOT
MAPPING = Path("mosaic-analysis-core/src/main/resources/org/buildmosaic/analysis/compiler-profiles.properties")
CENTRAL = "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin"
ARTIFACTS = ("kotlin-compiler-embeddable", "kotlin-gradle-plugin")


def stable_versions(metadata):
    result = set()
    for node in ET.fromstring(metadata).findall("./versioning/versions/version"):
        try:
            registry.version(node.text)
            result.add(node.text)
        except ValueError:
            pass
    return sorted(result, key=registry.version)


def central_versions():
    with urllib.request.urlopen(f"{CENTRAL}/{ARTIFACTS[0]}/maven-metadata.xml", timeout=60) as response:
        return stable_versions(response.read())


def artifact_headers(artifact, version, extension):
    url = f"{CENTRAL}/{artifact}/{version}/{artifact}-{version}.{extension}"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, method="HEAD"), timeout=30) as response:
            return response.headers
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def available(version):
    return all(artifact_headers(artifact, version, extension) is not None
               for artifact in ARTIFACTS for extension in ("pom", "jar"))


def pending_versions(versions, mapping, handled, now=None):
    """Keep mapped families, including backports; never expire or remove their mapping."""
    now = now or datetime.now(timezone.utc)
    month = now.year * 12 + now.month - 1 - 18
    year, month = divmod(month, 12)
    cutoff = now.replace(year=year, month=month + 1,
                         day=min(now.day, calendar.monthrange(year, month + 1)[1]))
    families = {registry.version(v)[:2] for v in mapping}
    eligibility = {}
    result = []
    for version in versions:
        parsed = registry.version(version)
        if version in mapping or version in handled or parsed < min(map(registry.version, mapping)):
            continue
        family = parsed[:2]
        if family not in eligibility:
            initial = f"{family[0]}.{family[1]}.0"
            headers = artifact_headers(ARTIFACTS[0], initial, "pom") if initial in versions else None
            eligibility[family] = family in families or (
                headers is not None and headers.get("Last-Modified") is not None
                and parsedate_to_datetime(headers["Last-Modified"]) >= cutoff)
        if eligibility[family] and available(version):
            result.append(version)
    return sorted(result, key=registry.version)


def profile_order(version, mapping):
    target = registry.version(version)
    # Nearest demonstrated consumer first, not inferred ABI compatibility.
    def distance(compiler):
        v = registry.version(compiler)
        return (abs(v[0] - target[0]), abs(v[1] - target[1]), abs(v[2] - target[2]))
    return list(dict.fromkeys(mapping[v] for v in sorted(mapping, key=distance)))


def admit(text, version, api):
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if line.startswith(api + "="):
            values = set(line.split("=", 1)[1].split(",")) | {version}
            lines[index] = api + "=" + ",".join(sorted(values, key=registry.version))
            break
    else:
        lines.append(f"{api}={version}")
    return "\n".join(lines) + "\n"


def investigate(version, java_installations, runtime=None):
    registry.version(version)
    path = ROOT / MAPPING
    original = path.read_text()
    _, mapping = certify.compiler_profiles()
    attempts = []
    if runtime is None:
        runtime = certify.Harness(version, "runtime", java_installations)
    elif runtime.version != version:
        runtime = certify.Harness(version, "runtime", java_installations, runtime_candidate=runtime)
    result = {"kotlin": version, "runtime": "fail", "analysis": "not-run", "attempts": attempts,
              "runtimeReport": str(runtime.directory / "report.json")}
    if certify.certify(runtime):
        result["diagnostic"] = ("Runtime consumer failed; resolve its diagnostics before enabling support.\n" +
                                "\n".join(runtime.report["failureDiagnostics"]))
        return result
    result["runtime"] = "pass"
    try:
        for api in profile_order(version, mapping) + ([version] if version not in mapping.values() else []):
            new_profile = api not in mapping.values()
            # A tentative entry creates a shared-source build target; probes remain explicitly marked.
            path.write_text(admit(original, version, api) if new_profile else original)
            probe = certify.Harness(version, "analysis", java_installations, True, api, runtime)
            status = certify.certify(probe)
            attempts.append({"api": api, "newProfile": new_profile, "probe": "fail" if status else "pass",
                             "report": str(probe.directory / "report.json"),
                             "diagnostics": probe.report["failureDiagnostics"]})
            if status:
                continue
            path.write_text(admit(original, version, api))
            production = certify.Harness(version, "analysis", java_installations, False, api, runtime)
            result["productionReport"] = str(production.directory / "report.json")
            attempts[-1]["productionReport"] = result["productionReport"]
            if certify.certify(production):
                attempts[-1]["production"] = "fail"
                attempts[-1]["diagnostics"].extend(production.report["failureDiagnostics"])
                continue
            try:
                production.report["productionInstallation"] = certify.production_installation(
                    production, [version], java_installations)
            except Exception as failure:
                production.report["failureDiagnostics"].append(str(failure))
                attempts[-1]["installation"] = "fail"
                attempts[-1]["diagnostics"].append(str(failure))
                continue
            finally:
                production.finish()
            result.update(analysis="pass", api=api, newProfile=new_profile,
                          mapping=path.read_text())
            return result
        result["analysis"] = "fail"
        result["diagnostic"] = (
            "No profile passed every required check. Implement the compilation, ABI or semantic adaptation "
            "shown in the attached reports, then rerun discovery. No mapping or public guarantee was enabled.")
        return result
    finally:
        path.write_text(original)


def release_update(current, evidence, train, release, revision, versions):
    """Derive one continuous interval; freeze every older implicit ceiling before advancing it."""
    registry.version(release)
    if train not in ("runtime", "analysis") or evidence.get("scope") != train:
        raise ValueError("Release certification train mismatch")
    if (evidence.get("result") != "pass" or evidence.get("releaseVersion") != release
            or evidence.get("sourceRevision") != revision):
        raise ValueError("Successful certification must identify the actual release and immutable source")
    compilers = sorted(set(evidence["compilers"]), key=registry.version)
    if not compilers or len(compilers) != len(evidence["reports"]):
        raise ValueError("Missing compiler evidence")
    observed = set()
    runtime_identity = None
    for path in evidence["reports"]:
        report = json.loads((ROOT / path).read_text())
        compiler = report["requestedCompilerVersion"]
        if (report.get("harnessResult") != "pass" or report.get("compatibilityProbe") is not False
                or report.get("scope") != train or not certify.scope_passed(report)
                or report.get("actualCompilerVersion") != compiler or report.get("sourceRevision") != revision
                or not report.get("environment", {}).get("java17Version", "").startswith("17.")):
            raise ValueError("Incomplete, probing or mismatched compiler evidence")
        identity = sorted((a["coordinate"], a["sha256"]) for a in report["runtimeArtifacts"])
        if not identity or (runtime_identity is not None and identity != runtime_identity):
            raise ValueError("Runtime candidate bytes changed within certification")
        runtime_identity = identity
        observed.add(compiler)
    if observed != set(compilers):
        raise ValueError("Missing compiler evidence")
    if train == "analysis":
        installation = evidence.get("productionInstallation", {})
        if not installation.get("tests") or installation.get("failed", 1) or installation.get("skipped", 1):
            raise ValueError("Production installation must pass without skips")
    lower, upper = compilers[0], compilers[-1]
    in_interval = {v for v in versions if registry.version(lower) <= registry.version(v) <= registry.version(upper)}
    if in_interval != set(compilers):
        raise ValueError("Uncertified stable compiler in the proposed interval; certify gaps before extending it")
    updated = copy.deepcopy(registry.validate(current))
    frontier = updated["latestCertifiedKotlin"]
    if frontier is None or registry.version(upper) > registry.version(frontier):
        for entries in (updated["runtime"], updated["analysis"]):
            for bounds in entries.values():
                if "maxKotlin" not in bounds:
                    if frontier is None:
                        raise ValueError("Cannot assign historical support to an entry with no certified ceiling")
                    bounds["maxKotlin"] = frontier
        updated["latestCertifiedKotlin"] = upper
    bounds = {"minKotlin": lower}
    if upper != updated["latestCertifiedKotlin"]:
        bounds["maxKotlin"] = upper
    if release in updated[train] and updated[train][release] != bounds:
        raise ValueError("Existing released entry differs; review its evidence manually")
    updated[train][release] = bounds
    return registry.validate(updated)


def gh(*args, payload=None):
    command = ["gh", *args]
    if payload is not None:
        command += ["--input", "-"]
    result = subprocess.run(command, cwd=ROOT, input=None if payload is None else json.dumps(payload),
                            text=True, capture_output=True, check=True)
    return result.stdout


def handled_versions():
    titles = []
    for endpoint in ("pulls?state=all&per_page=100", "issues?state=all&per_page=100"):
        pages = json.loads(gh("api", f"repos/{{owner}}/{{repo}}/{endpoint}", "--paginate", "--slurp"))
        titles.extend(item["title"] for page in pages for item in page)
    prefix = "Kotlin compatibility: "
    return {title[len(prefix):] for title in titles if title.startswith(prefix)}


def propose(branch, title, body, files):
    prs = json.loads(gh("pr", "list", "--state", "all", "--head", branch, "--base", "main", "--json", "url"))
    if prs:
        print(f"Already proposed: {prs[0]['url']}")
        return
    issues = json.loads(gh("issue", "list", "--state", "all", "--search", title, "--json", "title,url"))
    existing = next((issue for issue in issues if issue["title"] == title), None)
    if existing:
        print(f"Already reported: {existing['url']}")
        return
    repository = os.environ["GITHUB_REPOSITORY"]
    sha = json.loads(gh("api", f"repos/{repository}/git/ref/heads/main"))["object"]["sha"]
    # Never publish tested files on a different main revision.
    if subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip() != sha:
        raise ValueError("main advanced during verification; rerun against its new revision")
    refs = json.loads(gh("api", f"repos/{repository}/git/matching-refs/heads/{branch}"))
    ref = next((r for r in refs if r["ref"] == f"refs/heads/{branch}"), None)
    if ref is None:
        ref = json.loads(gh("api", f"repos/{repository}/git/refs", payload={"ref": f"refs/heads/{branch}", "sha": sha}))
    if ref["object"]["sha"] != sha:
        # Recover an interrupted proposal without adding duplicate commits or overwriting human edits.
        commit = json.loads(gh("api", f"repos/{repository}/commits/{ref['object']['sha']}"))
        if [parent["sha"] for parent in commit["parents"]] != [sha] or not commit["commit"]["verification"]["verified"]:
            raise ValueError(f"Existing {branch} needs manual review/rebase; refusing to overwrite it")
        for path, content in files.items():
            blob = json.loads(gh("api", f"repos/{repository}/contents/{path}?ref={branch}"))
            if base64.b64decode(blob["content"]).decode() != content:
                raise ValueError(f"Existing {branch} differs from verified changes; refusing to overwrite it")
    else:
        mutation = """mutation($input: CreateCommitOnBranchInput!) {
          createCommitOnBranch(input: $input) { commit { oid signature { isValid } } }
        }"""
        result = json.loads(gh("api", "graphql", payload={"query": mutation, "variables": {"input": {
            "branch": {"repositoryNameWithOwner": repository, "branchName": branch},
            "expectedHeadOid": sha, "message": {"headline": title},
            "fileChanges": {"additions": [{"path": path, "contents": base64.b64encode(content.encode()).decode()}
                                           for path, content in files.items()]},
        }}}))
        signature = result.get("data", {}).get("createCommitOnBranch", {}).get("commit", {}).get("signature")
        if not signature or not signature.get("isValid"):
            raise ValueError("GitHub did not verify the automation commit signature")
    try:
        print(gh("pr", "create", "--base", "main", "--head", branch, "--title", title, "--body", body))
    except subprocess.CalledProcessError as failure:
        diagnostic = ("Automatic PR creation failed: " + failure.stderr +
                      "\nEnable Settings → Actions → General → Workflow permissions → "
                      "Allow GitHub Actions to create and approve pull requests. "
                      f"The verified branch is `{branch}`; open its PR against main manually.\n")
        print(diagnostic)
        print(gh("issue", "create", "--title", title, "--body", body + "\n" + diagnostic))


def evidence_url():
    return f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}"


def discover(java_installations, plan=False, kotlin=None):
    _, mapping = certify.compiler_profiles()
    versions = pending_versions(central_versions(), mapping, handled_versions())
    if kotlin is not None:
        registry.version(kotlin)
        versions = [v for v in versions if v == kotlin]
    if plan:
        with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
            output.write("versions=" + json.dumps(versions) + "\n")
        print("New available stable Kotlin releases: " + json.dumps(versions))
        return
    if not versions:
        print("No new available stable Kotlin releases requiring investigation.")
        return
    runtime = None
    for version in versions:
        runtime = runtime or certify.Harness(version, "runtime", java_installations)
        result = investigate(version, java_installations, runtime)
        if not runtime.report.get("runtimeArtifacts"):
            runtime = None
        directory = ROOT / "build/reports/compatibility" / version
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "maintenance.json").write_text(json.dumps(result, indent=2) + "\n")
        title = f"Kotlin compatibility: {version}"
        body = (f"Discovered Kotlin {version} from Maven Central; compiler and KGP POMs/jars are available.\n\n"
                f"Runtime: **{result['runtime']}**. Analysis: **{result['analysis']}**.\n\n"
                f"[Certification workflow and uploaded evidence]({evidence_url()}) (artifact `kotlin-compatibility`). "
                "Reports include direct-compiler fixtures, metadata, semantic comparison and failure logs.\n\n")
        if result["analysis"] == "pass":
            body += (f"Introspector API: {result['api']} ({'new shared-source build' if result['newProfile'] else 'reused'}). "
                     "Production admission passed without the probe flag. The existing real Maven public-plugin "
                     "installation fixture passed for this exact compiler/KGP, checking selected artifact bytes, "
                     "compiler provenance, VERIFIED/MISSING/UNVERIFIED semantics and Java 17 execution.\n\n"
                     "Only this new compiler was targeted. This unreleased candidate establishes no published "
                     "compatibility guarantee; no registry update, release publication or merge is performed.")
            propose(f"automation/kotlin-{version}", title, body, {str(MAPPING): result["mapping"]})
        else:
            attempts = copy.deepcopy(result["attempts"])
            for attempt in attempts:
                # Keep GitHub's issue body bounded; complete diagnostics stay in the Actions artifact.
                attempt["diagnostics"] = [d if len(d) <= 2000 else d[:200] + "\n…\n" + d[-1700:]
                                          for d in attempt["diagnostics"]]
            body += result["diagnostic"] + "\n\nAttempts (full diagnostics in evidence):\n\n" + json.dumps(attempts, indent=2)
            print(gh("issue", "create", "--title", title, "--body", body))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    discovery = commands.add_parser("discover")
    discovery.add_argument("--java-installations", default="")
    discovery.add_argument("--plan", action="store_true")
    discovery.add_argument("--kotlin")
    release = commands.add_parser("registry")
    release.add_argument("--evidence", type=Path, required=True)
    release.add_argument("--train", choices=("runtime", "analysis"), required=True)
    release.add_argument("--version", required=True)
    release.add_argument("--revision", required=True)
    args = parser.parse_args()
    if args.command == "discover":
        discover(args.java_installations, args.plan, args.kotlin)
    else:
        # Stable releases only; prerelease publication never becomes a registry guarantee.
        try:
            registry.version(args.version)
        except ValueError:
            print("Prerelease: no public compatibility registry proposal.")
            return
        tag = f"{args.train}-{args.version}"
        published = json.loads(gh("release", "view", tag, "--json", "url,isDraft"))
        if published["isDraft"]:
            raise ValueError("Release must be published before proposing registry support")
        path = ROOT / "compatibility/registry.json"
        current = registry.load(path)
        updated = release_update(current, json.loads(args.evidence.read_text()), args.train,
                                 args.version, args.revision, central_versions())
        if updated == current:
            print("Registry already records this certified release.")
            return
        body = (f"Record the actual released {args.train} {args.version}.\n\n"
                f"[GitHub Release]({published['url']}) · [Successful release certification and evidence]({evidence_url()}) "
                "(artifact `release-compatibility`).\n\n"
                "Bounds derive from successful production certification for every available stable compiler in "
                "the interval. Older implicit ceilings are frozen before advancing the frontier. "
                "No Runtime/Analysis relationship bounds are inferred from synthetic candidate versions.")
        propose(f"automation/registry-{tag}", f"Compatibility registry: {tag}", body,
                {"compatibility/registry.json": json.dumps(updated, indent=2) + "\n"})


if __name__ == "__main__":
    main()
