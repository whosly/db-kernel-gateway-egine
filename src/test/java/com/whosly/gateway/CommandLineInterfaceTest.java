package com.whosly.gateway;

import com.whosly.gateway.adapter.ProtocolAdapter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandLineInterfaceTest {

    @Test
    void nonInteractiveDoesNotReadStdinOrStartGateway() throws Exception {
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        CommandLineInterface cli = new CommandLineInterface(adapter);
        cli.setInteractiveCli(false);

        InputStream originalIn = System.in;
        System.setIn(new ByteArrayInputStream("start\nquit\n".getBytes(StandardCharsets.UTF_8)));
        try {
            long started = System.nanoTime();
            cli.run();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(elapsedMs).isLessThan(2_000);
            verify(adapter, never()).start();
            verify(adapter, never()).stop();
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void interactiveQuitExitsWithoutStartingWhenAlreadyIdle() throws Exception {
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(adapter.isRunning()).thenReturn(false);
        CommandLineInterface cli = new CommandLineInterface(adapter);
        cli.setInteractiveCli(true);

        InputStream originalIn = System.in;
        System.setIn(new ByteArrayInputStream("status\nquit\n".getBytes(StandardCharsets.UTF_8)));
        try {
            cli.run();
            verify(adapter, never()).start();
        } finally {
            System.setIn(originalIn);
        }
    }
}
