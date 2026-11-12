// SPDX-License-Identifier: GPL-3.0-or-later
// Firmware-specific wrapper; contains no vendor library payload.
.text
.global hook
.extern original_body
.extern entry_for
.extern push_int
.extern push_long
.extern update
.extern destroy
.extern log_print
.extern entry_count
hook:
stp x29,x30,[sp,#-128]!
mov x29,sp
stp x19,x20,[sp,#16]
stp x21,x22,[sp,#32]
mov x21,x0
mov x19,x1
mov w20,wzr
ldr w3,[x21,#0x10]
mov w0,#4
adr x1,log_tag
adr x2,log_fmt
bl log_print
ldr w8,[x21,#0x10]
cbnz w8,done
mov x0,x19
mov w1,#10
movk w1,#13,lsl #16
mov w2,wzr
add x8,sp,#48
bl entry_for
mov w8,#32
movk w8,#0,lsl #16
str x8,[sp,#112]
add x0,sp,#48
add x1,sp,#112
bl push_int
mov w8,#8160
movk w8,#0,lsl #16
str x8,[sp,#112]
add x0,sp,#48
add x1,sp,#112
bl push_int
mov w8,#6120
movk w8,#0,lsl #16
str x8,[sp,#112]
add x0,sp,#48
add x1,sp,#112
bl push_int
mov w8,#0
movk w8,#0,lsl #16
str x8,[sp,#112]
add x0,sp,#48
add x1,sp,#112
bl push_int
mov x0,x19
mov w1,#10
movk w1,#13,lsl #16
add x2,sp,#48
bl update
add x0,sp,#48
bl destroy
mov x0,x19
mov w1,#11
movk w1,#13,lsl #16
mov w2,wzr
add x8,sp,#48
bl entry_for
mov w8,#32
movk w8,#0,lsl #16
str x8,[sp,#112]
add x0,sp,#48
add x1,sp,#112
bl push_long
mov w8,#8160
movk w8,#0,lsl #16
str x8,[sp,#112]
add x0,sp,#48
add x1,sp,#112
bl push_long
mov w8,#6120
movk w8,#0,lsl #16
str x8,[sp,#112]
add x0,sp,#48
add x1,sp,#112
bl push_long
mov w8,#57600
movk w8,#1525,lsl #16
str x8,[sp,#112]
add x0,sp,#48
add x1,sp,#112
bl push_long
mov x0,x19
mov w1,#11
movk w1,#13,lsl #16
add x2,sp,#48
bl update
add x0,sp,#48
bl destroy
mov x0,x19
mov w1,#12
movk w1,#13,lsl #16
mov w2,wzr
add x8,sp,#48
bl entry_for
mov w8,#32
movk w8,#0,lsl #16
str x8,[sp,#112]
add x0,sp,#48
add x1,sp,#112
bl push_long
mov w8,#8160
movk w8,#0,lsl #16
str x8,[sp,#112]
add x0,sp,#48
add x1,sp,#112
bl push_long
mov w8,#6120
movk w8,#0,lsl #16
str x8,[sp,#112]
add x0,sp,#48
add x1,sp,#112
bl push_long
mov w8,#0
movk w8,#0,lsl #16
str x8,[sp,#112]
add x0,sp,#48
add x1,sp,#112
bl push_long
mov x0,x19
mov w1,#12
movk w1,#13,lsl #16
add x2,sp,#48
bl update
add x0,sp,#48
bl destroy
done:
mov x0,x21
mov x1,x19
bl trampoline
ldp x21,x22,[sp,#32]
ldp x19,x20,[sp,#16]
ldp x29,x30,[sp],#128
ret
trampoline:
sub sp,sp,#0x100
b original_body
log_tag: .asciz "RAWLENS_META"
log_fmt: .asciz "setStaticDataXiaoMi sensor field=%d"
entry_fmt: .asciz "updated scaler tag=%u count=%u status=%d"
