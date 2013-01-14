/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.util.Arrays;
import net.i2p.util.NativeBigInteger;
import freenet.crypt.ciphers.Rijndael;
import freenet.support.HexUtil;
import freenet.support.Loader;

public class Util {

	// bah, i'm tired of chasing down dynamically loaded classes..
	// this is for getCipherByName()
	static {
		Rijndael.class.toString();
	}

	protected static final int BUFFER_SIZE = 32768;

	public static void fillByteArrayFromInts(int[] ints, byte[] bytes) {
		int ic = 0;
		for (int i = 0; i < ints.length; i++) {
			bytes[ic++] = (byte) (ints[i] >> 24);
			bytes[ic++] = (byte) (ints[i] >> 16);
			bytes[ic++] = (byte) (ints[i] >> 8);
			bytes[ic++] = (byte) ints[i];
		}
	}

	public static void fillByteArrayFromLongs(long[] ints, byte[] bytes) {
		int ic = 0;
		for (int i = 0; i < ints.length; i++) {
			bytes[ic++] = (byte) (ints[i] >> 56);
			bytes[ic++] = (byte) (ints[i] >> 48);
			bytes[ic++] = (byte) (ints[i] >> 40);
			bytes[ic++] = (byte) (ints[i] >> 32);
			bytes[ic++] = (byte) (ints[i] >> 24);
			bytes[ic++] = (byte) (ints[i] >> 16);
			bytes[ic++] = (byte) (ints[i] >> 8);
			bytes[ic++] = (byte) ints[i];
		}
	}

	// Crypto utility methods:
	public static final NativeBigInteger TWO = new NativeBigInteger(BigInteger.valueOf(2));

	// we should really try reading the JFC documentation sometime..
	// - the byte array generated by BigInteger.toByteArray() is
	//   compatible with the BigInteger(byte[]) constructor
	// - the byte length is ceil((bitLength()+1) / 8)

	public static byte[] MPIbytes(BigInteger num) {
		int len = num.bitLength();
		byte[] bytes = new byte[2 + ((len + 8) >> 3)];
		System.arraycopy(num.toByteArray(), 0, bytes, 2, bytes.length - 2);
		bytes[0] = (byte) (len >> 8);
		bytes[1] = (byte) len;
		return bytes;
	}

	public static void writeMPI(BigInteger num, OutputStream out)
		throws IOException {
		out.write(MPIbytes(num));
	}

	public static BigInteger readMPI(InputStream in) throws IOException {
		int b1 = in.read();
		int b2 = in.read();
		if ((b1 == -1) || (b2 == -1))
			throw new EOFException();
		byte[] data = new byte[(((b1 << 8) + b2) + 8) >> 3];
		readFully(in, data, 0, data.length);
		//(new DataInputStream(in)).readFully(data, 0, data.length);
		// REDFLAG: This can't possibly be negative, right?
		return new NativeBigInteger(1, data);
	}

	public static byte[] hashBytes(MessageDigest d, byte[] b) {
		return hashBytes(d, b, 0, b.length);
	}

	public static byte[] hashBytes(
		MessageDigest d,
		byte[] b,
		int offset,
		int length) {
		d.update(b, offset, length);
		return d.digest();
	}

