package org.aalku.joatse.cloud.tools.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * This main class allows us to use the Switchboard system from the command
 * line.
 * 
 * The process will connect to the specified (switchboard) port and it will tell
 * it to connect to certain tunnel and target, and then it will redirect stdin
 * to the socket and the socket to stdout.
 * 
 * You could use it like this to pass an SSH connection through a tunnel:
 * 
 * <pre>
 * ssh -o 'ProxyCommand java -jar file.jar <switchboard host> <switchboard port> <tunnel UUID> <target id>' <user>@<ssh server> ...
 * </pre>
 * 
 * The <ssh server> is needed in the command line but it's not really used, just to associate the pub key to that name.
 * 
 */
public class SwitchboardExternalClient {

	private static final int BUFFER_SIZE = 1024*128;

	public static void main(String[] args) {
		try {
			String host = args[0];
			int port = Integer.parseInt(args[1], 10);
			UUID uuid = UUID.fromString(args[2]);
			long targetId = Long.parseLong(args[3], 10);

			// Connect
			SocketChannel client = SocketChannel.open();
			client.configureBlocking(true);
			client.connect(new InetSocketAddress(host, port));
			// Say hello
			ByteBuffer hello = ByteBuffer.allocate(16 + 8);
			hello.putLong(uuid.getMostSignificantBits());
			hello.putLong(uuid.getLeastSignificantBits());
			hello.putLong(targetId);
			hello.flip();
			while (hello.hasRemaining()) {
				client.write(hello);
			}
			hello.flip();
			hello = ByteBuffer.allocate(1);
			int x;
			if ((x = client.read(hello)) != 1) {
				throw new IOException("Couldn't read switchboard response: " + x);
			}
			hello.flip();
			if (hello.get() != 0) {
				hello = ByteBuffer.allocate(BUFFER_SIZE);
				while (client.read(hello) >= 0);
				hello.flip();
				hello.limit(hello.limit() - 1); // \0 at the end
				throw new IOException("Negative response from switchboard: " + hello.asCharBuffer().toString());
			}
			hello = null;

			// Two way comm until exception or both ends closed
			CompletableFuture<Void> resOut = copyThread(Channels.newChannel(System.in), client, "thOut");
			CompletableFuture<Void> resIn = copyThread(client, Channels.newChannel(System.out), "thIn");
			CompletableFuture.allOf(resIn, resOut).exceptionally(e->{
				resIn.cancel(true);
				resOut.cancel(true);
				return null;
			}).get();
			
			try {
				client.close();
			} catch (Exception e) {
			}
		} catch (Exception e) {
			e.printStackTrace(System.err);
			System.exit(1);
		}

	}

	private static CompletableFuture<Void> copyThread(ReadableByteChannel in, WritableByteChannel out, String name) {
		CompletableFuture<Void> res = new CompletableFuture<Void>();
		new Thread(name) {
			volatile boolean closed = false;
			
			{
				this.setDaemon(true);
				this.start();
				res.exceptionally(e->{
					try {
						in.close();
					} catch (Exception e1) {
					}
					try {
						out.close();
					} catch (Exception e1) {
					}
					closed = true;
					this.interrupt();
					return null;
				});
			}
			public void run() {
				ByteBuffer buff = ByteBuffer.allocate(BUFFER_SIZE);
				while (true) {
					try {
						int c = (!closed && buff.capacity() > 0) ? in.read(buff) : 0;
						int p = buff.position();
						if (c < 0) {
							closed = true;
							if (p == 0) {
								res.complete(null);
								return;
							}
						}
						if (p > 0) {
							buff.flip();
							out.write(buff);
							buff.compact();
						}
					} catch (IOException e) {
						res.completeExceptionally(e);
						return;
					}
				}
			};
		};
		return res;
	}

}
