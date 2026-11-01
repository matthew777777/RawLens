# Embeds SPIR-V with one write, avoiding a filesystem operation for every byte.
file(READ "${INPUT}" HEXDATA HEX)
string(REGEX REPLACE "(..)" "0x\\1," BYTES "${HEXDATA}")
file(WRITE "${OUTPUT}" "#pragma once\n#include <cstddef>\n#include <cstdint>\nalignas(4) static const unsigned char ${SYMBOL}[] = {\n${BYTES}\n};\nstatic constexpr std::size_t ${SYMBOL}_size = sizeof(${SYMBOL});\n")
