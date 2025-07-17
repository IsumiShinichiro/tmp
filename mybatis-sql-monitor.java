// 1. SQL类型枚举
package com.example.monitor.enums;

public enum SqlType {
    SELECT("查询"),
    INSERT("插入"),
    UPDATE("更新"),
    DELETE("删除"),
    PROCEDURE("存储过程"),
    OTHER("其他");
    
    private final String description;
    
    SqlType(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
}

// 2. 监控配置类
package com.example.monitor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.HashSet;

@Component
@ConfigurationProperties(prefix = "mybatis.monitor")
public class SqlMonitorProperties {
    
    private boolean enabled = true;
    private boolean logSql = true;
    private boolean logParameters = true;
    private boolean logResult = false;
    private long slowSqlThreshold = 1000; // 慢SQL阈值，单位毫秒
    private Set<SqlType> monitorTypes = new HashSet<>();
    
    public SqlMonitorProperties() {
        // 默认监控所有类型
        monitorTypes.add(SqlType.SELECT);
        monitorTypes.add(SqlType.INSERT);
        monitorTypes.add(SqlType.UPDATE);
        monitorTypes.add(SqlType.DELETE);
        monitorTypes.add(SqlType.PROCEDURE);
    }
    
    // Getters and Setters
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public boolean isLogSql() {
        return logSql;
    }
    
    public void setLogSql(boolean logSql) {
        this.logSql = logSql;
    }
    
    public boolean isLogParameters() {
        return logParameters;
    }
    
    public void setLogParameters(boolean logParameters) {
        this.logParameters = logParameters;
    }
    
    public boolean isLogResult() {
        return logResult;
    }
    
    public void setLogResult(boolean logResult) {
        this.logResult = logResult;
    }
    
    public long getSlowSqlThreshold() {
        return slowSqlThreshold;
    }
    
    public void setSlowSqlThreshold(long slowSqlThreshold) {
        this.slowSqlThreshold = slowSqlThreshold;
    }
    
    public Set<SqlType> getMonitorTypes() {
        return monitorTypes;
    }
    
    public void setMonitorTypes(Set<SqlType> monitorTypes) {
        this.monitorTypes = monitorTypes;
    }
}

// 3. MyBatis拦截器实现
package com.example.monitor.interceptor;

import com.example.monitor.config.SqlMonitorProperties;
import com.example.monitor.enums.SqlType;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Intercepts({
    @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
    @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
})
public class SqlMonitorInterceptor implements Interceptor {
    
    private static final Logger logger = LoggerFactory.getLogger(SqlMonitorInterceptor.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    
    @Autowired
    private SqlMonitorProperties properties;
    
    // 用于存储SQL执行统计信息
    private final Map<String, SqlStatistics> statisticsMap = new ConcurrentHashMap<>();
    
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (!properties.isEnabled()) {
            return invocation.proceed();
        }
        
        MappedStatement mappedStatement = (MappedStatement) invocation.getArgs()[0];
        Object parameter = invocation.getArgs()[1];
        
        SqlType sqlType = getSqlType(mappedStatement);
        
        // 检查是否需要监控该类型的SQL
        if (!properties.getMonitorTypes().contains(sqlType)) {
            return invocation.proceed();
        }
        
        String sqlId = mappedStatement.getId();
        BoundSql boundSql = mappedStatement.getBoundSql(parameter);
        String sql = formatSql(boundSql.getSql());
        
        long startTime = System.currentTimeMillis();
        Object result = null;
        Throwable error = null;
        
        try {
            result = invocation.proceed();
            return result;
        } catch (Throwable e) {
            error = e;
            throw e;
        } finally {
            long endTime = System.currentTimeMillis();
            long executionTime = endTime - startTime;
            
            // 记录SQL执行信息
            logSqlExecution(sqlId, sql, sqlType, parameter, boundSql, executionTime, result, error);
            
            // 更新统计信息
            updateStatistics(sqlId, sqlType, executionTime);
        }
    }
    
