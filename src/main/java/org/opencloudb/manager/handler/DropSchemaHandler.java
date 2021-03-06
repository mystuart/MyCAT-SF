package org.opencloudb.manager.handler;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.opencloudb.MycatConfig;
import org.opencloudb.MycatServer;
import org.opencloudb.config.ErrorCode;
import org.opencloudb.config.loader.xml.jaxb.SchemaJAXB;
import org.opencloudb.config.loader.xml.jaxb.UserJAXB;
import org.opencloudb.config.model.SchemaConfig;
import org.opencloudb.config.model.UserConfig;
import org.opencloudb.config.util.ConfigTar;
import org.opencloudb.config.util.JAXBUtil;
import org.opencloudb.manager.ManagerConnection;
import org.opencloudb.manager.parser.druid.statement.MycatDropSchemaStatement;
import org.opencloudb.net.mysql.OkPacket;
import org.opencloudb.util.StringUtil;

/**
 * drop schema 逻辑处理器
 * @author CrazyPig
 * @since 2017-02-16
 *
 */
public class DropSchemaHandler {
	
	private static final Logger LOGGER = Logger.getLogger(DropSchemaHandler.class);
	
	public static void handle(ManagerConnection c, MycatDropSchemaStatement stmt, String sql) {
		
		// 限制非内置root用户无法执行drop schema
		if(!DynamicConfigPrivilegesHandler.isPrivilegesOk(c)) {
			c.writeErrMessage(ErrorCode.ER_ACCESS_DENIED_ERROR, "This command can only be used with build-in root user");
			return ;
		}
		
		String schemaName = StringUtil.removeBackquote(stmt.getSchema().toString());
		MycatConfig mycatConf = MycatServer.getInstance().getConfig();
		mycatConf.getLock().lock();
		
		try {
			c.setLastOperation("drop schema " + schemaName); // 记录操作
			
			Map<String, SchemaConfig> schemas = mycatConf.getSchemas();
			// 检查schema是否存在
			if(!schemas.containsKey(schemaName)) {
				c.writeErrMessage(ErrorCode.ERR_FOUND_EXCEPION, 
						"schema '" + schemaName + "' dosen't exist");
				return ;
			}
			
			// 对于引用该schema的用户, 需要删除对应schema集合中该schema
			boolean needFlushRule = false;
			Map<String, UserConfig> users = mycatConf.getUsers();
			List<UserConfig> needFlushUsers = new ArrayList<UserConfig>();
			for(UserConfig userConf : users.values()) {
				if(userConf.getSchemas().contains(schemaName)) {
					userConf.getSchemas().remove(schemaName);
					needFlushUsers.add(userConf);
					needFlushRule = true;
				}
			} 
			
			SchemaConfig delSchema = schemas.remove(schemaName);
			
			// 刷新 schema.xml
			SchemaJAXB schemaJAXB = JAXBUtil.toSchemaJAXB(schemas);
			
			if(!JAXBUtil.flushSchema(schemaJAXB)) {
				// 出错回滚
				schemas.put(schemaName, delSchema);
				for(UserConfig needFlushUser : needFlushUsers) {
					needFlushUser.getSchemas().add(schemaName);
				}
				c.writeErrMessage(ErrorCode.ERR_FOUND_EXCEPION, "flush schema.xml fail");
				return ;
			}
			
			// 刷新 user.xml
			UserJAXB userJAXB = JAXBUtil.toUserJAXB(users, true);
			
			if(needFlushRule && (!JAXBUtil.flushUser(userJAXB))) {
				// 出错回滚
				schemas.put(schemaName, delSchema);
				for(UserConfig needFlushUser : needFlushUsers) {
					needFlushUser.getSchemas().add(schemaName);
				}
				c.writeErrMessage(ErrorCode.ERR_FOUND_EXCEPION, "flush user.xml fail");
				return ;
			}
			
			// 删除的schema为当前连接使用的schema, 将当前连接的schema置空
			if(schemaName.equals(c.getSchema())) {
				c.setSchema(null);
			}
			
			// 对配置信息进行备份
			try {
				ConfigTar.tarConfig(c.getLastOperation());
			} catch (Exception e) {
				throw new Exception("Fail to do backup.");
			}
			
			// 响应客户端
			ByteBuffer buffer = c.allocate();
			c.write(c.writeToBuffer(OkPacket.OK, buffer));
			
		} catch(Exception e) {
			c.setLastOperation("drop schema " + schemaName); // 记录操作
			
			LOGGER.error(e.getMessage(), e);
			c.writeErrMessage(ErrorCode.ERR_FOUND_EXCEPION, e.getMessage());
		} finally {
			mycatConf.getLock().unlock();
		}
		
	}

}
