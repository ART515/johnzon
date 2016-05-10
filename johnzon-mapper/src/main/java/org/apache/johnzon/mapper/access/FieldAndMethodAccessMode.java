/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.johnzon.mapper.access;

import org.apache.johnzon.mapper.Adapter;
import org.apache.johnzon.mapper.ObjectConverter;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

// methods override fields
public class FieldAndMethodAccessMode extends BaseAccessMode {
    private final FieldAccessMode fields;
    private final MethodAccessMode methods;

    public FieldAndMethodAccessMode(final boolean useConstructor, final boolean acceptHiddenConstructor) {
        super(useConstructor, acceptHiddenConstructor);
        this.fields = new FieldAccessMode(useConstructor, acceptHiddenConstructor);
        this.methods = new MethodAccessMode(useConstructor, acceptHiddenConstructor, false);
    }

    @Override
    public Map<String, Reader> doFindReaders(final Class<?> clazz) {
        final Map<String, Reader> readers = new HashMap<String, Reader>(fields.findReaders(clazz));
        for (final Map.Entry<String, Reader> entry : methods.findReaders(clazz).entrySet()) {
            final Reader existing = readers.get(entry.getKey());
            if (existing == null) {
                readers.put(entry.getKey(), entry.getValue());
            } else {
                readers.put(entry.getKey(), new CompositeReader(existing, entry.getValue()));
            }
        }
        return readers;
    }

    @Override
    public Map<String, Writer> doFindWriters(final Class<?> clazz) {
        final Map<String, Writer> writers = new HashMap<String, Writer>(fields.findWriters(clazz));
        for (final Map.Entry<String, Writer> entry : methods.findWriters(clazz).entrySet()) {
            final Writer existing = writers.get(entry.getKey());
            if (existing == null) {
                writers.put(entry.getKey(), entry.getValue());
            } else {
                writers.put(entry.getKey(), new CompositeWriter(existing, entry.getValue()));
            }
        }
        return writers;
    }

    public static abstract class CompositeDecoratedType implements DecoratedType {
        protected final DecoratedType type1;
        private final DecoratedType type2;

        private CompositeDecoratedType(final DecoratedType type1, final DecoratedType type2) {
            this.type1 = type1;
            this.type2 = type2;
        }

        @Override
        public <T extends Annotation> T getClassOrPackageAnnotation(final Class<T> clazz) {
            final T found = type1.getClassOrPackageAnnotation(clazz);
            return found == null ? type2.getClassOrPackageAnnotation(clazz) : found;
        }

        @Override
        public Adapter<?, ?> findConverter() {
            final Adapter<?, ?> converter = type1.findConverter();
            return converter != null ? converter : type2.findConverter();
        }

        @Override
        public <T extends Annotation> T getAnnotation(final Class<T> clazz) {
            final T found = type1.getAnnotation(clazz);
            return found == null ? type2.getAnnotation(clazz) : found;
        }

        @Override
        public Type getType() {
            return type1.getType();
        }

        @Override
        public boolean isNillable() {
            return type1.isNillable() || type2.isNillable();
        }

        public DecoratedType getType1() {
            return type1;
        }

        public DecoratedType getType2() {
            return type2;
        }
    }

    public static final class CompositeReader extends CompositeDecoratedType implements Reader {
        private final Reader reader;

        private CompositeReader(final Reader type1, final DecoratedType type2) {
            super(type1, type2);
            reader = type1;
        }

        @Override
        public Object read(final Object instance) {
            return reader.read(instance);
        }

        @Override
        public ObjectConverter.Writer<?> findObjectConverterWriter() {
            final ObjectConverter.Writer<?> objectConverter = Reader.class.cast(type1).findObjectConverterWriter();
            return objectConverter == null ? reader.findObjectConverterWriter() : objectConverter;
        }
    }

    public static final class CompositeWriter extends CompositeDecoratedType implements Writer {
        private final Writer writer;

        private CompositeWriter(final Writer type1, final DecoratedType type2) {
            super(type1, type2);
            writer = type1;
        }

        @Override
        public void write(final Object instance, final Object value) {
            writer.write(instance, value);
        }

        @Override
        public ObjectConverter.Reader<?> findObjectConverterReader() {
            final ObjectConverter.Reader<?> objectConverter = Writer.class.cast(type1).findObjectConverterReader();
            return objectConverter == null ? writer.findObjectConverterReader() : objectConverter;
        }
    }
}
