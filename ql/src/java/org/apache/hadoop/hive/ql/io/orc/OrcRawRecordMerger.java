/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.io.orc;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.io.AcidOutputFormat;
import org.apache.hadoop.hive.shims.HadoopShims;
import org.apache.orc.OrcUtils;
import org.apache.orc.StripeInformation;
import org.apache.orc.TypeDescription;
import org.apache.orc.impl.AcidStats;
import org.apache.orc.impl.OrcAcidUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.ValidTxnList;
import org.apache.hadoop.hive.ql.io.AcidInputFormat;
import org.apache.hadoop.hive.ql.io.AcidUtils;
import org.apache.hadoop.hive.ql.io.RecordIdentifier;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;

import com.google.common.annotations.VisibleForTesting;

/**
 * Merges a base and a list of delta files together into a single stream of
 * events.
 */
public class OrcRawRecordMerger implements AcidInputFormat.RawReader<OrcStruct>{

  private static final Logger LOG = LoggerFactory.getLogger(OrcRawRecordMerger.class);

  private final Configuration conf;
  private final boolean collapse;
  private final RecordReader baseReader;
  private final ObjectInspector objectInspector;
  private final long offset;
  private final long length;
  private final ValidTxnList validTxnList;
  private final int columns;
  private final ReaderKey prevKey = new ReaderKey();
  // this is the key less than the lowest key we need to process
  private final RecordIdentifier minKey;
  // this is the last key we need to process
  private final RecordIdentifier maxKey;
  // an extra value so that we can return it while reading ahead
  private OrcStruct extraValue;

  /**
   * A RecordIdentifier extended with the current transaction id. This is the
   * key of our merge sort with the originalTransaction, bucket, and rowId
   * ascending and the currentTransaction, statementId descending. This means that if the
   * reader is collapsing events to just the last update, just the first
   * instance of each record is required.
   */
  @VisibleForTesting
  public final static class ReaderKey extends RecordIdentifier{
    private long currentTransactionId;
    /**
     * This is the value from delta file name which may be different from value encode in 
     * {@link RecordIdentifier#getBucketProperty()} in case of Update/Delete.
     * So for Acid 1.0 + multi-stmt txn, if {@code isSameRow() == true}, then it must be an update
     * or delete event.  For Acid 2.0 + multi-stmt txn, it must be a delete event.
     * No 2 Insert events from can ever agree on {@link RecordIdentifier}
     */
    private int statementId;//sort on this descending, like currentTransactionId

    public ReaderKey() {
      this(-1, -1, -1, -1, 0);
    }

    public ReaderKey(long originalTransaction, int bucket, long rowId,
                     long currentTransactionId) {
      this(originalTransaction, bucket, rowId, currentTransactionId, 0);
    }
    /**
     * @param statementId - set this to 0 if N/A
     */
    public ReaderKey(long originalTransaction, int bucket, long rowId,
                     long currentTransactionId, int statementId) {
      super(originalTransaction, bucket, rowId);
      this.currentTransactionId = currentTransactionId;
      this.statementId = statementId;
    }

    @Override
    public void set(RecordIdentifier other) {
      super.set(other);
      currentTransactionId = ((ReaderKey) other).currentTransactionId;
      statementId = ((ReaderKey) other).statementId;
    }

    public void setValues(long originalTransactionId,
                          int bucket,
                          long rowId,
                          long currentTransactionId,
                          int statementId) {
      setValues(originalTransactionId, bucket, rowId);
      this.currentTransactionId = currentTransactionId;
      this.statementId = statementId;
    }

    @Override
    public boolean equals(Object other) {
      return super.equals(other) &&
          currentTransactionId == ((ReaderKey) other).currentTransactionId
            && statementId == ((ReaderKey) other).statementId//consistent with compareTo()
          ;
    }
    @Override
    public int hashCode() {
      int result = super.hashCode();
      result = 31 * result + (int)(currentTransactionId ^ (currentTransactionId >>> 32));
      result = 31 * result + statementId;
      return result;
    }


    @Override
    public int compareTo(RecordIdentifier other) {
      int sup = compareToInternal(other);
      if (sup == 0) {
        if (other.getClass() == ReaderKey.class) {
          ReaderKey oth = (ReaderKey) other;
          if (currentTransactionId != oth.currentTransactionId) {
            return currentTransactionId < oth.currentTransactionId ? +1 : -1;
          }
          if(statementId != oth.statementId) {
            return statementId < oth.statementId ? +1 : -1;
          }
        } else {
          return -1;
        }
      }
      return sup;
    }

