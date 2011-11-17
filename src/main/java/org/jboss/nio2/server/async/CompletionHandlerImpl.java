/**
 * JBoss, Home of Professional Open Source. Copyright 2011, Red Hat, Inc., and
 * individual contributors as indicated by the @author tags. See the
 * copyright.txt file in the distribution for a full listing of individual
 * contributors.
 * 
 * This is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 * 
 * This software is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this software; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA, or see the FSF
 * site: http://www.fsf.org.
 */
package org.jboss.nio2.server.async;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Future;

import org.jboss.logging.Logger;

/**
 * {@code CompletionHandlerImpl}
 * 
 * Created on Nov 16, 2011 at 8:59:51 PM
 * 
 * @author <a href="mailto:nbenothm@redhat.com">Nabil Benothman</a>
 */
class CompletionHandlerImpl implements CompletionHandler<Integer, AsynchronousSocketChannel> {

	/**
	 * 
	 */
	public static final String CRLF = "\r\n";
	private static final Logger logger = Logger.getLogger(CompletionHandler.class.getName());
	private String sessionId;
	private ByteBuffer readBuffer;
	private ByteBuffer writeBuffer;

	/**
	 * Create a new instance of {@code CompletionHandlerImpl}
	 * 
	 * @param sessionId
	 * @param byteBuffer
	 */
	public CompletionHandlerImpl(String sessionId, ByteBuffer byteBuffer) {
		this.sessionId = sessionId;
		this.readBuffer = byteBuffer;
		this.writeBuffer = ByteBuffer.allocate(8 * 1024);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.nio.channels.CompletionHandler#completed(java.lang.Object,
	 * java.lang.Object)
	 */
	@Override
	public void completed(Integer nBytes, AsynchronousSocketChannel channel) {
		if (nBytes > 0) {
			readBuffer.flip();
			byte bytes[] = new byte[nBytes];
			readBuffer.get(bytes);
			readBuffer.clear();
			System.out.println("[" + this.sessionId + "] " + new String(bytes).trim());

			try {
				// write response to client
				writeResponse(channel);
			} catch (Exception e) {
				logger.error(e.getMessage(), e);
				e.printStackTrace();
			}
		}
		// Read again with the this CompletionHandler
		channel.read(readBuffer, Nio2AsyncServer.TIMEOUT, Nio2AsyncServer.TIME_UNIT, channel, this);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.nio.channels.CompletionHandler#failed(java.lang.Throwable,
	 * java.lang.Object)
	 */
	@Override
	public void failed(Throwable exc, AsynchronousSocketChannel channel) {
		System.out.println("[" + this.sessionId + "] Operation failed");
		exc.printStackTrace();
		try {
			System.out.println("[" + this.sessionId + "] Closing remote connection");
			channel.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Write the response to client
	 * 
	 * @param channel
	 *            the {@code AsynchronousSocketChannel} channel to which write
	 * @throws Exception
	 */
	protected void writeResponse(AsynchronousSocketChannel channel) throws Exception {

		File file = new File("data" + File.separatorChar + "file.txt");
		Path path = FileSystems.getDefault().getPath(file.getAbsolutePath());
		// SeekableByteChannel sbc = null;
		RandomAccessFile raf = new RandomAccessFile(file, "r");
		FileChannel fileChannel = raf.getChannel();

		final int BUFFER_SIZE = 8 * 1024;

		try {
			// sbc = Files.newByteChannel(path, StandardOpenOption.READ);
			long fileLength = fileChannel.size();
			double tmp = 1 + ((double) fileLength / BUFFER_SIZE);
			int x = (int) tmp;
			int length = (tmp - x > 0) ? x + 1 : x;
			ByteBuffer buffers[] = new ByteBuffer[length];

			for (int i = 0; i < buffers.length - 1; i++) {
				buffers[i] = ByteBuffer.allocate(BUFFER_SIZE);
			}
			buffers[buffers.length - 1] = ByteBuffer.allocate(16);
			// Read the whole file in one pass
			fileChannel.read(buffers);
			// Add the CRLF chars to the buffers
			buffers[buffers.length - 1].put(CRLF.getBytes());
			// Write the file content to the channel
			write(channel, buffers, buffers.length);
		} catch (Exception exp) {
			logger.error("Exception: " + exp.getMessage(), exp);
			exp.printStackTrace();
		} finally {
			fileChannel.close();
			raf.close();
		}
	}

	/**
	 * 
	 * @param channel
	 * @param buffers
	 * @param length
	 * @throws Exception
	 */
	protected void write(AsynchronousSocketChannel channel, ByteBuffer[] buffers, int length)
			throws Exception {

		for (int i = 0; i < length; i++) {
			buffers[i].flip();
		}

		channel.write(buffers, 0, length, Nio2AsyncServer.TIMEOUT, Nio2AsyncServer.TIME_UNIT, null,
				new CompletionHandler<Long, Void>() {
					@Override
					public void completed(Long result, Void attachment) {
						// Nothing to do
					}

					@Override
					public void failed(Throwable exc, Void attachment) {
						// Nothing to do
					}
				});

	}

	/**
	 * Write the byte buffer to the specified channel
	 * 
	 * @param channel
	 *            the {@code AsynchronousSocketChannel} channel
	 * @param byteBuffer
	 *            the data that will be written to the channel
	 * @throws IOException
	 */
	protected void write(AsynchronousSocketChannel channel, ByteBuffer byteBuffer) throws Exception {
		byteBuffer.flip();
		Future<Integer> count = channel.write(byteBuffer);
		int written = count.get();
		byteBuffer.clear();
	}
}
