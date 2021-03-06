/*
 * Copyright (c) 2013, OpenCloudDB/MyCAT and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software;Designed and Developed mainly by many Chinese 
 * opensource volunteers. you can redistribute it and/or modify it under the 
 * terms of the GNU General Public License version 2 only, as published by the
 * Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 * 
 * Any questions about this component can be directed to it's project Web address 
 * https://code.google.com/p/opencloudb/.
 *
 */
package org.opencloudb.mysql.nio.handler;


import static org.opencloudb.sqlfw.SQLFirewallServer.OP_UPATE;
import static org.opencloudb.sqlfw.SQLFirewallServer.OP_UPATE_ROW;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.log4j.Logger;
import org.opencloudb.MycatConfig;
import org.opencloudb.MycatServer;
import org.opencloudb.backend.BackendConnection;
import org.opencloudb.backend.PhysicalDBNode;
import org.opencloudb.cache.LayerCachePool;
import org.opencloudb.memory.unsafe.row.UnsafeRow;
import org.opencloudb.monitor.SQLRecord;
import org.opencloudb.mpp.AbstractDataNodeMerge;
import org.opencloudb.mpp.ColMeta;
import org.opencloudb.mpp.DataMergeService;
import org.opencloudb.mpp.DataNodeMergeManager;
import org.opencloudb.mpp.MergeCol;
import org.opencloudb.mysql.LoadDataUtil;
import org.opencloudb.net.mysql.FieldPacket;
import org.opencloudb.net.mysql.OkPacket;
import org.opencloudb.net.mysql.ResultSetHeaderPacket;
import org.opencloudb.net.mysql.RowDataPacket;
import org.opencloudb.route.RouteResultset;
import org.opencloudb.route.RouteResultsetNode;
import org.opencloudb.server.NonBlockingSession;
import org.opencloudb.server.ServerConnection;
import org.opencloudb.server.parser.ServerParse;
import org.opencloudb.sqlfw.SQLFirewallServer;
import org.opencloudb.trace.SqlTraceDispatcher;

/**
 * @author mycat
 */
