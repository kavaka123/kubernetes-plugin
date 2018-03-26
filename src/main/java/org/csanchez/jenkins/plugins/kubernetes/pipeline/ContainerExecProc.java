package org.csanchez.jenkins.plugins.kubernetes.pipeline;

import static org.csanchez.jenkins.plugins.kubernetes.pipeline.Constants.*;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Charsets;

import hudson.Proc;
import io.fabric8.kubernetes.client.dsl.ExecWatch;

/**
 * Handle the liveness of the processes executed in containers, wait for them to finish and process exit codes.
 *
 */
public class ContainerExecProc extends Proc implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(ContainerExecProc.class.getName());

    private static final Pattern EXIT_CODE = Pattern.compile(
            "command terminated with non-zero exit code: Error executing in Docker Container: (?<exitCode>\\d+)");

    private final AtomicBoolean alive;
    private final CountDownLatch finished;
    private final ExecWatch watch;
    private final ByteArrayOutputStream error;

    @Deprecated
    public ContainerExecProc(ExecWatch watch, AtomicBoolean alive, CountDownLatch finished,
            Callable<Integer> exitCode) {
        this(watch, alive, finished, new ByteArrayOutputStream());
    }

    public ContainerExecProc(ExecWatch watch, AtomicBoolean alive, CountDownLatch finished,
            ByteArrayOutputStream error) {
        this.watch = watch;
        this.alive = alive;
        this.finished = finished;
        this.error = error;
    }

    @Override
    public boolean isAlive() throws IOException, InterruptedException {
        return alive.get();
    }

    @Override
    public void kill() throws IOException, InterruptedException {
        try {
            // What we actually do is send a ctrl-c to the current process and then exit the shell.
            watch.getStdinPipe().write(CTRL_C);
            watch.getStdinPipe().write(EXIT.getBytes(StandardCharsets.UTF_8));
            watch.getStdinPipe().write(NEWLINE.getBytes(StandardCharsets.UTF_8));
            watch.getStdinPipe().flush();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Proc kill failed, ignoring", e);
        } finally {
            close();
        }
    }

    @Override
    public int join() throws IOException, InterruptedException {
        try {
            LOGGER.log(Level.FINEST, "Waiting for websocket to close on command finish ({0})", finished);
            finished.await();
            LOGGER.log(Level.FINEST, "Command is finished ({0})", finished);
            // command terminated with non-zero exit code: Error executing in Docker Container: 127
            String s = error.toString(Charsets.UTF_8.name());
            Matcher m = EXIT_CODE.matcher(s);
            if (m.matches()) {
                return Integer.parseInt(m.group("exitCode"));
            } else {
                LOGGER.log(Level.WARNING, "Unable to parse exit code from error message: {0}", s);
                return -1;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error getting exit code", e);
            return -1;
        } finally {
            close();
        }
    }

    @Override
    public InputStream getStdout() {
        return watch.getStdoutPipe();
    }

    @Override
    public InputStream getStderr() {
        return watch.getStderrPipe();
    }

    @Override
    public OutputStream getStdin() {
        return watch.getInput();
    }

    @Override
    public void close() throws IOException {
        try {
            // We are calling explicitly close, in order to cleanup websockets and threads (are not closed implicitly).
            watch.close();
        } catch (Exception e) {
            LOGGER.log(Level.INFO, "failed to close watch", e);
        }
    }
}
