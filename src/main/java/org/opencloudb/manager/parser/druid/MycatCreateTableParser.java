package org.opencloudb.manager.parser.druid;

import org.opencloudb.manager.parser.druid.statement.MycatCreateChildTableStatement;
import org.opencloudb.manager.parser.druid.statement.MycatCreateTableStatement;

import com.alibaba.druid.sql.ast.expr.SQLCharExpr;
import com.alibaba.druid.sql.parser.ParserException;
import com.alibaba.druid.sql.parser.SQLDDLParser;
import com.alibaba.druid.sql.parser.SQLExprParser;
import com.alibaba.druid.sql.parser.Token;

/**
 * 解析Mycat create table|childtable 语句的解析器
 * @author CrazyPig
 * @since 2017-02-06
 *
 */
public class MycatCreateTableParser extends SQLDDLParser {

	public MycatCreateTableParser(String sql) {
		super(sql);
	}
	
	public MycatCreateTableParser(SQLExprParser exprParser){
        super(exprParser);
    }
	
	/**
	 * create table 语句解析
	 * @param acceptCreate
	 * @return
	 */
	public MycatCreateTableStatement parseCreateTable(boolean acceptCreate) {
		if(acceptCreate) {
			accept(Token.CREATE);
		}
		accept(Token.TABLE);
		MycatCreateTableStatement stmt = new MycatCreateTableStatement();
		stmt.setTable(exprParser.name());
		Token token = lexer.token();
		if(token == Token.IN) {
			lexer.nextToken();
			stmt.setSchema(exprParser.name());
		}
		
		for(;;) {
			
			if(identifierEquals("global")) {
				lexer.nextToken();
				accept(Token.EQ);
				if(lexer.token() == Token.TRUE) {
					lexer.nextToken();
					stmt.setGlobal(true);
				} else if(lexer.token() == Token.FALSE) {
					lexer.nextToken();
					stmt.setGlobal(false);
				} else {
					throw new ParserException("global must be true or false");
				}
				continue;
			}
			
			if(identifierEquals("primaryKey")) {
				lexer.nextToken();
				accept(Token.EQ);
				stmt.setPrimaryKey(this.exprParser.expr());
				continue;
			}
			
			if(identifierEquals("dataNode")) {
				lexer.nextToken();
				accept(Token.EQ);
				stmt.setDataNodes(this.exprParser.expr());
				continue;
			}
			
			if(identifierEquals("rule")) {
				lexer.nextToken();
				accept(Token.EQ);
				stmt.setRule(this.exprParser.expr());
				continue;
			}
			
			break;
		}
		
		
		
		// 语义检查
		
		if(stmt.isGlobal() && (stmt.getRule() != null)) {
			throw new ParserException("global table can not provide rule");
		}
		
		if(stmt.getDataNodes() == null) {
			throw new ParserException("table definition must provide dataNode property, eg: dataNode = \"${dataNode_split_by_comma}\"");
		} else {
			String dataNodes = ((SQLCharExpr)stmt.getDataNodes()).getText();
			if(dataNodes.split(",").length > 1 && !stmt.isGlobal()) {
				if(stmt.getRule() == null) {
					throw new ParserException("table shard in more than one node must provide rule property, eg: rule = \"${rule_name}\"");
				}
			}
		}
		
		return stmt;
	}
	
	/**
	 * create childtable 语句解析
	 * @param acceptCreate
	 * @return
	 */
	public MycatCreateChildTableStatement parseCreateChildTable(boolean acceptCreate) {
		if(acceptCreate) {
			accept(Token.CREATE);
		}
		acceptIdentifier("CHILDTABLE");
		MycatCreateChildTableStatement stmt = new MycatCreateChildTableStatement();
		stmt.setTable(exprParser.name());
		if(lexer.token() == Token.IN) {
			lexer.nextToken();
			stmt.setSchema(exprParser.name());
		}
		
		for(;;) {
		
			if(identifierEquals("parent")) {
				lexer.nextToken();
				accept(Token.EQ);
				stmt.setParentTable(this.exprParser.expr());
				continue;
			}
			
			if(identifierEquals("parentKey")) {
				lexer.nextToken();
				accept(Token.EQ);
				stmt.setParentKey(this.exprParser.expr());
				continue;
			}
			
			if(identifierEquals("joinKey")) {
				lexer.nextToken();
				accept(Token.EQ);
				stmt.setJoinKey(this.exprParser.expr());
				continue;
			}
			
			if(identifierEquals("primaryKey")) {
				lexer.nextToken();
				accept(Token.EQ);
				stmt.setPrimaryKey(this.exprParser.expr());
				continue;
			}
			
			break;
		}
		
		// 语义检查
		
		if(stmt.getParentTable() == null) {
			throw new ParserException("childtable definition must provide parent property, eg: parent = ${parent_table_name}");
		}
		
		if(stmt.getParentKey() == null) {
			throw new ParserException("childtable definition must provide parentKey property, eg: parentKey = ${parentKey}");
		}
		
		if(stmt.getJoinKey() == null) {
			throw new ParserException("childtable definition must provide joinKey property, eg: joinKey = ${joinKey}");
		}
		
		return stmt;
	}

}
