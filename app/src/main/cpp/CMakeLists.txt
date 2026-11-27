cmake_minimum_required(VERSION 3.22.1)
project("pdf2svg_bridge" LANGUAGES C)

# The TeX engine and its SVG bridge are shipped only for arm64.
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    return()
endif()

set(MUPDF_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../document/src/main/jniLibs/${ANDROID_ABI}")
add_library(pdf2svg_bridge SHARED pdf2svg_bridge.c)
# MuPDF is packaged exclusively by :document. Link by SONAME without declaring
# another IMPORTED packaging input in :app; track the binary for relinking.
target_link_directories(pdf2svg_bridge PRIVATE "${MUPDF_DIR}")
target_link_libraries(pdf2svg_bridge PRIVATE mupdf_java log)
set_property(TARGET pdf2svg_bridge APPEND PROPERTY LINK_DEPENDS "${MUPDF_DIR}/libmupdf_java.so")
target_link_options(pdf2svg_bridge PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")
