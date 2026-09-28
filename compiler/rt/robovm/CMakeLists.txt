project(robovm-rt-core)

include_directories(../../vm/core/include ${CMAKE_BINARY_DIR}/gc/include)

set(SRC
  src/main/native/rt.c
  src/main/native/reflection_helpers.c 
  src/main/native/java_lang_Class.c 
  src/main/native/java_lang_ClassCache.c 
  src/main/native/java_lang_Object.c 
  src/main/native/java_lang_Thread.c 
  src/main/native/java_lang_Throwable.c 
  src/main/native/java_lang_Runtime.c 
  src/main/native/java_lang_String.c 
  src/main/native/java_lang_System.c 
  src/main/native/java_lang_VMClassLoader.c 
  src/main/native/java_lang_ref_Reference.c 
  src/main/native/java_lang_reflect_AccessibleObject.c 
  src/main/native/java_lang_reflect_Array.c 
  src/main/native/java_lang_reflect_Constructor.c 
  src/main/native/java_lang_reflect_Field.c 
  src/main/native/java_lang_reflect_Method.c 
  src/main/native/java_lang_reflect_Proxy.c 
  src/main/native/java_net_NetworkInterface.c 
  src/main/native/org_robovm_rt_Signals.c 
  src/main/native/org_robovm_rt_GC.c 
  src/main/native/org_robovm_rt_VM.c 
  src/main/native/sun_misc_Unsafe.cpp
)

# 28.09.2026 (tsb), for A2. Scoped to the one file that needs it, and not to the target.
#
# org_robovm_rt_GC.c reaches bdwgc's setters through GC_call_with_alloc_lock, whose signature is
# void *(*)(void *). So GC_set_free_space_divisor -- void(int) -- is passed as that function
# pointer and its argument is passed as a pointer holding an integer. On every ABI this project
# targets the call lands correctly: one register-sized argument in, return value ignored. It is
# still two type violations, and clang 21 turned both from warnings into errors, so the file
# stopped compiling the moment a newer compiler was used -- which building for Android is.
#
# Relaxed here rather than target-wide on purpose: -Wno-error=int-conversion across the whole VM
# would hide the next genuine pointer/integer mistake in our own code, and this file's version is
# upstream's and unchanged.
#
# TODO: replace with ten one-line wrappers of the right shape, which removes the undefined
# behaviour instead of tolerating it. Left out of A2 because this file is compiled for iOS and
# macOS too, and A2 has no business changing what they build.
set_source_files_properties(src/main/native/org_robovm_rt_GC.c PROPERTIES COMPILE_OPTIONS
  "-Wno-error=incompatible-function-pointer-types;-Wno-error=int-conversion")

if(DARWIN)
  add_definitions(-DNOUNCRYPT)
  set(OBJC_SRC src/main/native/darwin.m)
  set(SRC ${SRC} ${OBJC_SRC})
  set(OBJC_FLAGS "-x objective-c")
  if(IOS)
    set(OBJC_FLAGS "${OBJC_FLAGS} -fobjc-abi-version=2")
  endif()
  # disable errors on clang clang-1500.3.9.4
  set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Wno-error=incompatible-function-pointer-types -Wno-error=int-conversion")
  set_property(SOURCE ${OBJC_SRC} PROPERTY LANGUAGE C)
  set_property(SOURCE ${OBJC_SRC} PROPERTY COMPILE_FLAGS ${OBJC_FLAGS})
endif()

add_library(robovm-rt-core OBJECT ${SRC})
add_dependencies(robovm-rt-core extgc)
set(ROBOVM_RT_CORE_OBJECTS $<TARGET_OBJECTS:robovm-rt-core> PARENT_SCOPE)