	/**
	 * Hashes a string in a consistent manner
	 */
	public static byte[] hashString(MessageDigest d, String s) {
		try {
			byte[] sbytes = s.getBytes("UTF-8");
			d.update(sbytes, 0, sbytes.length);
			return d.digest();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	public static byte[] xor(byte[] b1, byte[] b2) {
		int maxl = Math.max(b1.length, b2.length);
		byte[] rv = new byte[maxl];

		int minl = Math.min(b1.length, b2.length);
		for (int i = 0; i < minl; i++)
			rv[i] = (byte) (b1[i] ^ b2[i]);
		return rv;
	}
	
	//Compares two byte arrays. Consider using Arrays.equals(a,b) instead of this
	//if you have two method if you have two equally sized arrays
	public static boolean byteArrayEqual(byte[] a, byte[] b, int offset, int length) {
		int lim = offset + length;
		if ((a.length < lim) || (b.length < lim))
			return false;
		for (int i = offset; i < lim; ++i)
			if (a[i] != b[i])
				return false;
		return true;
	}

	private static final MessageDigest ctx;
	private static final int ctx_length;

	static {
		try {
			ctx = MessageDigest.getInstance("SHA1");
			ctx_length = ctx.getDigestLength();
		} catch(NoSuchAlgorithmException e) {
			// impossible
			throw new Error(e);
		}
	}

	public static void makeKey(
		byte[] entropy,
		byte[] key,
		int offset,
		int len) {
		try {
		synchronized (ctx) {
			ctx.digest(); // reinitialize

			int ic = 0;
			while (len > 0) {
				ic++;
				for (int i = 0; i < ic; i++)
					ctx.update((byte) 0);
				ctx.update(entropy, 0, entropy.length);
				int bc;
				if (len > ctx_length) {
					ctx.digest(key, offset, ctx_length);
					bc = ctx_length;
				} else {
					byte[] hash = ctx.digest();
					bc = Math.min(len, hash.length);
					System.arraycopy(hash, 0, key, offset, bc);
				}
				offset += bc;
				len -= bc;
			}
		}
		Arrays.fill(entropy, (byte) 0);
		} catch(DigestException e) {
			// impossible
			throw new Error(e);
		}
	}

	public static BlockCipher getCipherByName(String name) {
		//throws UnsupportedCipherException {
		try {
			return (BlockCipher) Loader.getInstance(
				"freenet.crypt.ciphers." + name);
		} catch (Exception e) {
			//throw new UnsupportedCipherException(""+e);
			e.printStackTrace();
			return null;
		}
	}

	public static BlockCipher getCipherByName(String name, int keySize) {
		//throws UnsupportedCipherException {
		try {
			return (BlockCipher) Loader.getInstance(
				"freenet.crypt.ciphers." + name,
				new Class<?>[] { Integer.class },
				new Object[] { Integer.valueOf(keySize)});
		} catch (Exception e) {
			//throw new UnsupportedCipherException(""+e);
			e.printStackTrace();
			return null;
		}
	}

	public static void main(String[] args) throws Exception {
		if ((args.length == 0) || args[0].equals("write")) {
			writeMPI(new BigInteger("9"), System.out);
			writeMPI(new BigInteger("1234567890123456789"), System.out);
			writeMPI(new BigInteger("100200300400500600700800900"), System.out);
		} else if (args[0].equals("read")) {
			System.out.println("9");
			System.out.println(readMPI(System.in));
			System.out.println("1234567890123456789");
			System.out.println(readMPI(System.in));
			System.out.println("100200300400500600700800900");
			System.out.println(readMPI(System.in));
		} else if (args[0].equals("write-mpi")) {
			writeMPI(new BigInteger(args[1]), System.out);
		} else if (args[0].equals("read-mpi")) {
			System.err.println(readMPI(System.in));
		} else if (args[0].equals("keygen")) {
			byte[] entropy = readMPI(System.in).toByteArray();
			byte[] key =
				new byte[(args.length > 1 ? Integer.parseInt(args[1]) : 16)];
			makeKey(entropy, key, 0, key.length);
			System.err.println(HexUtil.bytesToHex(key, 0, key.length));
		} else if (args[0].equals("shatest")) {
			synchronized (ctx) {
				ctx.digest();
				ctx.update((byte) 'a');
				ctx.update((byte) 'b');
				ctx.update((byte) 'c');
				System.err.println(HexUtil.bytesToHex(ctx.digest()));
			}
		}
	}

	/**
	 * @return log2 of n, rounded up to the nearest integer
	 */
	public static int log2(long n) {
		int log2 = 0;
		while ((log2 < 63) && (1L << log2 < n))
			++log2;
		return log2;
	}

	public static void readFully(InputStream in, byte[] b) throws IOException {
		readFully(in, b, 0, b.length);
	}

	public static void readFully(InputStream in, byte[] b, int off, int length)
		throws IOException {
		int total = 0;
		while (total < length) {
			int got = in.read(b, off + total, length - total);
			if (got == -1) {
				throw new EOFException();
			}
			total += got;
		}
	}

}
