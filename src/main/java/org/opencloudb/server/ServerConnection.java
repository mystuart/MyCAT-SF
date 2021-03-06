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
package org.opencloudb.server;

import java.io.IOException;
import java.nio.channels.NetworkChannel;

import org.apache.log4j.Logger;
import org.opencloudb.MycatServer;
import org.opencloudb.config.ErrorCode;
import org.opencloudb.config.model.FirewallConfig;
import org.opencloudb.config.model.SchemaConfig;
import org.opencloudb.net.FrontendConnection;
import org.opencloudb.route.RouteResultset;
import org.opencloudb.server.handler.InformationSchemaHandler;
import org.opencloudb.server.handler.MysqlProcHandler;
import org.opencloudb.server.parser.ServerParse;
import org.opencloudb.server.response.Heartbeat;
import org.opencloudb.server.response.Ping;
import org.opencloudb.server.util.SchemaUtil;
import org.opencloudb.sqlfw.SQLFirewallServer;
import org.opencloudb.util.SplitUtil;
import org.opencloudb.util.TimeUtil;

/**
 * @author mycat
 */
public class ServerConnection extends FrontendConnection {
	private static final Logger LOGGER = Logger
			.getLogger(ServerConnection.class);
	private static final long AUTH_TIMEOUT = 15 * 1000L;

	private volatile int txIsolation;
	private volatile boolean autocommit;
	private volatile boolean txInterrupted;
	private volatile String txInterrputMsg = "";
	private long lastInsertId;
	private NonBlockingSession session;

	/**
	 * 同一个事务内执行show,desc命令将随机下发，改为跟上次sql执行的路由节点。
	 */
	private String inTransactionSingleRouteDataNode = null;
	
	/**
	 * 标志是否执行了lock tables语句，并处于lock状态
	 */
	private volatile boolean isLocked = false;

	public ServerConnection(NetworkChannel channel)
			throws IOException {
		super(channel);
		this.txInterrupted = false;
		this.autocommit = true;
	}

	@Override
	public boolean isIdleTimeout() {
		if (isAuthenticated) {
			return super.isIdleTimeout();
		} else {
			return TimeUtil.currentTimeMillis() > Math.max(lastWriteTime,
					lastReadTime) + AUTH_TIMEOUT;
		}
	}

	public int getTxIsolation() {
		return txIsolation;
	}

	public void setTxIsolation(int txIsolation) {
		this.txIsolation = txIsolation;
	}

	public boolean isAutocommit() {
		return autocommit;
	}

	public void setAutocommit(boolean autocommit) {
		this.autocommit = autocommit;
	}

	public long getLastInsertId() {
		return lastInsertId;
	}

	public void setLastInsertId(long lastInsertId) {
		this.lastInsertId = lastInsertId;
	}

	/**
	 * 设置是否需要中断当前事务
	 */
	public void setTxInterrupt(String txInterrputMsg) {
		if (!autocommit && !txInterrupted) {
			txInterrupted = true;
			this.txInterrputMsg = txInterrputMsg;
		}
	}

	public boolean isTxInterrupted()
	{
		return txInterrupted;
	}
	public NonBlockingSession getSession2() {
		return session;
	}

	public void setSession2(NonBlockingSession session2) {
		this.session = session2;
	}

	public boolean isLocked() {
		return isLocked;
	}

	public void setLocked(boolean isLocked) {
		this.isLocked = isLocked;
	}

	@Override
	public void ping() {
		Ping.response(this);
	}

	@Override
	public void heartbeat(byte[] data) {
		Heartbeat.response(this, data);
	}

