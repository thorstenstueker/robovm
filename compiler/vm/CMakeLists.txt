cmake_minimum_required(VERSION 3.20.0)


# The minimum Mac OS X version needed by apps built by RoboVM
set(MACOSX_MIN_VERSION 10.9)
# The minimum Mac OS X version needed by apps built by RoboVM
set(MACOSX_ARM64_MIN_VERSION 13.0)
# The minimum iOS version needed by apps built by RoboVM
set(IOS_MIN_VERSION 8.0)
# The minimum m1 Arm64 iOS simulator version needed by apps built by RoboVM
set(IOS_ARM64_SIM_MIN_VERSION 14.0)


project(build)
include(CheckCCompilerFlag)
include(ExternalProject)
include(${CMAKE_SOURCE_DIR}/MergeStaticLibObjectFiles.cmake)

enable_testing()

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE "debug")
endif()


if(NOT OS)
  message(FATAL_ERROR "OS not set!")
endif()
if(NOT ARCH)
  message(FATAL_ERROR "ARCH not set")
endif()

set(64_BIT NO)
if(OS STREQUAL "linux")
  set(LINUX YES)
  set(OS_FAMILY linux)
  if (ARCH STREQUAL "x86_64")
    set(X86_64 YES)
    set(64_BIT YES)
    set(CARCH x86_64)
  elseif (ARCH STREQUAL "arm64")
    # Added 27.09.2026 (tsb). Both ARM and ARM64 on purpose: macosx/arm64 above sets only
    # ARM and ios/arm64-simulator sets only ARM64, which is a disagreement nobody noticed
    # because nothing downstream reads them yet. ios/arm64 sets both; this follows that one.
    #
    # Neither CARCH nor CTARGET, unlike every branch above, and that is deliberate: a
    # compiler running on an aarch64 host already targets aarch64. CARCH would feed
    # `-arch` into ASM_FLAGS, which is an Apple clang flag; CTARGET would force a
    # cross-compilation triple onto a native build. The three flag blocks below say what
    # to do instead of `-m64`, which aarch64 gcc rejects outright.
    set(ARM YES)
    set(ARM64 YES)
    set(64_BIT YES)
  else()
    message(FATAL_ERROR "Unsupported ARCH: ${ARCH}")
  endif()
elseif(OS STREQUAL "macosx")
  set(MACOSX YES)
  set(DARWIN YES)
  set(OS_FAMILY darwin)
  if (ARCH STREQUAL "x86_64")
    set(X86_64 YES)
    set(64_BIT YES)
    set(CTARGET "x86_64-apple-macos${MACOSX_MIN_VERSION}")
    set(CARCH x86_64)
  elseif (ARCH STREQUAL "arm64")
    set(ARM YES)
    set(64_BIT YES)
    set(MACOSX_MIN_VERSION ${MACOSX_ARM64_MIN_VERSION})
    set(CTARGET "arm64-apple-macos${MACOSX_MIN_VERSION}")
    set(CARCH arm64)
  else()
    message(FATAL_ERROR "Unsupported ARCH: ${ARCH}")
  endif()
  set(CPLATFORM_MIN_VERSION ${MACOSX_MIN_VERSION})
  set(CPLATFORM macos)
elseif(OS STREQUAL "ios")
  set(IOS YES)
  set(DARWIN YES)
  set(OS_FAMILY darwin)
  if (ARCH STREQUAL "x86_64-simulator")
    set(X86_64 YES)
    set(64_BIT YES)
    set(CTARGET "x86_64-apple-ios${IOS_MIN_VERSION}")
    set(CARCH x86_64)
    set(CPLATFORM ios-simulator)
  elseif (ARCH STREQUAL "arm64-simulator")
    set(ARM64 YES)
    set(64_BIT YES)
    set(IOS_MIN_VERSION ${IOS_ARM64_SIM_MIN_VERSION})
    set(CTARGET "arm64-apple-ios${IOS_MIN_VERSION}-simulator")
    set(CARCH arm64)
    set(CPLATFORM ios-simulator)
  elseif (ARCH STREQUAL "arm64")
    set(ARM YES)
    set(ARM64 YES)
    set(64_BIT YES)
    set(CTARGET "arm64-apple-ios${IOS_MIN_VERSION}")
    set(CARCH arm64)
    set(CPLATFORM ios)
  elseif (ARCH STREQUAL "thumbv7")
    set(ARM YES)
    set(THUMB YES)
    set(THUMBV7 YES)
    set(CTARGET "armv7-apple-ios${IOS_MIN_VERSION}")
    set(CARCH armv7)
    set(CPLATFORM ios)
  else()
    message(FATAL_ERROR "Unsupported ARCH: ${ARCH}")
  endif()
  set(CPLATFORM_MIN_VERSION ${IOS_MIN_VERSION})
