package com.zifang.z.bot.channel;

/** 入站 body 超上限。各 handler 必须在通用 {@code catch (Exception)} **之前**接住它，否则会被映射成 500。 */
class BodyTooLargeException extends RuntimeException {

    BodyTooLargeException(String message) {
        super(message);
    }
}
