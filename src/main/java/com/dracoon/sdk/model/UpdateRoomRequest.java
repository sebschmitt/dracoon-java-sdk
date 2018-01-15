package com.dracoon.sdk.model;

/**
 * Request to update a room.<br>
 * <br>
 * A new instance can be created with {@link UpdateRoomRequest.Builder Builder}.
 */
@SuppressWarnings("unused")
public class UpdateRoomRequest {

    private Long mId;
    private String mName;
    private Long mQuota;
    private String mNotes;

    private UpdateRoomRequest() {

    }

    /**
     * Returns the ID of the room which should be updated.
     *
     * @return the ID
     */
    public Long getId() {
        return mId;
    }

    /**
     * Returns the new name of the room.
     *
     * @return the new name
     */
    public String getName() {
        return mName;
    }

    /**
     * Returns the new quota of the room.
     *
     * @return the new quota
     */
    public Long getQuota() {
        return mQuota;
    }

    /**
     * Returns the new notes of the room.
     *
     * @return the new notes
     */
    public String getNotes() {
        return mNotes;
    }

    /**
     * This builder creates new instances of {@link UpdateRoomRequest}.<br>
     * <br>
     * Following properties can be set:<br>
     * - Node ID (mandatory): {@link Builder#Builder(Long)}<br>
     * - Name:                {@link Builder#name(String)}<br>
     * - Quota:               {@link Builder#quota(Long)}<br>
     * - Notes:               {@link Builder#notes(String)}
     */
    public static class Builder {

        private UpdateRoomRequest mRequest;

        /**
         * Constructs a new builder.
         *
         * @param id The node ID of the room which should be updated.
         */
        public Builder(Long id) {
            mRequest = new UpdateRoomRequest();
            mRequest.mId = id;
        }

        /**
         * Sets the new name of the room.
         *
         * @param name The new name.
         *
         * @return a reference to this object
         */
        public Builder name(String name) {
            mRequest.mName = name;
            return this;
        }

        /**
         * Sets the new quota of the room.
         *
         * @param quota The new quota.
         *
         * @return a reference to this object
         */
        public Builder quota(Long quota) {
            mRequest.mQuota = quota;
            return this;
        }

        /**
         * Sets the new notes of the room.
         *
         * @param notes The new notes.
         *
         * @return a reference to this object
         */
        public Builder notes(String notes) {
            mRequest.mNotes = notes;
            return this;
        }

        /**
         * Creates a new {@link UpdateRoomRequest} instance with the supplied configuration.
         *
         * @return a new {@link UpdateRoomRequest} instance
         */
        public UpdateRoomRequest build() {
            return mRequest;
        }

    }

}
