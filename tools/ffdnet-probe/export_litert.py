#!/usr/bin/env python3
"""Export the prepared color FFDNet core to built-in LiteRT CONV_2D ops.
Uses schema object API directly; no training or TensorFlow conversion needed.
"""
import argparse,json,pathlib,struct
import flatbuffers
import numpy as np
from ai_edge_litert import schema_py_generated as s

p=argparse.ArgumentParser();p.add_argument('--skip-host-validation',action='store_true');p.add_argument('--prepared',required=True);p.add_argument('--output',required=True);a=p.parse_args()
src=pathlib.Path(a.prepared);out=pathlib.Path(a.output);out.mkdir(parents=True,exist_ok=True)
weights=[]
with (src/'weights.bin').open('rb') as f:
 depth,=struct.unpack('<I',f.read(4));assert depth==12
 for i in range(depth):
  ci,co=struct.unpack('<II',f.read(8));w=np.frombuffer(f.read(ci*co*9*4),dtype='<f4').reshape(co,ci,3,3).transpose(0,2,3,1).copy();b=np.frombuffer(f.read(co*4),dtype='<f4').copy();weights.append((ci,co,w,b))
 assert f.read()==b''
manifest=json.loads((src/'manifest.json').read_text());validation=[]
for size in manifest['tiles']:
 tile=int(size);n=tile//2;m=s.ModelT();m.version=3;m.description='Pretrained KAIR color FFDNet core; original weights; NHWC; no fallback ops';m.buffers=[s.BufferT()]
 code=s.OperatorCodeT();code.builtinCode=s.BuiltinOperator.CONV_2D;code.deprecatedBuiltinCode=s.BuiltinOperator.CONV_2D;code.version=1;m.operatorCodes=[code]
 g=s.SubGraphT();g.name='main';g.tensors=[];g.operators=[]
 def tensor(name,shape,data=None):
  t=s.TensorT();t.name=name;t.shape=shape;t.type=s.TensorType.FLOAT32;t.hasRank=True;t.buffer=0
  if data is not None:
   b=s.BufferT();b.data=np.frombuffer(data.astype('<f4').tobytes(),dtype=np.uint8);t.buffer=len(m.buffers);m.buffers.append(b)
  idx=len(g.tensors);g.tensors.append(t);return idx
 prev=tensor('packed_input',[1,n,n,13]);g.inputs=[prev]
 for i,(ci,co,w,b) in enumerate(weights):
  wi=tensor(f'weight_{i}',list(w.shape),w);bi=tensor(f'bias_{i}',[co],b);y=tensor(f'activation_{i}',[1,n,n,co])
  op=s.OperatorT();op.opcodeIndex=0;op.inputs=[prev,wi,bi];op.outputs=[y];op.builtinOptionsType=s.BuiltinOptions.Conv2DOptions
  opts=s.Conv2DOptionsT();opts.padding=s.Padding.SAME;opts.strideH=opts.strideW=1;opts.fusedActivationFunction=s.ActivationFunctionType.NONE if i==depth-1 else s.ActivationFunctionType.RELU;op.builtinOptions=opts;g.operators.append(op);prev=y
 g.outputs=[prev];m.subgraphs=[g]
 sig=s.SignatureDefT();sig.signatureKey='serving_default';sig.subgraphIndex=0
 ti=s.TensorMapT();ti.name='packed_input';ti.tensorIndex=g.inputs[0];to=s.TensorMapT();to.name='packed_output';to.tensorIndex=g.outputs[0];sig.inputs=[ti];sig.outputs=[to];m.signatureDefs=[sig]
 builder=flatbuffers.Builder(4*1024*1024);builder.Finish(m.Pack(builder),file_identifier=b'TFL3');path=out/f'ffdnet-color-{tile}.tflite';path.write_bytes(builder.Output())
 if a.skip_host_validation:
  print({'tile':tile,'bytes':path.stat().st_size,'host_validation':'deferred to Android CPU run'},flush=True)
  continue
 from ai_edge_litert.interpreter import Interpreter
 # Independent host LiteRT CPU validation of the exported flatbuffer.
 rt=Interpreter(model_path=str(path),num_threads=4);rt.allocate_tensors();x=np.fromfile(src/f'input-{tile}.bin',dtype='<f4').reshape(1,n,n,13);rt.set_tensor(rt.get_input_details()[0]['index'],x);rt.invoke();y=rt.get_tensor(rt.get_output_details()[0]['index']).ravel();ref=np.fromfile(src/f'expected-{tile}.bin',dtype='<f4');err=y-ref
 row={'tile':tile,'bytes':path.stat().st_size,'max_error':float(np.abs(err).max()),'rmse':float(np.sqrt(np.mean(err**2)))};assert row['max_error']<5e-4,row;validation.append(row);print(row,flush=True)
(out/'export-validation.json').write_text(json.dumps(validation,indent=2)+'\n')
