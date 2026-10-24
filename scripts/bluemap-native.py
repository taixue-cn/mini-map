#!/usr/bin/env python3
"""Real upstream BlueMap file render -> shipped MiniMap adapter -> distributed Fabric/Xaero, in a fresh loopback world."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import select
import shutil
import signal
import subprocess
import tempfile
import time
import uuid

project = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--accept-eula", action="store_true")
parser.add_argument("--bluemap-jar", type=pathlib.Path, required=True)
parser.add_argument("--bluemap-sha256", default="7bf475936c26107d476c63151c76d5b49e865eebeada12c9e7d92155de877b9f")
parser.add_argument("--resources-jar", type=pathlib.Path, help="Optional existing Mojang 26.3 client resource jar; never included in evidence bundles")
parser.add_argument("--java", default="java")
parser.add_argument("--xvfb", default=shutil.which("Xvfb"))
parser.add_argument("--work-root", type=pathlib.Path, default=pathlib.Path(tempfile.gettempdir()))
parser.add_argument("--reuse-runtime", type=pathlib.Path)
args = parser.parse_args()
if not args.accept_eula or not args.xvfb:
    parser.error("A local Xvfb executable and --accept-eula are required")
digest = hashlib.sha256(args.bluemap_jar.read_bytes()).hexdigest()
if digest != args.bluemap_sha256:
    parser.error("The supplied upstream BlueMap jar does not match its expected SHA-256")
subprocess.run([str(project / "gradlew"), ":server:jar", ":server:fixtureJar", ":client:jar", ":client:fixtureJar", ":client:downloadAssets", "--no-daemon"], cwd=project, check=True)
workspace = pathlib.Path(tempfile.mkdtemp(prefix="minimap-bluemap-", dir=args.work_root)).resolve()
(workspace / ".minimap-bluemap-fixture").write_text(str(uuid.uuid4()))
paper = workspace / "paper"
configuration = workspace / "configuration"
blue = configuration / "plugins/BlueMap"
(blue / "maps").mkdir(parents=True)
(blue / "storages").mkdir()


def config(path, body):
    (blue / path).write_text(body)


config("core.conf", f'accept-download: true\ndata: {json.dumps(str(paper / "bluemap"))}\nrender-thread-count: 1\nmetrics: false\nscan-for-mod-resources: false\n')
config("webapp.conf", "enabled: false\n")
config("webserver.conf", "enabled: false\n")
config("plugin.conf", "skin-download: false\nlive-player-markers: false\n")
config("storages/file.conf", f'storage-type: file\nroot: {json.dumps(str(paper / "bluemap/tiles"))}\ncompression: gzip\n')
config("maps/native-standard.conf", f'''world: {json.dumps(str(paper / 'world'))}
dimension: "minecraft:overworld"
storage: file
enable-hires: false
enable-perspective-view: false
enable-free-flight-view: false
render-mask: [{{ min-x: 608, max-x: 719, min-z: 608, max-z: 719, min-y: 70, max-y: 70 }}]
''')
mini_config = configuration / "plugins/MiniMapServer"
mini_config.mkdir()
(mini_config / "config.yml").write_text("server-id: native-standard-bluemap\n")
if args.resources_jar:
    data = configuration / "bluemap"
    data.mkdir()
    shutil.copy2(args.resources_jar, data / "minecraft-client-26.3.jar")
environment = os.environ | {"MINIMAP_TEST_UPSTREAM_BLUEMAP": "true", "MINIMAP_TEST_WORKSPACE": str(workspace),
                            "MINIMAP_TEST_MARKER": (workspace / ".minimap-bluemap-fixture").read_text()}
command = ["python3", str(project / "scripts/paper-smoke.py"), "--version", "26.3", "--java", args.java,
           "--directory", str(paper), "--accept-eula", "--fixture", "--hold",
           "--extra-plugin", str(args.bluemap_jar.resolve()), "--extra-config", str(configuration)]
if args.reuse_runtime:
    command += ["--reuse-runtime", str(args.reuse_runtime.resolve())]


def terminate(process):
    if process is None or process.poll() is not None:
        return
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait()


print(f"Upstream BlueMap native workspace: {workspace}", flush=True)
display = server = client = None
try:
    with (workspace / "paper-launch.log").open("w") as log:
        server = subprocess.Popen(command, cwd=project, env=environment, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    deadline = time.monotonic() + 360
    while time.monotonic() < deadline:
        if server.poll() is not None:
            raise RuntimeError("Paper exited before the upstream BlueMap render became readable")
        if (paper / "ready").exists() and (workspace / "expected.json").exists():
            break
        time.sleep(0.5)
    else:
        raise TimeoutError("The standard BlueMap adapter did not obtain the saved remote render")
    expected = json.loads((workspace / "expected.json").read_text())
    with (workspace / "xvfb.log").open("w") as log:
        display = subprocess.Popen([args.xvfb, "-displayfd", "1", "-screen", "0", "800x600x24", "-nolisten", "tcp", "-ac"],
                                   stdout=subprocess.PIPE, stderr=log, text=True, start_new_session=True)
    if not select.select([display.stdout], [], [], 20)[0]:
        raise TimeoutError("Xvfb did not become ready")
    number = display.stdout.readline().strip()
    if not number.isdecimal():
        raise RuntimeError("Xvfb failed to report a display")
    display.stdout.close()
    identities = []
    for index, host in enumerate(["127.0.0.1", "localhost"]):
        report = workspace / f"client-{index}.json"
        client_environment = os.environ | {"DISPLAY": f":{number}", "MINIMAP_TEST_JAVA": args.java,
            "MINIMAP_TEST_SERVER": f"{host}:{(paper / 'port').read_text().strip()}", "MINIMAP_TEST_MODE": "provider",
            "MINIMAP_TEST_ARGB": expected["argb"], "MINIMAP_TEST_HEIGHT": str(expected["height"]), "MINIMAP_TEST_LIGHT": str(expected["light"]),
            "MINIMAP_TEST_LIBRARIES": str(workspace / "libraries"), "MINIMAP_TEST_DIRECTORY": str(workspace / "client"),
            "MINIMAP_TEST_REPORT": str(report), "LIBGL_ALWAYS_SOFTWARE": "true", "ALSOFT_DRIVERS": "null"}
        with (workspace / f"client-{index}.log").open("w") as log:
            client = subprocess.Popen(["python3", str(project / "scripts/native-client.py")], cwd=project, env=client_environment,
                                      stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            assert client.wait(timeout=240) == 0, "Distributed Fabric client exited unsuccessfully"
        finally:
            terminate(client)
        result = json.loads(report.read_text())
        assert result["status"] == "PASS" and "outside loaded chunks" in result["detail"], result
        identity = re.search(r"root=(\S+) mw=(\S+) expected=(\S+)", pathlib.Path(str(report) + ".debug").read_text())
        assert identity and identity[2] == identity[3]
        identities.append((identity[1], identity[2]))
    assert identities[0] == identities[1], identities
    (paper / "stop").touch()
    assert server.wait(timeout=70) == 0, "Paper shutdown failed"
    result = {"status": "PASS", "upstream_sha256": digest, "expected": expected, "cache_identity": identities[0],
              "detail": "Actual upstream BlueMap file render, shipped fallback adapter, distributed Fabric/Xaero texture outside vanilla chunks, two entry addresses"}
    (workspace / "result.json").write_text(json.dumps(result))
    print(json.dumps(result), flush=True)
except BaseException:
    for path in [workspace / "binding.txt", workspace / "fixture-error.txt", workspace / "read-error.txt", paper / "console.log"]:
        if path.exists():
            print(f"{path.name}:\n{path.read_text(errors='replace')[-12000:]}", flush=True)
    raise
finally:
    if paper.exists():
        (paper / "stop").touch(exist_ok=True)
    terminate(client)
    terminate(server)
    terminate(display)
