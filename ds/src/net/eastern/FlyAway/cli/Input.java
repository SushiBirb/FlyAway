package net.eastern.FlyAway.cli;

import net.eastern.FlyAway.dbm.Dbm;
import net.eastern.FlyAway.dbm.DbmQueryType;
import net.eastern.FlyAway.dbm.DbmResponse;
import net.eastern.FlyAway.dbm.DbmResponseType;
import net.eastern.FlyAway.util.Utils;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

public class Input {

    public Input() throws SQLException {
        Utils.Infoprintln("Command Handler Started");
        doInputQueryCycle();
    }

    public void doInputQueryCycle() throws SQLException {
        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            System.out.print("# FLA-DS > ");
            processCommand(scanner.nextLine());
        }
    }

    public void processCommand(String fullcommand) throws SQLException {
        String[] brokencmd = fullcommand.toLowerCase().split(" ");
        String[] args = new String[brokencmd.length - 1];
        System.arraycopy(brokencmd, 1, args, 0, brokencmd.length - 1);
        String command = brokencmd[0];
        Utils.Debugprintln("Running command: " + fullcommand);

        String earlyexit;
        Connection conn;

        switch (command) {
            case "":
                break;
            case "sendrecord": {
                if (args.length < 1) {
                    System.err.println("Usage: sendrecord <studentid>");
                    break;
                }
                int studentId = Integer.parseInt(args[0]);
                conn = new Dbm().getConnection();
                try {
                    PreparedStatement checkUser = conn.prepareStatement("SELECT studentid FROM users WHERE studentid = ?");
                    checkUser.setInt(1, studentId);
                    ResultSet rs = checkUser.executeQuery();
                    if (!rs.next()) {
                        System.out.println("User does not exist, adding to database");
                        PreparedStatement insertUser = conn.prepareStatement("INSERT INTO users (studentid, exitallowed) VALUES (?, ?)");
                        insertUser.setInt(1, studentId);
                        insertUser.setBoolean(2, false);
                        insertUser.executeUpdate();
                        insertUser.close();
                    }
                    rs.close();
                    checkUser.close();

                    PreparedStatement checkExit = conn.prepareStatement("SELECT exitallowed FROM users WHERE studentid = ?");
                    checkExit.setInt(1, studentId);
                    ResultSet exitRs = checkExit.executeQuery();
                    exitRs.next();
                    boolean exitallowed = exitRs.getBoolean(1);
                    exitRs.close();
                    checkExit.close();

                    earlyexit = exitallowed ? "APPROVED" : "REJECTED";

                    PreparedStatement insertRecord = conn.prepareStatement("INSERT INTO RECORDS (sid, timestamp, result) VALUES (?, ?, ?)");
                    insertRecord.setInt(1, studentId);
                    insertRecord.setString(2, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    insertRecord.setString(3, earlyexit);
                    insertRecord.executeUpdate();
                    insertRecord.close();
                } finally {
                    conn.close();
                }
                break;
            }
            case "exit":
                System.exit(0);
                break;
            case "help": {
                ClassLoader classloader = Thread.currentThread().getContextClassLoader();
                InputStream is = classloader.getResourceAsStream("cmds/help.txt");
                if (is == null) {
                    System.err.println("Help file not found");
                    break;
                }
                try {
                    Scanner scanfile = new Scanner(is);
                    while (scanfile.hasNextLine()) {
                        System.out.println(scanfile.nextLine());
                    }
                    scanfile.close();
                } finally {
                    try { is.close(); } catch (Exception ignored) {}
                }
                break;
            }
            case "exec": {
                String sqlCmd = String.join(" ", args);
                String upperCmd = sqlCmd.toUpperCase();
                if (upperCmd.contains("DROP ") || upperCmd.contains("DELETE ") || upperCmd.contains("ALTER ") || upperCmd.contains("TRUNCATE ") || upperCmd.contains("UPDATE ") || upperCmd.contains("INSERT ") || upperCmd.contains("GRANT ") || upperCmd.contains("REVOKE ") || upperCmd.contains("CREATE ")) {
                    System.err.println("Only SELECT queries are allowed via exec.");
                    break;
                }
                Dbm dbm = new Dbm();
                conn = dbm.getConnection();
                try {
                    DbmResponse response2 = dbm.executeSQL(conn, DbmQueryType.UPDATE, sqlCmd);
                    if (response2.getType() == DbmResponseType.OneResponse) {
                        System.out.println(response2.getContentArray()[0]);
                    } else if (response2.getType() == DbmResponseType.ResponseEmpty) {
                        System.out.println("No results found");
                    } else if (response2.getType() == DbmResponseType.ResponseList) {
                        for (int i = 0; i < response2.getContentArray().length; i++) {
                            System.out.println(response2.getContentArray()[i]);
                        }
                    }
                } finally {
                    conn.close();
                }
                break;
            }
            case "setuserallow": {
                if (args.length < 2) {
                    System.err.println("Usage: setuserallow <studentid> <0|1>");
                    break;
                }
                int userId = Integer.parseInt(args[0]);
                int allowValue = Integer.parseInt(args[1]);
                conn = new Dbm().getConnection();
                try {
                    PreparedStatement checkUser = conn.prepareStatement("SELECT studentid FROM users WHERE studentid = ?");
                    checkUser.setInt(1, userId);
                    ResultSet rs = checkUser.executeQuery();
                    if (!rs.next()) {
                        System.out.println("User does not exist, adding to database");
                        PreparedStatement insertUser = conn.prepareStatement("INSERT INTO users (studentid, exitallowed) VALUES (?, ?)");
                        insertUser.setInt(1, userId);
                        insertUser.setBoolean(2, false);
                        insertUser.executeUpdate();
                        insertUser.close();
                    }
                    rs.close();
                    checkUser.close();

                    PreparedStatement updateUser = conn.prepareStatement("UPDATE users SET exitallowed = ? WHERE studentid = ?");
                    updateUser.setInt(1, allowValue);
                    updateUser.setInt(2, userId);
                    updateUser.executeUpdate();
                    updateUser.close();
                    System.out.println("User updated");
                } finally {
                    conn.close();
                }
                break;
            }
            case "validate": {
                if (args.length < 1) {
                    System.err.println("Usage: validate <studentid>");
                    break;
                }
                int sid = Integer.parseInt(args[0]);
                conn = new Dbm().getConnection();
                try {
                    PreparedStatement checkUser = conn.prepareStatement("SELECT exitallowed FROM users WHERE studentid = ?");
                    checkUser.setInt(1, sid);
                    ResultSet rs = checkUser.executeQuery();
                    if (rs.next()) {
                        System.out.println(rs.getBoolean(1) ? "1" : "0");
                    } else {
                        PreparedStatement insertUser = conn.prepareStatement("INSERT INTO users (studentid, exitallowed) VALUES (?, ?)");
                        insertUser.setInt(1, sid);
                        insertUser.setBoolean(2, false);
                        insertUser.executeUpdate();
                        insertUser.close();
                        System.out.println("User added");
                    }
                    rs.close();
                    checkUser.close();
                } finally {
                    conn.close();
                }
                break;
            }
            case "createuser": {
                if (args.length < 2) {
                    System.err.println("Usage: createuser <username> <permsum>");
                    break;
                }
                String username = args[0];
                int permsum = Integer.parseInt(args[1]);

                java.io.Console console = System.console();
                if (console == null) {
                    System.err.println("Console is not available for secure password entry.");
                    break;
                }

                char[] pwd1 = console.readPassword("Password: ");
                char[] pwd2 = console.readPassword("Confirm Password: ");
                if (pwd1 == null || pwd2 == null || !java.util.Arrays.equals(pwd1, pwd2)) {
                    System.err.println("Passwords do not match or input was cancelled.");
                    if (pwd1 != null) java.util.Arrays.fill(pwd1, ' ');
                    if (pwd2 != null) java.util.Arrays.fill(pwd2, ' ');
                    break;
                }

                String password = new String(pwd1);
                java.util.Arrays.fill(pwd1, ' ');
                java.util.Arrays.fill(pwd2, ' ');

                try {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
                    StringBuilder hexString = new StringBuilder(2 * hash.length);
                    for (int i = 0; i < hash.length; i++) {
                        String hex = Integer.toHexString(0xff & hash[i]);
                        if (hex.length() == 1) {
                            hexString.append('0');
                        }
                        hexString.append(hex);
                    }
                    String clientHash = hexString.toString();
                    String finalHash = net.eastern.FlyAway.auth.PasswordHasher.hash(clientHash);

                    conn = new Dbm().getConnection();
                    try {
                        PreparedStatement insertAcct = conn.prepareStatement("INSERT INTO accts (un, password, permsum, creationdate, lastlogin) VALUES (?, ?, ?, datetime('now'), NULL)");
                        insertAcct.setString(1, username);
                        insertAcct.setString(2, finalHash);
                        insertAcct.setInt(3, permsum);
                        insertAcct.executeUpdate();
                        insertAcct.close();
                        System.out.println("User '" + username + "' created successfully.");
                    } finally {
                        conn.close();
                    }
                } catch (Exception e) {
                    System.err.println("Error creating user: " + e.getMessage());
                }
                break;
            }
            default:
                System.err.println("Unknown command: " + command);
                break;
        }
    }
}
