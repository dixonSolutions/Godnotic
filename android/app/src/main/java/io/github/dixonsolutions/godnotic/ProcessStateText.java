package io.github.dixonsolutions.godnotic;

/**
 * What the process tells the system about itself for {@code ApplicationExitInfo.getProcessStateSummary}:
 * one line, rebuilt whenever a part changes (app and payload version, host, screen or stage, engine,
 * update phase).
 *
 * <p>Plain JVM, no Android classes: {@code ProcessStateTextTest} runs it without a device
 * ({@link ProcessState} does the ActivityManager call). The line is what a crash report prints under
 * {@code state:}, so it has to stay one line and short: it is the only record of what the dead process
 * was doing when the versions in the report's header are the ones reading it, not the ones dying.
 */
final class ProcessStateText {
    private ProcessStateText() {}

    /** Where a part is unknown or empty: still named, so the line's shape never changes. */
    static final String NONE = "-";

    /**
     * The summary line: {@code app=… payload=… host=… stage=… engine=… phase=…}. Parts are always in
     * this order, so two summaries can be compared as text.
     */
    static String format(String app, String payload, String host, String stage, boolean engine,
                         String phase) {
        StringBuilder b = new StringBuilder();
        b.append("app=").append(part(app))
                .append(" payload=").append(part(payload))
                .append(" host=").append(part(host))
                .append(" stage=").append(part(stage))
                .append(" engine=").append(engine ? '1' : '0')
                .append(" phase=").append(part(phase));
        return b.toString();
    }

    /** Whether publishing {@code next} would tell the system something new. */
    static boolean changed(String previous, String next) {
        return next != null && !next.equals(previous);
    }

    private static String part(String value) {
        if (value == null) {
            return NONE;
        }
        String text = oneLine(value);
        return text.isEmpty() ? NONE : text;
    }

    /** A part on one line and short: a summary is a label, not a log line. */
    static String oneLine(String value) {
        String text = value.trim().replaceAll("\\s+", " ");
        return text.length() > 48 ? text.substring(0, 48) : text;
    }
}
