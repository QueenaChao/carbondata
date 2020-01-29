/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.carbondata.core.scan.collector.impl;

import java.util.ArrayList;
import java.util.List;

import org.apache.carbondata.core.constants.CarbonCommonConstants;
import org.apache.carbondata.core.datastore.block.SegmentProperties;
import org.apache.carbondata.core.keygenerator.KeyGenerator;
import org.apache.carbondata.core.keygenerator.mdkey.MultiDimKeyVarLengthGenerator;
import org.apache.carbondata.core.metadata.datatype.DataTypes;
import org.apache.carbondata.core.metadata.encoder.Encoding;
import org.apache.carbondata.core.metadata.schema.table.column.CarbonDimension;
import org.apache.carbondata.core.scan.executor.infos.BlockExecutionInfo;
import org.apache.carbondata.core.scan.model.ProjectionDimension;
import org.apache.carbondata.core.scan.model.ProjectionMeasure;
import org.apache.carbondata.core.scan.result.BlockletScannedResult;
import org.apache.carbondata.core.scan.wrappers.ByteArrayWrapper;
import org.apache.carbondata.core.stats.QueryStatistic;
import org.apache.carbondata.core.stats.QueryStatisticsConstants;
import org.apache.carbondata.core.util.CarbonUtil;
import org.apache.carbondata.core.util.DataTypeUtil;

import org.apache.commons.lang3.ArrayUtils;

/**
 * It is not a collector it is just a scanned result holder.
 */
public class RestructureBasedRawResultCollector extends RawBasedResultCollector {

  /**
   * Key generator which will form the mdKey according to latest schema
   */
  private KeyGenerator restructuredKeyGenerator;

  /**
   * Key generator for uncompressing current block values
   */
  private KeyGenerator updatedCurrentBlockKeyGenerator;

  public RestructureBasedRawResultCollector(BlockExecutionInfo blockExecutionInfos) {
    super(blockExecutionInfos);
    initRestructuredKeyGenerator();
    initCurrentBlockKeyGenerator();
  }

  /**
   * This method will create a new key generator for generating mdKey according to latest schema
   */
  private void initRestructuredKeyGenerator() {
    SegmentProperties segmentProperties =
        executionInfo.getDataBlock().getSegmentProperties();
    ProjectionDimension[] queryDimensions = executionInfo.getActualQueryDimensions();
    List<Integer> updatedColumnCardinality = new ArrayList<>(queryDimensions.length);
    List<Integer> updatedDimensionPartitioner = new ArrayList<>(queryDimensions.length);
    int[] dictionaryColumnBlockIndex = executionInfo.getDictionaryColumnChunkIndex();
    int dimCounterInCurrentBlock = 0;
    for (int i = 0; i < queryDimensions.length; i++) {
      if (queryDimensions[i].getDimension().getDataType() == DataTypes.DATE) {
        if (executionInfo.getDimensionInfo().getDimensionExists()[i]) {
          // get the dictionary key ordinal as column cardinality in segment properties
          // will only be for dictionary encoded columns
          CarbonDimension currentBlockDimension = segmentProperties.getDimensions()
              .get(dictionaryColumnBlockIndex[dimCounterInCurrentBlock]);
          updatedColumnCardinality.add(
              segmentProperties.getDimColumnsCardinality()[currentBlockDimension.getKeyOrdinal()]);
          updatedDimensionPartitioner.add(
              segmentProperties.getDimensionPartitions()[currentBlockDimension.getKeyOrdinal()]);
          dimCounterInCurrentBlock++;
        } else {
          // partitioner index will be 1 every column will be in columnar format
          updatedDimensionPartitioner.add(1);
          // for direct dictionary 4 bytes need to be allocated
          updatedColumnCardinality.add(Integer.MAX_VALUE);
        }
      }
    }
    if (!updatedColumnCardinality.isEmpty()) {
      int[] latestColumnCardinality = ArrayUtils.toPrimitive(
          updatedColumnCardinality.toArray(new Integer[0]));
      int[] latestColumnPartitioner = ArrayUtils.toPrimitive(
          updatedDimensionPartitioner.toArray(new Integer[0]));
      int[] dimensionBitLength =
          CarbonUtil.getDimensionBitLength(latestColumnCardinality, latestColumnPartitioner);
      restructuredKeyGenerator = new MultiDimKeyVarLengthGenerator(dimensionBitLength);
    }
  }

