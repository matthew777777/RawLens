# SPDX-License-Identifier: GPL-3.0-or-later
"""Embed the green prepass compiled by the pinned NDK; called by CMake."""
import struct,sys
from pathlib import Path
source,output=map(Path,sys.argv[1:])
data=source.read_bytes();assert len(data)%4==0 and data[:4]==bytes.fromhex('03022307')
words=struct.unpack('<'+'I'*(len(data)//4),data)
text='// Generated from vf_mhc_green.comp; do not edit.\n#pragma once\n#include <cstdint>\nstatic constexpr uint32_t kMhcGreenSpv[] = {\n'
text+='\n'.join('    '+','.join('0x%08xu'%w for w in words[i:i+8])+',' for i in range(0,len(words),8))
output.write_text(text+'\n};\n')
