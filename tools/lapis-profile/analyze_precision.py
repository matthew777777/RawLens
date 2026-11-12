#!/usr/bin/env python3
"""Inspect RAW16 code lattices. This cannot establish ADC precision or dynamic range."""
import argparse, array, collections, json, math, sys
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('raw');p.add_argument('--width',type=int,required=True);p.add_argument('--height',type=int,required=True)
p.add_argument('--stride',type=int);p.add_argument('--step',type=int,default=4)
a=p.parse_args();stride=a.stride or a.width*2
assert stride>=a.width*2 and stride%2==0 and a.step>0
values=array.array('H');values.frombytes(open(a.raw,'rb').read())
if sys.byteorder!='little':values.byteswap()
assert len(values)*2>=stride*a.height,'Truncated RAW buffer'
scaled10={round(i*16383/1023) for i in range(1024)}
report={'file':a.raw,'width':a.width,'height':a.height,'stride':stride,'sensorPrecisionVerified':False,'note':'Code distributions alone cannot prove sensor precision. Require actual DCG mode and calibrated exposure/noise/range measurements.','planes':[]}
for cy in range(2):
 for cx in range(2):
  hist=collections.Counter(values[y*(stride//2)+x] for y in range(cy,a.height,a.step) for x in range(cx,a.width,a.step))
  n=sum(hist.values());mean=sum(v*c for v,c in hist.items())/n
  variance=sum((v-mean)**2*c for v,c in hist.items())/n
  report['planes'].append({'phase':[cx,cy],'samples':n,'minimum':min(hist),'maximum':max(hist),'uniqueCodes':len(hist),'mean':mean,'stddev':math.sqrt(variance),'fractionAbove1023':sum(c for v,c in hist.items() if v>1023)/n,'fractionOnLeftShifted10BitLattice':sum(c for v,c in hist.items() if v%16==0)/n,'fractionOnFullRangeScaled10BitLattice':sum(c for v,c in hist.items() if v in scaled10)/n,'low4BitCounts':[sum(c for v,c in hist.items() if v%16==r) for r in range(16)]})
print(json.dumps(report,indent=2))
