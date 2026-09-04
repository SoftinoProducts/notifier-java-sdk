package com.softino.notifier.kavenegar.enums;

/** Kavenegar-compatible meta/error codes. Identical to Kavenegar-java. */
public enum MetaData {
    NotChecked(99),
    Approved(100),
    InvalidApiKey(101),
    ExpiredApiKey(102),
    AccountDisabled(103),
    NotEnoughCredit(104),
    ServerisBusy(105),
    UndefinedCommand(106),
    RequestFailed(107),
    ParametersBroken(108),
    InvalidRecp(110),
    InvalidSenderNumber(111),
    EmptyMessage(112),
    RecpIsTooLarge(113),
    InvalidDate(114),
    MsgIsTooLarge(115),
    RecpNotEqualWithMessage(116);

    private final int value;

    MetaData(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static MetaData valueOf(int type) {
        for (MetaData code : values()) {
            if (type == code.value) {
                return code;
            }
        }
        return null;
    }
}
