/*
 * Copyright (C) 2026 RoboVM AB
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
package java.lang.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/**
 * Bootstrap methods for state-driven implementations of core methods of records.
 *
 * Stub class: like {@code java.lang.invoke.LambdaMetafactory} it only exists so that the
 * bootstrap method reference emitted by javac resolves. The RoboVM compiler replaces
 * all {@code invokedynamic} call sites that use this bootstrap with generated code.
 */
public class ObjectMethods {
    private ObjectMethods() {}

    public static Object bootstrap(MethodHandles.Lookup lookup, String methodName, Object type,
                                   Class<?> recordClass, String names, MethodHandle... getters)
            throws Throwable {
        throw new UnsupportedOperationException("ObjectMethods.bootstrap is desugared by the RoboVM compiler");
    }
}
