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
package org.apache.johnzon.jsonb;

import org.apache.johnzon.jsonb.converter.JohnzonJsonbAdapter;
import org.apache.johnzon.jsonb.converter.JsonbDateConverter;
import org.apache.johnzon.jsonb.converter.JsonbLocalDateConverter;
import org.apache.johnzon.jsonb.converter.JsonbLocalDateTimeConverter;
import org.apache.johnzon.jsonb.converter.JsonbNumberConverter;
import org.apache.johnzon.jsonb.converter.JsonbValueConverter;
import org.apache.johnzon.jsonb.converter.JsonbZonedDateTimeConverter;
import org.apache.johnzon.jsonb.spi.JohnzonAdapterFactory;
import org.apache.johnzon.mapper.Adapter;
import org.apache.johnzon.mapper.access.AccessMode;
import org.apache.johnzon.mapper.access.FieldAccessMode;
import org.apache.johnzon.mapper.access.FieldAndMethodAccessMode;
import org.apache.johnzon.mapper.access.MethodAccessMode;
import org.apache.johnzon.mapper.internal.AdapterKey;
import org.apache.johnzon.mapper.internal.ConverterAdapter;

import javax.json.bind.JsonbException;
import javax.json.bind.adapter.JsonbAdapter;
import javax.json.bind.annotation.JsonbCreator;
import javax.json.bind.annotation.JsonbDateFormat;
import javax.json.bind.annotation.JsonbNillable;
import javax.json.bind.annotation.JsonbNumberFormat;
import javax.json.bind.annotation.JsonbProperty;
import javax.json.bind.annotation.JsonbPropertyOrder;
import javax.json.bind.annotation.JsonbTransient;
import javax.json.bind.annotation.JsonbTypeAdapter;
import javax.json.bind.config.PropertyNamingStrategy;
import javax.json.bind.config.PropertyOrderStrategy;
import javax.json.bind.config.PropertyVisibilityStrategy;
import java.io.Closeable;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.Optional.ofNullable;
import static org.apache.johnzon.mapper.reflection.Converters.matches;

public class JsonbAccessMode implements AccessMode, Closeable {
    private final PropertyNamingStrategy naming;
    private final String order;
    private final PropertyVisibilityStrategy visibility;
    private final FieldAndMethodAccessMode delegate;
    private final boolean caseSensitive;
    private final Map<AdapterKey, Adapter<?, ?>> defaultConverters;
    private final JohnzonAdapterFactory factory;
    private final Collection<JohnzonAdapterFactory.Instance<?>> toRelease = new ArrayList<>();

    public JsonbAccessMode(final PropertyNamingStrategy propertyNamingStrategy, final String orderValue,
                           final PropertyVisibilityStrategy visibilityStrategy, final boolean caseSensitive,
                           final Map<AdapterKey, Adapter<?, ?>> defaultConverters, final JohnzonAdapterFactory factory) {
        this.naming = propertyNamingStrategy;
        this.order = orderValue;
        this.visibility = visibilityStrategy;
        this.caseSensitive = caseSensitive;
        this.delegate = new FieldAndMethodAccessMode(true, true);
        this.defaultConverters = defaultConverters;
        this.factory = factory;
    }

    @Override
    public Comparator<String> fieldComparator(final Class<?> clazz) {
        final Comparator<String> orderComparator = orderComparator(clazz);
        return caseSensitive ? orderComparator : ((o1, o2) -> o1.equalsIgnoreCase(o2) ? 0 : orderComparator.compare(o1, o2));
    }

