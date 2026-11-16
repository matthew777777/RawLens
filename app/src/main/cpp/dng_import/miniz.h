// TinyDNG's decode-only miniz surface, backed by Android's system zlib.
#pragma once
#include <zlib.h>
typedef uLong mz_ulong;
#define mz_uncompress uncompress
#define MZ_OK Z_OK
