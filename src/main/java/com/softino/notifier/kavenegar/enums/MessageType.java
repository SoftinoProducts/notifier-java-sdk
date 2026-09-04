package com.softino.notifier.kavenegar.enums;

/** Kavenegar-compatible message type codes. Identical to Kavenegar-java. */
public enum MessageType {
    Flash(0),
    MobileMemory(1),
    SimMemory(2),
    AppMemory(3);

    private final int value;

    MessageType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static MessageType valueOf(int type) {
        for (MessageType code : values()) {
            if (type == code.value) {
                return code;
            }
        }
        return null;
    }
}