elseif(OS STREQUAL "android")
  # Added 28.09.2026 (tsb). A2: the VM core for Bionic.
  #
  # OS_FAMILY is linux on purpose, and it is the reason this branch is short. The assembly
  # stubs are selected by ${OS_FAMILY}-${ARCH}, so android/arm64 gets exactly the three
  # files written for linux/arm64 in A1 -- same ELF, same AAPCS64, same register rules.
  # Bionic differs from glibc in its C library, not in its calling convention.
  #
  # BIONIC rather than ANDROID: CMake sets ANDROID itself when its own NDK toolchain file is
  # used, and a variable that means one thing here and another there is a trap. This build
  # does not use that toolchain file -- it follows the pattern iOS already uses, CTARGET plus
  # the platform's own clang, so there is one way of cross-compiling in this file and not two.
  set(BIONIC YES)
  set(OS_FAMILY linux)
  if(NOT ANDROID_API)
    # 21 is the NDK's own floor for 64-bit and well below MIN_SDK 26, so it constrains nothing
    # we ship. Raise it here rather than at the call site if that ever stops being true.
    set(ANDROID_API 21)
  endif()
  if (ARCH STREQUAL "arm64")
    set(ARM YES)
    set(ARM64 YES)
    set(64_BIT YES)
    set(CTARGET "aarch64-linux-android${ANDROID_API}")
  elseif (ARCH STREQUAL "x86_64")
    set(X86_64 YES)
    set(64_BIT YES)
    set(CTARGET "x86_64-linux-android${ANDROID_API}")
  else()
    message(FATAL_ERROR "Unsupported ARCH: ${ARCH}")
  endif()
  # No SYSROOT and no CARCH. The NDK's clang carries its own sysroot, and CARCH would feed
  # `-arch` into ASM_FLAGS, which is an Apple clang flag.
  #
  # Symbols newer than the target become weak rather than an error. libbase's unique_fd.h is
  # written for exactly this: it reaches for android_fdsan_exchange_owner_tag, which arrived in
  # API 29, and guards every use with `if (android_fdsan_...)`. Those guards only mean something
  # if the symbol may be absent at run time, which is what this flag arranges. Without it clang
  # reports the functions as undeclared at API 26 and the checks could never have run.
  add_definitions(-D__ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__)
else()
  message(FATAL_ERROR "Unsupported OS: ${OS}")
endif()


if(DARWIN)
  if(NOT SYSROOT)
    execute_process(COMMAND xcode-select --print-path 
      OUTPUT_VARIABLE XCODE_PATH 
      OUTPUT_STRIP_TRAILING_WHITESPACE
    )
    if(MACOSX)
      set(SYSROOT "${XCODE_PATH}/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk")
      if(NOT IS_DIRECTORY ${SYSROOT})
        message(FATAL_ERROR "Failed to locate MacOSX SDK")
      endif()
    elseif(IOS)
      if(ARCH MATCHES "^(x86_64-simulator|arm64-simulator)$")
        set(SYSROOT "${XCODE_PATH}/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk")
        if(NOT IS_DIRECTORY ${SYSROOT})
          message(FATAL_ERROR "Failed to locate iPhoneSimulator SDK")
        endif()
      else()
        set(SYSROOT "${XCODE_PATH}/Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk")
        if(NOT IS_DIRECTORY ${SYSROOT})
          message(FATAL_ERROR "Failed to locate iPhoneOS SDK")
        endif()
      endif()
    endif()
  endif()
  set(CPLATFORM_MIN_VERSION ${IOS_MIN_VERSION})
elseif(NOT LINUX AND NOT BIONIC)
  # 27.09.2026 (tsb): this was a bare else() with a fatal error in it, which meant the Linux
  # build died here -- twelve lines after the OS switch above had accepted it. Not something
  # arm64 introduced: linux-x86_64 hits it just the same, so the Linux VM build has been
  # broken in this tree for as long as this block has looked like this.
  #
  # The block asks Xcode where its SDK is. Linux has no Xcode and needs no sysroot; the
  # honest answer is to skip it, not to call the OS unsupported.
  message(FATAL_ERROR "Unsupported OS: ${OS}")
