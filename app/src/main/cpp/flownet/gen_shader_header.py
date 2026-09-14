#!/usr/bin/env python3
"""Generate a C header with shader text as a char array from a .comp file."""
import sys

data = open(sys.argv[1], "rb").read()
name = sys.argv[2]

print(f"static const unsigned char {name}[] = {{")
for i in range(0, len(data), 16):
    chunk = data[i:i+16]
    print("    " + ", ".join(f"0x{b:02x}" for b in chunk) + ",")
print("};")
print(f"static const int {name}_size = {len(data)};")
