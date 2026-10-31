package ru.provless.integration.chatwoot;

import java.util.List;

/**
 * What the connector needs of an API inbox: its webhook, the secret Chatwoot signs that webhook with (given to
 * administrators only), and the working hours.
 */
public record ChatwootInboxSettings(long id, String webhookUrl, String secret, String identifier,
                                    boolean workingHoursEnabled, String timezone, List<Day> days) {

    /**
     * One day of the schedule. {@code dayOfWeek} as Chatwoot keeps it: 0 = Sunday … 6 = Saturday; {@code open}/{@code close}
     * as {@code HH:mm}, both null when closed all day.
     */
    public record Day(int dayOfWeek, String open, String close) {
    }
}