	public void execute(String sql, int type) {
		if (this.isClosed()) {
			LOGGER.warn("ignore execute ,server connection is closed " + this);
			return;
		}
		// 状态检查
		if (txInterrupted) {
			writeErrMessage(ErrorCode.ER_YES,
					"Transaction error, need to rollback." + txInterrputMsg);
			return;
		}

		// 检查当前使用的DB
		String db = this.schema;
		if (db == null) {

            db = SchemaUtil.detectDefaultDb(sql, type);

            if(db==null)
            {
                writeErrMessage(ErrorCode.ERR_BAD_LOGICDB,
                        "No MyCAT Database selected");
                return;
            }
		}

        if(ServerParse.SELECT==type&&sql.contains("mysql")&&sql.contains("proc"))
        {
            SchemaUtil.SchemaInfo schemaInfo = SchemaUtil.parseSchema(sql);
            if(schemaInfo!=null&&"mysql".equalsIgnoreCase(schemaInfo.schema)&&"proc".equalsIgnoreCase(schemaInfo.table))
            {
                //兼容MySQLWorkbench
                MysqlProcHandler.handle(sql,this);
                return;
            }
        }
        if(ServerParse.SELECT == type) {
        	// 解决navicat等数据库管理工具发送查询infomation_schema相关表信息抛错的异常
        	if(sql.contains("information_schema") || sql.contains("INFORMATION_SCHEMA")) {
	        	InformationSchemaHandler.handle(sql, this);
	        	return;
        	}
        	/*
        	 *  navicat在查询里面执行select查询单表以后会发送SELECT * FROM `dbname`.`tablename` LIMIT 0 语句
        	 *  这里使用正则表达式匹配并移除相应的dbname
        	 *  [注意] 这里的dbname是分库真实的dbname, 不去除会在后面的路由模块抛no route异常
        	 */
        	if(sql.matches("SELECT \\* FROM `.*`\\.`.*` LIMIT 0")) {
        		sql = sql.replaceFirst("`.*`\\.", "");
        	}
        }
		SchemaConfig schema = MycatServer.getInstance().getConfig()
				.getSchemas().get(db);
		if (schema == null) {
			writeErrMessage(ErrorCode.ERR_BAD_LOGICDB,
					"Unknown MyCAT Database '" + db + "'");
			return;
		}

		routeEndExecuteSQL(sql, type, schema);

	}



    public RouteResultset routeSQL(String sql, int type) {

		// 检查当前使用的DB
		String db = this.schema;
		if (db == null) {
			writeErrMessage(ErrorCode.ERR_BAD_LOGICDB,
					"No MyCAT Database selected");
			return null;
		}
		SchemaConfig schema = MycatServer.getInstance().getConfig()
				.getSchemas().get(db);
		if (schema == null) {
			writeErrMessage(ErrorCode.ERR_BAD_LOGICDB,
					"Unknown MyCAT Database '" + db + "'");
			return null;
		}

		// 路由计算
		RouteResultset rrs = null;
		try {
			rrs = MycatServer
					.getInstance()
					.getRouterservice()
					.route(MycatServer.getInstance().getConfig().getSystem(),
							schema, type, sql, this.charset, this);

		} catch (Exception e) {
			StringBuilder s = new StringBuilder();
			LOGGER.warn(s.append(this).append(sql).toString() + " err:" + e.toString(),e);
			String msg = e.getMessage();
			writeErrMessage(ErrorCode.ER_PARSE_ERROR, msg == null ? e.getClass().getSimpleName() : msg);
			return null;
		}
		return rrs;
	}


	public String getInTransactionSingleRouteDataNode() {
		return inTransactionSingleRouteDataNode;
	}

	public void setInTransactionSingleRouteDataNode(String inTransactionSingleRouteDataNode) {
		this.inTransactionSingleRouteDataNode = inTransactionSingleRouteDataNode;
	}



