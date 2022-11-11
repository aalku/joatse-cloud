package org.aalku.joatse.cloud.tools.io;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardSocketOptions;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.aalku.joatse.cloud.tools.io.AsyncTcpPortListener.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AsyncReadWholeBufferTest {
	
	private AsyncTcpPortListener<Void> portListener;
	private final AtomicReference<Consumer<Event<Void>>> tcpAcceptHandler = new AtomicReference<>();
	private int listenPort;

	@BeforeEach
	void setUp() throws Exception {
		portListener = new AsyncTcpPortListener<Void>(InetAddress.getLoopbackAddress(), 0, null, (Consumer<Event<Void>>) ev -> {
			tcpAcceptHandler.get().accept(ev);
		});
		listenPort = portListener.getAddress().getPort();
	}

	@AfterEach
	void tearDown() throws Exception {
		portListener.close();
	}
	
	@Test
	void basicComm() throws InterruptedException, UnknownHostException, IOException {
		testEcho("Hi all!", 1);  // Force several operations
		testEcho("Hi all!", 2);  // Force several operations
		testEcho("Hi all!", 3);  // Force several operations
		testEcho("Hi all!", 100);  // One operation
	}
	
	@Test
	void asyncReadWholeBuffer() throws UnknownHostException, IOException, InterruptedException, ExecutionException {
		String longMessage = "0123456789012345678901234567890123456789"; // ASCII
		int echoBufferSize = 1; // Small so echo can't write much every time
		int socketInternalReadBufferSize = 3; // Slow
		
		// Read a part
		String messagePart = "012345678901234567890123456789"; // Part to read
		testAsyncReadWholeBuffer(longMessage, echoBufferSize, socketInternalReadBufferSize, messagePart);

		// Now read it whole
		testAsyncReadWholeBuffer(longMessage, echoBufferSize, socketInternalReadBufferSize, longMessage);
		
		// TODO force fails. Try them.
	}

	@Test
	void asyncReadWholeBufferFail1() throws UnknownHostException, IOException, InterruptedException, ExecutionException {
		String longMessage = "0123456789012345678901234567890123456789"; // ASCII
		int echoBufferSize = 1; // Small so echo can't write much every time
		int socketInternalReadBufferSize = 3; // Slow
		
		int messageLenBytes = longMessage.getBytes().length;		
		
		tcpAcceptHandler.set(ev->{
			if (ev.channel == null) {
				return;
			}
			IOTools.asyncEchoUntilEOF(ev.channel, ev.channel, echoBufferSize);
		});
		AsynchronousSocketChannel s = AsynchronousSocketChannel.open();
		s.setOption(StandardSocketOptions.SO_RCVBUF, socketInternalReadBufferSize);
		s.connect(new InetSocketAddress("localhost", listenPort)).get();
		try {
			ByteBuffer bb1 = ByteBuffer.wrap(longMessage
					.getBytes());
			s.write(bb1, null, new CompletionHandler<Integer, Void>() {
				public void completed(Integer result, Void attachment) {
					if (bb1.hasRemaining() && result >= 0) {
						System.out.println("Wrote " + result);
						s.write(bb1, null, this);
					} else {
						try {
							System.out.println("All written");
							s.shutdownOutput();
						} catch (IOException e) {
						}
					}
				}
				public void failed(Throwable exc, Void attachment) {
					System.err.println("Failed writting");
					try {
						s.close();
					} catch (IOException e) {
					}
				}
			});
			// Not sure if write is complete at this point. Doesn't matter as it's async
			
			// We will ask for 1 more byte that the amount really sent to the echo socket
			ByteBuffer bb2 = ByteBuffer.allocate(messageLenBytes + 1); // bytes + 1
			CompletableFuture<Void> res = IOTools.asyncReadWholeBuffer(s, bb2).thenAccept(x->{
				try {
					Assertions.assertEquals(new String(bb2.array(), "ASCII"), longMessage);
					System.out.println("Match!");
				} catch (UnsupportedEncodingException e) {
					throw new RuntimeException(e);
				}
			}).exceptionally(e->{
				throw new RuntimeException(e);
			}); // Wait and get exceptions
			ExecutionException thrown = Assertions.assertThrows(ExecutionException.class, () -> {
				res.get();
			});
			Assertions.assertEquals(RuntimeException.class, thrown.getCause().getClass());
			Assertions.assertEquals(CompletionException.class, thrown.getCause().getCause().getClass());
			Throwable ioException = thrown.getCause().getCause().getCause();
			Assertions.assertEquals(IOException.class, ioException.getClass());
			Assertions.assertTrue(()->ioException.getMessage().startsWith("Error while reading"), "Exception expected different message: " + ioException);
		} finally {
			s.close();
		}
	}

	private void testAsyncReadWholeBuffer(String longMessage, int echoBufferSize, int socketInternalReadBufferSize, String messagePart)
			throws IOException, InterruptedException, ExecutionException {
		int messagePartLenBytes = messagePart.getBytes().length;		
		tcpAcceptHandler.set(ev->{
			if (ev.channel == null) {
				return;
			}
			IOTools.asyncEchoUntilEOF(ev.channel, ev.channel, echoBufferSize);
		});
		AsynchronousSocketChannel s = AsynchronousSocketChannel.open();
		s.setOption(StandardSocketOptions.SO_RCVBUF, socketInternalReadBufferSize);
		s.connect(new InetSocketAddress("localhost", listenPort)).get();
		try {
			ByteBuffer bb1 = ByteBuffer.wrap(longMessage
					.getBytes());
			s.write(bb1, null, new CompletionHandler<Integer, Void>() {
				public void completed(Integer result, Void attachment) {
					if (bb1.hasRemaining() && result >= 0) {
						System.out.println("Wrote " + result);
						s.write(bb1, null, this);
					} else {
						try {
							System.out.println("All written");
							s.shutdownOutput();
						} catch (IOException e) {
						}
					}
				}
				public void failed(Throwable exc, Void attachment) {
					System.err.println("Failed writting");
					try {
						s.close();
					} catch (IOException e) {
					}
				}
			});
			// Not sure if write is complete at this point. Doesn't matter as it's async
			ByteBuffer bb2 = ByteBuffer.allocate(messagePartLenBytes);
			IOTools.asyncReadWholeBuffer(s, bb2).thenAccept(x->{
				try {
					Assertions.assertEquals(new String(bb2.array(), "ASCII"), messagePart);
					System.out.println("Match!");
				} catch (UnsupportedEncodingException e) {
					throw new RuntimeException(e);
				}
			}).exceptionally(e->{
				Assertions.fail(e);
				return null;
			}).get(); // Wait and get exceptions
		} finally {
			s.close();
		}
	}

	/**
	 * Connect to echo server and send a string to it and check it returns the same
	 * string and then EOF. We can play with bufferSize smaller than the message. 
	 */
	private void testEcho(String message, int bufferSize) throws UnknownHostException, IOException {
		tcpAcceptHandler.set(ev->{
			if (ev.channel == null) {
				return;
			}
			IOTools.asyncEchoUntilEOF(ev.channel, ev.channel, bufferSize);
		});
		Socket s = new Socket("localhost", listenPort);
		String answerUntilEof;
		try {
			s.getOutputStream().write(message.getBytes()); // Less than socket internal buffer (not the app level
															// byteBuffer) or otherwise it would block here
															// and we would need another thread to read while writing
			s.shutdownOutput();
			answerUntilEof = new String(s.getInputStream().readNBytes(100));
		} finally {
			s.close();
		}
		Assertions.assertEquals(message, answerUntilEof);
	}
}