endif()

# Set up global C and C++ compiler flags
set(CMAKE_CXX_STANDARD 17)
set(C_FLAGS "${C_FLAGS} -std=gnu99 -fPIC")
set(CXX_FLAGS "${CXX_FLAGS} -fPIC")
set(C_CXX_FLAGS "${C_CXX_FLAGS} -Wall -fPIC")
if(NOT IOS)
  set(C_CXX_FLAGS "${C_CXX_FLAGS} -fdata-sections -ffunction-sections")
endif()
if(CTARGET)
  set(C_CXX_FLAGS "${C_CXX_FLAGS} -target ${CTARGET}")
elseif(LINUX AND ARM64)
  # Nothing to select. -m64 and -m32 are x86 word-size switches; on aarch64 there is one
  # word size and gcc errors on the flag rather than ignoring it.
elseif(64_BIT)
  set(C_CXX_FLAGS "${C_CXX_FLAGS} -m64")
else()
  set(C_CXX_FLAGS "${C_CXX_FLAGS} -m32")
endif()
if(SYSROOT)
  set(CMAKE_OSX_SYSROOT "${SYSROOT}")
endif()
if(IOS)
  set(CMAKE_OSX_DEPLOYMENT_TARGET "") #reset variable for compat with SDK 10.12, see issue #48
endif()

# Linker flags
set(LD_FLAGS "${LD_FLAGS} -fPIC")
if(CTARGET)
  set(LD_FLAGS "${LD_FLAGS} -target ${CTARGET}")
elseif(LINUX AND ARM64)
  # See the C flags above.
elseif(64_BIT)
  set(LD_FLAGS "${LD_FLAGS} -m64")
else()
  set(LD_FLAGS "${LD_FLAGS} -m32")
endif()

set(CMAKE_C_FLAGS_RELEASE "-Os -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-Os -DNDEBUG")

set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} ${C_CXX_FLAGS} ${C_FLAGS}")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} ${C_CXX_FLAGS} ${CXX_FLAGS}")
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} ${C_CXX_FLAGS} ${C_FLAGS}")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} ${C_CXX_FLAGS} ${CXX_FLAGS}")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} ${LD_FLAGS}")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} ${LD_FLAGS}")
set(CMAKE_MODULE_LINKER_FLAGS "${CMAKE_MODULE_LINKER_FLAGS} ${LD_FLAGS}")

# Assembler flags
set(ASM_FLAGS "${ASM_FLAGS} -fPIC")
if(CARCH)
  set(ASM_FLAGS "${ASM_FLAGS} -c -arch ${CARCH}")
elseif(LINUX AND ARM64)
  set(ASM_FLAGS "${ASM_FLAGS} -c")
elseif(64_BIT)
  set(ASM_FLAGS "${ASM_FLAGS} -c -m64")
else()
  set(ASM_FLAGS "${ASM_FLAGS} -c -m32")
endif()
if (CMAKE_BUILD_TYPE STREQUAL "debug")
  set(ASM_FLAGS "${ASM_FLAGS} -g")
endif()
if(MACOSX)
  set(ASM_FLAGS "${ASM_FLAGS} -target ${CTARGET}")
endif()
if(IOS)
  set(ASM_FLAGS "${ASM_FLAGS} -target ${CTARGET}")
endif()
if(BIONIC)
  # Same reason as the two above: the assembler is invoked as clang, so it needs to be told
  # what it is assembling for. Without this the .s files are built for the host.
  set(ASM_FLAGS "${ASM_FLAGS} -target ${CTARGET}")
endif()
set(CMAKE_ASM_COMPILE_OBJECT "${CMAKE_C_COMPILER} ${ASM_FLAGS} -o <OBJECT> <SOURCE>")
enable_language(ASM)

if(LINUX OR BIONIC)
  # Bionic takes -DLINUX too: the core's #ifdef LINUX paths are about the kernel interface --
  # /proc, futexes, ELF -- and those are the same. Where Bionic genuinely differs, the source
  # can say so with __ANDROID__, which the NDK's clang predefines, so no second flag is needed
  # here and none is invented.
  add_definitions(-DLINUX)
elseif(DARWIN)
  if(MACOSX)
    add_definitions(-DMACOSX)
  else()
    add_definitions(-DIOS)
  endif()
  add_definitions(-DDARWIN)
endif()


