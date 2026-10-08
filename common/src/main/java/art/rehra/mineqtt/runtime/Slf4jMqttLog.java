package art.rehra.mineqtt.runtime;

import art.rehra.mineqtt.mqtt.core.MqttLog;
import org.slf4j.Logger;

/** Routes the log lines of {@code mqtt.core}, which may not use slf4j itself, to a mod logger. */
public final class Slf4jMqttLog implements MqttLog {
    private final Logger logger;

    public Slf4jMqttLog(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void debug(String message) {
        logger.debug(message);
    }

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void warn(String message, Throwable cause) {
        if (cause == null) {
            logger.warn(message);
        } else {
            logger.warn(message, cause);
        }
    }

    @Override
    public void error(String message, Throwable cause) {
        if (cause == null) {
            logger.error(message);
        } else {
            logger.error(message, cause);
        }
    }
}
