/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.inlong.sort.iceberg.flink.sink;

import org.apache.flink.table.data.RowData;
import org.apache.flink.table.types.logical.RowType;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.flink.RowDataWrapper;
import org.apache.iceberg.flink.sink.FlinkAppenderFactory;
import org.apache.iceberg.flink.sink.TaskWriterFactory;
import org.apache.iceberg.io.FileAppenderFactory;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.io.PartitionedFanoutWriter;
import org.apache.iceberg.io.TaskWriter;
import org.apache.iceberg.io.UnpartitionedWriter;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.util.ArrayUtil;

import java.util.List;

/**
 * Copy from iceberg-flink:iceberg-flink-1.13:0.13.2
 * Add an option `sink.ignore.changelog` to support insert-only mode without equalityFieldIds.
 */
public class RowDataTaskWriterFactory implements TaskWriterFactory<RowData> {
    private final Table table;
    private final Schema schema;
    private final RowType flinkSchema;
    private final PartitionSpec spec;
    private final FileIO io;
    private final long targetFileSizeBytes;
    private final FileFormat format;
    private final List<Integer> equalityFieldIds;
    private final boolean upsert;
    private final boolean appendMode;
    private final FileAppenderFactory<RowData> appenderFactory;

    private transient OutputFileFactory outputFileFactory;

    public RowDataTaskWriterFactory(Table table,
            RowType flinkSchema,
            long targetFileSizeBytes,
            FileFormat format,
            List<Integer> equalityFieldIds,
            boolean upsert,
            boolean appendMode) {
        this.table = table;
        this.schema = table.schema();
        this.flinkSchema = flinkSchema;
        this.spec = table.spec();
        this.io = table.io();
        this.targetFileSizeBytes = targetFileSizeBytes;
        this.format = format;
        this.equalityFieldIds = equalityFieldIds;
        this.upsert = upsert;
        this.appendMode = appendMode;

        if (equalityFieldIds == null || equalityFieldIds.isEmpty() || appendMode) {
            this.appenderFactory = new FlinkAppenderFactory(schema, flinkSchema, table.properties(), spec);
        } else {
            // TODO provide the ability to customize the equality-delete row schema.
            this.appenderFactory = new FlinkAppenderFactory(schema, flinkSchema, table.properties(), spec,
                    ArrayUtil.toIntArray(equalityFieldIds), schema, null);
        }
    }

    @Override
    public void initialize(int taskId, int attemptId) {
        this.outputFileFactory = OutputFileFactory.builderFor(table, taskId, attemptId).build();
    }

    @Override
    public TaskWriter<RowData> create() {
        Preconditions.checkNotNull(outputFileFactory,
                "The outputFileFactory shouldn't be null if we have invoked the initialize().");

        if (equalityFieldIds == null || equalityFieldIds.isEmpty() || appendMode) {
            // Initialize a task writer to write INSERT only.
            if (spec.isUnpartitioned()) {
                return new UnpartitionedWriter<>(
                        spec, format, appenderFactory, outputFileFactory, io, targetFileSizeBytes);
            } else {
                return new RowDataPartitionedFanoutWriter(spec, format, appenderFactory, outputFileFactory,
                        io, targetFileSizeBytes, schema, flinkSchema);
            }
        } else {
            // Initialize a task writer to write both INSERT and equality DELETE.
            if (spec.isUnpartitioned()) {
                return new UnpartitionedDeltaWriter(spec, format, appenderFactory, outputFileFactory, io,
                        targetFileSizeBytes, schema, flinkSchema, equalityFieldIds, upsert);
            } else {
                return new PartitionedDeltaWriter(spec, format, appenderFactory, outputFileFactory, io,
                        targetFileSizeBytes, schema, flinkSchema, equalityFieldIds, upsert);
            }
        }
    }

    private static class RowDataPartitionedFanoutWriter extends PartitionedFanoutWriter<RowData> {

        private final PartitionKey partitionKey;
        private final RowDataWrapper rowDataWrapper;

        RowDataPartitionedFanoutWriter(
                PartitionSpec spec, FileFormat format, FileAppenderFactory<RowData> appenderFactory,
                OutputFileFactory fileFactory, FileIO io, long targetFileSize, Schema schema,
                RowType flinkSchema) {
            super(spec, format, appenderFactory, fileFactory, io, targetFileSize);
            this.partitionKey = new PartitionKey(spec, schema);
            this.rowDataWrapper = new RowDataWrapper(flinkSchema, schema.asStruct());
        }

        @Override
        protected PartitionKey partition(RowData row) {
            partitionKey.partition(rowDataWrapper.wrap(row));
            return partitionKey;
        }
    }
}
