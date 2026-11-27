cmake_minimum_required(VERSION 3.22)
project(VideoDownloaderQuickJS LANGUAGES C)

include(FetchContent)

FetchContent_Declare(
    quickjs_ng
    URL https://github.com/quickjs-ng/quickjs/archive/refs/tags/v0.17.0.tar.gz
    URL_HASH SHA256=559bc4c420475e55c7ab4510adbc562f55d7524d75e8e89d79ce4bb02f5687d9
)

FetchContent_GetProperties(quickjs_ng)

if(NOT quickjs_ng_POPULATED)
    FetchContent_Populate(quickjs_ng)
endif()

set(QJS_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(QJS_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

add_subdirectory(
    "${quickjs_ng_SOURCE_DIR}"
    "${quickjs_ng_BINARY_DIR}"
    EXCLUDE_FROM_ALL
)

if(TARGET qjs)
    target_compile_options(qjs PRIVATE
        $<$<CONFIG:Debug>:-O2>
    )
endif()

if(TARGET qjs_exe)
    target_compile_options(qjs_exe PRIVATE
        $<$<CONFIG:Debug>:-O2>
    )
endif()


target_compile_definitions(qjs PRIVATE JS_DEFAULT_STACK_SIZE=2097152)
set_target_properties(qjs_exe PROPERTIES
    OUTPUT_NAME qjs
    PREFIX lib
    SUFFIX .so
    ENABLE_EXPORTS FALSE
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
)

target_link_options(qjs_exe PRIVATE -Wl,--gc-sections)

add_library(qjs_packaging_anchor SHARED qjs_packaging_anchor.c)

set_target_properties(qjs_packaging_anchor PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
)

add_dependencies(qjs_packaging_anchor qjs_exe)
