package org.apache.paimon.format.parquet.reader;


import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.PrimitiveIterator;

/*Parquet reader state*/
public class ParquetReadState {
  /**
   *  A special row range used when there is no row indexes (hence all rows must be included)
   * */
  private static final RowRange MAX_ROW_RANGE = new RowRange(Long.MIN_VALUE, Long.MAX_VALUE);

  /**
   * A special row range used when the row indexes are present AND all the row ranges have been
   * processed. This serves as a sentinel at the end indicating that all rows come after the last
   * row range should be skipped.
   */
  private static final RowRange END_ROW_RANGE = new RowRange(Long.MAX_VALUE, Long.MIN_VALUE);

  private final Iterator<RowRange> rowRanges;

  private RowRange currentRange;

  /**
   * row index for the next read
   */
  long rowId;
  int valuesToReadInPage;
  int rowsToReadInBatch;

  ParquetReadState(PrimitiveIterator.OfLong rowIndexes) {
    this.rowRanges = constructRanges(rowIndexes);
    nextRange();
  }

  /**
   * Construct a list of row ranges from the given `rowIndexes`.
   * For example, suppose the `rowIndexes` are `[0, 1, 2, 4, 5, 7, 8, 9]`,
   * it will be converted into 3 row ranges: `[0-2], [4-5], [7-9]`.
   */
  private Iterator<RowRange> constructRanges(PrimitiveIterator.OfLong rowIndexes) {
    if (rowIndexes == null) {
      return null;
    }

    List<RowRange> rowRanges = new ArrayList<>();
    long currentStart = Long.MIN_VALUE;
    long previous = Long.MIN_VALUE;

    while (rowIndexes.hasNext()) {
      long idx = rowIndexes.nextLong();
      if (currentStart == Long.MIN_VALUE) {
        currentStart = idx;
      } else if (previous + 1 != idx) {
        RowRange range = new RowRange(currentStart, previous);
        rowRanges.add(range);
        currentStart = idx;
      }
      previous = idx;
    }

    if (previous != Long.MIN_VALUE) {
      rowRanges.add(new RowRange(currentStart, previous));
    }

    return rowRanges.iterator();
  }

  /**
   * Must be called at the beginning of reading a new batch.
   */
  void resetForNewBatch(int batchSize) {
    this.rowsToReadInBatch = batchSize;
  }

  /**
   * Must be called at the beginning of reading a new page.
   */
  void resetForNewPage(int totalValuesInPage, long pageFirstRowIndex) {
    this.valuesToReadInPage = totalValuesInPage;
    this.rowId = pageFirstRowIndex;
  }

  /**
   * Returns the start index of the current row range.
   */
  long currentRangeStart() {
    return currentRange.start;
  }

  /**
   * Returns the end index of the current row range.
   */
  long currentRangeEnd() {
    return currentRange.end;
  }

  boolean isFinished(){
    return this.currentRange.equals(this.END_ROW_RANGE);
  }

  /**
   * Advance to the next range.
   */
  void nextRange() {
    if (rowRanges == null) {
      currentRange = MAX_ROW_RANGE;
    } else if (!rowRanges.hasNext()) {
      currentRange = END_ROW_RANGE;
    } else {
      currentRange = rowRanges.next();
    }
  }

  /**
   * Helper struct to represent a range of row indexes `[start, end]`.
   */
  private static class RowRange {
    final long start;
    final long end;

    RowRange(long start, long end) {
      this.start = start;
      this.end = end;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof RowRange)){
        return false;
      }
      return ((RowRange) obj).start == this.start &&
              ((RowRange) obj).end == this.end;

    }
  }
}
