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
package org.apache.johnzon.mapper;

import org.apache.johnzon.mapper.access.AccessMode;
import org.apache.johnzon.mapper.access.BaseAccessMode;
import org.apache.johnzon.mapper.access.FieldAccessMode;
import org.apache.johnzon.mapper.access.FieldAndMethodAccessMode;
import org.apache.johnzon.mapper.access.MethodAccessMode;
import org.apache.johnzon.mapper.converter.BigDecimalConverter;
import org.apache.johnzon.mapper.converter.BigIntegerConverter;
import org.apache.johnzon.mapper.converter.BooleanConverter;
import org.apache.johnzon.mapper.converter.ByteConverter;
import org.apache.johnzon.mapper.converter.CachedDelegateConverter;
import org.apache.johnzon.mapper.converter.CharacterConverter;
import org.apache.johnzon.mapper.converter.ClassConverter;
import org.apache.johnzon.mapper.converter.DateConverter;
import org.apache.johnzon.mapper.converter.DoubleConverter;
import org.apache.johnzon.mapper.converter.FloatConverter;
import org.apache.johnzon.mapper.converter.IntegerConverter;
import org.apache.johnzon.mapper.converter.LongConverter;
import org.apache.johnzon.mapper.converter.ShortConverter;
import org.apache.johnzon.mapper.converter.StringConverter;

