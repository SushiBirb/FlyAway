package net.eastern.FlyAway.dbm;

import net.eastern.FlyAway.util.Utils;

import java.sql.*;
import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Database Manager and Connection Pool.
 *
 * Provides connection pooling and dual-engine compatibility for MySQL/MariaDB
 * and embedded SQLite. Manages schema migrations, indexes, and prepared statement execution.
 */
public class Dbm {
    private static final int MAX_POOL_SIZE = 16;
    private static String dbUrl = "jdbc:sqlite:flyaway.db";
    private static String dbUser = null;
    private static String dbPass = null;
    private static boolean isMySql = false;

    private static final BlockingQueue<Connection> pool = new ArrayBlockingQueue<>(MAX_POOL_SIZE);
    private static volatile boolean initialized = false;

    public Dbm() {
        if (!initialized) {
            synchronized (Dbm.class) {
                if (!initialized) {
                    configureDatabase();
                    initSchema();

                    for (int i = 0; i < MAX_POOL_SIZE; i++) {
                        try {
                            pool.offer(createConnection());
                        } catch (SQLException e) {
                            Utils.Errprintln("Failed to create pool connection: " + e.getMessage());
                        }
                    }
                    initialized = true;
                    Utils.Infoprintln("DB connection pool initialized (" + pool.size() + " connections) using " + (isMySql ? "MySQL/MariaDB" : "SQLite"));
                }
            }
        }
    }

    private static void configureDatabase() {
        String envHost = System.getenv("DB_HOST");
        String envUrl = System.getenv("DB_URL");

        if (envUrl != null && !envUrl.isEmpty()) {
            dbUrl = envUrl;
            dbUser = System.getenv("DB_USER");
            dbPass = System.getenv("DB_PASSWORD");
            isMySql = dbUrl.startsWith("jdbc:mysql:");
        } else if (envHost != null && !envHost.isEmpty()) {
            String port = System.getenv("DB_PORT") != null ? System.getenv("DB_PORT") : "3306";
            String dbName = System.getenv("DB_DATABASE") != null ? System.getenv("DB_DATABASE") : "flyaway";
            dbUrl = "jdbc:mysql://" + envHost + ":" + port + "/" + dbName
                    + "?autoReconnect=true&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=10000";
            dbUser = System.getenv("DB_USER") != null ? System.getenv("DB_USER") : "flyaway";
            dbPass = System.getenv("DB_PASSWORD") != null ? System.getenv("DB_PASSWORD") : "flyawaypass";
            isMySql = true;
        }

        try {
            if (isMySql) {
                Class.forName("com.mysql.cj.jdbc.Driver");
                Utils.Infoprintln("Loaded MySQL JDBC Driver for " + dbUrl);
            } else {
                Class.forName("org.sqlite.JDBC");
                Utils.Infoprintln("Loaded SQLite JDBC Driver for " + dbUrl);
            }
        } catch (ClassNotFoundException e) {
            Utils.Errprintln("Database driver not found: " + e.getMessage());
        }
    }

    private static Connection createConnection() throws SQLException {
        if (dbUser != null && dbPass != null) {
            return DriverManager.getConnection(dbUrl, dbUser, dbPass);
        }
        return DriverManager.getConnection(dbUrl);
    }

    private static void initSchema() {
        try (Connection conn = createConnection();
             Statement stmt = conn.createStatement()) {

            if (isMySql) {
                stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                        "studentid INT PRIMARY KEY, " +
                        "exitallowed BOOLEAN NOT NULL DEFAULT FALSE" +
                        ")");

                stmt.execute("CREATE TABLE IF NOT EXISTS RECORDS (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "sid INT NOT NULL, " +
                        "timestamp VARCHAR(64) NOT NULL, " +
                        "result VARCHAR(32) NOT NULL" +
                        ")");

                stmt.execute("CREATE TABLE IF NOT EXISTS tokens (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "token VARCHAR(64) UNIQUE NOT NULL, " +
                        "status VARCHAR(32) NOT NULL, " +
                        "admin INT NOT NULL DEFAULT 0, " +
                        "sessionid VARCHAR(64), " +
                        "creationdate VARCHAR(64) NOT NULL, " +
                        "expdate VARCHAR(64), " +
                        "owner VARCHAR(64)" +
                        ")");

                stmt.execute("CREATE TABLE IF NOT EXISTS accts (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "un VARCHAR(64) UNIQUE NOT NULL, " +
                        "password VARCHAR(255) NOT NULL, " +
                        "permsum INT NOT NULL DEFAULT 777, " +
                        "creationdate VARCHAR(64) NOT NULL, " +
                        "lastlogin VARCHAR(64)" +
                        ")");
            } else {
                stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                        "studentid INTEGER PRIMARY KEY, " +
                        "exitallowed INTEGER NOT NULL DEFAULT 0" +
                        ")");

                stmt.execute("CREATE TABLE IF NOT EXISTS RECORDS (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "sid INTEGER NOT NULL, " +
                        "timestamp TEXT NOT NULL, " +
                        "result TEXT NOT NULL" +
                        ")");

                stmt.execute("CREATE TABLE IF NOT EXISTS tokens (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "token TEXT UNIQUE NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "admin INTEGER NOT NULL DEFAULT 0, " +
                        "sessionid TEXT, " +
                        "creationdate TEXT NOT NULL, " +
                        "expdate TEXT, " +
                        "owner TEXT" +
                        ")");

                stmt.execute("CREATE TABLE IF NOT EXISTS accts (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "un TEXT UNIQUE NOT NULL, " +
                        "password TEXT NOT NULL, " +
                        "permsum INTEGER NOT NULL DEFAULT 777, " +
                        "creationdate TEXT NOT NULL, " +
                        "lastlogin TEXT" +
                        ")");
            }

