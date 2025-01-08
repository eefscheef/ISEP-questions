import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.hibernate.SessionFactory
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.cfg.Configuration
import javax.sql.DataSource
import ut.isep.management.model.entity.*

interface DatabaseConfigProvider {
    fun getJdbcUrl(): String
    fun getUsername(): String
    fun getPassword(): String
    fun isTestEnvironment(): Boolean
}

class AzureDatabaseConfigProvider : DatabaseConfigProvider {
    override fun getJdbcUrl(): String = System.getenv("JDBC_URL")
        ?: "jdbc:postgresql://isep-test-database.postgres.database.azure.com:5432/postgres?sslmode=require"
    override fun getUsername(): String = System.getenv("DB_USERNAME") ?: "default_user"
    override fun getPassword(): String = System.getenv("DB_PASSWORD") ?: "default_password"
    override fun isTestEnvironment(): Boolean = false
}



class DatabaseConfiguration(private val configProvider: DatabaseConfigProvider) {
    fun createDataSource(): DataSource {
        val config = HikariConfig().apply {
            jdbcUrl = configProvider.getJdbcUrl()
            driverClassName = "org.postgresql.Driver"
            username = configProvider.getUsername()
            password = configProvider.getPassword()
            maximumPoolSize = if (configProvider.isTestEnvironment()) 5 else 10
        }
        return HikariDataSource(config)
    }

    fun createSessionFactory(dataSource: DataSource): SessionFactory {
        val configuration = Configuration().apply {
            setProperty("hibernate.hbm2ddl.auto", "update")
            setProperty("hibernate.show_sql", "true")
            setProperty("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl")
            setProperty("hibernate.implicit_naming_strategy", "org.hibernate.boot.model.naming.ImplicitNamingStrategyJpaCompliantImpl")

            addAnnotatedClass(Applicant::class.java)
            addAnnotatedClass(Assessment::class.java)
            addAnnotatedClass(Assignment::class.java)
            addAnnotatedClass(Invite::class.java)
            addAnnotatedClass(Section::class.java)
            addAnnotatedClass(SolvedAssignment::class.java)
            addAnnotatedClass(SolvedAssignmentCoding::class.java)
            addAnnotatedClass(SolvedAssignmentMultipleChoice::class.java)
            addAnnotatedClass(SolvedAssignmentOpen::class.java)
        }

        val serviceRegistry = StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.datasource", dataSource)
            .applySettings(configuration.properties)
            .build()

        return configuration.buildSessionFactory(serviceRegistry)
    }
}
