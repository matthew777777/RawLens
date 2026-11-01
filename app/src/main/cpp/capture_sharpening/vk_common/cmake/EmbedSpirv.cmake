# embed_spirv(<name> <src> <symbol>
#             [OUTPUT_DIR <dir>] [FLAGS <glslc args>...] [DEPENDS <files>...]
#             [HEADERS_VAR <list var>])
#
# Compiles <src> with ${GLSLC} into <dir>/<name>.spv and generates <dir>/<name>.h
# declaring `alignas(4) static const unsigned char <symbol>[]` and
# `<symbol>_size`. <dir> defaults to ${EMBED_SPIRV_OUTPUT_DIR}. Sets <name>_HDR
# in the caller and appends the header to <list var> when HEADERS_VAR is given.
set(RAWR_EMBED_BINARY_SCRIPT "${CMAKE_CURRENT_LIST_DIR}/EmbedBinary.cmake")

function(embed_spirv NAME SRC SYMBOL)
  cmake_parse_arguments(ARG "" "OUTPUT_DIR;HEADERS_VAR" "FLAGS;DEPENDS" ${ARGN})
  if(NOT ARG_OUTPUT_DIR)
    set(ARG_OUTPUT_DIR ${EMBED_SPIRV_OUTPUT_DIR})
  endif()
  if(NOT ARG_OUTPUT_DIR)
    message(FATAL_ERROR "embed_spirv(${NAME}): set OUTPUT_DIR or EMBED_SPIRV_OUTPUT_DIR")
  endif()
  # Resolved from this file, not a directory-scoped variable, so the function
  # works in sibling directories of the one that included it.
  set(EMBED_SCRIPT ${CMAKE_CURRENT_FUNCTION_LIST_DIR}/EmbedBinary.cmake)
  set(SPV ${ARG_OUTPUT_DIR}/${NAME}.spv)
  set(HDR ${ARG_OUTPUT_DIR}/${NAME}.h)
  add_custom_command(OUTPUT ${SPV}
    COMMAND ${GLSLC} ${ARG_FLAGS} ${SRC} -o ${SPV}
    DEPENDS ${SRC} ${ARG_DEPENDS}
    VERBATIM)
  add_custom_command(OUTPUT ${HDR}
    COMMAND ${CMAKE_COMMAND} -DINPUT=${SPV} -DOUTPUT=${HDR} -DSYMBOL=${SYMBOL} -P ${EMBED_SCRIPT}
    DEPENDS ${SPV} ${EMBED_SCRIPT}
    VERBATIM)
  set(${NAME}_HDR ${HDR} PARENT_SCOPE)
  if(ARG_HEADERS_VAR)
    set(${ARG_HEADERS_VAR} ${${ARG_HEADERS_VAR}} ${HDR} PARENT_SCOPE)
  endif()
endfunction()
