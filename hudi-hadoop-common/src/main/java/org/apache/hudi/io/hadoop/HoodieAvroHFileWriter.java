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

package org.apache.hudi.io.hadoop;

import org.apache.hudi.avro.HoodieAvroUtils;
import org.apache.hudi.common.bloom.BloomFilter;
import org.apache.hudi.common.engine.TaskContextSupplier;
import org.apache.hudi.common.model.HoodieKey;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.exception.HoodieDuplicateKeyException;
import org.apache.hudi.hadoop.fs.HadoopFSUtils;
import org.apache.hudi.hadoop.fs.HoodieWrapperFileSystem;
import org.apache.hudi.io.storage.HoodieAvroFileWriter;
import org.apache.hudi.io.storage.HoodieAvroHFileReaderImplBase;
import org.apache.hudi.storage.StoragePath;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.IndexedRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.HFile;
import org.apache.hadoop.hbase.io.hfile.HFileContext;
import org.apache.hadoop.hbase.io.hfile.HFileContextBuilder;
import org.apache.hadoop.io.Writable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.hudi.common.util.StringUtils.EMPTY_STRING;
import static org.apache.hudi.common.util.StringUtils.getUTF8Bytes;

/**
 * HoodieHFileWriter writes IndexedRecords into an HFile. The record's key is used as the key and the
 * AVRO encoded record bytes are saved as the value.
 * <p>
 * Limitations (compared to columnar formats like Parquet or ORC):
 * 1. Records should be added in order of keys
 * 2. There are no column stats
 */
