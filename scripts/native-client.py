#!/usr/bin/env python3
"""Launch the distributed Fabric jars, not a developer environment. Requires a display (or Xvfb)."""
import concurrent.futures, hashlib, json, os, pathlib, shutil, subprocess, urllib.request

project=pathlib.Path(__file__).resolve().parents[1]
gradle=pathlib.Path(os.environ.get('GRADLE_USER_HOME',pathlib.Path.home()/'.gradle'))/'caches'
cache=pathlib.Path(os.environ['MINIMAP_TEST_LIBRARIES']);cache.mkdir(parents=True,exist_ok=True)
def artifact(group,name,release):
 return next(gradle.glob(f'modules-2/files-2.1/{group}/{name}/{release}/*/{name}-{release}.jar'))
version='26.3';loader='0.20.1'
info=json.loads((gradle/f'fabric-loom/{version}/mojang_minecraft_info.json').read_text())
profile=json.load(urllib.request.urlopen(f'https://meta.fabricmc.net/v2/versions/loader/{version}/{loader}/profile/json'))
def fetch(item):
 target=cache/item['url'].rsplit('/',1)[1]
 def valid(path):return path.exists() and (not item.get('sha1') or hashlib.sha1(path.read_bytes()).hexdigest()==item['sha1'])
 if valid(target):return target
 for source in gradle.glob('modules-2/files-2.1/**/'+target.name):
  if valid(source):shutil.copy2(source,target);return target
 with urllib.request.urlopen(item['url'],timeout=60) as response:body=response.read()
 if item.get('sha1') and hashlib.sha1(body).hexdigest()!=item['sha1']:raise ValueError('Checksum mismatch')
 target.write_bytes(body);return target
libraries=[]
for lib in info['libraries']:
 rules=lib.get('rules',[])
 if rules:
  allowed=False
  for rule in rules:
   if rule.get('os',{}).get('name','linux')=='linux':allowed=rule['action']=='allow'
  if not allowed:continue
 if 'artifact' in lib.get('downloads',{}):libraries.append(lib['downloads']['artifact'])
for lib in profile['libraries']:
 group,name,release=lib['name'].split(':');libraries.append({'url':lib.get('url','https://maven.fabricmc.net/')+group.replace('.','/')+'/'+name+'/'+release+'/'+name+'-'+release+'.jar'})
with concurrent.futures.ThreadPoolExecutor(8) as pool:classpath=list(pool.map(fetch,libraries))
classpath.append(gradle/f'fabric-loom/{version}/minecraft-client.jar')
run=pathlib.Path(os.environ['MINIMAP_TEST_DIRECTORY']);mods=run/'mods';mods.mkdir(parents=True,exist_ok=True)
jars=[artifact('net.fabricmc','fabric-language-kotlin','1.14.1+kotlin.2.4.20'),project/'client/build/libs/mini-map-client-0.1.0.jar',project/'client/build/libs/mini-map-client-0.1.0-fixture.jar',artifact('net.fabricmc.fabric-api','fabric-api','0.165.0+26.3')]
if not os.environ.get('MINIMAP_TEST_OMIT_XAERO'):
 for group,artifact,release in [('xaero.map','xaeroworldmap-fabric-26.3','1.47.1'),('xaero.lib','xaerolib-fabric-26.3','1.8.3')]:jars.append(next(gradle.glob('modules-2/files-2.1/'+group+'/'+artifact+'/'+release+'/*/'+artifact+'-'+release+'.jar')))
for jar in jars:shutil.copy2(jar,mods/jar.name)
cmd=[os.environ.get('MINIMAP_TEST_JAVA','java'),'-Xmx1536m','-XX:ActiveProcessorCount=4','-cp',os.pathsep.join(map(str,classpath)),profile['mainClass'],'--username',os.environ.get('MINIMAP_TEST_USERNAME','MiniMapTest'),'--version',version,'--gameDir',str(run),'--assetsDir',str(gradle/'fabric-loom/assets'),'--assetIndex',version+'-'+info['assetIndex']['id'],'--accessToken','0','--userType','legacy','--width','640','--height','480']
raise SystemExit(subprocess.call(cmd,cwd=run))
