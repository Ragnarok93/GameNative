#!/usr/bin/env python3
"""Direct verified component control; requires API-v2 matching debug APK pair."""
import argparse,json
from benchmark.device import Device
from benchmark.direct_control import ensure_imported,select,restore
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--serial',required=True);p.add_argument('--output',required=True)
s=p.add_subparsers(dest='operation',required=True)
for name in ['import','select']:
 q=s.add_parser(name);q.add_argument('--verification',required=True)
 if name=='select':
  q.add_argument('--allow-existing-container',action='store_true')
  q.add_argument('--turnip-autotune',choices=['unchanged','bandwidth','profiled'],default='unchanged')
  q.add_argument('--gpu-trace',action='store_true')
q=s.add_parser('restore');q.add_argument('--selection',required=True)
a=p.parse_args();d=Device(a.serial)
if a.operation=='import':r=ensure_imported(d,a.verification,a.output)
elif a.operation=='select':r=select(d,a.verification,a.output,allow_existing=a.allow_existing_container,turnip_autotune=a.turnip_autotune,gpu_trace=a.gpu_trace)
else:r=restore(d,a.selection,a.output)
print(json.dumps(r,indent=2))
