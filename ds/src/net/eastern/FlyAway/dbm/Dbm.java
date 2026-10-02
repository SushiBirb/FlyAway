package net.eastern.FlyAway.dbm;

import net.eastern.FlyAway.util.Utils;

import java.sql.*;
import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class Dbm {
    private static final int MAX_POOL_SIZE = 10;
    private static final String URL = "jdbc:sqlite:flyaway.db";

    private static final BlockingQueue<Connection> pool = new ArrayBlockingQueue<>(MAX_POOL_SIZE);
    private static boolean initialized = false;

    public Dbm() {
        if (!initialized) {
            synchronized (Dbm.class) {
                if (!initialized) {
                    try {
                        Class.forName("org.sqlite.JDBC");
                    } catch (ClassNotFoundException e) {
                        Utils.Errprintln("SQLite JDBC driver not found: " + e.getMessage());
                    }

                    initSchema();

                    for (int i = 0; i < MAX_POOL_SIZE; i++) {
                        try {
                            pool.offer(DriverManager.getConnection(URL));
                        } catch (SQLException e) {
                            Utils.Errprintln("Failed to create pool connection: " + e.getMessage());
                        }
                    }
                    initialized = true;
                    Utils.Infoprintln("DB connection pool initialized (" + pool.size() + " connections)");
                }
            }
        }
    }

    private static void initSchema() {
        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement()) {
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

            // Create default admin account if accts is empty
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM accts");
            if (rs.next() && rs.getInt(1) == 0) {
                // SHA-256("admin") is 8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918
                String defaultPassHash = net.eastern.FlyAway.auth.PasswordHasher.hash("8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918");
                try (PreparedStatement insertStmt = conn.prepareStatement(
                        "INSERT INTO accts (un, password, permsum, creationdate, lastlogin) VALUES (?, ?, 777, datetime('now'), NULL)")) {
                    insertStmt.setString(1, "admin");
                    insertStmt.setString(2, defaultPassHash);
                    insertStmt.executeUpdate();
                    Utils.Infoprintln("Database initialized with default admin account (user: admin, pass: admin)");
                }
            }
            rs.close();
            Utils.Infoprintln("Database schema verified.");
        } catch (SQLException e) {
            Utils.Errprintln("Failed to initialize database schema: " + e.getMessage());
        }
    }

    public Connection getConnection() throws SQLException {
        try {
            Connection conn = pool.poll();
            if (conn != null && conn.isValid(2)) {
                return new PooledConnection(conn, pool);
            }
            if (conn != null) {
                try { conn.close(); } catch (SQLException ignored) {}
            }
            Connection fresh = DriverManager.getConnection(URL);
            return new PooledConnection(fresh, pool);
        } catch (Exception e) {
            throw new SQLException("Connection pool exhausted", e);
        }
    }

    public DbmResponse executeSQL(Connection conn, DbmQueryType qtype, String sql) throws SQLException {
        Statement stmt = conn.createStatement();

        if (qtype == DbmQueryType.QUERY) {
            try {
                ResultSet rs = stmt.executeQuery(sql);
                Utils.Debugprintln("[DBM] Query Executed");

                int times = 0;
                ArrayList<String> records = new ArrayList<String>();
                while (rs.next()) {
                    times++;
                    StringBuilder strresponse = new StringBuilder();

                    ResultSetMetaData metadata = rs.getMetaData();
                    for (int i = 0; i < metadata.getColumnCount(); i++) {
                        strresponse.append(rs.getString(i + 1)).append(",");
                    }
                    String response = strresponse.substring(0, strresponse.length() - 1);
                    records.add(response);
                }
                rs.close();

                if (times == 0) return new DbmResponse(DbmResponseType.ResponseEmpty);
                if (times == 1) {
                    String[] resparraylist = records.getFirst().split(",");
                    return new DbmResponse(DbmResponseType.OneResponse, resparraylist);
                } else {
                    return new DbmResponse(DbmResponseType.ResponseList, times, records);
                }
            } catch (SQLException e) {
                System.err.println("[DBM] Error: " + e);
                throw e;
            } finally {
                stmt.close();
            }
        } else {
            try {
                stmt.executeUpdate(sql);
                return new DbmResponse(DbmResponseType.ResponseEmpty);
            } finally {
                stmt.close();
            }
        }
    }

    /**
     * Wraps a real Connection so that close() returns it to the pool instead of closing it.
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
                returnTo.offer(delegate);
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