public class HoodieAvroHFileWriter
    implements HoodieAvroFileWriter {
  private static final AtomicLong RECORD_INDEX_COUNT = new AtomicLong(1);
  private final Path file;
  private final HoodieHFileConfig hfileConfig;
  private final boolean isWrapperFileSystem;
  private final Option<HoodieWrapperFileSystem> wrapperFs;
  private final long maxFileSize;
  private final String instantTime;
  private final TaskContextSupplier taskContextSupplier;
  private final boolean populateMetaFields;
  private final Option<Schema.Field> keyFieldSchema;
  private HFile.Writer writer;
  private String minRecordKey;
  private String maxRecordKey;
  private String prevRecordKey;

  // This is private in CacheConfig so have been copied here.
  private static final String DROP_BEHIND_CACHE_COMPACTION_KEY = "hbase.hfile.drop.behind.compaction";

  public HoodieAvroHFileWriter(String instantTime, StoragePath file, HoodieHFileConfig hfileConfig, Schema schema,
                               TaskContextSupplier taskContextSupplier, boolean populateMetaFields) throws IOException {

    Configuration conf = HadoopFSUtils.registerFileSystem(file, hfileConfig.getHadoopConf());
    this.file = HoodieWrapperFileSystem.convertToHoodiePath(file, conf);
    FileSystem fs = this.file.getFileSystem(conf);
    this.isWrapperFileSystem = fs instanceof HoodieWrapperFileSystem;
    this.wrapperFs = this.isWrapperFileSystem ? Option.of((HoodieWrapperFileSystem) fs) : Option.empty();
    this.hfileConfig = hfileConfig;
    this.keyFieldSchema = Option.ofNullable(schema.getField(hfileConfig.getKeyFieldName()));

    // TODO - compute this compression ratio dynamically by looking at the bytes written to the
    // stream and the actual file size reported by HDFS
    // this.maxFileSize = hfileConfig.getMaxFileSize()
    //    + Math.round(hfileConfig.getMaxFileSize() * hfileConfig.getCompressionRatio());
    this.maxFileSize = hfileConfig.getMaxFileSize();
    this.instantTime = instantTime;
    this.taskContextSupplier = taskContextSupplier;
    this.populateMetaFields = populateMetaFields;

    HFileContext context = new HFileContextBuilder().withBlockSize(hfileConfig.getBlockSize())
        .withCompression(hfileConfig.getCompressionAlgorithm())
        .withCellComparator(hfileConfig.getHFileComparator())
        .build();

    conf.set(CacheConfig.PREFETCH_BLOCKS_ON_OPEN_KEY,
        String.valueOf(hfileConfig.shouldPrefetchBlocksOnOpen()));
    conf.set(HColumnDescriptor.CACHE_DATA_IN_L1, String.valueOf(hfileConfig.shouldCacheDataInL1()));
    conf.set(DROP_BEHIND_CACHE_COMPACTION_KEY,
        String.valueOf(hfileConfig.shouldDropBehindCacheCompaction()));
    CacheConfig cacheConfig = new CacheConfig(conf);
    this.writer = HFile.getWriterFactory(conf, cacheConfig)
        .withPath(fs, this.file)
        .withFileContext(context)
        .create();

    writer.appendFileInfo(getUTF8Bytes(HoodieAvroHFileReaderImplBase.SCHEMA_KEY),
        getUTF8Bytes(schema.toString()));
    this.prevRecordKey = "";
  }

  @Override
  public void writeAvroWithMetadata(HoodieKey key, IndexedRecord avroRecord) throws IOException {
    if (populateMetaFields) {
      prepRecordWithMetadata(key, avroRecord, instantTime,
          taskContextSupplier.getPartitionIdSupplier().get(), RECORD_INDEX_COUNT.getAndIncrement(), file.getName());
      writeAvro(key.getRecordKey(), avroRecord);
    } else {
      writeAvro(key.getRecordKey(), avroRecord);
    }
  }

  @Override
  public boolean canWrite() {
    return !isWrapperFileSystem || wrapperFs.get().getBytesWritten(file) < maxFileSize;
  }

  @Override
  public void writeAvro(String recordKey, IndexedRecord record) throws IOException {
    if (prevRecordKey.equals(recordKey)) {
      throw new HoodieDuplicateKeyException("Duplicate recordKey " + recordKey + " found while writing to HFile."
          + "Record payload: " + record);
    }
    byte[] value = null;
    boolean isRecordSerialized = false;
    if (keyFieldSchema.isPresent()) {
      GenericRecord keyExcludedRecord = (GenericRecord) record;
      int keyFieldPos = this.keyFieldSchema.get().pos();
      boolean isKeyAvailable = (record.get(keyFieldPos) != null && !(record.get(keyFieldPos).toString().isEmpty()));
      if (isKeyAvailable) {
        Object originalKey = keyExcludedRecord.get(keyFieldPos);
        keyExcludedRecord.put(keyFieldPos, EMPTY_STRING);
        value = HoodieAvroUtils.avroToBytes(keyExcludedRecord);
        keyExcludedRecord.put(keyFieldPos, originalKey);
        isRecordSerialized = true;
      }
    }
    if (!isRecordSerialized) {
      value = HoodieAvroUtils.avroToBytes((GenericRecord) record);
    }

    KeyValue kv = new KeyValue(getUTF8Bytes(recordKey), null, null, value);
    writer.append(kv);

    if (hfileConfig.useBloomFilter()) {
      hfileConfig.getBloomFilter().add(recordKey);
      if (minRecordKey == null) {
        minRecordKey = recordKey;
      }
      maxRecordKey = recordKey;
    }
    prevRecordKey = recordKey;
  }

  @Override
  public void close() throws IOException {
    if (hfileConfig.useBloomFilter()) {
      final BloomFilter bloomFilter = hfileConfig.getBloomFilter();
      if (minRecordKey == null) {
        minRecordKey = "";
      }
      if (maxRecordKey == null) {
        maxRecordKey = "";
      }
      writer.appendFileInfo(getUTF8Bytes(HoodieAvroHFileReaderImplBase.KEY_MIN_RECORD),
          getUTF8Bytes(minRecordKey));
      writer.appendFileInfo(getUTF8Bytes(HoodieAvroHFileReaderImplBase.KEY_MAX_RECORD),
          getUTF8Bytes(maxRecordKey));
      writer.appendFileInfo(getUTF8Bytes(HoodieAvroHFileReaderImplBase.KEY_BLOOM_FILTER_TYPE_CODE),
          getUTF8Bytes(bloomFilter.getBloomFilterTypeCode().toString()));
      writer.appendMetaBlock(HoodieAvroHFileReaderImplBase.KEY_BLOOM_FILTER_META_BLOCK,
          new Writable() {
            @Override
            public void write(DataOutput out) throws IOException {
              out.write(getUTF8Bytes(bloomFilter.serializeToString()));
            }

            @Override
            public void readFields(DataInput in) throws IOException {
            }
          });
    }

    writer.close();
    writer = null;
  }
}
