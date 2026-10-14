"""Bounded semantic Android UI navigation; no raw container writes or gameplay inputs."""
import re
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path
from .device import LabError,PACKAGE


def bounds(node):
    values=[int(v) for v in re.findall(r'\d+',node.get('bounds',''))]
    if len(values)!=4 or values[2]<=values[0] or values[3]<=values[1]:raise LabError('Invisible/invalid UI node')
    return tuple(values)


class Navigator:
    def __init__(self,device,evidence,*,timeout=100,clock=time.monotonic,sleep=time.sleep):
        self.device=device;self.root=Path(evidence);self.root.mkdir(parents=True,exist_ok=True)
        self.clock=clock;self.sleep=sleep;self.deadline=clock()+timeout;self.serial=0
        self.remote='/data/local/tmp/codex-ui-'+uuid.uuid4().hex+'.xml'
    def check(self):
        if self.clock()>=self.deadline:raise LabError('UI navigation deadline exceeded')
    def dump(self):
        self.check()
        for attempt in range(3):
            result=self.device.call('shell','uiautomator','dump','--compressed',self.remote,timeout=10)
            if 'dumped to' in result.lower():break
            self.sleep(.2);self.check()
        else:raise LabError('UI hierarchy unavailable; refusing stale bounds')
        text=self.device.call('exec-out','cat',self.remote,timeout=5)
        self.serial+=1;(self.root/('ui-'+str(self.serial)+'.xml')).write_text(text)
        tree=ET.fromstring(text)
        parents={c:p for p in tree.iter() for c in p}
        return tree,parents
    @staticmethod
    def matches(tree,label,attribute='text'):
        found=[]
        for n in tree.iter('node'):
            if n.get(attribute)==label and n.get('enabled')=='true' and n.get('package')==PACKAGE:
                try:bounds(n)
                except LabError:continue
                found.append(n)
        return found
    def tap(self,node,parents=None):
        self.check()
        if parents:
            while node.get('clickable')!='true' and node in parents:node=parents[node]
        x1,y1,x2,y2=bounds(node)
        self.device.call('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2),timeout=5)
        self.sleep(.15)
    def scroll(self,tree,horizontal=False):
        values=[]
        for n in tree.iter('node'):
            if n.get('scrollable')!='true':continue
            try:x1,y1,x2,y2=bounds(n)
            except LabError:continue
            is_horizontal='Horizontal' in n.get('class','') or y2-y1<200
            if is_horizontal==horizontal:values.append((n,(x2-x1)*(y2-y1)))
        if not values:raise LabError('No matching scroll surface')
        n=max(values,key=lambda v:v[1])[0];x1,y1,x2,y2=bounds(n)
        if horizontal:
            inset=min(150,(x2-x1)//4)
            points=(x2-inset,(y1+y2)//2,x1+inset,(y1+y2)//2)
        else:points=((x1+x2)//2,y2-35,(x1+x2)//2,y1+35)
        self.device.call('shell','input','swipe',*(str(p) for p in points),'350',timeout=5)
        self.sleep(.25)
    def click(self,label,*,attribute='text',scroll=False,horizontal=False):
        scrolls=0
        for attempt in range(200):
            tree,parents=self.dump();found=self.matches(tree,label,attribute)
            if len(found)==1:
                self.tap(found[0],parents);return
            if len(found)>1:raise LabError('Ambiguous UI label: '+label)
            if scroll:
                try:
                    self.scroll(tree,horizontal);scrolls+=1
                    if scrolls>=14:raise LabError('UI scroll search exhausted: '+label)
                except LabError as exc:
                    if str(exc)!='No matching scroll surface':raise
                    self.sleep(.2) # App startup/transition has no scroll semantics yet.
            else:self.sleep(.15)
        raise LabError('UI label not found: '+label)
    def editor(self,title):
        self.device.call('shell','input','keyevent','224',timeout=5)
        self.device.call('shell','am','start','-n',PACKAGE+'/'+PACKAGE+'.MainActivity',
            '-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER',timeout=15)
        self.click(title,scroll=True)
        # Detail Options icon must share the Play button row, not bottom navigation.
        tree,parents=self.dump();anchor=self.matches(tree,'Uninstall','content-desc');opts=self.matches(tree,'Options','content-desc')
        if len(anchor)!=1:raise LabError('Unique game-detail Uninstall anchor required')
        a,b,c,d=bounds(anchor[0]);cy=(b+d)//2
        opts=[n for n in opts if abs((bounds(n)[1]+bounds(n)[3])//2-cy)<100]
        if len(opts)!=1:raise LabError('Unique detail Options button required')
        self.tap(opts[0],parents);self.click('Edit container')
    def version(self,value):
        self.click('Graphics',scroll=True,horizontal=True)
        self.click('DXVK Version',scroll=True)
        self.click(value,scroll=True)
    def render_flag(self,enabled):
        self.click('Environment',scroll=True,horizontal=True)
        label='DXVK_FF7R_RENDER_PASSES'
        for attempt in range(14):
            tree,parents=self.dump();found=self.matches(tree,label)
            if found:
                if len(found)!=1:raise LabError('Duplicate render-pass env variable')
                if enabled:raise LabError('Render flag already exists; exact value must be verified separately')
                n=found[0]
                while n.get('clickable')!='true' and n in parents:n=parents[n]
                delete=[x for x in n.iter('node') if x.get('content-desc')=='Delete variable']
                if len(delete)!=1:raise LabError('Unique env delete button required')
                self.tap(delete[0],parents);return
            add=self.matches(tree,'Add environment variable','content-desc')
            if add:
                if not enabled:return
                self.tap(add[0],parents);break
            self.scroll(tree)
        else:raise LabError('Environment list deadline exceeded')
        tree,parents=self.dump();fields=[n for n in tree.iter('node') if n.get('class')=='android.widget.EditText' and n.get('package')==PACKAGE]
        fields=sorted(fields,key=lambda n:bounds(n)[1])
        if len(fields)!=2:raise LabError('Expected name/value dialog')
        self.tap(fields[0]);self.device.call('shell','input','text',label,timeout=5)
        tree,parents=self.dump();fields=sorted([n for n in tree.iter('node') if n.get('class')=='android.widget.EditText' and n.get('package')==PACKAGE],key=lambda n:bounds(n)[1])
        if len(fields)!=2 or fields[0].get('text')!=label:raise LabError('Environment name entry mismatch')
        self.tap(fields[1]);self.device.call('shell','input','text','1',timeout=5);self.click('Ok')
    def save(self,title):
        tree,parents=self.dump()
        labels=[n for n in tree.iter('node') if n.get('text','').startswith(title+' Config')]
        if len(labels)!=1:raise LabError('Wrong container editor title')
        _,top,_,bottom=bounds(labels[0])
        buttons=[]
        for n in tree.iter('node'):
            if n.get('class')=='android.widget.Button' and n.get('clickable')=='true' and n.get('enabled')=='true':
                try:b=bounds(n)
                except LabError:continue
                if b[1]<=bottom and b[3]>=top:buttons.append(n)
        if len(buttons)!=2:raise LabError('Pinned editor header must expose exactly close/save buttons')
        self.tap(max(buttons,key=lambda n:bounds(n)[0]))


def configure_dxvk(device,container,title,version,render_passes,evidence):
    """Apply only a DXVK selector and the named diagnostic flag via normal UI.

    No game may be running. Save an exact before/readback journal and verify
    all other fields and Engine.ini. Normal startup later applies prefix DLLs;
    UI readback alone never proves runtime rollback.
    """
    import json
    from .live import attest,command_ledger
    if not re.fullmatch(r'[A-Za-z0-9._-]+',version) or type(render_passes) is not bool:
        raise LabError('Invalid DXVK UI request')
    root=Path(evidence);root.mkdir(parents=True,exist_ok=False)
    start=time.monotonic()
    with device.lease(),command_ledger(device,root):
        device.assert_idle_debug(require_bridge=False)
        before=attest(device,container,check_alias=False)
        (root/'baseline.json').write_text(json.dumps(before,indent=2))
        expected=json.loads(json.dumps(before['config']))
        if len(re.findall(r'(^|,)version=',expected['dxwrapperConfig']))!=1:
            raise LabError('Unique DXVK version field required')
        expected['dxwrapperConfig']=re.sub(r'(^|,)version=[^,]*',lambda m:m[1]+'version='+version,expected['dxwrapperConfig'])
        flag='DXVK_FF7R_RENDER_PASSES=1'
        fields=expected['envVars'].split(' ')
        named=[v for v in fields if v.startswith('DXVK_FF7R_RENDER_PASSES=')]
        if named and named!=[flag]:raise LabError('Unexpected existing diagnostic flag value')
        change_flag=bool(named)!=render_passes
        if change_flag:
            if render_passes:expected['envVars']+=' '+flag
            else:expected['envVars']=' '.join(v for v in fields if v!=flag)
        if expected==before['config']:
            return dict(status='noop',directory=str(root),elapsed_s=time.monotonic()-start,ui_dumps=0)
        ui=Navigator(device,root/'ui')
        try:
            ui.editor(title);ui.version(version)
            if change_flag:ui.render_flag(render_passes)
            ui.save(title)
            after=attest(device,container,check_alias=False)
            (root/'configured.json').write_text(json.dumps(after,indent=2))
            if after['config']!=expected or after['engine_ini_sha256']!=before['engine_ini_sha256']:
                raise LabError('Unexpected persisted configuration or Engine.ini change; inspect journal before further testing')
            result=dict(status='configured',directory=str(root),elapsed_s=time.monotonic()-start,ui_dumps=ui.serial,
                changed_fields=[k for k in expected if expected[k]!=before['config'][k]],engine_unchanged=True)
            (root/'result.json').write_text(json.dumps(result,indent=2));return result
        except Exception as exc:
            (root/'failure.json').write_text(json.dumps(dict(error=str(exc),readback=attest(device,container,check_alias=False)),indent=2));raise
        finally:
            processes=device.call('shell','ps','-A','-o','UID,PID,NAME',timeout=5)
            if not re.search(r'ff7remake|wine|fex|box64|box86',processes,re.I):device.call('shell','am','force-stop',PACKAGE,timeout=8)
