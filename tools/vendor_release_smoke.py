#!/usr/bin/env python3
"""Black-box checks of the fully optimized release APK; backs up/restores the lens profile.

Build :app:assembleRelease and install its arm64 APK first. This script verifies
the installed package version, non-debuggable flags and APK checksum, forces
ART speed compilation, then uses only the public UI. It writes a test DNG.
"""
import subprocess, time, json, re, shlex, shutil, hashlib, os, xml.etree.ElementTree as ET
from pathlib import Path
adb=shutil.which('adb') or str(Path(os.environ.get('ANDROID_SDK_ROOT', Path.home()/'Library/Android/sdk'))/'platform-tools/adb')
pkg='com.matthew.rawlens'
out=Path('/tmp/rawlens-vendor-release-ui');out.mkdir(exist_ok=True)
def run(*args, timeout=30):
 r=subprocess.run([adb,*map(str,args)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout,check=True)
 return r.stdout.decode(errors='replace').strip()
def shell(*args,timeout=30): return run('shell',*args,timeout=timeout)
def dump():
 shell('uiautomator','dump','/sdcard/rawlens-vendor-ui.xml')
 text=run('exec-out','cat','/sdcard/rawlens-vendor-ui.xml')
 (out/'latest.xml').write_text(text)
 return ET.fromstring(text)
def bounds(n):return list(map(int,re.findall(r'\d+',n.get('bounds',''))))
def tapnode(n):
 x1,y1,x2,y2=bounds(n);shell('input','tap',(x1+x2)//2,(y1+y2)//2);time.sleep(.25)
def scroll(root,up=False):
 nodes=[n for n in root.iter('node') if n.get('scrollable')=='true' and len(bounds(n))==4]
 if not nodes:return False
 n=max(nodes,key=lambda n:(bounds(n)[2]-bounds(n)[0])*(bounds(n)[3]-bounds(n)[1]))
 x1,y1,x2,y2=bounds(n); x=(x1+x2)//2;lo=y2-(y2-y1)//6;hi=y1+(y2-y1)//6
 shell('input','swipe',x,hi if up else lo,x,lo if up else hi,350);time.sleep(.25);return True
def find(text=None, rid=None, exact=False, tries=9):
 for _ in range(tries):
  root=dump()
  for n in root.iter('node'):
   t=n.get('text','');desc=n.get('content-desc','')
   yes=(rid and n.get('resource-id','').endswith(':id/'+rid)) or (text is not None and ((t.lower()==text.lower() or desc.lower()==text.lower()) if exact else (text.lower() in t.lower() or text.lower() in desc.lower())))
   if yes and len(bounds(n))==4 and bounds(n)[3]>bounds(n)[1]:return n
  if not scroll(root):break
 raise AssertionError(f'UI control missing: text={text} id={rid}')
def click(text=None,rid=None,exact=False):tapnode(find(text,rid,exact))
def fill(n,text):
 tapnode(n)
 # These editors use selectAllOnFocus; input is passed as one shell-quoted argument.
 escaped=text.replace('%',r'\u0025').replace(' ','%s')
 shell('input','text',shlex.quote(escaped));time.sleep(.2)
def texts(): return '\n'.join(n.get('text','') for n in dump().iter('node'))
def back():shell('input','keyevent','KEYCODE_BACK');time.sleep(.25)
def main():
 for _ in range(9):
  root=dump()
  if any(n.get('resource-id','').endswith(':id/settingsButton') for n in root.iter('node')):return
  close=next((n for n in root.iter('node') if n.get('text','').lower() in ('close','cancel')),None)
  if close is not None:tapnode(close)
  else:back()
 raise AssertionError('main camera UI not reached')
def vendor():
 root=dump()
 if any(n.get('text','').lower().startswith('vendor keys •') for n in root.iter('node')):return
 row=next((n for n in root.iter('node') if n.get('text','').lower().startswith('vendor keys (per lens')),None)
 if row is not None:tapnode(row);return
 main();click(rid='settingsButton');click('General',exact=True);click('Vendor keys (per lens')
def export():
 click('Export keys (copy text)',exact=True)
 root=dump(); edits=[n for n in root.iter('node') if n.get('class')=='android.widget.EditText']
 if not edits:return {'v':1,'op':0,'entries':[]}
 profile=json.loads(edits[0].get('text'));click('Close',exact=True);return profile
def reset():
 vendor();click('Reset all',exact=True);vendor()
def import_profile(profile):
 click('Import keys',exact=True)
 edit=find('Paste an exported key set here',exact=True)
 fill(edit,json.dumps(profile,separators=(',',':'),ensure_ascii=True))
 back();click('Import',exact=True);time.sleep(.4)
def reopen():
 click('Reopen camera (applies session keys)',exact=True);time.sleep(4)
def shot(name):
 (out/(name+'.png')).write_bytes(subprocess.check_output([adb,'exec-out','screencap','-p']))
def logs():
 pid=shell('pidof',pkg).split()[0]
 return run('logcat','-d','--pid',pid,'-s','RawLensCamera:I','AndroidRuntime:E')
metadata=Path(__file__).resolve().parents[1]/'app/build/outputs/apk/release/output-metadata.json'
d=json.loads(metadata.read_text());assert d['variantName']=='release';element=next(e for e in d['elements'] if any(f.get('value',f.get('identifier'))=='arm64-v8a' for f in e.get('filters',[])))
apk=metadata.parent/element['outputFile']
print('Checking installed full release',apk,flush=True)
print('Using installed release for repeated UI checks',flush=True)
installed=run('shell','pm','path',pkg).splitlines()[0].removeprefix('package:')
actual_hash=shell('sha256sum',shlex.quote(installed)).split()[0]
assert actual_hash==hashlib.sha256(apk.read_bytes()).hexdigest(),'Installed APK differs from built release'
print('Installed APK SHA-256',actual_hash,flush=True)
package=shell('dumpsys','package',pkg)
flags='\n'.join(l for l in package.splitlines() if l.strip().startswith(('pkgFlags=','flags=[','versionName=')))
assert 'DEBUGGABLE' not in flags,flags
(out/'release-flags.txt').write_text(flags+'\n')
print(flags,flush=True)
print(shell('cmd','package','compile','-m','speed','-f',pkg,timeout=120),flush=True)
shell('am','force-stop',pkg);shell('am','start','-n',pkg+'/.MainActivity');time.sleep(10)
shot('startup')
vendor();original=export();(out/'original-profile.json').write_text(json.dumps(original))
print('Original profile backed up:',original,flush=True)
try:
 reset()
 # Resolve the standard control through the shipping discovery UI, never a guessed numeric ID.
 click('Keys advertised on this lens',exact=True)
 edit=next(n for n in dump().iter('node') if n.get('class')=='android.widget.EditText')
 fill(edit,'android.control.aeMode');back()
 row=next(n for n in dump().iter('node') if n.get('text','').startswith('android.control.aeMode\n'))
 tag=re.search(r'0x[0-9A-Fa-f]+',row.get('text',''))
 assert tag is not None,row.attrib
 tag=tag.group();print('Discovered numeric tag',tag,flush=True)
 click('Close',exact=True);vendor()
 profile={'v':1,'op':37379,'entries':[
  {'n':'android.control.aeMode','t':'Int32','s':'session','v':'1','e':True},
  {'n':tag,'t':'Int32','s':'request','v':'1','e':True}]}
 import_profile(profile)
 actual=export();assert dict(actual,entries=sorted(actual['entries'],key=lambda x:x['n']))==dict(profile,entries=sorted(profile['entries'],key=lambda x:x['n'])),actual
 print('Mode + early/request keys imported and exported PASS',flush=True)
 reopen();main();time.sleep(3);shot('after-vendor-reopen')
 log=logs();(out/'vendor-session.log').write_text(log)
 assert 'Vendor session parameters attached' in log,log[-4000:]
 assert 'operatingMode=37379, parameters=true' in log,log[-4000:]
 assert 'Vendor request keys applied' in log and tag in log,log[-4000:]
 assert 'FATAL EXCEPTION' not in log
 print('Release custom mode + session keys submitted; request numeric alias applied PASS',flush=True)
 # UI capture exercises the actual minified controller and writer, without private field access.
 before=logs();click(rid='shutter');deadline=time.monotonic()+70
 while time.monotonic()<deadline:
  log=logs()
  if len(re.findall(r'(?:DNG|JPEG|JXL) saved',log,re.I))>len(re.findall(r'(?:DNG|JPEG|JXL) saved',before,re.I)):break
  time.sleep(1)
 else:raise AssertionError('Capture did not produce a save log')
 shot('capture-saved');print('Full release capture/save PASS',flush=True)
 vendor();assert export()['op']==37379
 # Adding and deleting an inactive key must preserve the operating mode.
 click('Add key',exact=True)
 edits=[n for n in dump().iter('node') if n.get('class')=='android.widget.EditText']
 fill(edits[0],'vendor.releaseSmoke')
 edits=[n for n in dump().iter('node') if n.get('class')=='android.widget.EditText'];fill(edits[-1],'0');back()
 click('Enabled',exact=True);click('Save',exact=True)
 assert export()['op']==37379
 click('vendor.releaseSmoke')
 click('Delete',exact=True)
 assert export()['op']==37379
 print('Add/delete preserves operating mode PASS',flush=True)
 # A numeric tag absent from the catalog must be rejected in the real release probe.
 click('Add key',exact=True)
 edits=[n for n in dump().iter('node') if n.get('class')=='android.widget.EditText'];fill(edits[0],'0xFFFFFFFF')
 edits=[n for n in dump().iter('node') if n.get('class')=='android.widget.EditText'];fill(edits[-1],'1');back()
 click('Test on this lens',exact=True)
 assert 'Numeric tag not resolved' in texts()
 click('Cancel',exact=True)
 print('Unknown numeric tag refused without capture failure PASS',flush=True)
 reset();import_profile({'v':1,'op':37379,'entries':[]});assert export()['op']==37379
 print('Mode-only import/export PASS',flush=True)
finally:
 # Restore the exact original key set and mode, including an empty original profile.
 vendor();reset()
 if original.get('entries') or original.get('op',0):import_profile(original)
 assert export()==original,'Original profile restoration mismatch'
 reopen();main();shot('restored')
 (out/'final-camera.log').write_text(logs())
 print('Original profile restored PASS',flush=True)
print('RELEASE VENDOR UI SMOKE PASS',flush=True)