  /**
   * This method will initialize the block key generator for the current block based on the
   * dictionary columns present in the current block
   */
  private void initCurrentBlockKeyGenerator() {
    SegmentProperties segmentProperties =
        executionInfo.getDataBlock().getSegmentProperties();
    int[] dictionaryColumnBlockIndex = executionInfo.getDictionaryColumnChunkIndex();
    int[] updatedColumnCardinality = new int[dictionaryColumnBlockIndex.length];
    int[] updatedDimensionPartitioner = new int[dictionaryColumnBlockIndex.length];
    for (int i = 0; i < dictionaryColumnBlockIndex.length; i++) {
      // get the dictionary key ordinal as column cardinality in segment properties
      // will only be for dictionary encoded columns
      CarbonDimension currentBlockDimension =
          segmentProperties.getDimensions().get(dictionaryColumnBlockIndex[i]);
      updatedColumnCardinality[i] =
          segmentProperties.getDimColumnsCardinality()[currentBlockDimension.getKeyOrdinal()];
      updatedDimensionPartitioner[i] =
          segmentProperties.getDimensionPartitions()[currentBlockDimension.getKeyOrdinal()];
    }
    if (dictionaryColumnBlockIndex.length > 0) {
      int[] dimensionBitLength =
          CarbonUtil.getDimensionBitLength(updatedColumnCardinality, updatedDimensionPartitioner);
      updatedCurrentBlockKeyGenerator = new MultiDimKeyVarLengthGenerator(dimensionBitLength);
    }
  }

  /**
   * This method will add a record both key and value to list object
   * it will keep track of how many record is processed, to handle limit scenario
   */
  @Override
  public List<Object[]> collectResultInRow(BlockletScannedResult scannedResult, int batchSize) {
    long startTime = System.currentTimeMillis();
    List<Object[]> listBasedResult = new ArrayList<>(batchSize);
    ProjectionMeasure[] queryMeasures = executionInfo.getActualQueryMeasures();
    // scan the record and add to list
    scanAndFillData(scannedResult, batchSize, listBasedResult, queryMeasures);
    // re-fill dictionary and no dictionary key arrays for the newly added columns
    if (dimensionInfo.isDictionaryColumnAdded()) {
      fillDictionaryKeyArrayBatchWithLatestSchema(listBasedResult);
    }
    if (dimensionInfo.isNoDictionaryColumnAdded()) {
      fillNoDictionaryKeyArrayBatchWithLatestSchema(listBasedResult);
    }
    QueryStatistic resultPrepTime = queryStatisticsModel.getStatisticsTypeAndObjMap()
        .get(QueryStatisticsConstants.RESULT_PREP_TIME);
    resultPrepTime.addCountStatistic(QueryStatisticsConstants.RESULT_PREP_TIME,
        resultPrepTime.getCount() + (System.currentTimeMillis() - startTime));
    return listBasedResult;
  }

