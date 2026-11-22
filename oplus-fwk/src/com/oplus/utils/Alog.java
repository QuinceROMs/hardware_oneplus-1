package com.oplus.utils;

import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

public class Alog {

    private static final String DEFAULT_TAG = "OplusReflect";

    private static final List<Logger> FOREST = new ArrayList<>();

    private static final Logger SOULS = new Logger() {
        @Override
        public void i(String message) {
            for (Logger logger : FOREST) {
                logger.i(message);
            }
        }

        @Override
        public void i(String message, Throwable t) {
            for (Logger logger : FOREST) {
                logger.i(message, t);
            }
        }

        @Override
        public void d(String message) {
            for (Logger logger : FOREST) {
                logger.d(message);
            }
        }

        @Override
        public void d(String message, Throwable t) {
            for (Logger logger : FOREST) {
                logger.d(message, t);
            }
        }

        @Override
        public void e(String message) {
            for (Logger logger : FOREST) {
                logger.e(message);
            }
        }

        @Override
        public void e(String message, Throwable t) {
            for (Logger logger : FOREST) {
                logger.e(message, t);
            }
        }

        @Override
        protected void log(int priority, String tag, String message, Throwable t) {
            throw new AssertionError("Should not handle log method by myself");
        }
    };

    static {
        addLogger(new LogcatLogger());
    }

    public static void i(String message) {
        SOULS.i(message);
    }

    public static void i(String message, Throwable t) {
        SOULS.i(message, t);
    }

    public static void d(String message) {
        SOULS.d(message);
    }

    public static void d(String message, Throwable t) {
        SOULS.d(message, t);
    }

    public static void e(String message) {
        SOULS.e(message);
    }

    public static void e(String message, Throwable t) {
        SOULS.e(message, t);
    }

    public static void addLogger(Logger logger) {
        synchronized (FOREST) {
            FOREST.add(logger);
        }
    }

    public static void clearLogger() {
        synchronized (FOREST) {
            FOREST.clear();
        }
    }

    public static void tag(String tag) {
        synchronized (FOREST) {
            for (Logger logger : FOREST) {
                logger.explicitTag.set(tag);
            }
        }
    }

    private static final class LogcatLogger extends Logger {
        @Override
        protected void log(int priority, String tag, String message, Throwable t) {
            Log.println(priority, tag != null ? tag : DEFAULT_TAG, message);
        }
    }

    public static abstract class Logger {
        public static final int VERBOSE = 2;
        public static final int DEBUG = 3;
        public static final int INFO = 4;
        public static final int WARN = 5;
        public static final int ERROR = 6;
        public static final int ASSERT = 7;

        final ThreadLocal<String> explicitTag = new ThreadLocal<>();

        protected abstract void log(int priority, String tag, String message, Throwable t);

        public void i(String message) {
            prepareLog(INFO, null, message);
        }

        public void i(String message, Throwable t) {
            prepareLog(INFO, t, message);
        }

        public void d(String message) {
            prepareLog(DEBUG, null, message);
        }

        public void d(String message, Throwable t) {
            prepareLog(DEBUG, t, message);
        }

        public void e(String message) {
            prepareLog(ERROR, null, message);
        }

        public void e(String message, Throwable t) {
            prepareLog(ERROR, t, message);
        }

        private String getTag() {
            String tag = explicitTag.get();
            if (tag != null) {
                explicitTag.remove();
            }
            return tag;
        }

        private void prepareLog(int priority, Throwable t, String message, Object... args) {
            String tag = getTag();
            if (message != null && message.length() == 0) {
                message = null;
            }
            if (message == null) {
                if (t == null) {
                    return;
                }
                message = getStackTraceString(t);
            } else {
                if (args != null && args.length > 0) {
                    message = formatMessage(message, args);
                }
                if (t != null) {
                    message = message + "\n" + getStackTraceString(t);
                }
            }
            log(priority, tag, message, t);
        }

        private String getStackTraceString(Throwable t) {
            StringWriter sw = new StringWriter(256);
            PrintWriter pw = new PrintWriter(sw, false);
            t.printStackTrace(pw);
            pw.flush();
            return sw.toString();
        }

        protected String formatMessage(String message, Object[] args) {
            return String.format(message, args);
        }
    }
}