set(INSTALL_DIR ${CMAKE_SOURCE_DIR}/target/binaries/${OS}/${ARCH})

# at this point convert remove "-simulator" suffix as it will conflict as sub-modules
set(ARCH_WITH_ENV "${ARCH}")
if (ARCH STREQUAL "arm64-simulator")
  set(ARCH arm64)
  add_definitions(-DRVM_SIMULATOR)
elseif (ARCH STREQUAL "x86_64-simulator")
  set(ARCH x86_64)
  add_definitions(-DRVM_SIMULATOR)
endif()

if (ARCH STREQUAL "x86_64")
  add_definitions(-DRVM_X86_64)
elseif (ARCH STREQUAL "thumbv7")
  add_definitions(-DRVM_THUMBV7)
elseif (ARCH STREQUAL "arm64")
  add_definitions(-DRVM_ARM64)
endif()

set(LIB_SUFFIX ".a")
if (CMAKE_BUILD_TYPE STREQUAL "debug")
  set(LIB_SUFFIX "-dbg.a")
endif()

message(STATUS "ARCH: ${ARCH}")
message(STATUS "OS: ${OS}")
message(STATUS "SYSROOT: ${SYSROOT}")
message(STATUS "CMAKE_BUILD_TYPE: ${CMAKE_BUILD_TYPE}")
message(STATUS "CMAKE_C_COMPILER: ${CMAKE_C_COMPILER}")
message(STATUS "CMAKE_CXX_COMPILER: ${CMAKE_CXX_COMPILER}")
message(STATUS "CMAKE_C_FLAGS: ${CMAKE_C_FLAGS}")
message(STATUS "CMAKE_CXX_FLAGS: ${CMAKE_CXX_FLAGS}")
message(STATUS "CMAKE_C_FLAGS_DEBUG: ${CMAKE_C_FLAGS_DEBUG}")
message(STATUS "CMAKE_CXX_FLAGS_DEBUG: ${CMAKE_CXX_FLAGS_DEBUG}")
message(STATUS "CMAKE_C_FLAGS_RELEASE: ${CMAKE_C_FLAGS_RELEASE}")
message(STATUS "CMAKE_CXX_FLAGS_RELEASE: ${CMAKE_CXX_FLAGS_RELEASE}")
message(STATUS "CMAKE_EXE_LINKER_FLAGS: ${CMAKE_EXE_LINKER_FLAGS}")
message(STATUS "CMAKE_SHARED_LINKER_FLAGS: ${CMAKE_SHARED_LINKER_FLAGS}")
message(STATUS "CMAKE_MODULE_LINKER_FLAGS: ${CMAKE_MODULE_LINKER_FLAGS}")
message(STATUS "CMAKE_OSX_SYSROOT: ${CMAKE_OSX_SYSROOT}")

# Ugly hardcoding. This is the offset in bytes of the gcDescriptor member in the Class struct from core/include/robovm/types.h
# Should be the value of sizeof(void*)*3 for the target platform.
if(64_BIT)
  set(EXTGC_MARK_DESCR_OFFSET 24)
else()
  set(EXTGC_MARK_DESCR_OFFSET 12)
endif()

set(EXTGC_C_FLAGS "${C_CXX_FLAGS} -DGC_DISABLE_INCREMENTAL -DGC_DISCOVER_TASK_THREADS -DGC_FORCE_UNMAP_ON_GCOLLECT -DMARK_DESCR_OFFSET=${EXTGC_MARK_DESCR_OFFSET}")
# disable errors on clang clang-1500.3.9.4
set(EXTGC_C_FLAGS "${EXTGC_C_FLAGS} -Wno-error=incompatible-function-pointer-types -Wno-error=int-conversion")
set(EXTGC_LD_FLAGS "${CMAKE_EXE_LINKER_FLAGS}")
if(DARWIN)
  set(EXTGC_C_FLAGS "${EXTGC_C_FLAGS} -DGC_MAX_MACH_THREADS=1024 -DNO_DYLD_BIND_FULLY_IMAGE --sysroot=${SYSROOT}")
  set(EXTGC_LD_FLAGS "${EXTGC_LD_FLAGS} --sysroot=${SYSROOT}")
endif()

