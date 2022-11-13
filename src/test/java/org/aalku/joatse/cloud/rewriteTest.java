package org.aalku.joatse.cloud;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.CharBuffer;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.aalku.joatse.cloud.tools.io.IOTools;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class rewriteTest {
	
	@Test
	final void test1() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		PrintWriter out = new PrintWriter(baos);
		String contentString = "Hello world!!!";
		int bufferSize = contentString.length() + 10;
		
		String result = replaceAll(baos, out, contentString, bufferSize, "l", m->"I");
		System.out.println(result);
		Assertions.assertEquals(contentString.replaceAll("l", "I"), result);
	}
	
	@Test
	final void test2() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		PrintWriter out = new PrintWriter(baos);
		String contentString = "Hello world!!!";
		int bufferSize = 4;
		
		String result = replaceAll(baos, out, contentString, bufferSize, "l", m->"I");
		System.out.println(result);
		Assertions.assertEquals(contentString.replaceAll("l", "I"), result);
	}

	@Test
	final void test3() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		PrintWriter out = new PrintWriter(baos);
		String contentString = "Hello world!!!";
		int bufferSize = 4;
		
		String result = replaceAll(baos, out, contentString, bufferSize, "lo", m->"LO");
		System.out.println(result);
		Assertions.assertEquals(contentString.replaceAll("lo", "LO"), result);
	}

	private String replaceAll(ByteArrayOutputStream baos, PrintWriter out, String contentString, int bufferSize,
			String regex, Function<String, String> replace) {
		System.out.println(
				String.format("Replacing %s->%s on %s with buffer size %d", regex, replace, contentString, bufferSize));
		CharBuffer contentSource = CharBuffer.wrap(contentString);
		CharBuffer buffer = CharBuffer.allocate(bufferSize);
		Pattern pattern = Pattern.compile(regex);
		while (contentSource.remaining() > 0) {
			int chunkSize = Math.min(buffer.remaining(), contentSource.remaining());
			buffer.append(contentSource.subSequence(0, chunkSize)); // Don't flip!!
			contentSource.position(contentSource.position() + chunkSize);
			IOTools.rewriteStringContent(buffer, out, contentSource.remaining() == 0, pattern, replace);
		}
		String result = new String(baos.toByteArray());
		return result;
	}


}
