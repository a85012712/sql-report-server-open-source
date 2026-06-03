package com.report.model;
import com.fasterxml.jackson.annotation.*;
@JsonIgnoreProperties(ignoreUnknown = true)
public class DataSourceInfo {
    private String id,name,type,url,username,password,driverClassName,description;
    private boolean active=true;private long createTime,updateTime;
    public DataSourceInfo(){this.createTime=this.updateTime=System.currentTimeMillis();}
    public String getId(){return id;}public void setId(String v){this.id=v;}
    public String getName(){return name;}public void setName(String v){this.name=v;}
    public String getType(){return type;}public void setType(String v){this.type=v;if(driverClassName==null||driverClassName.isEmpty())driverClassName=gd(v);}
    public String getUrl(){return url;}public void setUrl(String v){this.url=v;}
    public String getUsername(){return username;}public void setUsername(String v){this.username=v;}
    // 密码序列化策略：输出时返回"****"遮罩，输入时接受明文，防止真实密码泄露
    @JsonIgnore public String getPassword(){return password;}  // 阻止Jackson默认getter序列化真实密码
    @JsonProperty("password") public void setPassword(String p){if(p!=null&&p.chars().allMatch(c->c=='*'))return;this.password=p;}  // 反序列化接收密码，忽略遮罩值
    @JsonProperty("password") public String getMaskedPassword(){return(password==null||password.isEmpty())?"":"****";}  // 序列化输出遮罩
    public String getDriverClassName(){return driverClassName;}public void setDriverClassName(String v){this.driverClassName=v;}
    public boolean isActive(){return active;}public void setActive(boolean v){this.active=v;}
    public String getDescription(){return description;}public void setDescription(String v){this.description=v;}
    public long getCreateTime(){return createTime;}public void setCreateTime(long v){this.createTime=v;}
    public long getUpdateTime(){return updateTime;}public void setUpdateTime(long v){this.updateTime=v;}
    private String gd(String t){if(t==null)return"oracle.jdbc.OracleDriver";switch(t.toLowerCase()){case"mysql":return"com.mysql.cj.jdbc.Driver";case"postgresql":return"org.postgresql.Driver";case"sqlserver":return"com.microsoft.sqlserver.jdbc.SQLServerDriver";default:return"oracle.jdbc.OracleDriver";}}
}
