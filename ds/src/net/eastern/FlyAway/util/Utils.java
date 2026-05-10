package net.eastern.FlyAway.util;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class Utils {


    /**
     * Public fields for ease of console color control. Deprecated but Useful still.
     */
    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_BLACK = "\u001B[30m";
    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_GREEN = "\u001B[32m";
    public static final String ANSI_YELLOW = "\u001B[33m";
    public static final String ANSI_BLUE = "\u001B[34m";
    public static final String ANSI_PURPLE = "\u001B[35m";
    public static final String ANSI_CYAN = "\u001B[36m";
    public static final String ANSI_WHITE = "\u001B[37m";
    public static final String ANSI_GRAY = "\u001B[90m";


    /**
     * Gets Now.
     * @return
     */
    private static final String getNow() {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String formNow = "[" + now.format(formatter) + "] ";
        return formNow;
    }

    /**
     * Prints a Debug Message to System.out.
     * @param message
     */
    public static final void Debugprintln(String message) {
        System.out.println(ANSI_GRAY + getNow() + "[Debug] [" + StackWalker.getInstance().walk(stream -> stream.skip(1).findFirst().get()).getClassName() + "] " + message + ANSI_RESET );
    }

    /**
     * Prints an Info Message to System.out.
     * @param message
     */
    public static final void Infoprintln(String message) {
        System.out.println(ANSI_RESET + getNow() + "[Info] [" +  StackWalker.getInstance().walk(stream -> stream.skip(1).findFirst().get()).getClassName() + "] " + message + ANSI_RESET);
    }

    /**
     * Prints a Warning Message to System.out.
     * @param message
     */
    public static final void Warnprintln(String message) {
        System.out.println(ANSI_YELLOW + getNow() + "[Warn] [" +  StackWalker.getInstance().walk(stream -> stream.skip(1).findFirst().get()).getClassName() + "] " + message + ANSI_RESET);
    }

    /**
     * Prints an Error Message to System.err.
     * @param message
     */
    public static final void Errprintln(String message) {
        System.err.println(ANSI_RED + getNow() + "[Err] [" +  StackWalker.getInstance().walk(stream -> stream.skip(1).findFirst().get()).getClassName() + "] " + message + ANSI_RESET);
    }
}