  /**
   * This method will fill the dictionary key array with newly added dictionary columns if any
   *
   * @param rows
   * @return
   */
  private void fillDictionaryKeyArrayBatchWithLatestSchema(List<Object[]> rows) {
    for (Object[] row : rows) {
      ByteArrayWrapper byteArrayWrapper = (ByteArrayWrapper) row[0];
      byte[] dictKeyArray = byteArrayWrapper.getDictionaryKey();
      ProjectionDimension[] actualQueryDimensions = executionInfo.getActualQueryDimensions();
      int newKeyArrayLength = dimensionInfo.getNewDictionaryColumnCount();
      long[] keyArray = null;
      if (null != updatedCurrentBlockKeyGenerator) {
        keyArray = updatedCurrentBlockKeyGenerator.getKeyArray(dictKeyArray);
        newKeyArrayLength += keyArray.length;
      }
      long[] keyArrayWithNewAddedColumns = new long[newKeyArrayLength];
      int existingColumnKeyArrayIndex = 0;
      int newKeyArrayIndex = 0;
      for (int i = 0; i < dimensionInfo.getDimensionExists().length; i++) {
        if (actualQueryDimensions[i].getDimension().getDataType() == DataTypes.DATE) {
          // if dimension exists then add the key array value else add the default value
          if (dimensionInfo.getDimensionExists()[i] && null != keyArray && 0 != keyArray.length) {
            keyArrayWithNewAddedColumns[newKeyArrayIndex++] =
                keyArray[existingColumnKeyArrayIndex++];
          } else {
            long defaultValueAsLong;
            Object defaultValue = dimensionInfo.getDefaultValues()[i];
            if (null != defaultValue) {
              defaultValueAsLong = ((Integer) defaultValue).longValue();
            } else {
              defaultValueAsLong = (long) CarbonCommonConstants.MEMBER_DEFAULT_VAL_SURROGATE_KEY;
            }
            keyArrayWithNewAddedColumns[newKeyArrayIndex++] = defaultValueAsLong;
          }
        }
      }
      dictKeyArray = restructuredKeyGenerator.generateKey(keyArrayWithNewAddedColumns);
      byteArrayWrapper.setDictionaryKey(dictKeyArray);
    }
  }

  /**
   * This method will fill the no dictionary byte array with newly added no dictionary columns
   *
   * @param rows
   * @return
   */
  private void fillNoDictionaryKeyArrayBatchWithLatestSchema(List<Object[]> rows) {
    for (Object[] row : rows) {
      ByteArrayWrapper byteArrayWrapper = (ByteArrayWrapper) row[0];
      byte[][] noDictKeyArray = byteArrayWrapper.getNoDictionaryKeys();
      ProjectionDimension[] actualQueryDimensions = executionInfo.getActualQueryDimensions();
      byte[][] noDictionaryKeyArrayWithNewlyAddedColumns =
          new byte[noDictKeyArray.length + dimensionInfo.getNewNoDictionaryColumnCount()][];
      int existingColumnValueIndex = 0;
      int newKeyArrayIndex = 0;
      for (int i = 0; i < dimensionInfo.getDimensionExists().length; i++) {
        if (actualQueryDimensions[i].getDimension().getDataType() != DataTypes.DATE
            && !actualQueryDimensions[i].getDimension().hasEncoding(Encoding.IMPLICIT)) {
          // if dimension exists then add the byte array value else add the default value
          if (dimensionInfo.getDimensionExists()[i]) {
            noDictionaryKeyArrayWithNewlyAddedColumns[newKeyArrayIndex++] =
                noDictKeyArray[existingColumnValueIndex++];
          } else {
            byte[] newColumnDefaultValue = null;
            Object defaultValue = dimensionInfo.getDefaultValues()[i];
            if (null != defaultValue) {
              newColumnDefaultValue = (byte[]) defaultValue;
            } else if (actualQueryDimensions[i].getDimension().getDataType() == DataTypes.STRING) {
              newColumnDefaultValue =
                  DataTypeUtil.getDataTypeConverter().convertFromByteToUTF8Bytes(
                      CarbonCommonConstants.MEMBER_DEFAULT_VAL_ARRAY);
            } else {
              newColumnDefaultValue = CarbonCommonConstants.EMPTY_BYTE_ARRAY;
            }
            noDictionaryKeyArrayWithNewlyAddedColumns[newKeyArrayIndex++] = newColumnDefaultValue;
          }
        }
      }
      byteArrayWrapper.setNoDictionaryKeys(noDictionaryKeyArrayWithNewlyAddedColumns);
    }
  }
}
