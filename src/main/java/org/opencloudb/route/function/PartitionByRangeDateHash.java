package org.opencloudb.route.function;

import com.google.common.hash.Hashing;
import org.apache.log4j.Logger;
import org.opencloudb.config.model.rule.RuleAlgorithm;

import java.text.ParseException;
import java.text.SimpleDateFormat;

/**
 * 先根据日期分组，再根据时间hash使得短期内数据分布的更均匀
 * 优点可以避免扩容时的数据迁移，又可以一定程度上避免范围分片的热点问题
 * 要求日期格式尽量精确些，不然达不到局部均匀的目的
 *
 *
 */
public class PartitionByRangeDateHash extends AbstractPartitionAlgorithm implements RuleAlgorithm
{
    private static final Logger LOGGER = Logger
            .getLogger(PartitionByRangeDateHash.class);

    private String sBeginDate;
    private String sPartionDay;
    private String dateFormat;

    private long beginDate;
    private long partionTime;

    private static final long oneDay = 86400000;

    private String groupPartionSize;
    private int intGroupPartionSize;

    @Override
    public void init()
    {
        try
        {
            beginDate = new SimpleDateFormat(dateFormat).parse(sBeginDate)
                    .getTime();
            intGroupPartionSize = Integer.parseInt(groupPartionSize);
            if (intGroupPartionSize <= 0)
            {
                throw new RuntimeException("groupPartionSize must >0,but cur is " + intGroupPartionSize);
            }
        } catch (ParseException e)
        {
            throw new IllegalArgumentException(e);
        }
        partionTime = Integer.parseInt(sPartionDay) * oneDay;
    }

    @Override
    public Integer calculate(String columnValue)
    {
        try
        {
            long targetTime = new SimpleDateFormat(dateFormat).parse(
                    columnValue).getTime();
            int targetPartition = (int) ((targetTime - beginDate) / partionTime);
            int innerIndex =  Hashing.consistentHash(targetTime,intGroupPartionSize);
            return targetPartition * intGroupPartionSize + innerIndex;

        } catch (ParseException e)
        {
            throw new IllegalArgumentException(e);

        }
    }
    
    /*
     * 所需的分片数数是不确定的，理论上可以无限，暂不对此分片算法做校验
     */
    @Override
	public int requiredNodeNum() {
    	return -1;
    }

    public Integer calculateStart(String columnValue)
    {
        try
        {
            long targetTime = new SimpleDateFormat(dateFormat).parse(
                    columnValue).getTime();
            int targetPartition = (int) ((targetTime - beginDate) / partionTime);
            return targetPartition * intGroupPartionSize;

        } catch (ParseException e)
        {
            throw new IllegalArgumentException(e);

        }
    }

    public Integer calculateEnd(String columnValue)
    {
        try
        {
            long targetTime = new SimpleDateFormat(dateFormat).parse(
                    columnValue).getTime();
            int targetPartition = (int) ((targetTime - beginDate) / partionTime);
            return (targetPartition+1) * intGroupPartionSize  - 1;

        } catch (ParseException e)
        {
            throw new IllegalArgumentException(e);

        }
    }

    @Override
    public Integer[] calculateRange(String beginValue, String endValue)
    {
        Integer begin = 0, end = 0;
        begin = calculateStart(beginValue);
        end = calculateEnd(endValue);

        if (begin == null || end == null)
        {
            return new Integer[0];
        }

        if (end >= begin)
        {
            int len = end - begin + 1;
            Integer[] re = new Integer[len];

            for (int i = 0; i < len; i++)
            {
                re[i] = begin + i;
            }

            return re;
        } else
        {
            return null;
        }
    }

    public long getBeginDate()
    {
        return beginDate;
    }
    
    public String getsBeginDate() {
    	return sBeginDate;
    }

    public void setsBeginDate(String sBeginDate)
    {
        this.sBeginDate = sBeginDate;
    }
    
    public String getsPartionDay() {
    	return sPartionDay;
    }

    public void setsPartionDay(String sPartionDay)
    {
        this.sPartionDay = sPartionDay;
    }
    
    public String getDateFormat() {
    	return dateFormat;
    }

    public void setDateFormat(String dateFormat)
    {
        this.dateFormat = dateFormat;
    }

    public String getGroupPartionSize()
    {
        return groupPartionSize;
    }

    public void setGroupPartionSize(String groupPartionSize)
    {
        this.groupPartionSize = groupPartionSize;
    }
}