#!/usr/bin/env python3
"""Verify the shader's duplicated-center median without extra dependencies."""
from itertools import product
from pathlib import Path
import random
import re
import struct

source = (Path(__file__).resolve().parents[1] / "app/src/main/assets/shaders/rcd_packed/rcd_median.comp").read_text()
pairs = [(int(a), int(b)) for a, b in re.findall(r"sortPair\(values\[(\d+)\], values\[(\d+)\]\)", source)]
assert len(pairs) == 19

def middle(neighbors, center):
    values = list(neighbors)
    for a, b in pairs:
        values[a], values[b] = min(values[a], values[b]), max(values[a], values[b])
    return max(values[2], min(center, values[4])), max(values[3], min(center, values[5]))

for bits in product((0, 1), repeat=9):
    expected = sorted(list(bits[:8]) + [bits[8], bits[8]])[4:6]
    assert list(middle(bits[:8], bits[8])) == expected

rng = random.Random(333)
def half(value):
    return struct.unpack("e", struct.pack("e", value))[0]
for _ in range(100000):
    samples = [struct.unpack("e", struct.pack("H", rng.randrange(0x4801)))[0] for _ in range(9)]
    expected = sorted(samples[:8] + [samples[8], samples[8]])[4:6]
    actual = middle(samples[:8], samples[8])
    assert list(actual) == expected
    assert half(0.5 * half(sum(actual))) == half(0.5 * half(sum(expected)))
print("PASS: 512 Boolean cases and 100000 finite-half cases; 19-comparator weighted median")