    @Override
    public Factory findFactory(final Class<?> clazz) {
        Constructor<?> constructor = null;
        Method factory = null;
        for (final Constructor<?> c : clazz.getConstructors()) {
            if (c.isAnnotationPresent(JsonbCreator.class)) {
                if (constructor != null) {
                    throw new IllegalArgumentException("Only one constructor or method can have @JsonbCreator");
                }
                constructor = c;
            }
        }
        for (final Method m : clazz.getMethods()) {
            final int modifiers = m.getModifiers();
            if (Modifier.isStatic(modifiers) && Modifier.isPublic(modifiers) && m.isAnnotationPresent(JsonbCreator.class)) {
                if (constructor != null || factory != null) {
                    throw new IllegalArgumentException("Only one constructor or method can have @JsonbCreator");
                }
                factory = m;
            }
        }
        final Constructor<?> finalConstructor = constructor;
        final Method finalFactory = factory;
        final Type[] types;
        final String[] params;
        final Adapter<?, ?>[] converters;
        final Adapter<?, ?>[] itemConverters;
        if (finalConstructor != null || finalFactory != null) {
            types = finalConstructor != null ? finalConstructor.getGenericParameterTypes() : finalFactory.getGenericParameterTypes();
            params = new String[types.length];
            converters = new Adapter<?, ?>[types.length];
            itemConverters = new Adapter<?, ?>[types.length];
            int i = 0;
            for (final Parameter parameter : (finalConstructor == null ? finalFactory : finalConstructor).getParameters()) {
                final JsonbProperty property = parameter.getAnnotation(JsonbProperty.class);
                params[i] = property != null ? property.value() : parameter.getName();

                final JsonbTypeAdapter adapter = parameter.getAnnotation(JsonbTypeAdapter.class);
                final JsonbDateFormat dateFormat = parameter.getAnnotation(JsonbDateFormat.class);
                final JsonbNumberFormat numberFormat = parameter.getAnnotation(JsonbNumberFormat.class);
                if (adapter == null && dateFormat == null && numberFormat == null) {
                    converters[i] = defaultConverters.get(parameter.getType());
                    itemConverters[i] = null;
                } else {
                    validateAnnotations(parameter, adapter, dateFormat, numberFormat);

                    try {
                        final Adapter converter = toConverter(parameter.getType(), adapter, dateFormat, numberFormat);
                        if (matches(parameter.getParameterizedType(), converter)) {
                            converters[i] = converter;
                            itemConverters[i] = null;
                        } else {
                            converters[i] = null;
                            itemConverters[i] = converter;
                        }
                    } catch (final InstantiationException | IllegalAccessException e) {
                        throw new IllegalArgumentException(e);
                    }

                }
                i++;
            }
        } else {
            types = null;
            params = null;
            converters = null;
            itemConverters = null;
        }

        return constructor == null && factory == null ? delegate.findFactory(clazz) : (
            constructor != null ?
                new Factory() {
                    @Override
                    public Object create(final Object[] params) {
                        try {
                            return finalConstructor.newInstance(params);
                        } catch (final InstantiationException | IllegalAccessException e) {
                            throw new IllegalStateException(e);
                        } catch (final InvocationTargetException e) {
                            throw new IllegalStateException(e.getCause());
                        }
                    }

                    @Override
                    public Type[] getParameterTypes() {
                        return types;
                    }

                    @Override
                    public String[] getParameterNames() {
                        return params;
                    }

                    @Override
                    public Adapter<?, ?>[] getParameterConverter() {
                        return converters;
                    }

                    @Override
                    public Adapter<?, ?>[] getParameterItemConverter() {
                        return itemConverters;
                    }
                } :
                new Factory() {
                    @Override
                    public Object create(final Object[] params) {
                        try {
                            final Object invoke = finalFactory.invoke(null, params);
                            if (!clazz.isInstance(invoke)) {
                                throw new IllegalArgumentException(invoke + " is not a " + clazz.getName());
                            }
                            return invoke;
                        } catch (final IllegalAccessException e) {
                            throw new IllegalStateException(e);
                        } catch (final InvocationTargetException e) {
                            throw new IllegalStateException(e.getCause());
                        }
                    }

                    @Override
                    public Type[] getParameterTypes() {
                        return types;
                    }

                    @Override
                    public String[] getParameterNames() {
                        return params;
                    }

                    @Override
                    public Adapter<?, ?>[] getParameterConverter() {
                        return converters;
                    }

                    @Override
                    public Adapter<?, ?>[] getParameterItemConverter() {
                        return itemConverters;
                    }
                });
    }

    private void validateAnnotations(final Object parameter,
                                     final JsonbTypeAdapter adapter, final JsonbDateFormat dateFormat,
                                     final JsonbNumberFormat numberFormat) {
        int notNull = adapter != null ? 1 : 0;
        notNull += dateFormat != null ? 1 : 0;
        notNull += numberFormat != null ? 1 : 0;
        if (notNull > 1) {
            throw new IllegalArgumentException("Conflicting @JsonbXXX on " + parameter);
        }
    }

