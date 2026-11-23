package com.azurlane.capture;

import android.util.Log;

import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.Charset;

/** java.io.PrintStream（system.out / system.err）捕获。 */
final class StreamHooks {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    static void install(ModuleMain mod) throws Throwable {
        Method write = PrintStream.class.getDeclaredMethod(
                "write", byte[].class, int.class, int.class);
        mod.hook(write).intercept(chain -> {
            Object self = chain.getThisObject();
            boolean isOut = self == System.out;
            boolean isErr = self == System.err;
            if (isOut || isErr) {
                Object a0 = chain.getArg(0);
                Object a1 = chain.getArg(1);
                Object a2 = chain.getArg(2);
                if (a0 instanceof byte[] && a1 instanceof Integer && a2 instanceof Integer) {
                    byte[] b = (byte[]) a0;
                    int off = (Integer) a1;
                    int len = (Integer) a2;
                    if (len > 0 && off >= 0 && off + len <= b.length) {
                        int n = Math.min(len, Config.MAX_LINE);
                        String text = new String(b, off, n, UTF8);
                        FileSink.log(isOut ? Log.INFO : Log.ERROR, 1,
                                isOut ? "System.out" : "System.err", text);
                    }
                }
            }
            return chain.proceed();
        });
    }

    private StreamHooks() {}
}