    /**
     * This means 1 txn modified the same row more than once
     */
    private boolean isSameRow(ReaderKey other) {
      return compareRow(other) == 0 && currentTransactionId == other.currentTransactionId;
    }

    long getCurrentTransactionId() {
      return currentTransactionId;
    }

    /**
     * Compare rows without considering the currentTransactionId.
     * @param other the value to compare to
     * @return -1, 0, +1
     */
    int compareRow(RecordIdentifier other) {
      return compareToInternal(other);
    }

    @Override
    public String toString() {
      return "{originalTxn: " + getTransactionId() + ", " +
          bucketToString() + ", row: " + getRowId() + ", currentTxn: " +
          currentTransactionId + ", statementId: "+ statementId + "}";
    }
  }

  /**
   * A reader and the next record from that reader. The code reads ahead so that
   * we can return the lowest ReaderKey from each of the readers. Thus, the
   * next available row is nextRecord and only following records are still in
   * the reader.
   */
  static class ReaderPair {
    OrcStruct nextRecord;
    final Reader reader;
    final RecordReader recordReader;
    final ReaderKey key;
    private final RecordIdentifier minKey;
    private final RecordIdentifier maxKey;
    final int bucket;
    private final int statementId;
    boolean advancedToMinKey = false;

    /**
     * Create a reader that reads from the first key larger than minKey to any
     * keys equal to maxKey.
     * @param key the key to read into
     * @param reader the ORC file reader
     * @param bucket the bucket number for the file
     * @param minKey only return keys larger than minKey if it is non-null
     * @param maxKey only return keys less than or equal to maxKey if it is
     *               non-null
     * @param options options to provide to read the rows.
     * @param statementId id of SQL statement within a transaction
     * @throws IOException
     */
    ReaderPair(ReaderKey key, Reader reader, int bucket,
               RecordIdentifier minKey, RecordIdentifier maxKey,
               ReaderImpl.Options options, int statementId) throws IOException {
      this.reader = reader;
      this.key = key;
      this.minKey = minKey;
      this.maxKey = maxKey;
      this.bucket = bucket;
      // TODO use stripe statistics to jump over stripes
      recordReader = reader.rowsOptions(options);
      this.statementId = statementId;
    }
    RecordReader getRecordReader() {
      return recordReader;
    }
    /**
     * This must be called right after the constructor but not in the constructor to make sure
     * sub-classes are fully initialized before their {@link #next(OrcStruct)} is called
     */
    void advnaceToMinKey() throws IOException {
      advancedToMinKey = true;
      // advance the reader until we reach the minimum key
      do {
        next(nextRecord);
      } while (nextRecord != null &&
          (getMinKey() != null && key.compareRow(getMinKey()) <= 0));
    }

    void next(OrcStruct next) throws IOException {
      assert advancedToMinKey : "advnaceToMinKey() was not called";
      if (getRecordReader().hasNext()) {
        nextRecord = (OrcStruct) getRecordReader().next(next);
        // set the key
        key.setValues(OrcRecordUpdater.getOriginalTransaction(nextRecord),
            OrcRecordUpdater.getBucket(nextRecord),
            OrcRecordUpdater.getRowId(nextRecord),
            OrcRecordUpdater.getCurrentTransaction(nextRecord),
            statementId);

        // if this record is larger than maxKey, we need to stop
        if (getMaxKey() != null && key.compareRow(getMaxKey()) > 0) {
          LOG.debug("key " + key + " > maxkey " + getMaxKey());
          nextRecord = null;
          getRecordReader().close();
        }
      } else {
        nextRecord = null;
        recordReader.close();
      }
    }

    RecordIdentifier getMinKey() {
      return minKey;
    }
    RecordIdentifier getMaxKey() {
      return maxKey;
    }
    int getColumns() {
      return reader.getTypes().get(OrcRecordUpdater.ROW + 1).getSubtypesCount();
    }
  }

