package com.softino.notifier;

/**
 * Query parameters for {@link NotifierApi#listPushDevices(PushDeviceQuery)}. All fields are
 * optional; a blank {@code userRef} and non-positive {@code limit}/{@code offset} are omitted
 * from the request rather than sent as empty or zero values.
 */
public final class PushDeviceQuery {

    private final String userRef;
    private final int limit;
    private final int offset;

    public PushDeviceQuery(String userRef, int limit, int offset) {
        this.userRef = userRef;
        this.limit = limit;
        this.offset = offset;
    }

    /** An unfiltered query: the service's default page, newest devices first. */
    public static PushDeviceQuery none() {
        return new PushDeviceQuery(null, 0, 0);
    }

    /** Lists one user's devices, with the service default page size. */
    public static PushDeviceQuery forUser(String userRef) {
        return new PushDeviceQuery(userRef, 0, 0);
    }

    /** Limits the result to one of your users' devices; blank means every device. */
    public String getUserRef() {
        return userRef;
    }

    /** Page size; zero uses the service default (100), and the service caps larger values. */
    public int getLimit() {
        return limit;
    }

    /** The number of devices to skip. */
    public int getOffset() {
        return offset;
    }
}
