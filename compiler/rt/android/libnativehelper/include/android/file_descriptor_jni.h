/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * @addtogroup FileDescriptor File Descriptor
 * @{
 */

/**
 * @file file_descriptor_jni.h
 */

#pragma once

#include <sys/cdefs.h>

#include <jni.h>

// 28.09.2026 (tsb): __ROBOVM__ added to the condition, for A2.
//
// The three declarations below carry __INTRODUCED_IN(31) because on Android these functions come
// out of libnativehelper.so and did not exist before API 31. In this tree they do not come from
// there: file_descriptor_jni.c beside this header implements them, and they are linked in
// statically. The annotation is therefore untrue here, and it was harmless only for as long as
// nothing was compiled for Bionic -- off-Android the first half of this condition erased it.
//
// Compiling for Android turns it back on, and clang then refuses every call from
// JNIPlatformHelp.h with "unavailable: introduced in Android 31" while targeting 26. Widened
// rather than the target raised: raising it to 31 would put the product's floor at Android 12
// for a symbol we supply ourselves.
#if (!defined(__BIONIC__) || defined(__ROBOVM__)) && !defined(__INTRODUCED_IN)
#define __INTRODUCED_IN(x)
#endif

__BEGIN_DECLS

/**
 * Returns a new java.io.FileDescriptor.
 *
 * The FileDescriptor created represents an invalid Unix file descriptor (represented by
 * a file descriptor value of -1).
 *
 * Callers of this method should be aware that it can fail, returning NULL with a pending Java
 * exception.
 *
 * Available since API level 31.
 *
 * \param env a pointer to the JNI Native Interface of the current thread.
 * \return a java.io.FileDescriptor on success, nullptr if insufficient heap memory is available.
 */
jobject AFileDescriptor_create(JNIEnv* env) __INTRODUCED_IN(31);

/**
 * Returns the Unix file descriptor represented by the given java.io.FileDescriptor.
 *
 * A return value of -1 indicates that \a fileDescriptor represents an invalid file descriptor.
 *
 * Aborts the program if \a fileDescriptor is not a java.io.FileDescriptor instance.
 *
 * Available since API level 31.
 *
 * \param env a pointer to the JNI Native Interface of the current thread.
 * \param fileDescriptor a java.io.FileDescriptor instance.
 * \return the Unix file descriptor wrapped by \a fileDescriptor.
 */
int AFileDescriptor_getFd(JNIEnv* env, jobject fileDescriptor) __INTRODUCED_IN(31);

/**
 * Sets the Unix file descriptor represented by the given java.io.FileDescriptor.
 *
 * This function performs no validation of the Unix file descriptor argument, \a fd. Android uses
 * the value -1 to represent an invalid file descriptor, all other values are considered valid.
 * The validity of a file descriptor can be checked with FileDescriptor#valid().
 *
 * Aborts the program if \a fileDescriptor is not a java.io.FileDescriptor instance.
 *
 * Available since API level 31.
 *
 * \param env a pointer to the JNI Native Interface of the current thread.
 * \param fileDescriptor a java.io.FileDescriptor instance.
 * \param fd a Unix file descriptor that \a fileDescriptor will subsequently represent.
 */
void AFileDescriptor_setFd(JNIEnv* env, jobject fileDescriptor, int fd) __INTRODUCED_IN(31);

__END_DECLS

/** @} */
