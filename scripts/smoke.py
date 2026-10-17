#!/usr/bin/env python3
"""Real Paper 26.1.2 + PostgreSQL/MariaDB probe; isolated loopback server, player doubles.
Requires a throwaway DB via INVSYNC_TEST_* and a Maven-built test classpath.
The ephemeral server accepts Mojang's server EULA for this automated test.
"""
import hashlib
import os
import pathlib
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

root=pathlib.Path(__file__).resolve().parent.parent
java=pathlib.Path(os.environ["JAVA_HOME"])/"bin/java"
javac=java.with_name("javac")
plugin=pathlib.Path(sys.argv[1]).resolve()
artifact="https://fill-data.papermc.io/v1/objects/1d70b1dab9cf4a6de615209a536f3a45a2186240253c428213ce2188ab95e5f7/paper-26.1.2-74.jar"
def fetch(url):
    req=urllib.request.Request(url,headers={"User-Agent":"NetworkInventorySync/1.0 (https://github.com/FlintSH/network-inventory-sync)"})
    with urllib.request.urlopen(req,timeout=60) as response:return response.read()
jar=fetch(artifact)
assert hashlib.sha256(jar).hexdigest()=="1d70b1dab9cf4a6de615209a536f3a45a2186240253c428213ce2188ab95e5f7"
with tempfile.TemporaryDirectory(prefix="invsync-paper-") as temp:
    work=pathlib.Path(temp)
    (work/"plugins/NetworkInventorySync").mkdir(parents=True)
    shutil.copyfile(plugin,work/"plugins/invsync.jar")
    (work/"paper.jar").write_bytes(jar)
    (work/"eula.txt").write_text("eula=true\n")
    (work/"server.properties").write_text("server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nlevel-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nenable-status=false\npause-when-empty-seconds=-1\n")
    with (work/"server.properties").open("a") as props:
        props.write('generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\n')
    # Values are restricted to this CI/local test setup; no production config is read.
    config=(root/"src/main/resources/config.yml").read_text()
    config=config.replace("server-id: change-me","server-id: probe")
    config=config.replace("group: survival","group: probe")
    config=config.replace("type: postgresql","type: "+os.getenv("INVSYNC_TEST_TYPE","postgresql"))
    config=config.replace("host: 127.0.0.1","host: "+os.getenv("INVSYNC_TEST_HOST","127.0.0.1"))
    config=config.replace("port: 5432","port: "+os.getenv("INVSYNC_TEST_PORT","55432"))
    config=config.replace("name: invsync","name: "+os.getenv("INVSYNC_TEST_DATABASE","invsync"))
    config=config.replace("username: invsync","username: "+os.getenv("INVSYNC_TEST_USER","invsync"))
    config=config.replace("password-env: ''","password-env: 'INVSYNC_TEST_PASSWORD'")
    config=config.replace("ssl-mode: require","ssl-mode: disable")
    config=config.replace("health: false","health: true").replace("hunger: false","hunger: true")
    config=config.replace("table-prefix: invsync_","table-prefix: probe_"+os.urandom(4).hex()+"_")
    (work/"plugins/NetworkInventorySync/config.yml").write_text(config)
    os.environ.setdefault("INVSYNC_TEST_PASSWORD","")
    classes=work/"probe-classes";classes.mkdir()
    cp=(root/"target/test-classpath.txt").read_text().strip()
    subprocess.run([str(javac),"-cp",cp,"-d",str(classes),str(root/"scripts/probe/InventoryProbe.java")],check=True)
    (classes/"plugin.yml").write_text("name: InventoryProbe\nversion: '1'\nmain: dev.flint.invsync.probe.InventoryProbe\napi-version: '26.1.2'\ndepend: [NetworkInventorySync]\n")
    subprocess.run([str(java.with_name("jar")),"--create","--file",str(work/"plugins/probe.jar"),"-C",str(classes),"."],check=True)
    process=subprocess.Popen([str(java),"-Xms256m","-Xmx768m","-jar","paper.jar","--nogui"],cwd=work,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    output=queue.Queue();lines=[]
    def read():
        for line in process.stdout:output.put(line)
    threading.Thread(target=read,daemon=True).start()
    try:
        deadline=time.monotonic()+180
        while time.monotonic()<deadline:
            try:
                line=output.get(timeout=1);lines.append(line)
            except queue.Empty:
                if process.poll() is not None:break
        else:raise TimeoutError("Paper probe timed out")
        while not output.empty():lines.append(output.get())
        log="".join(lines)
        assert "INVSYNC_PROBE_OK" in log and "INVSYNC_PROBE_FAILED" not in log,"Probe failed"
        assert process.returncode==0,f"Paper exited {process.returncode}"
        print("PASS: real Paper 26.1.2 startup, item NBT/PDC, readiness hook, freeze and "+os.getenv("INVSYNC_TEST_TYPE","postgresql")+" handoff lifecycle")
    finally:
        if process.poll() is None:process.kill();process.wait()
        print("".join(lines))