    private Adapter<?, ?> toConverter(final Type type,
                                      final JsonbTypeAdapter adapter, final JsonbDateFormat dateFormat,
                                      final JsonbNumberFormat numberFormat) throws InstantiationException, IllegalAccessException {
        final Adapter converter;
        if (adapter != null) {
            final Class<? extends JsonbAdapter> value = adapter.value();
            final ParameterizedType pt = ParameterizedType.class.cast(
                Stream.of(value.getGenericInterfaces())
                    .filter(i -> ParameterizedType.class.isInstance(i) && ParameterizedType.class.cast(i).getRawType() == JsonbAdapter.class).findFirst().orElse(null));
            if (pt == null) {
                throw new IllegalArgumentException(value + " doesn't implement JsonbAdapter");
            }
            final Type[] args = pt.getActualTypeArguments();
            final JohnzonAdapterFactory.Instance<? extends JsonbAdapter> instance = newAdapter(value);
            toRelease.add(instance);
            converter = new JohnzonJsonbAdapter(instance.getValue());
        } else if (dateFormat != null) { // TODO: support lists, LocalDate?
            if (Date.class == type) {
                converter = new ConverterAdapter<>(new JsonbDateConverter(dateFormat));
            } else if (LocalDateTime.class == type) {
                converter = new ConverterAdapter<>(new JsonbLocalDateTimeConverter(dateFormat));
            } else if (LocalDate.class == type) {
                converter = new ConverterAdapter<>(new JsonbLocalDateConverter(dateFormat));
            } else if (ZonedDateTime.class == type) {
                converter = new ConverterAdapter<>(new JsonbZonedDateTimeConverter(dateFormat));
            } else {
                throw new IllegalArgumentException(type + " not a supported date type");
            }
        } else if (numberFormat != null) {  // TODO: support lists?
            converter = new ConverterAdapter<>(new JsonbNumberConverter(numberFormat));
        } else {
            converter = new ConverterAdapter<>(new JsonbValueConverter());
        }
        return converter;
    }

    private JohnzonAdapterFactory.Instance<? extends JsonbAdapter> newAdapter(final Class<? extends JsonbAdapter> value) {
        return factory.create(value);
    }

    @Override
    public Map<String, Reader> findReaders(final Class<?> clazz) {
        final Map<String, Reader> readers = delegate.findReaders(clazz);

        final Comparator<String> keyComparator = fieldComparator(clazz);
        final Map<String, Reader> result = keyComparator == null ? new HashMap<>() : new TreeMap<>(keyComparator);
        for (final Map.Entry<String, Reader> entry : readers.entrySet()) {
            final Reader initialReader = entry.getValue();
            if (isTransient(initialReader, visibility)) {
                continue;
            }

            final Reader finalReader;
            if (FieldAndMethodAccessMode.CompositeDecoratedType.class.isInstance(initialReader)) { // unwrap to use the right reader
                final FieldAndMethodAccessMode.CompositeDecoratedType decoratedType = FieldAndMethodAccessMode.CompositeDecoratedType.class.cast(initialReader);
                final DecoratedType type2 = decoratedType.getType2();
                if (MethodAccessMode.MethodReader.class.isInstance(type2)) {
                    finalReader = Reader.class.cast(type2);
                } else {
                    finalReader = initialReader;
                }
            } else {
                finalReader = initialReader;
            }

            // we are visible
            final JsonbProperty property = initialReader.getAnnotation(JsonbProperty.class);
            final JsonbNillable nillable = initialReader.getClassOrPackageAnnotation(JsonbNillable.class);
            final boolean isNillable = nillable != null || (property != null && property.nillable());
            final JsonbTypeAdapter adapter = initialReader.getAnnotation(JsonbTypeAdapter.class);
            final JsonbDateFormat dateFormat = initialReader.getAnnotation(JsonbDateFormat.class);
            final JsonbNumberFormat numberFormat = initialReader.getAnnotation(JsonbNumberFormat.class);
            validateAnnotations(initialReader, adapter, dateFormat, numberFormat);

            final Adapter<?, ?> converter;
            try {
                converter = adapter == null && dateFormat == null && numberFormat == null ?
                    defaultConverters.get(new AdapterKey(initialReader.getType(), String.class)) :
                    toConverter(initialReader.getType(), adapter, dateFormat, numberFormat);
            } catch (final InstantiationException | IllegalAccessException e) {
                throw new IllegalArgumentException(e);
            }

            // handle optionals since mapper is still only java 7
            final Type type;
            final Function<Object, Object> reader;
            if (isOptional(finalReader)) {
                type = ParameterizedType.class.cast(finalReader.getType()).getActualTypeArguments()[0];
                reader = i -> ofNullable(finalReader.read(i)).map(o -> Optional.class.cast(o).orElse(null)).orElse(null);
            } else if (OptionalInt.class == finalReader.getType()) {
                type = int.class;
                reader = i -> OptionalInt.class.cast(finalReader.read(i)).orElse(0);
            } else if (OptionalLong.class == finalReader.getType()) {
                type = long.class;
                reader = i -> OptionalLong.class.cast(finalReader.read(i)).orElse(0);
            } else if (OptionalDouble.class == finalReader.getType()) {
                type = double.class;
                reader = i -> OptionalDouble.class.cast(finalReader.read(i)).orElse(0);
            } else {
                type = finalReader.getType();
                reader = finalReader::read;
            }

            final String key = property == null || property.value().isEmpty() ? naming.translateName(entry.getKey()) : property.value();
            if (result.put(key, new Reader() {
                @Override
                public Object read(final Object instance) {
                    return reader.apply(instance);
                }

                @Override
                public Type getType() {
                    return type;
                }

                @Override
                public <T extends Annotation> T getAnnotation(final Class<T> clazz) {
                    return finalReader.getAnnotation(clazz);
                }

                @Override
                public <T extends Annotation> T getClassOrPackageAnnotation(final Class<T> clazz) {
                    return finalReader.getClassOrPackageAnnotation(clazz);
                }

                @Override
                public Adapter<?, ?> findConverter() {
                    return converter;
                }

                @Override
                public boolean isNillable() {
                    return isNillable;
                }
            }) != null) {
                throw new JsonbException("Ambiguous field " + key);
            }
        }
        return result;
    }

