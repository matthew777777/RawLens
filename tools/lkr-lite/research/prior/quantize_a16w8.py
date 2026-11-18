# SPDX-License-Identifier: Apache-2.0
"""Post-training quantisation of the exported fp32 prior with ai-edge-quantizer: int8 weights, int16
activations (static, calibrated on training crops). Qualcomm Hexagon HTP runs A16W8 natively.
  quantize_a16w8.py FP32.tflite PAIRS_DIR OUT.tflite"""
import os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from export_tflite import crops
from ai_edge_quantizer import quantizer, recipe

src, pairs, out = sys.argv[1:4]
q = quantizer.Quantizer(src)
q.load_quantization_recipe(recipe.static_wi8_ai16())
from ai_edge_litert.interpreter import Interpreter
name = Interpreter(model_path=src).get_signature_list()
sig = list(name.keys())[0]; inp = name[sig]['inputs'][0]
data = {sig: [{inp: c.numpy()} for c in crops(pairs, 256, 64, seed=1)]}
res = q.calibrate(data)
q.quantize(res).export_model(out, overwrite=True)
print('wrote', out, os.path.getsize(out), 'bytes')
