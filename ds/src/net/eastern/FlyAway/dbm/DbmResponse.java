package net.eastern.FlyAway.dbm;

import java.util.ArrayList;

public class DbmResponse {
    private final DbmResponseType responseType;
    private final Object[] content;


    /**
     * For when DB has no response
     * @param type
     */
    public DbmResponse(DbmResponseType type) {
        if (type != DbmResponseType.ResponseEmpty) throw new IllegalStateException("Invalid DbmResponse Type");
        this.responseType = type;
        this.content = new String[1];
        this.content[0] = "null";
    }

    /**
     * For when DB has only one response
     * @param type
     * @param Record
     */
    public DbmResponse(DbmResponseType type, String[] Record) {
        if (type != DbmResponseType.OneResponse) throw new IllegalStateException("Invalid DbmResponse Type");
        this.responseType = type;
        this.content = Record;
    }

    /**
     * For when DB has multiple responses
     * @param type
     * @param responses
     * @param records
     */
    public DbmResponse(DbmResponseType type, int responses, ArrayList<String> records) {
        if (type != DbmResponseType.ResponseList) throw new IllegalStateException("Invalid DbmResponse Type");
        this.responseType = type;
        this.content = new String[records.size()][records.getFirst().split(",").length];
        for (int i=0; i<records.size(); i++) {
            System.arraycopy(records.get(i).split(","), 0, this.content[i], 0, records.get(i).split(",").length);
        }
    }

    /**
     * Gets Type of Response
     * @return
     */
    public DbmResponseType getType() {
        return this.responseType;
    }

    /**
     * Gets Content Array From a Specific index of responses.
     * ONLY USED FOR DbmResponseType.ResponseList!!!
     * @param responsenum
     * @return
     */
    public String[] getContentArrayFromResponse(int responsenum) {
        if (this.responseType != DbmResponseType.ResponseList) {
            throw new IllegalStateException("Invalid DbmResponse Type");
        }
        return (String[]) this.content[responsenum];
    }

    public int getRecordCount() {
        return this.content != null ? this.content.length : 0;
    }

    /**
     * Gets Content Array from response. The content array is literally just the columns of data in the DB.
     * @return
     */
    public String[] getContentArray() {
        if (this.responseType == DbmResponseType.ResponseList) {
            throw new IllegalStateException("Invalid DbmResponse Type");
        }
        return (String[]) this.content;
    }
}
