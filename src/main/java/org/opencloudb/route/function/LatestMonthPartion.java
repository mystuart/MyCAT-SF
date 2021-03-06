package org.opencloudb.route.function;

/**
 * Latest one month data partions ,only reserve data of latest 31 days and one
 * day is partioned into N slide (splitOneDay), so total datanode is M*N table's
 * partion column must be int type and it's value format should be yyyyMMddHH
 * fomat for example colmn=2014050115 means: 15 clock of april 5 ,2014
 * 
 * @author wuzhih
 * 
 */
public class LatestMonthPartion extends AbstractPartitionAlgorithm {
	private int splitOneDay = 24;
	private int hourSpan;
	private String[] dataNodes;

	public String[] getDataNodes() {
		return dataNodes;
	}
	
	public int getSplitOneDay() {
		return splitOneDay;
	}

	/**
	 * @param dataNodeExpression
	 */
	public void setSplitOneDay(int split) {
		splitOneDay = split;
		hourSpan = 24 / splitOneDay;
		if (hourSpan * 24 < 24) {
			throw new java.lang.IllegalArgumentException(
					"invalid splitOnDay param:"
							+ splitOneDay
							+ " should be an even number and less or equals than 24");
		}
	}

	@Override
	public Integer calculate(String columnValue) {
		int valueLen = columnValue.length();
		int day = Integer.valueOf(columnValue.substring(valueLen - 4,
				valueLen - 2));
		int hour = Integer.valueOf(columnValue.substring(valueLen - 2));
		int dnIndex = (day - 1) * splitOneDay + hour / hourSpan;
		return dnIndex;

	}
	
	@Override
	public int requiredNodeNum() {
		return this.splitOneDay * 31; // 一个月最多31天，计算得到需要的最大分片数
	}

	public Integer[] calculateRange(String beginValue, String endValue) {
		return calculateSequenceRange(this,beginValue, endValue);
	}

}
