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
package org.apache.johnzon.jsonb.test;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;

import javax.json.JsonValue;
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.json.bind.JsonbException;

import org.apache.johnzon.jsonb.api.experimental.JsonbExtension;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

public class JsonbRule implements TestRule, Jsonb, JsonbExtension {
    private Jsonb jsonb;

    @Override
    public Statement apply(Statement statement, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                try (final Jsonb jsonb = JsonbBuilder.create()) {
                    JsonbRule.this.jsonb = jsonb;
                    statement.evaluate();
                } finally {
                    JsonbRule.this.jsonb = null;
                }
            }
        };
    }

    @Override
    public <T> T fromJson(final String str, final Class<T> type) throws JsonbException {
        return jsonb.fromJson(str, type);
    }

    @Override
    public <T> T fromJson(final String str, final Type runtimeType) throws JsonbException {
        return jsonb.fromJson(str, runtimeType);
    }

    @Override
    public <T> T fromJson(final Reader reader, final Class<T> type) throws JsonbException {
        return jsonb.fromJson(reader, type);
    }

    @Override
    public <T> T fromJson(final Reader reader, final Type runtimeType) throws JsonbException {
        return jsonb.fromJson(reader, runtimeType);
    }

    @Override
    public <T> T fromJson(final InputStream stream, final Class<T> type) throws JsonbException {
        return jsonb.fromJson(stream, type);
    }

    @Override
    public <T> T fromJson(final InputStream stream, final Type runtimeType) throws JsonbException {
        return jsonb.fromJson(stream, runtimeType);
    }

    @Override
    public String toJson(final Object object) throws JsonbException {
        return jsonb.toJson(object);
    }

    @Override
    public String toJson(final Object object, final Type runtimeType) throws JsonbException {
        return jsonb.toJson(object, runtimeType);
    }

    @Override
    public void toJson(final Object object, final Writer writer) throws JsonbException {
        jsonb.toJson(object, writer);
    }

    @Override
    public void toJson(final Object object, final Type runtimeType, final Writer writer) throws JsonbException {
        jsonb.toJson(object, runtimeType, writer);
    }

    @Override
    public void toJson(final Object object, final OutputStream stream) throws JsonbException {
        jsonb.toJson(object, stream);
    }

    @Override
    public void toJson(final Object object, final Type runtimeType, final OutputStream stream) throws JsonbException {
        jsonb.toJson(object, runtimeType, stream);
    }

    @Override
    public void close() {
        // no-op
    }

    @Override
    public <T> T fromJsonValue(final JsonValue json, final Class<T> type) {
        return JsonbExtension.class.cast(jsonb).fromJsonValue(json, type);
    }

    @Override
    public <T> T fromJsonValue(final JsonValue json, final Type runtimeType) {
        return JsonbExtension.class.cast(jsonb).fromJsonValue(json, runtimeType);
    }

    @Override
    public JsonValue toJsonValue(final Object object) {
        return JsonbExtension.class.cast(jsonb).toJsonValue(object);
    }

    @Override
    public JsonValue toJsonValue(final Object object, final Type runtimeType) {
        return JsonbExtension.class.cast(jsonb).toJsonValue(object, runtimeType);
    }
}