    @Override
    public Map<String, Writer> findWriters(final Class<?> clazz) {
        final Map<String, Writer> writers = delegate.findWriters(clazz);

        final Comparator<String> keyComparator = fieldComparator(clazz);
        final Map<String, Writer> result = keyComparator == null ? new HashMap<>() : new TreeMap<>(keyComparator);
        for (final Map.Entry<String, Writer> entry : writers.entrySet()) {
            Writer initialWriter = entry.getValue();
            if (isTransient(initialWriter, visibility)) {
                continue;
            }

            final Writer finalWriter;
            if (FieldAndMethodAccessMode.CompositeDecoratedType.class.isInstance(initialWriter)) { // unwrap to use the right reader
                final FieldAndMethodAccessMode.CompositeDecoratedType decoratedType = FieldAndMethodAccessMode.CompositeDecoratedType.class.cast(initialWriter);
                final DecoratedType type2 = decoratedType.getType2();
                if (MethodAccessMode.MethodWriter.class.isInstance(type2)) {
                    finalWriter = Writer.class.cast(type2);
                } else {
                    finalWriter = initialWriter;
                }
            } else {
                finalWriter = initialWriter;
            }

            // we are visible
            final JsonbProperty property = initialWriter.getAnnotation(JsonbProperty.class);
            final JsonbNillable nillable = initialWriter.getClassOrPackageAnnotation(JsonbNillable.class);
            final boolean isNillable = nillable != null || (property != null && property.nillable());
            final JsonbTypeAdapter adapter = initialWriter.getAnnotation(JsonbTypeAdapter.class);
            final JsonbDateFormat dateFormat = initialWriter.getAnnotation(JsonbDateFormat.class);
            final JsonbNumberFormat numberFormat = initialWriter.getAnnotation(JsonbNumberFormat.class);
            validateAnnotations(initialWriter, adapter, dateFormat, numberFormat);

            final Adapter<?, ?> converter;
            try {
                converter = adapter == null && dateFormat == null && numberFormat == null ?
                    defaultConverters.get(new AdapterKey(initialWriter.getType(), String.class)) :
                    toConverter(initialWriter.getType(), adapter, dateFormat, numberFormat);
            } catch (final InstantiationException | IllegalAccessException e) {
                throw new IllegalArgumentException(e);
            }

            // handle optionals since mapper is still only java 7
            final Type type;
            final BiConsumer<Object, Object> writer;
            if (isOptional(initialWriter)) {
                type = ParameterizedType.class.cast(initialWriter.getType()).getActualTypeArguments()[0];
                writer = (i, val) -> finalWriter.write(i, Optional.ofNullable(val));
            } else if (OptionalInt.class == initialWriter.getType()) {
                type = int.class;
                writer = (i, val) -> finalWriter.write(i, OptionalInt.of(Number.class.cast(val).intValue()));
            } else if (OptionalLong.class == initialWriter.getType()) {
                type = long.class;
                writer = (i, val) -> finalWriter.write(i, OptionalLong.of(Number.class.cast(val).longValue()));
            } else if (OptionalDouble.class == initialWriter.getType()) {
                type = double.class;
                writer = (i, val) -> finalWriter.write(i, OptionalDouble.of(Number.class.cast(val).doubleValue()));
            } else {
                type = initialWriter.getType();
                writer = finalWriter::write;
            }

            final String key = property == null || property.value().isEmpty() ? naming.translateName(entry.getKey()) : property.value();
            if (result.put(key, new Writer() {
                @Override
                public void write(final Object instance, final Object val) {
                    writer.accept(instance, val);
                }

                @Override
                public Type getType() {
                    return type;
                }

                @Override
                public <T extends Annotation> T getAnnotation(final Class<T> clazz) {
                    return initialWriter.getAnnotation(clazz);
                }

                @Override
                public <T extends Annotation> T getClassOrPackageAnnotation(final Class<T> clazz) {
                    return initialWriter.getClassOrPackageAnnotation(clazz);
                }

                @Override
                public Adapter<?, ?> findConverter() {
                    return converter;
                }

                @Override
                public boolean isNillable() {
                    return isNillable;
                }
            }) != null) {
                throw new JsonbException("Ambiguous field " + key);
            }
        }
        return result;
    }