import javax.json.JsonReaderFactory;
import javax.json.spi.JsonProvider;
import javax.json.stream.JsonGenerator;
import javax.json.stream.JsonGeneratorFactory;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class MapperBuilder {
    private static final Map<Class<?>, Converter<?>> DEFAULT_CONVERTERS = new HashMap<Class<?>, Converter<?>>();

    static {
        //DEFAULT_CONVERTERS.put(Date.class, new DateConverter("yyyy-MM-dd'T'HH:mm:ssZ")); // ISO8601 long RFC822 zone
        DEFAULT_CONVERTERS.put(Date.class, new DateConverter("yyyyMMddHHmmssZ")); // ISO8601 short
        DEFAULT_CONVERTERS.put(Class.class, new ClassConverter());
        DEFAULT_CONVERTERS.put(String.class, new StringConverter());
        DEFAULT_CONVERTERS.put(BigDecimal.class, new BigDecimalConverter());
        DEFAULT_CONVERTERS.put(BigInteger.class, new BigIntegerConverter());
        DEFAULT_CONVERTERS.put(Byte.class, new CachedDelegateConverter<Byte>(new ByteConverter()));
        DEFAULT_CONVERTERS.put(Character.class, new CharacterConverter());
        DEFAULT_CONVERTERS.put(Double.class, new DoubleConverter());
        DEFAULT_CONVERTERS.put(Float.class, new FloatConverter());
        DEFAULT_CONVERTERS.put(Integer.class, new IntegerConverter());
        DEFAULT_CONVERTERS.put(Long.class, new LongConverter());
        DEFAULT_CONVERTERS.put(Short.class, new ShortConverter());
        DEFAULT_CONVERTERS.put(Boolean.class, new CachedDelegateConverter<Boolean>(new BooleanConverter()));
        DEFAULT_CONVERTERS.put(byte.class, DEFAULT_CONVERTERS.get(Byte.class));
        DEFAULT_CONVERTERS.put(char.class, new CharacterConverter());
        DEFAULT_CONVERTERS.put(double.class, DEFAULT_CONVERTERS.get(Double.class));
        DEFAULT_CONVERTERS.put(float.class, DEFAULT_CONVERTERS.get(Float.class));
        DEFAULT_CONVERTERS.put(int.class, DEFAULT_CONVERTERS.get(Integer.class));
        DEFAULT_CONVERTERS.put(long.class, DEFAULT_CONVERTERS.get(Long.class));
        DEFAULT_CONVERTERS.put(short.class, DEFAULT_CONVERTERS.get(Short.class));
        DEFAULT_CONVERTERS.put(boolean.class, DEFAULT_CONVERTERS.get(Boolean.class));
    }

    private JsonReaderFactory readerFactory;
    private JsonGeneratorFactory generatorFactory;
    private boolean doCloseOnStreams = false;
    private boolean supportHiddenAccess = true;
    private int version = -1;
    private int maxSize = -1;
    private int bufferSize = -1;
    private String bufferStrategy;
    private Comparator<String> attributeOrder = null;
    private boolean skipNull = true;
    private boolean skipEmptyArray = false;
    private boolean supportsComments = false;
    protected boolean pretty;
    private AccessMode accessMode;
    private boolean treatByteArrayAsBase64;
    private boolean treatByteArrayAsBase64URL;
    private final Map<Type, Converter<?>> converters = new HashMap<Type, Converter<?>>(DEFAULT_CONVERTERS);
    private boolean supportConstructors;
    private Charset encoding = Charset.forName(System.getProperty("johnzon.mapper.encoding", "UTF-8"));
    private boolean useGetterForCollections;
    private String accessModeName;

    public Mapper build() {
        if (readerFactory == null || generatorFactory == null) {
            final JsonProvider provider = JsonProvider.provider();
            final Map<String, Object> config = new HashMap<String, Object>();
            if (bufferStrategy != null) {
                config.put("org.apache.johnzon.buffer-strategy", bufferStrategy);
            }
            if (pretty) {
                config.put(JsonGenerator.PRETTY_PRINTING, true);
            }

            if (generatorFactory == null) {
                generatorFactory = provider.createGeneratorFactory(config);
            }

            config.remove(JsonGenerator.PRETTY_PRINTING); // doesnt mean anything anymore for reader
            if (supportsComments) {
                config.put("org.apache.johnzon.supports-comments", "true");
            }
            if (maxSize > 0) {
                config.put("org.apache.johnzon.max-string-length", maxSize);
            }
            if (bufferSize > 0) {
                config.put("org.apache.johnzon.default-char-buffer", bufferSize);
            }
            if (readerFactory == null) {
                readerFactory = provider.createReaderFactory(config);
            }
        }

        if (accessMode == null) {
            if ("field".equalsIgnoreCase(accessModeName)) {
                this.accessMode = new FieldAccessMode(supportConstructors, supportHiddenAccess);
            } else if ("method".equalsIgnoreCase(accessModeName)) {
                this.accessMode = new MethodAccessMode(supportConstructors, supportHiddenAccess, true);
            } else if ("strict-method".equalsIgnoreCase(accessModeName)) {
                this.accessMode = new MethodAccessMode(supportConstructors, supportHiddenAccess, false);
            } else if ("both".equalsIgnoreCase(accessModeName)) {
                this.accessMode = new FieldAndMethodAccessMode(supportConstructors, supportHiddenAccess);
            } else {
                this.accessMode = new MethodAccessMode(supportConstructors, supportHiddenAccess, useGetterForCollections);
            }
        }

        return new Mapper(
                readerFactory, generatorFactory,
                doCloseOnStreams,
                converters,
                version,
                attributeOrder,
                skipNull, skipEmptyArray,
                accessMode,
                treatByteArrayAsBase64, treatByteArrayAsBase64URL,
                encoding);
    }

    public MapperBuilder setIgnoreFieldsForType(final Class<?> type, final String... fields) {
        if (BaseAccessMode.class.isInstance(accessMode)) {
            if (fields == null || fields.length == 0) {
                BaseAccessMode.class.cast(accessMode).getFieldsToRemove().remove(type);
            } else {
                BaseAccessMode.class.cast(accessMode).getFieldsToRemove().put(type, fields);
            }
        } else {
            throw new IllegalStateException("AccessMode is not an BaseAccessMode");
        }
        return this;
    }

    public MapperBuilder setSupportGetterForCollections(final boolean useGetterForCollections) {
        this.useGetterForCollections = useGetterForCollections;
        return this;
    }

    public MapperBuilder setSupportsComments(final boolean supportsComments) {
        this.supportsComments = supportsComments;
        return this;
    }

    public MapperBuilder setPretty(final boolean pretty) {
        this.pretty = pretty;
        return this;
    }

    public MapperBuilder setBufferSize(final int bufferSize) {
        this.bufferSize = bufferSize;
        return this;
    }

    public MapperBuilder setBufferStrategy(final String bufferStrategy) {
        this.bufferStrategy = bufferStrategy;
        return this;
    }

    public MapperBuilder setMaxSize(final int size) {
        this.maxSize = size;
        return this;
    }

    public MapperBuilder setAccessMode(final AccessMode mode) {
        this.accessMode = mode;
        return this;
    }

    public MapperBuilder setAccessModeName(final String mode) {
        if (!"field".equalsIgnoreCase(mode) && !"method".equalsIgnoreCase(mode) &&
            !"strict-method".equalsIgnoreCase(mode) && !"both".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("Mode " + mode + " unsupported");
        }
        this.accessModeName = mode;
        return this;
    }

    public MapperBuilder setSupportHiddenAccess(final boolean supportHiddenAccess) {
        this.supportHiddenAccess = supportHiddenAccess;
        return this;
    }

    public MapperBuilder setAttributeOrder(final Comparator<String> attributeOrder) {
        this.attributeOrder = attributeOrder;
        return this;
    }

    public MapperBuilder setReaderFactory(final JsonReaderFactory readerFactory) {
        this.readerFactory = readerFactory;
        return this;
    }

    public MapperBuilder setGeneratorFactory(final JsonGeneratorFactory generatorFactory) {
        this.generatorFactory = generatorFactory;
        return this;
    }

    public MapperBuilder setDoCloseOnStreams(final boolean doCloseOnStreams) {
        this.doCloseOnStreams = doCloseOnStreams;
        return this;
    }

    @Deprecated // use addConverter
    public MapperBuilder addPropertyEditor(final Class<?> clazz, final Converter<?> converter) {
        this.converters.put(clazz, converter);
        return this;
    }

    public MapperBuilder addConverter(final Type clazz, final Converter<?> converter) {
        this.converters.put(clazz, converter);
        return this;
    }

    public MapperBuilder setVersion(final int version) {
        this.version = version;
        return this;
    }
    
    public MapperBuilder setSkipNull(final boolean skipNull) {
        this.skipNull = skipNull;
        return this;
    }
    
    public MapperBuilder setSkipEmptyArray(final boolean skipEmptyArray) {
        this.skipEmptyArray = skipEmptyArray;
        return this;
    }
    
    public MapperBuilder setTreatByteArrayAsBase64(final boolean treatByteArrayAsBase64) {
        this.treatByteArrayAsBase64 = treatByteArrayAsBase64;
        return this;
    }

    public MapperBuilder setTreatByteArrayAsBase64URL(final boolean treatByteArrayAsBase64URL) {
        this.treatByteArrayAsBase64URL = treatByteArrayAsBase64URL;
        return this;
    }

    public MapperBuilder setSupportConstructors(final boolean supportConstructors) {
        this.supportConstructors = supportConstructors;
        return this;
    }

    public MapperBuilder setEncoding(final String encoding) {
        this.encoding = Charset.forName(encoding);
        return this;
    }
}