            // Create performance indexes across both MySQL/MariaDB and SQLite
            try {
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_records_timestamp ON RECORDS(timestamp)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_records_sid ON RECORDS(sid)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_tokens_token ON tokens(token)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_accts_un ON accts(un)");
            } catch (SQLException ex) {
                // If dialect does not support IF NOT EXISTS on index, continue gracefully
                Utils.Debugprintln("Index creation notice: " + ex.getMessage());
            }

            // Create default admin account if accts is empty
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM accts")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    String defaultPassHash = net.eastern.FlyAway.auth.PasswordHasher.hash("8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918");
                    String nowStr = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    try (PreparedStatement insertStmt = conn.prepareStatement(
                            "INSERT INTO accts (un, password, permsum, creationdate, lastlogin) VALUES (?, ?, 777, ?, NULL)")) {
                        insertStmt.setString(1, "admin");
                        insertStmt.setString(2, defaultPassHash);
                        insertStmt.setString(3, nowStr);
                        insertStmt.executeUpdate();
                        Utils.Infoprintln("Database initialized with default admin account (user: admin, pass: admin)");
                    }
                }
            }
            Utils.Infoprintln("Database schema and indexes verified.");
        } catch (SQLException e) {
            Utils.Errprintln("Failed to initialize database schema: " + e.getMessage());
        }
    }

    /**
     * Obtains a connection from the pool, testing its validity.
     * If the pooled connection is invalid, a fresh connection is spawned.
     */
    public Connection getConnection() throws SQLException {
        try {
            Connection conn = pool.poll();
            if (conn != null && conn.isValid(2)) {
                return new PooledConnection(conn, pool);
            }
            if (conn != null) {
                try { conn.close(); } catch (SQLException ignored) {}
            }
            Connection fresh = createConnection();
            return new PooledConnection(fresh, pool);
        } catch (Exception e) {
            throw new SQLException("Connection pool exhausted", e);
        }
    }

    public DbmResponse executeSQL(Connection conn, DbmQueryType qtype, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            if (qtype == DbmQueryType.QUERY) {
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    Utils.Debugprintln("[DBM] Query Executed");

                    int times = 0;
                    ArrayList<String> records = new ArrayList<>();
                    ResultSetMetaData md = rs.getMetaData();
                    int colCount = md.getColumnCount();

                    // Print header
                    StringBuilder header = new StringBuilder();
                    for (int i = 1; i <= colCount; i++) {
                        header.append(md.getColumnLabel(i)).append(i < colCount ? "\t" : "");
                    }
                    records.add(header.toString());

                    while (rs.next()) {
                        times++;
                        StringBuilder strresponse = new StringBuilder();
                        for (int i = 1; i <= colCount; i++) {
                            strresponse.append(rs.getString(i)).append(i < colCount ? "\t" : "");
                        }
                        records.add(strresponse.toString());
                    }

                    if (times == 0) {
                        return new DbmResponse(DbmResponseType.ResponseEmpty);
                    } else if (times == 1) {
                        return new DbmResponse(DbmResponseType.OneResponse, new String[]{ records.get(1) });
                    } else {
                        return new DbmResponse(DbmResponseType.ResponseList, records.size(), records);
                    }
                }
            } else if (qtype == DbmQueryType.UPDATE) {
                int affected = stmt.executeUpdate(sql);
                return new DbmResponse(DbmResponseType.OneResponse, new String[]{ "Rows affected: " + affected });
            }
            return new DbmResponse(DbmResponseType.ResponseEmpty);
        }
    }

    /**
     * Pooled Connection wrapper that returns connections to the pool upon close(),
     * preventing resource leaks.
     */
    private static class PooledConnection implements Connection {
        private final Connection delegate;
        private final BlockingQueue<Connection> returnTo;
        private boolean closed = false;

        PooledConnection(Connection delegate, BlockingQueue<Connection> returnTo) {
            this.delegate = delegate;
            this.returnTo = returnTo;
        }

        @Override
        public void close() throws SQLException {
            if (!closed) {
                closed = true;
                // If pool is full, close the physical connection to prevent socket leak
                if (!returnTo.offer(delegate)) {
                    try {
                        delegate.close();
                    } catch (SQLException ignored) {}
                }
            }
        }

        @Override
        public boolean isClosed() throws SQLException {
            return closed || delegate.isClosed();
        }

        // Delegate everything else
        @Override public Statement createStatement() throws SQLException { return delegate.createStatement(); }
        @Override public PreparedStatement prepareStatement(String sql) throws SQLException { return delegate.prepareStatement(sql); }
        @Override public CallableStatement prepareCall(String sql) throws SQLException { return delegate.prepareCall(sql); }
        @Override public String nativeSQL(String sql) throws SQLException { return delegate.nativeSQL(sql); }
        @Override public void setAutoCommit(boolean autoCommit) throws SQLException { delegate.setAutoCommit(autoCommit); }
        @Override public boolean getAutoCommit() throws SQLException { return delegate.getAutoCommit(); }
        @Override public void commit() throws SQLException { delegate.commit(); }
        @Override public void rollback() throws SQLException { delegate.rollback(); }
        @Override public DatabaseMetaData getMetaData() throws SQLException { return delegate.getMetaData(); }
        @Override public void setReadOnly(boolean readOnly) throws SQLException { delegate.setReadOnly(readOnly); }
        @Override public boolean isReadOnly() throws SQLException { return delegate.isReadOnly(); }
        @Override public void setCatalog(String catalog) throws SQLException { delegate.setCatalog(catalog); }
        @Override public String getCatalog() throws SQLException { return delegate.getCatalog(); }
        @Override public void setTransactionIsolation(int level) throws SQLException { delegate.setTransactionIsolation(level); }
        @Override public int getTransactionIsolation() throws SQLException { return delegate.getTransactionIsolation(); }
        @Override public SQLWarning getWarnings() throws SQLException { return delegate.getWarnings(); }
        @Override public void clearWarnings() throws SQLException { delegate.clearWarnings(); }
        @Override public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException { return delegate.createStatement(resultSetType, resultSetConcurrency); }
        @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException { return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency); }
        @Override public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException { return delegate.prepareCall(sql, resultSetType, resultSetConcurrency); }
        @Override public Map<String, Class<?>> getTypeMap() throws SQLException { return delegate.getTypeMap(); }
        @Override public void setTypeMap(Map<String, Class<?>> map) throws SQLException { delegate.setTypeMap(map); }
        @Override public void setHoldability(int holdability) throws SQLException { delegate.setHoldability(holdability); }
        @Override public int getHoldability() throws SQLException { return delegate.getHoldability(); }
        @Override public Savepoint setSavepoint() throws SQLException { return delegate.setSavepoint(); }
        @Override public Savepoint setSavepoint(String name) throws SQLException { return delegate.setSavepoint(name); }
        @Override public void rollback(Savepoint savepoint) throws SQLException { delegate.rollback(savepoint); }
        @Override public void releaseSavepoint(Savepoint savepoint) throws SQLException { delegate.releaseSavepoint(savepoint); }
        @Override public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { return delegate.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { return delegate.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException { return delegate.prepareStatement(sql, autoGeneratedKeys); }
        @Override public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException { return delegate.prepareStatement(sql, columnIndexes); }
        @Override public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException { return delegate.prepareStatement(sql, columnNames); }
        @Override public Clob createClob() throws SQLException { return delegate.createClob(); }
        @Override public Blob createBlob() throws SQLException { return delegate.createBlob(); }
        @Override public NClob createNClob() throws SQLException { return delegate.createNClob(); }
        @Override public SQLXML createSQLXML() throws SQLException { return delegate.createSQLXML(); }
        @Override public boolean isValid(int timeout) throws SQLException { return delegate.isValid(timeout); }
        @Override public void setClientInfo(String name, String value) throws SQLClientInfoException { delegate.setClientInfo(name, value); }
        @Override public void setClientInfo(Properties properties) throws SQLClientInfoException { delegate.setClientInfo(properties); }
        @Override public String getClientInfo(String name) throws SQLException { return delegate.getClientInfo(name); }
        @Override public Properties getClientInfo() throws SQLException { return delegate.getClientInfo(); }
        @Override public Array createArrayOf(String typeName, Object[] elements) throws SQLException { return delegate.createArrayOf(typeName, elements); }
        @Override public Struct createStruct(String typeName, Object[] attributes) throws SQLException { return delegate.createStruct(typeName, attributes); }
        @Override public void setSchema(String schema) throws SQLException { delegate.setSchema(schema); }
        @Override public String getSchema() throws SQLException { return delegate.getSchema(); }
        @Override public void abort(java.util.concurrent.Executor executor) throws SQLException { delegate.abort(executor); }
        @Override public void setNetworkTimeout(java.util.concurrent.Executor executor, int milliseconds) throws SQLException { delegate.setNetworkTimeout(executor, milliseconds); }
        @Override public int getNetworkTimeout() throws SQLException { return delegate.getNetworkTimeout(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
    }
}
