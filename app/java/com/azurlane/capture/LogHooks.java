package com.azurlane.capture;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/** Java 层日志：android.util.Log.* / PrintStream(System.out|err) / 未捕获异常。 */
final class LogHooks {
    private static final Class<?>[] SIG_TT = {String.class, String.class};
    private static final Class<?>[] SIG_TTE = {String.class, String.class, Throwable.class};

    static void install(ModuleMain mod) throws Throwable {
        Class<?> log = Log.class;
        hookLevel(mod, log, "v", Log.VERBOSE, SIG_TT);
        hookLevel(mod, log, "d", Log.DEBUG, SIG_TT);
        hookLevel(mod, log, "i", Log.INFO, SIG_TT);
        hookLevel(mod, log, "w", Log.WARN, SIG_TT);
        hookLevel(mod, log, "e", Log.ERROR, SIG_TT);
        hookLevel(mod, log, "wtf", Log.ASSERT, SIG_TT);
        hookThrowable(mod, log, "w", Log.WARN);
        hookThrowable(mod, log, "e", Log.ERROR);
        hookThrowable(mod, log, "wtf", Log.ASSERT);

        Method println = log.getDeclaredMethod("println", int.class, String.class, String.class);
        mod.hook(println).intercept(chain -> {
            Object p = chain.getArg(0);
            Object tag = chain.getArg(1);
            Object msg = chain.getArg(2);
            FileSink.log(p instanceof Integer ? (Integer) p : Log.INFO, 1,
                    tag == null ? "?" : tag.toString(), msg == null ? "" : msg.toString());
            return chain.proceed();
        });
    }

    private static void hookLevel(ModuleMain mod, Class<?> log, String name, final int level,
                                  Class<?>[] sig) throws Throwable {
        Method m = log.getDeclaredMethod(name, sig);
        mod.hook(m).intercept(chain -> {
            Object tag = chain.getArg(0);
            Object msg = chain.getArg(1);
            FileSink.log(level, 1, tag == null ? "?" : tag.toString(), msg == null ? "" : msg.toString());
            return chain.proceed();
        });
    }

    private static void hookThrowable(ModuleMain mod, Class<?> log, String name, final int level)
            throws Throwable {
        Method m = log.getDeclaredMethod(name, SIG_TTE);
        mod.hook(m).intercept(chain -> {
            Object tag = chain.getArg(0);
            Object msg = chain.getArg(1);
            Object tr = chain.getArg(2);
            StringBuilder sb = new StringBuilder();
            if (msg != null) sb.append(msg);
            if (tr instanceof Throwable) {
                java.io.StringWriter sw = new java.io.StringWriter();
                ((Throwable) tr).printStackTrace(new java.io.PrintWriter(sw));
                sb.append('\n').append(sw.toString());
            }
            FileSink.log(level, 1, tag == null ? "?" : tag.toString(), sb.toString());
            return chain.proceed();
        });
    }

    private LogHooks() {}
}