  /**
   * A reader that pretends an original base file is a new version base file.
   * It wraps the underlying reader's row with an ACID event object and
   * makes the relevant translations.
   * 
   * Running multiple Insert statements on the same partition (of non acid table) creates files
   * like so: 00000_0, 00000_0_copy1, 00000_0_copy2, etc.  So the OriginalReaderPair must treat all
   * of these files as part of a single logical bucket file.
   * 
   * For Compaction, where each split includes the whole bucket, this means reading over all the
   * files in order to assign ROW__ID.rowid in one sequence for the entire logical bucket.
   *
   * For a read after the table is marked transactional but before it's rewritten into a base/
   * by compaction, each of the original files may be split into many pieces.  For each split we
   * must make sure to include only the relevant part of each delta file.
   * {@link OrcRawRecordMerger#minKey} and {@link OrcRawRecordMerger#maxKey} are computed for each
   * split of the original file and used to filter rows from all the deltas.  The ROW__ID.rowid for
   * the rows of the 'original' file of course, must be assigned from the beginning of logical
   * bucket.
   */
  static final class OriginalReaderPair extends ReaderPair {
    private final Options mergerOptions;
    /**
     * Sum total of all rows in all the files before the 'current' one in {@link #originalFiles} list
     */
    private long rowIdOffset = 0;
    /**
     * See {@link AcidUtils.Directory#getOriginalFiles()}.  This list has a fixed sort order. This
     * is the full list when compacting and empty when doing a simple read.  The later is because we
     * only need to read the current split from 1 file for simple read.
     */
    private final List<HadoopShims.HdfsFileStatusWithId> originalFiles;
    /**
     * index into {@link #originalFiles}
     */
    private int nextFileIndex = 0;
    private long numRowsInCurrentFile = 0;
    private RecordReader originalFileRecordReader = null;
    private final Configuration conf;
    private final Reader.Options options;
    private final RecordIdentifier minKey;//shadow parent minKey to make final
    private final RecordIdentifier maxKey;//shadow parent maxKey to make final

