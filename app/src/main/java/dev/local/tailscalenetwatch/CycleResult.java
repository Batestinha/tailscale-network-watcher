package dev.local.tailscalenetwatch;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

/** Typed result returned across the private UserService binder. */
public final class CycleResult implements Parcelable {
    public static final int SUCCESS = 0;
    public static final int SKIPPED_NOT_ALWAYS_ON = 1;
    public static final int FORCE_STOP_FAILED = 2;
    public static final int CONNECT_FAILED = 3;
    public static final int INTERRUPTED = 4;

    private final int code;
    private final String detail;
    private final boolean forceStopAttempted;
    private final boolean forceStopCompleted;

    public CycleResult(
            int code,
            String detail,
            boolean forceStopAttempted,
            boolean forceStopCompleted
    ) {
        this.code = code;
        this.detail = detail == null ? "" : detail;
        this.forceStopAttempted = forceStopAttempted;
        this.forceStopCompleted = forceStopCompleted;
    }

    private CycleResult(Parcel source) {
        code = source.readInt();
        String parcelDetail = source.readString();
        detail = parcelDetail == null ? "" : parcelDetail;
        forceStopAttempted = source.readInt() != 0;
        forceStopCompleted = source.readInt() != 0;
    }

    public int getCode() {
        return code;
    }

    public String getDetail() {
        return detail;
    }

    public boolean wasForceStopAttempted() {
        return forceStopAttempted;
    }

    public boolean wasForceStopCompleted() {
        return forceStopCompleted;
    }

    public boolean isSuccess() {
        return code == SUCCESS;
    }

    public String getCodeName() {
        return codeName(code);
    }

    public static String codeName(int value) {
        return switch (value) {
            case SUCCESS -> "SUCCESS";
            case SKIPPED_NOT_ALWAYS_ON -> "SKIPPED_NOT_ALWAYS_ON";
            case FORCE_STOP_FAILED -> "FORCE_STOP_FAILED";
            case CONNECT_FAILED -> "CONNECT_FAILED";
            case INTERRUPTED -> "INTERRUPTED";
            default -> "UNKNOWN(" + value + ")";
        };
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeInt(code);
        destination.writeString(detail);
        destination.writeInt(forceStopAttempted ? 1 : 0);
        destination.writeInt(forceStopCompleted ? 1 : 0);
    }

    public static final @NonNull Creator<CycleResult> CREATOR = new Creator<>() {
        @Override
        public CycleResult createFromParcel(Parcel source) {
            return new CycleResult(source);
        }

        @Override
        public CycleResult[] newArray(int size) {
            return new CycleResult[size];
        }
    };
}
