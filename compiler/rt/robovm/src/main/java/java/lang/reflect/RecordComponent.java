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
package java.lang.reflect;

import java.lang.annotation.Annotation;

/**
 * A {@code RecordComponent} provides information about, and dynamic access to, a
 * component of a record class.
 *
 * RoboVM note: the VM does not keep the {@code Record} class file attribute. Record components
 * are derived from the record's private instance fields (which javac declares in component order)
 * and their accessor methods. Annotations are taken from the accessor method if present, from
 * the field otherwise.
 */
public final class RecordComponent implements AnnotatedElement {
    private final Class<?> declaringRecord;
    private final Field field;
    private final Method accessor;

    /** RoboVM internal: created by {@code Class.getRecordComponents()}. */
    public RecordComponent(Class<?> declaringRecord, Field field, Method accessor) {
        this.declaringRecord = declaringRecord;
        this.field = field;
        this.accessor = accessor;
    }

    public String getName() {
        return field.getName();
    }

    public Class<?> getType() {
        return field.getType();
    }

    public String getGenericSignature() {
        return null;
    }

    public Type getGenericType() {
        return field.getGenericType();
    }

    public Method getAccessor() {
        return accessor;
    }

    public Class<?> getDeclaringRecord() {
        return declaringRecord;
    }

    private AnnotatedElement annotationSource() {
        return accessor != null ? accessor : field;
    }

    @Override
    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        return annotationSource().getAnnotation(annotationClass);
    }

    @Override
    public Annotation[] getAnnotations() {
        return annotationSource().getAnnotations();
    }

    @Override
    public Annotation[] getDeclaredAnnotations() {
        return annotationSource().getDeclaredAnnotations();
    }

    @Override
    public boolean isAnnotationPresent(Class<? extends Annotation> annotationClass) {
        return annotationSource().isAnnotationPresent(annotationClass);
    }

    @Override
    public String toString() {
        return getType().getTypeName() + " " + getName();
    }
}