    private boolean isOptional(final DecoratedType value) {
        return ParameterizedType.class.isInstance(value.getType()) && Optional.class == ParameterizedType.class.cast(value.getType()).getRawType();
    }

    private boolean isTransient(final DecoratedType dt, final PropertyVisibilityStrategy visibility) {
        return shouldSkip(visibility, dt) &&
            (!FieldAndMethodAccessMode.CompositeDecoratedType.class.isInstance(dt) ||
                !Stream.of(FieldAndMethodAccessMode.CompositeDecoratedType.class.cast(dt).getType1(), FieldAndMethodAccessMode.CompositeDecoratedType.class.cast(dt).getType2())
                    .map(t -> shouldSkip(visibility, t))
                    .filter(a -> a)
                    .findAny()
                    .isPresent());
    }

    private boolean shouldSkip(final PropertyVisibilityStrategy visibility, final DecoratedType t) {
        return t.getAnnotation(JsonbTransient.class) != null ||
            (FieldAccessMode.FieldDecoratedType.class.isInstance(t) && !visibility.isVisible(FieldAccessMode.FieldDecoratedType.class.cast(t).getField())) ||
            (MethodAccessMode.MethodDecoratedType.class.isInstance(t) && !visibility.isVisible(MethodAccessMode.MethodDecoratedType.class.cast(t).getMethod()));
    }

    private Comparator<String> orderComparator(final Class<?> clazz) {
        final Comparator<String> keyComparator;
        final JsonbPropertyOrder orderAnnotation = clazz.getAnnotation(JsonbPropertyOrder.class);
        if (orderAnnotation != null) {
            final List<String> indexed = new ArrayList<>(asList(orderAnnotation.value()));
            keyComparator = (o1, o2) -> {
                final int i1 = indexed.indexOf(o1);
                final int i2 = indexed.indexOf(o2);
                if (i1 < 0) {
                    return 1;
                }
                return i1 - i2;
            };
        } else if (order != null) {
            switch (order) {
                case PropertyOrderStrategy.ANY:
                    keyComparator = null;
                    break;
                case PropertyOrderStrategy.LEXICOGRAPHICAL:
                    keyComparator = String::compareTo;
                    break;
                case PropertyOrderStrategy.REVERSE:
                    keyComparator = (o1, o2) -> o2.compareTo(o1);
                    break;
                default:
                    keyComparator = null;
            }
        } else {
            keyComparator = null;
        }
        return keyComparator;
    }

    @Override
    public void close() throws IOException {
        toRelease.forEach(JohnzonAdapterFactory.Instance::release);
        toRelease.clear();
    }
}
