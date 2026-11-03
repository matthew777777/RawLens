#!/usr/bin/env python3
"""Independent ownership, halo and memory model for the tile-local schedule."""
import argparse,hashlib,json
from pathlib import Path

ARRAYS={'raw':(38,11),'vh':(28,6),'lp':(30,7),'pq':(24,4),'green':(26,5),'missing':(22,3)}

def axis(n):
 intervals=[]
 for origin in range(0,n,176):
  extent=min(194,n-origin)
  if extent==18:continue
  for group in range(11):
   first=origin+9+group*16;last=min(first+16,origin+extent-9)
   if first<last:intervals.append((first,last))
 intervals.sort();cursor=9
 for first,last in intervals:
  assert first==cursor,(n,first,cursor)
  cursor=last
 assert cursor==n-9,(n,cursor)
 return intervals

def run():
 widths=[19,20,35,175,176,177,193,194,195,351,352,353,369,370,371,3840]
 heights=[19,21,177,194,195,2160];rows=[]
 for w in widths:
  for h in heights:
   x=axis(w);y=axis(h)
   assert sum(b-a for a,b in x)*sum(b-a for a,b in y)==(w-18)*(h-18)
   rows.append({'size':[w,h],'owned_rectangles':len(x)*len(y),'interior_pixels':(w-18)*(h-18),'no_gaps_or_multiple_writers':True})
 # Each stored shared item has exactly one loading invocation, independent of
 # source phase, while every invocation reaches every stage barrier.
 for edge,radius in ARRAYS.values():
  assert edge==16+2*radius
  owners=[0]*(edge*edge)
  for lane in range(256):
   for k in range(lane,edge*edge,256):owners[k]+=1
  assert set(owners)=={1}
 dependencies=[('final','missing',0,3),('missing','green',3,2),
               ('green','vh',5,1),('green','lp',5,2),('missing','pq',3,1),
               ('green','raw',5,4),('vh','raw',6,4),('pq','raw',4,5),
               ('lp','raw',7,1),('missing','raw',3,3)]
 for consumer,producer,base,tap in dependencies:
  assert base+tap<=ARRAYS[producer][1],(consumer,producer)
 shared=sum(edge*edge*4 for edge,radius in ARRAYS.values());assert shared==19456
 tiles=((3840+175)//176)*((2160+175)//176)
 return {'scope':'Independent index/ownership/dependency model, not GPU performance or numerical acceptance','case_count':len(rows),'passed':True,'shared_bytes':shared,'arrays':ARRAYS,'dependencies':dependencies,'cases':rows,'removed_4k_payload_bytes':{'atlas_planes':tiles*194*194*14,'rgba32f_frame':3840*2160*16},'source_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--report',type=Path,required=True);a=p.parse_args();r=run();a.report.write_text(json.dumps(r,indent=2)+'\n');print('PASS',r['case_count'],'ownership cases; shared bytes',r['shared_bytes'])