	public void routeEndExecuteSQL(String sql, int type, SchemaConfig schema) {
		// 路由计算
		RouteResultset rrs = null;
		try {
			rrs = MycatServer
					.getInstance()
					.getRouterservice()
					.route(MycatServer.getInstance().getConfig().getSystem(),
							schema, type, sql, this.charset, this);

		} catch (Exception e) {
			StringBuilder s = new StringBuilder();
			LOGGER.warn(s.append(this).append(sql).toString() + " err:" + e.toString(),e);
			String msg = e.getMessage();
			writeErrMessage(ErrorCode.ER_PARSE_ERROR, msg == null ? e.getClass().getSimpleName() : msg);
			return;
		}
		SQLFirewallServer sqlFirewallServer = MycatServer.getInstance().getSqlFirewallServer();
		FirewallConfig firewallConf = MycatServer.getInstance().getConfig().getFirewall();

		int enableSQLFirewall = firewallConf.getEnableSQLFirewall();
		/**
		 * 
		 * sql 语句拦截
		 * 1.基于sql blacklist 拦截 完整的sql 或者 sql正则表达式拦截
		 * 2.基于结果集合和执行频度拦截
		 */
		if(enableSQLFirewall >= 0) {
			if (sqlFirewallServer.sqlMatcher(sql)) {
				if (enableSQLFirewall == 1) {
					/**记录到sql_reporter中*/
					sqlFirewallServer.recordSQLReporter(sql,"sql exists in the blacklist.!".toUpperCase());
					writeErrMessage(ErrorCode.ER_NOT_ALLOWED_COMMAND, "'" + sql.toUpperCase() + "' exists in the blacklist.".toUpperCase());
					return;
				}
				if (enableSQLFirewall == 2) {
					sqlFirewallServer.recordSQLReporter(sql, "sql exists in the blacklist.!".toUpperCase());
				}
				if (enableSQLFirewall == 0) {
					LOGGER.warn("'" + sql.toUpperCase() + "' exists in the blacklist.".toUpperCase());
				}
			}
		}

		if (rrs != null) {
			// session执行
			if ((type == ServerParse.SELECT ||
				type == ServerParse.UPDATE ||
				type == ServerParse.INSERT ||
				type == ServerParse.DELETE) && !isAutocommit()) {
				setInTransactionSingleRouteDataNode(rrs.getNodes()[0].getName());
			}
			session.execute(rrs, type);
		}
	}

	/**
	 * 提交事务
	 */
	public void commit() {
		if (txInterrupted) {
			writeErrMessage(ErrorCode.ER_YES,
					"Transaction error, need to rollback.");
		} else {
			session.commit();
		}
	}

	/**
	 * 回滚事务
	 */
	public void rollback() {
		// 状态检查
		if (txInterrupted) {
			txInterrupted = false;
		}

		// 执行回滚
		session.rollback();
	}
	
	/**
	 * 执行lock tables语句方法
	 * @author songdabin
	 * @date 2016-7-9
	 * @param sql
	 */
	public void lockTable(String sql) {
		// 事务中不允许执行lock table语句
		if (!autocommit) {
			writeErrMessage(ErrorCode.ER_YES, "can't lock table in transaction!");
			return;
		}
		// 已经执行了lock table且未执行unlock table之前的连接不能再次执行lock table命令
		if (isLocked) {
			writeErrMessage(ErrorCode.ER_YES, "can't lock multi-table");
			return;
		}
		RouteResultset rrs = routeSQL(sql, ServerParse.LOCK);
		if (rrs != null) {
			session.lockTable(rrs);
		}
	}
	
	/**
	 * 执行unlock tables语句方法
	 * @author songdabin
	 * @date 2016-7-9
	 * @param sql
	 */
	public void unLockTable(String sql) {
		sql = sql.replaceAll("\n", " ").replaceAll("\t", " ");
		String[] words = SplitUtil.split(sql, ' ', true);
		if (words.length==2 && ("table".equalsIgnoreCase(words[1]) || "tables".equalsIgnoreCase(words[1]))) {
			isLocked = false;
			session.unLockTable(sql);
		} else {
			writeErrMessage(ErrorCode.ER_UNKNOWN_COM_ERROR, "Unknown command");
		}
		
	}

	/**
	 * 撤销执行中的语句
	 * 
	 * @param sponsor
	 *            发起者为null表示是自己
	 */
	public void cancel(final FrontendConnection sponsor) {
		processor.getExecutor().execute(new Runnable() {
			@Override
			public void run() {
				session.cancel(sponsor);
			}
		});
	}

	@Override
	public void close(String reason) {
		super.close(reason);
		session.terminate();
		if(getLoadDataInfileHandler()!=null)
		{
			getLoadDataInfileHandler().clear();
		}
	}

	@Override
	public String toString() {
		return "ServerConnection [id=" + id + ", schema=" + schema + ", host="
				+ host + ", user=" + user + ",txIsolation=" + txIsolation
				+ ", autocommit=" + autocommit + ", schema=" + schema + "]";
	}

}