    private SqlType getSqlType(MappedStatement mappedStatement) {
        SqlCommandType commandType = mappedStatement.getSqlCommandType();
        StatementType statementType = mappedStatement.getStatementType();
        
        // 检查是否是存储过程
        if (statementType == StatementType.CALLABLE) {
            return SqlType.PROCEDURE;
        }
        
        switch (commandType) {
            case SELECT:
                return SqlType.SELECT;
            case INSERT:
                return SqlType.INSERT;
            case UPDATE:
                return SqlType.UPDATE;
            case DELETE:
                return SqlType.DELETE;
            default:
                return SqlType.OTHER;
        }
    }
    
    private void logSqlExecution(String sqlId, String sql, SqlType sqlType, Object parameter, 
                                BoundSql boundSql, long executionTime, Object result, Throwable error) {
        
        StringBuilder logBuilder = new StringBuilder("\n");
        logBuilder.append("================== SQL监控信息 ==================\n");
        logBuilder.append("执行时间: ").append(LocalDateTime.now().format(DATE_FORMAT)).append("\n");
        logBuilder.append("SQL ID: ").append(sqlId).append("\n");
        logBuilder.append("SQL类型: ").append(sqlType.getDescription()).append("\n");
        logBuilder.append("执行耗时: ").append(executionTime).append(" ms\n");
        
        if (executionTime > properties.getSlowSqlThreshold()) {
            logBuilder.append("【慢SQL警告】执行时间超过阈值: ").append(properties.getSlowSqlThreshold()).append(" ms\n");
        }
        
        if (properties.isLogSql()) {
            logBuilder.append("SQL语句: ").append(sql).append("\n");
        }
        
        if (properties.isLogParameters() && boundSql.getParameterMappings() != null) {
            logBuilder.append("参数列表: ").append(getParameterValues(boundSql, parameter)).append("\n");
        }
        
        if (error != null) {
            logBuilder.append("执行异常: ").append(error.getMessage()).append("\n");
        }
        
        if (properties.isLogResult() && result != null) {
            if (result instanceof List) {
                logBuilder.append("结果集大小: ").append(((List<?>) result).size()).append("\n");
            } else {
                logBuilder.append("影响行数: ").append(result).append("\n");
            }
        }
        
        logBuilder.append("=================================================");
        
        if (executionTime > properties.getSlowSqlThreshold() || error != null) {
            logger.warn(logBuilder.toString());
        } else {
            logger.info(logBuilder.toString());
        }
    }
    
    private String getParameterValues(BoundSql boundSql, Object parameterObject) {
        List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        if (parameterMappings == null || parameterMappings.isEmpty()) {
            return "[]";
        }
        
        List<Object> values = new ArrayList<>();
        if (parameterObject == null) {
            values.add(null);
        } else if (parameterObject instanceof Map) {
            Map<?, ?> paramMap = (Map<?, ?>) parameterObject;
            for (ParameterMapping pm : parameterMappings) {
                values.add(paramMap.get(pm.getProperty()));
            }
        } else {
            for (ParameterMapping pm : parameterMappings) {
                String propertyName = pm.getProperty();
                Object value = null;
                try {
                    Field field = parameterObject.getClass().getDeclaredField(propertyName);
                    field.setAccessible(true);
                    value = field.get(parameterObject);
                } catch (Exception e) {
                    value = "获取失败";
                }
                values.add(value);
            }
        }
        
        return values.toString();
    }
    
    private void updateStatistics(String sqlId, SqlType sqlType, long executionTime) {
        statisticsMap.compute(sqlId, (key, stats) -> {
            if (stats == null) {
                stats = new SqlStatistics(sqlId, sqlType);
            }
            stats.addExecution(executionTime);
            return stats;
        });
    }
    
    private String formatSql(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
    
    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }
    
    @Override
    public void setProperties(Properties properties) {
        // 可以在这里设置一些自定义属性
    }
    
    // 获取统计信息的方法
    public Map<String, SqlStatistics> getStatistics() {
        return new HashMap<>(statisticsMap);
    }
    
    // 清空统计信息
    public void clearStatistics() {
        statisticsMap.clear();
    }
    
