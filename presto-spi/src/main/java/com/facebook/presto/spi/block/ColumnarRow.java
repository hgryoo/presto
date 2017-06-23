/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.spi.block;

import static java.util.Objects.requireNonNull;

public final class ColumnarRow
{
    private final Block nullCheckBlock;
    private final Block[] fields;

    public static ColumnarRow toColumnarRow(Block block)
    {
        requireNonNull(block, "block is null");

        if (block instanceof DictionaryBlock) {
            return toColumnarRow((DictionaryBlock) block);
        }
        if (block instanceof RunLengthEncodedBlock) {
            return toColumnarRow((RunLengthEncodedBlock) block);
        }

        if (!(block instanceof AbstractArrayBlock)) {
            throw new IllegalArgumentException("Invalid row block");
        }

        AbstractArrayBlock arrayBlock = (AbstractArrayBlock) block;
        Block arrayBlockValues = arrayBlock.getValues();
        if (!(arrayBlockValues instanceof AbstractInterleavedBlock)) {
            throw new IllegalArgumentException("Invalid row block");
        }
        AbstractInterleavedBlock interleavedBlock = (AbstractInterleavedBlock) arrayBlockValues;

        // get fields for visible region
        int interleavedBlockOffset = 0;
        int interleavedBlockLength = 0;
        if (arrayBlock.getPositionCount() > 0) {
            interleavedBlockOffset = arrayBlock.getOffset(0);
            interleavedBlockLength = arrayBlock.getOffset(arrayBlock.getPositionCount()) - interleavedBlockOffset;
        }
        Block[] fields = interleavedBlock.computeSerializableSubBlocks(interleavedBlockOffset, interleavedBlockLength);

        return new ColumnarRow(block, fields);
    }

    private static ColumnarRow toColumnarRow(DictionaryBlock dictionaryBlock)
    {
        // build a mapping from the old dictionary to a new dictionary with nulls removed
        Block dictionary = dictionaryBlock.getDictionary();
        int[] newDictionaryIndex = new int[dictionary.getPositionCount()];
        int nextNewDictionaryIndex = 0;
        for (int position1 = 0; position1 < dictionary.getPositionCount(); position1++) {
            if (!dictionary.isNull(position1)) {
                newDictionaryIndex[position1] = nextNewDictionaryIndex;
                nextNewDictionaryIndex++;
            }
        }

        // reindex the dictionary
        int[] dictionaryIds = new int[dictionaryBlock.getPositionCount()];
        int nonNullPositionCount = 0;
        for (int position = 0; position < dictionaryBlock.getPositionCount(); position++) {
            if (!dictionaryBlock.isNull(position)) {
                int oldDictionaryId = dictionaryBlock.getId(position);
                dictionaryIds[nonNullPositionCount] = newDictionaryIndex[oldDictionaryId];
                nonNullPositionCount++;
            }
        }

        ColumnarRow columnarRow = toColumnarRow(dictionaryBlock.getDictionary());
        Block[] fields = new Block[columnarRow.getFieldCount()];
        for (int i = 0; i < columnarRow.getFieldCount(); i++) {
            fields[i] = new DictionaryBlock(nonNullPositionCount, columnarRow.getNullSuppressedField(i), dictionaryIds);
        }
        return new ColumnarRow(dictionaryBlock, fields);
    }

    private static ColumnarRow toColumnarRow(RunLengthEncodedBlock rleBlock)
    {
        Block rleValue = rleBlock.getValue();
        ColumnarRow columnarRow = toColumnarRow(rleValue);

        Block[] fields = new Block[columnarRow.getFieldCount()];
        for (int i = 0; i < columnarRow.getFieldCount(); i++) {
            Block nullSuppressedField = columnarRow.getNullSuppressedField(i);
            if (rleValue.isNull(0)) {
                // the rle value is a null row so, all null-suppressed fields should empty
                if (nullSuppressedField.getPositionCount() != 0) {
                    throw new IllegalArgumentException("Invalid row block");
                }
                fields[i] = nullSuppressedField;
            }
            else {
                fields[i] = new RunLengthEncodedBlock(nullSuppressedField, rleBlock.getPositionCount());
            }
        }
        return new ColumnarRow(rleBlock, fields);
    }

    private ColumnarRow(Block nullCheckBlock, Block[] fields)
    {
        this.nullCheckBlock = nullCheckBlock;
        this.fields = fields.clone();
    }

    public int getPositionCount()
    {
        return nullCheckBlock.getPositionCount();
    }

    public boolean isNull(int position)
    {
        return nullCheckBlock.isNull(position);
    }

    public int getFieldCount()
    {
        return fields.length;
    }

    public Block getNullSuppressedField(int index)
    {
        return fields[index];
    }
}