set(EXTGC_PARAMS  "--enable-shared=no --enable-munmap=1 --enable-parallel-mark=yes")
if(BIONIC)
  # 28.09.2026 (tsb), for A2. Two of bdwgc's own switches, and both are statements of fact about
  # this build rather than workarounds.
  #
  # GC_NOT_DLL: gc.h registers the caller's static data roots itself on Android unless this is
  # set, and to do that it declares `extern int __data_start[], _end[]`. gcconfig.h then declares
  # `extern char _end[]` in its AArch64/Linux section, and the two contradict each other --
  # bdwgc disagreeing with itself, for this one combination. The switch exists for a GC that is
  # not in a shared library, which is exactly what --enable-shared=no above produces.
  #
  # GC_DONT_DEFINE_LINK_MAP: dyn_load.c defines struct link_map and struct r_debug itself,
  # explaining in a comment that only bionic/linker/linker.h has them and that it is a C++
  # header. That has not been true for years; the NDK's <link.h> declares both for C, and the
  # definitions collide.
  #
  # NO_GETCONTEXT: Bionic has never implemented getcontext/setcontext. mach_dep.c uses
  # getcontext to capture the register set when scanning a thread's stack, and falls back
  # to setjmp when told the call is not there. Android is the platform this switch was
  # written for. configure did not set it by itself, which is what cross-compiling costs:
  # its feature test cannot run a program on the target to find out.
  set(EXTGC_C_FLAGS "${EXTGC_C_FLAGS} -DGC_NOT_DLL -DGC_DONT_DEFINE_LINK_MAP -DNO_GETCONTEXT")
endif()
if (CMAKE_BUILD_TYPE STREQUAL "debug")
  set(EXTGC_C_FLAGS "${EXTGC_C_FLAGS} -g")
  set(EXTGC_PARAMS "${EXTGC_PARAMS} --enable-gc-debug=yes")
endif()
if (IOS OR MACOSX)
  if (ARCH STREQUAL "x86_64")
    set(EXT_HOST --host=x86_64-apple-darwin7)
  elseif (ARCH STREQUAL "arm64-simulator")
    set(EXT_HOST --host=aarch64-apple-darwin7)
  elseif (ARCH STREQUAL "arm64")
    set(EXT_HOST --host=aarch64-apple-darwin7)
  elseif (ARCH STREQUAL "thumbv7")
      set(EXT_HOST --host=arm-apple-darwin7)
  endif()
elseif (BIONIC)
  # 28.09.2026 (tsb), for A2. The GC and libatomic_ops are autotools projects, and --host is how
  # autotools is told it is being cross-compiled. Without it configure tries to *run* what it has
  # just built -- an aarch64 Android binary, on this Mac -- and stops with "cannot run C compiled
  # programs", after every other part of the VM has already compiled.
  if (ARCH STREQUAL "arm64")
    set(EXT_HOST --host=aarch64-linux-android)
  elseif (ARCH STREQUAL "x86_64")
    set(EXT_HOST --host=x86_64-linux-android)
  endif()
endif()
ExternalProject_Add(libatomic_ops
  GIT_REPOSITORY https://github.com/ivmai/libatomic_ops.git
  GIT_TAG 4e22546e8a161b6bab93e43c115a22151daf45c5
  UPDATE_COMMAND ""
  CONFIGURE_COMMAND ""
  BUILD_COMMAND ""
  INSTALL_COMMAND ""
  BUILD_IN_SOURCE 1
)
ExternalProject_Add(extgc
  DEPENDS libatomic_ops
  GIT_REPOSITORY https://github.com/mobivm/bdwgc.git
  GIT_TAG c1e985028101ec710937b6c721d9f79941a15bce
  UPDATE_COMMAND ""
  PATCH_COMMAND bash -c "rm -rf libatomic_ops && ln -s ../../../libatomic_ops-prefix/src/libatomic_ops libatomic_ops"
  CONFIGURE_COMMAND bash -c "CC=${CMAKE_C_COMPILER} CFLAGS='${EXTGC_C_FLAGS}' LDFLAGS='${EXTGC_LD_FLAGS}' ./configure ${EXT_HOST} ${EXTGC_PARAMS} --prefix=${CMAKE_BINARY_DIR}/gc"
  BUILD_IN_SOURCE 1
)
install(FILES ${CMAKE_BINARY_DIR}/gc/lib/libgc.a DESTINATION ${INSTALL_DIR} RENAME libgc${LIB_SUFFIX})

add_subdirectory(core/src)
add_subdirectory(debug/src)
add_subdirectory(bc/src)
add_subdirectory(../rt "rt")
add_subdirectory(frameworksupport/src)
add_subdirectory(bro/src)
