#!/usr/bin/env python3
"""Boot a fresh loopback Paper server and verify the released plugin enables. No production files are used."""
import argparse, hashlib, json, os, pathlib, shutil, socket, subprocess, time, urllib.request

p=argparse.ArgumentParser()
p.add_argument('--version',choices=['1.21.4','26.3'],required=True)
p.add_argument('--java',default='java')
p.add_argument('--directory',type=pathlib.Path,required=True)
p.add_argument('--accept-eula',action='store_true')
p.add_argument('--fixture',action='store_true')
p.add_argument('--hold',action='store_true')
p.add_argument('--updates',action='store_true')
p.add_argument('--without-plugin',action='store_true')
p.add_argument('--reuse-runtime',type=pathlib.Path,help='Reuse cache/libraries/versions from an isolated test directory of the same version')
p.add_argument('--extra-plugin',type=pathlib.Path,action='append',default=[])
a=p.parse_args()
if not a.accept_eula:p.error('Read https://aka.ms/MinecraftEULA and pass --accept-eula for this isolated test.')
root=a.directory.resolve();root.mkdir(parents=True,exist_ok=False)
project=pathlib.Path(__file__).resolve().parents[1]
def request(url):return urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'MiniMapIntegration/0.1.0 (https://github.com/taixue-cn/mini-map-server)'}),timeout=120).read()
build=json.loads(request(f'https://fill.papermc.io/v3/projects/paper/versions/{a.version}/builds'))[0]
asset=build['downloads']['server:default'];body=request(asset['url'])
assert hashlib.sha256(body).hexdigest()==asset['checksums']['sha256']
(root/'server.jar').write_bytes(body)
if a.reuse_runtime:
 for name in ['cache','libraries','versions']:
  source=a.reuse_runtime/name
  if source.is_dir():shutil.copytree(source,root/name)
(root/'plugins').mkdir()
if not a.without_plugin:shutil.copy2(project/'paper/build/libs/mini-map-server-0.1.0.jar',root/'plugins')
if a.fixture and not a.without_plugin:shutil.copy2(project/'paper/build/libs/paper-0.1.0-fixture.jar',root/'plugins')
for plugin in a.extra_plugin:shutil.copy2(plugin,root/'plugins'/plugin.name)
with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
(root/'eula.txt').write_text('eula=true\n')
(root/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nwhite-list=false\nenforce-whitelist=false\nspawn-protection=0\nview-distance=2\nsimulation-distance=2\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-players=2\n')
(root/'port').write_text(str(port))
env=os.environ.copy();env['JAVA_TOOL_OPTIONS']='-XX:ActiveProcessorCount=4'+(' -Dminimap.fixture.updates=true' if a.updates else '')
with (root/'console.log').open('w') as log:
 server=subprocess.Popen([a.java,'-Xmx1G','-jar','server.jar','--nogui'],cwd=root,env=env,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True)
 try:
  deadline=time.monotonic()+180
  while time.monotonic()<deadline:
   text=(root/'console.log').read_text()
   if server.poll() is not None:raise RuntimeError('Paper exited; inspect console.log')
   if 'Done (' in text and (a.without_plugin or ('MiniMap Server enabled;' in text and (not a.fixture or 'MINIMAP_FIXTURE_READY' in text))):break
   time.sleep(.5)
  else:raise TimeoutError('Paper did not become ready')
  print(f'PASS Paper {a.version} build {build["id"]}; loopback port {port}',flush=True)
  (root/'ready').write_text('ready')
  if a.hold:
   while not (root/'stop').exists():
    if server.poll() is not None:raise RuntimeError('Paper exited')
    time.sleep(.5)
 finally:
  if server.poll() is None:
   server.stdin.write('stop\n');server.stdin.flush()
   try:server.wait(timeout=40)
   except subprocess.TimeoutExpired:server.kill();server.wait()
