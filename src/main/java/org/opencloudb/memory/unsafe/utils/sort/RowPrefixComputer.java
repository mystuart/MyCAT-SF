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

package org.opencloudb.memory.unsafe.utils.sort;




import org.opencloudb.memory.unsafe.row.StructType;
import org.opencloudb.memory.unsafe.row.UnsafeRow;
import org.opencloudb.memory.unsafe.utils.BytesTools;
import org.opencloudb.mpp.ColMeta;
import org.opencloudb.mpp.OrderCol;

import javax.annotation.Nonnull;
import java.io.UnsupportedEncodingException;

/**
 * Created by zagnix on 2016/6/20.
 */
public class RowPrefixComputer extends UnsafeExternalRowSorter.PrefixComputer {
    @Nonnull
    private final StructType schema;
    private final ColMeta colMeta;

    public RowPrefixComputer(StructType schema){
        this.schema = schema;
        /**
         * 按order by col1，col2 .... 中列做比较顺序
         */
       // int orderIndex = 0;
        OrderCol[] orderCols = schema.getOrderCols();

        if (orderCols != null){
//            for (int i = 0; i < orderCols.length; i++) {
//                ColMeta colMeta = orderCols[i].colMeta;
//                if(colMeta.colIndex == 0){
//                    orderIndex = i;
//                    break;
//                }
//            }

            this.colMeta = orderCols[0].colMeta;
        }else {
            this.colMeta = null;
        }
    }

    protected long computePrefix(UnsafeRow row) throws UnsupportedEncodingException {

        if(this.colMeta == null){
            return 0;
        }

        int orderIndexType = colMeta.colType;

        byte[] rowIndexElem  = null;
		
		  if(!row.isNullAt(colMeta.colIndex)) {
              rowIndexElem = row.getBinary(colMeta.colIndex);
              /**
               * 这里注意一下，order by 排序的第一个字段
               */
              switch (orderIndexType) {
                  case ColMeta.COL_TYPE_INT:
                  case ColMeta.COL_TYPE_LONG:
                  case ColMeta.COL_TYPE_INT24:
                      return BytesTools.getInt(rowIndexElem);
                  case ColMeta.COL_TYPE_SHORT:
                      return BytesTools.getShort(rowIndexElem);
                  case ColMeta.COL_TYPE_LONGLONG:
                      return BytesTools.getLong(rowIndexElem);
                  case ColMeta.COL_TYPE_FLOAT:
                      return PrefixComparators.DoublePrefixComparator.
                          computePrefix(BytesTools.getFloat(rowIndexElem));
                  case ColMeta.COL_TYPE_DOUBLE:
                  case ColMeta.COL_TYPE_DECIMAL:
                  case ColMeta.COL_TYPE_NEWDECIMAL:
                      return PrefixComparators.DoublePrefixComparator.
                              computePrefix(BytesTools.getDouble(rowIndexElem));
                  case ColMeta.COL_TYPE_DATE:
                  case ColMeta.COL_TYPE_TIMSTAMP:
                  case ColMeta.COL_TYPE_TIME:
                  case ColMeta.COL_TYPE_YEAR:
                  case ColMeta.COL_TYPE_DATETIME:
                  case ColMeta.COL_TYPE_NEWDATE:
                  case ColMeta.COL_TYPE_BIT:
                  case ColMeta.COL_TYPE_VAR_STRING:
                  case ColMeta.COL_TYPE_STRING:
                      // ENUM和SET类型都是字符串，按字符串处理
                  case ColMeta.COL_TYPE_ENUM:
                  case ColMeta.COL_TYPE_SET:
                      return PrefixComparators.BinaryPrefixComparator.computePrefix(rowIndexElem);
                     //BLOB相关类型和GEOMETRY类型不支持排序，略掉
              }
          } else {
              rowIndexElem = new byte[1];
              rowIndexElem[0] = UnsafeRow.NULL_MARK;
              return PrefixComparators.BinaryPrefixComparator.computePrefix(rowIndexElem);
          }
		  
        return 0;
    }
}
