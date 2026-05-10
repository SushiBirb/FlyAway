package net.eastern.FlyAway.auth;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Date;

public class User {
    private String username;
    private String passwordhash;
    private int permsum;
    private LocalDateTime creationDate;
    private LocalDateTime lastloginDate;
    public User() {

    }
    public User(String username, String passwordhash, int permsum, LocalDateTime creationdate)
    {
        this.username = username;
        this.passwordhash = passwordhash;
        this.permsum = permsum;
        this.creationDate = creationdate;
        this.lastloginDate = LocalDateTime.now();
    }
    public User(String username, String passwordhash, int permsum, LocalDateTime creationdate, LocalDateTime lastlogindate) {
        this.username = username;
        this.passwordhash = passwordhash;
        this.permsum = permsum;
        this.creationDate = creationdate;
        this.lastloginDate = lastlogindate;
    }

    public String getUsername() {
        return username;
    }
    public void setUsername(String username) {
        this.username = username;
    }
    public String getPasswordhash() {
        return passwordhash;
    }
    public void setPasswordhash(String passwordhash) {
        this.passwordhash = passwordhash;
    }
    public int getPermsum()
    {
        return permsum;
    }
    public LocalDateTime getCreationDate() {
        return creationDate;
    }
    public LocalDateTime getLastloginDate() {
        return lastloginDate;
    }
    public void setLastloginDate(LocalDateTime lastloginDate) {
        this.lastloginDate = lastloginDate;
    }

}