    // SQL统计信息内部类
    public static class SqlStatistics {
        private final String sqlId;
        private final SqlType sqlType;
        private long totalExecutions = 0;
        private long totalTime = 0;
        private long minTime = Long.MAX_VALUE;
        private long maxTime = 0;
        
        public SqlStatistics(String sqlId, SqlType sqlType) {
            this.sqlId = sqlId;
            this.sqlType = sqlType;
        }
        
        public synchronized void addExecution(long executionTime) {
            totalExecutions++;
            totalTime += executionTime;
            minTime = Math.min(minTime, executionTime);
            maxTime = Math.max(maxTime, executionTime);
        }
        
        public double getAverageTime() {
            return totalExecutions > 0 ? (double) totalTime / totalExecutions : 0;
        }
        
        // Getters
        public String getSqlId() { return sqlId; }
        public SqlType getSqlType() { return sqlType; }
        public long getTotalExecutions() { return totalExecutions; }
        public long getTotalTime() { return totalTime; }
        public long getMinTime() { return minTime == Long.MAX_VALUE ? 0 : minTime; }
        public long getMaxTime() { return maxTime; }
    }
}

// 4. 自动配置类
package com.example.monitor.config;

import com.example.monitor.interceptor.SqlMonitorInterceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.util.List;

@Configuration
@EnableConfigurationProperties(SqlMonitorProperties.class)
@ConditionalOnProperty(prefix = "mybatis.monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SqlMonitorAutoConfiguration {
    
    @Autowired
    private List<SqlSessionFactory> sqlSessionFactories;
    
    @Autowired
    private SqlMonitorInterceptor sqlMonitorInterceptor;
    
    @PostConstruct
    public void addInterceptor() {
        for (SqlSessionFactory sqlSessionFactory : sqlSessionFactories) {
            sqlSessionFactory.getConfiguration().addInterceptor(sqlMonitorInterceptor);
        }
    }
}

// 5. 监控统计Controller（可选）
package com.example.monitor.controller;

import com.example.monitor.interceptor.SqlMonitorInterceptor;
import com.example.monitor.interceptor.SqlMonitorInterceptor.SqlStatistics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/sql-monitor")
public class SqlMonitorController {
    
    @Autowired
    private SqlMonitorInterceptor sqlMonitorInterceptor;
    
    @GetMapping("/statistics")
    public Map<String, SqlStatistics> getStatistics() {
        return sqlMonitorInterceptor.getStatistics();
    }
    
    @PostMapping("/clear")
    public String clearStatistics() {
        sqlMonitorInterceptor.clearStatistics();
        return "统计信息已清空";
    }
}

// 6. application.yml配置示例
/*
mybatis:
  monitor:
    enabled: true                    # 是否启用监控
    log-sql: true                   # 是否打印SQL语句
    log-parameters: true            # 是否打印参数
    log-result: false               # 是否打印结果
    slow-sql-threshold: 1000        # 慢SQL阈值（毫秒）
    monitor-types:                  # 要监控的SQL类型
      - SELECT
      - INSERT
      - UPDATE
      - DELETE
      - PROCEDURE
*/

// 7. 使用示例 - Mapper接口
package com.example.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface UserMapper {
    
    // 普通查询
    @Select("SELECT * FROM users WHERE id = #{id}")
    User findById(Long id);
    
    // 调用存储过程示例
    @Select("{CALL get_user_info(#{userId, mode=IN, jdbcType=BIGINT}, " +
            "#{userName, mode=OUT, jdbcType=VARCHAR}, " +
            "#{userAge, mode=OUT, jdbcType=INTEGER})}")
    @Options(statementType = StatementType.CALLABLE)
    void getUserInfo(Map<String, Object> params);
    
    // 批量插入
    @Insert({
        "<script>",
        "INSERT INTO users (name, age) VALUES ",
        "<foreach collection='users' item='user' separator=','>",
        "(#{user.name}, #{user.age})",
        "</foreach>",
        "</script>"
    })
    int batchInsert(@Param("users") List<User> users);
}

// 8. 使用示例 - 实体类
package com.example.entity;

public class User {
    private Long id;
    private String name;
    private Integer age;
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }
}