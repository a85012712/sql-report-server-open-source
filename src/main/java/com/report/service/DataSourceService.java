package com.report.service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.report.model.DataSourceInfo;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;import jakarta.annotation.PreDestroy;
import javax.sql.DataSource;import java.io.*;import java.sql.*;import java.util.*;
import java.util.concurrent.*;
@Service
public class DataSourceService {
    private static final Logger log=LoggerFactory.getLogger(DataSourceService.class);
    @Value("${app.datasource.config-file:./data/datasources.json}") private String configFile;
    @Value("${app.datasource.default-id:default}") private volatile String defaultId;
    private final ObjectMapper om=new ObjectMapper();
    private final Map<String,DataSourceInfo> dsMap=new ConcurrentHashMap<>();
    private final Map<String,HikariDataSource> pools=new ConcurrentHashMap<>();
    private final ScheduledExecutorService sched=Executors.newScheduledThreadPool(2);
    @PostConstruct void init(){loadConfig();initPools();}
    @PreDestroy void destroy(){sched.shutdown();for(var e:pools.entrySet()){try{e.getValue().close();}catch(Exception x){}}pools.clear();}
    private void initPools(){for(var e:dsMap.entrySet()){try{pools.put(e.getKey(),mkPool(e.getValue()));}catch(Exception x){}}}
    private HikariDataSource mkPool(DataSourceInfo d){HikariConfig c=new HikariConfig();c.setPoolName("DS-"+d.getId());c.setJdbcUrl(rv(d.getUrl()));c.setUsername(rv(d.getUsername()));c.setPassword(rv(d.getPassword()));c.setDriverClassName(d.getDriverClassName());c.setMaximumPoolSize(25);c.setMinimumIdle(5);c.setMaxLifetime(600000);c.setLeakDetectionThreshold(120000);String t=d.getType();c.setConnectionTestQuery((t==null||t.equalsIgnoreCase("oracle"))?"SELECT 1 FROM DUAL":"SELECT 1");return new HikariDataSource(c);}
    public DataSource getDataSource(String id){if(id==null||id.isEmpty())id=defaultId;HikariDataSource p=pools.get(id);if(p==null)throw new IllegalArgumentException("DataSource not found: "+id);return p;}
    private void loadConfig(){File f=new File(configFile);if(!f.exists()){mkDefault();saveCfg();return;}try{Map<String,Object>r=om.readValue(f,new TypeReference<Map<String,Object>>(){});if(r.containsKey("datasources")){String sid=(String)r.get("defaultId");if(sid!=null&&!sid.isEmpty())defaultId=sid;List<DataSourceInfo>ls=om.convertValue(r.get("datasources"),new TypeReference<List<DataSourceInfo>>(){});for(DataSourceInfo d:ls)dsMap.put(d.getId(),d);}else throw new IOException("old");}catch(Exception e){try{List<DataSourceInfo>ls=om.readValue(f,new TypeReference<List<DataSourceInfo>>(){});for(DataSourceInfo d:ls)dsMap.put(d.getId(),d);}catch(IOException x){mkDefault();}}if(!dsMap.containsKey(defaultId))mkDefault();}
    private void mkDefault(){DataSourceInfo d=new DataSourceInfo();d.setId(defaultId);d.setName("HIS");d.setType("oracle");d.setUrl("${DB_URL}");d.setUsername("${DB_USERNAME}");d.setPassword("${DB_PASSWORD}");d.setActive(true);dsMap.put(defaultId,d);}
    private synchronized void saveCfg(){try{File f=new File(configFile);f.getParentFile().mkdirs();Map<String,Object>c=new HashMap<>();c.put("defaultId",defaultId);c.put("datasources",new ArrayList<>(dsMap.values()));om.writerWithDefaultPrettyPrinter().writeValue(f,c);}catch(IOException e){log.error("save failed",e);}}
    public List<DataSourceInfo> getAll(){return new ArrayList<>(dsMap.values());}
    public DataSourceInfo getById(String id){return dsMap.get(id);}
    public DataSourceInfo add(DataSourceInfo d){if(d.getId()==null||d.getId().isEmpty())d.setId(UUID.randomUUID().toString().substring(0,8));if(dsMap.containsKey(d.getId()))throw new IllegalArgumentException("ID exists");if(d.getDriverClassName()==null||d.getDriverClassName().isEmpty())d.setType(d.getType());d.setCreateTime(System.currentTimeMillis());dsMap.put(d.getId(),d);saveCfg();try{pools.put(d.getId(),mkPool(d));}catch(Exception x){}return d;}
    public DataSourceInfo update(String id,DataSourceInfo d){if(!dsMap.containsKey(id))throw new IllegalArgumentException("Not found");d.setId(id);d.setUpdateTime(System.currentTimeMillis());dsMap.put(id,d);saveCfg();HikariDataSource op=pools.remove(id);if(op!=null)try{op.close();}catch(Exception x){}try{pools.put(id,mkPool(d));}catch(Exception x){}return d;}
    public boolean delete(String id){if(defaultId.equals(id))throw new IllegalArgumentException("Cannot delete default");if(dsMap.remove(id)!=null){saveCfg();HikariDataSource p=pools.remove(id);if(p!=null)try{p.close();}catch(Exception x){}return true;}return false;}
    public Map<String,Object> testConnection(String id){DataSourceInfo d=dsMap.get(id);if(d==null)throw new IllegalArgumentException("Not found");return testConn(d);}
    private static final Set<String>OK=Set.of("oracle.jdbc.OracleDriver","com.mysql.cj.jdbc.Driver","org.postgresql.Driver","com.microsoft.sqlserver.jdbc.SQLServerDriver");
    public Map<String,Object> testConn(DataSourceInfo d){Map<String,Object>r=new HashMap<>();try{if(!OK.contains(d.getDriverClassName())){r.put("success",false);r.put("message","Bad driver");return r;}Class.forName(d.getDriverClassName());try(Connection c=DriverManager.getConnection(rv(d.getUrl()),rv(d.getUsername()),rv(d.getPassword()))){r.put("success",true);r.put("message","OK");}}catch(Exception e){r.put("success",false);r.put("message",e.getMessage());}return r;}
    private String rv(String v){if(v==null||!v.contains("$"+"{"))return v;int idx=v.indexOf("$"+"{");String prefix=v.substring(0,idx);var m=java.util.regex.Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)(?::([^}]*))?").matcher(v.substring(idx+2));var sb=new StringBuffer();while(m.find()){String n=m.group(1),df=m.group(2)!=null?m.group(2):"";String r=System.getenv(n);if(r==null)r=System.getProperty(n);if(r==null)r=df;m.appendReplacement(sb,java.util.regex.Matcher.quoteReplacement(r));}m.appendTail(sb);return prefix+sb.toString();}
    public void setDefault(String id){if(!dsMap.containsKey(id))throw new IllegalArgumentException("Not found");this.defaultId=id;saveCfg();}
    public String getDefaultId(){return defaultId;}
}