public class MultiNodeQueryHandler extends MultiNodeHandler implements
		LoadDataResponseHandler {
	private static final Logger LOGGER = Logger
			.getLogger(MultiNodeQueryHandler.class);

	private final RouteResultset rrs;
	private final NonBlockingSession session;
	// private final CommitNodeHandler icHandler;
	private final AbstractDataNodeMerge dataMergeSvr;
	private final boolean autocommit;
	private String priamaryKeyTable = null;
	private int primaryKeyIndex = -1;
	private int fieldCount = 0;
	private final ReentrantLock lock;
	private long affectedRows;
	private long insertId;
	private volatile boolean fieldsReturned;
	private int okCount;
	private final boolean isCallProcedure;
	private long startTime = 0L;
	private long endTime = 0L;
	private int execCount = 0;
	
	private int isOffHeapuseOffHeapForMerge = 1;
	/**
	 * Limit N，M
	 */
	private   int limitStart;
	private   int limitSize;

	private int index = 0;

	private int end = 0;
	/**limitsqlExecute为空说明不用限制并发查询*/
    private Semaphore limitsqlExecute = null;

	public MultiNodeQueryHandler(int sqlType, RouteResultset rrs,
			boolean autocommit, NonBlockingSession session) {
		super(session);
		if (rrs.getNodes() == null) {
			throw new IllegalArgumentException("routeNode is null!");
		}
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("execute mutinode query " + rrs.getStatement());
		}
		this.rrs = rrs;
		isOffHeapuseOffHeapForMerge = MycatServer.getInstance().
				getConfig().getSystem().getUseOffHeapForMerge();
		if (ServerParse.SELECT == sqlType && rrs.needMerge()) {
			/**
			 * 使用Off Heap
			 */
			if(isOffHeapuseOffHeapForMerge == 1){
				dataMergeSvr = new DataNodeMergeManager(this,rrs,session.getSource().getId());
			}else {
				dataMergeSvr = new DataMergeService(this,rrs);
			}
		} else {
			dataMergeSvr = null;
		}
		isCallProcedure = rrs.isCallStatement();
		this.autocommit = session.getSource().isAutocommit();
		this.session = session;
		this.lock = new ReentrantLock();
		// this.icHandler = new CommitNodeHandler(session);
		this.limitStart = rrs.getLimitStart();
		this.limitSize = rrs.getLimitSize();
		this.end = limitStart + rrs.getLimitSize();

		if (this.limitStart < 0)
			this.limitStart = 0;

		if (rrs.getLimitSize() < 0)
			end = Integer.MAX_VALUE;
		if (dataMergeSvr != null) {
			if (LOGGER.isDebugEnabled()) {
				LOGGER.debug("has data merge logic ");
			}
		}
	}

	protected void reset(int initCount) {
		super.reset(initCount);
		this.okCount = initCount;
		this.execCount = 0;
	}

	public NonBlockingSession getSession() {
		return session;
	}

	public void execute() throws Exception {
		final ReentrantLock lock = this.lock;
		lock.lock();
		try {
			this.reset(rrs.getNodes().length);
			this.fieldsReturned = false;
			this.affectedRows = 0L;
			this.insertId = 0L;
		} finally {
			lock.unlock();
		}
		MycatConfig conf = MycatServer.getInstance().getConfig();
		startTime = System.currentTimeMillis();
		for (final RouteResultsetNode node : rrs.getNodes()) {
			/**并发查询控制*/
			if (limitsqlExecute !=null) {
				limitsqlExecute.acquire();
			}
			BackendConnection conn = session.getTarget(node);
			if (session.tryExistsCon(conn, node)) {
				_execute(conn, node);
			} else {
				// create new connection
				PhysicalDBNode dn = conf.getDataNodes().get(node.getName());
				dn.getConnection(dn.getDatabase(), autocommit, node, this, node);
			}

		}
	}

	private void _execute(BackendConnection conn, RouteResultsetNode node) {
		if (clearIfSessionClosed(session)) {
			return;
		}
		conn.setResponseHandler(this);
		try {
			conn.execute(node, session.getSource(), autocommit);
		} catch (IOException e) {
			connectionError(e, conn);
		}
	}

	@Override
	public void connectionAcquired(final BackendConnection conn) {
		final RouteResultsetNode node = (RouteResultsetNode) conn
				.getAttachment();
		session.bindConnection(node, conn);
		_execute(conn, node);
	}

	private boolean decrementOkCountBy(int finished) {
		lock.lock();
		try {
			return --okCount == 0;
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void okResponse(byte[] data, BackendConnection conn) {
		SqlTraceDispatcher.traceBackendConn(session.getSource(), conn, "okResponse");
		/**并发查询信号量释放*/
		if (limitsqlExecute != null) {
			limitsqlExecute.release();
		}
		
		boolean executeResponse = conn.syncAndExcute();
		endTime = System.currentTimeMillis();
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("received ok response ,executeResponse:"
					+ executeResponse + " from " + conn);
		}
		if (executeResponse) {

			ServerConnection source = session.getSource();
			OkPacket ok = new OkPacket();
			ok.read(data);
            //存储过程
            boolean isCanClose2Client =(!rrs.isCallStatement()) ||(rrs.isCallStatement() &&!rrs.getProcedure().isResultSimpleValue());;
             if(!isCallProcedure)
             {
                 if (clearIfSessionClosed(session))
                 {
                     return;
                 } else if (canClose(conn, false))
                 {
                     return;
                 }
             }
			lock.lock();
			try {
				// 判断是否是全局表，如果是，执行行数不做累加，以最后一次执行的为准。
				if (!rrs.isGlobalTable()) {
					affectedRows += ok.affectedRows;
				} else {
					affectedRows = ok.affectedRows;
				}
				if (ok.insertId > 0) {
					insertId = (insertId == 0) ? ok.insertId : Math.min(
							insertId, ok.insertId);
				}
			} finally {
				lock.unlock();
			}

			// 对于存储过程，其比较特殊，查询结果返回EndRow报文以后，还会再返回一个OK报文，才算结束
			boolean isEndPacket = isCallProcedure ? decrementOkCountBy(1)
					: decrementCountBy(1);
			if (isEndPacket&&isCanClose2Client) {
				if (this.autocommit && !session.getSource().isLocked()) {// clear all connections
					session.releaseConnections(false);
				}
				if (this.isFail() || session.closed()) {
					tryErrorFinished(true);
					return;
				}
				lock.lock();
				try {
					if (rrs.isLoadData()) {
						byte lastPackId = source.getLoadDataInfileHandler()
								.getLastPackId();
						ok.packetId = ++lastPackId;// OK_PACKET
						ok.message = ("Records: " + affectedRows + "  Deleted: 0  Skipped: 0  Warnings: 0")
								.getBytes();// 此处信息只是为了控制台给人看的
						source.getLoadDataInfileHandler().clear();
					} else {
						ok.packetId = ++packetId;// OK_PACKET
					}

					ok.affectedRows = affectedRows;
					ok.serverStatus = source.isAutocommit() ? 2 : 1;
					if (insertId > 0) {
						ok.insertId = insertId;
						source.setLastInsertId(insertId);
					}
					ok.write(source);
					/**
					 * delete & update 统计SQL执行次数
					 */
					if(MycatServer.getInstance().getConfig().getSystem().getEnableSqlStat() == 1) {
						endTime = System.currentTimeMillis();
						sqlRecord(affectedRows);
					}
				} catch (Exception e) {
					handleDataProcessException(e);
				} finally {
					lock.unlock();
				}
			}
		}
	}

	@Override
	public void rowEofResponse(final byte[] eof, BackendConnection conn) {
		SqlTraceDispatcher.traceBackendConn(session.getSource(), conn, "rowEofResponse");
		/**并发查询信号量释放*/
		if (limitsqlExecute != null) {
			limitsqlExecute.release();
		}
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("on row end reseponse " + conn);
		}
		if (errorRepsponsed.get()) {
			// the connection has been closed or set to "txInterrupt" properly
			//in tryErrorFinished() method! If we close it here, it can
			// lead to tx error such as blocking rollback tx for ever.
			// @author Uncle-pan
			// @since 2016-03-25
			// conn.close(this.error);
			return;
		}

		final ServerConnection source = session.getSource();
        if (!rrs.isCallStatement()){
			if (clearIfSessionClosed(session)) {
				return;
			} else if (canClose(conn, false)) {
				return;
			}
		}

		if (decrementCountBy(1)) {
            if (!rrs.isCallStatement()||(rrs.isCallStatement()&&rrs.getProcedure().isResultSimpleValue())) {
				if (this.autocommit && !session.getSource().isLocked()) {// clear all connections
					session.releaseConnections(false);
				}

				if (this.isFail() || session.closed()) {
					tryErrorFinished(true);
					return;
				}
			}
			if (dataMergeSvr != null && !dataMergeSvr.isStreamOutputResult()) {
				try {
					dataMergeSvr.outputMergeResult(session, eof);
				} catch (Exception e) {
					handleDataProcessException(e);
				}
			} else {

				try {
					lock.lock();
					eof[3] = ++packetId;
					if (LOGGER.isDebugEnabled()) {
						LOGGER.debug("last packet id:" + packetId);
					}
					source.write(eof);
				} finally {
					lock.unlock();
				}
				/**
				 * 处理select 没有merge情况，更新rows
				 */
				if (MycatServer.getInstance().getConfig().getSystem().getEnableSqlStat() == 1) {
					updateResultRows(index);
				}
			}
		}
	}
	
	

	/**
	 * 将汇聚结果集数据真正的发送给Mycat客户端
	 * @param source
	 * @param eof
	 * @param
	 */
	public void outputMergeResult(final ServerConnection source, final byte[] eof, Iterator<UnsafeRow> iter) {
		try {
			lock.lock();
			ByteBuffer buffer = session.getSource().allocate();
			final RouteResultset rrs = this.dataMergeSvr.getRrs();

			/**
			 * 处理limit语句的start 和 end位置，将正确的结果发送给
			 * Mycat 客户端
			 */
			int start = rrs.getLimitStart();
			int end = start + rrs.getLimitSize();
			int index = 0;

			if (start < 0)
				start = 0;

			if (rrs.getLimitSize() < 0)
				end = Integer.MAX_VALUE;

			while (iter.hasNext()){

				UnsafeRow row = iter.next();

				if(index >= start){
					row.packetId = ++packetId;
					buffer = row.write(buffer,source,true);
				}

				index++;

				if(index == end){
					break;
				}
			}

			eof[3] = ++packetId;

			/**
			 * select --- > 更新rows
			 */
			if (MycatServer.getInstance().getConfig().getSystem().getEnableSqlStat() ==1) {
				updateResultRows(index);
			}


			/**
			 * 真正的开始把Writer Buffer的数据写入到channel 中
			 */
			source.write(source.writeToBuffer(eof, buffer));
		} catch (Exception e) {
			handleDataProcessException(e);
		} finally {
			lock.unlock();
			dataMergeSvr.clear();
		}
	}
	public void outputMergeResult(final ServerConnection source,
			final byte[] eof, List<RowDataPacket> results) {
		try {
			lock.lock();
			ByteBuffer buffer = session.getSource().allocate();
			final RouteResultset rrs = this.dataMergeSvr.getRrs();

			// 处理limit语句
			int start = rrs.getLimitStart();
			int end = start + rrs.getLimitSize();

                        if (start < 0)
				start = 0;

			if (rrs.getLimitSize() < 0)
				end = results.size();

			if (end > results.size())
				end = results.size();

			for (int i = start; i < end; i++) {
				RowDataPacket row = results.get(i);
				row.packetId = ++packetId;
				buffer = row.write(buffer, source, true);
			}

			eof[3] = ++packetId;
			if (LOGGER.isDebugEnabled()) {
				LOGGER.debug("last packet id:" + packetId);
			}
			source.write(source.writeToBuffer(eof, buffer));
		} catch (Exception e) {
			handleDataProcessException(e);
		} finally {
			lock.unlock();
			dataMergeSvr.clear();
		}
	}

	@Override
	public void fieldEofResponse(byte[] header, List<byte[]> fields,
			byte[] eof, BackendConnection conn) {
		ServerConnection source = null;
		execCount++;
		if (execCount == rrs.getNodes().length) {
			/**
			 * select 统计SQL执行情况,影响行数，在结果集输出时候记录
			 */
			if(MycatServer.getInstance().getConfig().getSystem().getEnableSqlStat()==1) {
				endTime = System.currentTimeMillis();
				sqlRecord(0);
			}
		}
		if (fieldsReturned) {
			return;
		}
		lock.lock();
		try {
			if (fieldsReturned) {
				return;
			}
			fieldsReturned = true;

			boolean needMerg = (dataMergeSvr != null)
					&& dataMergeSvr.getRrs().needMerge();
			Set<String> shouldRemoveAvgField = new HashSet<>();
			Set<String> shouldRenameAvgField = new HashSet<>();
			if (needMerg) {
				Map<String, Integer> mergeColsMap = dataMergeSvr.getRrs()
						.getMergeCols();
				if (mergeColsMap != null) {
					for (Map.Entry<String, Integer> entry : mergeColsMap
							.entrySet()) {
						String key = entry.getKey();
						int mergeType = entry.getValue();
						if (MergeCol.MERGE_AVG == mergeType
								&& mergeColsMap.containsKey(key + "SUM")) {
							shouldRemoveAvgField.add((key + "COUNT")
									.toUpperCase());
							shouldRenameAvgField.add((key + "SUM")
									.toUpperCase());
						}
					}
				}
			}
			source = session.getSource();
			ByteBuffer buffer = source.allocate();
			fieldCount = fields.size();
			if (shouldRemoveAvgField.size() > 0) {
				ResultSetHeaderPacket packet = new ResultSetHeaderPacket();
				packet.packetId = ++packetId;
				packet.fieldCount = fieldCount - shouldRemoveAvgField.size();
				buffer = packet.write(buffer, source, true);
			} else {
				header[3] = ++packetId;
				buffer = source.writeToBuffer(header, buffer);
			}

			String primaryKey = null;
			if (rrs.hasPrimaryKeyToCache()) {
				String[] items = rrs.getPrimaryKeyItems();
				priamaryKeyTable = items[0];
				primaryKey = items[1];
			}

			Map<String, ColMeta> columToIndx = new HashMap<String, ColMeta>(
					fieldCount);

			for (int i = 0, len = fieldCount; i < len; ++i) {
				boolean shouldSkip = false;
				byte[] field = fields.get(i);
				if (needMerg) {
					FieldPacket fieldPkg = new FieldPacket();
					fieldPkg.read(field);
					String fieldName = new String(fieldPkg.name).toUpperCase();
					if (columToIndx != null
							&& !columToIndx.containsKey(fieldName)) {
						if (shouldRemoveAvgField.contains(fieldName)) {
							shouldSkip = true;
						}
						if (shouldRenameAvgField.contains(fieldName)) {
							String newFieldName = fieldName.substring(0,
									fieldName.length() - 3);
							fieldPkg.name = newFieldName.getBytes();
							fieldPkg.packetId = ++packetId;
							// process avg field precision and scale
 							fieldPkg.length = fieldPkg.length - 14;
 							fieldPkg.decimals = (byte) (fieldPkg.decimals + 4);
							shouldSkip = true;
							buffer = fieldPkg.write(buffer, source, false);
							// recovery field scale
							fieldPkg.decimals = (byte) (fieldPkg.decimals - 4);
						}
						
						ColMeta colMeta = new ColMeta(i, fieldPkg.type);
						colMeta.decimals = fieldPkg.decimals;
						colMeta.precision = (int) fieldPkg.length;
						columToIndx.put(fieldName, colMeta);
					}
				} else if (primaryKey != null && primaryKeyIndex == -1) {
					// find primary key index
					FieldPacket fieldPkg = new FieldPacket();
					fieldPkg.read(field);
					String fieldName = new String(fieldPkg.name);
					if (primaryKey.equalsIgnoreCase(fieldName)) {
						primaryKeyIndex = i;
						fieldCount = fields.size();
					}
				}
				if (!shouldSkip) {
					field[3] = ++packetId;
					buffer = source.writeToBuffer(field, buffer);
				}
			}
			eof[3] = ++packetId;
			buffer = source.writeToBuffer(eof, buffer);
			source.write(buffer);
			if (dataMergeSvr != null) {
				dataMergeSvr.onRowMetaData(columToIndx, fieldCount);
			}
		} catch (Exception e) {
			handleDataProcessException(e);
		} finally {
			lock.unlock();
		}
	}

	public void handleDataProcessException(Exception e) {
		if (!errorRepsponsed.get()) {
			this.error = e.toString();
			LOGGER.warn("caught exception ", e);
			setFail(e.toString());
			this.tryErrorFinished(true);
		}
	}

	@Override
	public void rowResponse(final byte[] row, final BackendConnection conn) {
		if (errorRepsponsed.get()) {
			// the connection has been closed or set to "txInterrupt" properly
			//in tryErrorFinished() method! If we close it here, it can
			// lead to tx error such as blocking rollback tx for ever.
			// @author Uncle-pan
			// @since 2016-03-25
			//conn.close(error);
			return;
		}
		lock.lock();
		try {
			RouteResultsetNode rNode = (RouteResultsetNode) conn
					.getAttachment();
			String dataNode = rNode.getName();
			if (dataMergeSvr != null && !dataMergeSvr.isStreamOutputResult()) {
				// even through discarding the all rest data, we can't
				//close the connection for tx control such as rollback or commit.
				// So the "isClosedByDiscard" variable is unnecessary.
				// @author Uncle-pan
				// @since 2016-03-25
				dataMergeSvr.onNewRecord(dataNode, row);
			} else {
				// cache primaryKey-> dataNode
				if (primaryKeyIndex != -1) {
					RowDataPacket rowDataPkg = new RowDataPacket(fieldCount);
					rowDataPkg.read(row);
					String primaryKey = new String(
							rowDataPkg.fieldValues.get(primaryKeyIndex));
					LayerCachePool pool = MycatServer.getInstance()
							.getRouterservice().getTableId2DataNodeCache();
					pool.putIfAbsent(priamaryKeyTable, primaryKey, dataNode);
				}
				/**
				 * 流式处理Limit语句
				 */
				if(index >= limitStart && index <=end) {
					row[3] = ++packetId;
					session.getSource().write(row);
				}
				index++;
			}

		} catch (Exception e) {
			handleDataProcessException(e);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void clearResources() {
		if (dataMergeSvr != null) {
			dataMergeSvr.clear();
		}
	}

	@Override
	public void writeQueueAvailable() {

	}

	@Override
	public void requestDataResponse(byte[] data, BackendConnection conn) {
		LoadDataUtil.requestFileDataResponse(data, conn);
	}

	/**
	 * 记录SQL执行情况
	 * @param rows sql操作影响rows
	 */
	public void sqlRecord(long rows){
		SQLFirewallServer sqlFirewallServer = MycatServer.getInstance().getSqlFirewallServer();
		SQLRecord sqlRecord = sqlFirewallServer.getSQLRecord(rrs.getStatement());
		if(sqlRecord != null) {
			sqlRecord.setUser(session.getSource().getUser());
			sqlRecord.setHost(session.getSource().getHost());
			sqlRecord.setSchema(session.getSource().getSchema());
			String tables = null;
			int size = rrs.getTables().size();
			for (int i = 0; i <size; i++) {
				if(i == size-1){
					tables = rrs.getTables().get(i).toLowerCase();
				}else {
					tables = rrs.getTables().get(i).toLowerCase() + ",";
				}
			}
			sqlRecord.setTables(tables);
			sqlRecord.setSqlType(rrs.getSqlType());
			sqlRecord.setStartTime(startTime);
			sqlRecord.setEndTime(endTime);
			sqlRecord.setSqlExecTime(endTime-startTime);
			sqlFirewallServer.updateSqlRecord(rrs.getStatement(),sqlRecord);
			sqlFirewallServer.getUpdateH2DBService().
					submit(new SQLFirewallServer.Task<SQLRecord>(sqlRecord,OP_UPATE));

			if (LOGGER.isDebugEnabled()){
				LOGGER.debug(sqlRecord.toString());
			}
		}
	}

	/**
	 * 最后更新affectedRows到sqlRecord中
	 * @param affectedRows sql操作影响rows
	 */
	public void updateResultRows(int affectedRows){
		SQLFirewallServer sqlFirewallServer = MycatServer.getInstance().getSqlFirewallServer();
		SQLRecord sqlRecord = sqlFirewallServer.getSQLRecord(rrs.getStatement());


		if(sqlRecord != null) {
			sqlRecord.setResultRows(affectedRows);
			sqlFirewallServer.updateSqlRecord(rrs.getStatement(), sqlRecord);
			sqlFirewallServer.getUpdateH2DBService().
					submit(new SQLFirewallServer.Task<SQLRecord>(sqlRecord,OP_UPATE_ROW));
			if (LOGGER.isDebugEnabled()){
				LOGGER.debug(sqlRecord.toString());
			}
		}else {
			sqlRecord = new SQLRecord(SQLFirewallServer.DEFAULT_TIMEOUT);
			sqlRecord = sqlRecord.query(rrs.getStatement());
			sqlRecord.setResultRows(affectedRows);
			sqlFirewallServer.updateSqlRecord(rrs.getStatement(),sqlRecord);
			sqlFirewallServer.getUpdateH2DBService().
					submit(new SQLFirewallServer.Task<SQLRecord>(sqlRecord,OP_UPATE_ROW));

			if (LOGGER.isDebugEnabled()){
				LOGGER.debug(sqlRecord.toString());
			}
		}

	}

}
