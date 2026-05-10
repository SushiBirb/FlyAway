package net.eastern.FlyAway.auth;

import java.time.ZoneId;
import java.time.ZonedDateTime;

public class AuthToken {
    private String ssid;
    private String code;
    private TokenStatus status;
    private ZonedDateTime creationDate;
    private ZonedDateTime expirationDate;
    private String owner;
    private boolean isAdmin;
    public AuthToken() {
        this.ssid="00000000-0000-0000-0000-000000000000";
        this.code="00000000-0000-0000-0000-000000000000";
        this.status = TokenStatus.INVALIDATED;
        this.creationDate=ZonedDateTime.now();
        this.expirationDate=ZonedDateTime.now();
        this.owner="nobody";
        this.isAdmin=false;
    }
    public AuthToken(String session, String Owner, boolean isAdmin) {
        this.ssid=session;
        this.code=java.util.UUID.randomUUID().toString();
        this.status = TokenStatus.VALIDATED;
        this.creationDate=ZonedDateTime.now();
        this.expirationDate=ZonedDateTime.now().plusDays(1);
        this.owner = Owner;
        this.isAdmin=isAdmin;
    }
    public AuthToken(String session, String code, String Owner, TokenStatus status, boolean isAdmin, ZonedDateTime creationDate, ZonedDateTime expirationDate) {
        this.ssid=session;
        this.code=code;
        this.status = status;
        this.creationDate=creationDate;
        this.expirationDate=expirationDate;
        this.owner=Owner;
        this.isAdmin=isAdmin;
    }
    public AuthToken(String session, String code, String Owner, TokenStatus status, boolean isAdmin, ZonedDateTime creationDate) {
        this.ssid=session;
        this.code=code;
        this.status = status;
        this.creationDate=creationDate;
        this.expirationDate=ZonedDateTime.of(9999,12,31,11,59,59,59, ZoneId.of("UTC"));
        this.owner=Owner;
        this.isAdmin=isAdmin;
    }

    public String getSsid() {
        return ssid;
    }
    public String getCode() {
        return code;
    }
    public String getOwner() {
        return owner;
    }
    public TokenStatus getStatus() {
        return status;
    }
    public boolean isAdmin() {
        return isAdmin;
    }
    public ZonedDateTime getCreationDate() {
        return creationDate;
    }
    public ZonedDateTime getExpirationDate() {
        return expirationDate;
    }
}