    OriginalReaderPair(ReaderKey key, Reader reader, int bucket,
                       final RecordIdentifier minKey, final RecordIdentifier maxKey,
                       Reader.Options options, Options mergerOptions, Configuration conf,
                       ValidTxnList validTxnList) throws IOException {
      super(key, reader, bucket, minKey, maxKey, options, 0);
      this.mergerOptions = mergerOptions;
      this.conf = conf;
      this.options = options;
      assert mergerOptions.getRootPath() != null : "Since we have original files";
      assert bucket >= 0 : "don't support non-bucketed tables yet";

      RecordIdentifier newMinKey = minKey;
      RecordIdentifier newMaxKey = maxKey;
      if(mergerOptions.isCompacting()) {
        {
          //when compacting each split needs to process the whole logical bucket
          assert options.getOffset() == 0;
          assert options.getMaxOffset() == Long.MAX_VALUE;
          assert minKey == null;
          assert maxKey == null;
        }
        AcidUtils.Directory directoryState = AcidUtils.getAcidState(
          mergerOptions.getRootPath(), conf, validTxnList, false, true);
        originalFiles = directoryState.getOriginalFiles();
        assert originalFiles.size() > 0;
        /**
         * when there are no copyN files, the {@link #recordReader} will be the the one and only
         * file for for 'bucket' but closing here makes flow cleaner and only happens once in the
         * life of the table.  With copyN files, the caller may pass in any one of the copyN files.
         * This is less prone to bugs than expecting the reader to pass in a Reader for the 1st file
         * of a logical bucket.*/
        recordReader.close();
        reader = advanceToNextFile();//in case of Compaction, this is the 1st file of the current bucket
        if(reader == null) {
          //Compactor generated a split for a bucket that has no data?
          throw new IllegalStateException("No 'original' files found for bucketId=" + bucket +
            " in " + mergerOptions.getRootPath());
        }
        numRowsInCurrentFile = reader.getNumberOfRows();
        originalFileRecordReader = reader.rowsOptions(options);
      }
      else {
        /**
         * Logically each bucket consists of 0000_0, 0000_0_copy_1... 0000_0_copyN. etc  We don't
         * know N a priori so if this is true, then the current split is from 0000_0_copyN file.
         * It's needed to correctly set maxKey.  In particular, set maxKey==null if this split
         * is the tail of the last file for this logical bucket to include all deltas written after
         * non-acid to acid table conversion.
         */
        boolean isLastFileForThisBucket = false;
        boolean haveSeenCurrentFile = false;
        originalFiles = Collections.emptyList();
        if (mergerOptions.getCopyIndex() > 0) {
          //the split is from something other than the 1st file of the logical bucket - compute offset
          
          AcidUtils.Directory directoryState = AcidUtils.getAcidState(mergerOptions.getRootPath(),
            conf, validTxnList, false, true);
          for (HadoopShims.HdfsFileStatusWithId f : directoryState.getOriginalFiles()) {
            AcidOutputFormat.Options bucketOptions =
              AcidUtils.parseBaseOrDeltaBucketFilename(f.getFileStatus().getPath(), conf);
            if (bucketOptions.getBucketId() != bucket) {
              continue;
            }
            if(haveSeenCurrentFile) {
              //if here we already saw current file and now found another file for the same bucket
              //so the current file is not the last file of the logical bucket
              isLastFileForThisBucket = false;
              break;
            }
            if(f.getFileStatus().getPath().equals(mergerOptions.getBucketPath())) {
              /**
               * found the file whence the current split is from so we're done
               * counting {@link rowIdOffset}
               */
              haveSeenCurrentFile = true;
              isLastFileForThisBucket = true;
              continue;
            }
            Reader copyReader = OrcFile.createReader(f.getFileStatus().getPath(),
              OrcFile.readerOptions(conf));
            rowIdOffset += copyReader.getNumberOfRows();
          }
          if (rowIdOffset > 0) {
            //rowIdOffset could be 0 if all files before current one are empty
            /**
             * Since we already done {@link OrcRawRecordMerger#discoverOriginalKeyBounds(Reader,
             * int, Reader.Options)} need to fix min/max key since these are used by
             * {@link #next(OrcStruct)} which uses {@link #rowIdOffset} to generate rowId for
             * the key.  Clear?  */
            if (minKey != null) {
              minKey.setRowId(minKey.getRowId() + rowIdOffset);
            }
            else {
              /**
               *  If this is not the 1st file, set minKey 1 less than the start of current file
               * (Would not need to set minKey if we knew that there are no delta files)
               * {@link #advanceToMinKey()} needs this */
              newMinKey = new RecordIdentifier(0, bucket, rowIdOffset - 1);
            }
            if (maxKey != null) {
              maxKey.setRowId(maxKey.getRowId() + rowIdOffset);
            }
          }
        } else {
          isLastFileForThisBucket = true;
          AcidUtils.Directory directoryState = AcidUtils.getAcidState(mergerOptions.getRootPath(),
            conf, validTxnList, false, true);
          int numFilesInBucket= 0;
          for (HadoopShims.HdfsFileStatusWithId f : directoryState.getOriginalFiles()) {
            AcidOutputFormat.Options bucketOptions =
              AcidUtils.parseBaseOrDeltaBucketFilename(f.getFileStatus().getPath(), conf);
            if (bucketOptions.getBucketId() == bucket) {
              numFilesInBucket++;
              if(numFilesInBucket > 1) {
                isLastFileForThisBucket = false;
                break;
              }
            }
          }
        }
        originalFileRecordReader = recordReader;
        if(!isLastFileForThisBucket && maxKey == null) {
          /*
           * If this is the last file for this bucket, maxKey == null means the split is the tail
           * of the file so we want to leave it blank to make sure any insert events in delta
           * files are included; Conversely, if it's not the last file, set the maxKey so that
           * events from deltas that don't modify anything in the current split are excluded*/
          newMaxKey = new RecordIdentifier(0, bucket,
            rowIdOffset + reader.getNumberOfRows() - 1);
        }
      }
      this.minKey = newMinKey;
      this.maxKey = newMaxKey;
    }
    @Override RecordReader getRecordReader() {
      return originalFileRecordReader;
    }
    @Override RecordIdentifier getMinKey() {
      return minKey;
    }
    @Override RecordIdentifier getMaxKey() {
      return maxKey;
    }
    private boolean nextFromCurrentFile(OrcStruct next) throws IOException {
      if (originalFileRecordReader.hasNext()) {
        //RecordReader.getRowNumber() produces a file-global row number even with PPD
        long nextRowId = originalFileRecordReader.getRowNumber() + rowIdOffset;
        // have to do initialization here, because the super's constructor
        // calls next and thus we need to initialize before our constructor
        // runs
        if (next == null) {
          nextRecord = new OrcStruct(OrcRecordUpdater.FIELDS);
          IntWritable operation =
              new IntWritable(OrcRecordUpdater.INSERT_OPERATION);
          nextRecord.setFieldValue(OrcRecordUpdater.OPERATION, operation);
          nextRecord.setFieldValue(OrcRecordUpdater.CURRENT_TRANSACTION,
              new LongWritable(0));
          nextRecord.setFieldValue(OrcRecordUpdater.ORIGINAL_TRANSACTION,
              new LongWritable(0));
          nextRecord.setFieldValue(OrcRecordUpdater.BUCKET,
              new IntWritable(bucket));
          nextRecord.setFieldValue(OrcRecordUpdater.ROW_ID,
              new LongWritable(nextRowId));
          nextRecord.setFieldValue(OrcRecordUpdater.ROW,
              originalFileRecordReader.next(null));
        } else {
          nextRecord = next;
          ((IntWritable) next.getFieldValue(OrcRecordUpdater.OPERATION))
              .set(OrcRecordUpdater.INSERT_OPERATION);
          ((LongWritable) next.getFieldValue(OrcRecordUpdater.ORIGINAL_TRANSACTION))
              .set(0);
          ((IntWritable) next.getFieldValue(OrcRecordUpdater.BUCKET))
              .set(bucket);
          ((LongWritable) next.getFieldValue(OrcRecordUpdater.CURRENT_TRANSACTION))
              .set(0);
          ((LongWritable) next.getFieldValue(OrcRecordUpdater.ROW_ID))
              .set(nextRowId);
          nextRecord.setFieldValue(OrcRecordUpdater.ROW,
              originalFileRecordReader.next(OrcRecordUpdater.getRow(next)));
        }
        key.setValues(0L, bucket, nextRowId, 0L, 0);
        if (maxKey != null && key.compareRow(maxKey) > 0) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("key " + key + " > maxkey " + maxKey);
          }
          return false;//reached End Of Split
        }
        return true;
      }
      return false;//reached EndOfFile
    }
    @Override
    void next(OrcStruct next) throws IOException {
      assert advancedToMinKey : "advnaceToMinKey() was not called";
      while(true) {
        if(nextFromCurrentFile(next)) {
          return;
        } else {
          if (originalFiles.size() <= nextFileIndex) {
            //no more original files to read
            nextRecord = null;
            originalFileRecordReader.close();
            return;
          } else {
            assert mergerOptions.isCompacting() : "originalFiles.size() should be 0 when not compacting";
            rowIdOffset += numRowsInCurrentFile;
            originalFileRecordReader.close();
            Reader reader = advanceToNextFile();
            if(reader == null) {
              nextRecord = null;
              return;
            }
            numRowsInCurrentFile = reader.getNumberOfRows();
            originalFileRecordReader = reader.rowsOptions(options);
          }
        }
      }
    }
    /**
     * Finds the next file of the logical bucket
     * @return {@code null} if there are no more files
     */
    private Reader advanceToNextFile() throws IOException {
      while(nextFileIndex < originalFiles.size()) {
        AcidOutputFormat.Options bucketOptions = AcidUtils.parseBaseOrDeltaBucketFilename(originalFiles.get(nextFileIndex).getFileStatus().getPath(), conf);
        if (bucketOptions.getBucketId() == bucket) {
          break;
        }
        nextFileIndex++;
      }
      if(originalFiles.size() <= nextFileIndex) {
        return null;//no more files for current bucket
      }
      return OrcFile.createReader(originalFiles.get(nextFileIndex++).getFileStatus().getPath(), OrcFile.readerOptions(conf));
    }

    @Override
    int getColumns() {
      return reader.getTypes().get(0).getSubtypesCount();
    }
  }

  /**
   * The process here reads several (base + some deltas) files each of which is sorted on 
   * {@link ReaderKey} ascending.  The output of this Reader should a global order across these
   * files.  The root of this tree is always the next 'file' to read from.
   */
  private final TreeMap<ReaderKey, ReaderPair> readers =
      new TreeMap<ReaderKey, ReaderPair>();

  // The reader that currently has the lowest key.
  private ReaderPair primary;

  // The key of the next lowest reader.
  private ReaderKey secondaryKey = null;

  private static final class KeyInterval {
    private final RecordIdentifier minKey;
    private final RecordIdentifier maxKey;
    private KeyInterval(RecordIdentifier minKey, RecordIdentifier maxKey) {
      this.minKey = minKey;
      this.maxKey = maxKey;
    }
    private RecordIdentifier getMinKey() {
      return minKey;
    }
    private RecordIdentifier getMaxKey() {
      return maxKey;
    }
  }
  /**
   * Find the key range for original bucket files.
   * @param reader the reader
   * @param bucket the bucket number we are reading
   * @param options the options for reading with
   * @throws IOException
   */
  private KeyInterval discoverOriginalKeyBounds(Reader reader, int bucket,
                                         Reader.Options options
                                         ) throws IOException {
    long rowLength = 0;
    long rowOffset = 0;
    long offset = options.getOffset();//this would usually be at block boundary
    long maxOffset = options.getMaxOffset();//this would usually be at block boundary
    boolean isTail = true;
    RecordIdentifier minKey = null;
    RecordIdentifier maxKey = null;
   /**
    * options.getOffset() and getMaxOffset() would usually be at block boundary which doesn't
    * necessarily match stripe boundary.  So we want to come up with minKey to be one before the 1st
    * row of the first stripe that starts after getOffset() and maxKey to be the last row of the
    * stripe that contains getMaxOffset().  This breaks if getOffset() and getMaxOffset() are inside
    * the sames tripe - in this case we have minKey & isTail=false but rowLength is never set.
    * (HIVE-16953)
    */
    for(StripeInformation stripe: reader.getStripes()) {
      if (offset > stripe.getOffset()) {
        rowOffset += stripe.getNumberOfRows();
      } else if (maxOffset > stripe.getOffset()) {
        rowLength += stripe.getNumberOfRows();
      } else {
        isTail = false;
        break;
      }
    }
    if (rowOffset > 0) {
      minKey = new RecordIdentifier(0, bucket, rowOffset - 1);
    }
    if (!isTail) {
      maxKey = new RecordIdentifier(0, bucket, rowOffset + rowLength - 1);
    }
    return new KeyInterval(minKey, maxKey);
  }

  /**
   * Find the key range for bucket files.
   * @param reader the reader
   * @param options the options for reading with
   * @throws IOException
   */
  private KeyInterval discoverKeyBounds(Reader reader,
                                 Reader.Options options) throws IOException {
    RecordIdentifier[] keyIndex = OrcRecordUpdater.parseKeyIndex(reader);
    long offset = options.getOffset();
    long maxOffset = options.getMaxOffset();
    int firstStripe = 0;
    int stripeCount = 0;
    boolean isTail = true;
    RecordIdentifier minKey = null;
    RecordIdentifier maxKey = null;
    
    List<StripeInformation> stripes = reader.getStripes();
    for(StripeInformation stripe: stripes) {
      if (offset > stripe.getOffset()) {
        firstStripe += 1;
      } else if (maxOffset > stripe.getOffset()) {
        stripeCount += 1;
      } else {
        isTail = false;
        break;
      }
    }
    if (firstStripe != 0) {
      minKey = keyIndex[firstStripe - 1];
    }
    if (!isTail) {
      maxKey = keyIndex[firstStripe + stripeCount - 1];
    }
    return new KeyInterval(minKey, maxKey);
  }

  /**
   * Convert from the row include/sarg/columnNames to the event equivalent
   * for the underlying file.
   * @param options options for the row reader
   * @return a cloned options object that is modified for the event reader
   */
  static Reader.Options createEventOptions(Reader.Options options) {
    Reader.Options result = options.clone();
    result.range(options.getOffset(), Long.MAX_VALUE);
    result.include(options.getInclude());

    // slide the column names down by 6 for the name array
    if (options.getColumnNames() != null) {
      String[] orig = options.getColumnNames();
      String[] cols = new String[orig.length + OrcRecordUpdater.FIELDS];
      for(int i=0; i < orig.length; ++i) {
        cols[i + OrcRecordUpdater.FIELDS] = orig[i];
      }
      result.searchArgument(options.getSearchArgument(), cols);
    }
    return result;
  }

  static class Options {
    private int copyIndex = 0;
    private boolean isCompacting = false;
    private Path bucketPath;
    private Path rootPath;
    Options copyIndex(int copyIndex) {
      assert copyIndex >= 0;
      this.copyIndex = copyIndex;
      return this;
    }
    Options isCompacting(boolean isCompacting) {
      this.isCompacting = isCompacting;
      return this;
    }
    Options bucketPath(Path bucketPath) {
      this.bucketPath = bucketPath;
      return this;
    }
    Options rootPath(Path rootPath) {
      this.rootPath = rootPath;
      return this;
    }
    /**
     * 0 means it's the original file, without {@link Utilities#COPY_KEYWORD} suffix
     */
    int getCopyIndex() {
      return copyIndex;
    }
    boolean isCompacting() {
      return isCompacting;
    }
    /**
     * Full path to the data file
     * @return
     */
    Path getBucketPath() {
      return bucketPath;
    }
    /**
     * Partition folder (Table folder if not partitioned)
     */
    Path getRootPath()  { return rootPath; }
  }
  /**
   * Create a reader that merge sorts the ACID events together.
   * @param conf the configuration
   * @param collapseEvents should the events on the same row be collapsed
   * @param isOriginal is the base file a pre-acid file
   * @param bucket the bucket we are reading
   * @param options the options to read with
   * @param deltaDirectory the list of delta directories to include
   * @throws IOException
   */
  OrcRawRecordMerger(Configuration conf,
                     boolean collapseEvents,
                     Reader reader,
                     boolean isOriginal,
                     int bucket,
                     ValidTxnList validTxnList,
                     Reader.Options options,
                     Path[] deltaDirectory, Options mergerOptions) throws IOException {
    this.conf = conf;
    this.collapse = collapseEvents;
    this.offset = options.getOffset();
    this.length = options.getLength();
    this.validTxnList = validTxnList;

    TypeDescription typeDescr =
        OrcInputFormat.getDesiredRowTypeDescr(conf, true, Integer.MAX_VALUE);

    objectInspector = OrcRecordUpdater.createEventSchema
        (OrcStruct.createObjectInspector(0, OrcUtils.getOrcTypes(typeDescr)));

    // modify the options to reflect the event instead of the base row
    Reader.Options eventOptions = createEventOptions(options);
    if (reader == null) {
      baseReader = null;
      minKey = maxKey = null;
    } else {
      KeyInterval keyInterval;
      // find the min/max based on the offset and length (and more for 'original')
      if (isOriginal) {
        keyInterval = discoverOriginalKeyBounds(reader, bucket, options);
      } else {
        keyInterval = discoverKeyBounds(reader, options);
      }
      LOG.info("min key = " + keyInterval.getMinKey() + ", max key = " + keyInterval.getMaxKey());
      // use the min/max instead of the byte range
      ReaderPair pair;
      ReaderKey key = new ReaderKey();
      if (isOriginal) {
        options = options.clone();
        pair = new OriginalReaderPair(key, reader, bucket, keyInterval.getMinKey(), keyInterval.getMaxKey(),
                                      options, mergerOptions, conf, validTxnList);
      } else {
        pair = new ReaderPair(key, reader, bucket, keyInterval.getMinKey(), keyInterval.getMaxKey(),
                              eventOptions, 0);
      }
      minKey = pair.getMinKey();
      maxKey = pair.getMaxKey();
      LOG.info("updated min key = " + keyInterval.getMinKey() + ", max key = " + keyInterval.getMaxKey());
      pair.advnaceToMinKey();
      // if there is at least one record, put it in the map
      if (pair.nextRecord != null) {
        readers.put(key, pair);
      }
      baseReader = pair.getRecordReader();
    }

    // we always want to read all of the deltas
    eventOptions.range(0, Long.MAX_VALUE);
    if (deltaDirectory != null) {
      for(Path delta: deltaDirectory) {
        ReaderKey key = new ReaderKey();
        Path deltaFile = AcidUtils.createBucketFile(delta, bucket);
        AcidUtils.ParsedDelta deltaDir = AcidUtils.parsedDelta(delta);
        FileSystem fs = deltaFile.getFileSystem(conf);
        long length = OrcAcidUtils.getLastFlushLength(fs, deltaFile);
        if (length != -1 && fs.exists(deltaFile)) {
          Reader deltaReader = OrcFile.createReader(deltaFile,
              OrcFile.readerOptions(conf).maxLength(length));
          Reader.Options deltaEventOptions = null;
          if(eventOptions.getSearchArgument() != null) {
            // Turn off the sarg before pushing it to delta.  We never want to push a sarg to a delta as
            // it can produce wrong results (if the latest valid version of the record is filtered out by
            // the sarg) or ArrayOutOfBounds errors (when the sarg is applied to a delete record)
            // unless the delta only has insert events
            AcidStats acidStats = OrcAcidUtils.parseAcidStats(deltaReader);
            if(acidStats.deletes > 0 || acidStats.updates > 0) {
              deltaEventOptions = eventOptions.clone().searchArgument(null, null);
            }
          }
          ReaderPair deltaPair;
          deltaPair = new ReaderPair(key, deltaReader, bucket, minKey,
            maxKey, deltaEventOptions != null ? deltaEventOptions : eventOptions, deltaDir.getStatementId());
          deltaPair.advnaceToMinKey();
          if (deltaPair.nextRecord != null) {
            readers.put(key, deltaPair);
          }
        }
      }
    }

    // get the first record
    Map.Entry<ReaderKey, ReaderPair> entry = readers.pollFirstEntry();
    if (entry == null) {
      columns = 0;
      primary = null;
    } else {
      primary = entry.getValue();
      if (readers.isEmpty()) {
        secondaryKey = null;
      } else {
        secondaryKey = readers.firstKey();
      }
      // get the number of columns in the user's rows
      columns = primary.getColumns();
    }
  }

  @VisibleForTesting
  RecordIdentifier getMinKey() {
    return minKey;
  }

  @VisibleForTesting
  RecordIdentifier getMaxKey() {
    return maxKey;
  }

  @VisibleForTesting
  ReaderPair getCurrentReader() {
    return primary;
  }

  @VisibleForTesting
  Map<ReaderKey, ReaderPair> getOtherReaders() {
    return readers;
  }

  @Override
  public boolean next(RecordIdentifier recordIdentifier,
                      OrcStruct prev) throws IOException {
    boolean keysSame = true;
    while (keysSame && primary != null) {

      // The primary's nextRecord is the next value to return
      OrcStruct current = primary.nextRecord;
      recordIdentifier.set(primary.key);

      // Advance the primary reader to the next record
      primary.next(extraValue);

      // Save the current record as the new extraValue for next time so that
      // we minimize allocations
      extraValue = current;

      // now that the primary reader has advanced, we need to see if we
      // continue to read it or move to the secondary.
      if (primary.nextRecord == null ||
          primary.key.compareTo(secondaryKey) > 0) {

        // if the primary isn't done, push it back into the readers
        if (primary.nextRecord != null) {
          readers.put(primary.key, primary);
        }

        // update primary and secondaryKey
        Map.Entry<ReaderKey, ReaderPair> entry = readers.pollFirstEntry();
        if (entry != null) {
          primary = entry.getValue();
          if (readers.isEmpty()) {
            secondaryKey = null;
          } else {
            secondaryKey = readers.firstKey();
          }
        } else {
          primary = null;
        }
      }

      // if this transaction isn't ok, skip over it
      if (!validTxnList.isTxnValid(
          ((ReaderKey) recordIdentifier).getCurrentTransactionId())) {
        continue;
      }

      /*for multi-statement txns, you may have multiple events for the same
      * row in the same (current) transaction.  We want to collapse these to just the last one
      * regardless whether we are minor compacting.  Consider INSERT/UPDATE/UPDATE of the
      * same row in the same txn.  There is no benefit passing along anything except the last
      * event.  If we did want to pass it along, we'd have to include statementId in the row
      * returned so that compaction could write it out or make minor minor compaction understand
      * how to write out delta files in delta_xxx_yyy_stid format.  There doesn't seem to be any
      * value in this.*/
      boolean isSameRow = prevKey.isSameRow((ReaderKey)recordIdentifier);
      // if we are collapsing, figure out if this is a new row
      if (collapse || isSameRow) {
        keysSame = (collapse && prevKey.compareRow(recordIdentifier) == 0) || (isSameRow);
        if (!keysSame) {
          prevKey.set(recordIdentifier);
        }
      } else {
        keysSame = false;
      }

      // set the output record by fiddling with the pointers so that we can
      // avoid a copy.
      prev.linkFields(current);
    }
    return !keysSame;
  }

  @Override
  public RecordIdentifier createKey() {
    return new ReaderKey();
  }

  @Override
  public OrcStruct createValue() {
    return new OrcStruct(OrcRecordUpdater.FIELDS);
  }

  @Override
  public long getPos() throws IOException {
    return offset + (long)(getProgress() * length);
  }

  @Override
  public void close() throws IOException {
    if (primary != null) {
      primary.recordReader.close();
    }
    for(ReaderPair pair: readers.values()) {
      pair.recordReader.close();
    }
  }

  @Override
  public float getProgress() throws IOException {
    //this is not likely to do the right thing for Compaction of "original" files when there are copyN files
    return baseReader == null ? 1 : baseReader.getProgress();
  }

  @Override
  public ObjectInspector getObjectInspector() {
    return objectInspector;
  }

  @Override
  public boolean isDelete(OrcStruct value) {
    return OrcRecordUpdater.getOperation(value) == OrcRecordUpdater.DELETE_OPERATION;
  }

  /**
   * Get the number of columns in the underlying rows.
   * @return 0 if there are no base and no deltas.
   */
  public int getColumns() {
    return columns;
  }
}
