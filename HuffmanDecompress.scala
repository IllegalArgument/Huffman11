import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Path, Paths}

object HuffmanDecompress {
	import HuffmanDictionaries.Dictionaries

	val ChunkSize = 0x1000

	def codeLength(code: Int): Int = {
		if ((code >>> 25) >= 0x77) {
			7
		} else if ((code >>> 24) >= 0xA3) {
			8
		} else if ((code >>> 23) >= 0xBC) {
			9
		} else if ((code >>> 22) >= 0xCB) {
			10
		} else if ((code >>> 21) >= 0xD8) {
			11
		} else if ((code >>> 20) >= 0xDF) {
			12
		} else if ((code >>> 19) >= 0xCA) {
			13
		} else if ((code >>> 18) >= 0x50) {
			14
		} else {
			15
		}
	}

	def decompress(file: Path, decompressedSize: Int): Array[Byte] = {
		val fileBytes = Files.readAllBytes(file)
		val chunkCount = decompressedSize / ChunkSize
		val headerSize = 0x4 * chunkCount
		val headerBuffer = ByteBuffer.wrap(fileBytes.take(headerSize)).order(ByteOrder.LITTLE_ENDIAN)
		// The extra four bytes of padding is needed so that getInt below won't underflow
		val compressedBuffer = ByteBuffer.wrap(fileBytes.drop(headerSize) ++ Array.ofDim[Byte](4)).order(ByteOrder.BIG_ENDIAN)
		val decompressedBuffer = ByteBuffer.allocate(decompressedSize).order(ByteOrder.BIG_ENDIAN)
		val chunks = List.fill(chunkCount)({
			val entry = headerBuffer.getInt()
			(entry & 0x1FFFFFF, (entry >>> 25) & 0x7F)
		})
		chunks.zipWithIndex.foreach({
			case ((offset, dictionaryType), index) =>
				println(f"==Processing chunk 0x$index%X at compressed offset 0x$offset%X with dictionary 0x$dictionaryType%X==")
				val dictionary = Dictionaries(dictionaryType)
				compressedBuffer.position(offset)
				decompressedBuffer.position(index * ChunkSize).limit((index + 1) * ChunkSize)
				var bitBuffer = compressedBuffer.getInt()
				var availableBits = 32
				def nextCode(): (Int, Int) = {
					if (availableBits < codeLength(bitBuffer)) {
						val next = compressedBuffer.getInt()
						bitBuffer |= next >>> availableBits
						val length = codeLength(bitBuffer)
						val code = (bitBuffer >>> (32 - length)) & 0xFFFF
						bitBuffer = next << (length - availableBits)
						availableBits += 32 - length
						(length, code)
					} else {
						val length = codeLength(bitBuffer)
						val code = (bitBuffer >>> (32 - length)) & 0xFFFF
						bitBuffer <<= length
						availableBits -= length
						(length, code)
					}
				}
				while (decompressedBuffer.hasRemaining) {
					val (length, code) = nextCode()
					val symbol = dictionary(length).getOrElse(code, {
						println(f"Unknown code ${s"%${length}s".format(code.toBinaryString).replace(' ', '0')} (dictionary 0x$dictionaryType%X, length $length, code 0x$code%X) at decompressed offset 0x${decompressedBuffer.position}%X")
						// 0x7F was arbitrarily chosen as a filler byte
						Array.fill(1)(0x7F.toByte)
					})
					if (decompressedBuffer.remaining >= symbol.length) {
						decompressedBuffer.put(symbol)
					} else {
						println(f"Ignoring unaligned last code ${s"%${length}s".format(code.toBinaryString).replace(' ', '0')} (dictionary 0x$dictionaryType%X, length $length, code 0x$code%X) at decompressed offset 0x${decompressedBuffer.position}%X")
						decompressedBuffer.put(Array.fill(decompressedBuffer.remaining)(0x7F.toByte))
					}
				}
		})
		decompressedBuffer.array()
	}

	def main(args: Array[String]): Unit = {
		if (args.length == 3) {
			val compressedFile = Paths.get(args(0))
			val decompressedFile = Paths.get(args(1))
			val decompressedSize = Integer.decode(args(2))
			val decompressed = decompress(compressedFile, decompressedSize)
			Files.write(decompressedFile, decompressed)
		} else {
			println("Usage: HuffmanDecompress <compressed file path> <decompressed file path> <decompressed file size>")
			println("Example: HuffmanDecompress bup.huff bup.mod 0x31000")
		}
	}
}
