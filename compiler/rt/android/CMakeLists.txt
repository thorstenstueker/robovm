project(android)

set(ANDROID_RT_PATH "${CMAKE_CURRENT_LIST_DIR}")
add_definitions(-D__ROBOVM__)

include_directories(
  # system/core/liblog
  system/logging/include
  system/libbase/include

  # native helper
  libnativehelper/
  libnativehelper/include/
  libnativehelper/include_jni/
  libnativehelper/include_platform/
  libnativehelper/include_platform_header_only/
  libnativehelper/header_only_include

  # 28.09.2026 (tsb): the bundled third-party headers, for A2.
  #
  # These four are compiled from this tree and were never included from it. On a Mac that went
  # unnoticed for years, because expat.h, sqlite3.h, zlib.h and unicode/*.h all sit in the macOS
  # SDK as well and clang found them there -- so the sources were built against Apple's headers
  # and linked against our objects, and the versions happened to agree.
  #
  # Building for Android takes the SDK away and every one of them fails to resolve: sixteen
  # errors across ICUBinary.cpp, sqlite3_android.cpp and ExpatParser.cpp, all of the form
  # "'zlib.h' file not found" or "unknown type name 'z_stream'".
  external/expat/lib
  external/sqlite/dist
  external/zlib
  external/icu/icu4c/source/common
)

if(DARWIN)
  # this will enables BSD path of code instead of linux one that will allow lot of sources
  # to be compilable without changing sources
  add_definitions(-D_ALLBSD_SOURCE)
endif()

add_subdirectory(cmake/libcore)
add_subdirectory(cmake/libbase)
add_subdirectory(cmake/libnativehelper)
add_subdirectory(cmake/expat)
add_subdirectory(cmake/fdlibm)
add_subdirectory(cmake/icu)
add_subdirectory(cmake/javasqlite)
if(NOT DARWIN)
  # Mac OS X and iOS ships with sqlite so we'll just use that
  add_subdirectory(cmake/sqlite)
endif()
add_subdirectory(cmake/zlib)
add_subdirectory(cmake/boringssl)
add_subdirectory(cmake/art)
add_subdirectory(cmake/conscrypt)

set(ANDROID_OBJECTS
  ${LIBCORE_OBJECTS}
  ${SYSTEM_LIBBASE_OBJECTS}
  ${LIBNATIVEHELPER_OBJECTS}
  ${EXTERNAL_EXPAT_OBJECTS}
  ${EXTERNAL_FDLIBM_OBJECTS}
  ${EXTERNAL_ICU4C_OBJECTS}
  ${EXTERNAL_JAVASQLITE_OBJECTS}
  ${EXTERNAL_SQLITE_OBJECTS}
  ${EXTERNAL_ZLIB_OBJECTS}
  ${EXTERNAL_BORINGSSL_OBJECTS}
  ${ART_OBJECTS}
  ${LIBCONSCRYPT_OBJECTS}

  PARENT_SCOPE
)
