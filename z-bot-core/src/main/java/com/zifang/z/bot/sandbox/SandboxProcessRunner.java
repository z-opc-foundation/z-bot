package com.zifang.z.bot.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.*;

/**
 * 沙箱内进程执行与输出采集。
 */
final class SandboxProcessRunner {

    private SandboxProcessRunner() {
    }

    static SandboxExecResult run(
            List<String> command,
            File workingDirectory,
            long timeoutSeconds,
            int maxCaptureBytes) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDirectory);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<byte[]> readFuture = pool.submit(new DrainTask(process.getInputStream(), maxCaptureBytes));
            Future<Integer> waitFuture = pool.submit(new WaitTask(process));
            Integer exit;
            try {
                exit = waitFuture.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                process.destroyForcibly();
                byte[] partial = readFuture.get(3, TimeUnit.SECONDS);
                return new SandboxExecResult(-1, new String(partial, Charset.defaultCharset()), true);
            }
            byte[] outBytes = readFuture.get(10, TimeUnit.SECONDS);
            int code = exit == null ? -1 : exit.intValue();
            return new SandboxExecResult(code, new String(outBytes, Charset.defaultCharset()), false);
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            throw new IOException("sandbox process failed: " + e.getMessage(), e);
        } finally {
            pool.shutdownNow();
        }
    }

    private static final class DrainTask implements java.util.concurrent.Callable<byte[]> {
        private final InputStream in;
        private final int max;

        DrainTask(InputStream in, int max) {
            this.in = in;
            this.max = max;
        }

        @Override
        public byte[] call() throws Exception {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                int allow = Math.min(n, max - total);
                if (allow > 0) {
                    bos.write(buf, 0, allow);
                    total += allow;
                }
                if (total >= max) {
                    break;
                }
            }
            return bos.toByteArray();
        }
    }

    private static final class WaitTask implements java.util.concurrent.Callable<Integer> {
        private final Process process;

        WaitTask(Process process) {
            this.process = process;
        }

        @Override
        public Integer call() throws Exception {
            return process.waitFor();
        }
    }
}
