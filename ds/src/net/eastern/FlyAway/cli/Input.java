package net.eastern.FlyAway.cli;

import net.eastern.FlyAway.dbm.Dbm;
import net.eastern.FlyAway.dbm.DbmQueryType;
import net.eastern.FlyAway.dbm.DbmResponse;
import net.eastern.FlyAway.dbm.DbmResponseType;
import net.eastern.FlyAway.util.Utils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;

/**
 * Interactive Command Line Interface for the Dedicated Server.
 *
 * Provides administrative control commands for manual student validation,
 * account creation, query execution, and server management.
 */
public class Input {
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public Input() throws SQLException {
        Utils.Infoprintln("Command Handler Started");
        doInputQueryCycle();
    }

    public void doInputQueryCycle() throws SQLException {
        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            System.out.print("# FLA-DS > ");
            String line = scanner.nextLine();
            if (line != null) {
                processCommand(line);
            }
        }
    }

    public void processCommand(String fullcommand) throws SQLException {
        String trimmed = fullcommand.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        String[] brokencmd = trimmed.split("\\s+");
        String command = brokencmd[0].toLowerCase();
        String[] args = new String[brokencmd.length - 1];
        System.arraycopy(brokencmd, 1, args, 0, brokencmd.length - 1);

        Dbm dbm = new Dbm();

        switch (command) {
            case "sendrecord": {
                if (args.length < 1) {
                    System.err.println("Usage: sendrecord <studentid>");
                    break;
                }
                int studentId;
                try {
                    studentId = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    System.err.println("Error: Student ID must be an integer.");
                    break;
                }

                try (Connection conn = dbm.getConnection()) {
                    boolean exitallowed;
                    try (PreparedStatement checkUser = conn.prepareStatement("SELECT exitallowed FROM users WHERE studentid = ?")) {
                        checkUser.setInt(1, studentId);
                        try (ResultSet rs = checkUser.executeQuery()) {
                            if (rs.next()) {
                                exitallowed = rs.getBoolean("exitallowed");
                            } else {
                                System.out.println("User does not exist, enrolling as BLOCKED.");
                                exitallowed = false;
                                try (PreparedStatement insertUser = conn.prepareStatement("INSERT INTO users (studentid, exitallowed) VALUES (?, FALSE)")) {
                                    insertUser.setInt(1, studentId);
                                    insertUser.executeUpdate();
                                }
                            }
                        }
                    }

                    String earlyexit = exitallowed ? "APPROVED" : "REJECTED";
                    String nowStr = LocalDateTime.now().format(ISO_FORMATTER);

                    try (PreparedStatement insertRecord = conn.prepareStatement("INSERT INTO RECORDS (sid, timestamp, result) VALUES (?, ?, ?)")) {
                        insertRecord.setInt(1, studentId);
                        insertRecord.setString(2, nowStr);
                        insertRecord.setString(3, earlyexit);
                        insertRecord.executeUpdate();
                    }

                    System.out.println("Record logged: Student #" + studentId + " => " + earlyexit);
                } catch (SQLException e) {
                    System.err.println("Database error: " + e.getMessage());
                }
                break;
            }

            case "setuserallow": {
                if (args.length < 2) {
                    System.err.println("Usage: setuserallow <studentid> <0|1>");
                    break;
                }
                int userId;
                int allowValue;
                try {
                    userId = Integer.parseInt(args[0]);
                    allowValue = Integer.parseInt(args[1]);
                    if (allowValue != 0 && allowValue != 1) {
                        System.err.println("Error: Value must be 0 (blocked) or 1 (allowed).");
                        break;
                    }
                } catch (NumberFormatException e) {
                    System.err.println("Error: Invalid numeric arguments.");
                    break;
                }

                try (Connection conn = dbm.getConnection()) {
                    try (PreparedStatement checkUser = conn.prepareStatement("SELECT studentid FROM users WHERE studentid = ?")) {
                        checkUser.setInt(1, userId);
                        try (ResultSet rs = checkUser.executeQuery()) {
                            if (!rs.next()) {
                                try (PreparedStatement insertUser = conn.prepareStatement("INSERT INTO users (studentid, exitallowed) VALUES (?, ?)")) {
                                    insertUser.setInt(1, userId);
                                    insertUser.setBoolean(2, allowValue == 1);
                                    insertUser.executeUpdate();
                                }
                            } else {
                                try (PreparedStatement updateUser = conn.prepareStatement("UPDATE users SET exitallowed = ? WHERE studentid = ?")) {
                                    updateUser.setBoolean(1, allowValue == 1);
                                    updateUser.setInt(2, userId);
                                    updateUser.executeUpdate();
                                }
                            }
                        }
                    }
                    System.out.println("Student #" + userId + " exit privilege updated to: " + (allowValue == 1 ? "ALLOWED" : "BLOCKED"));
                } catch (SQLException e) {
                    System.err.println("Database error: " + e.getMessage());
                }
                break;
            }

            case "validate": {
                if (args.length < 1) {
                    System.err.println("Usage: validate <studentid>");
                    break;
                }
                int sid;
                try {
                    sid = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    System.err.println("Error: Student ID must be an integer.");
                    break;
                }

                try (Connection conn = dbm.getConnection()) {
                    try (PreparedStatement checkUser = conn.prepareStatement("SELECT exitallowed FROM users WHERE studentid = ?")) {
                        checkUser.setInt(1, sid);
                        try (ResultSet rs = checkUser.executeQuery()) {
                            if (rs.next()) {
                                System.out.println(rs.getBoolean("exitallowed") ? "1 (ALLOWED)" : "0 (BLOCKED)");
                            } else {
                                try (PreparedStatement insertUser = conn.prepareStatement("INSERT INTO users (studentid, exitallowed) VALUES (?, FALSE)")) {
                                    insertUser.setInt(1, sid);
                                    insertUser.executeUpdate();
                                }
                                System.out.println("0 (BLOCKED - Newly Enrolled)");
                            }
                        }
                    }
                } catch (SQLException e) {
                    System.err.println("Database error: " + e.getMessage());
                }
                break;
            }

            case "createuser": {
                if (args.length < 2) {
                    System.err.println("Usage: createuser <username> <permsum>");
                    break;
                }
                String username = args[0];
                int permsum;
                try {
                    permsum = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    System.err.println("Error: permsum must be an integer (e.g., 777).");
                    break;
                }

                String password = null;
                java.io.Console console = System.console();
                if (console != null) {
                    char[] pwd1 = console.readPassword("Password: ");
                    char[] pwd2 = console.readPassword("Confirm Password: ");
                    if (pwd1 == null || pwd2 == null || !java.util.Arrays.equals(pwd1, pwd2)) {
                        System.err.println("Passwords do not match or input was cancelled.");
                        if (pwd1 != null) java.util.Arrays.fill(pwd1, ' ');
                        if (pwd2 != null) java.util.Arrays.fill(pwd2, ' ');
                        break;
                    }
                    password = new String(pwd1);
                    java.util.Arrays.fill(pwd1, ' ');
                    java.util.Arrays.fill(pwd2, ' ');
                } else {
                    // Fallback to standard input for headless environments
                    System.out.print("Password: ");
                    Scanner inputScan = new Scanner(System.in);
                    if (inputScan.hasNextLine()) {
                        password = inputScan.nextLine();
                    }
                }

                if (password == null || password.trim().isEmpty()) {
                    System.err.println("Password cannot be empty.");
                    break;
                }

                try {
                    // Pre-hash with SHA-256 to emulate client-side hash, then hash with PBKDF2
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
                    StringBuilder hexString = new StringBuilder(2 * hash.length);
                    for (byte b : hash) {
                        String hex = Integer.toHexString(0xff & b);
                        if (hex.length() == 1) {
                            hexString.append('0');
                        }
                        hexString.append(hex);
                    }
                    String clientHash = hexString.toString();
                    String finalHash = net.eastern.FlyAway.auth.PasswordHasher.hash(clientHash);

                    try (Connection conn = dbm.getConnection()) {
                        String nowStr = LocalDateTime.now().format(ISO_FORMATTER);
                        try (PreparedStatement insertAcct = conn.prepareStatement(
                                "INSERT INTO accts (un, password, permsum, creationdate, lastlogin) VALUES (?, ?, ?, ?, NULL)")) {
                            insertAcct.setString(1, username);
                            insertAcct.setString(2, finalHash);
                            insertAcct.setInt(3, permsum);
                            insertAcct.setString(4, nowStr);
                            insertAcct.executeUpdate();
                            System.out.println("Administrator account '" + username + "' created successfully.");
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Error creating user: " + e.getMessage());
                }
                break;
            }

            case "exec": {
                if (args.length < 1) {
                    System.err.println("Usage: exec <SELECT query>");
                    break;
                }
                String sqlCmd = String.join(" ", args);
                String upperCmd = sqlCmd.toUpperCase();
                if (upperCmd.contains("DROP ") || upperCmd.contains("DELETE ") || upperCmd.contains("ALTER ")
                        || upperCmd.contains("TRUNCATE ") || upperCmd.contains("UPDATE ") || upperCmd.contains("INSERT ")
                        || upperCmd.contains("GRANT ") || upperCmd.contains("REVOKE ") || upperCmd.contains("CREATE ")) {
                    System.err.println("Only SELECT queries are allowed via exec.");
                    break;
                }

                try (Connection conn = dbm.getConnection()) {
                    DbmResponse response2 = dbm.executeSQL(conn, DbmQueryType.QUERY, sqlCmd);
                    if (response2.getType() == DbmResponseType.OneResponse) {
                        System.out.println(String.join("\t", response2.getContentArray()));
                    } else if (response2.getType() == DbmResponseType.ResponseEmpty) {
                        System.out.println("No results found.");
                    } else if (response2.getType() == DbmResponseType.ResponseList) {
                        for (int i = 0; i < response2.getRecordCount(); i++) {
                            System.out.println(String.join("\t", response2.getContentArrayFromResponse(i)));
                        }
                    }
                } catch (SQLException e) {
                    System.err.println("SQL execution error: " + e.getMessage());
                }
                break;
            }

            case "help": {
                ClassLoader classloader = Thread.currentThread().getContextClassLoader();
                try (InputStream is = classloader.getResourceAsStream("cmds/help.txt")) {
                    if (is == null) {
                        System.err.println("Help file not found.");
                        break;
                    }
                    try (Scanner scanfile = new Scanner(is)) {
                        while (scanfile.hasNextLine()) {
                            System.out.println(scanfile.nextLine());
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Error displaying help: " + e.getMessage());
                }
                break;
            }

            case "exit":
            case "quit":
                System.out.println("Exiting FlyAway Dedicated Server.");
                System.exit(0);
                break;

            default:
                System.err.println("Unknown command: '" + command + "'. Type 'help' for available commands.");
                break;
        }
    }
}
