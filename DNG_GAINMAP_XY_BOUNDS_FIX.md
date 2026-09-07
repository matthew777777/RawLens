# DNG GainMap X/Y bounds regression fix

The v7 DNG already contained tag 51009 (`OpcodeList2`) with four GainMap opcodes, but their rectangle geometry was transposed.

Root cause: `android.graphics.Rect` was passed as `top,left,bottom,right` into a native function whose arguments are `xmin,ymin,xmax,ymax`. For a 4080x3060 RAW this generated GainMap bounds such as `Bottom=4080, Right=3060`. DNG GainMap rectangles are relative to ActiveArea, so `Bottom` exceeded the 3060-pixel ActiveArea height. Darktable correctly ignored/rejected the malformed flat-field opcode.

The bridge now passes `left,top,right,bottom`, giving `Bottom=3060, Right=4080` for a full 4080x3060 ActiveArea. The verification tool also checks every GainMap rectangle against ActiveArea dimensions, so this exact regression cannot pass unnoticed again.
