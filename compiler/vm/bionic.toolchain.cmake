# Cross-compiling the VM core to Android's Bionic, from a Mac or from Linux.
#
# Usage:
#   cmake -DCMAKE_TOOLCHAIN_FILE=<vm>/bionic.toolchain.cmake -DOS=android -DARCH=arm64 <vm>
#
# Why a toolchain file rather than three -D flags. CMAKE_SYSTEM_NAME has to be set before
# project() runs, and project() is on line 14 of CMakeLists.txt -- so it cannot be set from the
# OS switch further down, and a toolchain file is the only place CMake reads it early enough.
#
# Without it, a build on a macOS host keeps CMAKE_SYSTEM_NAME as Darwin and CMake adds
# `-arch <host>` and `-isysroot .../MacOSX.sdk` to every target it creates. Clearing
# CMAKE_OSX_ARCHITECTURES does not help; the cache already shows both as empty while the flags
# appear anyway, because the injection comes from the Darwin platform module and not from those
# variables. The build then dies with "unsupported option '-arch' for target
# 'aarch64-linux-android21'" -- and it dies in rt/android, a long way from the cause.
#
# Linux and not Android for the system name, deliberately. CMAKE_SYSTEM_NAME=Android switches on
# CMake's own NDK support, which picks the compiler, the sysroot and the target triple itself --
# a second way of cross-compiling beside the CTARGET pattern that iOS and this file already use.
# Linux tells CMake the one thing it needs to know, that this is not Apple, and leaves the rest
# to the OS switch in CMakeLists.txt. For CMake's purposes Bionic *is* Linux: ELF, .so, no
# frameworks.

set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)

# The NDK, from the environment if it is named, else the newest under the Android SDK.
if(DEFINED ENV{ANDROID_NDK_HOME})
  set(NDK "$ENV{ANDROID_NDK_HOME}")
elseif(DEFINED ENV{ANDROID_NDK_ROOT})
  set(NDK "$ENV{ANDROID_NDK_ROOT}")
else()
  if(DEFINED ENV{ANDROID_HOME})
    set(SDK "$ENV{ANDROID_HOME}")
  elseif(APPLE)
    set(SDK "$ENV{HOME}/Library/Android/sdk")
  else()
    set(SDK "$ENV{HOME}/Android/Sdk")
  endif()
  file(GLOB CANDIDATES "${SDK}/ndk/*")
  if(CANDIDATES)
    list(SORT CANDIDATES)
    list(REVERSE CANDIDATES)
    list(GET CANDIDATES 0 NDK)
  endif()
endif()

if(NOT NDK OR NOT IS_DIRECTORY "${NDK}")
  message(FATAL_ERROR
    "No Android NDK found. Set ANDROID_NDK_HOME, or install one under <sdk>/ndk/.")
endif()

# One prebuilt host toolchain per NDK, named after the host it runs on.
file(GLOB PREBUILT "${NDK}/toolchains/llvm/prebuilt/*")
if(NOT PREBUILT)
  message(FATAL_ERROR "No prebuilt LLVM toolchain in ${NDK}/toolchains/llvm/prebuilt/")
endif()
list(GET PREBUILT 0 LLVM)

set(CMAKE_C_COMPILER "${LLVM}/bin/clang")
set(CMAKE_CXX_COMPILER "${LLVM}/bin/clang++")
set(CMAKE_AR "${LLVM}/bin/llvm-ar" CACHE FILEPATH "")
set(CMAKE_RANLIB "${LLVM}/bin/llvm-ranlib" CACHE FILEPATH "")

# The compiler is asked to check nothing at configure time. It is a cross-compiler for a device
# that is not here, so CMake's usual "compile and run a probe" tests cannot pass.
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)

message(STATUS "Bionic toolchain: ${LLVM}")
