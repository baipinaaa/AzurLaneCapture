package com.azurlane.capture;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/** Java 层日志：android.util.Log.* / PrintStream(System.out|err) / 未捕获异常。 */
final class LogHooks {
    private static final Class<?>[] SIG_TT = {String.class, String.class};

    /**
     * Java 层 tag 白名单：SDK 的 APM / 广告 / 支付回调是纯噪音，
     * 默认只留和游戏逻辑、Unity、Lua、崩溃相关的 tag。
     */
    private static final String[] TAGS = {
            "Unity", "LuaInterface", "AndroidRuntime", "DEBUG", "libc", "Il2Cpp",
            "tolua", "Mono", "CRASH", "AzCap", "MainThreadStuck", "art",
    };

    static boolean allowed(String tag) {
        if (Config.verbose) return true;
        if (tag == null || tag.isEmpty()) return false;
        for (String a : TAGS) {
            if (tag.startsWith(a)) return true;
        }
        return false;
    }
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

    /**
     * Lua 侧的完整 stack traceback 走 tolua 的 LuaInterface.Debugger.Log*。
     * 这些是 il2cpp 导出的包装类，能不能反射到取决于打包方式，所以先探测再 hook。
     */
    static void probeLua(final ModuleMain mod, ClassLoader cl) {
        if (cl == null) return;
        Class<?> dbg = null;
        try {
            dbg = Class.forName("LuaInterface.Debugger", false, cl);
        } catch (Throwable ignored) {
            mod.log(Log.INFO, Config.TAG, "LuaInterface.Debugger not found (走 native Unity tag)");
            return;
        }
        mod.log(Log.INFO, Config.TAG, "LuaInterface.Debugger found: " + dbg);
        for (final String name : new String[]{"Log", "LogWarning", "LogError"}) {
            try {
                Method m = dbg.getDeclaredMethod(name, String.class);
                m.setAccessible(true);
                mod.hook(m).intercept(chain -> {
                    Object s = chain.getArg(0);
                    FileSink.log(Log.ERROR, 1, "Lua/" + name, s == null ? "" : s.toString());
                    return chain.proceed();
                });
                mod.log(Log.INFO, Config.TAG, "hooked LuaInterface.Debugger." + name);
            } catch (Throwable t) {
                mod.log(Log.WARN, Config.TAG, "hook " + name + " failed: " + t);
            }
        }
    }

    private LogHooks() {}
}
