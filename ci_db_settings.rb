MYSQL               = "mysql:mysql-connector-java:jar:5.1.31",
SYBASE              = "sybase:jconnect:jar:4.3",
DB2                 = "db2:jcc:jar:9.5"
SQLSERVER2008       = "microsoft:sqljdbc:jar:2008"

REQUIRES = [MYSQL, SYBASE, DB2, SQLSERVER2008]

def settings
  jpa_derby = {
    :db=>"derby",
    :dao=>"org.apache.ode.axis2.instancecleanup.JpaDaoConnectionFactoryImpl"
  }

  hib_derby = {
    :db=>"derby",
    :dao=>"org.apache.ode.axis2.instancecleanup.HibDaoConnectionFactoryImpl"
  }

  local_hib_mysql = {
    :integr_branch=>"5.2.x",
    :db=>"mysql",
    :schema_url=>"http://ode.apache.org/sql/ode-1.3.4-hib-mysql55.sql",
    :driver=>"com.mysql.jdbc.Driver",
    :url=>"jdbc:mysql://localhost:3306/hib?autoReconnect=true",
    :userid=>"root",
    :password=>"",
    :autocommit=>"off",
    :dao=>"org.apache.ode.axis2.instancecleanup.HibDaoConnectionFactoryImpl"
  }

  local_jpa_mysql = {
    :integr_branch=>"5.2.x",
    :db=>"mysql",
    :schema_url=>"http://ode.apache.org/sql/ode-1.3.4-jpa-mysql55.sql",
    :driver=>"com.mysql.jdbc.Driver",
    :url=>"jdbc:mysql://localhost:3306/jpa?autoReconnect=true",
    :userid=>"root",
    :password=>"",
    :autocommit=>"off",
    :dao=>"org.apache.ode.axis2.instancecleanup.JpaDaoConnectionFactoryImpl"
  }

  hib_db2_95 = {
    :integr_branch=>"5.2.x",
    :db=>"db2",
    :driver=>"com.ibm.db2.jcc.DB2Driver",
    :url=>"jdbc:db2://<host>:50000/<instance>",
    :userid=>"<userid>",
    :password=>"<password>",
    :autocommit=>"on",
    :dao=>"org.apache.ode.axis2.instancecleanup.HibDaoConnectionFactoryImpl"
  }

  hib_sybase_12 = {
    :integr_branch=>"5.2.x",
    :db=>"sybase",
    :driver=>"com.sybase.jdbc3.jdbc.SybDriver",
    :url=>"jdbc:sybase:Tds:<host>:6000/<instance>?SELECT_OPENS_CURSOR=true",
    :userid=>"<userid>",
    :password=>"<password>",
    :autocommit=>"on",
    :dao=>"org.apache.ode.axis2.instancecleanup.HibDaoConnectionFactoryImpl"
  }

  hib_sqlserver = {
    :integr_branch=>"5.2.x",
    :db=>"sqlserver",
    :driver=>"com.microsoft.sqlserver.jdbc.SQLServerDriver",
    :url=>"jdbc:sqlserver://<host>:1433;database=<instance>",
    :userid=>"sa",
    :password=>"sa",
    :autocommit=>"on",
    :dao=>"org.apache.ode.axis2.instancecleanup.HibDaoConnectionFactoryImpl"
  }

  {
#    "jpa-derby"=>jpa_derby,
#    "hib-derby"=>hib_derby
    "jpa-mysql"=>local_jpa_mysql,
    "hib-mysql"=>local_hib_mysql
#    "hib-db2"=>pxetest_hib_db2_95,
#    "hib-sybase"=>pxetest_hib_sybase_12
  }
end
